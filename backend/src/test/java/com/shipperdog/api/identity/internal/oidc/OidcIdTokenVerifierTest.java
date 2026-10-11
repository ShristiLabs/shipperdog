package com.shipperdog.api.identity.internal.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.shipperdog.api.identity.internal.config.SsoProperties;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ID-token verifier's own gates (S14-01, issue #67): every leg of
 * the uniform refusal, proven token-by-token against freshly minted
 * spec-shaped assertions -- algorithm pinning (alg:none and the
 * HS256-confusion never reach a key), signature, issuer, audience,
 * expiry, and the nonce replay guard.
 */
class OidcIdTokenVerifierTest {

    private static final String ISSUER = "https://login.example.com";
    private static final String CLIENT_ID = "shipperdog-console";
    private static final String SUBJECT = "auth0|user-1";
    private static final String NONCE = "nonce-123";

    private final RSAKey rsaKey;
    private final ECKey ecKey;
    private final JWKSet keySet;
    private final OidcIdTokenVerifier verifier = new OidcIdTokenVerifier(
            Clock.systemUTC(),
            new SsoProperties(
                    URI.create("http://localhost:8080"),
                    URI.create("http://localhost:5173/sso/callback"),
                    Duration.ofMinutes(10),
                    Duration.ofSeconds(60),
                    Duration.ofMinutes(10),
                    Duration.ofMinutes(10),
                    Duration.ofSeconds(60),
                    "",
                    ""));

    OidcIdTokenVerifierTest() throws Exception {
        rsaKey = new RSAKeyGenerator(2048)
                .keyID("rsa-1")
                .keyUse(KeyUse.SIGNATURE)
                .generate();
        ecKey = new ECKeyGenerator(Curve.P_256)
                .keyID("ec-1")
                .keyUse(KeyUse.SIGNATURE)
                .generate();
        keySet = new JWKSet(List.of(rsaKey.toPublicJWK(), ecKey.toPublicJWK()));
    }

    @Test
    void acceptsAWellFormedRs256Token() throws Exception {
        OidcIdTokenVerifier.VerifiedClaims claims = verifier.verify(
                token(JWSAlgorithm.RS256, claims()),
                ISSUER,
                CLIENT_ID,
                SecureTokens.sha256Hex(NONCE),
                keySet,
                null,
                null);
        assertThat(claims).isNotNull();
        assertThat(claims.subject()).isEqualTo(SUBJECT);
        assertThat(claims.email()).isEqualTo("user@example.com");
        assertThat(claims.emailVerified()).isTrue();
        assertThat(claims.groups()).isEmpty();
        assertThat(claims.inactive()).isFalse();
    }

