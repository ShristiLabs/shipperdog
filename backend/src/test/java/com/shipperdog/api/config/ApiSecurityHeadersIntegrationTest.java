package com.shipperdog.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The API-origin security-header contract (issue #39, S8-05): every
 * response — including the security chain's own 401 — carries the
 * hardening set the story pins. (The SPA origins' nginx set is graded
 * by the CI header check against the deployed image; this pins the
 * API's own Spring Security leg against the live server.)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class ApiSecurityHeadersIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    @Test
    void apiResponsesCarryTheSecurityHeaderSetEvenOnDenials() {
        RestClient client =
                RestClient.builder().baseUrl("http://localhost:" + port).build();
        // An unauthenticated business call: the 401 exercises the
        // security chain's headers — the layers every real response
        // passes through.
        ResponseEntity<String> response = client.get()
                .uri("/api/v1/auth/me")
                .retrieve()
                .onStatus(status -> true, (request, raw) -> {})
                .toEntity(String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(401);

        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getFirst("Strict-Transport-Security")).isEqualTo("max-age=31536000; includeSubDomains");
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.getFirst("Permissions-Policy"))
                .isEqualTo("geolocation=(), camera=(), microphone=(), payment=(), usb=()");
        assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
    }
}
