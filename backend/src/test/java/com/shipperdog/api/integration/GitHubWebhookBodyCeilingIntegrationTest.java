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
        // The bounded-read filter (S12-04) answers this leg now, with
        // the same envelope the controller used (the limiter's charset
        // suffix included) -- the ceiling's contract is unchanged.
        assertThat(answer.contentType).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_PAYLOAD_TOO_LARGE");
    }

    @Test
    void theCeilingIsFamilyWideNotGithubOnly() {
        // The bounded-read filter and the controller ceiling are the
        // shared framework's, so every receiver refuses the same leg
        // (checked on the Jenkins and App Store routes; the gate
        // fires before any credential is even consulted).
        byte[] oversized = ("{\"padding\":\"" + "x".repeat(128) + "\"}").getBytes(StandardCharsets.UTF_8);
        for (String route : java.util.List.of("/webhooks/jenkins", "/webhooks/appstore")) {
            Recording answer = client.post()
                    .uri(route)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(oversized)
                    .exchange((request, response) -> {
                        byte[] raw = response.getBody().readAllBytes();
                        return new Recording(
                                response.getStatusCode().value(),
                                response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                                raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw));
                    });
            assertThat(answer.status).as("route %s", route).isEqualTo(413);
            assertThat(answer.body.get("code").asText()).isEqualTo("WEBHOOK_PAYLOAD_TOO_LARGE");
        }
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

    /**
     * The bounded-read leg (issue #54, S12-04 -- the S12-03 review's
     * deferred hardening): a POST whose DECLARED Content-Length is
     * over the ceiling is refused before a single body byte is
     * buffered -- a raw socket sends only the headers, and the 413
     * problem+json envelope arrives while the body is still unsent.
     * The response is read by its own Content-Length (the container
     * waits to discard the unread request body before closing, so
     * EOF never arrives to a caller that sent none of it).
     */
    @Test
    void anOversizedDeclaredContentLengthIsRefusedBeforeItsBytesAreRead() throws Exception {
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            String request = "POST /webhooks/github HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: 99999999\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int total = 0;
            while (total < 4096) {
                int read = socket.getInputStream().read(buffer);
                if (read < 0) {
                    break;
                }
                received.write(buffer, 0, read);
                total += read;
                String partial = received.toString(StandardCharsets.UTF_8);
                if (partial.contains("\r\n\r\n") && partial.length() > partial.indexOf("\r\n\r\n") + 16) {
                    break;
                }
            }
            String response = received.toString(StandardCharsets.UTF_8);
            assertThat(response).startsWith("HTTP/1.1 413");
            assertThat(response).contains("WEBHOOK_PAYLOAD_TOO_LARGE");
            assertThat(response).contains("application/problem+json");
        }
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
