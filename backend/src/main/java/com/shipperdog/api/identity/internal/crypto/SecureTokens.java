package com.shipperdog.api.identity.internal.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generation and hashing of opaque token material. Raw tokens are 256 bits
 * of {@link SecureRandom} output in URL-safe base64; only their SHA-256
 * hex digests are persisted or compared, so database access never yields a
 * usable credential.
 */
public final class SecureTokens {

    /** Prefix identifying ShipperDog personal access tokens. */
    public static final String PAT_PREFIX = "sdp_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() {}

    /** A fresh 256-bit random token, URL-safe base64 (43 characters). */
    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A fresh personal-access token, e.g. {@code sdp_<43 chars>}. */
    public static String personalAccessToken() {
        return PAT_PREFIX + randomToken();
    }

    /** Lowercase SHA-256 hex digest of the raw token, for storage/lookup. */
    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