    @Test
    void acceptsEs256AndExtractsGroupAndInactiveClaims() throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder(claims())
                .claim("groups", List.of("eng-devs"))
                .claim("sd_inactive", true)
                .build();
        OidcIdTokenVerifier.VerifiedClaims verified = verifier.verify(
                token(JWSAlgorithm.ES256, claims),
                ISSUER,
                CLIENT_ID,
                SecureTokens.sha256Hex(NONCE),
                keySet,
                "groups",
                "sd_inactive");
        assertThat(verified).isNotNull();
        assertThat(verified.groups()).containsExactly("eng-devs");
        assertThat(verified.inactive()).isTrue();
    }

    @Test
    void algorithmPinningRefusesNoneAndHmacConfusion() throws Exception {
        // alg:none: an unsigned JWT (header claims none, empty
        // signature) with an otherwise perfect payload.
        String headerless = "eyJhbGciOiJub25lIn0."
                + java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(claims().toString().getBytes())
                + ".";
        assertThat(verifier.verify(headerless, ISSUER, CLIENT_ID, null, keySet, null, null))
                .isNull();

        // HS256 over the RSA public key's bytes: the classic confusion.
        SignedJWT hmac = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("rsa-1").build(), claims());
        hmac.sign(new MACSigner(rsaKey.toPublicKey().getEncoded()));
        assertThat(verifier.verify(hmac.serialize(), ISSUER, CLIENT_ID, null, keySet, null, null))
                .isNull();
    }

    @Test
    void wrongIssuerAudienceExpiryAndNonceAreEachRefused() throws Exception {
        String nonceHash = SecureTokens.sha256Hex(NONCE);
        assertThat(verifier.verify(
                        token(
                                JWSAlgorithm.RS256,
                                new JWTClaimsSet.Builder(claims())
                                        .issuer("https://evil.example")
                                        .build()),
                        ISSUER,
                        CLIENT_ID,
                        nonceHash,
                        keySet,
                        null,
                        null))
                .isNull();
        assertThat(verifier.verify(
                        token(
                                JWSAlgorithm.RS256,
                                new JWTClaimsSet.Builder(claims())
                                        .audience("other-client")
                                        .build()),
                        ISSUER,
                        CLIENT_ID,
                        nonceHash,
                        keySet,
                        null,
                        null))
                .isNull();
        assertThat(verifier.verify(
                        token(
                                JWSAlgorithm.RS256,
                                new JWTClaimsSet.Builder(claims())
                                        .expirationTime(Date.from(Instant.now().minusSeconds(3600)))
                                        .build()),
                        ISSUER,
                        CLIENT_ID,
                        nonceHash,
                        keySet,
                        null,
                        null))
                .isNull();
        assertThat(verifier.verify(
                        token(
                                JWSAlgorithm.RS256,
                                new JWTClaimsSet.Builder(claims())
                                        .claim("nonce", "replayed")
                                        .build()),
                        ISSUER,
                        CLIENT_ID,
                        nonceHash,
                        keySet,
                        null,
                        null))
                .as("the nonce must hash-match the authorization request")
                .isNull();
        assertThat(verifier.verify(token(JWSAlgorithm.RS256, claims()), ISSUER, CLIENT_ID, null, keySet, null, null))
                .as("a null nonce expectation only skips the replay leg")
                .isNotNull();
    }

    @Test
    void unknownKidAndBadSignatureAreRefused() throws Exception {
        RSAKey strangerKey = new RSAKeyGenerator(2048)
                .keyID("stranger-1")
                .keyUse(KeyUse.SIGNATURE)
                .generate();
        SignedJWT stranger = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("stranger-1").build(), claims());
        stranger.sign(new RSASSASigner(strangerKey.toRSAPrivateKey()));
        assertThat(verifier.verify(stranger.serialize(), ISSUER, CLIENT_ID, null, keySet, null, null))
                .as("unknown kid")
                .isNull();

        // A tampered signature over the known kid: flipping the last
        // byte breaks verification under the key the kid names.
        String good = token(JWSAlgorithm.RS256, claims());
        char[] bytes = good.toCharArray();
        bytes[bytes.length - 2] = bytes[bytes.length - 2] == 'A' ? 'B' : 'A';
        assertThat(verifier.verify(new String(bytes), ISSUER, CLIENT_ID, null, keySet, null, null))
                .as("tampered signature")
                .isNull();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static JWTClaimsSet claims() {
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(CLIENT_ID)
                .subject(SUBJECT)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("email", "user@example.com")
                .claim("email_verified", true)
                .claim("nonce", NONCE)
                .build();
    }

    private String token(JWSAlgorithm algorithm, JWTClaimsSet claims) throws Exception {
        String kid = algorithm == JWSAlgorithm.ES256 ? "ec-1" : "rsa-1";
        SignedJWT jwt =
                new SignedJWT(new JWSHeader.Builder(algorithm).keyID(kid).build(), claims);
        if (algorithm == JWSAlgorithm.ES256) {
            jwt.sign(new ECDSASigner(ecKey.toECPrivateKey()));
        } else {
            jwt.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));
        }
        return jwt.serialize();
    }
}
