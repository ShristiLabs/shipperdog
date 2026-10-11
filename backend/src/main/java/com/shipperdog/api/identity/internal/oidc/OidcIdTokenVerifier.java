package com.shipperdog.api.identity.internal.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * OIDC ID-token verification (S14-01, issue #67): the one place a
 * corporate identity's assertion becomes trusted. Every leg fails
 * closed with one uniform refusal (the caller cannot split the
 * branches apart):
 *
 * <ul>
 *   <li>algorithm PINNING: only RS256 and ES256 are ever accepted --
 *       {@code alg:none} and the HMAC family (the classic
 *       RS256-public-key-as-HMAC-secret confusion) are rejected
 *       before any key is consulted;
 *   <li>signature against the JWKS key the {@code kid} names (the
 *       caller supplies the key set -- {@link OidcProviderClient}'s
 *       cached, rotation-refreshed set);
 *   <li>issuer: exact match against the configured issuer;
 *   <li>audience: must contain the configured client id;
 *   <li>expiry/issued-at: within the clock skew;
 *   <li>nonce: must hash-match the authorization request's nonce
 *       (replay guard) when the caller carries one.
 * </ul>
 */
@Component
public class OidcIdTokenVerifier {

    /** The only algorithms this verifier ever accepts. */
    public static final Set<JWSAlgorithm> ACCEPTED_ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.ES256);

    /** The verified claim set of one accepted ID token. */
    public record VerifiedClaims(
            String subject,
            String email,
            Boolean emailVerified,
            String nonce,
            List<String> groups,
            boolean inactive,
            String name) {}

    private final Clock clock;
    private final Duration skew;

    OidcIdTokenVerifier(Clock clock, com.shipperdog.api.identity.internal.config.SsoProperties properties) {
        this.clock = clock;
        this.skew = properties.clockSkew();
    }

    /**
     * Verifies the ID token or returns null -- one uniform refusal for
     * every failure leg, never a hint which refused. {@code groupClaimName}
     * and {@code inactiveClaimName} are optional extractions (null
     * disables the respective leg).
     */
    public VerifiedClaims verify(
            String idToken,
            String expectedIssuer,
            String expectedAudience,
            String expectedNonceHash,
            JWKSet keySet,
            String groupClaimName,
            String inactiveClaimName) {
        try {
            SignedJWT jwt = SignedJWT.parse(idToken);
            JWSHeader header = jwt.getHeader();
            if (!ACCEPTED_ALGORITHMS.contains(header.getAlgorithm())) {
                return null;
            }
            String kid = header.getKeyID();
            if (kid == null) {
                return null;
            }
            JWK key = keySet.getKeyByKeyId(kid);
            if (key == null) {
                return null;
            }
            if (!jwt.verify(verifierFor(key))) {
                return null;
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = Instant.now(clock);
            if (!expectedIssuer.equals(claims.getIssuer())) {
                return null;
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(expectedAudience)) {
                return null;
            }
            if (claims.getExpirationTime() == null
                    || claims.getExpirationTime().toInstant().isBefore(now.minus(skew))) {
                return null;
            }
            if (claims.getIssueTime() == null
                    || claims.getIssueTime().toInstant().isAfter(now.plus(skew))) {
                return null;
            }
            if (claims.getSubject() == null || claims.getSubject().isBlank()) {
                return null;
            }
            if (expectedNonceHash != null) {
                String nonce = claims.getStringClaim("nonce");
                if (nonce == null
                        || !expectedNonceHash.equals(
                                com.shipperdog.api.identity.internal.crypto.SecureTokens.sha256Hex(nonce))) {
                    return null;
                }
            }
            Boolean emailVerified = claims.getBooleanClaim("email_verified");
            List<String> groups = groupClaimName == null ? List.of() : claims.getStringListClaim(groupClaimName);
            boolean inactive =
                    inactiveClaimName != null && Boolean.TRUE.equals(claims.getBooleanClaim(inactiveClaimName));
            return new VerifiedClaims(
                    claims.getSubject(),
                    claims.getStringClaim("email"),
                    emailVerified,
                    claims.getStringClaim("nonce"),
                    groups == null ? List.of() : groups,
                    inactive,
                    claims.getStringClaim("name"));
        } catch (RuntimeException
                | java.text.ParseException
                | com.nimbusds.jose.JOSEException
                | java.security.GeneralSecurityException e) {
            return null;
        }
    }

    /** The algorithm-pinned verifier for the key's family. */
    private static JWSVerifier verifierFor(JWK key)
            throws java.security.GeneralSecurityException, com.nimbusds.jose.JOSEException {
        if (key instanceof RSAKey rsaKey) {
            return new RSASSAVerifier(rsaKey.toRSAPublicKey());
        }
        if (key instanceof ECKey ecKey) {
            return new ECDSAVerifier(ecKey.toECPublicKey());
        }
        throw new IllegalStateException("unsupported key type for kid " + key.getKeyID());
    }
}
