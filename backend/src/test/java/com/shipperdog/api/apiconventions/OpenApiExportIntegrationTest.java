package com.shipperdog.api.apiconventions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Generates the OpenAPI contract at build time (issue #8, acceptance
 * criterion 5): fetches springdoc's YAML from the running application and
 * writes it to {@code backend/target/openapi.yaml}, which CI publishes as
 * an artifact and diffs against the committed baseline
 * ({@code docs/openapi/baseline.yaml}) with oasdiff so a breaking change
 * fails the build. The contract is therefore always exactly what the code
 * serves — never a hand-maintained file.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class OpenApiExportIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void exportsOpenApiYamlAtBuildTime() {
        RestClient client =
                RestClient.builder().baseUrl("http://localhost:" + port).build();

        String yaml = client.get().uri("/v3/api-docs.yaml").retrieve().body(String.class);
        assertThat(yaml).as("the YAML spec document is served").isNotBlank();
        assertThat(yaml).contains("/api/v1/");

        // Structure assertions run against the JSON flavor (same content).
        JsonNode spec =
                mapper.readTree(client.get().uri("/v3/api-docs").retrieve().body(String.class));
        assertThat(spec.path("openapi").asString()).startsWith("3.");
        assertThat(spec.path("paths").has("/api/v1/organizations")).isTrue();

        Path output = Path.of("target", "openapi.yaml");
        try {
            Files.createDirectories(output.getParent());
            Files.writeString(output, yaml, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("cannot write " + output, e);
        }
    }
}
