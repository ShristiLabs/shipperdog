package com.shipperdog.api.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Boundary branches of the organization change (issue #4) that the flow
 * tests do not isolate: expiry of org-scoped invitations, the
 * platform-admin exemption covering refresh as well as login,
 * reversibility of deactivation, the org_id claim preferring the
 * earliest ACTIVE organization, and the absence of the org_id claim
 * for users without a membership.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class OrganizationEdgeCasesIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";
    /** Argon2id digest of DEV_ADMIN_PASSWORD (same parameters as the seed). */
    private static final String DEV_ADMIN_HASH =
            "$argon2id$v=19$m=19456,t=2,p=1$rcTTp+SlrHFDVHLZ2ldpgA$IrecYlooXAarJah1NUyEO0taZ+VUYgc715akqUftZU4";

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
    void expiredOrgInvitationsAreRejectedWithoutGrantingMembership() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Iota Expired Org");
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", "expired-invite@example.com", "organizationId", orgId.toString(), "role", "ORG_MEMBER"),
                201);

        jdbc.update(
                "UPDATE user_invitations SET expires_at = now() - interval '1 minute' WHERE email = ?",
                "expired-invite@example.com");

        assertThat(registerStatus(invitation.get("invitationToken").asText(), "expired-invite@example.com"))
                .as("an expired org invitation registers nobody")
                .isEqualTo(400);
        Integer expired = jdbc.queryForObject(
                "SELECT count(*) FROM user_invitations WHERE email = ? AND status = 'EXPIRED'",
                Integer.class,
                "expired-invite@example.com");
        assertThat(expired).isEqualTo(1);
        Integer memberships = jdbc.queryForObject(
                "SELECT count(*) FROM organization_members m JOIN users u ON u.id = m.user_id " + "WHERE u.email = ?",
                Integer.class,
                "expired-invite@example.com");
        assertThat(memberships).as("no membership is granted post-expiry").isEqualTo(0);
    }

    @Test
    void platformAdminsStayLoggedInAndRefreshableInsideDeactivatedOrganizations() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Lambda Exempt Org");
        UUID adminId = insertPlatformAdmin("refresh-exempt@example.com");
        jdbc.update(
                "INSERT INTO organization_members (organization_id, user_id, role) VALUES (?, ?, 'ORG_MEMBER')",
                orgId,
                adminId);
        put(devToken, "/api/v1/organizations/" + orgId, Map.of("status", "DEACTIVATED"), 200);

        String tokens = login("refresh-exempt@example.com", DEV_ADMIN_PASSWORD);
        assertThat(tokens)
                .as("platform admins log into their deactivated org (AC3)")
                .isNotNull();
        String rotated = refreshTokens(refreshToken(tokens));
        assertThat(rotated).as("the exemption covers refresh, not only login").isNotNull();
        assertThat(accessToken(rotated)).isNotBlank();
    }

    @Test
    void reactivatedOrganizationsAcceptLoginsAgain() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Mu Rebind Org");
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", "relogin@example.com", "organizationId", orgId.toString(), "role", "ORG_MEMBER"),
                201);
        register(invitation.get("invitationToken").asText(), "relogin@example.com");

        put(devToken, "/api/v1/organizations/" + orgId, Map.of("status", "DEACTIVATED"), 200);
        assertThat(statusOf(client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", "relogin@example.com", "password", PASSWORD))))
                .isEqualTo(401);

        put(devToken, "/api/v1/organizations/" + orgId, Map.of("status", "ACTIVE"), 200);
        assertThat(statusOf(client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", "relogin@example.com", "password", PASSWORD))))
                .as("reactivation restores logins for existing members")
                .isEqualTo(200);
    }

    @Test
    void orgIdClaimPrefersTheEarliestActiveOrganization() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID earliest = createOrganization(devToken, "Nu Earliest Deactivated");
        UUID later = createOrganization(devToken, "Xi Later Active");
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", "multi-org@example.com", "organizationId", earliest.toString(), "role", "ORG_MEMBER"),
                201);
        register(invitation.get("invitationToken").asText(), "multi-org@example.com");
        jdbc.update(
                "INSERT INTO organization_members (organization_id, user_id, role) "
                        + "SELECT ?, id, 'ORG_MEMBER' FROM users WHERE email = 'multi-org@example.com'",
                later);
        // Make the deactivated-to-be organization definitively the earliest.
        jdbc.update(
                "UPDATE organization_members SET created_at = now() - interval '1 hour' WHERE organization_id = ?",
                earliest);
        put(devToken, "/api/v1/organizations/" + earliest, Map.of("status", "DEACTIVATED"), 200);

        String tokens = login("multi-org@example.com", PASSWORD);
        assertThat(tokens)
                .as("a later active organization keeps the user login-eligible")
                .isNotNull();
        assertThat(claimsOf(accessToken(tokens)).path("org_id").asText())
                .as("the claim carries the active organization, never the deactivated earliest one")
                .isEqualTo(later.toString());
        JsonNode me = get(accessToken(tokens), "/api/v1/auth/me", 200);
        assertThat(me.get("orgId").asText()).isEqualTo(later.toString());
    }

    @Test
    void orglessUserTokensOmitTheOrgIdClaim() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(devToken, "/api/v1/auth/invitations", Map.of("email", "orgless@example.com"), 201);
        register(invitation.get("invitationToken").asText(), "orgless@example.com");

        String access = accessToken(login("orgless@example.com", PASSWORD));
        assertThat(claimsOf(access).has("org_id"))
                .as("absent org_id means no organization context, never a default")
                .isFalse();
        JsonNode me = get(access, "/api/v1/auth/me", 200);
        assertThat(me.get("orgId").isNull()).isTrue();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private UUID createOrganization(String token, String name) throws Exception {
        JsonNode created = post(token, "/api/v1/organizations", Map.of("name", name), 201);
        return UUID.fromString(created.get("id").asText());
    }

    /** Inserts a platform administrator with the seed's dev password hash. */
    private UUID insertPlatformAdmin(String email) {
        return jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, full_name, platform_admin) "
                        + "VALUES (?, ?, 'Platform Ops', true) RETURNING id",
                UUID.class,
                email,
                DEV_ADMIN_HASH);
    }

    private void register(String invitationToken, String email) {
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken", invitationToken,
                        "email", email,
                        "password", PASSWORD,
                        "fullName", "Registered User"))
                .retrieve()
                .body(String.class);
    }

    private int registerStatus(String invitationToken, String email) {
        return client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken", invitationToken,
                        "email", email,
                        "password", PASSWORD,
                        "fullName", "Registered User"))
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    private String refreshTokens(String refreshToken) {
        return client.post()
                .uri("/api/v1/auth/refresh")
                .body(Map.of("refreshToken", refreshToken))
                .retrieve()
                .body(String.class);
    }

    private JsonNode claimsOf(String access) throws Exception {
        String[] parts = access.split("\\.");
        assertThat(parts).hasSize(3);
        return mapper.readTree(new String(Base64.getUrlDecoder().decode(parts[1])));
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return client.post()
                .uri(uri)
                .header("Authorization", "Bearer " + bearer)
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                    return mapper.readValue(response.getBody(), JsonNode.class);
                });
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return client.put()
                .uri(uri)
                .header("Authorization", "Bearer " + bearer)
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                    return mapper.readValue(response.getBody(), JsonNode.class);
                });
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return client.get().uri(uri).header("Authorization", "Bearer " + bearer).exchange((request, response) -> {
            assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
            return mapper.readValue(response.getBody(), JsonNode.class);
        });
    }

    private int statusOf(org.springframework.web.client.RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((req, res) -> res.getStatusCode().value());
    }

    private String accessToken(String tokensJson) throws Exception {
        return mapper.readTree(tokensJson).get("accessToken").asText();
    }

    private String refreshToken(String tokensJson) throws Exception {
        return mapper.readTree(tokensJson).get("refreshToken").asText();
    }
}
