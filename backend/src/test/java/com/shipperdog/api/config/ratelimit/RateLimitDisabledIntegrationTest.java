package com.shipperdog.api.config.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The limiter's master switch (issue #39, S8-05): with
 * {@code shipperdog.ratelimit.enabled=false} the tier is entirely
 * absent — the same traffic that throttles at capacity 3 in the
 * sibling suites passes unbounded here. This is the fail-open
 * posture an operator buys with {@code RATE_LIMIT_ENABLED=false},
 * and it must not leak a single 429.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.ratelimit.enabled=false",
            "shipperdog.ratelimit.rules.login.capacity=3",
            "shipperdog.ratelimit.rules.login.refill=PT1H"
        })
@Testcontainers
class RateLimitDisabledIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void disabledLimiterNeverAnswers429() {
        Map<String, String> body = Map.of("email", "off-" + System.nanoTime() + "@example.com", "password", "x");
        for (int i = 0; i < 10; i++) {
            int attempt = client.post()
                    .uri("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(code -> true, (request, response) -> {})
                    .toEntity(String.class)
                    .getStatusCode()
                    .value();
            assertThat(attempt).as("attempt %d with the limiter off", i + 1).isEqualTo(401);
        }
    }
}
