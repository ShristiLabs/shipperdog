package com.shipperdog.api.scim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The SCIM surface's rate-limit leg (issue #69, S14-03): the
 * connector routes join the limiter's rule table (the webhook
 * precedent — anonymous, provider-facing, retrying), throttled per
 * client before the request costs token-hash or provisioning work.
 * The burst is configured tight with an hour-long refill so the test
 * observes the pure bound deterministically; the 429 keeps the
 * platform envelope (the limiter is deployment infrastructure, not
 * SCIM protocol — documented in docs/authentication.md).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.ratelimit.enabled=true",
            "shipperdog.ratelimit.trusted-proxy-count=1",
            "shipperdog.ratelimit.rules.scim.capacity=3",
            "shipperdog.ratelimit.rules.scim.refill=PT1H"
        })
@Testcontainers
class ScimRateLimitIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";

    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";

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
    void scimFloodAnswers429WithRetryAfter() {
        String devToken = loginToken();
        JsonNode organization = client.post()
                .uri("/api/v1/organizations")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "SCIM Rate Limit " + System.nanoTime()))
                .retrieve()
                .body(JsonNode.class);
        String orgId = organization.get("id").asText();
        client.put()
                .uri("/api/v1/organizations/" + orgId + "/scim-config")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", true, "groupRoleMappings", List.of()))
                .exchange((request, response) -> null);
        String token = client.post()
                .uri("/api/v1/organizations/" + orgId + "/scim-config/token")
                .header("Authorization", "Bearer " + devToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .exchange((request, response) -> mapper.readTree(
                                new String(response.getBody().readAllBytes()))
                        .get("token")
                        .asText());

        // The burst bound: three requests pass, the fourth is refused.
        for (int i = 0; i < 3; i++) {
            assertThat(scimStatus(token))
                    .as("request %d inside the burst", i + 1)
                    .isEqualTo(200);
        }
        org.springframework.http.ResponseEntity<String> refused = client.get()
                .uri("/scim/v2/Users")
                .headers(headers -> headers.set("Authorization", "Bearer " + token))
                .exchange(
                        (request, response) -> org.springframework.http.ResponseEntity.status(response.getStatusCode())
                                .headers(response.getHeaders())
                                .body(new String(response.getBody().readAllBytes())));
        assertThat(refused.getStatusCode().value()).isEqualTo(429);
        assertThat(refused.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(refused.getBody()).contains("RATE_LIMITED");
    }

    private int scimStatus(String token) {
        return client.get()
                .uri("/scim/v2/Users")
                .headers(headers -> headers.set("Authorization", "Bearer " + token))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private String loginToken() {
        return client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", DEV_ADMIN_EMAIL, "password", DEV_ADMIN_PASSWORD))
                .retrieve()
                .body(JsonNode.class)
                .get("accessToken")
                .asText();
    }
}
