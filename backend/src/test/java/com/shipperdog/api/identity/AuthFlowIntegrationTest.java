package com.shipperdog.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import java.util.Map;
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
 * End-to-end acceptance flow for issue #3 against a real PostgreSQL and
 * HTTP stack: bootstrap admin login (dev seed) - invitation - registration
 * - login - token claims - refresh rotation with reuse detection - logout
 * - personal access tokens. Also asserts the password-hash storage
 * contract (Argon2id, no plaintext anywhere).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class AuthFlowIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String ALICE = "alice@example.com";
    private static final String ALICE_PASSWORD = "correct-horse-battery-1";

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
    void fullAuthenticationLifecycle() throws Exception {
        String devTokens = login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD);
        String devAccessToken = accessToken(devTokens);
        assertThat(devTokens).isNotBlank();

        String invitationToken = createInvitation(devAccessToken, ALICE);
        JsonNode user = register(invitationToken, ALICE, ALICE_PASSWORD, "Alice Example");
        String userId = user.get("id").asText();
        assertThat(userId).isNotBlank();

        assertThatPasswordIsHashedWithArgon2id(ALICE);

        assertThat(loginStatus(ALICE, "wrong-password-1")).isEqualTo(401);
        String tokens = login(ALICE, ALICE_PASSWORD);
        String access = accessToken(tokens);
        String refresh = refreshToken(tokens);

        assertAccessTokenClaims(access, userId);

        JsonNode me = get("/api/v1/auth/me", access, 200, JsonNode.class);
        assertThat(me.get("email").asText()).isEqualTo(ALICE);
        assertThat(me.get("authenticatedVia").asText()).isEqualTo("jwt");
        assertThat(me.has("orgId")).isTrue();
        assertThat(me.get("orgId").isNull()).isTrue();

        refreshTokenIsRejectedAsAccessCredential(refresh);

        String rotated = client.post()
                .uri("/api/v1/auth/refresh")
                .body(Map.of("refreshToken", refresh))
                .retrieve()
                .body(String.class);
        String rotatedRefresh = refreshToken(rotated);

        assertThat(statusOf(client.post().uri("/api/v1/auth/refresh").body(Map.of("refreshToken", refresh))))
                .as("reusing a rotated refresh token must fail")
                .isEqualTo(401);
        assertThat(audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.TOKEN_REUSE_DETECTED))
                .isNotEmpty();

        client.post()
                .uri("/api/v1/auth/logout")
                .body(Map.of("refreshToken", rotatedRefresh))
                .retrieve()
                .toBodilessEntity();
        assertThat(statusOf(client.post().uri("/api/v1/auth/refresh").body(Map.of("refreshToken", rotatedRefresh))))
                .as("family revocation must kill the rotated successor too")
                .isEqualTo(401);

        assertThat(audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.LOGIN_SUCCESS))
                .isNotEmpty();
        assertThat(audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.LOGIN_FAILURE))
                .isNotEmpty();
        assertThat(audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.TOKEN_ISSUED))
                .isNotEmpty();
        assertThat(audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.TOKEN_REFRESHED))
                .isNotEmpty();
        assertThat(audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.TOKEN_REVOKED))
                .isNotEmpty();
        assertThat(audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.USER_REGISTERED))
                .isNotEmpty();
        assertThat(audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.INVITATION_CREATED))
                .isNotEmpty();
    }

    @Test
    void personalAccessTokensAuthenticateAndRevoke() throws Exception {
        String devTokens = login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD);
        String devAccessToken = accessToken(devTokens);

        JsonNode created = client.post()
                .uri("/api/v1/auth/tokens")
                .header("Authorization", "Bearer " + devAccessToken)
                .body(Map.of("name", "ci-token"))
                .retrieve()
                .body(JsonNode.class);
        String rawPat = created.get("token").asText();
        assertThat(rawPat).startsWith("sdp_");

        JsonNode meWithPat = get("/api/v1/auth/me", rawPat, 200, JsonNode.class);
        assertThat(meWithPat.get("authenticatedVia").asText()).isEqualTo("pat");

        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT token_prefix, last_used_at FROM personal_access_tokens");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("last_used_at")).isNotNull();
        assertThat(rawPat).startsWith((String) rows.get(0).get("token_prefix"));

        JsonNode list = get("/api/v1/auth/tokens", devAccessToken, 200, JsonNode.class);
        assertThat(list.get("items").size()).isEqualTo(1);
        assertThat(list.hasNonNull("next_cursor"))
                .as("one token is the last page")
                .isFalse();
        assertThat(list.get("items").get(0).has("token"))
                .as("listings never contain the raw token")
                .isFalse();
        assertThat(list.get("items").get(0).get("name").asText()).isEqualTo("ci-token");

        client.delete()
                .uri("/api/v1/auth/tokens/" + created.get("id").asText())
                .header("Authorization", "Bearer " + devAccessToken)
                .retrieve()
                .toBodilessEntity();
        assertThat(statusOf(client.get().uri("/api/v1/auth/me").header("Authorization", "Bearer " + rawPat)))
                .isEqualTo(401);
        assertThat(audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.PAT_CREATED))
                .isNotEmpty();
        assertThat(audit.eventsOfType(com.shipperdog.api.audit.AuditEventType.PAT_REVOKED))
                .isNotEmpty();
    }

    @Test
    void registrationRulesAreEnforced() throws Exception {
        String devAccessToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        String invitationToken = createInvitation(devAccessToken, "bob@example.com");

        assertThat(registerStatus(invitationToken, "bob@example.com", "short", "Bob"))
                .as("weak password rejected")
                .isEqualTo(400);
        assertThat(registerStatus(invitationToken, "mallory@example.com", "a-long-enough-password", "Mallory"))
                .as("invitation bound to the invited address")
                .isEqualTo(400);
        assertThat(registerStatus(invitationToken, "bob@example.com", "a-long-enough-password", "Bob"))
                .isEqualTo(201);
        assertThat(registerStatus(invitationToken, "bob@example.com", "a-long-enough-password", "Bob Again"))
                .as("invitation is single-use")
                .isEqualTo(400);

        Integer accepted = jdbc.queryForObject(
                "SELECT count(*) FROM user_invitations WHERE email = 'bob@example.com' AND status = 'ACCEPTED'",
                Integer.class);
        assertThat(accepted).isEqualTo(1);
    }

    @Test
    void unauthenticatedAndMalformedRequestsAreRejected() {
        assertThat(statusOf(client.get().uri("/api/v1/auth/me"))).isEqualTo(401);
        assertThat(statusOf(client.get()
                        .uri("/api/v1/auth/me")
                        .header("Authorization", "Bearer sdp_not-a-real-token-value")))
                .isEqualTo(401);
        assertThat(statusOf(client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", "ghost@nowhere.example", "password", "whatever-password"))))
                .isEqualTo(401);
    }

    private void assertThatPasswordIsHashedWithArgon2id(String email) {
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hash).startsWith("$argon2id$");
        assertThat(hash).doesNotContain(ALICE_PASSWORD);
    }

    private void assertAccessTokenClaims(String access, String userId) throws Exception {
        String[] parts = access.split("\\.");
        assertThat(parts).hasSize(3);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        JsonNode claims = mapper.readTree(payload);
        assertThat(claims.get("sub").asText()).isEqualTo(userId);
        assertThat(claims.get("type").asText()).isEqualTo("access");
        assertThat(claims.get("iss").asText()).isEqualTo("shipperdog-api");
        assertThat(claims.has("org_id")).isFalse();
        assertThat(claims.has("tenant_id")).isFalse();
        long ttl = claims.get("exp").asLong() - claims.get("iat").asLong();
        assertThat(ttl).isEqualTo(15 * 60);
    }

    private void refreshTokenIsRejectedAsAccessCredential(String refresh) {
        assertThat(statusOf(client.get().uri("/api/v1/auth/me").header("Authorization", "Bearer " + refresh)))
                .isEqualTo(401);
    }

    private String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    private int statusOf(org.springframework.web.client.RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((req, res) -> res.getStatusCode().value());
    }

    private int loginStatus(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private String createInvitation(String accessToken, String email) {
        JsonNode invitation = client.post()
                .uri("/api/v1/auth/invitations")
                .header("Authorization", "Bearer " + accessToken)
                .body(Map.of("email", email))
                .retrieve()
                .body(JsonNode.class);
        return invitation.get("invitationToken").asText();
    }

    private JsonNode register(String invitationToken, String email, String password, String fullName) {
        return client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken", invitationToken,
                        "email", email,
                        "password", password,
                        "fullName", fullName))
                .retrieve()
                .body(JsonNode.class);
    }

    private int registerStatus(String invitationToken, String email, String password, String fullName) {
        return client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken", invitationToken,
                        "email", email,
                        "password", password,
                        "fullName", fullName))
                .exchange((request, response) -> response.getStatusCode().value());
    }

    private <T> T get(String uri, String bearer, int expectedStatus, Class<T> type) {
        return client.get().uri(uri).header("Authorization", "Bearer " + bearer).exchange((request, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
            return mapper.readValue(response.getBody(), type);
        });
    }

    private String accessToken(String tokensJson) throws Exception {
        return mapper.readTree(tokensJson).get("accessToken").asText();
    }

    private String refreshToken(String tokensJson) throws Exception {
        return mapper.readTree(tokensJson).get("refreshToken").asText();
    }
}
