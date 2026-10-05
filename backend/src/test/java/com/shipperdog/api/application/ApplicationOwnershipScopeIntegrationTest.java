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
 * End-to-end acceptance flow for the ownership scope (issue #14, S3-02,
 * PRD 14, INV-002): an application is exactly one of tenant-owned or
 * organization/shared. AC1 — the discriminator and its database CHECK;
 * AC2 — INV-002 enforcement on create and on scope transitions; AC3 —
 * audited transitions with their preconditions; AC4 — repository
 * queries serve both scopes: organization-shared rows are org-visible
 * and never leak into tenant surfaces, and tenant users see
 * tenant-owned applications only (the authorized-shared arm is S3-03).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ApplicationOwnershipScopeIntegrationTest {

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
    // AC1: the discriminator and its pairing
    // -----------------------------------------------------------------------

    @Test
    void organizationSharedRegistrationCreatesAnOrgOwnedApplication() {
        UUID orgId = createOrg("Scope Create Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s-create-admin@example.com", "ORG_ADMIN");

        JsonNode created = post(
                adminToken,
                "/api/v1/applications",
                sharedApp(orgId, "Banking Platform", "com.scopecreate.banking", "com.scopecreate.banking"),
                201);
        assertThat(created.get("ownershipScope").asText()).isEqualTo("ORGANIZATION");
        assertThat(created.get("tenantId").isNull()).isTrue();
        assertThat(UUID.fromString(created.get("organizationId").asText())).isEqualTo(orgId);
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");

        // The database row carries the org-owned shape (V10 CHECK's valid
        // arm).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT ownership_scope, tenant_id FROM applications WHERE id = ?",
                UUID.fromString(created.get("id").asText()));
        assertThat(row.get("ownership_scope")).isEqualTo("ORGANIZATION");
        assertThat(row.get("tenant_id")).isNull();

        List<AuditEvent> events = audit.eventsOfType(AuditEventType.APPLICATION_CREATED);
        assertThat(events).as("org-shared registration is audited").hasSize(1);
        assertThat(events.get(0).orgId()).isEqualTo(orgId);
        assertThat(events.get(0).tenantId()).isNull();
        assertThat(events.get(0).detail()).containsEntry("ownershipScope", "ORGANIZATION");
    }

    @Test
    void tenantOwnedRegistrationDefaultsTheDiscriminator() {
        UUID orgId = createOrg("Scope Default Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "s-default-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken(orgId), orgId, "Scope Default Bank", "s-default-1");

        JsonNode created = post(
                ownerToken,
                "/api/v1/applications",
                tenantApp(tenantId, "Default Banking", List.of("IOS"), "com.scopedefault.banking", null),
                201);
        assertThat(created.get("ownershipScope").asText()).isEqualTo("TENANT");
        assertThat(UUID.fromString(created.get("tenantId").asText())).isEqualTo(tenantId);
    }

    @Test
    void v10CheckEnforcesExactlyOneOwnerShape() {
        UUID orgId = organizationIdByName("ShipperDog Demo Org");
        // TENANT scope without an owning tenant: rejected (INV-002).
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM applications WHERE ownership_scope = 'TENANT' AND tenant_id IS NULL",
                        Integer.class))
                .isZero();
        org.springframework.dao.DataIntegrityViolationException noTenant = catchIntegrityViolation(
                "INSERT INTO applications (organization_id, ownership_scope, tenant_id, name, platforms, bundle_id)"
                        + " VALUES (?, 'TENANT', NULL, 'Check No Tenant', '{IOS}', 'com.check.notenant')",
                orgId);
        assertThat(noTenant).isNotNull();
        // ORGANIZATION scope with an owning tenant: rejected.
        UUID tenantId = jdbc.queryForObject("SELECT id FROM tenants WHERE external_id = 'demo-acme'", UUID.class);
        org.springframework.dao.DataIntegrityViolationException withTenant = catchIntegrityViolation(
                "INSERT INTO applications (organization_id, ownership_scope, tenant_id, name, platforms, bundle_id)"
                        + " VALUES (?, 'ORGANIZATION', ?, 'Check With Tenant', '{IOS}', 'com.check.withtenant')",
                orgId,
                tenantId);
        assertThat(withTenant).isNotNull();
    }

    // -----------------------------------------------------------------------
    // AC2: INV-002 on registration
    // -----------------------------------------------------------------------

    @Test
    void organizationSharedRegistrationRejectsAVagueOwner() {
        UUID orgId = createOrg("Scope Vague Corp");
        UUID tenantId = createTenant(adminToken(orgId), orgId, "Scope Vague Bank", "s-vague-1");
        String ownerToken = loginOfOrgRoleIn(orgId, "s-vague-owner@example.com", "APP_OWNER");

        // A tenant owner and ORGANIZATION scope contradict (issue AC1);
        // the service rejects before anything is written.
        Map<String, Object> withTenant = sharedApp(orgId, "Vague One", "com.scopevague.one", "com.scopevague.one");
        withTenant.put("tenantId", tenantId.toString());
        JsonNode rejected = post(ownerToken, "/api/v1/applications", withTenant, 400);
        assertThat(rejected.get("code").asText()).isEqualTo("INVALID_REQUEST");

        // No organization at all: the creation permission check has no
        // target and denies opaquely (the target-less rule, PRD 12).
        Map<String, Object> noOrg = sharedApp(null, "Vague Two", "com.scopevague.two", null);
        post(ownerToken, "/api/v1/applications", noOrg, 403);

        // An organization the caller has no role in: the standard miss.
        post(
                ownerToken,
                "/api/v1/applications",
                sharedApp(UUID.randomUUID(), "Vague Three", "com.scopevague.three", null),
                404);

        // A nonexistent organization under the platform wildcard resolves
        // to the same miss at the service (INV-002 service check).
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        post(
                devToken,
                "/api/v1/applications",
                sharedApp(UUID.randomUUID(), "Vague Four", "com.scopevague.four", null),
                404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM applications WHERE name LIKE 'Vague%'", Integer.class))
                .isZero();
    }

    // -----------------------------------------------------------------------
    // AC3: audited transitions with preconditions
    // -----------------------------------------------------------------------

    @Test
    void tenantToOrganizationTransitionIsAuditedAndReleasesTheTenant() {
        UUID orgId = createOrg("Scope TtoO Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "s-ttoo-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken(orgId), orgId, "Scope TtoO Bank", "s-ttoo-1");
        UUID appId = createTenantOwned(ownerToken, tenantId, "TtoO Banking", "com.ttoo.banking");

        JsonNode transitioned =
                patch(ownerToken, "/api/v1/applications/" + appId, Map.of("ownershipScope", "ORGANIZATION"), 200);
        assertThat(transitioned.get("ownershipScope").asText()).isEqualTo("ORGANIZATION");
        assertThat(transitioned.get("tenantId").isNull()).isTrue();

        List<AuditEvent> events = audit.eventsOfType(AuditEventType.APPLICATION_SCOPE_CHANGED);
        assertThat(events).as("the transition is audited").hasSize(1);
        assertThat(events.get(0).applicationId()).isEqualTo(appId);
        assertThat(events.get(0).detail())
                .containsEntry("ownershipScope.before", "TENANT")
                .containsEntry("ownershipScope.after", "ORGANIZATION")
                .containsEntry("tenantId.before", tenantId.toString())
                .containsEntry("tenantId.after", "");

        // The tenant surface no longer lists it; the org surface keeps it.
        assertThat(listingIds(adminToken(orgId), tenantId)).doesNotContain(appId);
        assertThat(get(ownerToken, "/api/v1/applications/" + appId, 200)
                        .get("ownershipScope")
                        .asText())
                .isEqualTo("ORGANIZATION");
    }

    @Test
    void organizationToTenantTransitionInstallsAValidOwner() {
        UUID orgId = createOrg("Scope OtoT Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "s-otot-owner@example.com", "APP_OWNER");
        UUID appId = createOrgShared(ownerToken, orgId, "OtoT Banking", "com.otot.banking");
        UUID tenantId = createTenant(adminToken(orgId), orgId, "Scope OtoT Bank", "s-otot-1");

        JsonNode transitioned = patch(
                ownerToken,
                "/api/v1/applications/" + appId,
                Map.of("ownershipScope", "TENANT", "tenantId", tenantId.toString()),
                200);
        assertThat(transitioned.get("ownershipScope").asText()).isEqualTo("TENANT");
        assertThat(UUID.fromString(transitioned.get("tenantId").asText())).isEqualTo(tenantId);

        List<AuditEvent> events = audit.eventsOfType(AuditEventType.APPLICATION_SCOPE_CHANGED);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).tenantId()).isEqualTo(tenantId);

        assertThat(listingIds(adminToken(orgId), tenantId)).contains(appId);
    }

    @Test
    void organizationToTenantTransitionEnforcesInv002OnTheFutureOwner() {
        UUID orgId = createOrg("Scope Inv Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "s-inv-owner@example.com", "APP_OWNER");
        String adminToken = adminToken(orgId);
        UUID appId = createOrgShared(ownerToken, orgId, "Inv Banking", "com.inv.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Scope Inv Bank", "s-inv-1");

        // Unknown tenant: the standard miss.
        patch(
                ownerToken,
                "/api/v1/applications/" + appId,
                Map.of("ownershipScope", "TENANT", "tenantId", UUID.randomUUID().toString()),
                404);
        // A tenant of another organization: indistinguishable from unknown.
        UUID otherOrgId = createOrg("Scope Inv Other Corp");
        UUID foreignTenantId = createTenant(adminToken(otherOrgId), otherOrgId, "Scope Foreign Bank", "s-inv-f");
        patch(
                ownerToken,
                "/api/v1/applications/" + appId,
                Map.of("ownershipScope", "TENANT", "tenantId", foreignTenantId.toString()),
                404);
        // A deactivated tenant of this organization: the affiliation rule.
        put(adminToken, "/api/v1/tenants/" + tenantId, Map.of("status", "DEACTIVATED"), 200);
        patch(
                ownerToken,
                "/api/v1/applications/" + appId,
                Map.of("ownershipScope", "TENANT", "tenantId", tenantId.toString()),
                409);

        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_SCOPE_CHANGED))
                .as("no transition happened")
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT ownership_scope FROM applications WHERE id = ?", String.class, appId))
                .isEqualTo("ORGANIZATION");
    }

    @Test
    void metadataEditAndScopeTransitionComposeInOnePatch() {
        UUID orgId = createOrg("Scope Compose Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "s-compose-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken(orgId), orgId, "Scope Compose Bank", "s-compose-1");
        UUID appId = createTenantOwned(ownerToken, tenantId, "Compose Banking", "com.compose.banking");

        // One PATCH renames AND converts ownership: the metadata edit goes
        // through the seam (same scope), the transition through the
        // sanctioned cross-scope update; both audited, both durable, and
        // the response reflects the transitioned row.
        JsonNode patched = patch(
                ownerToken,
                "/api/v1/applications/" + appId,
                Map.of("name", "Compose Shared", "ownershipScope", "ORGANIZATION"),
                200);
        assertThat(patched.get("name").asText()).isEqualTo("Compose Shared");
        assertThat(patched.get("ownershipScope").asText()).isEqualTo("ORGANIZATION");
        assertThat(patched.get("tenantId").isNull()).isTrue();

        Map<String, Object> row =
                jdbc.queryForMap("SELECT name, ownership_scope, tenant_id FROM applications WHERE id = ?", appId);
        assertThat(row.get("name")).isEqualTo("Compose Shared");
        assertThat(row.get("ownership_scope")).isEqualTo("ORGANIZATION");
        assertThat(row.get("tenant_id")).isNull();

        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_UPDATED).get(0).detail())
                .containsEntry("name.before", "Compose Banking")
                .containsEntry("name.after", "Compose Shared");
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_SCOPE_CHANGED)).hasSize(1);
    }

    @Test
    void transitionRulesRejectMalformedRequests() {
        UUID orgId = createOrg("Scope Rules Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "s-rules-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken(orgId), orgId, "Scope Rules Bank", "s-rules-1");
        UUID tenantOwnedId = createTenantOwned(ownerToken, tenantId, "Rules Banking", "com.rules.banking");
        UUID orgSharedId = createOrgShared(ownerToken, orgId, "Rules Shared", "com.rules.shared");

        // tenantId without a scope change: re-owning is not a registry
        // operation.
        patch(ownerToken, "/api/v1/applications/" + tenantOwnedId, Map.of("tenantId", tenantId.toString()), 400);
        // Restating the current scope with a tenant: same rejection.
        patch(
                ownerToken,
                "/api/v1/applications/" + tenantOwnedId,
                Map.of("ownershipScope", "TENANT", "tenantId", tenantId.toString()),
                400);
        // Converting to TENANT without naming the owner.
        patch(ownerToken, "/api/v1/applications/" + orgSharedId, Map.of("ownershipScope", "TENANT"), 400);
        // Converting to ORGANIZATION while naming a tenant.
        patch(
                ownerToken,
                "/api/v1/applications/" + tenantOwnedId,
                Map.of("ownershipScope", "ORGANIZATION", "tenantId", tenantId.toString()),
                400);
        // Unknown vocabulary.
        patch(ownerToken, "/api/v1/applications/" + tenantOwnedId, Map.of("ownershipScope", "GLOBAL"), 400);
        // Restating the current scope alone is a no-op: no transition
        // audit, row unchanged.
        patch(ownerToken, "/api/v1/applications/" + tenantOwnedId, Map.of("ownershipScope", "TENANT"), 200);
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_SCOPE_CHANGED)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // AC4: both scopes served; tenant surfaces see tenant-owned only
    // -----------------------------------------------------------------------

    @Test
    void organizationSharedApplicationsAreOrgVisibleAndNeverLeakToTenants() {
        UUID orgId = createOrg("Scope Vis Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "s-vis-owner@example.com", "APP_OWNER");
        String viewerToken = loginOfOrgRoleIn(orgId, "s-vis-viewer@example.com", "VIEWER");
        UUID tenantId = createTenant(adminToken(orgId), orgId, "Scope Vis Bank", "s-vis-1");
        UUID sharedId = createOrgShared(ownerToken, orgId, "Vis Shared Banking", "com.vis.shared");
        UUID tenantOwnedId = createTenantOwned(ownerToken, tenantId, "Vis Tenant Banking", "com.vis.tenant");

        // Organization roles read the shared application org-wide (PRD 14).
        assertThat(get(ownerToken, "/api/v1/applications/" + sharedId, 200)
                        .get("ownershipScope")
                        .asText())
                .isEqualTo("ORGANIZATION");
        get(viewerToken, "/api/v1/applications/" + sharedId, 200);

        // The tenant listing shows tenant-owned rows only: the shared
        // application is absent until authorization maps it (S3-03,
        // PRD 15 — distribution decides, never the other way around).
        List<UUID> listed = listingIds(adminToken(orgId), tenantId);
        assertThat(listed).contains(tenantOwnedId).doesNotContain(sharedId);

        // A tenant administrator without an organization role holds no
        // app: grants (their surface is the tenant listing, S2-03), so
        // the shared application conceal-denies — and so does the
        // by-id read of their own tenant's application: org roles own
        // item reads since S3-01.
        String tenantAdminToken = tenantAdminIn(tenantId, "s-vis-tenant-admin@example.com");
        get(tenantAdminToken, "/api/v1/applications/" + sharedId, 404);
        get(tenantAdminToken, "/api/v1/applications/" + tenantOwnedId, 404);
        JsonNode tenantListing = get(tenantAdminToken, "/api/v1/tenants/" + tenantId + "/applications", 200);
        List<UUID> tenantView = new ArrayList<>();
        tenantListing
                .get("items")
                .forEach(item -> tenantView.add(UUID.fromString(item.get("id").asText())));
        assertThat(tenantView).contains(tenantOwnedId).doesNotContain(sharedId);

        // Another organization's administrator: the same miss.
        UUID otherOrgId = createOrg("Scope Vis Other Corp");
        String otherAdminToken = loginOfOrgRoleIn(otherOrgId, "s-vis-other@example.com", "ORG_ADMIN");
        get(otherAdminToken, "/api/v1/applications/" + sharedId, 404);
        get(otherAdminToken, "/api/v1/applications/" + tenantOwnedId, 404);

        // An org-less user: nothing.
        get(registerOrglessUser("s-vis-orgless@example.com"), "/api/v1/applications/" + sharedId, 404);
    }

    @Test
    void namesAreUniqueAcrossScopesWithinAnOrganization() {
        UUID orgId = createOrg("Scope Name Corp");
        String ownerToken = loginOfOrgRoleIn(orgId, "s-name-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken(orgId), orgId, "Scope Name Bank", "s-name-1");

        createTenantOwned(ownerToken, tenantId, "Collide", "com.namecollide.tenant");
        // Same name as an org-shared application of the same organization.
        JsonNode rejected = post(
                ownerToken, "/api/v1/applications", sharedApp(orgId, "Collide", "com.namecollide.shared", null), 409);
        assertThat(rejected.get("code").asText()).isEqualTo("APPLICATION_NAME_TAKEN");
        // And a second tenant's application colliding with the first.
        UUID secondTenantId = createTenant(adminToken(orgId), orgId, "Scope Name Bank 2", "s-name-2");
        JsonNode crossTenant = post(
                ownerToken,
                "/api/v1/applications",
                tenantApp(secondTenantId, "Collide", List.of("IOS"), "com.namecollide.second", null),
                409);
        assertThat(crossTenant.get("code").asText()).isEqualTo("APPLICATION_NAME_TAKEN");
    }

    @Test
    void platformAdministratorCreatesOrgSharedApplicationsCrossOrganization() {
        UUID orgId = createOrg("Scope Platform Corp");
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));

        JsonNode created = post(
                devToken,
                "/api/v1/applications",
                sharedApp(orgId, "Platform Banking", "com.scopeplatform.banking", null),
                201);
        assertThat(created.get("ownershipScope").asText()).isEqualTo("ORGANIZATION");
        assertThat(audit.eventsOfType(AuditEventType.CROSS_TENANT_ACCESS))
                .as("the platform-administered establishment is audited")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int adminCounter = 0;

    /** A fresh organization administrator token in the organization. */
    private String adminToken(UUID orgId) {
        return loginOfOrgRoleIn(orgId, "s-admin-" + orgId + "-" + adminCounter++ + "@example.com", "ORG_ADMIN");
    }

    private UUID createTenantOwned(String token, UUID tenantId, String name, String bundleId) {
        JsonNode created =
                post(token, "/api/v1/applications", tenantApp(tenantId, name, List.of("IOS"), bundleId, null), 201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID createOrgShared(String token, UUID orgId, String name, String bundleId) {
        JsonNode created = post(token, "/api/v1/applications", sharedApp(orgId, name, bundleId, null), 201);
        return UUID.fromString(created.get("id").asText());
    }

    /** Registration body for a TENANT-owned application (the S3-01 shape). */
    private static Map<String, Object> tenantApp(
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

    /**
     * Registration body for an ORGANIZATION-shared application (S3-02):
     * both identifiers mean both platforms, a bundleId alone means IOS,
     * and no bundleId means ANDROID with a package derived from the name
     * (a valid reverse-DNS value).
     */
    private static Map<String, Object> sharedApp(UUID orgId, String name, String bundleId, String packageName) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        if (orgId != null) {
            body.put("organizationId", orgId.toString());
        }
        if (name != null) {
            body.put("name", name);
        }
        if (bundleId != null && packageName != null) {
            body.put("bundleId", bundleId);
            body.put("packageName", packageName);
            body.put("platforms", new ArrayList<>(List.of("IOS", "ANDROID")));
        } else if (bundleId != null) {
            body.put("bundleId", bundleId);
            body.put("platforms", new ArrayList<>(List.of("IOS")));
        } else {
            String slug = name == null ? "app" : name.toLowerCase().replaceAll("[^a-z0-9]", "");
            body.put("packageName", packageName != null ? packageName : "com." + slug + ".shared");
            body.put("platforms", new ArrayList<>(List.of("ANDROID")));
        }
        return body;
    }

    private List<UUID> listingIds(String token, UUID tenantId) {
        JsonNode page = get(token, "/api/v1/tenants/" + tenantId + "/applications?limit=100", 200);
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("id").asText())));
        return ids;
    }

    private String tenantAdminIn(UUID tenantId, String email) {
        // Register the user org-less, then have the org admin add them to
        // the tenant as TENANT_ADMIN (no organization role: exactly the
        // tenant-only actor AC4 needs).
        insertOrglessUser(email);
        UUID userId = userIdByEmail(email);
        post(
                adminToken(tenantsOrganizationOf(tenantId)),
                "/api/v1/tenants/" + tenantId + "/members",
                Map.of("userId", userId.toString(), "role", "TENANT_ADMIN"),
                201);
        return accessToken(login(email, PASSWORD));
    }

    private UUID tenantsOrganizationOf(UUID tenantId) {
        return jdbc.queryForObject("SELECT organization_id FROM tenants WHERE id = ?", UUID.class, tenantId);
    }

    /** Wraps an insert so a constraint violation is returned, not thrown. */
    private org.springframework.dao.DataIntegrityViolationException catchIntegrityViolation(
            String sql, Object... args) {
        try {
            jdbc.update(sql, args);
            return null;
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return e;
        }
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

    private void insertOrglessUser(String email) {
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
                        "Ownership Scope Test User"))
                .retrieve()
                .body(String.class);
    }

    private String registerOrglessUser(String email) {
        insertOrglessUser(email);
        return accessToken(login(email, PASSWORD));
    }

    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

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
                        "Scope " + role))
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

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null).expect(expectedStatus);
    }

    private Recording exchange(String uri, String bearer, String method, Map<String, Object> body) {
        RestClient.RequestBodySpec spec =
                client.method(HttpMethod.valueOf(method)).uri(uri);
        if (bearer != null) {
            spec.headers(headers -> headers.setBearerAuth(bearer));
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
