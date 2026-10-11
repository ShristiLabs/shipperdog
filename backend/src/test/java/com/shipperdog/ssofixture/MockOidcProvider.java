package com.shipperdog.ssofixture;

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
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * A contract-faithful mock OIDC provider for the SSO suites (S14-01,
 * issue #67): discovery, authorization endpoint (validating
 * response_type, scope, the S256 PKCE challenge, and the state/nonce
 * echo), token endpoint (client_secret_post authentication, PKCE
 * verifier check, RS256/ES256-signed ID tokens), and JWKS -- the four
 * endpoints of the OIDC core spec the relying party consumes. No live
 * IdP is ever contacted in CI; the attack modes ({@code alg:none},
 * HS256-confusion, wrong audience, missing nonce) exist precisely so
 * the platform's refusals are proven against spec-shaped attacks.
 */
@RestController
public class MockOidcProvider {

    public static final String CLIENT_ID = "shipperdog-console";
    public static final String CLIENT_SECRET = "mock-idp-client-secret";

    private static final String RSA_KID = "mock-rsa-1";
    private static final String EC_KID = "mock-ec-1";

    private static final RSAKey RSA_KEY;
    private static final ECKey EC_KEY;

    static {
        try {
            RSA_KEY = new RSAKeyGenerator(2048)
                    .keyID(RSA_KID)
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();
            EC_KEY = new ECKeyGenerator(Curve.P_256)
                    .keyID(EC_KID)
                    .keyUse(KeyUse.SIGNATURE)
                    .generate();
        } catch (Exception e) {
            throw new IllegalStateException("cannot generate the mock provider's keys", e);
        }
    }

    /** What the provider asserts for the next authorization. */
    public static final class Behavior {
        public String clientId = CLIENT_ID;
        public String clientSecret = CLIENT_SECRET;
        public String subject = "auth0|corp-user-1";
        public String email = "corp.user@example.com";
        public boolean emailVerified = true;
        public String name = "Corp User";
        public List<String> groups = List.of();
        public Map<String, Object> extraClaims = Map.of();
        public JWSAlgorithm alg = JWSAlgorithm.RS256;
        public boolean dropNonce = false;
        public String audienceOverride = null;
        public boolean expiredToken = false;
        public boolean rejectVerifier = false;
        public boolean refuseExchange = false;
    }

    private volatile Behavior behavior = new Behavior();

    /** The suite's server port once announced (minting happens off-request). */
    private volatile int knownPort = -1;

    /** One outstanding authorization code grant. */
    private record Grant(String clientId, String codeChallenge, String nonce) {}

    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    /** The issuer the suites configure the organization with. */
    public String issuerFor(int port) {
        return "http://localhost:" + port + "/mock-idp";
    }

    public Behavior behave() {
        return behavior;
    }

    public void reset() {
        behavior = new Behavior();
        grants.clear();
    }

