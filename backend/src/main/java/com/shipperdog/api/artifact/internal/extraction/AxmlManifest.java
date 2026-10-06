package com.shipperdog.api.artifact.internal.extraction;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A minimal, bounded reader of Android binary XML (AXML) — issue #22,
 * S5-03, FR-005. Both artifact formats ShipperDog must parse carry
 * their manifest in this one format: an APK's root {@code
 * AndroidManifest.xml} and an AAB's {@code base/manifest/
 * AndroidManifest.xml} are identical chunk streams, so a single walker
 * serves both parsers' needs.
 *
 * <p>Why in-house rather than a library (decision recorded in
 * {@code docs/artifacts.md}): the OSS options are whole-file oriented
 * ({@code net.dongliu:apk-parser}, untouched since 2020) or execution
 * oriented (bundletool, dragging protobuf and guava), while this seam
 * needs a bounded reader over the exact byte slice {@link ZipRanges}
 * already fetched — the same reasoning that produced the in-house
 * ZIP walker. The format is small and frozen: a file chunk wrapping a
 * string pool plus start/end element chunks whose attributes carry
 * typed values. Attribute identity is by NAME STRING ("package",
 * "versionCode", ...), so the optional resource-id map is never
 * consulted.
 *
 * <p>Every read is bounds-checked against the chunk it belongs to and
 * the element/attribute/string-count ceilings below — a damaged or
 * hostile manifest answers {@code MALFORMED_MANIFEST} with a specific
 * message, never an allocation or a loop. The byte slice itself is
 * already capped by the extraction entry ceiling before this class
 * sees it.
 */
final class AxmlManifest {

    /** {@code 0x0003} — the AXML file chunk that wraps everything. */
    private static final int XML_FILE_TYPE = 0x0003;

    /** {@code 0x0001} — the string pool chunk. */
    private static final int STRING_POOL_TYPE = 0x0001;

    /** {@code 0x0102} — a start-element chunk. */
    private static final int START_ELEMENT_TYPE = 0x0102;

    /** The fixed width of the pool header (before the offset array). */
    private static final int POOL_FIXED = 28;

    /** The fixed width of an element chunk's node header. */
    private static final int ELEMENT_HEADER = 16;

    /** The fixed width of the attribute extension of an element chunk. */
    private static final int ATTR_EXT = 20;

    /** The fixed width of one attribute. */
    private static final int ATTRIBUTE_FIXED = 20;

    /** The string-index every "absent" field carries. */
    private static final int NO_STRING = 0xFFFFFFFF;

    /** String-pool flags: bit 8 marks UTF-8 encoding. */
    private static final int UTF8_FLAG = 1 << 8;

    /** Typed-value encodings: the string pool indexes the data field. */
    private static final int TYPE_STRING = 0x03;

    /** Typed-value encodings: a 32-bit decimal integer. */
    private static final int TYPE_INT_DEC = 0x10;

    /** Typed-value encodings: a 32-bit hexadecimal integer. */
    private static final int TYPE_INT_HEX = 0x11;

    /** Typed-value encodings: a boolean. */
    private static final int TYPE_BOOLEAN = 0x12;

    /** Element ceiling of one manifest — real manifests are tens deep. */
    private static final int MAX_ELEMENTS = 2048;

    /** Attribute ceiling of one element — real manifests carry a handful. */
    private static final int MAX_ATTRIBUTES = 256;

    /** String ceiling of one pool; the slice is entry-capped regardless. */
    private static final int MAX_STRINGS = 100_000;

    private final List<Element> elements;

    private AxmlManifest(List<Element> elements) {
        this.elements = elements;
    }

    /** One parsed start element: its name and decodable attributes. */
    record Element(String name, List<Attribute> attributes) {

        /** Attribute pairs in document order; values never null. */
        Element {
            assert name != null;
        }
    }

    /** One attribute as this walker surfaces it: a name and a string value. */
    record Attribute(String name, String value) {}

