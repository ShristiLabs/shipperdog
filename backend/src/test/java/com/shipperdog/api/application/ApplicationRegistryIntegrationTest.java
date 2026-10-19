package com.shipperdog.api.application;

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
 * End-to-end acceptance flow for the application registry (issue #13,
 * S3-01, FR-003): registration with platform-coupled identifiers
 * (AC1), organization-scoped identifier uniqueness with 409s (AC2),
 * metadata edits with audited identifier changes (AC3), the
 * ACTIVE/ARCHIVED lifecycle that keeps archived applications readable
 * with history (AC4), and tenant-scope visibility through the owning
 * tenant + organization roles (AC5) — with the concealed-404 rule on
 * every scoped denial (PRD 12).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ApplicationRegistryIntegrationTest {

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
    // AC1: registration with platform-coupled identifiers
    // -----------------------------------------------------------------------

    @Test
    void registrationCreatesAnApplicationWithItsPlatformIdentifiers() throws Exception {
        UUID orgId = createOrg("Reg Create Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "r-create-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "r-create-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Reg Create Bank", "crm-r-create-1");

        Map<String, Object> body =
                app(tenantId, "Banking", List.of("IOS", "ANDROID"), "com.regcreate.banking", "com.regcreate.banking");
        body.put("description", "The flagship banking app");
        JsonNode created = post(ownerToken, "/api/v1/applications", body, 201);
        assertThat(created.get("id").asText()).isNotBlank();
        assertThat(UUID.fromString(created.get("organizationId").asText())).isEqualTo(orgId);
        assertThat(UUID.fromString(created.get("tenantId").asText())).isEqualTo(tenantId);
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.get("platforms").toString()).contains("IOS", "ANDROID");
        assertThat(created.get("bundleId").asText()).isEqualTo("com.regcreate.banking");
        assertThat(created.get("packageName").asText()).isEqualTo("com.regcreate.banking");
        assertThat(created.get("description").asText()).isEqualTo("The flagship banking app");

        List<AuditEvent> events = audit.eventsOfType(AuditEventType.APPLICATION_CREATED);
        assertThat(events).as("registration is audited").hasSize(1);
        assertThat(events.get(0).applicationId())
                .isEqualTo(UUID.fromString(created.get("id").asText()));
        assertThat(events.get(0).tenantId()).isEqualTo(tenantId);
        assertThat(events.get(0).detail()).containsEntry("name", "Banking");
    }

    @Test
    void registrationRequiresIdentifiersForEachChosenPlatformAndAtLeastOnePlatform() throws Exception {
        UUID orgId = createOrg("Reg Couple Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "r-couple-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "r-couple-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Reg Couple Bank", "crm-r-couple-1");

        // No platforms at all.
        post(ownerToken, "/api/v1/applications", app(tenantId, "No Platforms", List.of(), null, null), 400);
        // iOS chosen, bundle id missing.
        post(ownerToken, "/api/v1/applications", app(tenantId, "Missing Bundle", List.of("IOS"), null, null), 400);
        // Android chosen, package name missing.
        post(ownerToken, "/api/v1/applications", app(tenantId, "Missing Package", List.of("ANDROID"), null, null), 400);
        // Identifier supplied without its platform.
        post(
                ownerToken,
                "/api/v1/applications",
                app(tenantId, "Orphan Bundle", List.of("ANDROID"), "com.regcouple.orphan", null),
                400);
        // Malformed (not reverse-DNS) identifier.
        post(
                ownerToken,
                "/api/v1/applications",
                app(tenantId, "Bad Bundle", List.of("IOS"), "not reverse dns!", null),
                400);
        // Unknown platform value.
        post(
                ownerToken,
                "/api/v1/applications",
                app(tenantId, "Bad Platform", List.of("VISIONOS"), "com.regcouple.bad", null),
                400);
        // Missing name.
        post(
                ownerToken,
                "/api/v1/applications",
                app(tenantId, null, List.of("IOS"), "com.regcouple.noname", null),
                400);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM applications WHERE organization_id = ?", Integer.class, orgId);
        assertThat(count).as("no invalid registration persisted anything").isEqualTo(0);
    }

    @Test
    void registrationIsAuthorizedForOrganizationRolesOnly() throws Exception {
        UUID orgId = createOrg("Reg Auth Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "r-auth-admin@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(adminToken, orgId, "Reg Auth Bank", "crm-r-auth-1");

        // ORG_ADMIN holds app:applications:write through the org overlay.
        post(
                adminToken,
                "/api/v1/applications",
                app(tenantId, "Admin Made", List.of("IOS"), "com.regauth.admin", null),
                201);

        // A TENANT_ADMIN of the target tenant does not hold creation
        // rights: target-scoped denial, concealed as the tenant miss.
        UUID tenantAdminId = userIdByEmail(insertOrglessUser("r-auth-tadmin@example.com"));
        assignMember(adminToken, tenantId, tenantAdminId, "TENANT_ADMIN");
        String tenantAdminToken = accessToken(login("r-auth-tadmin@example.com", PASSWORD));
        Recording denied = exchange(
                "/api/v1/applications",
                tenantAdminToken,
                "POST",
                app(tenantId, "Tenant Made", List.of("IOS"), "com.regauth.tenant", null));
        assertThat(denied.status)
                .as("creation rights are organization-level (concealed)")
                .isEqualTo(404);
        assertThat(denied.body.get("code").asText()).isEqualTo("TENANT_NOT_FOUND");

        // A read-only org role cannot create either (VIEWER lists read
        // only); the denial conceals on the tenant target the same way.
        String viewerToken = loginOfOrgRoleIn(orgId, "r-auth-viewer@example.com", "VIEWER");
        Recording viewerDenied = exchange(
                "/api/v1/applications",
                viewerToken,
                "POST",
                app(tenantId, "Viewer Made", List.of("IOS"), "com.regauth.viewer", null));
        assertThat(viewerDenied.status).isEqualTo(404);
    }

    // -----------------------------------------------------------------------
    // AC2: uniqueness per organization+platform
    // -----------------------------------------------------------------------

    @Test
    void identifiersAreUniquePerOrganizationWithDistinctConflictCodes() throws Exception {
        UUID orgId = createOrg("Reg Uniq Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "r-uniq-admin@example.com", "ORG_ADMIN");
        UUID tenantA = createTenant(ownerToken, orgId, "Reg Uniq A", "crm-r-uniq-a");
        UUID tenantB = createTenant(ownerToken, orgId, "Reg Uniq B", "crm-r-uniq-b");

        post(
                ownerToken,
                "/api/v1/applications",
                app(tenantA, "First", List.of("IOS", "ANDROID"), "com.reguniq.first", "com.reguniq.first"),
                201);

        // Same bundle id, same organization, different tenant: still a conflict.
        JsonNode bundle = exchange(
                        "/api/v1/applications",
                        ownerToken,
                        "POST",
                        app(tenantB, "Second", List.of("IOS"), "com.reguniq.first", null))
                .expect(409);
        assertThat(bundle.get("code").asText()).isEqualTo("APPLICATION_BUNDLE_TAKEN");

        // Same package name, same organization.
        JsonNode pkg = exchange(
                        "/api/v1/applications",
                        ownerToken,
                        "POST",
                        app(tenantB, "Third", List.of("ANDROID"), null, "com.reguniq.first"))
                .expect(409);
        assertThat(pkg.get("code").asText()).isEqualTo("APPLICATION_PACKAGE_TAKEN");

        // Same name within the same owning scope.
        JsonNode name = exchange(
                        "/api/v1/applications",
                        ownerToken,
                        "POST",
                        app(tenantA, "First", List.of("IOS"), "com.reguniq.x", null))
                .expect(409);
        assertThat(name.get("code").asText()).isEqualTo("APPLICATION_NAME_TAKEN");

        // The same identifier in a DIFFERENT organization is fine: the
        // guard is per organization, not global.
        UUID otherOrg = createOrg("Reg Uniq Other Corp");
        String otherToken = loginOfOrgRoleIn(otherOrg, "r-uniq-other@example.com", "ORG_ADMIN");
        UUID otherTenant = createTenant(otherToken, otherOrg, "Reg Uniq Other Bank", "crm-r-uniq-o");
        post(
                otherToken,
                "/api/v1/applications",
                app(otherTenant, "First", List.of("IOS"), "com.reguniq.first", null),
                201);
    }

    // -----------------------------------------------------------------------
    // AC3: metadata edits with audited identifier changes
    // -----------------------------------------------------------------------

    @Test
    void metadataEditsAuditIdentifierChangesWithBeforeAndAfter() throws Exception {
        UUID orgId = createOrg("Reg Edit Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "r-edit-owner@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(ownerToken, orgId, "Reg Edit Bank", "crm-r-edit-1");
        UUID appId = UUID.fromString(post(
                        ownerToken,
                        "/api/v1/applications",
                        app(tenantId, "Editable", List.of("IOS"), "com.regedit.old", null),
                        201)
                .get("id")
                .asText());

        JsonNode updated = patch(
                ownerToken,
                "/api/v1/applications/" + appId,
                Map.of("description", "Now with a description", "bundleId", "com.regedit.new"),
                200);
        assertThat(updated.get("bundleId").asText()).isEqualTo("com.regedit.new");
        assertThat(updated.get("description").asText()).isEqualTo("Now with a description");

        List<AuditEvent> updates = audit.eventsOfType(AuditEventType.APPLICATION_UPDATED);
        assertThat(updates).hasSize(1);
        Map<String, String> detail = updates.get(0).detail();
        assertThat(detail).containsEntry("bundleId.before", "com.regedit.old");
        assertThat(detail).containsEntry("bundleId.after", "com.regedit.new");

        // Switching platforms without supplying the new platform's
        // identifier is rejected; switching with it retires the old
        // identifier, audited.
        patch(ownerToken, "/api/v1/applications/" + appId, Map.of("platforms", List.of("ANDROID")), 400);
        JsonNode switched = patch(
                ownerToken,
                "/api/v1/applications/" + appId,
                Map.of("platforms", List.of("ANDROID"), "packageName", "com.regedit.android"),
                200);
        assertThat(switched.get("bundleId").isNull()).isTrue();
        assertThat(switched.get("packageName").asText()).isEqualTo("com.regedit.android");

        Map<String, String> switchDetail =
                audit.eventsOfType(AuditEventType.APPLICATION_UPDATED).get(1).detail();
        assertThat(switchDetail).containsEntry("bundleId.before", "com.regedit.new");
        assertThat(switchDetail).containsEntry("bundleId.after", "");
        assertThat(switchDetail).containsEntry("packageName.after", "com.regedit.android");

        // An update colliding with another application's identifier is a 409.
        // The neighbor registers the Android platform so the collision is on
        // the package-name guard (the editable app is ANDROID-only now).
        post(
                ownerToken,
                "/api/v1/applications",
                app(tenantId, "Neighbor", List.of("ANDROID"), null, "com.regedit.neighbor"),
                201);
        JsonNode collision = exchange(
                        "/api/v1/applications/" + appId,
                        ownerToken,
                        "PATCH",
                        Map.of("packageName", "com.regedit.neighbor"))
                .expect(409);
        assertThat(collision.get("code").asText()).isEqualTo("APPLICATION_PACKAGE_TAKEN");

        // Re-adding the iOS platform without its identifier is rejected;
        // with it, both platforms coexist again.
        patch(ownerToken, "/api/v1/applications/" + appId, Map.of("platforms", List.of("IOS", "ANDROID")), 400);
        JsonNode dual = patch(
                ownerToken,
                "/api/v1/applications/" + appId,
                Map.of("platforms", List.of("IOS", "ANDROID"), "bundleId", "com.regedit.back"),
                200);
        assertThat(dual.get("platforms").size()).isEqualTo(2);
        assertThat(dual.get("bundleId").asText()).isEqualTo("com.regedit.back");
    }

    // -----------------------------------------------------------------------
    // AC4: archived applications accept no new versions, stay readable
    // -----------------------------------------------------------------------

    @Test
    void archivedApplicationsRemainReadableWithHistoryAndReverse() throws Exception {
        UUID orgId = createOrg("Reg Arch Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "r-arch-owner@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(ownerToken, orgId, "Reg Arch Bank", "crm-r-arch-1");
        UUID appId = UUID.fromString(post(
                        ownerToken,
                        "/api/v1/applications",
                        app(tenantId, "Archivable", List.of("IOS"), "com.regarch.app", null),
                        201)
                .get("id")
                .asText());

        JsonNode archived = patch(ownerToken, "/api/v1/applications/" + appId, Map.of("status", "ARCHIVED"), 200);
        assertThat(archived.get("status").asText()).isEqualTo("ARCHIVED");

        // Readable after archiving (AC4), with its audit history intact.
        JsonNode read = get(ownerToken, "/api/v1/applications/" + appId, 200);
        assertThat(read.get("status").asText()).isEqualTo("ARCHIVED");
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_CREATED))
                .as("the registration history survives archiving")
                .isNotEmpty();
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_UPDATED))
                .as("the archive transition is audited with before/after")
                .isNotEmpty();

        // The reserved DISABLED label is rejected outright.
        patch(ownerToken, "/api/v1/applications/" + appId, Map.of("status", "DISABLED"), 400);

        // Archiving is reversible, mirroring the tenant lifecycle.
        JsonNode restored = patch(ownerToken, "/api/v1/applications/" + appId, Map.of("status", "ACTIVE"), 200);
        assertThat(restored.get("status").asText()).isEqualTo("ACTIVE");
    }

    // -----------------------------------------------------------------------
    // S16-15: the OTA opt-in toggle rides the registry PATCH (V55)
    // -----------------------------------------------------------------------

    @Test
    void otaOptInToggleFlipsThroughPatchAuditedAndGuarded() throws Exception {
        UUID orgId = createOrg("Reg Ota Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "r-ota-owner@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(ownerToken, orgId, "Reg Ota Bank", "crm-r-ota-1");
        UUID appId = UUID.fromString(post(
                        ownerToken,
                        "/api/v1/applications",
                        app(tenantId, "Updatable", List.of("IOS"), "com.regota.app", null),
                        201)
                .get("id")
                .asText());

        // The opt-in is born disabled (V55's default) and the view says so.
        JsonNode born = get(ownerToken, "/api/v1/applications/" + appId, 200);
        assertThat(born.get("otaEnabled").asBoolean()).isFalse();

        // A same-value PATCH is a pure no-op: 200, the unchanged view,
        // and no audit noise (the change-pair discipline skips it).
        JsonNode noop = patch(ownerToken, "/api/v1/applications/" + appId, Map.of("otaEnabled", false), 200);
        assertThat(noop.get("otaEnabled").asBoolean()).isFalse();
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_UPDATED)).isEmpty();

        // Flipping the gate is a normal PATCH field: audited before/after.
        JsonNode enabled = patch(ownerToken, "/api/v1/applications/" + appId, Map.of("otaEnabled", true), 200);
        assertThat(enabled.get("otaEnabled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT ota_enabled FROM applications WHERE id = ?", Boolean.class, appId))
                .isTrue();
        // The plain GET re-read carries the enabled gate too — the
        // console renders from GET, never from the PATCH echo.
        assertThat(get(ownerToken, "/api/v1/applications/" + appId, 200)
                        .get("otaEnabled")
                        .asBoolean())
                .isTrue();
        Map<String, String> detail =
                audit.eventsOfType(AuditEventType.APPLICATION_UPDATED).get(0).detail();
        assertThat(detail).containsEntry("otaEnabled.before", "false");
        assertThat(detail).containsEntry("otaEnabled.after", "true");

        // The toggle is reversible, mirroring every sibling field.
        JsonNode disabled = patch(ownerToken, "/api/v1/applications/" + appId, Map.of("otaEnabled", false), 200);
        assertThat(disabled.get("otaEnabled").asBoolean()).isFalse();

        // A read-only org role cannot flip it: the write-grain denial
        // conceals on the application target (PRD 12).
        String viewerToken = loginOfOrgRoleIn(orgId, "r-ota-viewer@example.com", "VIEWER");
        Recording viewerDenied =
                exchange("/api/v1/applications/" + appId, viewerToken, "PATCH", Map.of("otaEnabled", true));
        assertThat(viewerDenied.status).isEqualTo(404);
        assertThat(viewerDenied.body.get("code").asText()).isEqualTo("APPLICATION_NOT_FOUND");

        // Another organization's administrator sees the standard miss.
        UUID otherOrgId = createOrg("Reg Ota Other Corp");
        String otherAdminToken = loginOfOrgRoleIn(otherOrgId, "r-ota-other-admin@example.com", "ORG_ADMIN");
        Recording outsiderDenied =
                exchange("/api/v1/applications/" + appId, otherAdminToken, "PATCH", Map.of("otaEnabled", true));
        assertThat(outsiderDenied.status).isEqualTo(404);
        assertThat(outsiderDenied.body.get("code").asText()).isEqualTo("APPLICATION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // AC5: visibility respects tenant scope (S2-02)
    // -----------------------------------------------------------------------

    @Test
    void readsResolveThroughTheOwningTenantAndOrgRolesAndConcealOtherwise() throws Exception {
        UUID orgId = createOrg("Reg Vis Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "r-vis-admin@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(adminToken, orgId, "Reg Vis Bank", "crm-r-vis-1");
        UUID appId = UUID.fromString(post(
                        adminToken,
                        "/api/v1/applications",
                        app(tenantId, "Visible", List.of("IOS"), "com.regvis.app", null),
                        201)
                .get("id")
                .asText());

        // The owning organization's administrators read by id.
        assertThat(get("/api/v1/applications/" + appId, adminToken).status).isEqualTo(200);

        // The owning organization's read-only roles read too (VIEWER
        // lists app:applications:read in the matrix).
        String viewerToken = loginOfOrgRoleIn(orgId, "r-vis-viewer@example.com", "VIEWER");
        assertThat(get("/api/v1/applications/" + appId, viewerToken).status).isEqualTo(200);

        // Tenant roles hold no app: grants — their surface is the
        // tenant-scoped listing (S2-03); the by-id denial conceals.
        UUID testerId = userIdByEmail(insertOrglessUser("r-vis-tester@example.com"));
        assignMember(adminToken, tenantId, testerId, "TENANT_TESTER");
        String testerToken = accessToken(login("r-vis-tester@example.com", PASSWORD));
        JsonNode listing = get(testerToken, "/api/v1/tenants/" + tenantId + "/applications", 200);
        assertThat(listing.get("items").size())
                .as("the tenant listing remains the tenant-role surface")
                .isEqualTo(1);
        assertThat(listing.get("items").get(0).get("platforms").size())
                .as("the listing carries the registry fields")
                .isEqualTo(1);
        Recording byIdDenied = get("/api/v1/applications/" + appId, testerToken);
        assertThat(byIdDenied.status).isEqualTo(404);
        assertThat(byIdDenied.body.get("code").asText()).isEqualTo("APPLICATION_NOT_FOUND");

        // A tenant administrator of a DIFFERENT tenant of the same
        // organization holds no role in the owning tenant: concealed.
        UUID otherTenant = createTenant(adminToken, orgId, "Reg Vis Other", "crm-r-vis-2");
        UUID otherAdminId = userIdByEmail(insertOrglessUser("r-vis-other-tadmin@example.com"));
        assignMember(adminToken, otherTenant, otherAdminId, "TENANT_ADMIN");
        String otherAdminToken = accessToken(login("r-vis-other-tadmin@example.com", PASSWORD));
        assertThat(get("/api/v1/applications/" + appId, otherAdminToken).status).isEqualTo(404);

        // A tenant administrator of the OWNING tenant has no app: grant
        // either (the overlay flows from org roles), but the tenant
        // listing serves them.
        UUID ownAdminId = userIdByEmail(insertOrglessUser("r-vis-own-tadmin@example.com"));
        assignMember(adminToken, tenantId, ownAdminId, "TENANT_ADMIN");
        String ownAdminToken = accessToken(login("r-vis-own-tadmin@example.com", PASSWORD));
        assertThat(get("/api/v1/applications/" + appId, ownAdminToken).status).isEqualTo(404);
        assertThat(get(ownAdminToken, "/api/v1/tenants/" + tenantId + "/applications", 200)
                        .get("items")
                        .size())
                .isEqualTo(1);

        // An outsider probing an unknown id and a denied caller get the
        // same envelope: genuine miss and denial are indistinguishable.
        String outsiderToken = accessToken(registerOrglessUser("r-vis-outsider@example.com"));
        Recording genuineMiss = get("/api/v1/applications/" + UUID.randomUUID(), outsiderToken);
        Recording outsiderDenied = get("/api/v1/applications/" + appId, outsiderToken);
        assertThat(genuineMiss.status).isEqualTo(404);
        assertThat(outsiderDenied.status).isEqualTo(404);
        assertThat(outsiderDenied.body.get("code").asText())
                .isEqualTo(genuineMiss.body.get("code").asText())
                .isEqualTo("APPLICATION_NOT_FOUND");
    }

    @Test
    void creationIntoDeactivatedTenantIsBlockedButExistingAppsStayUsable() throws Exception {
        UUID orgId = createOrg("Reg Deact Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "r-deact-admin@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(adminToken, orgId, "Reg Deact Bank", "crm-r-deact-1");
        UUID appId = UUID.fromString(post(
                        adminToken,
                        "/api/v1/applications",
                        app(tenantId, "Before Deactivation", List.of("IOS"), "com.regdeact.app", null),
                        201)
                .get("id")
                .asText());

        put(adminToken, "/api/v1/tenants/" + tenantId, Map.of("status", "DEACTIVATED"), 200);

        JsonNode blocked = exchange(
                        "/api/v1/applications",
                        adminToken,
                        "POST",
                        app(tenantId, "After Deactivation", List.of("IOS"), "com.regdeact.after", null))
                .expect(409);
        assertThat(blocked.get("code").asText()).isEqualTo("APPLICATION_TENANT_DEACTIVATED");

        // Reads of the existing application stay available: deactivation
        // blocks NEW affiliations only.
        assertThat(get(adminToken, "/api/v1/applications/" + appId, 200)
                        .get("status")
                        .asText())
                .isEqualTo("ACTIVE");
    }

    // -----------------------------------------------------------------------
    // Conventions: idempotency, unknown tenant, authentication
    // -----------------------------------------------------------------------

    @Test
    void registrationHonorsTheIdempotencyKeyContract() throws Exception {
        UUID orgId = createOrg("Reg Idem Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "r-idem-owner@example.com", "ORG_ADMIN");
        UUID tenantId = createTenant(ownerToken, orgId, "Reg Idem Bank", "crm-r-idem-1");

        Map<String, Object> body = app(tenantId, "Idempotent", List.of("IOS"), "com.regidem.app", null);
        String key = UUID.randomUUID().toString();
        Recording first = exchange("/api/v1/applications", ownerToken, "POST", body, key);
        assertThat(first.status).isEqualTo(201);
        Recording replay = exchange("/api/v1/applications", ownerToken, "POST", body, key);
        assertThat(replay.status).as("the same key replays the stored outcome").isEqualTo(201);
        assertThat(replay.body.get("id").asText())
                .isEqualTo(first.body.get("id").asText());

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM applications WHERE organization_id = ?", Integer.class, orgId);
        assertThat(count).as("one logical registration, one row").isEqualTo(1);
    }

    @Test
    void unknownTenantIsTheStandardMissAndUnauthenticatedIsRejected() throws Exception {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode miss = exchange(
                        "/api/v1/applications",
                        devToken,
                        "POST",
                        app(UUID.randomUUID(), "Ghost", List.of("IOS"), "com.ghost.app", null))
                .expect(404);
        assertThat(miss.get("code").asText()).isEqualTo("APPLICATION_NOT_FOUND");

        assertThat(get("/api/v1/applications/" + UUID.randomUUID(), null).status)
                .isEqualTo(401);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Registration body builder; null fields are omitted entirely. */
    private static Map<String, Object> app(
            UUID tenantId, String name, List<String> platforms, String bundleId, String packageName) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        if (name != null) {
            body.put("name", name);
        }
        if (bundleId != null) {
            body.put("bundleId", bundleId);
        }
        if (packageName != null) {
            body.put("packageName", packageName);
        }
        body.put("platforms", new ArrayList<>(platforms));
        return body;
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

    private UUID organizationIdByName(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", UUID.class, name);
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
                        "Registry Test User"))
                .retrieve()
                .body(String.class);
        return email;
    }

    private String registerOrglessUser(String email) {
        insertOrglessUser(email);
        return login(email, PASSWORD);
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
                        "Registry " + role))
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

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PUT", body).expect(expectedStatus);
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
