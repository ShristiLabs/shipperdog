package com.shipperdog.api.otabundle;

import com.shipperdog.api.otabundle.internal.service.OtaAppTokenProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * The OTA app-token codec (issue #166, S16-04, ADR-0010 D3): mints the
 * {@code sdc1.} device identifier the console/CLI issues per
 * application and the shipped app embeds — the credential the device
 * check presents BEFORE any download (and so before any {@code sdt1.}
 * telemetry grant exists). The token is opaque to the client —
 * {@code sdc1.<base64url(payload)>.<base64url(hmac)>} — exactly the
 * {@code TelemetryTokenCodec} doctrine: stateless, tamper-proof by
 * HMAC-SHA256 over the payload with the deployment's key, and carrying
 * everything a device request needs to resolve unambiguously: the
 * owning organization, the application, and the registry row's id
 * (bundleId is NOT tenant-unique — the token, not the store listing,
 * carries the resolution).
 *
 * <p><strong>Identifier, not a secret</strong> (D3): possession of a
 * token grants exactly one thing — presenting the device check
 * ({@code GET /api/v1/ota/check}, S16-07) under the one authority
 * {@code ota:app-token:check}. It enables rate limiting and org/
 * tenant/app resolution; mTLS was rejected because mobile fleets
 * cannot do it. Unlike the telemetry grant there is NO expiry: the
 * identifier's lifetime is its registry row's (V55 {@code
 * ota_app_tokens}), so a fleet's embedded token never ages out of the
 * shipped binary. Revocation rides the row ({@code revoked_at} — the
 * filter's registry lookup refuses it immediately) and the key:
 * rotating {@code OTA_APP_TOKEN_KEY} is the operator's break-glass
 * lever that retires every minted token at once, the same posture as
 * the telemetry and download-proxy keys.
 *
 * <p>The row id rides the payload so two tokens issued for the same
 * application are always distinct material (a codec over org+app alone
 * would mint identical strings — the registry's unique {@code
 * token_hash} would collapse them into one row). The MAC covers it, so
 * a token cannot be re-pointed at another registry row.
 *
 * <p>Like the codec and principal, this class sits at the module's
 * public root rather than in {@code internal}: the identity module's
 * security chain consumes the wire prefix (its bearer-token resolver
 * must skip {@code sdc1.} the way it skips PATs and {@code sdt1.}),
 * and the ArchUnit module-boundary rule forbids cross-module {@code
 * .internal.} imports — the root is the otabundle module's published
 * surface, the {@code TelemetryTokenCodec} placement.
 *
 * <p>The local default key is a dev-only value exactly like the JWT
 * secret's and the telemetry key's local defaults; production supplies
 * a real secret through the environment and nothing secret is
 * committed.
 */
@Component
public class OtaAppTokenCodec {

    /** The wire prefix every minted app token carries (the filter's discriminator). */
    public static final String TOKEN_PREFIX = "sdc1.";

    /** The payload schema version embedded in every token; a shape change bumps it. */
    private static final byte VERSION = 1;

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final byte[] key;

    public OtaAppTokenCodec(OtaAppTokenProperties properties) {
        String signingKey = properties.appTokenKey();
        this.key = (signingKey == null || signingKey.isBlank() ? "shipperdog-local-ota-app-token-key" : signingKey)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** One minted app token's claims: the resolution triple the payload carries. */
    public record Claims(UUID organizationId, UUID applicationId, UUID tokenId) {}

    /** Mints the opaque identifier carrying the claims (the registry row already owns revocation). */
    public String mint(UUID organizationId, UUID applicationId, UUID tokenId) {
        String payload = String.join(
                "|", Byte.toString(VERSION), organizationId.toString(), applicationId.toString(), tokenId.toString());
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return TOKEN_PREFIX + URL_ENCODER.encodeToString(payloadBytes) + "."
                + URL_ENCODER.encodeToString(mac(payloadBytes));
    }

    /**
     * Verifies and parses a token: the MAC must recompute over the
     * payload (constant-time). Empty on any failure — the caller
     * renders the uniform 401, never a reason (a device learns neither
     * which check failed nor that the application exists). Registry
     * state (revocation, row coherence) is the service resolution's
     * question, deliberately not the codec's: a stateless parse stays
     * cacheable and the registry stays the one owner of "still valid".
     */
    public Optional<Claims> parse(String token) {
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
        if (fields.length != 4 || !"1".equals(fields[0])) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new Claims(UUID.fromString(fields[1]), UUID.fromString(fields[2]), UUID.fromString(fields[3])));
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
            throw new IllegalStateException("cannot mint the OTA app token: " + e.getMessage(), e);
        }
    }
}
