package com.shipperdog.api.integration.internal.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Google JWT verifier's unit legs (issue #54, S12-04): a
 * Google-shaped OIDC token (RS256, JWKS-verifiable, right issuer and
 * audience, fresh) decodes; every wrong-key, wrong-audience, stale,
 * or mis-shapen variant is the one uniform refusal. A local HTTP
 * server stands in for Google's JWKS endpoint, serving the fixture
 * RSA key (a self-signed ShipperDog TEST certificate, generated for
 * this suite, never a credential).
 */
class GoogleJwtVerifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    private static final String AUDIENCE = "https://shipperdog.example.com/webhooks/google-play";

    private static HttpServer jwks;
    private static PrivateKey signingKey;
    private static GoogleJwtVerifier verifier;
    private static GoogleJwtVerifier allowlistVerifier;
    private static final AtomicInteger jwksFetches = new AtomicInteger();

    @BeforeAll
    static void startJwksAndLoadKey() throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (InputStream in =
                GoogleJwtVerifierTest.class.getClassLoader().getResourceAsStream("webhooks/google-play-test-rsa.crt")) {
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(in);
            RSAPublicKey publicKey = (RSAPublicKey) certificate.getPublicKey();
            String jwksJson = "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\","
                    + "\"kid\":\"fixture-key-1\",\"n\":\""
                    + Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    trimLeadingZero(publicKey.getModulus().toByteArray()))
                    + "\",\"e\":\""
                    + Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(trimLeadingZero(
                                    publicKey.getPublicExponent().toByteArray()))
                    + "\"}]}";
            jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            jwks.createContext("/certs", exchange -> {
                jwksFetches.incrementAndGet();
                byte[] body = jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            jwks.start();
        }
        String pem = Files.readString(Path.of(GoogleJwtVerifierTest.class
                .getClassLoader()
                .getResource("webhooks/google-play-test-rsa.key")
                .toURI()));
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        signingKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RestClient http = RestClient.builder().build();
        verifier = new GoogleJwtVerifier(
                AUDIENCE,
                List.of(),
                "http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs",
                Duration.ofMinutes(5),
                MAPPER,
                http,
                clock);
        allowlistVerifier = new GoogleJwtVerifier(
                AUDIENCE,
                List.of("expected-sa@project.iam.gserviceaccount.com"),
                "http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs",
                Duration.ofMinutes(5),
                MAPPER,
                http,
                clock);
    }

    @AfterAll
    static void stopJwks() {
        if (jwks != null) {
            jwks.stop(0);
        }
    }

    @Test
    void wellFormedFreshTokenDecodes() {
        String token = token("fixture-key-1", "RS256", claims(AUDIENCE, NOW));
        JsonNode decoded = verifier.verifyAndDecode("Bearer " + token);
        assertThat(decoded).isNotNull();
        assertThat(decoded.get("iss").asString()).isEqualTo("https://accounts.google.com");
    }

    /**
     * RFC 7519's aud is "array of strings OR a single string", and
     * Google-issued OIDC tokens carry the SINGLE-STRING form (the
     * review's finding: an array-only reader would reject every real
     * Google token while array-shaped fixtures passed). Both forms
     * accept; the wrong string refuses.
     */
    @Test
    void theSingleStringAudienceFormAcceptsLikeTheArrayForm() {
        ObjectNode stringAudience = claims(AUDIENCE, NOW);
        stringAudience.remove("aud");
        stringAudience.put("aud", AUDIENCE);
        assertThat(verifier.verifyAndDecode("Bearer " + token("fixture-key-1", "RS256", stringAudience)))
                .isNotNull();
        ObjectNode wrongString = claims(AUDIENCE, NOW);
        wrongString.remove("aud");
        wrongString.put("aud", "https://other.example.com/hook");
        assertThat(verifier.verifyAndDecode("Bearer " + token("fixture-key-1", "RS256", wrongString)))
                .isNull();
    }

    @Test
    void everyWrongVariantRefusesUniformly() {
        String good = token("fixture-key-1", "RS256", claims(AUDIENCE, NOW));
        // Arrays.asList: the absent-header leg is a deliberate null entry.
        List<String> variants = java.util.Arrays.asList(
                // Wrong audience.
                token("fixture-key-1", "RS256", claims("https://other.example.com/hook", NOW)),
                // Stale issued-at (a replayed token outside the window).
                token("fixture-key-1", "RS256", claims(AUDIENCE, NOW.minusSeconds(3600))),
                // Already expired.
                expiredToken(),
                // Wrong issuer.
                token("fixture-key-1", "RS256", claims("https://evil.example.com", AUDIENCE, NOW)),
                // Unknown key id.
                token("unknown-kid", "RS256", claims(AUDIENCE, NOW)),
                // Wrong algorithm.
                token("fixture-key-1", "HS256", claims(AUDIENCE, NOW)),
                // Tampered signature.
                good.substring(0, good.length() - 4) + "AAAA",
                // Broken shapes.
                "Bearer not-a-token",
                "Basic something-else",
                null);
        for (String variant : variants) {
            assertThat(verifier.verifyAndDecode(variant))
                    .as("variant [%.24s]", variant)
                    .isNull();
        }
    }

    @Test
    void emptyAudienceKeepsTheReceiverOff() {
        GoogleJwtVerifier off = new GoogleJwtVerifier(
                "",
                List.of(),
                "http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs",
                Duration.ofMinutes(5),
                MAPPER,
                RestClient.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(off.verifyAndDecode("Bearer " + token("fixture-key-1", "RS256", claims(AUDIENCE, NOW))))
                .isNull();
    }

    @Test
    void trustedEmailAllowlistAcceptsTheConfiguredSenderOnly() {
        ObjectNode trusted = claims(AUDIENCE, NOW);
        trusted.put("email", "expected-sa@project.iam.gserviceaccount.com");
        assertThat(allowlistVerifier.verifyAndDecode("Bearer " + token("fixture-key-1", "RS256", trusted)))
                .isNotNull();
        ObjectNode untrusted = claims(AUDIENCE, NOW);
        untrusted.put("email", "someone-else@project.iam.gserviceaccount.com");
        assertThat(allowlistVerifier.verifyAndDecode("Bearer " + token("fixture-key-1", "RS256", untrusted)))
                .isNull();
        // No email claim at all: refused under an allowlist.
        assertThat(allowlistVerifier.verifyAndDecode(
                        "Bearer " + token("fixture-key-1", "RS256", claims(AUDIENCE, NOW))))
                .isNull();
    }

    @Test
    void theJwksCacheIsFetchedOncePerTtlNotPerDelivery() {
        int before = jwksFetches.get();
        for (int i = 0; i < 5; i++) {
            assertThat(verifier.verifyAndDecode("Bearer " + token("fixture-key-1", "RS256", claims(AUDIENCE, NOW))))
                    .isNotNull();
        }
        // The key is cached under a fixed clock, so five deliveries
        // cost at most the one fetch that populated it (the fixed
        // clock also freezes the TTL, keeping the test deterministic).
        assertThat(jwksFetches.get() - before).isLessThanOrEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static ObjectNode claims(String audience, Instant issuedAt) {
        return claims("https://accounts.google.com", audience, issuedAt);
    }

    private static ObjectNode claims(String issuer, String audience, Instant issuedAt) {
        ObjectNode claims = MAPPER.createObjectNode();
        claims.put("iss", issuer);
        claims.putArray("aud").add(audience);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", issuedAt.plusSeconds(3600).getEpochSecond());
        return claims;
    }

    private static String expiredToken() {
        ObjectNode expired = claims(AUDIENCE, NOW.minusSeconds(7200));
        expired.put("exp", NOW.minusSeconds(60).getEpochSecond());
        return token("fixture-key-1", "RS256", expired);
    }

    private static String token(String kid, String alg, ObjectNode claims) {
        try {
            String header = "{\"alg\":\"" + alg + "\",\"kid\":\"" + kid + "\"}";
            Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
            String encodedHeader = url.encodeToString(header.getBytes(StandardCharsets.UTF_8));
            String encodedClaims = url.encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8));
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(signingKey);
            signer.update((encodedHeader + "." + encodedClaims).getBytes(StandardCharsets.US_ASCII));
            return encodedHeader + "." + encodedClaims + "." + url.encodeToString(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** JWKS integers are unsigned big-endian without ASN.1's leading-zero byte. */
    private static byte[] trimLeadingZero(byte[] twosComplement) {
        if (twosComplement.length > 1 && twosComplement[0] == 0) {
            byte[] trimmed = new byte[twosComplement.length - 1];
            System.arraycopy(twosComplement, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return twosComplement;
    }
}
