package com.shipperdog.api.integration.internal.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The generic inbound-webhook signature verification every provider
 * receiver shares (issue #53, S12-03, FR-022, and the framework seam
 * S12-04's Jenkins/GitLab/store receivers and E13's connectors
 * reuse). Two independent defenses live here:
 *
 * <p><b>Authenticity</b> -- the {@code sha256=<hex>} HMAC-SHA256
 * header over the exact received body bytes, checked constant-time
 * against a LIST of configured secrets: rotation is overlap, never
 * cutover (every entry is tried, the first match accepts). GitHub's
 * {@code X-Hub-Signature-256} is this shape verbatim; the S12-04
 * store receivers compute the same HMAC with their own key and header
 * name. An empty secret list accepts nothing (the receiver is off
 * until an operator configures it), and EVERY authenticity failure is
 * the caller's uniform answer -- absent header, malformed prefix,
 * wrong hex, wrong key: indistinguishable by design (the throw site
 * decides the response, this class decides the verdict).
 *
 * <p><b>Replay</b> -- the timestamp-window check for providers that
 * stamp their deliveries (epoch seconds in a header; Apple's and
 * Google's store callbacks both do). GitHub sends no timestamp
 * header, so its deliveries never enter the window and its replay
 * defense is the delivery-id ledger alone (a deliberate, documented
 * posture: GitHub delivery ids are unique per delivery ATTEMPT, so a
 * network replay of the same delivery is the ledger's duplicate; a
 * re-delivery GitHub itself sends is a NEW delivery id and a
 * legitimate redelivery the receiver processes idempotently). The
 * seam is real and unit-tested -- S12-04 turns it on by naming the
 * header.
 */
@Component
public class WebhookSignatureVerifier {

    /** The header prefix every provider of the sha256 family uses. */
    public static final String SHA256_PREFIX = "sha256=";

    private final Clock clock;

    public WebhookSignatureVerifier(Clock clock) {
        this.clock = clock;
    }

    /**
     * The authenticity verdict: does the {@code sha256=<hex>} HMAC
     * header match the exact body bytes under ANY configured secret?
     */
    public boolean signatureMatches(byte[] body, String signatureHeader, Iterable<String> secrets) {
        if (signatureHeader == null || secrets == null) {
            return false;
        }
        String trimmed = signatureHeader.trim();
        if (!trimmed.startsWith(SHA256_PREFIX)) {
            return false;
        }
        String hex = trimmed.substring(SHA256_PREFIX.length()).toLowerCase(java.util.Locale.ROOT);
        byte[] claimed;
        try {
            claimed = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (String secret : secrets) {
            if (secret == null || secret.isBlank()) {
                continue;
            }
            if (MessageDigest.isEqual(claimed, hmac(secret, body))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The replay verdict for timestamped providers: is the delivery's
     * epoch-seconds stamp inside the configured window of now? A
     * missing stamp is REJECTED here -- a provider configured for
     * timestamped deliveries must send one; GitHub is not configured
     * for the window at all and never calls this method.
     */
    public boolean timestampWithinWindow(String epochSecondsHeader, Duration window) {
        if (epochSecondsHeader == null) {
            return false;
        }
        long delivered;
        try {
            delivered = Long.parseLong(epochSecondsHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        return withinWindow(Instant.ofEpochSecond(delivered), window);
    }

    /**
     * The replay verdict over an already-parsed stamp (S12-04): the
     * providers that timestamp their deliveries inside the PAYLOAD
     * rather than a header -- Jenkins buildTimestamp, GitLab pipeline
     * timestamps, Apple signedDate, Google publishTime -- hand the
     * verifier the parsed {@link Instant} after their authenticity
     * leg passed, and this is the same window arithmetic over it. A
     * null stamp is not fresh: a consumed event shape whose timestamp
     * is missing degrades to a no-op (IGNORED), never to a silent
     * pass.
     */
    public boolean withinWindow(Instant stamped, Duration window) {
        if (stamped == null) {
            return false;
        }
        Duration drift = Duration.between(stamped, Instant.now(clock)).abs();
        return drift.compareTo(window) <= 0;
    }

    /**
     * The GitLab family's authenticity verdict: does the delivered
     * {@code X-Gitlab-Token} equal any configured secret? Compared
     * constant-time per entry over the rotating list, the same
     * rotation-is-overlap contract as the HMAC family -- but the
     * token rides the header verbatim (GitLab's protocol signs
     * nothing), so the transport must be TLS; an empty header or an
     * empty list accepts nothing (the receiver is off until an
     * operator configures it).
     */
    public boolean tokenMatches(String tokenHeader, Iterable<String> secrets) {
        if (tokenHeader == null || tokenHeader.isBlank() || secrets == null) {
            return false;
        }
        byte[] presented = tokenHeader.trim().getBytes(StandardCharsets.UTF_8);
        for (String secret : secrets) {
            if (secret == null || secret.isBlank()) {
                continue;
            }
            if (MessageDigest.isEqual(presented, secret.trim().getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private static byte[] hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (Exception e) {
            throw new IllegalStateException("cannot compute the webhook HMAC", e);
        }
    }
}
