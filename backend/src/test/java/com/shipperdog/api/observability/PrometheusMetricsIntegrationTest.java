package com.shipperdog.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.config.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Metrics acceptance for issue #7: {@code /actuator/prometheus} exposes
 * JVM and HTTP metrics under the environment-configured exposure (AC3),
 * the {@code shipperdog_*} namespace exists with the first PRD-section-28
 * metric recorded by the RBAC enforcement point (AC5,
 * {@code authorization_decision_duration}, tagged by outcome), and the
 * scrape endpoint stays reachable without authentication exactly because
 * it is served on the internal management port in the local stack (the
 * security posture the issue demands).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "management.endpoints.web.exposure.include=health,info,prometheus"
        })
@Testcontainers
class PrometheusMetricsIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MeterRegistry meters;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void prometheusEndpointExposesJvmHttpAndShipperdogMetrics() throws Exception {
        // Traffic first: an allowed decision (platform admin) and a
        // denied one (authenticated non-member), so the timers exist.
        String devToken = devAdminToken();
        registerInvitedUser(devToken, "metrics.bob@example.com");
        String bobToken = accessToken(login("metrics.bob@example.com", PASSWORD));
        assertThat(get("/api/v1/organizations/" + UUID.randomUUID(), devToken)).isEqualTo(404);
        assertThat(get("/api/v1/organizations/" + UUID.randomUUID(), bobToken))
                .as("denied decisions are concealed as 404 (issue #8) but still timed")
                .isEqualTo(404);

        String scrape = client.get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(scrape).isNotNull();

        assertThat(scrape).contains("jvm_memory_used_bytes");
        assertThat(scrape)
                .as("HTTP latency histogram buckets are published for p95 SLO computation")
                .contains("http_server_requests_seconds_bucket");
        assertThat(scrape)
                .as("authorization_decision_duration renders in the shipperdog namespace with both outcomes")
                .contains("shipperdog_authorization_decision_duration_seconds_count")
                .contains("outcome=\"allow\"")
                .contains("outcome=\"deny\"");
    }

    @Test
    void healthStaysPublicAndApiStaysProtectedAlongsideTheScrapeEndpoint() {
        assertThat(get("/actuator/health", null)).isEqualTo(200);
        assertThat(get("/actuator/info", null)).isEqualTo(200);
        assertThat(get("/api/v1/auth/me", null))
                .as("business API stays authenticated")
                .isEqualTo(401);
    }

    @Test
    void authorizationDecisionsAreTimedTaggedByOutcome() throws Exception {
        String devToken = devAdminToken();
        UUID organizationId = createOrganization(devToken, "Timer Corp");
        registerInvitedUser(devToken, "timer.carol@example.com");
        String carolToken = accessToken(login("timer.carol@example.com", PASSWORD));

        assertThat(get("/api/v1/organizations/" + organizationId, carolToken)).isEqualTo(404);
        assertThat(get("/api/v1/organizations/" + organizationId, devToken)).isEqualTo(200);

        Timer allowed = meters.find(MetricNames.AUTHORIZATION_DECISION_DURATION)
                .tag("outcome", "allow")
                .timer();
        Timer denied = meters.find(MetricNames.AUTHORIZATION_DECISION_DURATION)
                .tag("outcome", "deny")
                .timer();
        assertThat(allowed).as("allowed decisions are timed").isNotNull();
        assertThat(denied).as("denied decisions are timed").isNotNull();
        assertThat(allowed.count()).isPositive();
        assertThat(denied.count()).isPositive();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    private String accessToken(String tokensJson) throws Exception {
        return mapper.readTree(tokensJson).get("accessToken").asText();
    }

    private String devAdminToken() throws Exception {
        return accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
    }

    private void registerInvitedUser(String token, String email) {
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .headers(headers -> headers.setBearerAuth(token))
                .body(Map.of("email", email))
                .retrieve()
                .body(JsonNode.class);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Metrics User"))
                .retrieve()
                .body(String.class);
    }

    private UUID createOrganization(String token, String name) {
        JsonNode created = client.post()
                .uri("/api/v1/organizations")
                .headers(headers -> headers.setBearerAuth(token))
                .body(Map.of("name", name))
                .retrieve()
                .body(JsonNode.class);
        return UUID.fromString(created.get("id").asText());
    }

    private int get(String uri, String bearer) {
        return client.get()
                .uri(uri)
                .headers(headers -> {
                    if (bearer != null) {
                        headers.setBearerAuth(bearer);
                    }
                })
                .exchange((req, res) -> res.getStatusCode().value());
    }
}
