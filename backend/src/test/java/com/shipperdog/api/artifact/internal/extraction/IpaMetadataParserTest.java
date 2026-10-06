package com.shipperdog.api.artifact.internal.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.artifact.ArtifactBinary;
import com.shipperdog.api.artifact.ExtractedMetadata;
import com.shipperdog.api.artifact.IpaFixtures;
import com.shipperdog.api.artifact.IpaFixtures.IpaBuilder;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The issue #21 fixture matrix (AC1-AC4) over the real parser: every
 * leg is a programmatic fixture (no committed binaries), and the
 * SLO's mechanism is asserted directly — a padded multi-megabyte IPA
 * answers in well under the 30-second budget BECAUSE the parser
 * fetched kilobytes, so the test pins both the byte count and the
 * wall clock.
 */
class IpaMetadataParserTest {

    private final IpaMetadataParser parser =
            new IpaMetadataParser(new ArtifactExtractionProperties(2, 200, 1024 * 1024, 3));

    @Test
    void releaseBuildExtractsEveryIdentityFieldAndTheSigningTier() throws IOException {
        byte[] ipa = IpaFixtures.releaseIpa();
        ZipRangesTest.CountingRanges ranges = new ZipRangesTest.CountingRanges(ipa);

        ExtractedMetadata metadata = parser.parse(new ArtifactBinary(ipa.length, ranges));

        assertThat(metadata.bundleId()).isEqualTo(IpaFixtures.BUNDLE_ID);
        assertThat(metadata.versionName()).isEqualTo(IpaFixtures.VERSION_NAME);
        assertThat(metadata.buildNumber()).isEqualTo(421L);
        assertThat(metadata.buildNumberRaw()).isEqualTo(IpaFixtures.BUILD);
        assertThat(metadata.minimumOs()).isEqualTo(IpaFixtures.MINIMUM_OS);
        assertThat(metadata.displayName()).isEqualTo(IpaFixtures.DISPLAY_NAME);
        assertThat(metadata.parser()).isEqualTo("ipa:dd-plist");
        assertThat(metadata.extractedAt()).isBefore(Instant.now().plusSeconds(1));
        assertThat(metadata.signing()).isNotNull();
        assertThat(metadata.signing().profileName()).isEqualTo(IpaFixtures.PROFILE_NAME);
        assertThat(metadata.signing().profileUuid()).isEqualTo(IpaFixtures.PROFILE_UUID);
        assertThat(metadata.signing().teamIdentifiers()).containsExactly(IpaFixtures.TEAM_ID);
        assertThat(metadata.signing().expiresAt()).isEqualTo(Instant.parse("2031-01-01T00:00:00Z"));
        assertThat(metadata.signing().entitlements()).isEqualTo(IpaFixtures.expectedEntitlements());
    }

    @Test
    void unsignedBuildExtractsIdentityFieldsWithNoSigningTier() throws IOException {
        ExtractedMetadata metadata = parser.parse(binaryOf(IpaFixtures.unsignedIpa()));

        assertThat(metadata.bundleId()).isEqualTo(IpaFixtures.BUNDLE_ID);
        assertThat(metadata.buildNumber()).isEqualTo(421L);
        assertThat(metadata.signing()).isNull();
    }

