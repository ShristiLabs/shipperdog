package com.shipperdog.api.config.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
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
 * The rate limiter's remaining edges (issue #39, S8-05): the
 * downloads rule covers the ISSUANCE route pre-authentication (an
 * unauthenticated flood of URL requests must be throttled before it
 * costs an engine decision), and the master switch turns the whole
 * tier off without touching any behavior (the fail-open posture an
 * operator gets from {@code RATE_LIMIT_ENABLED=false}).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.ratelimit.enabled=true",
            "shipperdog.ratelimit.rules.downloads.capacity=3",
            "shipperdog.ratelimit.rules.downloads.refill=PT1H"
        })
@Testcontainers
class RateLimitEdgeIntegrationTest {

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
    void issuanceFloodIsThrottledBeforeAuthenticationOrDecisions() {
        // Unauthenticated issuance attempts: each consumes a downloads
        // token at the filter (401 from the chain), and the burst
        // bound answers 429 before the fourth request spends an
        // engine evaluation on it.
        for (int i = 0; i < 3; i++) {
            assertThat(issuance("00000000-0000-4000-8000-00000000000" + i)).isEqualTo(401);
        }
        assertThat(issuance("00000000-0000-4000-8000-00000000000f")).isEqualTo(429);
    }

    private int issuance(String artifactId) {
        return client.get()
                .uri("/api/v1/artifacts/" + artifactId + "/download?version_id=00000000-0000-4000-8000-000000000001")
                .retrieve()
                .onStatus(status -> true, (request, response) -> {})
                .toEntity(String.class)
                .getStatusCode()
                .value();
    }
}
