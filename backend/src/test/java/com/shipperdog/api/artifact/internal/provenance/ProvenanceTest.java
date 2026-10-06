package com.shipperdog.api.artifact.internal.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.artifact.ArtifactException;
import org.junit.jupiter.api.Test;

/**
 * The provenance contract (issue #23, S5-04, FR-005): the five frozen
 * build claims — optional at upload, shaped when present, never
 * fabricated. Every leg here pins one acceptance criterion of the
 * story's validation matrix: SHA-1/SHA-256 commits, printable branch
 * and pipeline labels, absolute http/https run URLs, ISO-8601
 * instants canonicalized to UTC, blank-equals-absent, and the
 * one-rejection-with-every-field-error answer of a malformed request.
 */
class ProvenanceTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";

    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static Provenance of(String gitCommit, String branch, String pipeline, String url, String timestamp) {
        return Provenance.of(new Provenance.Fields(gitCommit, branch, pipeline, url, timestamp));
    }

    // -----------------------------------------------------------------------
    // The happy shapes
    // -----------------------------------------------------------------------

    @Test
    void sha1AndSha256CommitsAreAcceptedLowercase() {
        assertThat(of(SHA1, null, null, null, null).gitCommit()).isEqualTo(SHA1);
        assertThat(of(SHA256, null, null, null, null).gitCommit()).isEqualTo(SHA256);
    }

    @Test
    void uppercaseCommitHexNormalizesToLowercase() {
        assertThat(of(SHA1.toUpperCase(java.util.Locale.ROOT), null, null, null, null)
                        .gitCommit())
                .isEqualTo(SHA1);
    }

    @Test
    void fullClaimsParseNormalizeAndRoundTrip() {
        Provenance provenance = of(
                SHA256.toUpperCase(java.util.Locale.ROOT),
                "  release/7.12.0  ",
                " nightly ",
                "https://ci.acme.dev/run/842",
                "2026-10-04T14:30:05+02:00");
        assertThat(provenance.gitCommit()).isEqualTo(SHA256);
        assertThat(provenance.gitBranch()).isEqualTo("release/7.12.0");
        assertThat(provenance.pipeline()).isEqualTo("nightly");
        assertThat(provenance.ciRunUrl()).isEqualTo("https://ci.acme.dev/run/842");
        // The +02:00 instant canonicalizes to UTC.
        assertThat(provenance.buildTimestamp()).isEqualTo("2026-10-04T12:30:05Z");

        // The document stores ONLY asserted members and reads back equal.
        String document = provenance.toJson();
        assertThat(document)
                .isEqualTo("{\"gitCommit\":\"" + SHA256 + "\",\"gitBranch\":\"release/7.12.0\","
                        + "\"pipeline\":\"nightly\",\"ciRunUrl\":\"https://ci.acme.dev/run/842\","
                        + "\"buildTimestamp\":\"2026-10-04T12:30:05Z\"}");
        assertThat(Provenance.from(document)).isEqualTo(provenance);
    }

    @Test
    void absentMembersStayAbsentInTheDocumentAndTheReadBack() {
        String document = of(null, null, "mobile-release", null, null).toJson();
        assertThat(document).isEqualTo("{\"pipeline\":\"mobile-release\"}");
        Provenance read = Provenance.from(document);
        assertThat(read.pipeline()).isEqualTo("mobile-release");
        assertThat(read.gitCommit()).isNull();
        assertThat(read.gitBranch()).isNull();
        assertThat(read.ciRunUrl()).isNull();
        assertThat(read.buildTimestamp()).isNull();
    }

    // -----------------------------------------------------------------------
    // Optional, never fabricated (the story's second criterion)
    // -----------------------------------------------------------------------

    @Test
    void entirelyAbsentProvenanceAnswersNull() {
        assertThat(of(null, null, null, null, null)).isNull();
    }

    @Test
    void blankInputsAreAbsenceNotValues() {
        assertThat(of("", "   ", "\t", null, " ")).isNull();
    }

    @Test
    void readBackToleratesUnknownKeysAndUnreadableDocuments() {
        assertThat(Provenance.from("{\"gitCommit\":\"" + SHA1 + "\",\"futureField\":true}")
                        .gitCommit())
                .isEqualTo(SHA1);
        assertThat(Provenance.from(null)).isNull();
        assertThat(Provenance.from("  ")).isNull();
        assertThat(Provenance.from("not json at all")).isNull();
    }

    // -----------------------------------------------------------------------
    // Malformed shapes reject with field errors (the fourth criterion)
    // -----------------------------------------------------------------------

    @Test
    void malformedCommitShapesRejectNamingTheField() {
        for (String bad : new String[] {
            "0123456789abcdef0123456789abcdef0123456", // 39 hex
            SHA1 + "ab", // 42 hex
            SHA256.substring(0, 63), // 63 hex
            "zz" + SHA1.substring(2), // non-hex
            "HEAD",
            "main~1"
        }) {
            assertThatThrownBy(() -> of(bad, null, null, null, null))
                    .isInstanceOf(ArtifactException.class)
                    .hasMessageContaining("git_commit")
                    .satisfies(e ->
                            assertThat(((ArtifactException) e).fieldErrors()).containsKey("git_commit"));
        }
    }

    @Test
    void malformedUrlsRejectNamingTheField() {
        for (String bad : new String[] {
            "not a url",
            "git@github.com:acme/app.git",
            "ftp://ci.acme.dev/run/842",
            "/relative/path",
            "http://",
            "acme.dev/run/842",
            "https://".repeat(257)
        }) {
            assertThatThrownBy(() -> of(null, null, null, bad, null))
                    .isInstanceOf(ArtifactException.class)
                    .satisfies(e ->
                            assertThat(((ArtifactException) e).fieldErrors()).containsKey("ci_run_url"));
        }
    }

    @Test
    void malformedTimestampsRejectNamingTheField() {
        for (String bad : new String[] {"yesterday", "2026-10-04", "2026-10-04T12:30:05", "unix:1760000000"}) {
            assertThatThrownBy(() -> of(null, null, null, null, bad))
                    .isInstanceOf(ArtifactException.class)
                    .satisfies(e ->
                            assertThat(((ArtifactException) e).fieldErrors()).containsKey("build_timestamp"));
        }
    }

    @Test
    void branchAndPipelineRejectOverlongAndControlCharacters() {
        assertThatThrownBy(() -> of(null, "b".repeat(256), null, null, null))
                .isInstanceOf(ArtifactException.class)
                .satisfies(
                        e -> assertThat(((ArtifactException) e).fieldErrors()).containsKey("git_branch"));
        assertThatThrownBy(() -> of(null, null, "p".repeat(256), null, null))
                .isInstanceOf(ArtifactException.class)
                .satisfies(
                        e -> assertThat(((ArtifactException) e).fieldErrors()).containsKey("pipeline"));
        assertThatThrownBy(() -> of(null, "bad\u0007branch", null, null, null))
                .isInstanceOf(ArtifactException.class)
                .satisfies(
                        e -> assertThat(((ArtifactException) e).fieldErrors()).containsKey("git_branch"));
        // The boundary itself passes.
        assertThat(of(null, "b".repeat(255), null, null, null).gitBranch()).hasSize(255);
    }

    @Test
    void everyOffendingFieldIsNamedInOneRejection() {
        assertThatThrownBy(() -> of("HEAD", "ok-branch", "ok-pipeline", "not a url", "soon"))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> {
                    ArtifactException rejection = (ArtifactException) e;
                    assertThat(rejection.code()).isEqualTo(ArtifactException.Code.INVALID_REQUEST);
                    assertThat(rejection.fieldErrors()).containsOnlyKeys("git_commit", "ci_run_url", "build_timestamp");
                });
    }

    @Test
    void urlAndTimestampBoundariesAccept() {
        assertThat(of(null, null, null, "http://ci.acme.dev", null).ciRunUrl()).isEqualTo("http://ci.acme.dev");
        assertThat(of(null, null, null, "https://ci.acme.dev:8443/run/842?step=3#log", null)
                        .ciRunUrl())
                .isEqualTo("https://ci.acme.dev:8443/run/842?step=3#log");
        assertThat(of(null, null, null, null, "2026-10-04T12:30:05Z").buildTimestamp())
                .isEqualTo("2026-10-04T12:30:05Z");
        assertThat(of(null, null, null, null, "2026-10-04T12:30:05.123456789Z").buildTimestamp())
                .isEqualTo("2026-10-04T12:30:05.123456789Z");
    }

    // -----------------------------------------------------------------------
    // Adversarial edges (tester pass)
    // -----------------------------------------------------------------------

    @Test
    void urlLengthBoundaryIsExact() {
        String base = "https://ci.acme.dev/";
        String at2048 = base + "a".repeat(2048 - base.length());
        assertThat(of(null, null, null, at2048, null).ciRunUrl()).hasSize(2048);
        assertThatThrownBy(() -> of(null, null, null, at2048 + "a", null))
                .isInstanceOf(ArtifactException.class)
                .satisfies(
                        e -> assertThat(((ArtifactException) e).fieldErrors()).containsKey("ci_run_url"));
    }

    @Test
    void controlCharactersCannotHideInsideUrlsOrTimestamps() {
        // java.net.URI ACCEPTS embedded NULs (the tester-pass catch),
        // and a \u0000 inside a stored jsonb string is a database
        // refusal — the explicit control check must answer the 400.
        assertThatThrownBy(() -> of(null, null, null, "https://ci.acme.dev/run\u0000/842", null))
                .isInstanceOf(ArtifactException.class)
                .satisfies(
                        e -> assertThat(((ArtifactException) e).fieldErrors()).containsKey("ci_run_url"));
        // Garbage timestamps fail parse, never reach storage; an
        // all-whitespace/NUL timestamp is ABSENCE (trim semantics),
        // not a value.
        assertThatThrownBy(() -> of(null, null, null, null, "soon\u0007ish"))
                .isInstanceOf(ArtifactException.class)
                .satisfies(
                        e -> assertThat(((ArtifactException) e).fieldErrors()).containsKey("build_timestamp"));
        assertThat(of(null, null, null, null, "\u0000".repeat(5000))).isNull();
    }

    @Test
    void printableNonAsciiLabelsAreClaimsNotThreats() {
        // Accented labels pass the printable check: length-capped
        // claims rendered as JSON data, no control characters to split
        // logs, never interpolated anywhere.
        assertThat(of(null, "release/7.12.0-rc1", "café-nightly", null, null).pipeline())
                .isEqualTo("café-nightly");
    }

    @Test
    void paddedMixedCaseCommitNormalizes() {
        String padded = "  " + SHA1.toUpperCase(java.util.Locale.ROOT) + "  ";
        assertThat(of(padded, null, null, null, null).gitCommit()).isEqualTo(SHA1);
    }

    @Test
    void timestampExtremesAreClientClaimsAndStayParseable() {
        assertThat(of(null, null, null, null, "1970-01-01T00:00:00Z").buildTimestamp())
                .isEqualTo("1970-01-01T00:00:00Z");
        assertThat(of(null, null, null, null, "9999-12-31T23:59:59Z").buildTimestamp())
                .isEqualTo("9999-12-31T23:59:59Z");
    }
}
