package com.shipperdog.api.version;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * End-to-end acceptance flow for the version layer (issue #17, S4-01,
 * FR-008, PRD 8.4/8.6): creation with a validated semver-style name and
 * per-application duplicate 409s (AC1), the per-platform artifact slots
 * in listing and detail (AC2), visibility strictly through the
 * tenant/authorization chain with the concealed 404 on every scoped
 * denial (AC3, S2-02/S3-03), the release-history section in the empty
 * M1 state (AC4), and audited mutations (AC5) — plus the reserved
 * APPLICATION_ARCHIVED 409 landing from issue #13's registry and the
 * platform conventions (idempotency, pagination, authentication).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class VersionManagementIntegrationTest {

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

    // -----------------------------------------------------------------------
    // AC1: creation with a validated semver-style name
    // -----------------------------------------------------------------------

    @Test
    void creationMakesADraftVersionWithArtifactSlotsAndAudit() throws Exception {
        UUID orgId = createOrg("Ver Create Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-create-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "ver-create-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver Create Bank", "crm-ver-create-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Banking", "com.vercreate.banking");

        JsonNode created = post(ownerToken, versionsUri(appId), Map.of("versionName", "7.12.0"), 201);
        assertThat(created.get("id").asText()).isNotBlank();
        assertThat(UUID.fromString(created.get("applicationId").asText())).isEqualTo(appId);
        assertThat(created.get("versionName").asText()).isEqualTo("7.12.0");
        assertThat(created.get("status").asText())
                .as("a version is born DRAFT (lifecycle is S4-03)")
                .isEqualTo("DRAFT");
        assertThat(created.get("artifactsByPlatform").toString())
                .as("the per-platform slots exist, empty until E5 (AC2)")
                .contains("IOS", "ANDROID");
        assertThat(created.get("artifactsByPlatform").get("IOS").isEmpty()).isTrue();
        assertThat(created.get("artifactsByPlatform").get("ANDROID").isEmpty()).isTrue();
        assertThat(created.get("createdAt").asText()).isNotBlank();

        // The owning-dimension snapshot and the normalized sort key (the
        // issue's technical note) are stored alongside the human name.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT organization_id, tenant_id, sort_key, status FROM versions WHERE id = ?",
                UUID.fromString(created.get("id").asText()));
        assertThat(row.get("organization_id")).isEqualTo(orgId);
        assertThat(row.get("tenant_id")).isEqualTo(tenantId);
        assertThat(row.get("sort_key")).isEqualTo("00007.00012.00000");
        assertThat(row.get("status")).isEqualTo("DRAFT");

        List<AuditEvent> events = audit.eventsOfType(AuditEventType.VERSION_CREATED);
        assertThat(events).as("creation is audited (AC5)").hasSize(1);
        assertThat(events.get(0).applicationId()).isEqualTo(appId);
        assertThat(events.get(0).tenantId()).isEqualTo(tenantId);
        assertThat(events.get(0).detail()).containsEntry("versionName", "7.12.0");
        assertThat(events.get(0).detail()).containsEntry("status", "DRAFT");
    }

    @Test
    void invalidNamesAreRejectedAndNothingIsPersisted() throws Exception {
        UUID orgId = createOrg("Ver Invalid Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-invalid-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "ver-invalid-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver Invalid Bank", "crm-ver-invalid-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Invalid Names", "com.verinvalid.app");

        List<String> bad = List.of(
                "7.12",
                "7",
                "7.12.0.1",
                "v7.12.0",
                "07.12.0",
                "7.12.00",
                "123456.0.0",
                "7.12.0-rc.1",
                "7.12.0+build.5",
                "7.12.x",
                "latest",
                "");
        for (String name : bad) {
            post(ownerToken, versionsUri(appId), Map.of("versionName", name), 400);
        }
        post(ownerToken, versionsUri(appId), Map.of(), 400);
        post(ownerToken, versionsUri(appId), Map.of("versionName", "  "), 400);

        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM versions WHERE application_id = ?", Integer.class, appId);
        assertThat(count).as("no invalid creation persisted anything").isEqualTo(0);
    }

    @Test
    void boundaryNamesAreAcceptedAndWhitespaceIsTrimmed() throws Exception {
        UUID orgId = createOrg("Ver Boundary Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-bound-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "ver-bound-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver Boundary Bank", "crm-ver-bound-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Boundary", "com.verbound.app");

        // The full segment range: zero and the five-digit cap both fit the
        // vocabulary and the sort key's fixed width.
        JsonNode zero = post(ownerToken, versionsUri(appId), Map.of("versionName", "0.0.0"), 201);
        assertThat(zero.get("versionName").asText()).isEqualTo("0.0.0");
        JsonNode cap = post(ownerToken, versionsUri(appId), Map.of("versionName", "99999.99999.99999"), 201);
        assertThat(cap.get("versionName").asText()).isEqualTo("99999.99999.99999");
        assertThat(jdbc.queryForObject(
                        "SELECT sort_key FROM versions WHERE version_name = '99999.99999.99999'", String.class))
                .isEqualTo("99999.99999.99999");

        // Surrounding whitespace trims to the canonical name — which then
        // duplicates the trimmed creation already in place.
        JsonNode padded = post(ownerToken, versionsUri(appId), Map.of("versionName", " 0.0.0 "), 409);
        assertThat(padded.get("code").asText()).isEqualTo("VERSION_NAME_TAKEN");
    }

    @Test
    void duplicateNamesPerApplicationConflictButOtherApplicationsAreIndependent() throws Exception {
        UUID orgId = createOrg("Ver Dup Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-dup-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "ver-dup-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver Dup Bank", "crm-ver-dup-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Dup Source", "com.verdup.one");
        UUID otherApp = createTenantApp(ownerToken, tenantId, "Dup Other", "com.verdup.two");

        post(ownerToken, versionsUri(appId), Map.of("versionName", "1.0.0"), 201);
        JsonNode duplicate = exchange(versionsUri(appId), ownerToken, "POST", Map.of("versionName", "1.0.0"))
                .expect(409);
        assertThat(duplicate.get("code").asText()).isEqualTo("VERSION_NAME_TAKEN");

        // Uniqueness is per application (uq_versions_application_version),
        // not per tenant or organization.
        assertThat(post(ownerToken, versionsUri(otherApp), Map.of("versionName", "1.0.0"), 201)
                        .get("versionName")
                        .asText())
                .isEqualTo("1.0.0");
    }

    @Test
    void creationUnderAnArchivedApplicationIsTheReserved409AndReverses() throws Exception {
        UUID orgId = createOrg("Ver Arch Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-arch-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "ver-arch-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver Arch Bank", "crm-ver-arch-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Arch Guard", "com.verarch.app");
        UUID existingVersion = UUID.fromString(post(ownerToken, versionsUri(appId), Map.of("versionName", "0.9.0"), 201)
                .get("id")
                .asText());

        patch(ownerToken, "/api/v1/applications/" + appId, Map.of("status", "ARCHIVED"), 200);
        JsonNode blocked = exchange(versionsUri(appId), ownerToken, "POST", Map.of("versionName", "1.0.0"))
                .expect(409);
        assertThat(blocked.get("code").asText())
                .as("issue #13 reserved this 409 for exactly this surface")
                .isEqualTo("APPLICATION_ARCHIVED");

        // Existing versions stay readable on the archived application
        // (issue #13 AC4).
        assertThat(get(ownerToken, versionsUri(appId), 200).get("items").size()).isEqualTo(1);
        assertThat(get(ownerToken, versionsUri(appId) + "/" + existingVersion, 200)
                        .get("versionName")
                        .asText())
                .isEqualTo("0.9.0");

        // Unarchiving resumes creation (the transition is reversible).
        patch(ownerToken, "/api/v1/applications/" + appId, Map.of("status", "ACTIVE"), 200);
        post(ownerToken, versionsUri(appId), Map.of("versionName", "1.0.0"), 201);
    }

    // -----------------------------------------------------------------------
    // AC2: the listing and its per-platform slots, semver-ordered
    // -----------------------------------------------------------------------

    @Test
    void listingOrdersByNormalizedSemverKeyNewestFirstAndPages() throws Exception {
        UUID orgId = createOrg("Ver List Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-list-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "ver-list-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver List Bank", "crm-ver-list-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Sort Me", "com.verlist.app");

        for (String name : List.of("7.9.0", "10.0.0", "7.12.0", "9.9.9")) {
            post(ownerToken, versionsUri(appId), Map.of("versionName", name), 201);
        }

        JsonNode firstPage = get(ownerToken, versionsUri(appId) + "?limit=2", 200);
        assertThat(firstPage.get("items").size()).isEqualTo(2);
        // Numeric order: 10.0.0 after 9.9.9 — lexicographic string order
        // would be wrong; the normalized sort key is the proof.
        assertThat(firstPage.get("items").get(0).get("versionName").asText()).isEqualTo("10.0.0");
        assertThat(firstPage.get("items").get(1).get("versionName").asText()).isEqualTo("9.9.9");
        assertThat(firstPage
                        .get("items")
                        .get(0)
                        .get("artifactsByPlatform")
                        .get("IOS")
                        .isEmpty())
                .as("every item carries the empty per-platform slots (AC2)")
                .isTrue();

        String cursor = firstPage.get("next_cursor").asText();
        JsonNode secondPage = get(ownerToken, versionsUri(appId) + "?limit=2&cursor=" + cursor, 200);
        assertThat(secondPage.get("items").size()).isEqualTo(2);
        assertThat(secondPage.get("items").get(0).get("versionName").asText()).isEqualTo("7.12.0");
        assertThat(secondPage.get("items").get(1).get("versionName").asText()).isEqualTo("7.9.0");
        assertThat(secondPage.has("next_cursor")).as("the walk ended").isFalse();

        assertThat(get(ownerToken, versionsUri(appId) + "?limit=0", 400)
                        .get("code")
                        .asText())
                .isEqualTo("INVALID_REQUEST");
    }

    // -----------------------------------------------------------------------
    // AC2 + AC4: the detail shape — slots and the empty release history
    // -----------------------------------------------------------------------

    @Test
    void detailCarriesArtifactSlotsAndAnEmptyReleaseHistorySection() throws Exception {
        UUID orgId = createOrg("Ver Detail Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-detail-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "ver-detail-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver Detail Bank", "crm-ver-detail-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Detailed", "com.verdetail.app");

        UUID versionId = UUID.fromString(post(ownerToken, versionsUri(appId), Map.of("versionName", "7.12.0"), 201)
                .get("id")
                .asText());

        JsonNode detail = get(ownerToken, versionsUri(appId) + "/" + versionId, 200);
        assertThat(detail.get("artifactsByPlatform").get("IOS").isEmpty()).isTrue();
        assertThat(detail.get("releaseHistory").isArray()).isTrue();
        assertThat(detail.get("releaseHistory").size())
                .as("a version can exist without being released (PRD 8.6); releases are M2")
                .isZero();

        // A version id under the wrong application is the standard miss.
        UUID otherApp = createTenantApp(ownerToken, tenantId, "Detailed Neighbor", "com.verdetail.next");
        JsonNode misplaced = get(ownerToken, versionsUri(otherApp) + "/" + versionId, 404);
        assertThat(misplaced.get("code").asText()).isEqualTo("VERSION_NOT_FOUND");

        // So is an unknown version id.
        assertThat(get(ownerToken, versionsUri(appId) + "/" + UUID.randomUUID(), 404)
                        .get("code")
                        .asText())
                .isEqualTo("VERSION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // AC3: visibility only through the tenant/authorization chain
    // -----------------------------------------------------------------------

    @Test
    void readsResolveThroughOwnershipRolesAndConcealOtherwise() throws Exception {
        UUID orgId = createOrg("Ver Vis Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-vis-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "ver-vis-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver Vis Bank", "crm-ver-vis-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Visible", "com.vervis.app");
        UUID versionId = UUID.fromString(post(ownerToken, versionsUri(appId), Map.of("versionName", "1.0.0"), 201)
                .get("id")
                .asText());

        // Read-holding organization roles read the versions (the #5
        // catalog grants app:versions:read to them).
        String viewerToken = loginOfOrgRoleIn(orgId, "ver-vis-viewer@example.com", "VIEWER");
        assertThat(get(viewerToken, versionsUri(appId), 200).get("items").size())
                .isEqualTo(1);
        assertThat(get(viewerToken, versionsUri(appId) + "/" + versionId, 200)
                        .get("versionName")
                        .asText())
                .isEqualTo("1.0.0");

        // A DEVELOPER reads (app:versions:read) but cannot create
        // (app:versions:write): the denial conceals as the application
        // miss, indistinguishable from an unknown application.
        String developerToken = loginOfOrgRoleIn(orgId, "ver-vis-dev@example.com", "DEVELOPER");
        assertThat(get(developerToken, versionsUri(appId), 200).get("items").size())
                .isEqualTo(1);
        Recording deniedCreate = exchange(versionsUri(appId), developerToken, "POST", Map.of("versionName", "2.0.0"));
        assertThat(deniedCreate.status).isEqualTo(404);
        assertThat(deniedCreate.body.get("code").asText()).isEqualTo("APPLICATION_NOT_FOUND");

        // Tenant roles hold no app: grants — their surface stays the
        // tenant-scoped application listing (S2-03); the version routes
        // conceal.
        UUID tenantAdminId = userIdByEmail(insertOrglessUser("ver-vis-tadmin@example.com"));
        assignMember(adminToken, tenantId, tenantAdminId, "TENANT_ADMIN");
        String tenantAdminToken = accessToken(login("ver-vis-tadmin@example.com", PASSWORD));
        assertThat(get(versionsUri(appId), tenantAdminToken).status).isEqualTo(404);

        // A different organization's administrator probing the
        // application: the same concealed miss as an unknown id.
        UUID otherOrg = createOrg("Ver Vis Other Corp");
        String otherToken = loginOfOrgRoleIn(otherOrg, "ver-vis-other@example.com", "ORG_ADMIN");
        Recording foreignDenied = get(versionsUri(appId), otherToken);
        Recording genuineMiss = get("/api/v1/applications/" + UUID.randomUUID() + "/versions", otherToken);
        assertThat(foreignDenied.status).isEqualTo(404);
        assertThat(genuineMiss.status).isEqualTo(404);
        assertThat(foreignDenied.body.get("code").asText())
                .isEqualTo(genuineMiss.body.get("code").asText())
                .isEqualTo("APPLICATION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // AC3, S3-02/S3-03: shared applications serve versions through the
    // organization scope, and the ownership transition carries versions
    // -----------------------------------------------------------------------

    @Test
    void sharedApplicationVersionsLiveInTheOrganizationScope() throws Exception {
        UUID orgId = createOrg("Ver Shared Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "ver-shared-owner@example.com", "APP_OWNER");
        UUID appId = UUID.fromString(post(
                        ownerToken,
                        "/api/v1/applications",
                        Map.of(
                                "ownershipScope",
                                "ORGANIZATION",
                                "organizationId",
                                orgId.toString(),
                                "name",
                                "Shared Banking",
                                "platforms",
                                List.of("IOS"),
                                "bundleId",
                                "com.vershared.app"),
                        201)
                .get("id")
                .asText());

        JsonNode created = post(ownerToken, versionsUri(appId), Map.of("versionName", "1.0.0"), 201);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT organization_id, tenant_id FROM versions WHERE id = ?",
                UUID.fromString(created.get("id").asText()));
        assertThat(row.get("organization_id")).isEqualTo(orgId);
        assertThat(row.get("tenant_id"))
                .as("a shared application's versions are organization-owned")
                .isNull();

        JsonNode items = get(ownerToken, versionsUri(appId), 200).get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("artifactsByPlatform").size())
                .as("the slots group by the application's platforms exactly — IOS only here")
                .isEqualTo(1);
        assertThat(items.get(0).get("artifactsByPlatform").has("IOS")).isTrue();
        assertThat(items.get(0).get("artifactsByPlatform").has("ANDROID")).isFalse();
    }

    @Test
    void ownershipTransitionsCarryTheVersionsAlong() throws Exception {
        UUID orgId = createOrg("Ver Transition Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-trans-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "ver-trans-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver Transition Bank", "crm-ver-trans-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Transitioning", "com.vertrans.app");
        UUID versionId = UUID.fromString(post(ownerToken, versionsUri(appId), Map.of("versionName", "1.0.0"), 201)
                .get("id")
                .asText());

        // TENANT -> ORGANIZATION: the version follows to the null-tenant
        // organization scope and stays reachable on its routes.
        patch(ownerToken, "/api/v1/applications/" + appId, Map.of("ownershipScope", "ORGANIZATION"), 200);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM versions WHERE id = ?", UUID.class, versionId))
                .as("the version row moved with its application")
                .isNull();
        assertThat(get(ownerToken, versionsUri(appId), 200).get("items").size()).isEqualTo(1);
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_SCOPE_CHANGED)
                        .get(0)
                        .detail())
                .containsEntry("versionsCarried", "1");

        // ORGANIZATION -> TENANT: it follows back to the owning tenant.
        patch(
                ownerToken,
                "/api/v1/applications/" + appId,
                Map.of("ownershipScope", "TENANT", "tenantId", tenantId.toString()),
                200);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM versions WHERE id = ?", UUID.class, versionId))
                .isEqualTo(tenantId);
        assertThat(get(ownerToken, versionsUri(appId), 200).get("items").size()).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Conventions: idempotency, authentication
    // -----------------------------------------------------------------------

    @Test
    void creationHonorsTheIdempotencyKeyContract() throws Exception {
        UUID orgId = createOrg("Ver Idem Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ver-idem-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "ver-idem-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Ver Idem Bank", "crm-ver-idem-1");
        UUID appId = createTenantApp(ownerToken, tenantId, "Idempotent", "com.veridem.app");

        Map<String, Object> body = Map.of("versionName", "1.2.3");
        String key = UUID.randomUUID().toString();
        Recording first = exchange(versionsUri(appId), ownerToken, "POST", body, key);
        assertThat(first.status).isEqualTo(201);
        Recording replay = exchange(versionsUri(appId), ownerToken, "POST", body, key);
        assertThat(replay.status).as("the same key replays the stored outcome").isEqualTo(201);
        assertThat(replay.body.get("id").asText())
                .isEqualTo(first.body.get("id").asText());

        Integer count =
                jdbc.queryForObject("SELECT count(*) FROM versions WHERE application_id = ?", Integer.class, appId);
        assertThat(count).as("one logical creation, one row").isEqualTo(1);
    }

    @Test
    void unauthenticatedRequestsAreRejected() {
        assertThat(get(versionsUri(UUID.randomUUID()), null).status).isEqualTo(401);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String versionsUri(UUID applicationId) {
        return "/api/v1/applications/" + applicationId + "/versions";
    }

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", new ArrayList<>(List.of("IOS", "ANDROID")));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private void assignMember(String token, UUID tenantId, UUID userId, String role) {
        post(token, "/api/v1/tenants/" + tenantId + "/members", Map.of("userId", userId.toString(), "role", role), 201);
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        JsonNode created = post(
                token,
                "/api/v1/tenants",
                Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID userIdByEmail(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

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
                        "Version Test User"))
                .retrieve()
                .body(String.class);
        return email;
    }

    /** Creates one organization per test; repeated calls for the same name are a fixture bug. */
    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
    private String loginOfOrgRoleIn(UUID orgId, String email, String role) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(
                devToken,
                "/api/v1/auth/invitations",
                Map.of("email", email, "organizationId", orgId.toString(), "role", role),
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
                        "Version " + role))
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
        return exchange(uri, bearer, "POST", body).expect(expectedStatus);
    }

    private JsonNode patch(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PATCH", body).expect(expectedStatus);
    }

    private Recording get(String uri, String bearer) {
        return exchange(uri, bearer, "GET", null);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null).expect(expectedStatus);
    }

    private Recording exchange(String uri, String bearer, String method, Map<String, Object> body) {
        return exchange(uri, bearer, method, body, null);
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

    private record Recording(int status, String contentType, JsonNode body) {

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return body;
        }
    }
}
