package com.shipperdog.api.artifact.internal.extraction;

import com.shipperdog.api.artifact.ArtifactBinary.RangeReader;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A bounded-memory ZIP reader over ranged object-store reads (issue
 * #21, S5-02 — the issue's "no whole-binary heap" rule, made
 * structural): it walks the END of central directory record and the
 * central directory itself, then reads individual entries by seeking
 * to their local headers. A multi-hundred-MB IPA costs a few ranged
 * reads totaling kilobytes — the SLO's {@code < 30 s for 500 MB}
 * (PRD 42) is met by not reading the 500 MB, and by nothing else.
 *
 * <p>Deliberate limits, all enforced before any large allocation:
 * classic ZIP only (ZIP64 answers {@link
 * ExtractionFailure.Code#ZIP64_UNSUPPORTED} — actionable, and no
 * IPA in practice needs it), a central-directory byte cap, an entry
 * count cap, a per-entry compressed AND decompressed size cap, and
 * rejection of encrypted entries. Entry names decode as UTF-8 (flag
 * bit 11 or ASCII either way; a name that cannot decode is a damaged
 * archive).
 *
 * <p>Only what the artifact module's formats need is implemented:
 * entry listing by name and entry extraction with STORED/DEFLATED.
 * The class is package-private and unit-tested against real archives
 * produced by {@code ZipOutputStream} — no other consumer exists.
 */
final class ZipRanges {

    /** {@code PK\x05\x06} — the end of central directory record. */
    private static final int EOCD_SIGNATURE = 0x06054b50;

    /** {@code PK\x06\x07} — the ZIP64 end of central directory locator. */
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;

    /** Fixed byte width of the ZIP64 EOCD locator record. */
    private static final int ZIP64_LOCATOR_FIXED = 20;

    /** {@code PK\x01\x02} — a central directory file header. */
    private static final int CENTRAL_SIGNATURE = 0x02014b50;

    /** {@code PK\x03\x04} — a local file header. */
    private static final int LOCAL_SIGNATURE = 0x04034b50;

    /** Fixed byte width of the EOCD record (before the comment). */
    private static final int EOCD_FIXED = 22;

    /** The ZIP comment ceiling; the backward EOCD scan window derives from it. */
    private static final int MAX_COMMENT = 65535;

    /** A central directory larger than this is not an IPA's; refuse before reading. */
    private static final long MAX_CENTRAL_BYTES = 8L * 1024 * 1024;

    /** Entry-count ceiling of one archive's central directory. */
    private static final int MAX_ENTRIES = 100_000;

    /** Fixed byte width of a central directory file header (before name/extra/comment). */
    private static final int CENTRAL_FIXED = 46;

    /** Fixed byte width of a local file header (before name/extra). */
    private static final int LOCAL_FIXED = 30;

    private final RangeReader ranges;

    private final int maxEntryBytes;

    private final long centralDirectoryOffset;

    private final List<Entry> entries;

    private ZipRanges(RangeReader ranges, int maxEntryBytes, long centralDirectoryOffset, List<Entry> entries) {
        this.ranges = ranges;
        this.maxEntryBytes = maxEntryBytes;
        this.centralDirectoryOffset = centralDirectoryOffset;
        this.entries = entries;
    }

    /**
     * Opens the archive: locates the EOCD (scanning backward over at
     * most the comment ceiling), validates the ZIP64 and layout
     * invariants, and parses the whole central directory into named
     * entries.
     *
     * @param size the object's byte count (the scan needs it; a wrong
     *     size is indistinguishable from a damaged archive)
     * @param maxEntryBytes the per-entry cap, applied to compressed
     *     AND decompressed sizes at read time
     */
    static ZipRanges open(RangeReader ranges, long size, int maxEntryBytes) {
        if (size < EOCD_FIXED) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.NOT_A_ZIP, "the object is smaller than a ZIP end record");
        }
        long eocdOffset = locateEocd(ranges, size);
        byte[] eocd = ranges.read(eocdOffset, EOCD_FIXED);
        int totalEntries = u16(eocd, 10);
        long centralSize = u32(eocd, 12);
        long centralOffset = u32(eocd, 16);
        // Saturated counters are ZIP64's signature; a >= 4 GiB object
        // would be too. Both answer the honest unsupported error.
        if (totalEntries == 0xFFFF
                || centralSize == 0xFFFFFFFFL
                || centralOffset == 0xFFFFFFFFL
                || size >= 0xFFFFFFFFL) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.ZIP64_UNSUPPORTED,
                    ExtractionFailure.Code.ZIP64_UNSUPPORTED.defaultMessage());
        }
        if (totalEntries > MAX_ENTRIES) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_ZIP,
                    "the archive declares " + totalEntries + " entries; refusing");
        }
        if (centralSize > MAX_CENTRAL_BYTES) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_ZIP,
                    "the central directory exceeds the " + MAX_CENTRAL_BYTES + "-byte cap; refusing");
        }
        if (centralOffset + centralSize > eocdOffset) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_ZIP,
                    "the central directory does not lie inside the archive (offset " + centralOffset + ", size "
                            + centralSize + ", archive " + size + " bytes)");
        }
        List<Entry> entries = parseCentralDirectory(ranges, totalEntries, centralOffset, centralSize);
        return new ZipRanges(ranges, maxEntryBytes, centralOffset, entries);
    }

    /** The central directory entries, in archive order. */
    List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * The central directory's start offset. Everything BEFORE it is
     * entry data (and, for APKs, the signing block); a format probe
     * that needs the bytes just before the directory (the v2/v3
     * signing block's footer) anchors here.
     */
    long centralDirectoryOffset() {
        return centralDirectoryOffset;
    }

    /**
     * Reads one entry's fully decompressed bytes, enforcing every cap:
     * encryption, compression method, and both size ceilings before
     * the ranged read; the decompressed size must match the central
     * directory's declaration exactly.
     */
    byte[] readEntry(Entry entry) {
        if (entry.encrypted()) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.ENTRY_ENCRYPTED, "the entry '" + entry.name() + "' is encrypted");
        }
        if (entry.method() != 0 && entry.method() != 8) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.UNSUPPORTED_COMPRESSION,
                    "the entry '" + entry.name() + "' uses compression method " + entry.method());
        }
        if (entry.compressedSize() > maxEntryBytes || entry.uncompressedSize() > maxEntryBytes) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.ENTRY_TOO_LARGE,
                    "the entry '" + entry.name() + "' exceeds the " + maxEntryBytes + "-byte extraction cap");
        }
        byte[] local = ranges.read(entry.localHeaderOffset(), LOCAL_FIXED);
        if (u32(local, 0) != LOCAL_SIGNATURE) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_ZIP,
                    "the local header of '" + entry.name() + "' does not start where the directory says");
        }
        long dataOffset = entry.localHeaderOffset() + LOCAL_FIXED + u16(local, 26) + u16(local, 28);
        // The entry's declared extent must stay inside the archive —
        // and specifically before the central directory, where entry
        // data physically lives. Declaring an extent past the archive
        // end is binary damage: answering it here keeps the artifact
        // FAILED (the binary's fault) instead of a ranged-read throw
        // that would misfile as STORAGE_UNAVAILABLE (infra) and BLOCK
        // the artifact after retries.
        if (dataOffset + entry.compressedSize() > centralDirectoryOffset) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_ZIP,
                    "the data of '" + entry.name() + "' extends past the archive (offset " + dataOffset
                            + ", size " + entry.compressedSize() + ", central directory at "
                            + centralDirectoryOffset + ")");
        }
        byte[] compressed = ranges.read(dataOffset, (int) entry.compressedSize());
        if (entry.method() == 0) {
            if (entry.compressedSize() != entry.uncompressedSize()) {
                throw new ExtractionFailure(
                        ExtractionFailure.Code.MALFORMED_ZIP, "the stored entry '" + entry.name() + "' sizes disagree");
            }
            return compressed;
        }
        byte[] inflated = inflate(compressed, entry);
        if (inflated.length != entry.uncompressedSize()) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_ZIP,
                    "the entry '" + entry.name() + "' inflated to " + inflated.length
                            + " bytes, the directory declares " + entry.uncompressedSize());
        }
        return inflated;
    }

    // -------------------------------------------------------------------------
    // EOCD location and central directory parsing
    // -------------------------------------------------------------------------

    /** Scans backward for the EOCD signature, tolerating a comment. */
    private static long locateEocd(RangeReader ranges, long size) {
        long window = Math.min(size, MAX_COMMENT + EOCD_FIXED);
        byte[] tail = ranges.read(size - window, (int) window);
        for (int end = tail.length - EOCD_FIXED; end >= 0; end--) {
            if (u32(tail, end) == EOCD_SIGNATURE) {
                int commentLength = u16(tail, end + 20);
                if (end + EOCD_FIXED + commentLength == tail.length) {
                    long recordOffset = size - window + end;
                    // The ZIP64 EOCD locator is a fixed 20-byte record
                    // that sits immediately before the classic EOCD;
                    // catching it here covers small archives written
                    // ZIP64 without any saturated counter.
                    if (recordOffset >= ZIP64_LOCATOR_FIXED
                            && u32(ranges.read(recordOffset - ZIP64_LOCATOR_FIXED, 4), 0) == ZIP64_LOCATOR_SIGNATURE) {
                        throw new ExtractionFailure(
                                ExtractionFailure.Code.ZIP64_UNSUPPORTED,
                                ExtractionFailure.Code.ZIP64_UNSUPPORTED.defaultMessage());
                    }
                    return recordOffset;
                }
            }
        }
        throw new ExtractionFailure(ExtractionFailure.Code.NOT_A_ZIP, "no ZIP end-of-central-directory record found");
    }

    private static List<Entry> parseCentralDirectory(
            RangeReader ranges, int totalEntries, long centralOffset, long centralSize) {
        List<Entry> entries = new ArrayList<>(Math.min(totalEntries, 1024));
        if (centralSize == 0) {
            return entries;
        }
        byte[] directory = ranges.read(centralOffset, (int) centralSize);
        int position = 0;
        for (int index = 0; index < totalEntries; index++) {
            if (position + CENTRAL_FIXED > directory.length) {
                throw new ExtractionFailure(
                        ExtractionFailure.Code.MALFORMED_ZIP,
                        "the central directory ends inside entry " + index + " of " + totalEntries);
            }
            if (u32(directory, position) != CENTRAL_SIGNATURE) {
                throw new ExtractionFailure(
                        ExtractionFailure.Code.MALFORMED_ZIP, "a central directory entry has no ZIP signature");
            }
            int flags = u16(directory, position + 8);
            int method = u16(directory, position + 10);
            long compressedSize = u32(directory, position + 20);
            long uncompressedSize = u32(directory, position + 24);
            int nameLength = u16(directory, position + 28);
            int extraLength = u16(directory, position + 30);
            int commentLength = u16(directory, position + 32);
            long localHeaderOffset = u32(directory, position + 42);
            int nameStart = position + CENTRAL_FIXED;
            // Every local header must live before the central
            // directory — a declared offset at or past it (or past the
            // archive itself) is binary damage, answered BEFORE any
            // ranged read so a damaged archive fails as the binary's
            // own fault, never as an infra misread.
            if (localHeaderOffset + LOCAL_FIXED > centralOffset) {
                throw new ExtractionFailure(
                        ExtractionFailure.Code.MALFORMED_ZIP,
                        "the directory declares a local header for '" + decodeName(directory, nameStart, nameLength)
                                + "' outside the archive (offset " + localHeaderOffset
                                + ", central directory at " + centralOffset + ")");
            }
            int recordEnd = nameStart + nameLength + extraLength + commentLength;
            if (recordEnd > directory.length) {
                throw new ExtractionFailure(
                        ExtractionFailure.Code.MALFORMED_ZIP, "a central directory entry overruns the directory");
            }
            String name = decodeName(directory, nameStart, nameLength);
            entries.add(
                    new Entry(name, (flags & 0x1) != 0, method, compressedSize, uncompressedSize, localHeaderOffset));
            position = recordEnd;
        }
        if (position != directory.length) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_ZIP,
                    directory.length - position + " trailing bytes after the last declared entry; refusing");
        }
        return entries;
    }

    /**
     * Decodes an entry name as UTF-8. Malformed bytes never throw —
     * {@link String#String(byte[], int, int, java.nio.charset.Charset)}
     * replaces them with U+FFFD — so a name that fails to match an
     * expected entry shape reads as that shape's absence (the binary
     * stays wrong, through the shape-specific failure) rather than as
     * a decode error.
     */
    private static String decodeName(byte[] directory, int start, int length) {
        return new String(directory, start, length, StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Deflate
    // -------------------------------------------------------------------------

    /**
     * Inflates raw deflate (the ZIP variant, no zlib wrapper) with the
     * wrapped form as fallback, capping output at the entry's
     * CENTRAL-DIRECTORY-DECLARED size as it streams — a compressed
     * bomb that declares a small size stops at the declaration, never
     * at an allocation. {@link ExtractionFailure.Code#ENTRY_TOO_LARGE}
     * is the earlier, pre-read answer for sizes honestly declared
     * above the configured cap; this guard is the dishonest-declaration
     * one.
     */
    private static byte[] inflate(byte[] compressed, Entry entry) {
        byte[] raw = inflate(compressed, entry, true);
        if (raw != null) {
            return raw;
        }
        byte[] wrapped = inflate(compressed, entry, false);
        if (wrapped != null) {
            return wrapped;
        }
        throw new ExtractionFailure(
                ExtractionFailure.Code.MALFORMED_ZIP,
                "the entry '" + entry.name() + "' does not inflate; the archive is damaged or truncated");
    }

    private static byte[] inflate(byte[] compressed, Entry entry, boolean raw) {
        Inflater inflater = new Inflater(raw);
        // Raw deflate (the ZIP variant) can need one byte of lookahead
        // past the final block before it reports finished — the
        // Inflater javadoc's own "dummy byte" note; ZipInputStream
        // gets it for free by reading into the next entry. Without it
        // a perfectly valid ZERO-byte deflated entry (an empty
        // Info.plist) reads as damaged. Zlib ignores padding after
        // BFINAL, so this only ever helps.
        byte[] input = raw ? padForRawLookahead(compressed) : compressed;
        inflater.setInput(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > 0) {
                    if (output.size() + count > entry.uncompressedSize()) {
                        // More output than the directory declares is
                        // damage OR a bomb; both stop here without the
                        // allocation the plain cap check would allow.
                        return null;
                    }
                    output.write(buffer, 0, count);
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    return null;
                }
            }
            return output.toByteArray();
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    // -------------------------------------------------------------------------
    // Little-endian readers
    // -------------------------------------------------------------------------

    private static byte[] padForRawLookahead(byte[] compressed) {
        byte[] padded = new byte[compressed.length + 1];
        System.arraycopy(compressed, 0, padded, 0, compressed.length);
        return padded;
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    /** One central directory entry, as the parser needs it. */
    record Entry(
            String name,
            boolean encrypted,
            int method,
            long compressedSize,
            long uncompressedSize,
            long localHeaderOffset) {

        /** Directory entries are inert; only {@link ZipRanges#readEntry} reads bytes. */
        Entry {
            assert name != null;
        }
    }
}
