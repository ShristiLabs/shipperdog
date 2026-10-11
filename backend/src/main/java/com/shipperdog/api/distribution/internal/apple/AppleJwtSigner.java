package com.shipperdog.api.distribution.internal.apple;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apple's App Store Connect API token, signed here (S13-02, #62,
 * FR-020): an ES256 JWS whose header carries the key id and whose
 * payload is exactly Apple's model — issuer id, issued-at, expiry at
 * most twenty minutes out, audience {@code asc}. Signing is the JDK's
 * own JCA ({@code SHA256withECDSA} over the P-256 key from the .p8
 * PEM, the DER signature converted to JWS's raw {@code r||s} form) —
 * no JWT library enters the tree for six claims.
 *
 * <p><strong>The cache:</strong> one live token per (issuer, key id,
 * key material) triple, refreshed a minute before expiry. Dispatches
 * and syncs reuse the live token; a rotated key is a different triple,
 * so its first call mints fresh and the superseded entry is never
 * answered again (rotation handled by construction, AC1). The cache
 * KEY is the credential's digest, never the material; the material is
 * read exactly once per mint, on this class's stack, and never leaves
 * it.
 */
final class AppleJwtSigner {

    /** Apple's audience constant: the token speaks to App Store Connect, not Apple ID. */
    static final String AUDIENCE = "asc";

    /** Apple's maximum token lifetime (a longer window is rejected). */
    static final Duration MAX_LIFETIME = Duration.ofMinutes(20);

    /** The refresh margin: a token is reminted this long before its expiry. */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(1);

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final Clock clock;
    private final Map<String, Token> cache = new ConcurrentHashMap<>();

    AppleJwtSigner(Clock clock) {
        this.clock = clock;
    }

    /** The bearer token for the credential, minted or cached (the caller validated the credential). */
    String tokenOf(AppleCredentials credential) {
        Instant now = Instant.now(clock);
        String cacheKey = credential.cacheKey();
        Token live = cache.get(cacheKey);
        if (live == null || now.isAfter(live.expiry().minus(REFRESH_MARGIN))) {
            live = mint(credential, now);
            cache.put(cacheKey, live);
        }
        return live.value();
    }

    private Token mint(AppleCredentials credential, Instant now) {
        Instant expiry = now.plus(MAX_LIFETIME);
        String header = "{\"alg\":\"ES256\",\"kid\":\"" + escape(credential.keyId()) + "\"}";
        String payload = "{\"iss\":\""
                + escape(credential.issuerId()) + "\",\"iat\":" + now.getEpochSecond() + ",\"exp\":"
                + expiry.getEpochSecond() + ",\"aud\":\"" + AUDIENCE + "\"}";
        String signingInput = encode(header) + "." + encode(payload);
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKeyOf(credential.pem()));
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            byte[] raw = jwsSignature(signer.sign());
            return new Token(signingInput + "." + URL.encodeToString(raw), expiry);
        } catch (KeyFault e) {
            throw new IllegalStateException("the Apple key could not sign: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("the Apple key could not sign", e);
        }
    }

    /** The .p8 PEM (PKCS#8) to a JCA private key; strips the PEM armor Apple ships. */
    private static PrivateKey privateKeyOf(String pem) throws KeyFault {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        if (base64.isEmpty()) {
            throw new KeyFault("the Apple key material is empty");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new KeyFault("the Apple key is not a valid PEM payload");
        }
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new KeyFault("the Apple key is not a valid EC PKCS#8 key");
        }
    }

    /**
     * JCA's {@code SHA256withECDSA} answers the DER {@code SEQUENCE(r,
     * s)}; JWS ES256 wants the two fixed-width 32-byte halves. The
     * integers are left-padded (a high-byte P-256 integer DER-encodes
     * short) and right-truncated to their fixed width, which is exact
     * because P-256's field elements never exceed 32 bytes.
     */
    private static byte[] jwsSignature(byte[] der) throws KeyFault {
        if (der.length < 8 || der[0] != 0x30 || der[1] != der.length - 2) {
            throw new KeyFault("the Apple key's signature was not DER");
        }
        int rOffset = 4;
        int rLength = der[3];
        if (rLength < 1 || rLength > 33) {
            throw new KeyFault("the Apple key's signature r-half is malformed");
        }
        int sOffset = rOffset + rLength + 2;
        if (sOffset >= der.length) {
            throw new KeyFault("the Apple key's signature s-half is missing");
        }
        int sLength = der[sOffset - 1];
        if (sLength < 1 || sLength > 33 || sOffset + sLength != der.length) {
            throw new KeyFault("the Apple key's signature s-half is malformed");
        }
        byte[] raw = new byte[64];
        copyFixed(der, rOffset, rLength, raw, 0);
        copyFixed(der, sOffset, sLength, raw, 32);
        return raw;
    }

    private static void copyFixed(byte[] der, int offset, int length, byte[] raw, int destination) {
        int start = offset + (length > 32 ? length - 32 : 0);
        int take = Math.min(length, 32);
        System.arraycopy(der, start, raw, destination + (32 - take), take);
    }

    private static String encode(String json) {
        return URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record Token(String value, Instant expiry) {}

    /** The signer's own fault: a key that cannot sign (empty, malformed, wrong format). */
    static final class KeyFault extends Exception {
        KeyFault(String message) {
            super(message);
        }
    }
}
