package com.shipperdog.api.artifact.internal.download;

import com.shipperdog.api.artifact.ArtifactException;
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
 * The emulator-mode download token (issue #38, S8-04, ADR-0003's
 * download-tier amendment): the backend-minted credential that stands
 * in for a GCS V4 signature wherever the storage target cannot verify
 * one (the compose emulator). The token is opaque to the client —
 * {@code base64url(payload).base64url(hmac)} — and carries everything
 * the proxy needs to serve WITHOUT a database read: the artifact id
 * (attribution), the storage key, the file name, and the byte size,
 * plus the expiry instant the issuance decided. Tamper-proof by
 * HMAC-SHA256 over the payload with the deployment's key; a token the
 * server did not mint, or one past its expiry, parses to nothing and
 * the proxy answers the uniform opaque 404 (INV-005's no-leak rule —
 * the caller learns neither which check failed nor that the artifact
 * exists).
 *
 * <p>The storage key INSIDE the token is not an exposure: it never
 * reaches a client in the clear, cannot be altered (the MAC covers
 * it), and grants nothing by itself — the buckets stay private and
 * the proxy is the only reader this URL form opens, bounded by the
 * expiry. Production deployments never mint these tokens (GCS V4
 * signing is the mechanism there); the codec is the emulator branch
 * of one contract: a short-lived URL whose lifetime the server owns.
 */
@Component
public class DownloadTokenCodec {

    /** The payload schema version; a future shape change bumps this. */
    private static final byte VERSION = 1;

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final byte[] key;
    private final Clock clock;

    public DownloadTokenCodec(ArtifactDownloadProperties properties, Clock clock) {
        String signingKey = properties.proxySigningKey();
        this.key = (signingKey == null || signingKey.isBlank() ? "shipperdog-local-download-key" : signingKey)
                .getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** One minted download grant: what the issuance wrote into the URL. */
    public record Grant(UUID artifactId, String storageKey, String fileName, long size, Instant expiresAt) {}

    /** Mints the opaque token carrying the grant. */
    public String mint(Grant grant) {
        String payload = String.join(
                "|",
                Byte.toString(VERSION),
                grant.artifactId().toString(),
                Long.toString(grant.expiresAt().getEpochSecond()),
                Long.toString(grant.size()),
                grant.fileName() == null ? "" : grant.fileName(),
                grant.storageKey());
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return URL_ENCODER.encodeToString(payloadBytes) + "." + URL_ENCODER.encodeToString(mac(payloadBytes));
    }

    /**
     * Verifies and parses a token: the MAC must recompute over the
     * payload (constant-time) and the expiry must still be in the
     * future. Empty on any failure — the caller renders the uniform
     * miss, never a reason.
     */
    public Optional<Grant> parse(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int separator = token.indexOf('.');
        if (separator <= 0 || separator == token.length() - 1) {
            return Optional.empty();
        }
        byte[] payloadBytes;
        byte[] signature;
        try {
            payloadBytes = URL_DECODER.decode(token.substring(0, separator));
            signature = URL_DECODER.decode(token.substring(separator + 1));
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
            UUID artifactId = UUID.fromString(fields[1]);
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(fields[2]));
            long size = Long.parseLong(fields[3]);
            String fileName = fields[4].isEmpty() ? null : fields[4];
            String storageKey = fields[5];
            if (storageKey.isBlank() || !Instant.now(clock).isBefore(expiresAt)) {
                return Optional.empty();
            }
            return Optional.of(new Grant(artifactId, storageKey, fileName, size, expiresAt));
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
            throw new ArtifactException(
                    ArtifactException.Code.STORAGE_UNAVAILABLE, "cannot mint the download token: " + e.getMessage());
        }
    }
}
