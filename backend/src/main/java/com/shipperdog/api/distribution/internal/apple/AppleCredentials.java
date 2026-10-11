package com.shipperdog.api.distribution.internal.apple;

import com.shipperdog.api.distribution.DistributionCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * One resolved Apple credential as the connector reads it (S13-02,
 * #62, AC1, PRD 25): the .p8 key material from the delegation chain's
 * secret store, the issuer id and key id from the store account's
 * non-secret metadata — Apple's own triple. Metadata is an open
 * {@code jsonb} object on the binding row; the Apple provider's
 * convention is {@code {"issuerId": "...", "keyId": "..."}} beside the
 * material, exactly what the App Store Connect console hands an
 * operator creating an API key.
 *
 * <p>The digest ({@link #cacheKey()}) is a SHA-256 over the triple —
 * the token cache's key and the rotation test's identity: a rotated
 * key is a different digest by construction, never a stale token.
 */
record AppleCredentials(String issuerId, String keyId, String pem) {

    static final String METADATA_ISSUER_ID = "issuerId";
    static final String METADATA_KEY_ID = "keyId";

    /** Reads the credential from a dispatch's resolved credential; null fields answer the empty. */
    static AppleCredentials of(DistributionCommand.ResolvedCredential credential) {
        return new AppleCredentials(
                metadataString(credential.metadata(), METADATA_ISSUER_ID),
                metadataString(credential.metadata(), METADATA_KEY_ID),
                credential.material() == null ? "" : credential.material().value());
    }

    /**
     * Whether every fact of the triple is present AND printable (an
     * incomplete or malformed binding — issuer/key ids carrying
     * control characters would corrupt the token's JSON payload — is a
     * configuration fault, never a dial).
     */
    boolean complete() {
        return issuerId != null
                && !issuerId.isBlank()
                && keyId != null
                && !keyId.isBlank()
                && pem != null
                && !pem.isBlank()
                && issuerId.chars().allMatch(c -> c >= 0x20 && c < 0x7f)
                && keyId.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
    }

    /** The token cache's key: the triple's digest, so no material ever sits in a map key or log line. */
    String cacheKey() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((issuerId + "\n" + keyId + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(pem.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * The key never renders: an accidental {@code toString()} (a stack
     * dump, a debug log) answers the redaction marker, the {@link
     * com.shipperdog.api.distribution.SecretMaterial} doctrine.
     */
    @Override
    public String toString() {
        return "AppleCredentials[issuerId=" + issuerId + ", keyId=" + keyId + ", pem=<redacted>]";
    }

    private static String metadataString(String metadata, String key) {
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            var node = MAPPER.readTree(metadata);
            var value = node.get(key);
            return value == null || value.isNull() ? null : value.asString();
        } catch (Exception e) {
            return null;
        }
    }

    private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.json.JsonMapper();
}
