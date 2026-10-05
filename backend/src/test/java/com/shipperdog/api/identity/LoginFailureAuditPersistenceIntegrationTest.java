package com.shipperdog.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Reproduces the S1-07 follow-up fixed in issue #8: a failed login throws,
 * and under the old wrapping transaction the BEFORE_COMMIT audit sink's
 * LOGIN_FAILURE row rolled back with it — the event was logged but never
 * persisted. The fix (login no longer opens the transaction; only the
 * success path is atomic) must keep every failure branch durable in
 * {@code audit_events}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class LoginFailureAuditPersistenceIntegrationTest {

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
    private tools.jackson.databind.ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void unknownAddressFailuresPersistTheirAuditRow() {
        int before = loginFailureCount();

        int status = loginStatus("nobody@example.com", "wrong-password");

        assertThat(status).isEqualTo(401);
        assertThat(loginFailureCount() - before)
                .as("the rolled-back login no longer swallows its LOGIN_FAILURE row")
                .isEqualTo(1);
    }

    @Test
    void wrongPasswordFailuresPersistTheirAuditRowWithReason() {
        registerInvitedUser("audit.failure@example.com");
        int before = loginFailureCount();

        int status = loginStatus("audit.failure@example.com", "wrong-password");

        assertThat(status).isEqualTo(401);
        assertThat(loginFailureCount() - before).isEqualTo(1);
        String reason = jdbc.queryForObject(
                "SELECT detail ->> 'reason' FROM audit_events WHERE event_type = 'LOGIN_FAILURE' "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                String.class);
        assertThat(reason).isEqualTo("invalid_credentials");
    }

    private int loginFailureCount() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = 'LOGIN_FAILURE'", Integer.class);
        return count == null ? 0 : count;
    }

    private int loginStatus(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private void registerInvitedUser(String email) {
        String adminToken = mapper.readTree(client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", DEV_ADMIN_EMAIL, "password", DEV_ADMIN_PASSWORD))
                        .retrieve()
                        .body(String.class))
                .get("accessToken")
                .asString();
        tools.jackson.databind.JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .headers(headers -> headers.setBearerAuth(adminToken))
                .body(Map.of("email", email))
                .retrieve()
                .body(tools.jackson.databind.JsonNode.class);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asString(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Audit Failure User"))
                .retrieve()
                .body(String.class);
    }
}
