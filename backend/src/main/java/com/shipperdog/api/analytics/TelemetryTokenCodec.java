package com.shipperdog.api.analytics;

import com.shipperdog.api.analytics.internal.telemetry.TelemetryTokenProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The telemetry token codec (issue #72, S15-01): the backend-minted
 * credential the S8-04 download issuance hands the tester telemetry
 * SDK, and the events API's device-side authentication. The token is
 * opaque to the client — {@code sdt1.<base64url(payload)>.
 * <base64url(hmac)>} — exactly the {@code DownloadTokenCodec}
 * doctrine: stateless, tamper-proof by HMAC-SHA256 over the payload
 * with the deployment's key, and carrying everything the ingest needs
 * to authenticate WITHOUT a database read: the reporting tenant, the
 * downloading tester's user id, the release the download issued for,
 * an optional device binding, and the expiry.
 *
 * <p><strong>Narrow capability by construction:</strong> possession
 * of a token grants exactly one thing — posting event batches for
 * (its tenant, its release) to the frozen events contract. No other
 * surface honors the credential, and the bindings are enforced at
 * ingest (path tenant and per-event release must be the token's own;
 * a device-bound token accepts only its device).
 *
 * <p><strong>Revocation levers</strong> (the token is stateless, so
 * revocation rides the facts it binds and the key): the bounded TTL
 * ({@code shipperdog.telemetry.token-ttl}, default 90 days — a test
 * cycle's honest horizon); the device registry's reversible status
 * switch for device-bound tokens (a DEACTIVATED device fails the
 * ingest's ACTIVE check — the two-switches rule); and key rotation
 * ({@code TELEMETRY_TOKEN_KEY} — every minted token stops parsing;
 * the operator's break-glass lever, the same posture as the download
 * proxy key).
 *
 * <p>The local default key is a dev-only value exactly like the JWT
 * secret's and the download proxy key's local defaults; production
 * supplies a real secret through the environment and nothing secret
 * is committed.
 */
@Component
public class TelemetryTokenCodec {

    /** The wire prefix every minted token carries (the filter's discriminator). */
    public static final String TOKEN_PREFIX = "sdt1.";

    /** The payload schema version embedded in every token; a shape change bumps it. */
    private static final byte VERSION = 1;

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final byte[] key;

    private final TelemetryTokenProperties properties;

    private final Clock clock;

    public TelemetryTokenCodec(TelemetryTokenProperties properties, Clock clock) {
        String signingKey = properties.tokenKey();
        this.key = (signingKey == null || signingKey.isBlank() ? "shipperdog-local-telemetry-key" : signingKey)
                .getBytes(StandardCharsets.UTF_8);
        this.properties = properties;
        this.clock = clock;
    }

    /** One minted telemetry grant: what the download issuance binds. */
    public record Grant(UUID tenantId, UUID testerUserId, UUID releaseId, UUID deviceId, Instant expiresAt) {}

    /** The token's lifetime the properties decide (the mint computes the expiry). */
    public java.time.Duration tokenTtl() {
        return properties.tokenTtl();
    }

    /** Mints the opaque token carrying the grant. */
    public String mint(Grant grant) {
        String payload = String.join(
                "|",
                Byte.toString(VERSION),
                grant.tenantId().toString(),
                grant.testerUserId().toString(),
                grant.releaseId().toString(),
                grant.deviceId() == null ? "" : grant.deviceId().toString(),
                Long.toString(grant.expiresAt().getEpochSecond()));
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return TOKEN_PREFIX + URL_ENCODER.encodeToString(payloadBytes) + "."
                + URL_ENCODER.encodeToString(mac(payloadBytes));
    }

    /** The grant for {@code now + ttl} of the caller's bindings (mint computes the token of it). */
    public Grant grantForNow(UUID tenantId, UUID testerUserId, UUID releaseId, UUID deviceId) {
        return new Grant(
                tenantId, testerUserId, releaseId, deviceId, Instant.now(clock).plus(properties.tokenTtl()));
    }

    /**
     * Verifies and parses a token: the MAC must recompute over the
     * payload (constant-time) and the expiry must still be in the
     * future. Empty on any failure — the caller renders the uniform
     * 401, never a reason (a caller learns neither which check failed
     * nor that the release exists).
     */
    public Optional<TelemetryTokenPrincipal> parse(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        String body = token.substring(TOKEN_PREFIX.length());
        int separator = body.indexOf('.');
        if (separator <= 0 || separator == body.length() - 1) {
            return Optional.empty();
        }
        byte[] payloadBytes;
        byte[] signature;
        try {
            payloadBytes = URL_DECODER.decode(body.substring(0, separator));
            signature = URL_DECODER.decode(body.substring(separator + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(mac(payloadBytes), signature)) {
            return Optional.empty();
        }
        String[] fields = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
        if (fields.length != 6 || !"1".equals(fields[0])) {
            return Optional.empty();
        }
        try {
            UUID tenantId = UUID.fromString(fields[1]);
            UUID testerUserId = UUID.fromString(fields[2]);
            UUID releaseId = UUID.fromString(fields[3]);
            UUID deviceId = fields[4].isEmpty() ? null : UUID.fromString(fields[4]);
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(fields[5]));
            if (!Instant.now(clock).isBefore(expiresAt)) {
                return Optional.empty();
            }
            return Optional.of(new TelemetryTokenPrincipal(tenantId, testerUserId, releaseId, deviceId, expiresAt));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (InvalidKeyException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot mint the telemetry token: " + e.getMessage(), e);
        }
    }
}
