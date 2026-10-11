package com.shipperdog.api.distribution.internal.google;

import com.shipperdog.api.distribution.DistributionCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * One resolved Google Play credential as the connector reads it (S13-04,
 * #64, AC1, PRD 25): the SERVICE-ACCOUNT JSON from the delegation chain's
 * secret store — the .p8-to-ES256 analog of the Apple channels is the
 * service-account JSON to an RS256 JWT-bearer exchange. The JSON is
 * Google's own single-file convention and carries every connection fact
 * (client email, PKCS#8 RSA private key, token endpoint), so the binding
 * needs no metadata convention: the material IS the triple.
 *
 * <p>The digest ({@link #cacheKey()}) is a SHA-256 over the whole JSON —
 * the token cache's key and the rotation test's identity: a rotated key
 * file is a different digest by construction, never a stale token.
 */
record GooglePlayCredentials(String clientEmail, String privateKeyPem, String tokenUri) {

    static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";

    static final String FIELD_CLIENT_EMAIL = "client_email";
    static final String FIELD_PRIVATE_KEY = "private_key";
    static final String FIELD_TOKEN_URI = "token_uri";

    /** Reads the credential from a dispatch's resolved credential (the material is the JSON document). */
    static GooglePlayCredentials of(DistributionCommand.ResolvedCredential credential) {
        if (credential == null || credential.material() == null) {
            return new GooglePlayCredentials(null, null, null);
        }
        try {
            var node = MAPPER.readTree(credential.material().value());
            return new GooglePlayCredentials(
                    text(node.get(FIELD_CLIENT_EMAIL)),
                    text(node.get(FIELD_PRIVATE_KEY)),
                    text(node.get(FIELD_TOKEN_URI)));
        } catch (RuntimeException e) {
            // A material that is not the service-account JSON document is
            // a configuration fault, answered by complete() below.
            return new GooglePlayCredentials(null, null, null);
        }
    }

    /**
     * Whether every fact is present AND printable (an incomplete or
     * malformed binding — a truncated JSON, control characters that
     * would corrupt the token's payload — is a configuration fault,
     * never a dial).
     */
    boolean complete() {
        return clientEmail != null
                && !clientEmail.isBlank()
                && privateKeyPem != null
                && !privateKeyPem.isBlank()
                && clientEmail.chars().allMatch(c -> c >= 0x20 && c < 0x7f)
                && privateKeyPem.contains("PRIVATE KEY");
    }

    /** The token endpoint this credential exchanges its assertion at (Google's default when the JSON carried none). */
    String tokenEndpoint() {
        return tokenUri == null || tokenUri.isBlank() ? DEFAULT_TOKEN_URI : tokenUri;
    }

    /** The token cache's key: the document's digest, so no material ever sits in a map key or log line. */
    String cacheKey() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((clientEmail + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(privateKeyPem.getBytes(StandardCharsets.UTF_8));
            digest.update(tokenEndpoint().getBytes(StandardCharsets.UTF_8));
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
        return "GooglePlayCredentials[clientEmail=" + clientEmail + ", privateKey=<redacted>, tokenUri=" + tokenUri
                + "]";
    }

    private static String text(tools.jackson.databind.JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static final tools.jackson.databind.json.JsonMapper MAPPER = new tools.jackson.databind.json.JsonMapper();
}
