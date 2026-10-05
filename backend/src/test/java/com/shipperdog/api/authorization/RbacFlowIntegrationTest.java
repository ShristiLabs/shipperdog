package com.shipperdog.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end RBAC acceptance flow for issue #5 against a real PostgreSQL
 * and HTTP stack: the permission matrix enforced through
 * {@code @PreAuthorize} (deny-by-default, opaque 403s), role assignment
 * endpoints for organization and tenant scopes, the union of roles
 * across scopes, tenant-isolation and cross-role-escalation denials,
 * immediate effect of role changes with refresh-token revocation on
 * demotion, and the last-admin guard.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class RbacFlowIntegrationTest {

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
    void permissionMatrixGrantsAndDeniesByRole() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Matrix Industries");
        registerOrgMember(devToken, orgId, "matrix.alice@example.com", "ORG_ADMIN");
        registerOrgMember(devToken, orgId, "matrix.bob@example.com", "DEVELOPER");
        registerOrgMember(devToken, orgId, "matrix.carol@example.com", "ORG_MEMBER");
        UUID bobId = userId("matrix.bob@example.com");

        String aliceToken = accessToken(login("matrix.alice@example.com", PASSWORD));
        String bobToken = accessToken(login("matrix.bob@example.com", PASSWORD));
        String carolToken = accessToken(login("matrix.carol@example.com", PASSWORD));

        assertThat(get("/api/v1/organizations/" + orgId, bobToken))
                .as("deny-by-default: DEVELOPER's denial is concealed as 404 (issue #8)")
                .isEqualTo(404);
        assertThat(get("/api/v1/organizations/" + orgId + "/members", carolToken))
                .as("ORG_MEMBER holds no catalog permissions; concealed as 404")
                .isEqualTo(404);
        assertThat(get("/api/v1/organizations/" + orgId, null))
                .as("unauthenticated callers get 401, not 403")
                .isEqualTo(401);

        JsonNode members = getJson("/api/v1/organizations/" + orgId + "/members", aliceToken, 200);
        assertThat(members.get("items").size()).isEqualTo(4);
        assertThat(members.get("items").toString()).contains("alice@example.com", "DEVELOPER", "ORG_MEMBER");
        assertThat(members.has("next_cursor"))
                .as("single page exhausts the member list")
                .isFalse();

        JsonNode changed = putJson(
                "/api/v1/organizations/" + orgId + "/members/" + bobId + "/role",
                aliceToken,
                Map.of("role", "QA_ENGINEER"),
                200);
        assertThat(changed.get("role").asText()).isEqualTo("QA_ENGINEER");
        // DEVELOPER loses artifact permissions under QA_ENGINEER, so this
        // change counts as a demotion and revokes bob's sessions.
        assertThat(audit.eventsOfType(AuditEventType.ORG_MEMBER_ROLE_CHANGED)
                        .getFirst()
                        .detail())
                .containsEntry("role.before", "DEVELOPER")
                .containsEntry("role.after", "QA_ENGINEER")
                .containsEntry("sessionsRevoked", "true");

        assertThat(put(
                        "/api/v1/organizations/" + orgId + "/members/" + bobId + "/role",
                        aliceToken,
                        Map.of("role", "NOT_A_ROLE")))
                .isEqualTo(400);
        assertThat(put(
                        "/api/v1/organizations/" + orgId + "/members/" + UUID.randomUUID() + "/role",
                        aliceToken,
                        Map.of("role", "DEVELOPER")))
                .isEqualTo(404);
    }

    @Test
    void denialsAreOpaqueForUnauthorizedCallers() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Opaque Ops");
        registerOrgMember(devToken, orgId, "oscar@example.com", "VIEWER");
        String oscarToken = accessToken(login("oscar@example.com", PASSWORD));

        assertThat(get("/api/v1/organizations/" + orgId, oscarToken))
                .as("permission denial on an existing organization is concealed")
                .isEqualTo(404);
        assertThat(get("/api/v1/organizations/" + UUID.randomUUID(), oscarToken))
                .as("denial on an unknown organization is indistinguishable")
                .isEqualTo(404);

        JsonNode body = getJson("/api/v1/organizations/" + orgId, oscarToken, 404);
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("code").asText()).isEqualTo("ORGANIZATION_NOT_FOUND");
        assertThat(body.get("title").asText()).isEqualTo("Not Found");
        assertThat(body.get("detail").asText()).isEqualTo("Resource not found.");
        assertThat(body.get("trace_id").asText()).isNotBlank();
        assertThat(body.has("error")).as("legacy error-key must be gone").isFalse();
        // Byte-for-byte identical problem as a permitted caller's genuine
        // miss: concealment and real 404s share one shape (issue #8, PRD 12).
        JsonNode genuine = getJson("/api/v1/organizations/" + UUID.randomUUID(), devToken, 404);
        assertThat(genuine.get("code").asText()).isEqualTo("ORGANIZATION_NOT_FOUND");
        assertThat(genuine.get("detail").asText()).isEqualTo(body.get("detail").asText());
        assertThat(genuine.get("title").asText()).isEqualTo(body.get("title").asText());

        assertThat(get("/api/v1/organizations/" + UUID.randomUUID(), devToken))
                .as("permitted callers still learn existence (404)")
                .isEqualTo(404);
    }

    @Test
    void tenantIsolationAndCrossRoleEscalationAreDenied() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Isolation Inc");
        UUID tenantOne = insertTenant(orgId, "alpha");
        UUID tenantTwo = insertTenant(orgId, "beta");
        registerOrgMember(devToken, orgId, "iso.alice@example.com", "ORG_ADMIN");
        registerOrgMember(devToken, orgId, "ted@example.com", "ORG_MEMBER");
        registerOrgMember(devToken, orgId, "tina@example.com", "ORG_MEMBER");
        UUID tedId = userId("ted@example.com");
        UUID tinaId = userId("tina@example.com");

        String aliceToken = accessToken(login("iso.alice@example.com", PASSWORD));
        String tedToken = accessToken(login("ted@example.com", PASSWORD));
        String tinaToken = accessToken(login("tina@example.com", PASSWORD));

        putJson(
                "/api/v1/tenants/" + tenantOne + "/members/" + tedId + "/role",
                aliceToken,
                Map.of("role", "TENANT_ADMIN"),
                200);
        putJson(
                "/api/v1/tenants/" + tenantOne + "/members/" + tinaId + "/role",
                aliceToken,
                Map.of("role", "TENANT_TESTER"),
                200);
        assertThat(audit.eventsOfType(AuditEventType.TENANT_MEMBER_ROLE_CHANGED))
                .hasSize(2);

        assertThat(get("/api/v1/tenants/" + tenantOne + "/members", tedToken))
                .as("TENANT_ADMIN lists their tenant's members")
                .isEqualTo(200);
        assertThat(get("/api/v1/tenants/" + tenantOne + "/members", aliceToken))
                .as("ORG_ADMIN of the owning organization holds tenant permissions (union)")
                .isEqualTo(200);
        assertThat(get("/api/v1/tenants/" + tenantOne + "/members", devToken))
                .as("platform administrators pass every check")
                .isEqualTo(200);
        assertThat(get("/api/v1/tenants/" + tenantTwo + "/members", tedToken))
                .as("tenant isolation: cross-tenant denial is concealed as 404")
                .isEqualTo(404);
        assertThat(get("/api/v1/tenants/" + tenantOne + "/members", tinaToken))
                .as("TENANT_TESTER lacks tenant:members:read; concealed as 404")
                .isEqualTo(404);

        assertThat(put(
                        "/api/v1/tenants/" + tenantTwo + "/members/" + tinaId + "/role",
                        tedToken,
                        Map.of("role", "TENANT_TESTER")))
                .as("tenant isolation: managing another tenant's members is denied (concealed 404)")
                .isEqualTo(404);
        assertThat(put(
                        "/api/v1/organizations/" + orgId + "/members/" + tinaId + "/role",
                        tedToken,
                        Map.of("role", "ORG_ADMIN")))
                .as("cross-role escalation: TENANT_ADMIN cannot assign organization roles (concealed 404)")
                .isEqualTo(404);
        assertThat(post("/api/v1/organizations", tedToken, Map.of("name", "Ted Corp")))
                .as("target-less permission denial stays an opaque 403 (issue #8)")
                .isEqualTo(403);
        assertThat(get("/api/v1/tenants/" + UUID.randomUUID() + "/members", tedToken))
                .as("tenant denials are opaque too")
                .isEqualTo(404);

        assertThat(delete("/api/v1/tenants/" + tenantOne + "/members/" + tinaId, tedToken))
                .isEqualTo(204);
        assertThat(audit.eventsOfType(AuditEventType.TENANT_MEMBER_REMOVED)).hasSize(1);
    }

    @Test
    void roleChangesApplyImmediatelyAndDemotionRevokesSessions() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Swift Changes");
        registerOrgMember(devToken, orgId, "swift.alice@example.com", "ORG_ADMIN");
        registerOrgMember(devToken, orgId, "swift.carol@example.com", "ORG_MEMBER");
        UUID aliceId = userId("swift.alice@example.com");
        UUID carolId = userId("swift.carol@example.com");

        String aliceTokens = login("swift.alice@example.com", PASSWORD);
        String aliceAccess = accessToken(aliceTokens);
        String aliceRefresh = refreshToken(aliceTokens);
        assertThat(get("/api/v1/organizations/" + orgId, aliceAccess)).isEqualTo(200);

        // ORG_ADMIN -> DEVELOPER loses permissions: demotion.
        putJson(
                "/api/v1/organizations/" + orgId + "/members/" + aliceId + "/role",
                devToken,
                Map.of("role", "DEVELOPER"),
                200);

        assertThat(get("/api/v1/organizations/" + orgId, aliceAccess))
                .as("role change takes effect on the live token immediately (concealed 404)")
                .isEqualTo(404);
        assertThat(refreshStatus(aliceRefresh))
                .as("demotion revokes the refresh family")
                .isEqualTo(401);
        assertThat(audit.eventsOfType(AuditEventType.TOKEN_REVOKED).getFirst().detail())
                .containsEntry("reason", "role_demoted");

        String reLogin = accessToken(login("swift.alice@example.com", PASSWORD));
        assertThat(get("/api/v1/organizations/" + orgId, reLogin))
                .as("re-login works but DEVELOPER still lacks org read (concealed 404)")
                .isEqualTo(404);

        // Pure upgrade (ORG_MEMBER -> VIEWER): no revocation, session lives.
        String carolTokens = login("swift.carol@example.com", PASSWORD);
        putJson(
                "/api/v1/organizations/" + orgId + "/members/" + carolId + "/role",
                devToken,
                Map.of("role", "VIEWER"),
                200);
        assertThat(refreshStatus(refreshToken(carolTokens)))
                .as("upgrades keep the session alive")
                .isEqualTo(200);
    }

    @Test
    void effectivePermissionsAreTheUnionAcrossScopes() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgOne = createOrganization(devToken, "Union One");
        UUID orgTwo = createOrganization(devToken, "Union Two");
        registerOrgMember(devToken, orgOne, "dave@example.com", "ORG_MEMBER");
        registerOrgMember(devToken, orgOne, "eve@example.com", "ORG_MEMBER");
        UUID daveId = userId("dave@example.com");
        UUID eveId = userId("eve@example.com");
        String daveToken = accessToken(login("dave@example.com", PASSWORD));

        assertThat(get("/api/v1/organizations/" + orgOne, daveToken))
                .as("member of org one: denied there (concealed 404)")
                .isEqualTo(404);
        assertThat(get("/api/v1/organizations/" + orgTwo, daveToken))
                .as("not a member of org two: denied there too (concealed 404)")
                .isEqualTo(404);

        // A tenant role grants inside the tenant even with a member-only
        // organization role (union of org and tenant scopes).
        UUID tenantId = insertTenant(orgOne, "union");
        putJson(
                "/api/v1/tenants/" + tenantId + "/members/" + daveId + "/role",
                devToken,
                Map.of("role", "TENANT_ADMIN"),
                200);
        assertThat(get("/api/v1/tenants/" + tenantId + "/members", daveToken))
                .as("tenant role grants despite member-only org role")
                .isEqualTo(200);

        // The same user becomes ORG_ADMIN of a second organization: the
        // union is evaluated per target scope. (Second memberships are
        // seeded directly: the invitation flow registers new addresses
        // only, while role assignment changes existing memberships.)
        jdbc.update(
                "INSERT INTO organization_members (organization_id, user_id, role) VALUES (?, ?, 'ORG_ADMIN')",
                orgTwo,
                daveId);
        String freshDaveToken = accessToken(login("dave@example.com", PASSWORD));
        assertThat(get("/api/v1/organizations/" + orgTwo, freshDaveToken))
                .as("org role grants at its own scope")
                .isEqualTo(200);
        assertThat(get("/api/v1/organizations/" + orgOne, freshDaveToken))
                .as("roles do not leak across organizations (concealed 404)")
                .isEqualTo(404);

        // Deactivated organizations grant nothing. Eve keeps an ACTIVE
        // membership in org one so the deactivation login block does not
        // interfere; her ORG_ADMIN role in the dead organization must not
        // grant anything.
        UUID orgThree = createOrganization(devToken, "Union Dead");
        jdbc.update(
                "INSERT INTO organization_members (organization_id, user_id, role) VALUES (?, ?, 'ORG_ADMIN')",
                orgThree,
                eveId);
        putJson("/api/v1/organizations/" + orgThree, devToken, Map.of("status", "DEACTIVATED"), 200);
        String eveToken = accessToken(login("eve@example.com", PASSWORD));
        assertThat(get("/api/v1/organizations/" + orgThree, eveToken))
                .as("roles in a DEACTIVATED organization grant nothing (concealed 404)")
                .isEqualTo(404);
        assertThat(get("/api/v1/organizations/" + orgOne, eveToken))
                .as("the ACTIVE membership still evaluates by its own role (concealed 404)")
                .isEqualTo(404);
    }

    @Test
    void lastAdministratorCannotBeRemovedOrDemotedAway() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, "Sole Admin");
        registerOrgMember(devToken, orgId, "sole.alice@example.com", "ORG_ADMIN");
        UUID aliceId = userId("sole.alice@example.com");
        UUID devId = userId(DEV_ADMIN_EMAIL);

        // The creator is the second admin; remove their membership so
        // alice is the sole ORG_ADMIN (platform access is flag-based).
        jdbc.update("DELETE FROM organization_members WHERE organization_id = ? AND user_id = ?", orgId, devId);

        String aliceToken = accessToken(login("sole.alice@example.com", PASSWORD));
        assertThat(put(
                        "/api/v1/organizations/" + orgId + "/members/" + aliceId + "/role",
                        aliceToken,
                        Map.of("role", "DEVELOPER")))
                .as("the last ORG_ADMIN cannot demote away")
                .isEqualTo(409);
        assertThat(delete("/api/v1/organizations/" + orgId + "/members/" + aliceId, aliceToken))
                .as("the last ORG_ADMIN cannot be removed")
                .isEqualTo(409);
        assertThat(delete("/api/v1/organizations/" + orgId + "/members/" + aliceId, devToken))
                .as("platform administrators hit the same guard")
                .isEqualTo(409);

        // With a second admin in place, removal works and revokes sessions.
        registerOrgMember(devToken, orgId, "backup@example.com", "ORG_ADMIN");
        assertThat(delete("/api/v1/organizations/" + orgId + "/members/" + aliceId, devToken))
                .isEqualTo(204);
        assertThat(audit.eventsOfType(AuditEventType.ORG_MEMBER_REMOVED)).hasSize(1);
    }

    @Test
    void disabledAccountsCannotRefreshOrUsePersonalAccessTokens() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = postJson("/api/v1/auth/invitations", devToken, Map.of("email", "frank@example.com"), 201);
        register(invitation.get("invitationToken").asText(), "frank@example.com");
        String tokens = login("frank@example.com", PASSWORD);
        JsonNode pat = postJson("/api/v1/auth/tokens", accessToken(tokens), Map.of("name", "ci-token"), 201);
        assertThat(get("/api/v1/auth/me", pat.get("token").asText())).isEqualTo(200);

        jdbc.update("UPDATE users SET status = 'DISABLED' WHERE email = ?", "frank@example.com");

        assertThat(get("/api/v1/auth/me", pat.get("token").asText()))
                .as("DISABLED accounts cannot authenticate with PATs")
                .isEqualTo(401);
        assertThat(refreshStatus(refreshToken(tokens)))
                .as("DISABLED accounts cannot refresh")
                .isEqualTo(401);
    }

    @Test
    void crossOrganizationEscalationViaAssignmentAndInvitationsIsDenied() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgOne = createOrganization(devToken, "East Corp");
        UUID orgTwo = createOrganization(devToken, "West Corp");
        registerOrgMember(devToken, orgOne, "east.alice@example.com", "ORG_ADMIN");
        registerOrgMember(devToken, orgTwo, "west.bob@example.com", "ORG_MEMBER");
        UUID bobId = userId("west.bob@example.com");
        String aliceToken = accessToken(login("east.alice@example.com", PASSWORD));

        assertThat(put(
                        "/api/v1/organizations/" + orgTwo + "/members/" + bobId + "/role",
                        aliceToken,
                        Map.of("role", "ORG_ADMIN")))
                .as("ORG_ADMIN of one organization cannot assign roles in another (concealed 404)")
                .isEqualTo(404);
        assertThat(post(
                        "/api/v1/auth/invitations",
                        aliceToken,
                        Map.of(
                                "email",
                                "mallory@example.com",
                                "organizationId",
                                orgTwo.toString(),
                                "role",
                                "ORG_ADMIN")))
                .as("ORG_ADMIN of one organization cannot invite ORG_ADMIN into another (concealed 404)")
                .isEqualTo(404);
        assertThat(delete("/api/v1/organizations/" + orgTwo + "/members/" + bobId, aliceToken))
                .as("ORG_ADMIN of one organization cannot remove members of another (concealed 404)")
                .isEqualTo(404);
        assertThat(get("/api/v1/organizations/" + orgOne + "/members", aliceToken))
                .as("the same caller remains effective inside their own organization")
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private UUID createOrganization(String token, String name) throws Exception {
        JsonNode created = postJson("/api/v1/organizations", token, Map.of("name", name), 201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID insertTenant(UUID orgId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO tenants (organization_id, name, external_id) VALUES (?, ?, ?) RETURNING id",
                UUID.class,
                orgId,
                name,
                "ext-" + UUID.randomUUID());
    }

    /** Invites an email into the organization with the role and registers it. */
    private void registerOrgMember(String token, UUID orgId, String email, String role) throws Exception {
        JsonNode invitation = postJson(
                "/api/v1/auth/invitations",
                token,
                Map.of("email", email, "organizationId", orgId.toString(), "role", role),
                201);
        register(invitation.get("invitationToken").asText(), email);
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

    private UUID userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    private String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    private String accessToken(String tokensJson) throws Exception {
        return mapper.readTree(tokensJson).get("accessToken").asText();
    }

    private String refreshToken(String tokensJson) throws Exception {
        return mapper.readTree(tokensJson).get("refreshToken").asText();
    }

    private int refreshStatus(String refreshToken) {
        return client.post()
                .uri("/api/v1/auth/refresh")
                .body(Map.of("refreshToken", refreshToken))
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private JsonNode getJson(String uri, String bearer, int expectedStatus) {
        return client.get()
                .uri(uri)
                .headers(headers -> authorize(headers, bearer))
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                    return mapper.readValue(response.getBody(), JsonNode.class);
                });
    }

    private int get(String uri, String bearer) {
        return client.get()
                .uri(uri)
                .headers(headers -> authorize(headers, bearer))
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private JsonNode postJson(String uri, String bearer, Map<String, Object> body, int expectedStatus) {
        return client.post()
                .uri(uri)
                .headers(headers -> authorize(headers, bearer))
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                    return mapper.readValue(response.getBody(), JsonNode.class);
                });
    }

    private int post(String uri, String bearer, Map<String, Object> body) {
        return client.post()
                .uri(uri)
                .headers(headers -> authorize(headers, bearer))
                .body(body)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private JsonNode putJson(String uri, String bearer, Map<String, Object> body, int expectedStatus) {
        return client.put()
                .uri(uri)
                .headers(headers -> authorize(headers, bearer))
                .body(body)
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
                    return mapper.readValue(response.getBody(), JsonNode.class);
                });
    }

    private int put(String uri, String bearer, Map<String, Object> body) {
        return client.put()
                .uri(uri)
                .headers(headers -> authorize(headers, bearer))
                .body(body)
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private int delete(String uri, String bearer) {
        return client.delete()
                .uri(uri)
                .headers(headers -> authorize(headers, bearer))
                .exchange((req, res) -> res.getStatusCode().value());
    }

    private static void authorize(HttpHeaders headers, String bearer) {
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
    }
}
