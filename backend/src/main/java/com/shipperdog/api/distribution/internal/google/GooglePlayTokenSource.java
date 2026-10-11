package com.shipperdog.api.distribution.internal.google;

import com.shipperdog.api.distribution.AdapterException;
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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * The Google Play Developer API's access token (S13-04, #64, AC1,
 * FR-021): the OAuth2 JWT-bearer exchange of RFC 7523 — an RS256 JWS
 * whose payload is exactly Google's service-account model (issuer the
 * client email, the {@code androidpublisher} scope, audience the token
 * endpoint, one-hour lifetime), exchanged at the token endpoint for the
 * bearer every Publishing-API call carries. Signing is the JDK's own
 * JCA ({@code SHA256withRSA} over the PKCS#8 key from the service-
 * account JSON) — no JWT library enters the tree, the Apple channels'
 * precedent.
 *
 * <p><strong>The cache:</strong> one live access token per credential
 * document, refreshed a minute before expiry. Dispatches and syncs
 * reuse the live token; a rotated service account is a different
 * digest, so its first call mints fresh and the superseded entry is
 * never answered again (rotation handled by construction, AC1). The
 * cache KEY is the credential's digest, never the material; the
 * material is read exactly once per mint, on this class's stack, and
 * never leaves it.
 *
 * <p>The exchange is its own taxonomy arm (never a sandbox retry of
 * the publishing call): a rejected assertion (bad signature, unknown
 * client) is AUTHENTICATION — retrying a rejected credential is a
 * lockout generator; a transport fault is TRANSIENT.
 */
@Component
final class GooglePlayTokenSource {

    /** Google's scope constant: the Play Developer (publishing) API. */
    static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher";

    /** The RFC 7523 grant type of the JWT-bearer flow. */
    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    /** Google's maximum assertion lifetime (a longer window is rejected). */
    static final Duration MAX_LIFETIME = Duration.ofHours(1);

    /** The refresh margin: a token is re-exchanged this long before its expiry. */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(1);

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final RestClient http;
    private final Clock clock;
    private final Map<String, Token> cache = new ConcurrentHashMap<>();

    GooglePlayTokenSource(
            com.shipperdog.api.distribution.internal.config.DistributionProperties properties, Clock clock) {
        var play = properties.googlePlay();
        java.net.http.HttpClient transport = java.net.http.HttpClient.newBuilder()
                .connectTimeout(
                        java.time.Duration.ofMillis(play.connectTimeout().toMillis()))
                .build();
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(play.readTimeout());
        // No base URL: the token endpoint rides the credential's own
        // document (Google's accounts endpoint in production, the mock
        // server's /token in tests).
        this.http = RestClient.builder().requestFactory(factory).build();
        this.clock = clock;
    }

    /** The bearer token for the credential, minted or cached (the caller validated the credential). */
    String tokenOf(GooglePlayCredentials credential) {
        Instant now = Instant.now(clock);
        String cacheKey = credential.cacheKey();
        Token live = cache.get(cacheKey);
        if (live == null || now.isAfter(live.expiry().minus(REFRESH_MARGIN))) {
            live = exchange(credential, now);
            cache.put(cacheKey, live);
        }
        return live.value();
    }

    private Token exchange(GooglePlayCredentials credential, Instant now) {
        String assertion = assertionOf(credential, now);
        String body;
        try {
            body = http.post()
                    .uri(credential.tokenEndpoint())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=" + GRANT_TYPE + "&assertion=" + assertion)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() >= 500) {
                // The endpoint's own fault, not the credential's:
                // TRANSIENT — a token-service blip is what the
                // sandbox's bounded retries exist for.
                throw new AdapterException(
                        AdapterException.Category.TRANSIENT,
                        PROVIDER,
                        "the Google token endpoint failed (HTTP "
                                + e.getStatusCode().value() + ")");
            }
            // Google's own refusal of the assertion: 400/401 with
            // invalid_grant / invalid_client — AUTHENTICATION, never
            // retried, exactly like a rejected Apple key.
            throw new AdapterException(
                    AdapterException.Category.AUTHENTICATION,
                    PROVIDER,
                    "Google refused the service-account assertion (HTTP "
                            + e.getStatusCode().value() + "): " + e.getResponseBodyAsString());
        } catch (RuntimeException e) {
            throw new AdapterException(
                    AdapterException.Category.TRANSIENT,
                    PROVIDER,
                    "the Google token endpoint call failed: " + e.getClass().getSimpleName());
        }
        try {
            JsonNode answer = MAPPER.readTree(body);
            String accessToken = answer.path("access_token").asString(null);
            long expiresIn = answer.path("expires_in").asLong(MAX_LIFETIME.toSeconds());
            if (accessToken == null || accessToken.isBlank()) {
                throw new AdapterException(
                        AdapterException.Category.AUTHENTICATION,
                        PROVIDER,
                        "the Google token endpoint answered no access token");
            }
            return new Token(accessToken, now.plusSeconds(expiresIn));
        } catch (AdapterException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AdapterException(
                    AdapterException.Category.TRANSIENT,
                    PROVIDER,
                    "the Google token endpoint's answer was not understood: "
                            + e.getClass().getSimpleName());
        }
    }

    /** The RS256 assertion: Google's header/payload model, signed with the JDK's own JCA. */
    private String assertionOf(GooglePlayCredentials credential, Instant now) {
        Instant expiry = now.plus(MAX_LIFETIME);
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}";
        String payload = "{\"iss\":\""
                + escape(credential.clientEmail())
                + "\",\"scope\":\""
                + SCOPE
                + "\",\"aud\":\""
                + escape(credential.tokenEndpoint())
                + "\",\"iat\":"
                + now.getEpochSecond()
                + ",\"exp\":"
                + expiry.getEpochSecond()
                + "}";
        String signingInput = encode(header) + "." + encode(payload);
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKeyOf(credential.privateKeyPem()));
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + URL.encodeToString(signer.sign());
        } catch (KeyFault e) {
            throw new AdapterException(AdapterException.Category.PERMANENT, PROVIDER, e.getMessage());
        } catch (Exception e) {
            throw new AdapterException(
                    AdapterException.Category.PERMANENT, PROVIDER, "the service-account key could not sign");
        }
    }

    /** The service-account JSON's private_key (PKCS#8 PEM) to a JCA private key; strips the PEM armor Google ships. */
    private static PrivateKey privateKeyOf(String pem) throws KeyFault {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        if (base64.isEmpty()) {
            throw new KeyFault("the Google service-account key material is empty");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new KeyFault("the Google service-account key is not a valid PEM payload");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new KeyFault("the Google service-account key is not a valid RSA PKCS#8 key");
        }
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

    /** The provider label this source's failures carry (the client's vocabulary). */
    static final String PROVIDER = "google-play";

    private static final tools.jackson.databind.json.JsonMapper MAPPER = new tools.jackson.databind.json.JsonMapper();
}
