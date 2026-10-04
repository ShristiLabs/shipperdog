package com.shipperdog.api.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Acceptance checks for the scaffold: the health endpoint answers UP without
 * authentication (for infrastructure probes), while everything else stays
 * locked down by default.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef")
@Testcontainers
class HealthEndpointTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void healthEndpointIsUpWithoutAuthentication() {
        String body = client().get().uri("/actuator/health").retrieve().body(String.class);

        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void otherActuatorEndpointsRequireAuthentication() {
        int status = client().get()
                .uri("/actuator")
                .exchange((request, response) -> response.getStatusCode().value());

        assertThat(status).isEqualTo(401);
    }
}