    /** Records the suite's server port for off-request token minting. */
    public void announcePort(int port) {
        this.knownPort = port;
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /**
     * A second issuer whose discovery document LIES about its issuer --
     * the OIDC discovery contract's mismatch guard, on its own
     * (uncached) issuer string so the test's refusal is a fresh fetch.
     */
    @GetMapping("/mock-idp-mismatch/.well-known/openid-configuration")
    public Map<String, Object> lyingDiscovery() {
        String issuer = issuerFor(localPort()) + "-mismatch";
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("issuer", "https://someone-else.example");
        document.put("authorization_endpoint", issuer + "/authorize");
        document.put("token_endpoint", issuer + "/token");
        document.put("jwks_uri", issuer + "/jwks");
        return document;
    }

    @GetMapping("/mock-idp/.well-known/openid-configuration")
    public Map<String, Object> discovery() {
        String issuer = issuerFor(localPort());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("issuer", issuer);
        document.put("authorization_endpoint", issuer + "/authorize");
        document.put("token_endpoint", issuer + "/token");
        document.put("jwks_uri", issuer + "/jwks");
        document.put("response_types_supported", List.of("code"));
        document.put("subject_types_supported", List.of("public"));
        document.put("id_token_signing_alg_values_supported", List.of("RS256", "ES256"));
        document.put("code_challenge_methods_supported", List.of("S256"));
        return document;
    }

    // ------------------------------------------------------------------
    // Authorization endpoint
    // ------------------------------------------------------------------

    @GetMapping("/mock-idp/authorize")
    public ResponseEntity<Void> authorize(
            @RequestParam String client_id,
            @RequestParam String redirect_uri,
            @RequestParam String response_type,
            @RequestParam String scope,
            @RequestParam String state,
            @RequestParam String nonce,
            @RequestParam String code_challenge,
            @RequestParam String code_challenge_method) {
        if (!CLIENT_ID.equals(client_id)
                || !"code".equals(response_type)
                || !scope.contains("openid")
                || code_challenge == null
                || code_challenge.isBlank()
                || !"S256".equals(code_challenge_method)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        String code = UUID.randomUUID().toString().replace("-", "");
        grants.put(code, new Grant(client_id, code_challenge, nonce));
        // The relying party registers its externally reachable callback
        // (an alias host in the suites); the mock normalizes the echo to
        // the live local port so the suite's client can follow it.
        URI redirect = UriComponentsBuilder.fromUriString(redirect_uri)
                .host("localhost")
                .port(localPort())
                .queryParam("code", code)
                .queryParam("state", state)
                .build()
                .toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(redirect).build();
    }

    // ------------------------------------------------------------------
    // Token endpoint
    // ------------------------------------------------------------------

    @PostMapping(value = "/mock-idp/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(
            @RequestParam String grant_type,
            @RequestParam String code,
            @RequestParam String redirect_uri,
            @RequestParam String client_id,
            @RequestParam String client_secret,
            @RequestParam String code_verifier) {
        Behavior current = behavior;
        if (!"authorization_code".equals(grant_type)
                || !current.clientId.equals(client_id)
                || !current.clientSecret.equals(client_secret)) {
            return error("invalid_client");
        }
        Grant grant = grants.remove(code);
        if (grant == null || !grant.clientId().equals(client_id)) {
            return error("invalid_grant");
        }
        if (current.refuseExchange) {
            return error("invalid_grant");
        }
        if (current.rejectVerifier || !s256(code_verifier).equals(grant.codeChallenge())) {
            return error("invalid_grant");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", "mock-access-" + UUID.randomUUID());
        response.put("token_type", "Bearer");
        response.put("expires_in", 3600);
        response.put("id_token", idToken(current, grant));
        return ResponseEntity.ok(response);
    }

    // ------------------------------------------------------------------
    // JWKS
    // ------------------------------------------------------------------

    /**
     * Mints a spec-shaped Back-Channel Logout token for the subject
     * (issuer, audience, expiry, the spec's events claim; RS256).
     */
    public String mintLogoutToken(String subject) {
        return mintLogoutToken(subject, true);
    }

    /** The includeEvents=false shape is a replayed ID token's skeleton --
     * a well-signed assertion that is NOT a logout token. */
    public String mintLogoutToken(String subject, boolean includeEvents) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .issuer(issuerFor(localPort()))
                    .audience(CLIENT_ID)
                    .subject(subject)
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)));
            if (includeEvents) {
                builder.claim("events", Map.of("http://schemas.openid.net/event/backchannel-logout", Map.of()));
            }
            JWTClaimsSet claims = builder.build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KID).build(), claims);
            jwt.sign(new RSASSASigner(RSA_KEY.toRSAPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("cannot mint the logout token", e);
        }
    }

    @GetMapping("/mock-idp/jwks")
    public Map<String, Object> jwks() {
        return new JWKSet(List.of(RSA_KEY.toPublicJWK(), EC_KEY.toPublicJWK())).toJSONObject();
    }

    // ------------------------------------------------------------------
    // ID-token minting (including the attack modes)
    // ------------------------------------------------------------------

    private String idToken(Behavior current, Grant grant) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuerFor(localPort()))
                    .audience(current.audienceOverride == null ? current.clientId : current.audienceOverride)
                    .subject(current.subject)
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(current.expiredToken ? -3600 : 3600)))
                    .claim("email", current.email)
                    .claim("email_verified", current.emailVerified);
            if (!current.dropNonce) {
                claims.claim("nonce", grant.nonce());
            }
            if (!current.groups.isEmpty()) {
                claims.claim("groups", current.groups);
            }
            if (!current.name.isBlank()) {
                claims.claim("name", current.name);
            }
            current.extraClaims.forEach(claims::claim);
            JWSHeader header;
            SignedJWT jwt;
            if (current.alg == JWSAlgorithm.HS256) {
                // The classic confusion attack: HMAC over the RSA
                // public key's bytes (the payload an attacker can read
                // from JWKS), presented where RS256 is expected.
                header =
                        new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(RSA_KID).build();
                jwt = new SignedJWT(header, claims.build());
                jwt.sign(new MACSigner(((RSAPublicKey) RSA_KEY.toPublicKey()).getEncoded()));
            } else if (current.alg == JWSAlgorithm.ES256) {
                header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(EC_KID).build();
                jwt = new SignedJWT(header, claims.build());
                jwt.sign(new ECDSASigner(EC_KEY.toECPrivateKey()));
            } else {
                header =
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(RSA_KID).build();
                jwt = new SignedJWT(header, claims.build());
                jwt.sign(new RSASSASigner(RSA_KEY.toRSAPrivateKey()));
            }
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("cannot mint the mock ID token", e);
        }
    }

    private static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(String error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", error));
    }

    private int localPort() {
        // The mock serves inside the application itself; the suites run
        // RANDOM_PORT and the issuer must match it exactly. Off-request
        // minting (the logout token) uses the announced port; serving
        // threads prefer the live request's.
        if (knownPort > 0) {
            return knownPort;
        }
        org.springframework.web.context.request.RequestAttributes attributes =
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            return servlet.getRequest().getLocalPort();
        }
        return -1;
    }
}
