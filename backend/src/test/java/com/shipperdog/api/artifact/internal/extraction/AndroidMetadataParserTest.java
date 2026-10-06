package com.shipperdog.api.artifact.internal.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.artifact.AndroidFixtures;
import com.shipperdog.api.artifact.ArtifactBinary;
import com.shipperdog.api.artifact.ExtractedMetadata;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The issue #22 fixture matrix (AC1-AC4) over the real parser: every
 * leg is a programmatic fixture — real ZIPs with real AXML the
 * fixture writer assembled independently of this parser — covering
 * the APK and AAB shapes, the signing-block markers, the format
 * detection split, and every corrupt/renamed rejection with its
 * actionable code. The SLO mechanism is pinned directly: a
 * multi-megabyte APK answers in kilobytes of ranged reads because the
 * dex payload is never fetched.
 */
class AndroidMetadataParserTest {

    private final AndroidMetadataParser parser =
            new AndroidMetadataParser(new ArtifactExtractionProperties(2, 200, 1024 * 1024, 3));

    // -----------------------------------------------------------------------
    // AC1: the APK identity fields
    // -----------------------------------------------------------------------

    @Test
    void releaseApkExtractsEveryIdentityField() throws IOException {
        byte[] apk = AndroidFixtures.releaseApk();

        ExtractedMetadata metadata = parser.parse(binaryOf(apk));

        assertThat(metadata.bundleId()).isEqualTo(AndroidFixtures.PACKAGE);
        assertThat(metadata.versionName()).isEqualTo(AndroidFixtures.VERSION_NAME);
        assertThat(metadata.buildNumber()).isEqualTo(421L);
        assertThat(metadata.buildNumberRaw()).isEqualTo(AndroidFixtures.VERSION_CODE);
        assertThat(metadata.minimumOs()).isEqualTo(AndroidFixtures.MIN_SDK);
        assertThat(metadata.targetOs()).isEqualTo(AndroidFixtures.TARGET_SDK);
        assertThat(metadata.displayName()).isEqualTo(AndroidFixtures.LABEL);
        assertThat(metadata.format()).isEqualTo("APK");
        assertThat(metadata.parser()).isEqualTo("android:axml-walker");
        assertThat(metadata.extractedAt()).isBefore(Instant.now().plusSeconds(1));
        assertThat(metadata.signatureBlocks())
                .as("an unsigned APK carries no scheme pairs")
                .isEqualTo(new com.shipperdog.api.artifact.ExtractedMetadata.SignatureBlocks(false, false));
        assertThat(metadata.signing()).isNull();
    }

    @Test
    void v2AndV3SigningBlockPairsAreDetectedAsStoredMarkers() throws IOException {
        ExtractedMetadata both = parser.parse(binaryOf(AndroidFixtures.signedApk(true, true)));
        assertThat(both.signatureBlocks().v2SignatureBlock()).isTrue();
        assertThat(both.signatureBlocks().v3SignatureBlock()).isTrue();
        // The splice patched the directory offsets; identity fields
        // still read through them.
        assertThat(both.bundleId()).isEqualTo(AndroidFixtures.PACKAGE);

        ExtractedMetadata v2Only = parser.parse(binaryOf(AndroidFixtures.signedApk(true, false)));
        assertThat(v2Only.signatureBlocks().v2SignatureBlock()).isTrue();
        assertThat(v2Only.signatureBlocks().v3SignatureBlock()).isFalse();
    }

