package com.shipperdog.api.integration.internal.webhook;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The Google Play receiver's authenticity leg (issue #54, S12-04,
 * FR-022): Google Play's Real-Time Developer Notifications arrive as
 * Pub/Sub push deliveries, authenticated -- per Google's documented
 * push-subscription pattern -- by a Google-signed OIDC JWT in the
 * {@code Authorization: Bearer} header, minted for THIS endpoint's
 * audience by the service account the push subscription runs as.
 * Verification is Google's documented recipe: RS256 signature against
 * Google's public JWKS (fetched from the configured endpoint, cached,
 * refreshed on an unknown {@code kid}), then the claim checks --
 * issuer, audience, expiry, issued-at freshness (the replay window),
 * and the optional service-account email allowlist.
 *
 * <p>Fail CLOSED: a JWKS endpoint that cannot be fetched rejects the
 * delivery (an unverifiable signature is a rejected signature, never
 * an assumed one). The verdict is boolean by design -- every failure
 * leg (missing token, wrong algorithm, unknown key, bad signature,
 * wrong issuer, wrong audience, expired, stale, untrusted email) is
 * one uniform "not accepted", never a hint which refused.
 */
@Component
public class GoogleJwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleJwtVerifier.class);

    /** Google's OIDC token issuers (the documented pair for push tokens). */
    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    /** RS256 is the only algorithm this verifier ever accepts. */
    private static final String EXPECTED_ALG = "RS256";

    /** How long a fetched JWKS set stays authoritative between refreshes. */
    private static final Duration JWKS_TTL = Duration.ofMinutes(10);

    private final String requiredAudience;
    private final Set<String> trustedEmails;
    private final String jwksUri;
    private final Duration maxSkew;
    private final ObjectMapper mapper;
    private final RestClient http;
    private final Clock clock;

    private volatile Map<String, RSAPublicKey> cachedKeys = Map.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    GoogleJwtVerifier(
            String requiredAudience,
            List<String> trustedEmails,
            String jwksUri,
            Duration maxSkew,
            ObjectMapper mapper,
            RestClient http,
            Clock clock) {
        this.requiredAudience = requiredAudience;
        this.trustedEmails = Set.copyOf(trustedEmails);
        this.jwksUri = jwksUri;
        this.maxSkew = maxSkew;
        this.mapper = mapper;
        this.http = http;
        this.clock = clock;
    }

    /**
     * The authenticity verdict and, when it passes, the decoded claim
     * set. Returns null for EVERY failure leg -- the caller's uniform
     * answer is the only public signal.
     */
    public JsonNode verifyAndDecode(String bearerToken) {
        if (bearerToken == null
                || !bearerToken.startsWith("Bearer ")
                || requiredAudience == null
                || requiredAudience.isBlank()) {
            return null;
        }
        String token = bearerToken.substring("Bearer ".length()).trim();
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            Base64.Decoder url = Base64.getUrlDecoder();
            JsonNode header = mapper.readTree(url.decode(parts[0]));
            JsonNode claims = mapper.readTree(url.decode(parts[1]));
            JsonNode alg = header.get("alg");
            JsonNode kid = header.get("kid");
            if (alg == null
                    || !EXPECTED_ALG.equals(alg.asText())
                    || kid == null
                    || kid.asText().isBlank()) {
                return null;
            }
            RSAPublicKey key = keyFor(kid.asText());
            if (key == null) {
                return null;
            }
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            if (!verifier.verify(url.decode(parts[2]))) {
                return null;
            }
            return claimsAreTrusted(claims) ? claims : null;
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            log.debug(
                    "a google-play push token failed verification: {}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The claim checks the authenticity verdict includes: issuer,
     * audience, expiry, issued-at freshness (the replay window -- a
     * token minted outside the window is a replayed delivery), and
     * the optional sender-email allowlist.
     */
    private boolean claimsAreTrusted(JsonNode claims) {
        JsonNode issuer = claims.get("iss");
        if (issuer == null || !ISSUERS.contains(issuer.asText())) {
            return false;
        }
        // RFC 7519's aud is "array of strings OR a single string";
        // Google-issued OIDC tokens carry the single-string form --
        // both are accepted and matched against the pinned audience.
        JsonNode audience = claims.get("aud");
        if (audience == null || audience.isNull()) {
            return false;
        }
        boolean audienceMatches = false;
        if (audience.isArray()) {
            for (JsonNode entry : audience) {
                if (requiredAudience.equals(entry.asText())) {
                    audienceMatches = true;
                    break;
                }
            }
        } else {
            audienceMatches = requiredAudience.equals(audience.asText());
        }
        if (!audienceMatches) {
            return false;
        }
        JsonNode expiry = claims.get("exp");
        JsonNode issuedAt = claims.get("iat");
        if (expiry == null || issuedAt == null || expiry.isNull() || issuedAt.isNull()) {
            return false;
        }
        Instant now = Instant.now(clock);
        if (Instant.ofEpochSecond(expiry.asLong()).isBefore(now)) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(issuedAt.asLong()), now)
                        .abs()
                        .compareTo(maxSkew)
                > 0) {
            return false;
        }
        if (!trustedEmails.isEmpty()) {
            JsonNode email = claims.get("email");
            if (email == null || email.isNull() || !trustedEmails.contains(email.asText())) {
                return false;
            }
        }
        return true;
    }

    /** The cached JWKS lookup, refreshed on TTL expiry or an unknown key id. */
    private RSAPublicKey keyFor(String kid) {
        Map<String, RSAPublicKey> keys = cachedKeys;
        RSAPublicKey key = keys.get(kid);
        if (key != null && cachedAt.plus(JWKS_TTL).isAfter(Instant.now(clock))) {
            return key;
        }
        keys = fetchJwks();
        return keys.get(kid);
    }

    private Map<String, RSAPublicKey> fetchJwks() {
        try {
            String body = http.get().uri(jwksUri).retrieve().body(String.class);
            JsonNode parsed = mapper.readTree(body == null ? "" : body);
            JsonNode keySet = parsed.get("keys");
            if (keySet == null || !keySet.isArray()) {
                return keepStale();
            }
            Map<String, RSAPublicKey> fresh = new HashMap<>();
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            Base64.Decoder url = Base64.getUrlDecoder();
            for (JsonNode jwk : keySet) {
                JsonNode kid = jwk.get("kid");
                JsonNode modulus = jwk.get("n");
                JsonNode exponent = jwk.get("e");
                if (kid == null || modulus == null || exponent == null) {
                    continue;
                }
                RSAPublicKeySpec spec = new RSAPublicKeySpec(
                        new BigInteger(1, url.decode(modulus.asText())),
                        new BigInteger(1, url.decode(exponent.asText())));
                fresh.put(kid.asText(), (RSAPublicKey) rsa.generatePublic(spec));
            }
            cachedKeys = Map.copyOf(fresh);
            cachedAt = Instant.now(clock);
            return cachedKeys;
        } catch (RuntimeException | java.security.GeneralSecurityException e) {
            // Fail closed, but keep the stale cache: a fetch outage is
            // not evidence against a key already verified this TTL.
            log.warn("could not refresh the google-play JWKS: {}", e.getClass().getSimpleName());
            return keepStale();
        }
    }

    private Map<String, RSAPublicKey> keepStale() {
        return cachedKeys;
    }
}
