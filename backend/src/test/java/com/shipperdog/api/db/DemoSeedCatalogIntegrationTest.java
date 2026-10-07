package com.shipperdog.api.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * The demo-tester fixture the post-deploy smoke test relies on
 * (issue #39, S8-05): the seeded tester authenticates and sees a
 * NON-EMPTY, INSTALLABLE catalog — the three-legged proof (identity
 * tier, wiring, authorization engine end to end) pinned in-repo so
 * the shell script is only the delivery vehicle, not the only
 * witness. The login rate limiter stays off in the suite (the shared
 * test-profile default), keeping this about the fixture, not the
 * bucket.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class DemoSeedCatalogIntegrationTest {

    private static final String DEMO_TESTER_EMAIL = "tester@shipperdog.local";
    private static final String DEMO_TESTER_PASSWORD = "shipperdog-dev-tester";

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
    void demoTesterSeesAnInstallableCatalog() {
        ResponseEntity<String> login = client.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", DEMO_TESTER_EMAIL, "password", DEMO_TESTER_PASSWORD))
                .retrieve()
                .toEntity(String.class);
        assertThat(login.getStatusCode().value())
                .as("seeded demo tester authenticates")
                .isEqualTo(200);
        String accessToken =
                mapper.readTree(login.getBody()).path("accessToken").asString();
        assertThat(accessToken).isNotEmpty();

        ResponseEntity<String> catalog = client.get()
                .uri("/api/v1/authorization/decisions/applications?limit=10")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .onStatus(status -> true, (request, raw) -> {})
                .toEntity(String.class);
        assertThat(catalog.getStatusCode().value()).isEqualTo(200);
        JsonNode body = mapper.readTree(catalog.getBody());
        assertThat(body.path("effectiveAccess").asBoolean()).isTrue();
        assertThat(body.path("items")).hasSize(1);
        JsonNode card = body.path("items").get(0);
        assertThat(card.path("application").path("name").asString()).isEqualTo("Banking");
        // Installable: the seeded READY artifact gives the ANDROID
        // slot its platform row (the no-dead-cards rule would drop
        // the card otherwise).
        assertThat(card.path("platforms").toString()).contains("ANDROID");
    }
}