    /**
     * Walks the whole manifest slice. Structural damage — a bad chunk
     * header, an offset outside its chunk, a missing or duplicated
     * string pool, an element without a name — answers
     * MALFORMED_MANIFEST naming what broke.
     */
    static AxmlManifest parse(byte[] axml) {
        if (axml.length < 8 || u16(axml, 0) != XML_FILE_TYPE) {
            throw malformed("the byte slice is not an Android binary XML document");
        }
        int headerSize = u16(axml, 2);
        long declared = u32(axml, 4);
        if (headerSize < 8 || declared != axml.length) {
            throw malformed("the document declares " + declared + " bytes but the entry carries " + axml.length);
        }
        List<String> strings = null;
        List<Element> elements = new ArrayList<>();
        int position = headerSize;
        while (position < axml.length) {
            if (position + 8 > axml.length) {
                throw malformed("a chunk header overruns the document at offset " + position);
            }
            int type = u16(axml, position);
            int chunkHeader = u16(axml, position + 2);
            long chunkSize = u32(axml, position + 4);
            if (chunkHeader < 8 || chunkSize < chunkHeader || position + chunkSize > axml.length) {
                throw malformed("the chunk at offset " + position + " (type 0x" + Integer.toHexString(type)
                        + ") does not fit the document");
            }
            if (type == STRING_POOL_TYPE) {
                if (strings != null) {
                    throw malformed("the document carries more than one string pool");
                }
                strings = parseStrings(axml, position, (int) chunkSize);
            } else if (type == START_ELEMENT_TYPE) {
                if (strings == null) {
                    throw malformed("an element chunk appears before the string pool");
                }
                if (elements.size() >= MAX_ELEMENTS) {
                    throw malformed("the document exceeds the " + MAX_ELEMENTS + "-element ceiling");
                }
                elements.add(parseElement(axml, position, (int) chunkSize, chunkHeader, strings));
            }
            // Namespace, end-element, and resource-map chunks carry
            // nothing this walker needs; unknown chunk types are
            // skipped whole — forward compatibility is the format's own
            // rule, and every skip stays inside the validated extent.
            position += (int) chunkSize;
        }
        return new AxmlManifest(elements);
    }

    /** The document's elements, in order. */
    List<Element> elements() {
        return Collections.unmodifiableList(elements);
    }

    // -------------------------------------------------------------------------
    // String pool
    // -------------------------------------------------------------------------

    private static List<String> parseStrings(byte[] axml, int position, int chunkEnd) {
        // The pool's fixed header alone is 28 bytes; a chunk declaring
        // less cannot carry even stringCount/flags/stringsStart, and
        // reading those fields anyway could index past the document
        // when the undersized chunk is the last one. Every read below
        // is covered by this guard plus the chunk-bound checks.
        if (chunkEnd < POOL_FIXED) {
            throw malformed("the string pool chunk is " + chunkEnd + " bytes; it cannot carry its header");
        }
        int count = u32(axml, position + 8);
        boolean utf8 = (u32(axml, position + 16) & UTF8_FLAG) != 0;
        long dataStart = u32(axml, position + 20);
        if (count < 0 || count > MAX_STRINGS) {
            throw malformed("the string pool declares " + count + " strings; refusing");
        }
        if (POOL_FIXED + 4L * count > chunkEnd) {
            throw malformed("the string pool's offset array overruns its chunk");
        }
        List<String> strings = new ArrayList<>(Math.min(count, 1024));
        for (int index = 0; index < count; index++) {
            int offset = u32(axml, position + POOL_FIXED + 4 * index);
            long at = position + dataStart + offset;
            if (at < 0 || at >= position + chunkEnd) {
                throw malformed("string " + index + " starts outside the pool");
            }
            strings.add(
                    utf8
                            ? utf8String(axml, (int) at, position + chunkEnd)
                            : utf16String(axml, (int) at, position + chunkEnd));
        }
        return strings;
    }

    /**
     * UTF-16 pool string: a (possibly escaped) u16 length, chars,
     * terminator. Every read is bounds-checked against the pool BEFORE
     * it happens — a string offset the caller admits (inside the
     * chunk) can still sit at the pool's final bytes, and reading a
     * length there must answer MALFORMED_MANIFEST, never an array
     * overrun.
     */
    private static String utf16String(byte[] axml, int at, int poolEnd) {
        if (at + 2 > poolEnd) {
            throw malformed("a UTF-16 string length starts at the pool's edge");
        }
        int length = u16(axml, at);
        int units = 2;
        if ((length & 0x8000) != 0) {
            if (at + 4 > poolEnd) {
                throw malformed("a UTF-16 string length starts at the pool's edge");
            }
            length = ((length & 0x7FFF) << 16) | u16(axml, at + 2);
            units = 4;
        }
        if (at + units + 2L * length > poolEnd) {
            throw malformed("a UTF-16 string length overruns the pool");
        }
        return new String(axml, at + units, length * 2, StandardCharsets.UTF_16LE);
    }

