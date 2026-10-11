package com.shipperdog.api.identity.internal.oidc;

import com.nimbusds.jose.jwk.JWKSet;
import com.shipperdog.api.identity.internal.config.SsoProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The OIDC relying-party's outbound leg (S14-01, issue #67): discovery
 * document fetch, JWKS fetch, and the authorization-code token
 * exchange. Two TTL caches (per issuer, per JWKS endpoint) follow the
 * #54 receiver's doctrine -- cached until TTL expiry, refreshed on an
 * unknown {@code kid}, stale cache KEPT when a refresh fails (a fetch
 * outage is not evidence against a key already verified this TTL) and
 * every verification then fails closed against what is held.
 *
 * <p>No live IdP is ever contacted in tests: the suites run a
 * contract-faithful mock provider (discovery, authorize, token, JWKS)
 * and this client cannot tell the difference by design.
 */
@Component
public class OidcProviderClient {

    /** The endpoints one discovery document yields. */
    public record OidcEndpoints(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri) {}

    /** The token endpoint's answer; only the ID token is load-bearing. */
    public record TokenResponse(String idToken, String accessToken, long expiresIn) {}

    /** A cached document plus its fetch time. */
    private record Cached<T>(T value, Instant fetchedAt) {}

    private final RestClient http;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SsoProperties properties;
    private final Map<String, Cached<OidcEndpoints>> discoveryCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<JWKSet>> jwksCache = new ConcurrentHashMap<>();

    OidcProviderClient(ObjectMapper mapper, Clock clock, SsoProperties properties) {
        this.mapper = mapper;
        this.clock = clock;
        this.properties = properties;
        this.http = RestClient.create();
    }

    /**
     * The provider's endpoints, from {@code <issuer>/.well-known/
     * openid-configuration}, cached per issuer for the discovery TTL.
     * The document's {@code issuer} MUST equal the configured issuer
     * (the OIDC discovery contract's mismatch guard).
     */
    public OidcEndpoints endpointsFor(String issuer) {
        Instant now = Instant.now(clock);
        Cached<OidcEndpoints> cached = discoveryCache.get(issuer);
        if (cached != null
                && cached.fetchedAt().plus(properties.discoveryCacheTtl()).isAfter(now)) {
            return cached.value();
        }
        String document = http.get()
                .uri(URI.create(issuer + "/.well-known/openid-configuration"))
                .retrieve()
                .body(String.class);
        JsonNode parsed = mapper.readTree(document == null ? "" : document);
        String documentIssuer = text(parsed, "issuer");
        String authorization = text(parsed, "authorization_endpoint");
        String token = text(parsed, "token_endpoint");
        String jwks = text(parsed, "jwks_uri");
        if (documentIssuer == null || authorization == null || token == null || jwks == null) {
            throw new IllegalStateException("incomplete OIDC discovery document for issuer " + issuer);
        }
        if (!issuer.equals(documentIssuer)) {
            throw new IllegalStateException(
                    "OIDC discovery issuer mismatch: configured " + issuer + ", document " + documentIssuer);
        }
        OidcEndpoints endpoints = new OidcEndpoints(documentIssuer, authorization, token, jwks);
        discoveryCache.put(issuer, new Cached<>(endpoints, now));
        return endpoints;
    }

    /**
     * The provider's JWKS, cached per JWKS endpoint for the JWKS TTL.
     * A fetch failure propagates: the caller fails closed (the caller
     * holding a stale cache is {@link #keySetFor}'s refresh path).
     */
    public JWKSet keySetFor(String jwksUri) {
        Instant now = Instant.now(clock);
        Cached<JWKSet> cached = jwksCache.get(jwksUri);
        if (cached != null && cached.fetchedAt().plus(properties.jwksCacheTtl()).isAfter(now)) {
            return cached.value();
        }
        JWKSet fresh = fetchKeySet(jwksUri);
        jwksCache.put(jwksUri, new Cached<>(fresh, now));
        return fresh;
    }

    /**
     * The cached JWKS with a refresh attempt when the key id is
     * unknown (rotation): a fresh set that still lacks the kid keeps
     * the STALE set -- the #54 keep-stale doctrine -- and an outright
     * fetch failure keeps it too. The caller's next step fails closed
     * against whatever is held.
     */
    public JWKSet keySetForKid(String jwksUri, String kid) {
        Cached<JWKSet> cached = jwksCache.get(jwksUri);
        if (cached != null && cached.value().getKeyByKeyId(kid) != null) {
            return cached.value();
        }
        Instant now = Instant.now(clock);
        try {
            JWKSet fresh = fetchKeySet(jwksUri);
            jwksCache.put(jwksUri, new Cached<>(fresh, now));
            if (fresh.getKeyByKeyId(kid) != null) {
                return fresh;
            }
        } catch (RuntimeException e) {
            // Fall through to the stale set: rotation misses are the
            // refresh trigger, outages are not a verdict.
        }
        if (cached != null) {
            return cached.value();
        }
        throw new IllegalStateException("no JWKS available for " + jwksUri);
    }

    /**
     * The authorization-code + PKCE exchange. Client authentication is
     * {@code client_secret_post} (Entra, Okta, and Google all accept
     * it; it keeps the secret out of URLs and logs). The answer's ID
     * token is returned unverified -- verification is {@link
     * OidcIdTokenVerifier}'s single responsibility.
     */
    public TokenResponse exchangeCode(
            OidcEndpoints endpoints,
            String clientId,
            String clientSecret,
            String code,
            String codeVerifier,
            String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code_verifier", codeVerifier);
        String body = http.post()
                .uri(URI.create(endpoints.tokenEndpoint()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        JsonNode parsed = mapper.readTree(body == null ? "" : body);
        JsonNode idToken = parsed.get("id_token");
        if (idToken == null || idToken.isNull()) {
            throw new IllegalStateException("token endpoint answered without an id_token");
        }
        long expiresIn = parsed.path("expires_in").asLong(0);
        return new TokenResponse(idToken.asText(), text(parsed, "access_token"), expiresIn);
    }

    private JWKSet fetchKeySet(String jwksUri) {
        try {
            String body = http.get().uri(URI.create(jwksUri)).retrieve().body(String.class);
            return JWKSet.parse(body == null ? "" : body);
        } catch (RuntimeException | java.text.ParseException e) {
            throw new IllegalStateException("cannot fetch JWKS from " + jwksUri, e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
