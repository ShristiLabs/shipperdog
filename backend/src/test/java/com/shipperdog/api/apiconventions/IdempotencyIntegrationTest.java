package com.shipperdog.api.apiconventions;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end coverage of the Idempotency-Key contract (issue #8) on its
 * first designated endpoint, POST /api/v1/organizations: replay of stored
 * outcomes (success and deterministic errors alike), principal scoping,
 * 409 for in-progress keys, re-execution after expiry, header validation,
 * and the at-rest protection of hashing the raw key.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class IdempotencyIntegrationTest {

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
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void retryWithTheSameKeyReplaysTheStoredResponseWithoutReExecuting() throws Exception {
        String token = devToken();
        String key = UUID.randomUUID().toString();
        String name = "Idem Corp " + UUID.randomUUID();

        JsonNode first = postOrganization(token, key, name, 201, false);
        JsonNode replay = postOrganization(token, key, name, 201, true);

        assertThat(replay.get("id").asText())
                .as("the replay returns the same organization id")
                .isEqualTo(first.get("id").asText());
        assertThat(organizationCountByName(name))
                .as("only one organization was created")
                .isEqualTo(1);
    }

    @Test
    void requestsWithoutAKeyAlwaysExecute() throws Exception {
        String token = devToken();
        String name = "No Key Corp " + UUID.randomUUID();

        postOrganization(token, null, name, 201, false);
        postOrganization(token, null, name, 201, false);

        assertThat(organizationCountByName(name))
                .as("without the header every POST executes")
                .isEqualTo(2);
    }

    @Test
    void keysAreScopedToThePrincipal() throws Exception {
        String key = UUID.randomUUID().toString();
        String name = "Scoped Corp " + UUID.randomUUID();
        String outsiderToken = registerOrglessUser("idem.outsider@example.com");

        // The outsider has no creation permission: their denial is stored
        // and replayed, but it must not influence the platform admin's use
        // of the same key value.
        postOrganization(outsiderToken, key, name, 403, false);
        postOrganization(outsiderToken, key, name, 403, true);

        JsonNode created = postOrganization(devToken(), key, name, 201, false);
        assertThat(created.get("id").asText()).isNotBlank();
        assertThat(organizationCountByName(name)).isEqualTo(1);
    }

    @Test
    void inProgressKeysConflictUntilCompleted() throws Exception {
        String token = devToken();
        UUID principal = userId(DEV_ADMIN_EMAIL);
        String key = UUID.randomUUID().toString();
        insertKeyRow(
                principal, key, "PROCESSING", null, null, null, Instant.now().plus(1, ChronoUnit.HOURS));

        JsonNode conflict = postOrganization(token, key, "Racing Corp", 409, false);
        assertThat(conflict.get("code").asText()).isEqualTo("REQUEST_IN_PROGRESS");
    }

    @Test
    void expiredKeysReExecute() throws Exception {
        String token = devToken();
        UUID principal = userId(DEV_ADMIN_EMAIL);
        String key = UUID.randomUUID().toString();
        String name = "Stale Corp " + UUID.randomUUID();
        insertKeyRow(
                principal,
                key,
                "COMPLETED",
                201,
                MediaType.APPLICATION_JSON_VALUE,
                "{\"id\":\"" + UUID.randomUUID() + "\",\"stale\":true}",
                Instant.now().minus(1, ChronoUnit.HOURS));

        JsonNode fresh = postOrganization(token, key, name, 201, false);
        assertThat(fresh.has("stale"))
                .as("the expired stored response is not replayed")
                .isFalse();
        assertThat(organizationCountByName(name)).isEqualTo(1);
    }

    @Test
    void malformedKeysAreRejected() throws Exception {
        String token = devToken();
        String oversized = "k".repeat(256);

        JsonNode rejected = postOrganization(token, oversized, "Bad Key Corp", 400, false);
        assertThat(rejected.get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_INVALID");
    }

    @Test
    void storedKeysAreHashedNotKeptVerbatim() throws Exception {
        String token = devToken();
        String key = "verbatim-secret-key-" + UUID.randomUUID();
        String name = "Hashed Corp " + UUID.randomUUID();
        postOrganization(token, key, name, 201, false);

        String stored = jdbc.queryForObject(
                "SELECT key_hash FROM idempotency_keys WHERE principal_id = ? AND key_hash = ?",
                String.class,
                userId(DEV_ADMIN_EMAIL),
                sha256(key));
        assertThat(stored).as("the stored digest matches the raw key's SHA-256").isEqualTo(sha256(key));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM idempotency_keys WHERE key_hash = ? OR key_hash = ?",
                        Integer.class,
                        key,
                        "k".repeat(256)))
                .as("no raw key material is stored")
                .isZero();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private JsonNode postOrganization(
            String bearer, String key, String name, int expectedStatus, boolean expectReplay) {
        return client.post()
                .uri("/api/v1/organizations")
                .headers(headers -> {
                    headers.setBearerAuth(bearer);
                    if (key != null) {
                        headers.set("Idempotency-Key", key);
                    }
                })
                .body(Map.of("name", name))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value())
                            .as("organization POST status")
                            .isEqualTo(expectedStatus);
                    if (expectReplay) {
                        assertThat(response.getHeaders().getFirst("Idempotency-Replayed"))
                                .as("replayed responses are marked")
                                .isEqualTo("true");
                    } else {
                        assertThat(response.getHeaders().getFirst("Idempotency-Replayed"))
                                .isNull();
                    }
                    byte[] bytes = response.getBody().readAllBytes();
                    return bytes.length == 0
                            ? mapper.createObjectNode()
                            : mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
                });
    }

    private int organizationCountByName(String name) {
        return jdbc.queryForObject("SELECT count(*) FROM organizations WHERE name = ?", Integer.class, name);
    }

    private UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private void insertKeyRow(
            UUID principal,
            String rawKey,
            String state,
            Integer status,
            String contentType,
            String body,
            Instant expiresAt) {
        jdbc.update(
                "INSERT INTO idempotency_keys "
                        + "(principal_id, key_hash, method, request_path, state, response_status, response_content_type, response_body, expires_at) "
                        + "VALUES (?, ?, 'POST', '/api/v1/organizations', ?, ?, ?, ?, ?)",
                principal,
                sha256(rawKey),
                state,
                status,
                contentType,
                body,
                java.sql.Timestamp.from(expiresAt));
    }

    private String devToken() {
        JsonNode tokens = mapper.readTree(client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", DEV_ADMIN_EMAIL, "password", DEV_ADMIN_PASSWORD))
                .retrieve()
                .body(String.class));
        return tokens.get("accessToken").asText();
    }

    private String registerOrglessUser(String email) {
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .headers(headers -> headers.setBearerAuth(devToken()))
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
                        "Idempotency User"))
                .retrieve()
                .body(String.class);
        JsonNode tokens = mapper.readTree(client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", PASSWORD))
                .retrieve()
                .body(String.class));
        return tokens.get("accessToken").asText();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
