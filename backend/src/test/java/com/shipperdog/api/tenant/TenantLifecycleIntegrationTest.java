package com.shipperdog.api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
 * End-to-end acceptance flow for issue #9 (S2-01) against a real
 * PostgreSQL and HTTP stack: tenant creation under an organization with
 * within-org identifier uniqueness and a globally-unique external id,
 * partial update with the reversible deactivation lifecycle (blocks new
 * memberships, preserves data), organization-scoped filtered listing
 * with cursor pagination, the role split (org admins manage, tenant
 * admins read), audit before/after on every transition, Idempotency-Key
 * on creation, and concealed-404 denial shapes.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class TenantLifecycleIntegrationTest {

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
    void tenantCreationEnforcesIdentifierUniquenessAndAudits() throws Exception {
        String adminToken = loginOfOrgAdmin("t-acme-admin@example.com", "Tenant Acme Corp");
        UUID orgId = organizationIdByName("Tenant Acme Corp");

        JsonNode created = post(
                adminToken,
                "/api/v1/tenants",
                Map.of("organizationId", orgId.toString(), "name", "Acme Bank", "externalId", "crm-acme-1"),
                201);
        UUID tenantId = UUID.fromString(created.get("id").asText());
        assertThat(created.get("status").asText())
                .as("tenants are born ACTIVE (AC1)")
                .isEqualTo("ACTIVE");
        assertThat(created.get("externalId").asText())
                .as("external_id is carried (AC2)")
                .isEqualTo("crm-acme-1");
        assertThat(created.get("organizationId").asText()).isEqualTo(orgId.toString());

        Recording duplicateName = post(
                "/api/v1/tenants",
                adminToken,
                Map.of("organizationId", orgId.toString(), "name", "Acme Bank", "externalId", "crm-other"));
        assertThat(duplicateName.status)
                .as("duplicate identifier within the org is 409 (AC1)")
                .isEqualTo(409);
        assertThat(duplicateName.body.get("code").asText()).isEqualTo("TENANT_NAME_TAKEN");
        assertThat(duplicateName.contentType).startsWith("application/problem+json");

        Recording duplicateExternalId = post(
                "/api/v1/tenants",
                adminToken,
                Map.of("organizationId", orgId.toString(), "name", "Acme Cards", "externalId", "crm-acme-1"));
        assertThat(duplicateExternalId.status)
                .as("external_id maps to exactly one tenant globally")
                .isEqualTo(409);
        assertThat(duplicateExternalId.body.get("code").asText()).isEqualTo("TENANT_EXTERNAL_ID_TAKEN");

        AuditEvent creation = audit.eventsOfType(AuditEventType.TENANT_CREATED).getFirst();
        assertThat(creation.orgId()).isEqualTo(orgId);
        assertThat(creation.tenantId()).isEqualTo(tenantId);
        assertThat(creation.detail())
                .containsEntry("name", "Acme Bank")
                .containsEntry("externalId", "crm-acme-1")
                .containsEntry("status", "ACTIVE");
    }

    @Test
    void sameNameInDifferentOrganizationsIsAllowed() throws Exception {
        String firstAdmin = loginOfOrgAdmin("t-gamma-admin@example.com", "Tenant Gamma Corp");
        String secondAdmin = loginOfOrgAdmin("t-delta-admin@example.com", "Tenant Delta Corp");

        post(
                firstAdmin,
                "/api/v1/tenants",
                Map.of(
                        "organizationId",
                        organizationIdByName("Tenant Gamma Corp").toString(),
                        "name",
                        "Shared Name",
                        "externalId",
                        "crm-gamma-1"),
                201);
        assertThat(post(
                                "/api/v1/tenants",
                                secondAdmin,
                                Map.of(
                                        "organizationId",
                                        organizationIdByName("Tenant Delta Corp")
                                                .toString(),
                                        "name",
                                        "Shared Name",
                                        "externalId",
                                        "crm-delta-1"))
                        .status)
                .as("uniqueness is per organization, not global")
                .isEqualTo(201);
    }

    @Test
    void tenantCreationIsPermissionScoped() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        String adminToken = loginOfOrgAdmin("t-eta-admin@example.com", "Tenant Eta Corp");
        UUID ownOrg = organizationIdByName("Tenant Eta Corp");
        UUID otherOrg = createOrganization(devToken, "Tenant Zeta Corp");

        assertThat(post(
                                "/api/v1/tenants",
                                registerOrglessUser("t-outsider@example.com"),
                                Map.of("organizationId", ownOrg.toString(), "name", "Nope", "externalId", "crm-nope-1"))
                        .status)
                .as("outsiders get the concealed 404, never a 403 that reveals the org")
                .isEqualTo(404);
        assertThat(post(
                                "/api/v1/tenants",
                                adminToken,
                                Map.of(
                                        "organizationId",
                                        otherOrg.toString(),
                                        "name",
                                        "Cross Org",
                                        "externalId",
                                        "crm-cross-1"))
                        .status)
                .as("an org admin cannot create tenants in another organization")
                .isEqualTo(404);
        assertThat(post("/api/v1/tenants", adminToken, Map.of("name", "No Org", "externalId", "crm-noorg-1")).status)
                .as("a body without organizationId is denied before validation (opaque 403)")
                .isEqualTo(403);
        assertThat(post(
                                "/api/v1/tenants",
                                adminToken,
                                Map.of(
                                        "organizationId",
                                        UUID.randomUUID().toString(),
                                        "name",
                                        "Ghost",
                                        "externalId",
                                        "crm-ghost-1"))
                        .status)
                .as("org admins get the concealed 404 for unknown organizations too")
                .isEqualTo(404);
        assertThat(post(
                                "/api/v1/tenants",
                                devToken,
                                Map.of(
                                        "organizationId",
                                        UUID.randomUUID().toString(),
                                        "name",
                                        "Ghost",
                                        "externalId",
                                        "crm-ghost-2"))
                        .status)
                .as("platform admins reach the service and get the genuine 404")
                .isEqualTo(404);
        assertThat(post("/api/v1/tenants", null, Map.of("name", "Anon", "externalId", "crm-anon-1")).status)
                .isEqualTo(401);
        assertThat(post(
                                "/api/v1/tenants",
                                devToken,
                                Map.of(
                                        "organizationId",
                                        otherOrg.toString(),
                                        "name",
                                        "Platform Made",
                                        "externalId",
                                        "crm-platform-1"))
                        .status)
                .as("the platform wildcard passes org-scoped checks")
                .isEqualTo(201);
    }

    @Test
    void tenantCreationValidatesFields() throws Exception {
        String adminToken = loginOfOrgAdmin("t-validation-admin@example.com", "Tenant Validation Corp");
        UUID orgId = organizationIdByName("Tenant Validation Corp");

        assertThat(post("/api/v1/tenants", adminToken, Map.of("organizationId", orgId.toString(), "externalId", "x-1"))
                        .status)
                .as("name is required")
                .isEqualTo(400);
        assertThat(post(
                                "/api/v1/tenants",
                                adminToken,
                                Map.of(
                                        "organizationId",
                                        orgId.toString(),
                                        "name",
                                        "a".repeat(201),
                                        "externalId",
                                        "x-2"))
                        .status)
                .as("name is capped at 200 characters")
                .isEqualTo(400);
        assertThat(post("/api/v1/tenants", adminToken, Map.of("organizationId", orgId.toString(), "name", "No Ext"))
                        .status)
                .as("externalId is required (NOT NULL column, V1)")
                .isEqualTo(400);
        assertThat(post(
                                "/api/v1/tenants",
                                adminToken,
                                Map.of("organizationId", orgId.toString(), "name", "Blank Ext", "externalId", "  "))
                        .status)
                .isEqualTo(400);
    }

    @Test
    void deactivationIsReversibleBlocksNewMembersAndPreservesData() throws Exception {
        String adminToken = loginOfOrgAdmin("t-deact-admin@example.com", "Tenant Deact Corp");
        UUID orgId = organizationIdByName("Tenant Deact Corp");
        UUID tenantId = createTenant(adminToken, orgId, "Deact Bank", "crm-deact-1");

        UUID earlyMemberId = userIdByEmail(insertOrglessUser("t-early-member@example.com"));
        put(
                adminToken,
                "/api/v1/tenants/" + tenantId + "/members/" + earlyMemberId + "/role",
                Map.of("role", "TENANT_ADMIN"),
                200);

        put(adminToken, "/api/v1/tenants/" + tenantId, Map.of("status", "DEACTIVATED"), 200);
        assertThat(audit.eventsOfType(AuditEventType.TENANT_UPDATED).getFirst().detail())
                .containsEntry("status.before", "ACTIVE")
                .containsEntry("status.after", "DEACTIVATED");

        UUID lateMemberId = userIdByEmail(insertOrglessUser("t-late-member@example.com"));
        Recording blocked = put(
                "/api/v1/tenants/" + tenantId + "/members/" + lateMemberId + "/role",
                adminToken,
                Map.of("role", "TENANT_TESTER"));
        assertThat(blocked.status)
                .as("deactivation blocks new memberships (AC3, invitation analog)")
                .isEqualTo(409);
        assertThat(blocked.body.get("code").asText()).isEqualTo("TENANT_DEACTIVATED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ?",
                        Integer.class,
                        tenantId,
                        lateMemberId))
                .as("the blocked affiliation wrote nothing")
                .isZero();

        put(
                adminToken,
                "/api/v1/tenants/" + tenantId + "/members/" + earlyMemberId + "/role",
                Map.of("role", "TENANT_TESTER"),
                200);
        assertThat(statusOf(client.get()
                        .uri("/api/v1/tenants/" + tenantId)
                        .header("Authorization", "Bearer " + adminToken)))
                .as("a deactivated tenant stays readable and its data intact (AC3)")
                .isEqualTo(200);
        JsonNode afterDeactivation = get(adminToken, "/api/v1/tenants/" + tenantId, 200);
        assertThat(afterDeactivation.get("name").asText()).isEqualTo("Deact Bank");
        assertThat(afterDeactivation.get("status").asText()).isEqualTo("DEACTIVATED");

        JsonNode reactivated = put(adminToken, "/api/v1/tenants/" + tenantId, Map.of("status", "ACTIVE"), 200);
        assertThat(reactivated.get("status").asText())
                .as("deactivation is reversible")
                .isEqualTo("ACTIVE");
        put(
                adminToken,
                "/api/v1/tenants/" + tenantId + "/members/" + lateMemberId + "/role",
                Map.of("role", "TENANT_TESTER"),
                200);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM tenant_members WHERE tenant_id = ? AND user_id = ?",
                        Integer.class,
                        tenantId,
                        lateMemberId))
                .as("reactivation lifts the affiliation block")
                .isEqualTo(1);
    }

    @Test
    void tenantUpdateIsPartialAndAuditedBeforeAfter() throws Exception {
        String adminToken = loginOfOrgAdmin("t-update-admin@example.com", "Tenant Update Corp");
        UUID orgId = organizationIdByName("Tenant Update Corp");
        UUID tenantId = createTenant(adminToken, orgId, "Update Bank", "crm-update-1");
        createTenant(adminToken, orgId, "Sibling Bank", "crm-update-2");

        JsonNode noop = put(adminToken, "/api/v1/tenants/" + tenantId, Map.of(), 200);
        assertThat(noop.get("name").asText())
                .as("null fields are left unchanged")
                .isEqualTo("Update Bank");

        JsonNode updated = put(
                adminToken,
                "/api/v1/tenants/" + tenantId,
                Map.of("name", "Update Bank AG", "externalId", "crm-update-3"),
                200);
        assertThat(updated.get("name").asText()).isEqualTo("Update Bank AG");
        assertThat(updated.get("externalId").asText()).isEqualTo("crm-update-3");
        // The no-op update above also publishes TENANT_UPDATED (empty
        // detail); the rename is the event that actually changed fields.
        AuditEvent rename = audit.eventsOfType(AuditEventType.TENANT_UPDATED).stream()
                .filter(event -> event.detail().containsKey("name.after"))
                .findFirst()
                .orElseThrow();
        assertThat(rename.detail())
                .containsEntry("name.before", "Update Bank")
                .containsEntry("name.after", "Update Bank AG")
                .containsEntry("externalId.before", "crm-update-1")
                .containsEntry("externalId.after", "crm-update-3");

        assertThat(put("/api/v1/tenants/" + tenantId, adminToken, Map.of("name", "Sibling Bank")).status)
                .as("renaming onto a sibling's name conflicts")
                .isEqualTo(409);
        assertThat(put("/api/v1/tenants/" + tenantId, adminToken, Map.of("externalId", "crm-update-2")).status)
                .as("reusing a sibling's externalId conflicts")
                .isEqualTo(409);
        assertThat(put("/api/v1/tenants/" + tenantId, adminToken, Map.of("name", "Update Bank AG")).status)
                .as("a no-op rename onto the tenant's own name is fine")
                .isEqualTo(200);
        assertThat(put("/api/v1/tenants/" + tenantId, adminToken, Map.of("status", "DISABLED")).status)
                .as("DISABLED is reserved-unused")
                .isEqualTo(400);
        assertThat(put("/api/v1/tenants/" + UUID.randomUUID(), adminToken, Map.of("name", "Ghost")).status)
                .isEqualTo(404);
    }

    @Test
    void tenantListIsOrganizationScopedAndFilterable() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        String adminToken = loginOfOrgAdmin("t-list-admin@example.com", "Tenant List Corp");
        UUID orgId = organizationIdByName("Tenant List Corp");
        UUID alpha = createTenant(adminToken, orgId, "List Alpha", "crm-list-1");
        createTenant(adminToken, orgId, "List Beta", "crm-list-2");
        createTenant(adminToken, orgId, "List Gamma", "crm-list-3");
        put(adminToken, "/api/v1/tenants/" + alpha, Map.of("status", "DEACTIVATED"), 200);

        JsonNode all = get(adminToken, "/api/v1/tenants?organizationId=" + orgId + "&limit=10", 200);
        assertThat(all.get("items").size())
                .as("the org sees exactly its three tenants")
                .isEqualTo(3);
        for (JsonNode item : all.get("items")) {
            assertThat(UUID.fromString(item.get("organizationId").asText()))
                    .as("listing never leaks other organizations' tenants (AC5, INV-001 groundwork)")
                    .isEqualTo(orgId);
        }

        JsonNode deactivated = get(adminToken, "/api/v1/tenants?organizationId=" + orgId + "&status=DEACTIVATED", 200);
        assertThat(deactivated.get("items").size()).isEqualTo(1);
        assertThat(deactivated.get("items").get(0).get("name").asText()).isEqualTo("List Alpha");

        JsonNode active = get(adminToken, "/api/v1/tenants?organizationId=" + orgId + "&status=ACTIVE", 200);
        assertThat(active.get("items").size()).isEqualTo(2);

        assertThat(get("/api/v1/tenants?organizationId=" + orgId, registerOrglessUser("t-list-outside@example.com"))
                        .status)
                .as("listing is org-admin only (AC5); denials conceal as 404")
                .isEqualTo(404);
        assertThat(get("/api/v1/tenants", adminToken).status)
                .as("organizationId is a required parameter")
                .isEqualTo(400);
        assertThat(get("/api/v1/tenants?organizationId=" + orgId + "&status=DISABLED", adminToken).status)
                .isEqualTo(400);
        assertThat(get("/api/v1/tenants?organizationId=" + orgId, devToken).status)
                .as("platform administrators pass org-scoped listing")
                .isEqualTo(200);
    }

    @Test
    void tenantListPagesWithTheCursorContract() throws Exception {
        String adminToken = loginOfOrgAdmin("t-page-admin@example.com", "Tenant Page Corp");
        UUID orgId = organizationIdByName("Tenant Page Corp");
        Set<UUID> created = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) {
            created.add(createTenant(adminToken, orgId, "Page Tenant " + i, "crm-page-" + i));
        }

        Set<UUID> seen = new HashSet<>();
        String cursor = "";
        int pages = 0;
        while (true) {
            JsonNode page = get(
                    adminToken,
                    "/api/v1/tenants?organizationId="
                            + orgId
                            + "&limit=2"
                            + (cursor.isEmpty() ? "" : "&cursor=" + cursor),
                    200);
            assertThat(page.get("items").size()).as("limit is honored").isLessThanOrEqualTo(2);
            page.get("items")
                    .forEach(item -> seen.add(UUID.fromString(item.get("id").asText())));
            pages++;
            if (!page.hasNonNull("next_cursor")) {
                break;
            }
            cursor = page.get("next_cursor").asText();
        }
        assertThat(seen).as("the walk covers every tenant, no gaps").containsAll(created);
        assertThat(seen.size()).isEqualTo(5);
        assertThat(pages).as("five tenants at limit=2 span three pages").isEqualTo(3);

        assertThat(get("/api/v1/tenants?organizationId=" + orgId + "&limit=0", adminToken).status)
                .as("bounds are rejected, not clamped")
                .isEqualTo(400);
        assertThat(get("/api/v1/tenants?organizationId=" + orgId + "&limit=101", adminToken).status)
                .isEqualTo(400);
    }

    @Test
    void tenantAdminsReadButCannotManageTheLifecycle() throws Exception {
        String adminToken = loginOfOrgAdmin("t-role-admin@example.com", "Tenant Role Corp");
        UUID orgId = organizationIdByName("Tenant Role Corp");
        UUID tenantId = createTenant(adminToken, orgId, "Role Bank", "crm-role-1");

        UUID tenantAdminId = userIdByEmail(insertOrglessUser("t-tenant-admin@example.com"));
        put(
                adminToken,
                "/api/v1/tenants/" + tenantId + "/members/" + tenantAdminId + "/role",
                Map.of("role", "TENANT_ADMIN"),
                200);
        String tenantAdminToken = accessToken(login("t-tenant-admin@example.com", PASSWORD));

        assertThat(get("/api/v1/tenants/" + tenantId, tenantAdminToken).status)
                .as("tenant administrators hold tenant:tenants:read")
                .isEqualTo(200);
        Recording concealedUpdate = put("/api/v1/tenants/" + tenantId, tenantAdminToken, Map.of("name", "Hijack"));
        assertThat(concealedUpdate.status)
                .as("tenant administrators cannot update (concealed 404)")
                .isEqualTo(404);
        assertThat(get("/api/v1/tenants?organizationId=" + orgId, tenantAdminToken).status)
                .as("organization-scoped listing needs an org role")
                .isEqualTo(404);

        Recording genuineMiss = get("/api/v1/tenants/" + UUID.randomUUID(), tenantAdminToken);
        assertThat(concealedUpdate.body.get("code").asText())
                .as("denial and genuine miss share the TENANT_NOT_FOUND shape")
                .isEqualTo(genuineMiss.body.get("code").asText())
                .isEqualTo("TENANT_NOT_FOUND");
    }

    @Test
    void tenantCreationHonorsIdempotencyKey() throws Exception {
        String adminToken = loginOfOrgAdmin("t-idem-admin@example.com", "Tenant Idem Corp");
        UUID orgId = organizationIdByName("Tenant Idem Corp");
        String key = UUID.randomUUID().toString();

        JsonNode first = postWithIdempotencyKey(
                adminToken,
                key,
                Map.of("organizationId", orgId.toString(), "name", "Idem Bank", "externalId", "crm-idem-1"),
                201);
        JsonNode replay = postWithIdempotencyKey(
                adminToken,
                key,
                Map.of("organizationId", orgId.toString(), "name", "Idem Bank Copy", "externalId", "crm-idem-2"),
                201);
        assertThat(replay.get("id").asText())
                .as("the replay returns the original tenant")
                .isEqualTo(first.get("id").asText());
        assertThat(replay.get("name").asText()).isEqualTo("Idem Bank");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE external_id = 'crm-idem-1'", Integer.class))
                .as("only one tenant exists despite the retried POST")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        JsonNode created = post(
                token,
                "/api/v1/tenants",
                Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID createOrganization(String token, String name) {
        JsonNode created = post(token, "/api/v1/organizations", Map.of("name", name), 201);
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
                        "Tenant Test User"))
                .retrieve()
                .body(String.class);
        return email;
    }

    /** Registers an orgless user through a plain invitation; returns an access token. */
    private String registerOrglessUser(String email) {
        insertOrglessUser(email);
        return accessToken(login(email, PASSWORD));
    }

    /** Creates an organization as dev admin and registers its named ORG_ADMIN. */
    private String loginOfOrgAdmin(String email, String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        UUID orgId = createOrganization(devToken, orgName);
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

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Recording response = exchange(uri, bearer, "GET", null, null);
        assertThat(response.status).as("GET %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording get(String uri, String bearer) {
        return exchange(uri, bearer, "GET", null, null);
    }

    private Recording post(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "POST", body, null);
    }

    private Recording put(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "PUT", body, null);
    }

    private JsonNode postWithIdempotencyKey(String bearer, String key, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange("/api/v1/tenants", bearer, "POST", body, key);
        assertThat(response.status)
                .as("POST /api/v1/tenants with Idempotency-Key")
                .isEqualTo(expectedStatus);
        return response.body;
    }

    private int statusOf(org.springframework.web.client.RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((req, res) -> res.getStatusCode().value());
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
