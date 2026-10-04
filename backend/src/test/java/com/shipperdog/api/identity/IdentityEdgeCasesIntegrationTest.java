package com.shipperdog.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Error and boundary branches of the identity change (issue #3) that the
 * flow-level tests do not reach: disabled accounts, lockout recovery,
 * token expiry at the record level, wrong-token-type usage, invitation
 * lifecycle, email normalization, PAT expiry and ownership, and signature
 * tampering.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class IdentityEdgeCasesIntegrationTest {

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
    private AuditCapture audit;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
    }

    @Test
    void disabledAccountsCannotLogIn() throws Exception {
        String email = registerFreshUser();

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE email = ?", email);

        assertThat(loginStatus(email, PASSWORD)).isEqualTo(401);
        assertThat(audit.eventsOfType(AuditEventType.LOGIN_FAILURE))
                .extracting(AuditEvent::detail)
                .anySatisfy(detail -> assertThat(detail).containsEntry("reason", "account_disabled"));
    }

    @Test
    void lockoutAllowsLoginAgainAfterItExpires() throws Exception {
        String email = registerFreshUser();
        for (int i = 0; i < 5; i++) {
            assertThat(loginStatus(email, "definitely-wrong-" + i)).isEqualTo(401);
        }
        assertThat(loginStatus(email, PASSWORD)).isEqualTo(401);

        jdbc.update("UPDATE users SET locked_until = now() - interval '1 minute' WHERE email = ?", email);

        assertThat(loginStatus(email, PASSWORD))
                .as("expired lock no longer blocks")
                .isEqualTo(200);
    }

    @Test
    void refreshFailsWhenTheStoredRecordIsExpired() throws Exception {
        String email = registerFreshUser();
        String refreshToken = loginAndExtract(email, "refreshToken");

        jdbc.update(
                "UPDATE refresh_tokens SET expires_at = now() - interval '1 minute' WHERE token_hash = ?",
                SecureTokens.sha256Hex(refreshToken));

        assertThat(statusOf(client.post().uri("/api/v1/auth/refresh").body(Map.of("refreshToken", refreshToken))))
                .isEqualTo(401);
    }

    @Test
    void accessTokensAreRejectedAtTheRefreshEndpoint() throws Exception {
        String email = registerFreshUser();
        String accessToken = loginAndExtract(email, "accessToken");

        assertThat(statusOf(client.post().uri("/api/v1/auth/refresh").body(Map.of("refreshToken", accessToken))))
                .isEqualTo(401);
    }

    @Test
    void reinvitingAnAddressRevokesItsPendingInvitation() throws Exception {
        String email = uniqueEmail();
        String devAccessToken = loginAndExtract(DEV_ADMIN_EMAIL, "accessToken");
        for (int i = 0; i < 2; i++) {
            client.post()
                    .uri("/api/v1/auth/invitations")
                    .header("Authorization", "Bearer " + devAccessToken)
                    .body(Map.of("email", email))
                    .retrieve()
                    .toBodilessEntity();
        }

        Integer pending = jdbc.queryForObject(
                "SELECT count(*) FROM user_invitations WHERE email = ? AND status = 'PENDING'", Integer.class, email);
        Integer revoked = jdbc.queryForObject(
                "SELECT count(*) FROM user_invitations WHERE email = ? AND status = 'REVOKED'", Integer.class, email);
        assertThat(pending).isEqualTo(1);
        assertThat(revoked).isEqualTo(1);
    }

    @Test
    void expiredInvitationsAreRejectedAndMarkedExpired() throws Exception {
        String email = uniqueEmail();
        String invitationToken = invite(email);

        jdbc.update(
                "UPDATE user_invitations SET expires_at = now() - interval '1 minute' WHERE token_hash = ?",
                SecureTokens.sha256Hex(invitationToken));

        assertThat(registerStatus(invitationToken, email)).isEqualTo(400);
        Integer expired = jdbc.queryForObject(
                "SELECT count(*) FROM user_invitations WHERE email = ? AND status = 'EXPIRED'", Integer.class, email);
        assertThat(expired).isEqualTo(1);
    }

    @Test
    void emailsAreNormalizedCaseAndWhitespaceInsensitively() throws Exception {
        String email = uniqueEmail();
        String invitationToken = invite(email);

        String mixed = email.toUpperCase();
        assertThat(registerStatus(invitationToken, mixed)).isEqualTo(201);

        assertThat(statusOf(client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", "  " + mixed + "  ", "password", PASSWORD))))
                .isEqualTo(200);
    }

    @Test
    void expiredPersonalAccessTokensStopWorking() throws Exception {
        String devAccessToken = loginAndExtract(DEV_ADMIN_EMAIL, "accessToken");
        JsonNode created = client.post()
                .uri("/api/v1/auth/tokens")
                .header("Authorization", "Bearer " + devAccessToken)
                .body(Map.of("name", "expiring", "expiresInSeconds", 3600))
                .retrieve()
                .body(JsonNode.class);

        jdbc.update(
                "UPDATE personal_access_tokens SET expires_at = now() - interval '1 minute' WHERE id = ?",
                java.util.UUID.fromString(created.get("id").asText()));

        assertThat(statusOf(client.get()
                        .uri("/api/v1/auth/me")
                        .header(
                                "Authorization",
                                "Bearer " + created.get("token").asText())))
                .isEqualTo(401);
    }

    @Test
    void usersCannotRevokeSomeoneElsesPersonalAccessToken() throws Exception {
        String devAccessToken = loginAndExtract(DEV_ADMIN_EMAIL, "accessToken");
        JsonNode adminPat = client.post()
                .uri("/api/v1/auth/tokens")
                .header("Authorization", "Bearer " + devAccessToken)
                .body(Map.of("name", "admin-only"))
                .retrieve()
                .body(JsonNode.class);

        String otherAccessToken = loginAndExtract(registerFreshUser(), "accessToken");

        assertThat(statusOf(client.delete()
                        .uri("/api/v1/auth/tokens/" + adminPat.get("id").asText())
                        .header("Authorization", "Bearer " + otherAccessToken)))
                .isEqualTo(404);
        assertThat(statusOf(client.get()
                        .uri("/api/v1/auth/me")
                        .header(
                                "Authorization",
                                "Bearer " + adminPat.get("token").asText())))
                .as("the PAT still authenticates its owner")
                .isEqualTo(200);
    }

    @Test
    void tamperedSignaturesAreRejected() throws Exception {
        String accessToken = loginAndExtract(registerFreshUser(), "accessToken");
        String[] parts = accessToken.split("\\.");
        String signature = parts[2];
        char flipped = signature.charAt(signature.length() - 1) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "." + signature.substring(0, signature.length() - 1) + flipped;

        assertThat(statusOf(client.get().uri("/api/v1/auth/me").header("Authorization", "Bearer " + tampered)))
                .isEqualTo(401);
    }

    private String uniqueEmail() {
        return "edge-" + UUID.randomUUID() + "@example.com";
    }

    private String registerFreshUser() throws Exception {
        String email = uniqueEmail();
        String invitationToken = invite(email);
        assertThat(registerStatus(invitationToken, email)).isEqualTo(201);
        return email;
    }

    private String invite(String email) {
        String devAccessToken;
        try {
            devAccessToken = loginAndExtract(DEV_ADMIN_EMAIL, "accessToken");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .header("Authorization", "Bearer " + devAccessToken)
                .body(Map.of("email", email))
                .retrieve()
                .body(JsonNode.class);
        return invitation.get("invitationToken").asText();
    }

    private int registerStatus(String invitationToken, String email) {
        return statusOf(client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken", invitationToken,
                        "email", email,
                        "password", PASSWORD,
                        "fullName", "Edge Case User")));
    }

    private String loginAndExtract(String email, String field) throws Exception {
        String body = client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", email.equals(DEV_ADMIN_EMAIL) ? DEV_ADMIN_PASSWORD : PASSWORD))
                .retrieve()
                .body(String.class);
        return mapper.readTree(body).get(field).asText();
    }

    private int loginStatus(String email, String password) {
        return statusOf(client.post().uri("/api/v1/auth/login").body(Map.of("email", email, "password", password)));
    }

    private int statusOf(RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((req, res) -> res.getStatusCode().value());
    }
}
