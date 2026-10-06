package com.shipperdog.api.artifact.internal.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.artifact.IpaFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Unit matrix of the bounded-memory ZIP reader (issue #21, S5-02):
 * round-trips of deflated and stored entries against archives built
 * by {@code ZipOutputStream} (the same JDK writer iOS tooling's shape
 * mirrors), plus every rejection leg — non-zip, ZIP64 counters,
 * encrypted flags, entry caps, damaged local headers — each with the
 * actionable code the artifact's {@code extraction_error} records.
 */
class ZipRangesTest {

    /** A generous per-entry cap for the happy paths. */
    private static final int CAP = 1024 * 1024;

    private final byte[] archive;

    private final CountingRanges ranges;

    ZipRangesTest() throws IOException {
        archive = twoEntryZip();
        ranges = new CountingRanges(archive);
    }

    @Test
    void walksTheCentralDirectoryWithoutReadingEntryBytes() {
        ZipRanges zip = ZipRanges.open(ranges, archive.length, CAP);
        List<ZipRanges.Entry> entries = zip.entries();
        assertThat(entries).extracting(ZipRanges.Entry::name).containsExactly("first.txt", "second.bin");
        // Opening read the EOCD scan window, the EOCD itself, and the
        // central directory — never an entry's payload bytes.
        assertThat(ranges.fetchedBytes()).isLessThan(600);
    }

    @Test
    void readsDeflatedEntriesVerbatim() {
        ZipRanges zip = ZipRanges.open(ranges, archive.length, CAP);
        byte[] first = zip.readEntry(zip.entries().get(0));
        assertThat(new String(first, StandardCharsets.UTF_8)).isEqualTo("hello zip");
    }

    @Test
    void readsStoredEntriesVerbatim() throws IOException {
        byte[] stored = storedZip();
        CountingRanges storedRanges = new CountingRanges(stored);
        ZipRanges zip = ZipRanges.open(storedRanges, stored.length, CAP);
        assertThat(zip.entries()).extracting(ZipRanges.Entry::name).containsExactly("stored.bin");
        assertThat(zip.readEntry(zip.entries().get(0))).isEqualTo(new byte[] {1, 2, 3, 4, 5});
    }

    @Test
    void readsTheFixtureIpaEntries() throws IOException {
        byte[] ipa = IpaFixtures.releaseIpa();
        ZipRanges zip = ZipRanges.open(new CountingRanges(ipa), ipa.length, CAP);
        assertThat(zip.entries()).extracting(ZipRanges.Entry::name).contains("Payload/Bank.app/Info.plist");
        byte[] plist = zip.readEntry(zip.entries().get(0));
        // A binary plist starts with the magic header bplist00.
        assertThat(new String(plist, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("bplist00");
    }

    @Test
    void rejectsBytesThatAreNotAZip() {
        byte[] notZip = IpaFixtures.notAZip(2048);
        assertThatThrownBy(() -> ZipRanges.open(new CountingRanges(notZip), notZip.length, CAP))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.NOT_A_ZIP);
    }

    @Test
    void rejectsSaturatedZip64Counters() throws IOException {
        byte[] zip64 = twoEntryZip();
        int eocd = eocdOffset(zip64);
        // Saturate the total-entries counter (EOCD + 10): ZIP64's
        // classic signature, answered with the honest unsupported code.
        zip64[eocd + 10] = (byte) 0xFF;
        zip64[eocd + 11] = (byte) 0xFF;
        assertThatThrownBy(() -> ZipRanges.open(new CountingRanges(zip64), zip64.length, CAP))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.ZIP64_UNSUPPORTED);
    }

    @Test
    void rejectsEncryptedEntries() throws IOException {
        byte[] encrypted = twoEntryZip();
        int central = centralOffset(encrypted);
        // General-purpose flags sit at central + 8; bit 0 is encryption.
        encrypted[central + 8] |= 0x1;
        ZipRanges zip = ZipRanges.open(new CountingRanges(encrypted), encrypted.length, CAP);
        assertThatThrownBy(() -> zip.readEntry(zip.entries().get(0)))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.ENTRY_ENCRYPTED);
    }

    @Test
    void rejectsEntriesAboveTheCap() {
        ZipRanges zip = ZipRanges.open(ranges, archive.length, 4);
        assertThatThrownBy(() -> zip.readEntry(zip.entries().get(1)))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.ENTRY_TOO_LARGE);
    }

    @Test
    void rejectsLocalHeaderOffsetsDeclaredPastTheArchive() throws IOException {
        byte[] damaged = twoEntryZip();
        int central = centralOffset(damaged);
        // Declare the first entry's local header past the central
        // directory — binary damage that previously reached the ranged
        // read and misfiled as infra; it must answer MALFORMED_ZIP at
        // open time (issue #21's folded reviewer advisory).
        damaged[central + 42] = (byte) 0xFF;
        damaged[central + 43] = (byte) 0xFF;
        damaged[central + 44] = (byte) 0xFF;
        damaged[central + 45] = (byte) 0x7F;
        assertThatThrownBy(() -> ZipRanges.open(new CountingRanges(damaged), damaged.length, CAP))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_ZIP);
    }

    @Test
    void rejectsDataExtentsDeclaredPastTheCentralDirectory() throws IOException {
        byte[] damaged = twoEntryZip();
        int central = centralOffset(damaged);
        // A valid local header but a compressed size whose extent runs
        // into the central directory: damage, answered before any read.
        damaged[central + 20] = (byte) central;
        damaged[central + 21] = 0;
        damaged[central + 22] = 0;
        damaged[central + 23] = 0;
        ZipRanges zip = ZipRanges.open(new CountingRanges(damaged), damaged.length, CAP);
        assertThatThrownBy(() -> zip.readEntry(zip.entries().get(0)))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_ZIP);
    }

    @Test
    void rejectsAOneByteExtentOverrunAtTheExactBoundary() throws IOException {
        byte[] damaged = twoEntryZip();
        int central = centralOffset(damaged);
        // The first entry's data starts at 39 (header 30 + the 9-byte
        // name "first.txt"); declare a size that ends exactly ONE byte
        // past the central directory's start — the boundary the new
        // check exists to catch, where a ranged read would happily try.
        int dataStartsAt = 39;
        for (int i = 0; i < 4; i++) {
            damaged[central + 20 + i] = (byte) ((central - dataStartsAt + 1) >> (8 * i));
        }
        ZipRanges zip = ZipRanges.open(new CountingRanges(damaged), damaged.length, CAP);
        assertThatThrownBy(() -> zip.readEntry(zip.entries().get(0)))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("past the archive")
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_ZIP);
    }

    @Test
    void rejectsDamagedLocalHeaders() throws IOException {
        byte[] damaged = twoEntryZip();
        ZipRanges zip = ZipRanges.open(new CountingRanges(damaged), damaged.length, CAP);
        ZipRanges.Entry entry = zip.entries().get(0);
        // Corrupt the local header's first byte (the P of PK\x03\x04).
        damaged[(int) entry.localHeaderOffset()] = 'X';
        assertThatThrownBy(() -> zip.readEntry(entry))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_ZIP);
    }

    @Test
    void rejectsUnsupportedCompressionMethods() throws IOException {
        byte[] archiveBytes = twoEntryZip();
        int central = centralOffset(archiveBytes);
        // Method 14 (LZMA) at central + 10: valid ZIP, unsupported here.
        archiveBytes[central + 10] = 14;
        ZipRanges zip = ZipRanges.open(new CountingRanges(archiveBytes), archiveBytes.length, CAP);
        assertThatThrownBy(() -> zip.readEntry(zip.entries().get(0)))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.UNSUPPORTED_COMPRESSION);
    }

    @Test
    void zeroLengthDeflatedEntriesReadAsEmpty() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("empty.plist"));
            zip.closeEntry();
        }
        byte[] archiveBytes = bytes.toByteArray();
        ZipRanges zip = ZipRanges.open(new CountingRanges(archiveBytes), archiveBytes.length, CAP);
        assertThat(zip.readEntry(zip.entries().get(0))).isEmpty();
    }

    @Test
    void archiveCommentsAreTolerated() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.setComment("an archive comment of arbitrary length");
            zip.putNextEntry(new ZipEntry("first.txt"));
            zip.write("hello zip".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] archiveBytes = bytes.toByteArray();
        ZipRanges zip = ZipRanges.open(new CountingRanges(archiveBytes), archiveBytes.length, CAP);
        assertThat(zip.entries()).extracting(ZipRanges.Entry::name).containsExactly("first.txt");
    }

    // -----------------------------------------------------------------------
    // Archive builders and byte-level probes
    // -----------------------------------------------------------------------

    private static byte[] twoEntryZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("first.txt"));
            zip.write("hello zip".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("second.bin"));
            zip.write(new byte[32]);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] storedZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("stored.bin");
            byte[] payload = {1, 2, 3, 4, 5};
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(payload);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(payload);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** The EOCD's offset: the last PK\x05\x06 in the archive. */
    private static int eocdOffset(byte[] archiveBytes) {
        for (int i = archiveBytes.length - 22; i >= 0; i--) {
            if (archiveBytes[i] == 'P'
                    && archiveBytes[i + 1] == 'K'
                    && archiveBytes[i + 2] == 5
                    && archiveBytes[i + 3] == 6) {
                return i;
            }
        }
        throw new IllegalStateException("fixture has no EOCD");
    }

    /** The first central-directory record's offset (PK\x01\x02). */
    private static int centralOffset(byte[] archiveBytes) {
        for (int i = 0; i < archiveBytes.length - 4; i++) {
            if (archiveBytes[i] == 'P'
                    && archiveBytes[i + 1] == 'K'
                    && archiveBytes[i + 2] == 1
                    && archiveBytes[i + 3] == 2) {
                return i;
            }
        }
        throw new IllegalStateException("fixture has no central directory");
    }

    /** An in-memory range source that counts fetched bytes. */
    static final class CountingRanges implements com.shipperdog.api.artifact.ArtifactBinary.RangeReader {

        private final byte[] data;

        private long fetched;

        CountingRanges(byte[] data) {
            this.data = data;
        }

        @Override
        public byte[] read(long offset, int length) {
            fetched += length;
            return java.util.Arrays.copyOfRange(data, (int) offset, (int) offset + length);
        }

        long fetchedBytes() {
            return fetched;
        }
    }
}
