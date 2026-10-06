package com.shipperdog.api.artifact.internal.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.artifact.AndroidFixtures;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Hostile-input matrix of the in-house AXML walker (issue #22, S5-03):
 * every leg is a well-formed fixture manifest with ONE structural lie
 * patched into its bytes — the damage a hostile or simply broken
 * builder tool produces — and the walker must answer the actionable
 * MALFORMED_MANIFEST failure, never an unhandled array read.
 */
class AxmlManifestTest {

    // -----------------------------------------------------------------------
    // The defect reproducer: string offsets at the pool's last byte
    // -----------------------------------------------------------------------

    @Test
    void aStringStartingAtThePoolsLastByteIsMalformedNeverAnArrayCrash() throws IOException {
        // A manifest of file header + pool ONLY, the pool as the final
        // chunk: the caller-side bound allows at == poolEnd-1, and
        // poolEnd-1 is then the array's last byte — the length read
        // itself must be bounds-checked or the walk dies with a raw
        // ArrayIndexOutOfBoundsException instead of a failure code.
        byte[] full = AndroidFixtures.manifestAxml();
        Pool pool = poolOf(full);
        byte[] axml = java.util.Arrays.copyOf(full, pool.end);
        patchU32(axml, 4, axml.length);
        int lastSlot = pool.offsetsAt + 4 * (pool.count - 1);
        patchU32(axml, lastSlot, axml.length - 1 - (pool.start + pool.dataStart));

        assertThatThrownBy(() -> AxmlManifest.parse(axml))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_MANIFEST);
    }

    // -----------------------------------------------------------------------
    // Attribute-layout lies
    // -----------------------------------------------------------------------

    @Test
    void thePoolHeaderBoundaryIsExact() {
        // 27 bytes: one short of the pool's fixed header — the guard's
        // floor answers MALFORMED, never a field read.
        byte[] undersized = poolOnlyDocument(27, 0);
        assertThatThrownBy(() -> AxmlManifest.parse(undersized))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("cannot carry its header")
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_MANIFEST);

        // 28 bytes with zero strings: the smallest legal pool parses
        // (empty), and the downstream failure is the missing package,
        // never a crash.
        assertThat(AxmlManifest.parse(poolOnlyDocument(28, 0)).elements()).isEmpty();

        // Hostile counts still answer the ceiling, never an
        // offsets-array read: positive-saturated and negative.
        assertThatThrownBy(() -> AxmlManifest.parse(poolOnlyDocument(28, 0x7FFFFFFF)))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("refusing");
        assertThatThrownBy(() -> AxmlManifest.parse(poolOnlyDocument(28, 0x80000000)))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("refusing");
    }

    /** A document of file header plus one pool chunk of the given size/count. */
    private static byte[] poolOnlyDocument(int poolSize, int count) {
        int length = 8 + poolSize;
        byte[] axml = new byte[length];
        patchU16(axml, 0, 0x0003);
        patchU16(axml, 2, 8);
        patchU32(axml, 4, length);
        patchU16(axml, 8, 0x0001);
        patchU16(axml, 10, 8);
        patchU32(axml, 12, poolSize);
        patchU32(axml, 16, count); // the pool's stringCount field
        return axml;
    }

    @Test
    void poolChunksTooSmallForTheirOwnHeaderAreMalformed() {
        // A 16-byte document: file header plus an 8-byte pool chunk.
        // The pool's fixed header alone is 28 bytes — reading its
        // fields anyway would index past the array, so the walker must
        // answer the failure before touching a single field byte.
        byte[] axml = new byte[16];
        patchU16(axml, 0, 0x0003); // the file chunk
        patchU16(axml, 2, 8);
        patchU32(axml, 4, axml.length);
        patchU16(axml, 8, 0x0001); // the pool chunk, 8 declared bytes
        patchU16(axml, 10, 8);
        patchU32(axml, 12, 8);

        assertThatThrownBy(() -> AxmlManifest.parse(axml))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("cannot carry its header")
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_MANIFEST);
    }

    @Test
    void attributeNamesOutsideThePoolAreMalformed() throws IOException {
        byte[] axml = AndroidFixtures.manifestAxml();
        ElementLayout layout = firstElementOf(axml);
        // The first attribute's name index (attr + 4): a value that is
        // neither absent (0xFFFFFFFF) nor inside the pool.
        patchU32(axml, layout.firstAttributeAt + 4, 0xFFFFFFFEL);

        assertThatThrownBy(() -> AxmlManifest.parse(axml))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("outside the pool");
    }

    @Test
    void attributeCountsOverrunningTheChunkAreMalformed() throws IOException {
        byte[] axml = AndroidFixtures.manifestAxml();
        ElementLayout layout = firstElementOf(axml);
        patchU16(axml, layout.at + ELEMENT_HEADER_LOCAL + 12, layout.attributeCount + 3);

        assertThatThrownBy(() -> AxmlManifest.parse(axml))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("past its chunk");
    }

    @Test
    void attributeStartsBelowTheFixedFieldsAreMalformed() throws IOException {
        byte[] axml = AndroidFixtures.manifestAxml();
        ElementLayout layout = firstElementOf(axml);
        patchU16(axml, layout.at + ELEMENT_HEADER_LOCAL + 8, 0);

        assertThatThrownBy(() -> AxmlManifest.parse(axml))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("attribute layout");
    }

    // -----------------------------------------------------------------------
    // Chunk-sequence lies
    // -----------------------------------------------------------------------

    @Test
    void duplicateStringPoolsAreMalformed() throws IOException {
        byte[] axml = AndroidFixtures.manifestAxml();
        Pool pool = poolOf(axml);
        byte[] duplicated = new byte[axml.length + pool.size];
        System.arraycopy(axml, 0, duplicated, 0, pool.start + pool.size);
        System.arraycopy(axml, pool.start, duplicated, pool.start + pool.size, pool.size);
        System.arraycopy(
                axml,
                pool.start + pool.size,
                duplicated,
                pool.start + 2 * pool.size,
                axml.length - pool.start - pool.size);
        patchU32(duplicated, 4, duplicated.length);

        assertThatThrownBy(() -> AxmlManifest.parse(duplicated))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("more than one string pool");
    }

    @Test
    void elementChunksBeforeThePoolAreMalformed() throws IOException {
        byte[] axml = AndroidFixtures.manifestAxml();
        Pool pool = poolOf(axml);
        ElementLayout layout = firstElementOf(axml);
        int firstElementEnd = layout.at + layout.size;
        // Header, first element, pool, then everything that followed
        // the first element (the pool sat before it originally).
        byte[] reordered = new byte[axml.length];
        System.arraycopy(axml, 0, reordered, 0, 8);
        System.arraycopy(axml, layout.at, reordered, 8, layout.size);
        System.arraycopy(axml, pool.start, reordered, 8 + layout.size, pool.size);
        System.arraycopy(axml, firstElementEnd, reordered, 8 + layout.size + pool.size, axml.length - firstElementEnd);

        assertThatThrownBy(() -> AxmlManifest.parse(reordered))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("before the string pool");
    }

    @Test
    void zeroSizeChunksAreMalformed() throws IOException {
        byte[] axml = AndroidFixtures.manifestAxml();
        Pool pool = poolOf(axml);
        patchU32(axml, pool.start + 4, 0);

        assertThatThrownBy(() -> AxmlManifest.parse(axml))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("does not fit the document");
    }

    // -----------------------------------------------------------------------
    // String-length lies
    // -----------------------------------------------------------------------

    @Test
    void utf8ByteLengthsOverrunningThePoolAreMalformed() throws IOException {
        byte[] axml = AndroidFixtures.manifestAxml(true);
        Pool pool = poolOf(axml);
        int lastSlot = pool.offsetsAt + 4 * (pool.count - 1);
        int last = pool.start + pool.dataStart + (int) u32Of(axml, lastSlot);
        // The last string's byte-length sits two bytes in (after the
        // u16 char length); declare more bytes than the pool has left.
        axml[last + 2] = (byte) 0x7F;

        assertThatThrownBy(() -> AxmlManifest.parse(axml))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("UTF-8 string length overruns");
    }

    @Test
    void escapedUtf16LengthsOverrunningThePoolAreMalformed() throws IOException {
        byte[] axml = AndroidFixtures.manifestAxml();
        Pool pool = poolOf(axml);
        int lastSlot = pool.offsetsAt + 4 * (pool.count - 1);
        int last = pool.start + pool.dataStart + (int) u32Of(axml, lastSlot);
        // Set the high bit of the length: the two-unit escaped form,
        // whose combined value reads far past the pool.
        axml[last + 1] |= 0x80;

        assertThatThrownBy(() -> AxmlManifest.parse(axml))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("UTF-16 string length overruns");
    }

    @Test
    void duplicateInBoundsStringOffsetsAreTolerated() throws IOException {
        byte[] axml = AndroidFixtures.manifestAxml();
        Pool pool = poolOf(axml);
        // Point the LAST string at the previous string's offset: both
        // slots stay in bounds and decode, so the pool is merely
        // weird, not damaged — the walker reads it and the manifest
        // still parses.
        int lastSlot = pool.offsetsAt + 4 * (pool.count - 1);
        int previous = (int) u32Of(axml, lastSlot - 4);
        patchU32(axml, lastSlot, previous);

        AxmlManifest parsed = AxmlManifest.parse(axml);

        assertThat(parsed.elements()).isNotEmpty();
        assertThat(parsed.elements().stream().map(AxmlManifest.Element::name)).contains("manifest");
    }

    // -----------------------------------------------------------------------
    // Patch helpers (the format's layout is the walker's own contract)
    // -----------------------------------------------------------------------

    /** 16, the element chunk's node header, relative to chunk start. */
    private static final int ELEMENT_HEADER_LOCAL = 16;

    private record Pool(int start, int size, int count, int dataStart, int offsetsAt, int end) {}

    private record ElementLayout(int at, int size, int attributeCount, int firstAttributeAt) {}

    private static Pool poolOf(byte[] axml) {
        int start = 8;
        int size = (int) u32Of(axml, start + 4);
        int count = (int) u32Of(axml, start + 8);
        int dataStart = (int) u32Of(axml, start + 20);
        return new Pool(start, size, count, dataStart, start + 28, start + size);
    }

    private static ElementLayout firstElementOf(byte[] axml) {
        Pool pool = poolOf(axml);
        // The 24-byte namespace chunk follows the pool; the element
        // chunk follows that.
        int at = pool.end + 24;
        int size = (int) u32Of(axml, at + 4);
        int attributeCount =
                axml[at + ELEMENT_HEADER_LOCAL + 12] & 0xFF | ((axml[at + ELEMENT_HEADER_LOCAL + 13] & 0xFF) << 8);
        int attributeStart =
                axml[at + ELEMENT_HEADER_LOCAL + 8] & 0xFF | ((axml[at + ELEMENT_HEADER_LOCAL + 9] & 0xFF) << 8);
        return new ElementLayout(at, size, attributeCount, at + ELEMENT_HEADER_LOCAL + attributeStart);
    }

    private static void patchU16(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value & 0xFF);
        bytes[at + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void patchU32(byte[] bytes, int at, long value) {
        for (int i = 0; i < 4; i++) {
            bytes[at + i] = (byte) ((value >> (8 * i)) & 0xFF);
        }
    }

    private static long u32Of(byte[] bytes, int at) {
        return (bytes[at] & 0xFFL)
                | ((bytes[at + 1] & 0xFFL) << 8)
                | ((bytes[at + 2] & 0xFFL) << 16)
                | ((bytes[at + 3] & 0xFFL) << 24);
    }
}