    /** UTF-8 pool string: u16 char length, u8 byte length, bytes, terminator. */
    private static String utf8String(byte[] axml, int at, int poolEnd) {
        if (at + 2 > poolEnd) {
            throw malformed("a UTF-8 string length starts at the pool's edge");
        }
        int chars = u16(axml, at);
        int charsUnits = 2;
        if ((chars & 0x8000) != 0) {
            if (at + 4 > poolEnd) {
                throw malformed("a UTF-8 string length starts at the pool's edge");
            }
            chars = ((chars & 0x7FFF) << 16) | u16(axml, at + 2);
            charsUnits = 4;
        }
        if (at + charsUnits + 1 > poolEnd) {
            throw malformed("a UTF-8 string length starts at the pool's edge");
        }
        int bytes = axml[at + charsUnits] & 0xFF;
        int byteUnits = 1;
        if ((bytes & 0x80) != 0) {
            if (at + charsUnits + 2 > poolEnd) {
                throw malformed("a UTF-8 string length starts at the pool's edge");
            }
            bytes = ((bytes & 0x7F) << 8) | (axml[at + charsUnits + 1] & 0xFF);
            byteUnits = 2;
        }
        int start = at + charsUnits + byteUnits;
        if (start + (long) bytes > poolEnd) {
            throw malformed("a UTF-8 string length overruns the pool");
        }
        return new String(axml, start, bytes, StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Elements
    // -------------------------------------------------------------------------

    private static Element parseElement(
            byte[] axml, int position, int chunkSize, int chunkHeader, List<String> strings) {
        if (chunkHeader < ELEMENT_HEADER || position + ELEMENT_HEADER + ATTR_EXT > position + chunkSize) {
            throw malformed("an element chunk is too small to carry its fields");
        }
        int nameIndex = u32(axml, position + ELEMENT_HEADER + 4);
        String name = stringOf(strings, nameIndex);
        if (name == null) {
            throw malformed("an element carries no resolvable name");
        }
        int attributeStart = u16(axml, position + ELEMENT_HEADER + 8);
        int attributeSize = u16(axml, position + ELEMENT_HEADER + 10);
        int attributeCount = u16(axml, position + ELEMENT_HEADER + 12);
        if (attributeStart < ATTR_EXT || attributeSize < ATTRIBUTE_FIXED || attributeCount > MAX_ATTRIBUTES) {
            throw malformed("the element '" + name + "' declares an unusable attribute layout");
        }
        List<Attribute> attributes = new ArrayList<>(Math.min(attributeCount, 8));
        for (int index = 0; index < attributeCount; index++) {
            long at = position + ELEMENT_HEADER + attributeStart + (long) index * attributeSize;
            if (at + ATTRIBUTE_FIXED > position + chunkSize) {
                throw malformed("the element '" + name + "' declares attributes past its chunk");
            }
            String attributeName = stringOf(strings, u32(axml, (int) at + 4));
            if (attributeName == null) {
                continue;
            }
            String value = attributeValue(axml, (int) at, strings);
            if (value != null) {
                attributes.add(new Attribute(attributeName, value));
            }
        }
        return new Element(name, List.copyOf(attributes));
    }

    /** The attribute's value as a string, preferring the raw literal. */
    private static String attributeValue(byte[] axml, int at, List<String> strings) {
        int rawIndex = u32(axml, at + 8);
        if (rawIndex != NO_STRING) {
            return stringOf(strings, rawIndex);
        }
        int dataType = axml[at + 15] & 0xFF;
        // Mask to unsigned: u32 answers a signed int, and version
        // codes above 2^31-1 (legal in Android's unsigned 32-bit
        // space) must not land as negative builds.
        long data = u32(axml, at + 16) & 0xFFFFFFFFL;
        return switch (dataType) {
            case TYPE_STRING -> stringOf(strings, (int) data);
            // Version codes and SDK levels are unsigned 32-bit values;
            // rendering them unsigned matches every Android tool.
            case TYPE_INT_DEC -> Long.toString(data);
            case TYPE_INT_HEX -> "0x" + Long.toHexString(data);
            case TYPE_BOOLEAN -> data != 0 ? "true" : "false";
            default -> null;
        };
    }

    /** The pool string at the index, or null when the index means absent. */
    private static String stringOf(List<String> strings, int index) {
        if (index == NO_STRING) {
            return null;
        }
        if (index < 0 || index >= strings.size()) {
            throw malformed("a string index (" + index + ") points outside the pool");
        }
        return strings.get(index);
    }

    private static ExtractionFailure malformed(String detail) {
        return new ExtractionFailure(ExtractionFailure.Code.MALFORMED_MANIFEST, "AndroidManifest.xml: " + detail);
    }

    // -------------------------------------------------------------------------
    // Little-endian readers
    // -------------------------------------------------------------------------

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