    @Test
    void missingInfoPlistIsTheActionableFailure() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(IpaFixtures.missingPlistIpa())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MISSING_INFO_PLIST);
    }

    @Test
    void nonZipRenamedIpaIsTheActionableFailure() {
        byte[] notZip = IpaFixtures.notAZip(4096);
        assertThatThrownBy(() -> parser.parse(binaryOf(notZip)))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.NOT_A_ZIP);
    }

    @Test
    void xmlInfoPlistsParseIdentically() throws IOException {
        ExtractedMetadata metadata = parser.parse(binaryOf(IpaFixtures.xmlPlistIpa()));

        assertThat(metadata.bundleId()).isEqualTo(IpaFixtures.BUNDLE_ID);
        assertThat(metadata.versionName()).isEqualTo(IpaFixtures.VERSION_NAME);
        assertThat(metadata.buildNumber()).isEqualTo(421L);
        assertThat(metadata.minimumOs()).isEqualTo(IpaFixtures.MINIMUM_OS);
    }

    @Test
    void plistWithoutBundleIdentifierIsMalformed() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(IpaFixtures.identifierlessIpa())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_PLIST);
    }

    @Test
    void nonIntegerBuildStringsKeepTheColumnNullAndTheRawString() throws IOException {
        ExtractedMetadata metadata = parser.parse(binaryOf(IpaFixtures.nonNumericBuildIpa()));

        assertThat(metadata.buildNumber()).isNull();
        assertThat(metadata.buildNumberRaw()).isEqualTo("1.2.3-beta.1");
        assertThat(metadata.bundleId()).isEqualTo(IpaFixtures.BUNDLE_ID);
    }

    @Test
    void damagedProvisioningProfileDegradesToNoSigningTier() throws IOException {
        // A release-shaped archive whose profile carries no XML payload:
        // the identity extraction must still succeed.
        byte[] plist = IpaFixtures.infoPlist();
        byte[] ipa = new IpaBuilder()
                .infoPlist(plist)
                .profile(IpaFixtures.garbageProfile())
                .build();
        ExtractedMetadata metadata = parser.parse(binaryOf(ipa));

        assertThat(metadata.bundleId()).isEqualTo(IpaFixtures.BUNDLE_ID);
        assertThat(metadata.signing()).isNull();
    }

    @Test
    void paddedMultiMegabyteIpaExtractsWithBoundedReadsAndWellUnderTheSlo() throws IOException {
        byte[] ipa = IpaFixtures.paddedIpa(24 * 1024 * 1024);
        ZipRangesTest.CountingRanges ranges = new ZipRangesTest.CountingRanges(ipa);

        long startedAt = System.nanoTime();
        ExtractedMetadata metadata = parser.parse(new ArtifactBinary(ipa.length, ranges));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(metadata.bundleId()).isEqualTo(IpaFixtures.BUNDLE_ID);
        // The SLO mechanism: kilobytes fetched out of 24 MiB (< 1%),
        // milliseconds spent out of the 30-second budget (PRD 42).
        assertThat(ranges.fetchedBytes()).isLessThan(ipa.length / 100);
        assertThat(elapsedMs).isLessThan(5_000);
        assertThat(metadata.durationMs()).isLessThan(5_000);
    }

    @Test
    void oversizedIdentifierIsAnActionableParseFailureNotASilentWriteDeath() throws IOException {
        // The defect this pins: V16's bundle_id is varchar(255); an
        // over-long value used to surface as an unrecordable async
        // write (artifact silently VALIDATING). It must reject at
        // parse time with the actionable code.
        assertThatThrownBy(() -> parser.parse(binaryOf(IpaFixtures.oversizedIdentifierIpa())))
                .isInstanceOf(ExtractionFailure.class)
                .hasMessageContaining("CFBundleIdentifier exceeds 255 characters")
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_PLIST);
    }

    @Test
    void numericBundleIdentifierIsMalformed() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(IpaFixtures.numericIdentifierIpa())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_PLIST);
    }

    @Test
    void zeroByteInfoPlistIsMalformed() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(IpaFixtures.emptyPlistIpa())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MALFORMED_PLIST);
    }

    @Test
    void appBundlesNestedDeeperThanApplesShapeAreMissingPlist() throws IOException {
        assertThatThrownBy(() -> parser.parse(binaryOf(IpaFixtures.nestedAppPathIpa())))
                .isInstanceOf(ExtractionFailure.class)
                .extracting(failure -> ((ExtractionFailure) failure).code())
                .isEqualTo(ExtractionFailure.Code.MISSING_INFO_PLIST);
    }

    @Test
    void unicodeDisplayNamesRoundTripUntouched() throws IOException {
        ExtractedMetadata metadata = parser.parse(binaryOf(IpaFixtures.unicodeDisplayNameIpa()));

        assertThat(metadata.displayName()).isEqualTo("银行银行银行");
        assertThat(metadata.bundleId()).isEqualTo(IpaFixtures.BUNDLE_ID);
    }

    private static ArtifactBinary binaryOf(byte[] bytes) {
        return new ArtifactBinary(bytes.length, new ZipRangesTest.CountingRanges(bytes));
    }
}
