package com.shipperdog.api.artifact.internal.extraction;

import com.shipperdog.api.artifact.ArtifactBinary;
import com.shipperdog.api.artifact.ArtifactMetadataParser;
import com.shipperdog.api.artifact.ExtractedMetadata;
import com.shipperdog.api.artifact.ExtractedMetadata.SignatureBlocks;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The Android APK/AAB parser (issue #22, S5-03, FR-005): the {@code
 * ANDROID} implementation of the S5-02 parser port. Both formats are
 * ZIPs whose manifests are Android binary XML, so one path serves
 * them: walk the central directory with ranged reads, detect the
 * format STRUCTURALLY (never by file name), read the manifest entry,
 * and lift the identity fields through the in-house {@link
 * AxmlManifest} walker.
 *
 * <p>Format detection (AC3): an archive carrying {@code
 * base/manifest/AndroidManifest.xml} is an AAB (the bundle layout);
 * one carrying the root {@code manifest/AndroidManifest.xml} PLUS
 * {@code BundleConfig.pb} is the AAB's alternate manifest location;
 * one carrying a root {@code AndroidManifest.xml} is an APK; anything
 * else is a ZIP that is neither — UNKNOWN_FORMAT, the rejected-
 * unknown leg of a renamed plain ZIP. The verdict rides in the
 * extraction document's {@code format} key so FR-006's channel rules
 * (an AAB cannot ship as a direct download) can act on structure,
 * not on what the uploader named the file.
 *
 * <p>The AAB version-code nuance (AC2's careful AAB-only behavior):
 * bundletool strips {@code android:versionCode} from the base module
 * manifest into {@code BundleConfig.pb} (protobuf field 1), so the
 * manifest is read FIRST and the bundle config is consulted only when
 * the manifest lacks the attribute — a bounded varint walk of one
 * small entry, never a protobuf runtime. Which source produced the
 * build is recorded in the parser attribution string.
 *
 * <p>Signing (AC1, metadata only in M1): the APK Signing Block sits
 * immediately before the central directory; its footer (size u64 +
 * the {@code "APK Sig Block 42"} magic) and pair headers are probed
 * with ranged reads for the v2 ({@code 0x7109871a}) and v3 ({@code
 * 0xf05368c0}) scheme ids. Presence is stored, never verified — S5-05
 * owns judging signatures, and v1 JAR signatures in {@code META-INF}
 * are out of scope. A structurally inconsistent block reads as
 * not-detected rather than failing the extraction: the identity
 * fields already proved the binary parseable, and a signing gap is
 * S5-05's finding to make.
 */
@Component
public class AndroidMetadataParser implements ArtifactMetadataParser {

    /** The platform of every artifact this parser accepts. */
    private static final String PLATFORM = "ANDROID";

    /** The APK's manifest entry, at the archive root. */
    private static final String APK_MANIFEST = "AndroidManifest.xml";

    /** The AAB's base-module manifest entry (the canonical bundle layout). */
    private static final String AAB_BASE_MANIFEST = "base/manifest/AndroidManifest.xml";

    /** The AAB's whole-app manifest entry (the alternate bundle location). */
    private static final String AAB_ROOT_MANIFEST = "manifest/AndroidManifest.xml";

    /** The AAB's bundle configuration entry (version code carrier). */
    private static final String BUNDLE_CONFIG = "BundleConfig.pb";

    /** The attribution when every field came from the manifest itself. */
    private static final String PARSER = "android:axml-walker";

    /** The attribution when the version code came from BundleConfig.pb. */
    private static final String PARSER_WITH_BUNDLE_CONFIG = "android:axml-walker+bundle-config-pb";

    /** APK Signature Scheme v2: the signing block pair id. */
    private static final long V2_SIGNATURE_ID = 0x7109871aL;

    /** APK Signature Scheme v3: the signing block pair id. */
    private static final long V3_SIGNATURE_ID = 0xf05368c0L;

    /** The signing block's trailing magic, exactly as Android spells it. */
    private static final byte[] SIGNING_MAGIC = "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII);

    /** The signing block's fixed footer: size u64 + the 16-byte magic. */
    private static final int SIGNING_FOOTER = 24;

    /** Pair-walk ceiling; a real block carries a handful of pairs. */
    private static final int MAX_SIGNING_PAIRS = 1024;

    /** The bundle_id column's width — the parser's contract with V16. */
    private static final int IDENTIFIER_CAP = 255;

    /** The minimum_os column's width — the parser's contract with V16. */
    private static final int SDK_CAP = 32;

    private final ArtifactExtractionProperties properties;

    public AndroidMetadataParser(ArtifactExtractionProperties properties) {
        this.properties = properties;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public ExtractedMetadata parse(ArtifactBinary binary) {
        long startedAt = System.nanoTime();
        ZipRanges zip = ZipRanges.open(binary.ranges(), binary.size(), properties.maxEntryBytes());
        DetectedFormat detected = detectFormat(zip);
        AxmlManifest manifest = AxmlManifest.parse(zip.readEntry(detected.manifest()));
        Map<String, String> manifestAttributes = attributesOf(manifest, "manifest");
        Map<String, String> sdk = attributesOf(manifest, "uses-sdk");
        String bundleId = requiredPackage(manifestAttributes);
        String versionName = bounded("versionName", manifestAttributes.get("versionName"), IDENTIFIER_CAP);
        String versionCode = manifestAttributes.get("versionCode");
        String parser = PARSER;
        if (versionCode == null && detected.bundle()) {
            versionCode = bundleConfigVersionCode(zip);
            parser = PARSER_WITH_BUNDLE_CONFIG;
        }
        String minimumOs = bounded("minSdkVersion", sdk.get("minSdkVersion"), SDK_CAP);
        String targetOs = bounded("targetSdkVersion", sdk.get("targetSdkVersion"), SDK_CAP);
        SignatureBlocks signatureBlocks = signingBlocks(binary, zip);
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new ExtractedMetadata(
                bundleId,
                versionName,
                numericBuild(versionCode),
                versionCode,
                minimumOs,
                labelOf(manifestAttributes),
                targetOs,
                detected.format(),
                null,
                signatureBlocks,
                Instant.now(),
                durationMs,
                parser);
    }

    // -------------------------------------------------------------------------
    // Format detection (AC3): structure, never the file name
    // -------------------------------------------------------------------------

    /** The detected format and the manifest entry to read. */
    private record DetectedFormat(String format, ZipRanges.Entry manifest, boolean bundle) {}

    private static DetectedFormat detectFormat(ZipRanges zip) {
        Optional<ZipRanges.Entry> baseManifest = entry(zip, AAB_BASE_MANIFEST);
        if (baseManifest.isPresent()) {
            return new DetectedFormat("AAB", baseManifest.get(), true);
        }
        boolean bundleConfig = entry(zip, BUNDLE_CONFIG).isPresent();
        Optional<ZipRanges.Entry> rootManifest = entry(zip, AAB_ROOT_MANIFEST);
        if (bundleConfig && rootManifest.isPresent()) {
            return new DetectedFormat("AAB", rootManifest.get(), true);
        }
        Optional<ZipRanges.Entry> apkManifest = entry(zip, APK_MANIFEST);
        if (apkManifest.isPresent()) {
            return new DetectedFormat("APK", apkManifest.get(), false);
        }
        throw new ExtractionFailure(
                ExtractionFailure.Code.UNKNOWN_FORMAT, ExtractionFailure.Code.UNKNOWN_FORMAT.defaultMessage());
    }

    private static Optional<ZipRanges.Entry> entry(ZipRanges zip, String name) {
        return zip.entries().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst();
    }

    /** The named element's attributes as a map; absent elements answer empty. */
    private static Map<String, String> attributesOf(AxmlManifest manifest, String elementName) {
        Map<String, String> attributes = new LinkedHashMap<>();
        manifest.elements().stream()
                .filter(element -> element.name().equals(elementName))
                .findFirst()
                .ifPresent(element -> element.attributes()
                        .forEach(attribute -> attributes.putIfAbsent(attribute.name(), attribute.value())));
        return attributes;
    }

    // -------------------------------------------------------------------------
    // Field discipline
    // -------------------------------------------------------------------------

    /** The package attribute is the one required field (V16 bundle_id). */
    private static String requiredPackage(Map<String, String> manifestAttributes) {
        String pkg = manifestAttributes.get("package");
        if (pkg == null || pkg.isBlank()) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_MANIFEST, "the manifest lacks the required package attribute");
        }
        return bounded("package", pkg.trim(), IDENTIFIER_CAP);
    }

    /**
     * The V16 column widths are the contract, exactly as the iOS
     * parser enforces them: an identity field longer than its column
     * is a malformed manifest for extraction purposes, rejected at
     * parse time with an actionable message instead of an
     * unrecordable write that would strand the artifact VALIDATING.
     */
    private static String bounded(String field, String value, int cap) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > cap) {
            throw new ExtractionFailure(
                    ExtractionFailure.Code.MALFORMED_MANIFEST,
                    field + " exceeds " + cap + " characters (" + trimmed.length()
                            + "); the artifact registry cannot store it");
        }
        return trimmed;
    }

    /**
     * The display-name candidate: {@code android:label}, kept only as
     * a literal — resource references ({@code @string/...}, {@code
     * ?attr/...}) name a resource the registry cannot resolve, so they
     * read as no display name rather than as noise.
     */
    private static String labelOf(Map<String, String> manifestAttributes) {
        String label = bounded("label", manifestAttributes.get("label"), IDENTIFIER_CAP);
        if (label == null || label.startsWith("@") || label.startsWith("?")) {
            return null;
        }
        return label;
    }

    /** versionCode is an unsigned 32-bit integer in both encodings. */
    private static Long numericBuild(String versionCode) {
        if (versionCode == null || versionCode.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(versionCode);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // BundleConfig.pb: the AAB version-code carrier (AC2)
    // -------------------------------------------------------------------------

    /**
     * Reads the bundle config's {@code version_code} (protobuf field
     * 1, varint) with a bounded wire walk — no protobuf runtime for
     * one integer. The manifest is always consulted first; this runs
     * only when it lacks the attribute. A config that exists but
     * cannot be walked is MALFORMED_BUNDLE_CONFIG; a config without
     * field 1 leaves the build null (the optional-field semantics of
     * the format itself).
     */
    private String bundleConfigVersionCode(ZipRanges zip) {
        ZipRanges.Entry config = entry(zip, BUNDLE_CONFIG)
                .orElseThrow(() -> new ExtractionFailure(
                        ExtractionFailure.Code.MALFORMED_BUNDLE_CONFIG,
                        "the manifest carries no versionCode and the bundle has no BundleConfig.pb"));
        return bundleConfigVersionCode(zip.readEntry(config));
    }

    /** The bounded wire walk itself; null when field 1 is simply absent. */
    static String bundleConfigVersionCode(byte[] config) {
        Cursor cursor = new Cursor();
        while (cursor.at < config.length) {
            long tag = varint(config, cursor);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 0x7);
            if (field == 1 && wire == 0) {
                return Long.toString(varint(config, cursor));
            }
            switch (wire) {
                case 0:
                    varint(config, cursor);
                    break;
                case 1:
                    cursor.at += 8;
                    break;
                case 2:
                    // Deliberately two statements: a compound assignment
                    // would capture cursor.at BEFORE varint advances it
                    // and land the skip on the wrong offset.
                    long length = varint(config, cursor);
                    cursor.at += length;
                    break;
                case 5:
                    cursor.at += 4;
                    break;
                // Groups (3/4) died with proto2; a bundle config is
                // not allowed to carry them.
                default:
                    throw new ExtractionFailure(
                            ExtractionFailure.Code.MALFORMED_BUNDLE_CONFIG,
                            "BundleConfig.pb carries an unskippable field (wire type " + wire + ")");
            }
            if (cursor.at < 0 || cursor.at > config.length) {
                throw new ExtractionFailure(
                        ExtractionFailure.Code.MALFORMED_BUNDLE_CONFIG,
                        "BundleConfig.pb declares a field past its end; the entry is truncated or damaged");
            }
        }
        return null;
    }

    /** The walk's position; one instance per call, never shared. */
    private static final class Cursor {
        private long at;
    }

    /** Reads one varint at the cursor and advances past it. */
    private static long varint(byte[] bytes, Cursor cursor) {
        long value = 0;
        int shift = 0;
        long position = cursor.at;
        while (position < bytes.length && shift <= 63) {
            byte current = bytes[(int) position++];
            value |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                cursor.at = position;
                return value;
            }
            shift += 7;
        }
        throw new ExtractionFailure(
                ExtractionFailure.Code.MALFORMED_BUNDLE_CONFIG, "BundleConfig.pb carries an unterminated varint");
    }

    // -------------------------------------------------------------------------
    // APK Signing Block presence (AC1): stored, never verified
    // -------------------------------------------------------------------------

    /**
     * Probes for the v2/v3 scheme pairs. The block ends exactly at the
     * central directory's start: a 24-byte footer (size u64 + magic),
     * a size echo at the block's head, then id/length pairs. Every
     * probe is a small ranged read; pair VALUES are skipped by offset,
     * never read. Structural inconsistency answers not-detected — see
     * the class javadoc for why it must not fail the extraction.
     */
    private SignatureBlocks signingBlocks(ArtifactBinary binary, ZipRanges zip) {
        long directoryStart = zip.centralDirectoryOffset();
        if (directoryStart < SIGNING_FOOTER) {
            return new SignatureBlocks(false, false);
        }
        byte[] footer = binary.ranges().read(directoryStart - SIGNING_FOOTER, SIGNING_FOOTER);
        long sizeInFooter = u64(footer, 0);
        if (!magic(footer, 8) || sizeInFooter < SIGNING_FOOTER || sizeInFooter + 8 > directoryStart) {
            return new SignatureBlocks(false, false);
        }
        long blockStart = directoryStart - sizeInFooter - 8;
        if (u64(binary.ranges().read(blockStart, 8), 0) != sizeInFooter) {
            return new SignatureBlocks(false, false);
        }
        boolean v2 = false;
        boolean v3 = false;
        long position = blockStart + 8;
        long end = directoryStart - SIGNING_FOOTER;
        int pairs = 0;
        while (position + 12 <= end && pairs < MAX_SIGNING_PAIRS && !(v2 && v3)) {
            byte[] header = binary.ranges().read(position, 12);
            long length = u64(header, 0);
            if (length < 4 || position + 8 + length > end) {
                break;
            }
            long id = u32(header, 8);
            if (id == V2_SIGNATURE_ID) {
                v2 = true;
            } else if (id == V3_SIGNATURE_ID) {
                v3 = true;
            }
            position += 8 + length;
            pairs++;
        }
        return new SignatureBlocks(v2, v3);
    }

    private static boolean magic(byte[] footer, int offset) {
        for (int i = 0; i < SIGNING_MAGIC.length; i++) {
            if (footer[offset + i] != SIGNING_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Little-endian readers
    // -------------------------------------------------------------------------

    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFFL)
                | ((bytes[offset + 1] & 0xFFL) << 8)
                | ((bytes[offset + 2] & 0xFFL) << 16)
                | ((bytes[offset + 3] & 0xFFL) << 24);
    }

    private static long u64(byte[] bytes, int offset) {
        return u32(bytes, offset) | (u32(bytes, offset + 4) << 32);
    }
}
