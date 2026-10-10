package com.shipperdog.api.integration.internal.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * The generic webhook verifier's unit legs (issue #53, S12-03): the
 * constant-time HMAC verdict over a rotating secret list (absent,
 * malformed, wrong-key, and unconfigured all refuse), and the
 * timestamp-window replay seam S12-04's store receivers turn on.
 */
class WebhookSignatureVerifierTest {

    private static final String SECRET = "a-fixture-signing-secret-32-chars";
    private static final String OLD_SECRET = "the-rotated-out-secret-32-chars";
    private static final byte[] BODY = "{\"zen\":\"design for failure\"}".getBytes(StandardCharsets.UTF_8);

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void correctSignatureUnderTheConfiguredSecretAccepts() {
        assertThat(verifier.signatureMatches(BODY, signature(SECRET, BODY), List.of(SECRET)))
                .isTrue();
    }

    @Test
    void rotationIsOverlapAnyConfiguredSecretAccepts() {
        assertThat(verifier.signatureMatches(BODY, signature(OLD_SECRET, BODY), List.of(SECRET, OLD_SECRET)))
                .isTrue();
    }

    @Test
    void wrongKeyRefuses() {
        assertThat(verifier.signatureMatches(BODY, signature(OLD_SECRET, BODY), List.of(SECRET)))
                .isFalse();
    }

    @Test
    void emptySecretListRefusesEvenTheCorrectSignature() {
        // The unconfigured receiver is OFF: nothing accepts until an
        // operator configures a secret.
        assertThat(verifier.signatureMatches(BODY, signature(SECRET, BODY), List.of()))
                .isFalse();
    }

    @Test
    void absentOrMalformedHeaderRefuses() {
        assertThat(verifier.signatureMatches(BODY, null, List.of(SECRET))).isFalse();
        assertThat(verifier.signatureMatches(BODY, "   ", List.of(SECRET))).isFalse();
        assertThat(verifier.signatureMatches(BODY, "sha1=deadbeef", List.of(SECRET)))
                .isFalse();
        assertThat(verifier.signatureMatches(BODY, "sha256=not-hex!", List.of(SECRET)))
                .isFalse();
        // Surrounding whitespace around the header value is tolerated
        // (proxy-fed headers), and the hex itself is not
        // case-sensitive -- the sha256= prefix is the wire contract
        // and stays lowercase (GitHub always sends it lowercase).
        String canonical = signature(SECRET, BODY);
        String upperHex = "sha256=" + canonical.substring("sha256=".length()).toUpperCase(java.util.Locale.ROOT);
        assertThat(verifier.signatureMatches(BODY, "  " + upperHex + "  ", List.of(SECRET)))
                .isTrue();
    }

    @Test
    void blankSecretEntriesAreSkippedNeverMatch() {
        assertThat(verifier.signatureMatches(BODY, signature(SECRET, BODY), List.of(" ", "")))
                .isFalse();
    }

    @Test
    void timestampWindowAcceptsFreshAndRejectsStaleFutureAndMissing() {
        Duration window = Duration.ofMinutes(5);
        long now = NOW.getEpochSecond();
        assertThat(verifier.timestampWithinWindow(Long.toString(now), window)).isTrue();
        assertThat(verifier.timestampWithinWindow(Long.toString(now - 60), window))
                .isTrue();
        assertThat(verifier.timestampWithinWindow(Long.toString(now - 600), window))
                .isFalse(); // stale
        assertThat(verifier.timestampWithinWindow(Long.toString(now + 600), window))
                .isFalse(); // future
        assertThat(verifier.timestampWithinWindow(null, window)).isFalse();
        assertThat(verifier.timestampWithinWindow("not-a-number", window)).isFalse();
    }

    // -----------------------------------------------------------------------
    // S12-04 (issue #54): the parsed-instant window (the providers that
    // timestamp their payloads) and the GitLab token family
    // -----------------------------------------------------------------------

    @Test
    void instantWindowAcceptsFreshAndRejectsStaleFutureAndNull() {
        Duration window = Duration.ofMinutes(5);
        assertThat(verifier.withinWindow(NOW, window)).isTrue();
        assertThat(verifier.withinWindow(NOW.minusSeconds(60), window)).isTrue();
        assertThat(verifier.withinWindow(NOW.minusSeconds(600), window)).isFalse();
        assertThat(verifier.withinWindow(NOW.plusSeconds(600), window)).isFalse();
        assertThat(verifier.withinWindow(null, window)).isFalse();
    }

    @Test
    void tokenFamilyAcceptsAnyConfiguredSecretAndRefusesEverythingElse() {
        assertThat(verifier.tokenMatches(SECRET, List.of(SECRET, OLD_SECRET))).isTrue();
        // Rotation is overlap, and surrounding whitespace is tolerated
        // on both sides (proxy-fed headers, operator-pasted config).
        assertThat(verifier.tokenMatches("  " + OLD_SECRET + "  ", List.of(SECRET, " " + OLD_SECRET + " ")))
                .isTrue();
        assertThat(verifier.tokenMatches(OLD_SECRET, List.of(SECRET))).isFalse();
        assertThat(verifier.tokenMatches(SECRET, List.of())).isFalse();
        assertThat(verifier.tokenMatches(null, List.of(SECRET))).isFalse();
        assertThat(verifier.tokenMatches("   ", List.of(SECRET))).isFalse();
        assertThat(verifier.tokenMatches(SECRET, null)).isFalse();
        // Blank secrets never match anything.
        assertThat(verifier.tokenMatches(SECRET, List.of(" ", ""))).isFalse();
    }

    /** GitHub's own derivation: sha256=hex(HMAC-SHA256(secret, body)). */
    private static String signature(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
