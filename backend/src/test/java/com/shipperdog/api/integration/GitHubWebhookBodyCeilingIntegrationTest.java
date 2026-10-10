package com.shipperdog.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The receiver's body ceiling (issue #53, S12-03): a delivery beyond
 * {@code shipperdog.webhooks.max-body-bytes} answers 413 BEFORE its
 * signature is even examined (the documented gate order -- ceiling,
 * signature, parse), and nothing about it reaches the ledger. Its own
 * context: the ceiling is dialed down to 64 bytes so a compact
 * delivery overflows it without a multi-megabyte fixture.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.webhooks.max-body-bytes=64"
        })
@Testcontainers
class GitHubWebhookBodyCeilingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    final ObjectMapper mapper = new ObjectMapper();

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void oversizedDeliveryAnswers413BeforeAnyOtherGate() {
        byte[] oversized = ("{\"padding\":\"" + "x".repeat(128) + "\"}").getBytes(StandardCharsets.UTF_8);
        Recording answer =
                deliver(oversized, "sha256=0000000000000000000000000000000000000000000000000000000000000000");
        assertThat(answer.status).isEqualTo(413);
        assertThat(answer.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_PAYLOAD_TOO_LARGE");
    }

    @Test
    void aDeliveryUnderTheCeilingIsNotAnsweredByTheCeiling() {
        byte[] compact = "{\"zen\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        Recording answer = deliver(compact, "sha256=0000000000000000000000000000000000000000000000000000000000000000");
        // Under the ceiling the next gate owns the answer: the bogus
        // signature's uniform 401 -- never the 413.
        assertThat(answer.status).isEqualTo(401);
        assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_SIGNATURE_REJECTED");
    }

    private Recording deliver(byte[] body, String signatureHeader) {
        return client.post()
                .uri("/webhooks/github")
                .header("X-Hub-Signature-256", signatureHeader)
                .header("X-GitHub-Delivery", "ceiling-" + java.util.UUID.randomUUID())
                .header("X-GitHub-Event", "workflow_run")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    byte[] raw = response.getBody().readAllBytes();
                    return new Recording(
                            response.getStatusCode().value(),
                            response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
                });
    }

    private record Recording(int status, String contentType, JsonNode body) {}
}