    @Test
    void aMultiMegabyteApkCostsKilobytesOfRangedReads() throws IOException {
        byte[] apk = AndroidFixtures.paddedApk(24 * 1024 * 1024);
        ZipRangesTest.CountingRanges ranges = new ZipRangesTest.CountingRanges(apk);

        long startedAt = System.nanoTime();
        ExtractedMetadata metadata = parser.parse(new ArtifactBinary(apk.length, ranges));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(metadata.bundleId()).isEqualTo(AndroidFixtures.PACKAGE);
        // The SLO mechanism, pinned like the IPA leg: kilobytes fetched
        // out of 24 MiB (< 1%), milliseconds out of the 30-second
        // budget (PRD 42) — the dex payload is never fetched.
        assertThat(ranges.fetchedBytes()).isLessThan(apk.length / 100);
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void stringPoolEncodingsAndStringTypedSdkValuesParseIdentically() throws IOException {
        ExtractedMetadata utf8 = parser.parse(binaryOf(AndroidFixtures.utf8PoolApk()));
        ExtractedMetadata stringSdks = parser.parse(binaryOf(AndroidFixtures.stringValuesApk()));

        assertThat(utf8.bundleId()).isEqualTo(AndroidFixtures.PACKAGE);
        assertThat(utf8.minimumOs()).isEqualTo(AndroidFixtures.MIN_SDK);
        assertThat(stringSdks.targetOs()).isEqualTo(AndroidFixtures.TARGET_SDK);
    }

    @Test
    void labelsCarryLiteralsOnly() throws IOException {
        assertThat(parser.parse(binaryOf(AndroidFixtures.unicodeLabelApk())).displayName())
                .isEqualTo("银行银行银行");
        assertThat(parser.parse(binaryOf(AndroidFixtures.resourceLabelApk())).displayName())
                .as("a @string reference names a resource the registry cannot resolve")
                .isNull();
    }

    @Test
    void missingSdkElementLeavesBothLevelsNull() throws IOException {
        ExtractedMetadata metadata = parser.parse(binaryOf(AndroidFixtures.noSdkApk()));
        assertThat(metadata.minimumOs()).isNull();
        assertThat(metadata.targetOs()).isNull();
        assertThat(metadata.bundleId()).isEqualTo(AndroidFixtures.PACKAGE);
    }

    // -----------------------------------------------------------------------
    // AC2: the AAB legs, including the BundleConfig.pb version code
    // -----------------------------------------------------------------------

    @Test
    void aabExtractsManifestFieldsWithTheBundleConfigVersionCode() throws IOException {
        ExtractedMetadata metadata = parser.parse(binaryOf(AndroidFixtures.aabVersionCodeInBundleConfig()));

        assertThat(metadata.format()).isEqualTo("AAB");
        assertThat(metadata.bundleId()).isEqualTo(AndroidFixtures.PACKAGE);
        assertThat(metadata.versionName()).isEqualTo(AndroidFixtures.VERSION_NAME);
        assertThat(metadata.buildNumber()).isEqualTo(421L);
        assertThat(metadata.buildNumberRaw()).isEqualTo(AndroidFixtures.VERSION_CODE);
        assertThat(metadata.minimumOs()).isEqualTo(AndroidFixtures.MIN_SDK);
        assertThat(metadata.targetOs()).isEqualTo(AndroidFixtures.TARGET_SDK);
        assertThat(metadata.parser())
                .as("the version code came from BundleConfig.pb, and the attribution says so")
                .isEqualTo("android:axml-walker+bundle-config-pb");
    }

    @Test
    void aabManifestVersionCodeIsPreferredOverTheBundleConfig() throws IOException {
        ExtractedMetadata metadata = parser.parse(binaryOf(AndroidFixtures.aabVersionCodeInManifest()));
        assertThat(metadata.buildNumber()).isEqualTo(421L);
        assertThat(metadata.parser()).isEqualTo("android:axml-walker");
    }

    @Test
    void theAlternateAabManifestLayoutIsDetected() throws IOException {
        assertThat(parser.parse(binaryOf(AndroidFixtures.aabRootManifest())).format())
                .isEqualTo("AAB");
    }

    @Test
    void aabWithoutAnyVersionCodeLeavesTheBuildNull() throws IOException {
        ExtractedMetadata metadata = parser.parse(binaryOf(AndroidFixtures.aabWithoutVersionCode()));
        assertThat(metadata.buildNumber()).isNull();
        assertThat(metadata.buildNumberRaw()).isNull();
        assertThat(metadata.bundleId()).isEqualTo(AndroidFixtures.PACKAGE);
    }

    @Test
    void theBundleConfigWalkSkipsLengthDelimitedFields() {
        assertThat(AndroidMetadataParser.bundleConfigVersionCode(
                        AndroidFixtures.bundleConfigVersionCodeAfterOtherFields()))
                .isEqualTo("421");
    }

    @Test
    void apksNeverConsultABundleConfigForTheVersionCode() throws IOException {
        // An APK has no BundleConfig.pb entry at all: a manifest without
        // versionCode leaves the build null — the fallback is the
        // bundle's alone, and it must not fire (or fail) for APKs.
        byte[] apk = AndroidFixtures.apk(AndroidFixtures.manifestAxml(
                AndroidFixtures.PACKAGE,
                AndroidFixtures.VERSION_NAME,
                null,
                AndroidFixtures.MIN_SDK,
                AndroidFixtures.TARGET_SDK,
                AndroidFixtures.LABEL,
                false,
                false));

        ExtractedMetadata metadata = parser.parse(binaryOf(apk));

        assertThat(metadata.format()).isEqualTo("APK");
        assertThat(metadata.buildNumber()).isNull();
        assertThat(metadata.buildNumberRaw()).isNull();
        assertThat(metadata.parser()).isEqualTo("android:axml-walker");
    }

    @Test
    void emptyBundleConfigsAreValidProtosWithoutTheField() throws IOException {
        ExtractedMetadata metadata = parser.parse(binaryOf(AndroidFixtures.aabWithEmptyBundleConfig()));

        assertThat(metadata.format()).isEqualTo("AAB");
        assertThat(metadata.buildNumber()).isNull();
        assertThat(metadata.bundleId()).isEqualTo(AndroidFixtures.PACKAGE);
    }

    @Test
    void corruptBundleConfigsAreTheActionableFailure() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(AndroidFixtures.aabWithCorruptBundleConfig())))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("BundleConfig.pb")
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_BUNDLE_CONFIG);
    }

    @Test
    void hostileSigningPairWalksTerminateUndetected() throws IOException {
        byte[] apk = AndroidFixtures.withHostileSigningPairs(AndroidFixtures.releaseApk(), 2000);

        ExtractedMetadata metadata = parser.parse(binaryOf(apk));

        assertThat(metadata.bundleId()).isEqualTo(AndroidFixtures.PACKAGE);
        assertThat(metadata.signatureBlocks().v2SignatureBlock()).isFalse();
        assertThat(metadata.signatureBlocks().v3SignatureBlock()).isFalse();
    }

    @Test
    void structurallyInconsistentSigningBlocksReadAsNotDetected() throws IOException {
        byte[] damaged = AndroidFixtures.withUndersizedSigningFooter(AndroidFixtures.signedApk(true, false));

        ExtractedMetadata metadata = parser.parse(binaryOf(damaged));

        assertThat(metadata.signatureBlocks().v2SignatureBlock())
                .as("an impossible footer size cannot prove a scheme pair")
                .isFalse();
        assertThat(metadata.bundleId()).isEqualTo(AndroidFixtures.PACKAGE);
    }

    @Test
    void whitespacePaddedPackagesAreTrimmed() throws IOException {
        byte[] apk = AndroidFixtures.apk(AndroidFixtures.manifestAxml(
                "  " + AndroidFixtures.PACKAGE + "  ",
                AndroidFixtures.VERSION_NAME,
                AndroidFixtures.VERSION_CODE,
                AndroidFixtures.MIN_SDK,
                AndroidFixtures.TARGET_SDK,
                AndroidFixtures.LABEL,
                false,
                false));

        assertThat(parser.parse(binaryOf(apk)).bundleId()).isEqualTo(AndroidFixtures.PACKAGE);
    }

    @Test
    void thePackageColumnBoundaryIsExact() throws IOException {
        String atCap = "a".repeat(255);
        byte[] boundary =
                AndroidFixtures.apk(AndroidFixtures.manifestAxml(atCap, null, null, null, null, null, false, false));
        assertThat(parser.parse(binaryOf(boundary)).bundleId()).isEqualTo(atCap);

        byte[] over = AndroidFixtures.apk(
                AndroidFixtures.manifestAxml("a".repeat(256), null, null, null, null, null, false, false));
        assertThatThrownBy(() -> parser.parse(binaryOf(over)))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("255")
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_MANIFEST);
    }

    @Test
    void unsignedMaxVersionCodesExtract() throws IOException {
        // Android version codes are unsigned 32-bit; 4294967295 must
        // survive both the walker's rendering and the numeric build.
        byte[] apk = AndroidFixtures.apk(AndroidFixtures.manifestAxml(
                AndroidFixtures.PACKAGE, null, "4294967295", null, null, null, false, false));

        ExtractedMetadata metadata = parser.parse(binaryOf(apk));

        assertThat(metadata.buildNumber()).isEqualTo(4294967295L);
        assertThat(metadata.buildNumberRaw()).isEqualTo("4294967295");
    }

    // -----------------------------------------------------------------------
    // AC3 + AC4: format detection and the corrupt/renamed rejections
    // -----------------------------------------------------------------------

    @Test
    void aRenamedPlainZipIsRejectedAsUnknownFormat() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(AndroidFixtures.renamedZipApk())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.UNKNOWN_FORMAT);
    }

    @Test
    void nonZipRenamedApkIsTheActionableFailure() {
        byte[] notZip = AndroidFixtures.notAZip(4096);
        assertThatThrownBy(() -> parser.parse(binaryOf(notZip)))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.NOT_A_ZIP);
    }

    @Test
    void aTruncatedApkAnswersNotAZipNeverAnUnhandledRead() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(AndroidFixtures.truncatedApk())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.NOT_A_ZIP);
    }

    @Test
    void garbageManifestBytesAreMalformedManifest() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(AndroidFixtures.garbageManifestApk())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_MANIFEST);
    }

    @Test
    void zeroByteManifestsAreMalformedManifest() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(AndroidFixtures.emptyManifestApk())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_MANIFEST);
    }

    @Test
    void manifestsWithoutAPackageAreMalformedManifest() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(AndroidFixtures.identifierlessManifestApk())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_MANIFEST);
    }

    @Test
    void oversizedPackagesAreRejectedAtTheColumnWidth() throws IOException {
        byte[] apk = AndroidFixtures.oversizedPackageApk();
        assertThatThrownBy(() -> parser.parse(binaryOf(apk)))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("255")
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_MANIFEST);
    }

    @Test
    void encryptedManifestsAreTheEncryptionFailure() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(AndroidFixtures.encryptedManifestApk())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.ENTRY_ENCRYPTED);
    }

    @Test
    void manifestSizeBombsAreRefusedByTheEntryCap() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(AndroidFixtures.bombManifestApk())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.ENTRY_TOO_LARGE);
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private static ArtifactBinary binaryOf(byte[] bytes) {
        return new ArtifactBinary(bytes.length, new ZipRangesTest.CountingRanges(bytes));
    }
}
