package com.shipperdog.api.config.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The in-app rate limiter's contract (issue #39, S8-05): the login
 * door and the download tier are throttled per client IP with the
 * platform's single 429 problem+json envelope ({@code RATE_LIMITED})
 * plus a {@code Retry-After} header; every other endpoint passes
 * untouched; and the X-Forwarded-For trust arithmetic means a client
 * cannot mint fresh buckets by forging header prefixes. Buckets are
 * configured with an hour-long refill so the tests observe the pure
 * burst bound deterministically, and unknown emails keep the identity
 * lockout (a per-ACCOUNT authority) out of the picture entirely.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.ratelimit.enabled=true",
            "shipperdog.ratelimit.trusted-proxy-count=1",
            "shipperdog.ratelimit.rules.login.capacity=3",
            "shipperdog.ratelimit.rules.login.refill=PT1H",
            "shipperdog.ratelimit.rules.downloads.capacity=2",
            "shipperdog.ratelimit.rules.downloads.refill=PT1H",
            "shipperdog.ratelimit.rules.webhooks.capacity=2",
            "shipperdog.ratelimit.rules.webhooks.refill=PT1H",
            "shipperdog.webhooks.jenkins-secrets=fixture-jenkins-secret"
        })
@Testcontainers
class RateLimitIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void loginFloodAnswers429EnvelopeWithRetryAfter() {
        Map<String, String> body = Map.of("email", "flood-" + System.nanoTime() + "@example.com", "password", "x");
        for (int i = 0; i < 3; i++) {
            assertThat(post(body, null).getStatusCode().value())
                    .as("attempt %d inside the burst", i + 1)
                    .isEqualTo(401);
        }
        ResponseEntity<String> refused = post(body, null);
        assertThat(refused.getStatusCode().value()).isEqualTo(429);

        HttpHeaders headers = refused.getHeaders();
        assertThat(headers.getFirst("Retry-After")).isNotNull();
        assertThat(Long.parseLong(headers.getFirst("Retry-After"))).isGreaterThanOrEqualTo(1);
        assertThat(headers.getFirst(HttpHeaders.CONTENT_TYPE)).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode problem = mapper.readTree(refused.getBody());
        assertThat(problem.path("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(problem.path("status").asInt()).isEqualTo(429);
        assertThat(problem.path("trace_id").asString()).isNotEmpty();
    }

    @Test
    void distinctClientsHaveIndependentBuckets() {
        Map<String, String> body = Map.of("email", "other-" + System.nanoTime() + "@example.com", "password", "x");
        // The trusted proxy appended exactly the client's address, so
        // each client's chain is that single entry.
        for (int i = 0; i < 3; i++) {
            post(body, "198.51.100.1");
        }
        assertThat(post(body, "198.51.100.1").getStatusCode().value()).isEqualTo(429);
        assertThat(post(body, "198.51.100.2").getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void forgedForwardedPrefixesCannotMultiplyBuckets() {
        Map<String, String> body = Map.of("email", "spoof-" + System.nanoTime() + "@example.com", "password", "x");
        // The attacker's forged prefix rides in front of the address
        // our trusted proxy appended; the bucket key never sees it.
        for (int i = 0; i < 3; i++) {
            post(body, "spoof-" + i + ", 198.51.100.7");
        }
        assertThat(post(body, "spoof-" + System.nanoTime() + ", 198.51.100.7")
                        .getStatusCode()
                        .value())
                .as("the prefix is untrusted; the bucket is the same")
                .isEqualTo(429);
    }

    @Test
    void downloadProxyGuessingIsThrottledBeforeTheTokenCheck() {
        // The anonymous proxy 404s every fake token; the limiter still
        // bounds the guessing rate — the throttle fires within the
        // burst regardless of each attempt's outcome.
        for (int i = 0; i < 2; i++) {
            assertThat(proxy("fake-token-" + i).getStatusCode().value()).isEqualTo(404);
        }
        assertThat(proxy("fake-token-3").getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void unthrottledEndpointsAreUntouched() {
        for (int i = 0; i < 10; i++) {
            assertThat(me().getStatusCode().value()).isEqualTo(401);
        }
    }

    /**
     * The inbound webhook receivers' rule (issue #54, S12-04): an
     * anonymous, provider-facing POST surface throttled per client --
     * a flood of bogus deliveries is refused with the same 429
     * envelope before it costs signature or chain work, and the
     * limiter's meter counts the rule.
     */
    @Test
    void webhookFloodAnswers429BeforeSignatureWork() {
        for (int i = 0; i < 2; i++) {
            assertThat(webhook().getStatusCode().value())
                    .as("attempt %d inside the burst", i + 1)
                    .isEqualTo(401);
        }
        ResponseEntity<String> refused = webhook();
        assertThat(refused.getStatusCode().value()).isEqualTo(429);
        assertThat(refused.getHeaders().getFirst("Retry-After")).isNotNull();
        JsonNode problem = mapper.readTree(refused.getBody());
        assertThat(problem.path("code").asString()).isEqualTo("RATE_LIMITED");
    }

    private ResponseEntity<String> webhook() {
        return neverThrow(client.post()
                .uri("/webhooks/jenkins")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("result", "FLOOD")));
    }

    /**
     * POSTs one login. {@code forwardedFor} is the full X-Forwarded-For chain the
     * app receives (entries our trusted proxy appended plus whatever the caller
     * forged in front of them).
     */
    private ResponseEntity<String> post(Map<String, String> body, String forwardedFor) {
        RestClient.RequestBodySpec spec = client.post().uri("/api/v1/auth/login");
        if (forwardedFor != null) {
            spec = spec.header("X-Forwarded-For", forwardedFor);
        }
        return neverThrow(spec.contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private ResponseEntity<String> proxy(String token) {
        return neverThrow(client.get().uri("/api/v1/downloads/" + token));
    }

    private ResponseEntity<String> me() {
        return neverThrow(client.get().uri("/api/v1/auth/me"));
    }

    /** Executes without RestClient's default 4xx/5xx exception so statuses assert directly. */
    private static ResponseEntity<String> neverThrow(RestClient.RequestHeadersSpec<?> spec) {
        return spec.retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class);
    }
}
