package com.shipperdog.api.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.Base64;
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
 * End-to-end acceptance flow for issue #4 against a real PostgreSQL and
 * HTTP stack: organization creation (creator becomes ORG_ADMIN),
 * admin-gated read/update, deactivation login blocks with the
 * platform-admin exemption, org-scoped invitations granting membership
 * and the org_id token claim, and the stored IdP configuration record.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class OrganizationFlowIntegrationTest {

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
    void organizationLifecycleEnforcesOrgAdminPermissions() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        String plainToken = registerOrglessUser("carol@example.com");

        assertThat(statusOf(client.post()
                        .uri("/api/v1/organizations")
                        .header("Authorization", "Bearer " + plainToken)
                        .body(Map.of("name", "Carol Industries"))))
                .as("only platform administrators may create organizations")
                .isEqualTo(403);
        assertThat(statusOf(client.post().uri("/api/v1/organizations").body(Map.of("name", "Anonymous Industries"))))
                .isEqualTo(401);

        JsonNode created = post(devToken, "/api/v1/organizations", Map.of("name", "Acme Industries"), 201);
        UUID orgId = UUID.fromString(created.get("id").asText());
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("externalId").isNull()).isTrue();

        String role = jdbc.queryForObject(
                "SELECT role FROM organization_members WHERE organization_id = ? AND user_id = "
                        + "(SELECT id FROM users WHERE email = ?)",
                String.class,
                orgId,
                DEV_ADMIN_EMAIL);
        assertThat(role).as("creator becomes Organization Admin (AC1)").isEqualTo("ORG_ADMIN");

        assertThat(statusOf(client.get()
                        .uri("/api/v1/organizations/" + orgId)
                        .header("Authorization", "Bearer " + devToken)))
                .isEqualTo(200);
        assertThat(statusOf(client.get()
                        .uri("/api/v1/organizations/" + orgId)
                        .header("Authorization", "Bearer " + plainToken)))
                .as("GET is admin-gated (AC2)")
                .isEqualTo(403);
        assertThat(statusOf(client.get()
                        .uri("/api/v1/organizations/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + devToken)))
                .isEqualTo(404);

        JsonNode updated = put(devToken, "/api/v1/organizations/" + orgId, Map.of("name", "Acme Industries AG"), 200);
        assertThat(updated.get("name").asText()).isEqualTo("Acme Industries AG");

        assertThat(audit.eventsOfType(AuditEventType.ORGANIZATION_CREATED))
                .extracting(AuditEvent::orgId)
                .contains(orgId);
        assertThat(audit.eventsOfType(AuditEventType.ORGANIZATION_UPDATED)
                        .getFirst()
                        .detail())
                .containsEntry("name.before", "Acme Industries")
                .containsEntry("name.after", "Acme Industries AG");
    }

    @Test
    void deactivatedOrganizationsBlockLoginsAndRefreshExceptForPlatformAdmins() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Beta Ops");

        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", "dave@example.com", "organizationId", orgId.toString(), "role", "ORG_MEMBER"),
                201);
        assertThat(invitation.get("organizationId").asText()).isEqualTo(orgId.toString());
        register(invitation.get("invitationToken").asText(), "dave@example.com");

        String daveTokens = login("dave@example.com", PASSWORD);
        String daveAccess = accessToken(daveTokens);
        String daveRefresh = refreshToken(daveTokens);

        assertThat(claimsOf(daveAccess).get("org_id").asText())
                .as("membership attaches the org_id claim")
                .isEqualTo(orgId.toString());
        JsonNode me = get(daveAccess, "/api/v1/auth/me", 200);
        assertThat(me.get("orgId").asText()).isEqualTo(orgId.toString());

        String rotatedRefresh = refreshToken(refreshTokens(daveRefresh));

        put(devToken, "/api/v1/organizations/" + orgId, Map.of("status", "DEACTIVATED"), 200);

        assertThat(statusOf(client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", "dave@example.com", "password", PASSWORD))))
                .as("deactivation blocks login (AC3)")
                .isEqualTo(401);
        assertThat(audit.eventsOfType(AuditEventType.LOGIN_FAILURE).getFirst().detail())
                .containsEntry("reason", "organization_deactivated");
        assertThat(audit.eventsOfType(AuditEventType.ORGANIZATION_UPDATED)
                        .getFirst()
                        .detail())
                .containsEntry("status.before", "ACTIVE")
                .containsEntry("status.after", "DEACTIVATED");

        assertThat(statusOf(client.post().uri("/api/v1/auth/refresh").body(Map.of("refreshToken", rotatedRefresh))))
                .as("deactivation refuses refresh so it outlives old sessions")
                .isEqualTo(401);

        UUID solePlatformAdmin = insertPlatformAdmin("ops-platform@example.com");
        jdbc.update(
                "INSERT INTO organization_members (organization_id, user_id, role) VALUES (?, ?, 'ORG_MEMBER')",
                orgId,
                solePlatformAdmin);
        assertThat(statusOf(client.post()
                        .uri("/api/v1/auth/login")
                        .body(Map.of("email", "ops-platform@example.com", "password", DEV_ADMIN_PASSWORD))))
                .as("platform administrators stay exempt (AC3)")
                .isEqualTo(200);
    }

    @Test
    void orgScopedInvitationsGrantMembershipWithRole() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Delta Labs");
        String memberToken = registerOrglessUser("erin@example.com");

        assertThat(statusOf(client.post()
                        .uri("/api/v1/auth/invitations")
                        .header("Authorization", "Bearer " + memberToken)
                        .body(Map.of(
                                "email", "frank@example.com",
                                "organizationId", orgId.toString(),
                                "role", "ORG_ADMIN"))))
                .as("non-admins may not invite into an organization")
                .isEqualTo(403);
        assertThat(statusOf(client.post()
                        .uri("/api/v1/auth/invitations")
                        .header("Authorization", "Bearer " + devToken)
                        .body(Map.of(
                                "email", "frank@example.com",
                                "organizationId", orgId.toString(),
                                "role", "NOT_A_ROLE"))))
                .isEqualTo(400);
        assertThat(statusOf(client.post()
                        .uri("/api/v1/auth/invitations")
                        .header("Authorization", "Bearer " + devToken)
                        .body(Map.of(
                                "email",
                                "frank@example.com",
                                "organizationId",
                                UUID.randomUUID().toString()))))
                .isEqualTo(404);

        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", "frank@example.com", "organizationId", orgId.toString(), "role", "ORG_ADMIN"),
                201);
        register(invitation.get("invitationToken").asText(), "frank@example.com");

        String grantedRole = jdbc.queryForObject(
                "SELECT role FROM organization_members WHERE organization_id = ? "
                        + "AND user_id = (SELECT id FROM users WHERE email = 'frank@example.com')",
                String.class,
                orgId);
        assertThat(grantedRole)
                .as("invitation role grants the membership (AC4)")
                .isEqualTo("ORG_ADMIN");
        assertThat(audit.eventsOfType(AuditEventType.ORG_MEMBER_ADDED)
                        .getFirst()
                        .orgId())
                .isEqualTo(orgId);

        String frankToken = accessToken(login("frank@example.com", PASSWORD));
        assertThat(statusOf(client.get()
                        .uri("/api/v1/organizations/" + orgId)
                        .header("Authorization", "Bearer " + frankToken)))
                .as("invited ORG_ADMIN can administer the organization")
                .isEqualTo(200);
    }

    @Test
    void idpConfigurationRecordIsStoredAndAudited() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Epsilon SSO");

        Map<String, Object> config = Map.of("issuer", "https://sso.example.com", "clientId", "shipperdog");
        JsonNode stored = put(
                devToken,
                "/api/v1/organizations/" + orgId + "/idp-config",
                Map.of("providerType", "oidc", "config", config),
                200);
        assertThat(stored.get("providerType").asText()).isEqualTo("oidc");
        assertThat(stored.get("config").get("issuer").asText()).isEqualTo("https://sso.example.com");

        JsonNode fetched = get(devToken, "/api/v1/organizations/" + orgId + "/idp-config", 200);
        assertThat(fetched.get("config").get("clientId").asText()).isEqualTo("shipperdog");
        assertThat(audit.eventsOfType(AuditEventType.ORG_IDP_CONFIGURED)).isNotEmpty();

        assertThat(statusOf(client.put()
                        .uri("/api/v1/organizations/" + orgId + "/idp-config")
                        .header("Authorization", "Bearer " + devToken)
                        .body(Map.of("providerType", "oidc", "config", "not-an-object"))))
                .isEqualTo(400);

        UUID unconfigured = createOrganization(devToken, "Zeta No SSO");
        assertThat(statusOf(client.get()
                        .uri("/api/v1/organizations/" + unconfigured + "/idp-config")
                        .header("Authorization", "Bearer " + devToken)))
                .isEqualTo(404);
    }

    @Test
    void externalIdIsPlatformAdminManagedAndUnique() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        String adminToken = loginOfOrgAdmin("eta@example.com", "Eta Industries");

        post(devToken, "/api/v1/organizations", Map.of("name", "Eta Billing", "externalId", "crm-eta-1"), 201);
        assertThat(statusOf(client.post()
                        .uri("/api/v1/organizations")
                        .header("Authorization", "Bearer " + devToken)
                        .body(Map.of("name", "Eta Clone", "externalId", "crm-eta-1"))))
                .as("external_id maps to exactly one organization")
                .isEqualTo(409);

        UUID orgId = organizationIdByName("Eta Industries");
        assertThat(statusOf(client.put()
                        .uri("/api/v1/organizations/" + orgId)
                        .header("Authorization", "Bearer " + adminToken)
                        .body(Map.of("externalId", "crm-eta-2"))))
                .as("externalId changes are platform-admin only")
                .isEqualTo(403);
        JsonNode updated = put(devToken, "/api/v1/organizations/" + orgId, Map.of("externalId", "crm-eta-2"), 200);
        assertThat(updated.get("externalId").asText()).isEqualTo("crm-eta-2");
    }

    @Test
    void deactivatedOrganizationsRejectNewMembersAtRegistration() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Theta Closed");
        put(devToken, "/api/v1/organizations/" + orgId, Map.of("status", "DEACTIVATED"), 200);

        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", "ivan@example.com", "organizationId", orgId.toString(), "role", "ORG_MEMBER"),
                201);
        assertThat(statusOf(client.post()
                        .uri("/api/v1/auth/register")
                        .body(Map.of(
                                "invitationToken",
                                invitation.get("invitationToken").asText(),
                                "email",
                                "ivan@example.com",
                                "password",
                                PASSWORD,
                                "fullName",
                                "Ivan Example"))))
                .as("a deactivated organization grants no new memberships")
                .isEqualTo(400);
    }

    @Test
    void listShowsOwnOrganizationsForMembersAndAllForPlatformAdmins() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        String memberToken = registerOrglessUser("kate@example.com");

        JsonNode memberList = get(memberToken, "/api/v1/organizations", 200);
        assertThat(memberList.isEmpty())
                .as("orgless users see no organizations")
                .isTrue();

        createOrganization(devToken, "Kappa Member Org");
        JsonNode platformList = get(devToken, "/api/v1/organizations", 200);
        assertThat(platformList.size()).isGreaterThanOrEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private UUID createOrganization(String token, String name) throws Exception {
        JsonNode created = post(token, "/api/v1/organizations", Map.of("name", name), 201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID organizationIdByName(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, name);
    }

    /** Registers an orgless user through a plain S1-03 invitation; returns an access token. */
    private String registerOrglessUser(String email) throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(devToken, "/api/v1/auth/invitations", Map.of("email", email), 201);
        register(invitation.get("invitationToken").asText(), email);
        return accessToken(login(email, PASSWORD));
    }

    /** Creates an organization as dev admin and registers its named ORG_ADMIN. */
    private String loginOfOrgAdmin(String email, String orgName) throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, orgName);
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", "ORG_ADMIN"),
                201);
        register(invitation.get("invitationToken").asText(), email);
        return accessToken(login(email, PASSWORD));
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
