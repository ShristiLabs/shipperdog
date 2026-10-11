package com.shipperdog.api.distribution.internal.apple;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The ES256 token's own proof (issue #62, S13-02, AC1): the JWS a
 * verifier (the mock ASC server included) checks — the claims Apple
 * demands (issuer, key id, aud asc, the twenty-minute window), the
 * signature a third party verifies with the public key, and the cache
 * that refreshes (a rotated key mints fresh; a live token is reused
 * until its margin).
 */
class AppleJwtSignerTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    @Test
    void tokenCarriesApplesClaimModelAndVerifiesUnderThePublicKey() throws Exception {
        KeyPair pair = pair();
        AppleCredentials credential = credentialOf(pair);
        AppleJwtSigner signer = new AppleJwtSigner(clockAt(NOW));

        String token = signer.tokenOf(credential);
        String[] parts = token.split("\\.");
        String header = decode(parts[0]);
        String payload = decode(parts[1]);

        // Apple's model: ES256 + kid in the header; iss/iat/exp/aud in
        // the payload; the window at most twenty minutes.
        assertThat(header).contains("\"alg\":\"ES256\"").contains("\"kid\":\"KEY42\"");
        assertThat(payload).contains("\"iss\":\"iss-uuid-1\"").contains("\"aud\":\"asc\"");
        long iat = Long.parseLong(extract(payload, "\"iat\":", ','));
        long exp = Long.parseLong(extract(payload, "\"exp\":", ','));
        assertThat(iat).isEqualTo(NOW.getEpochSecond());
        assertThat(Duration.ofSeconds(exp - iat)).isEqualTo(AppleJwtSigner.MAX_LIFETIME);
        assertThat(exp - iat).isLessThanOrEqualTo(20 * 60);

        // The signature verifies under the public key (raw r||s).
        byte[] raw = Base64.getUrlDecoder().decode(parts[2]);
        assertThat(raw).hasSize(64);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(verifier.verify(toDer(raw))).isTrue();
    }

    @Test
    void cacheReusesTheLiveTokenAndRefreshesNearExpiry() throws Exception {
        KeyPair pair = pair();
        AppleCredentials credential = credentialOf(pair);
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        AppleJwtSigner signer = new AppleJwtSigner(new Clock() {
            @Override
            public Instant instant() {
                return now.get();
            }

            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }
        });

        String first = signer.tokenOf(credential);
        // Inside the margin: the same live token, no remint.
        now.set(NOW.plus(Duration.ofMinutes(18)));
        assertThat(signer.tokenOf(credential)).isSameAs(first);
        // Past the margin (a minute before expiry): a fresh mint.
        now.set(NOW.plusSeconds(19 * 60 + 30));
        assertThat(signer.tokenOf(credential)).isNotSameAs(first);
    }

    @Test
    void aRotatedKeyMintsFreshImmediately() throws Exception {
        KeyPair first = pair();
        KeyPair second = pair();
        AppleJwtSigner signer = new AppleJwtSigner(clockAt(NOW));
        String before = signer.tokenOf(credentialOf(first));
        String after = signer.tokenOf(credentialOf(second));
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void malformedMaterialIsTheConfigurationFaultNotADial() {
        AppleCredentials credential = new AppleCredentials("iss-uuid-1", "KEY42", "not a pem at all");
        AppleJwtSigner signer = new AppleJwtSigner(clockAt(NOW));
        assertThatThrownBy(() -> signer.tokenOf(credential))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the Apple key");
    }

    private AppleCredentials credentialOf(KeyPair pair) throws Exception {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return new AppleCredentials("iss-uuid-1", "KEY42", pem);
    }

    private static KeyPair pair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Clock clockAt(Instant at) {
        return Clock.fixed(at, ZoneOffset.UTC);
    }

    private static String decode(String segment) {
        return new String(Base64.getUrlDecoder().decode(segment), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String extract(String json, String marker, char terminal) {
        int start = json.indexOf(marker) + marker.length();
        int end = json.indexOf(terminal, start);
        return json.substring(start, end).trim();
    }

    /** Raw JWS r||s back to DER (the JDK verifier's expected shape): minimal integers, high-bit padded. */
    private static byte[] toDer(byte[] raw) {
        byte[] r = derInteger(java.util.Arrays.copyOfRange(raw, 0, 32));
        byte[] s = derInteger(java.util.Arrays.copyOfRange(raw, 32, 64));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x30);
        out.write(r.length + s.length);
        out.write(r, 0, r.length);
        out.write(s, 0, s.length);
        return out.toByteArray();
    }

    private static byte[] derInteger(byte[] magnitude) {
        int leading = 0;
        while (leading < magnitude.length - 1 && magnitude[leading] == 0) {
            leading++;
        }
        byte[] minimal = java.util.Arrays.copyOfRange(magnitude, leading, magnitude.length);
        boolean pad = (minimal[0] & 0x80) != 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x02);
        out.write(minimal.length + (pad ? 1 : 0));
        if (pad) {
            out.write(0);
        }
        out.write(minimal, 0, minimal.length);
        return out.toByteArray();
    }
}
