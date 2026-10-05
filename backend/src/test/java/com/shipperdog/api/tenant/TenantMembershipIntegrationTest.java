package com.shipperdog.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
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
import org.springframework.http.HttpMethod;
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
 * End-to-end acceptance flow for issue #11 (S2-03) member management:
 * member assignment with idempotent duplicates, the membership
 * suspension switch (both switches must be on for access), removal
 * revoking tenant context at the next token refresh, the
 * manage-vs-read role split, and audit coverage of every change.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class TenantMembershipIntegrationTest {

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
    void memberAssignmentIsIdempotentAndAudited() throws Exception {
        String adminToken = loginOfOrgAdmin("m-idem-admin@example.com", "Member Idem Corp");
        UUID tenantId = createTenant(adminToken, organizationIdByName("Member Idem Corp"), "Idem Bank", "crm-m-idem-1");
        String memberEmail = insertOrglessUser("m-idem-member@example.com");
        UUID memberId = userIdByEmail(memberEmail);

        JsonNode created = post(
                adminToken, tenantUri(tenantId), Map.of("userId", memberId.toString(), "role", "TENANT_ADMIN"), 201);
        assertThat(created.get("userId").asText()).isEqualTo(memberId.toString());
        assertThat(created.get("role").asText()).isEqualTo("TENANT_ADMIN");
        assertThat(created.get("status").asText())
                .as("memberships are born ACTIVE (AC1)")
                .isEqualTo("ACTIVE");
        assertThat(created.get("email").asText()).isEqualTo("m-idem-member@example.com");

        JsonNode replay = post(
                adminToken, tenantUri(tenantId), Map.of("userId", memberId.toString(), "role", "TENANT_ADMIN"), 200);
        assertThat(replay.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ?",
                        Integer.class,
                        tenantId,
                        memberId))
                .as("the duplicate assignment wrote no second row (AC1)")
                .isEqualTo(1);

        AuditEvent added =
                audit.eventsOfType(AuditEventType.TENANT_MEMBER_ADDED).getFirst();
        assertThat(added.tenantId()).isEqualTo(tenantId);
        assertThat(added.detail())
                .containsEntry("userId", memberId.toString())
                .containsEntry("role", "TENANT_ADMIN")
                .containsEntry("status", "ACTIVE");
        assertThat(audit.eventsOfType(AuditEventType.TENANT_MEMBER_ADDED))
                .as("the idempotent replay is no change and audits nothing new")
                .hasSize(1);

        Recording conflict = post(
                "/api/v1/tenants/" + tenantId + "/members",
                adminToken,
                Map.of("userId", memberId.toString(), "role", "TENANT_TESTER"));
        assertThat(conflict.status)
                .as("a duplicate with a different role is a conflict; role changes belong to the role endpoint")
                .isEqualTo(409);
        assertThat(conflict.body.get("code").asText()).isEqualTo("TENANT_MEMBER_EXISTS");

        assertThat(post(
                                "/api/v1/tenants/" + tenantId + "/members",
                                adminToken,
                                Map.of("userId", UUID.randomUUID().toString(), "role", "TENANT_TESTER"))
                        .status)
                .as("unknown users are a genuine miss")
                .isEqualTo(404);
        assertThat(post("/api/v1/tenants/" + tenantId + "/members", adminToken, Map.of("role", "TENANT_TESTER")).status)
                .isEqualTo(400);
        assertThat(post(
                                "/api/v1/tenants/" + UUID.randomUUID() + "/members",
                                adminToken,
                                Map.of("userId", memberId.toString(), "role", "TENANT_TESTER"))
                        .status)
                .isEqualTo(404);
        assertThat(post(
                                "/api/v1/tenants/" + tenantId + "/members",
                                adminToken,
                                Map.of("userId", "not-a-uuid", "role", "TENANT_TESTER"))
                        .status)
                .as("a malformed userId is a validation failure, not a 500")
                .isEqualTo(400);
    }

    @Test
    void suspendingAMemberDoesNotSuspendTheirOrganizationRole() throws Exception {
        String adminToken = loginOfOrgAdmin("m-overlay-admin@example.com", "Member Overlay Corp");
        UUID orgId = organizationIdByName("Member Overlay Corp");
        UUID tenantId = createTenant(adminToken, orgId, "Overlay Bank", "crm-m-overlay-1");
        // A second admin of the SAME organization who is ALSO a tenant
        // member; suspending the membership must not touch the
        // organization-level role union.
        String dualToken = loginOfSecondOrgAdmin(orgId, "m-overlay-dual@example.com");
        UUID dualId = userIdByEmail("m-overlay-dual@example.com");
        post(adminToken, tenantUri(tenantId), Map.of("userId", dualId.toString(), "role", "TENANT_TESTER"), 201);

        put(
                adminToken,
                "/api/v1/tenants/" + tenantId + "/members/" + dualId + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        assertThat(get("/api/v1/tenants/" + tenantId, dualToken).status)
                .as(
                        "the owning organization's role still grants tenant reads — suspension is the membership switch only")
                .isEqualTo(200);
        assertThat(get("/api/v1/tenants/" + tenantId + "/members", dualToken).status)
                .as("the org overlay carries tenant:members:read regardless of membership state")
                .isEqualTo(200);
    }

    @Test
    void memberAssignmentHonorsIdempotencyKeyAndDeactivatedTenants() throws Exception {
        String adminToken = loginOfOrgAdmin("m-deact-admin@example.com", "Member Deact Corp");
        UUID orgId = organizationIdByName("Member Deact Corp");
        UUID tenantId = createTenant(adminToken, orgId, "Deact Members", "crm-m-deact-1");
        UUID memberId = userIdByEmail(insertOrglessUser("m-deact-member@example.com"));
        String key = UUID.randomUUID().toString();

        JsonNode first = exchange(
                        "/api/v1/tenants/" + tenantId + "/members",
                        adminToken,
                        "POST",
                        Map.of("userId", memberId.toString(), "role", "TENANT_TESTER"),
                        key)
                .body;
        JsonNode replayed = exchange(
                        "/api/v1/tenants/" + tenantId + "/members",
                        adminToken,
                        "POST",
                        Map.of("userId", memberId.toString(), "role", "TENANT_ADMIN"),
                        key)
                .body;
        assertThat(replayed.get("role").asText())
                .as("the replay returns the original assignment")
                .isEqualTo("TENANT_TESTER")
                .isEqualTo(first.get("role").asText());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM tenant_members WHERE tenant_id = ?", Integer.class, tenantId))
                .isEqualTo(1);

        put(adminToken, "/api/v1/tenants/" + tenantId, Map.of("status", "DEACTIVATED"), 200);
        UUID lateMemberId = userIdByEmail(insertOrglessUser("m-late-member@example.com"));
        Recording blocked = post(
                "/api/v1/tenants/" + tenantId + "/members",
                adminToken,
                Map.of("userId", lateMemberId.toString(), "role", "TENANT_ADMIN"));
        assertThat(blocked.status)
                .as("deactivation blocks new affiliations (issue #9 rule, now on the assignment surface)")
                .isEqualTo(409);
        assertThat(blocked.body.get("code").asText()).isEqualTo("TENANT_DEACTIVATED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ?",
                        Integer.class,
                        tenantId,
                        lateMemberId))
                .isZero();
    }

    @Test
    void removalRevokesTenantContextOnNextTokenRefresh() throws Exception {
        String adminToken = loginOfOrgAdmin("m-remove-admin@example.com", "Member Remove Corp");
        UUID tenantId =
                createTenant(adminToken, organizationIdByName("Member Remove Corp"), "Remove Bank", "crm-m-remove-1");
        UUID memberId = userIdByEmail(insertOrglessUser("m-removed-member@example.com"));
        post(adminToken, tenantUri(tenantId), Map.of("userId", memberId.toString(), "role", "TENANT_ADMIN"), 201);
        String memberLogin = login("m-removed-member@example.com", PASSWORD);
        String memberToken = accessToken(memberLogin);
        String refreshToken = refreshToken(memberLogin);

        assertThat(get("/api/v1/tenants/" + tenantId, memberToken).status)
                .as("the member holds the tenant role before removal")
                .isEqualTo(200);

        Recording removal =
                exchange("/api/v1/tenants/" + tenantId + "/members/" + memberId, adminToken, "DELETE", null, null);
        assertThat(removal.status).as("DELETE member").isEqualTo(204);
        assertThat(get("/api/v1/tenants/" + tenantId, memberToken).status)
                .as("live role resolution drops the removed member immediately (concealed 404)")
                .isEqualTo(404);
        assertThat(post("/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken)).status)
                .as("removal revokes tenant context on next token refresh (AC2)")
                .isEqualTo(401);

        AuditEvent removed =
                audit.eventsOfType(AuditEventType.TENANT_MEMBER_REMOVED).getFirst();
        assertThat(removed.tenantId()).isEqualTo(tenantId);
        assertThat(removed.detail()).containsEntry("userId", memberId.toString());
    }

    @Test
    void suspensionIsTheSecondSwitchAndIsReversible() throws Exception {
        String adminToken = loginOfOrgAdmin("m-susp-admin@example.com", "Member Susp Corp");
        UUID tenantId = createTenant(adminToken, organizationIdByName("Member Susp Corp"), "Susp Bank", "crm-m-susp-1");
        UUID memberId = userIdByEmail(insertOrglessUser("m-suspended-member@example.com"));
        post(adminToken, tenantUri(tenantId), Map.of("userId", memberId.toString(), "role", "TENANT_ADMIN"), 201);
        String memberLogin = login("m-suspended-member@example.com", PASSWORD);
        String memberToken = accessToken(memberLogin);
        String refreshToken = refreshToken(memberLogin);
        assertThat(get("/api/v1/tenants/" + tenantId, memberToken).status).isEqualTo(200);

        JsonNode suspended = put(
                adminToken,
                "/api/v1/tenants/" + tenantId + "/members/" + memberId + "/status",
                Map.of("status", "SUSPENDED"),
                200);
        assertThat(suspended.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(get("/api/v1/tenants/" + tenantId, memberToken).status)
                .as("a suspended membership grants no tenant role — both switches must be on")
                .isEqualTo(404);
        assertThat(post("/api/v1/auth/refresh", null, Map.of("refreshToken", refreshToken)).status)
                .as("suspension ends sessions at the next token refresh")
                .isEqualTo(401);
        AuditEvent suspension =
                audit.eventsOfType(AuditEventType.TENANT_MEMBER_STATUS_CHANGED).getFirst();
        assertThat(suspension.detail())
                .containsEntry("status.before", "ACTIVE")
                .containsEntry("status.after", "SUSPENDED")
                .containsEntry("sessionsRevoked", "true");

        JsonNode roster = get(adminToken, "/api/v1/tenants/" + tenantId + "/members", 200);
        assertThat(roster.get("items").size())
                .as("the roster keeps the suspended member (no deletion)")
                .isEqualTo(1);
        assertThat(roster.get("items").get(0).get("status").asText()).isEqualTo("SUSPENDED");

        JsonNode reactivated = put(
                adminToken,
                "/api/v1/tenants/" + tenantId + "/members/" + memberId + "/status",
                Map.of("status", "ACTIVE"),
                200);
        assertThat(reactivated.get("status").asText()).isEqualTo("ACTIVE");
        String freshLogin = login("m-suspended-member@example.com", PASSWORD);
        assertThat(get("/api/v1/tenants/" + tenantId, accessToken(freshLogin)).status)
                .as("reactivation restores the tenant role")
                .isEqualTo(200);
        AuditEvent reactivation = audit.eventsOfType(AuditEventType.TENANT_MEMBER_STATUS_CHANGED).stream()
                .filter(event -> "ACTIVE".equals(event.detail().get("status.after")))
                .findFirst()
                .orElseThrow();
        assertThat(reactivation.detail()).containsEntry("sessionsRevoked", "false");

        assertThat(put(
                                "/api/v1/tenants/" + tenantId + "/members/" + memberId + "/status",
                                adminToken,
                                Map.of("status", "PAUSED"))
                        .status)
                .isEqualTo(400);
        assertThat(put(
                                "/api/v1/tenants/" + tenantId + "/members/" + UUID.randomUUID() + "/status",
                                adminToken,
                                Map.of("status", "SUSPENDED"))
                        .status)
                .isEqualTo(404);
    }

    @Test
    void onlyTenantAdminsAndOrgAdminsManageMembers() throws Exception {
        String adminToken = loginOfOrgAdmin("m-roles-admin@example.com", "Member Roles Corp");
        UUID tenantId =
                createTenant(adminToken, organizationIdByName("Member Roles Corp"), "Roles Bank", "crm-m-roles-1");
        UUID tenantAdminId = userIdByEmail(insertOrglessUser("m-roles-tadmin@example.com"));
        post(adminToken, tenantUri(tenantId), Map.of("userId", tenantAdminId.toString(), "role", "TENANT_ADMIN"), 201);
        String tenantAdminToken = accessToken(login("m-roles-tadmin@example.com", PASSWORD));
        UUID testerId = userIdByEmail(insertOrglessUser("m-roles-tester@example.com"));
        post(adminToken, tenantUri(tenantId), Map.of("userId", testerId.toString(), "role", "TENANT_TESTER"), 201);
        String testerToken = accessToken(login("m-roles-tester@example.com", PASSWORD));

        JsonNode selfManaged = post(
                tenantAdminToken,
                tenantUri(tenantId),
                Map.of(
                        "userId",
                        userIdByEmail(insertOrglessUser("m-roles-newcomer@example.com"))
                                .toString(),
                        "role",
                        "TENANT_TESTER"),
                201);
        assertThat(selfManaged.get("role").asText()).isEqualTo("TENANT_TESTER");
        assertThat(put(
                                "/api/v1/tenants/" + tenantId + "/members/" + testerId + "/status",
                                tenantAdminToken,
                                Map.of("status", "SUSPENDED"))
                        .status)
                .as("tenant administrators manage members (AC4)")
                .isEqualTo(200);

        assertThat(post(
                                "/api/v1/tenants/" + tenantId + "/members",
                                testerToken,
                                Map.of(
                                        "userId",
                                        userIdByEmail(insertOrglessUser("m-roles-outsider@example.com"))
                                                .toString(),
                                        "role",
                                        "TENANT_TESTER"))
                        .status)
                .as("tenant testers cannot manage members — concealed denial (AC4)")
                .isEqualTo(404);
        assertThat(put(
                                "/api/v1/tenants/" + tenantId + "/members/" + tenantAdminId + "/status",
                                testerToken,
                                Map.of("status", "SUSPENDED"))
                        .status)
                .isEqualTo(404);
        assertThat(get("/api/v1/tenants/" + tenantId + "/members", testerToken).status)
                .as("the member roster is administrators-only; the tester read surface is applications")
                .isEqualTo(404);
        Recording genuineMiss = get("/api/v1/tenants/" + UUID.randomUUID() + "/members", testerToken);
        Recording concealed = get("/api/v1/tenants/" + tenantId + "/members", testerToken);
        assertThat(concealed.body.get("code").asText())
                .as("denial and genuine miss share the envelope (PRD 12)")
                .isEqualTo(genuineMiss.body.get("code").asText());
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static String tenantUri(UUID tenantId) {
        return "/api/v1/tenants/" + tenantId + "/members";
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        JsonNode created = post(
                token,
                "/api/v1/tenants",
                Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID organizationIdByName(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, name);
    }

    private UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    /** Registers an orgless user through a plain invitation; returns the email. */
    private String insertOrglessUser(String email) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(devToken, "/api/v1/auth/invitations", Map.of("email", email), 201);
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
                        "Member Test User"))
                .retrieve()
                .body(String.class);
        return email;
    }

    /** Registers a second ORG_ADMIN of an EXISTING organization; returns their token. */
    private String loginOfSecondOrgAdmin(UUID orgId, String email) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", "ORG_ADMIN"),
                201);
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
                        "Dual Admin"))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    /** Creates an organization as dev admin and registers its named ORG_ADMIN. */
    private String loginOfOrgAdmin(String email, String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", "ORG_ADMIN"),
                201);
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
                        "Org Admin"))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    private String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    private String accessToken(String tokensJson) {
        try {
            return mapper.readTree(tokensJson).get("accessToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String refreshToken(String tokensJson) {
        try {
            return mapper.readTree(tokensJson).get("refreshToken").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, bearer, "POST", body, null);
        assertThat(response.status).as("POST %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, bearer, "PUT", body, null);
        assertThat(response.status).as("PUT %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording get(String uri, String bearer) {
        return exchange(uri, bearer, "GET", null, null);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording response = exchange(uri, bearer, "GET", null, null);
        assertThat(response.status).as("GET %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording post(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "POST", body, null);
    }

    private Recording put(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "PUT", body, null);
    }

    private Recording exchange(
            String uri, String bearer, String method, Map<String, Object> body, String idempotencyKey) {
        RestClient.RequestBodySpec spec =
                client.method(HttpMethod.valueOf(method)).uri(uri);
        if (bearer != null) {
            spec.headers(headers -> headers.setBearerAuth(bearer));
        }
        if (idempotencyKey != null) {
            spec.headers(headers -> headers.set("Idempotency-Key", idempotencyKey));
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
        });
    }

    private record Recording(int status, String contentType, JsonNode body) {}
}
