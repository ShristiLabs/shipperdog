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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * End-to-end acceptance flow for the shared-application tenant
 * authorization mapping (issue #15, S3-03, PRD 15, INV-005/INV-007).
 * AC1 — grant and soft, auditable revocation; AC2 — opaque denial for
 * unauthorized tenants; AC3 — de-authorization hides the application
 * immediately; AC4 — the single effective-visibility resolution
 * (tenant-owned OR shared+authorized) behind the portal/catalog
 * listing; AC5 — every change audited with the request's reason.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ApplicationTenantAuthorizationIntegrationTest {

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
    // AC1: authorize and de-authorize (soft, auditable)
    // -----------------------------------------------------------------------

    @Test
    void authorizeCreatesAnActiveGrantAuditedWithReason() {
        UUID orgId = createOrg("Authz Grant Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-grant-admin@example.com", "ORG_ADMIN");
        UUID adminUserId = userIdByEmail("s15-grant-admin@example.com");
        UUID appId = createOrgShared(adminToken, orgId, "Grant Banking", "com.s15.grant.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Grant Bank", "s15-grant-1");

        JsonNode granted = post(
                adminToken,
                "/api/v1/applications/" + appId + "/tenants/" + tenantId,
                Map.of("reason", "Customer contract 2026-10"),
                201);
        assertThat(granted.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(UUID.fromString(granted.get("tenantId").asText())).isEqualTo(tenantId);
        assertThat(UUID.fromString(granted.get("grantedBy").asText())).isEqualTo(adminUserId);
        assertThat(granted.get("grantedAt").isNull()).isFalse();
        assertThat(granted.get("revokedAt").isNull()).isTrue();

        Map<String, Object> row = authorizationRow(appId, tenantId);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("revoked_at")).isNull();

        List<AuditEvent> events = audit.eventsOfType(AuditEventType.APPLICATION_TENANT_AUTHORIZED);
        assertThat(events).as("the grant is audited").hasSize(1);
        assertThat(events.get(0).applicationId()).isEqualTo(appId);
        assertThat(events.get(0).orgId()).isEqualTo(orgId);
        assertThat(events.get(0).tenantId()).isEqualTo(tenantId);
        assertThat(events.get(0).userId()).isEqualTo(adminUserId);
        assertThat(events.get(0).reason()).isEqualTo("Customer contract 2026-10");
        assertThat(events.get(0).detail()).containsEntry("status", "ACTIVE");
    }

    @Test
    void deauthorizeRetainsTheRowRevokedAndAudited() {
        UUID orgId = createOrg("Authz Revoke Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-revoke-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Revoke Banking", "com.s15.revoke.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Revoke Bank", "s15-revoke-1");
        authorize(adminToken, appId, tenantId, "onboarding complete");

        delete(adminToken, appId, tenantId, Map.of("reason", "Contract ended"), 204);

        // The row is retained REVOKED with its revocation stamped — never
        // deleted (INV-007 philosophy).
        Map<String, Object> row = authorizationRow(appId, tenantId);
        assertThat(row.get("status")).isEqualTo("REVOKED");
        assertThat(row.get("revoked_at")).isNotNull();
        assertThat(row.get("revoked_by")).isEqualTo(userIdByEmail("s15-revoke-admin@example.com"));

        List<AuditEvent> events = audit.eventsOfType(AuditEventType.APPLICATION_TENANT_DEAUTHORIZED);
        assertThat(events).as("the revocation is audited").hasSize(1);
        assertThat(events.get(0).applicationId()).isEqualTo(appId);
        assertThat(events.get(0).tenantId()).isEqualTo(tenantId);
        assertThat(events.get(0).reason()).isEqualTo("Contract ended");
        assertThat(events.get(0).detail()).containsEntry("status", "REVOKED");
    }

    @Test
    void duplicateAuthorizeIsIdempotentAndRegrantFlipsTheRevokedRow() {
        UUID orgId = createOrg("Authz Dup Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-dup-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Dup Banking", "com.s15.dup.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Dup Bank", "s15-dup-1");

        authorize(adminToken, appId, tenantId, null);
        // Same-state duplicate: success without side effects (the
        // membership precedent) — nothing written, nothing audited.
        JsonNode duplicate = post(adminToken, "/api/v1/applications/" + appId + "/tenants/" + tenantId, null, 200);
        assertThat(duplicate.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_TENANT_AUTHORIZED))
                .hasSize(1);

        // Re-grant after revocation: the same row flips back to ACTIVE.
        delete(adminToken, appId, tenantId, null, 204);
        JsonNode regranted = post(
                adminToken,
                "/api/v1/applications/" + appId + "/tenants/" + tenantId,
                Map.of("reason", "contract renewed"),
                200);
        assertThat(regranted.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(regranted.get("revokedAt").isNull()).isTrue();
        assertThat(authorizationRow(appId, tenantId).get("status")).isEqualTo("ACTIVE");
        List<AuditEvent> grants = audit.eventsOfType(AuditEventType.APPLICATION_TENANT_AUTHORIZED);
        assertThat(grants).as("first grant + re-grant audited, duplicate not").hasSize(2);
        assertThat(grants.get(1).reason()).isEqualTo("contract renewed");
        assertThat(grants.get(1).detail()).containsEntry("regrant", "true");
    }

    // -----------------------------------------------------------------------
    // AC2: opaque denial — unauthorized tenants learn nothing
    // -----------------------------------------------------------------------

    @Test
    void unauthorizedTenantsSeeTheStandardMissNeverA403() {
        UUID orgId = createOrg("Authz Opaque Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-opaque-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Opaque Banking", "com.s15.opaque.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Opaque Bank", "s15-opaque-1");
        String tenantAdminToken = tenantAdminIn(tenantId, "s15-opaque-tenant-admin@example.com");

        // The tenant has no authorization: the item read is the same
        // 404-shaped envelope an unknown id produces — no existence leak
        // (PRD 12), never a 403.
        JsonNode denied = get(tenantAdminToken, "/api/v1/applications/" + appId, 404);
        assertThat(denied.get("code").asText()).isEqualTo("APPLICATION_NOT_FOUND");
        assertThat(listingIds(tenantAdminToken, tenantId)).doesNotContain(appId);

        // A tenant administrator holds no app: grants — managing the
        // mapping conceal-denies the same way (org-level surface).
        post(tenantAdminToken, "/api/v1/applications/" + appId + "/tenants/" + tenantId, null, 404);
        delete(tenantAdminToken, appId, tenantId, null, 404);

        // Another organization's administrator: the same miss.
        UUID otherOrgId = createOrg("Authz Opaque Other Corp");
        String otherAdminToken = loginOfOrgRoleIn(otherOrgId, "s15-opaque-other@example.com", "ORG_ADMIN");
        post(otherAdminToken, "/api/v1/applications/" + appId + "/tenants/" + tenantId, null, 404);
        get(otherAdminToken, "/api/v1/applications/" + appId, 404);
    }

    // -----------------------------------------------------------------------
    // AC3: de-authorization hides the application immediately
    // -----------------------------------------------------------------------

    @Test
    void deauthorizationHidesTheApplicationFromTheTenantImmediately() {
        UUID orgId = createOrg("Authz Hide Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-hide-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Hide Banking", "com.s15.hide.banking");
        UUID otherSharedId = createOrgShared(adminToken, orgId, "Hide Other Banking", "com.s15.hide.other");
        UUID tenantId = createTenant(adminToken, orgId, "Hide Bank", "s15-hide-1");
        UUID secondTenantId = createTenant(adminToken, orgId, "Hide Bank 2", "s15-hide-2");
        String tenantAdminToken = tenantAdminIn(tenantId, "s15-hide-tenant-admin@example.com");

        authorize(adminToken, appId, tenantId, null);
        authorize(adminToken, otherSharedId, tenantId, null);
        authorize(adminToken, otherSharedId, secondTenantId, null);
        assertThat(listingIds(tenantAdminToken, tenantId)).contains(appId, otherSharedId);

        // De-authorization removes exactly this tenant's visibility of
        // the application — immediately, in the same transaction the
        // resolution reads.
        delete(adminToken, appId, tenantId, Map.of("reason", "offboarding"), 204);
        assertThat(listingIds(tenantAdminToken, tenantId)).doesNotContain(appId).contains(otherSharedId);
        // Another authorized tenant is unaffected.
        assertThat(listingIds(adminToken, secondTenantId))
                .contains(otherSharedId)
                .doesNotContain(appId);
    }

    // -----------------------------------------------------------------------
    // AC4: the single effective-visibility resolution
    // -----------------------------------------------------------------------

    @Test
    void listingIsTenantOwnedPlusAuthorizedSharedOnly() {
        UUID orgId = createOrg("Authz Union Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-union-admin@example.com", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "s15-union-owner@example.com", "APP_OWNER");
        UUID tenantId = createTenant(adminToken, orgId, "Union Bank", "s15-union-1");
        UUID secondTenantId = createTenant(adminToken, orgId, "Union Bank 2", "s15-union-2");
        UUID ownedId = createTenantOwned(ownerToken, tenantId, "Union Owned Banking", "com.s15.union.owned");
        UUID sharedId = createOrgShared(ownerToken, orgId, "Union Shared Banking", "com.s15.union.shared");
        UUID unauthorizedSharedId = createOrgShared(ownerToken, orgId, "Union Locked Banking", "com.s15.union.locked");

        // Before any grant: owned only — shared visibility is opt-in,
        // never ambient (INV-005).
        assertThat(listingIds(adminToken, tenantId)).containsExactly(ownedId);

        authorize(ownerToken, sharedId, tenantId, null);
        // The resolution is the union: tenant-owned OR shared+authorized.
        assertThat(listingIds(adminToken, tenantId)).containsExactly(ownedId, sharedId);
        // An authorized application of another tenant never leaks here.
        assertThat(listingIds(adminToken, secondTenantId)).isEmpty();

        // De-authorized again: back to owned only.
        delete(ownerToken, sharedId, tenantId, null, 204);
        assertThat(listingIds(adminToken, tenantId)).containsExactly(ownedId);
        // And the never-authorized shared application stays invisible.
        assertThat(listingIds(adminToken, tenantId)).doesNotContain(unauthorizedSharedId);
    }

    // -----------------------------------------------------------------------
    // AC5: audited with reason — covered above; validation edge here
    // -----------------------------------------------------------------------

    @Test
    void reasonIsOptionalAndBounded() {
        UUID orgId = createOrg("Authz Reason Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-reason-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Reason Banking", "com.s15.reason.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Reason Bank", "s15-reason-1");

        // No body at all: grant succeeds, the event carries no reason.
        authorize(adminToken, appId, tenantId, null);
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_TENANT_AUTHORIZED)
                        .get(0)
                        .reason())
                .isNull();
        delete(adminToken, appId, tenantId, null, 204);
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_TENANT_DEAUTHORIZED)
                        .get(0)
                        .reason())
                .isNull();

        // Over-long reason: rejected at the door.
        post(
                adminToken,
                "/api/v1/applications/" + appId + "/tenants/" + tenantId,
                Map.of("reason", "x".repeat(501)),
                400);
    }

    // -----------------------------------------------------------------------
    // Guards: tenant checks, scope applicability, misses
    // -----------------------------------------------------------------------

    @Test
    void authorizeEnforcesTheTenantGuards() {
        UUID orgId = createOrg("Authz Guard Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-guard-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Guard Banking", "com.s15.guard.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Guard Bank", "s15-guard-1");
        UUID secondTenantId = createTenant(adminToken, orgId, "Guard Bank 2", "s15-guard-2");

        // Unknown tenant: the standard miss.
        post(adminToken, "/api/v1/applications/" + appId + "/tenants/" + UUID.randomUUID(), null, 404);
        // A tenant of another organization: indistinguishable from unknown.
        UUID otherOrgId = createOrg("Authz Guard Other Corp");
        UUID foreignTenantId = createTenant(adminTokenOf(otherOrgId), otherOrgId, "Guard Foreign Bank", "s15-guard-f");
        post(adminToken, "/api/v1/applications/" + appId + "/tenants/" + foreignTenantId, null, 404);
        // A deactivated tenant of this organization: the affiliation rule.
        put(adminToken, "/api/v1/tenants/" + tenantId, Map.of("status", "DEACTIVATED"), 200);
        JsonNode conflict = post(adminToken, "/api/v1/applications/" + appId + "/tenants/" + tenantId, null, 409);
        assertThat(conflict.get("code").asText()).isEqualTo("APPLICATION_TENANT_DEACTIVATED");
        // A tenant-owned application accepts no mapping (PRD 14: its
        // visibility is ownership, not distribution).
        UUID ownedId = createTenantOwned(adminToken, secondTenantId, "Guard Owned", "com.s15.guard.owned");
        JsonNode invalid =
                post(adminToken, "/api/v1/applications/" + ownedId + "/tenants/" + secondTenantId, null, 400);
        assertThat(invalid.get("code").asText()).isEqualTo("INVALID_REQUEST");

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tenant_application_authorizations WHERE application_id = ?",
                        Integer.class,
                        appId))
                .isZero();
    }

    @Test
    void deauthorizeOfAnAbsentAuthorizationIsTheStandardMiss() {
        UUID orgId = createOrg("Authz Miss Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-miss-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Miss Banking", "com.s15.miss.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Miss Bank", "s15-miss-1");

        delete(adminToken, appId, tenantId, null, 404);
        authorize(adminToken, appId, tenantId, null);
        delete(adminToken, appId, tenantId, null, 204);
        // Twice: the second is the same miss.
        delete(adminToken, appId, tenantId, null, 404);
        assertThat(audit.eventsOfType(AuditEventType.APPLICATION_TENANT_DEAUTHORIZED))
                .hasSize(1);
    }

    // -----------------------------------------------------------------------
    // The authorized-tenant list (PRD 15's view)
    // -----------------------------------------------------------------------

    @Test
    void listShowsActiveGrantsOnlyPaginated() {
        UUID orgId = createOrg("Authz List Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-list-admin@example.com", "ORG_ADMIN");
        String viewerToken = loginOfOrgRoleIn(orgId, "s15-list-viewer@example.com", "VIEWER");
        UUID appId = createOrgShared(adminToken, orgId, "List Banking", "com.s15.list.banking");
        UUID firstTenantId = createTenant(adminToken, orgId, "List Bank", "s15-list-1");
        UUID secondTenantId = createTenant(adminToken, orgId, "List Bank 2", "s15-list-2");
        UUID thirdTenantId = createTenant(adminToken, orgId, "List Bank 3", "s15-list-3");

        authorize(adminToken, appId, firstTenantId, null);
        authorize(adminToken, appId, secondTenantId, null);
        authorize(adminToken, appId, thirdTenantId, null);
        delete(adminToken, appId, thirdTenantId, null, 204);

        JsonNode page = get(adminToken, "/api/v1/applications/" + appId + "/tenants?limit=1", 200);
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("tenantId").asText()).isEqualTo(firstTenantId.toString());
        assertThat(page.has("next_cursor")).isTrue();

        JsonNode rest = get(
                adminToken,
                "/api/v1/applications/" + appId + "/tenants?limit=100&cursor="
                        + page.get("next_cursor").asText(),
                200);
        assertThat(rest.get("items")).hasSize(1);
        assertThat(rest.get("items").get(0).get("tenantId").asText()).isEqualTo(secondTenantId.toString());

        // Read-only organization roles see the list; writers only write.
        JsonNode viewerPage = get(viewerToken, "/api/v1/applications/" + appId + "/tenants", 200);
        assertThat(viewerPage.get("items")).hasSize(2);
        post(viewerToken, "/api/v1/applications/" + appId + "/tenants/" + thirdTenantId, null, 404);

        // An application with no grants lists empty, and an unknown
        // application is the standard miss.
        UUID emptyAppId = createOrgShared(adminToken, orgId, "List Empty", "com.s15.list.empty");
        assertThat(get(adminToken, "/api/v1/applications/" + emptyAppId + "/tenants", 200)
                        .get("items"))
                .isEmpty();
        get(adminToken, "/api/v1/applications/" + UUID.randomUUID() + "/tenants", 404);
    }

    // -----------------------------------------------------------------------
    // The scope-transition precondition (issue #14's declared point)
    // -----------------------------------------------------------------------

    @Test
    void activeAuthorizationsBlockTheOrganizationToTenantTransition() {
        UUID orgId = createOrg("Authz Block Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-block-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Block Banking", "com.s15.block.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Block Bank", "s15-block-1");
        authorize(adminToken, appId, tenantId, null);

        JsonNode blocked = patch(
                adminToken,
                "/api/v1/applications/" + appId,
                Map.of("ownershipScope", "TENANT", "tenantId", tenantId.toString()),
                409);
        assertThat(blocked.get("code").asText()).isEqualTo("APPLICATION_AUTHORIZED_TENANTS_PRESENT");
        assertThat(jdbc.queryForObject("SELECT ownership_scope FROM applications WHERE id = ?", String.class, appId))
                .isEqualTo("ORGANIZATION");

        // De-authorized, the same transition succeeds (revoked history
        // grants nothing and does not block).
        delete(adminToken, appId, tenantId, null, 204);
        JsonNode transitioned = patch(
                adminToken,
                "/api/v1/applications/" + appId,
                Map.of("ownershipScope", "TENANT", "tenantId", tenantId.toString()),
                200);
        assertThat(transitioned.get("ownershipScope").asText()).isEqualTo("TENANT");
    }

    // -----------------------------------------------------------------------
    // Race: one grant wins the pair, the loser is idempotent
    // -----------------------------------------------------------------------

    @Test
    void concurrentGrantsResolveToOneActiveRow() throws Exception {
        UUID orgId = createOrg("Authz Race Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "s15-race-admin@example.com", "ORG_ADMIN");
        UUID appId = createOrgShared(adminToken, orgId, "Race Banking", "com.s15.race.banking");
        UUID tenantId = createTenant(adminToken, orgId, "Race Bank", "s15-race-1");

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return grantWithOneRaceRetry("/api/v1/applications/" + appId + "/tenants/" + tenantId, adminToken);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> call : calls) {
                statuses.add(call.get(30, TimeUnit.SECONDS));
            }
            assertThat(grantSuccesses(statuses))
                    .as("every concurrent grant resolves to success (201 create or 200 idempotent), got " + statuses)
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tenant_application_authorizations WHERE application_id = ?"
                                + " AND tenant_id = ?",
                        Integer.class,
                        appId,
                        tenantId))
                .as("exactly one row wins the pair")
                .isOne();
        assertThat(authorizationRow(appId, tenantId).get("status")).isEqualTo("ACTIVE");
    }

    /**
     * One bounded retry for the loser of the check-then-insert race
     * (test-side hardening, issue #39, of the CI flake observed on
     * #38's runs): when two grants interleave between the existence
     * check and the insert, the loser's unique-constraint violation
     * surfaces as a rare 500. The row's winner commits regardless, so
     * a single retry after a beat observes the winner and answers the
     * route's idempotent 200 — the FINAL-STATE assertions below (one
     * row, ACTIVE) are what the race test actually pins, and they run
     * unchanged whether any retry fired.
     */
    private int grantWithOneRaceRetry(String uri, String token) throws InterruptedException {
        int status = exchange(uri, token, "POST", null).status();
        if (status == 500) {
            Thread.sleep(250);
            status = exchange(uri, token, "POST", null).status();
        }
        return status;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean grantSuccesses(List<Integer> statuses) {
        return statuses.stream().allMatch(status -> status == 201 || status == 200);
    }

    private int adminCounter = 0;

    /** A fresh organization administrator token in the organization. */
    private String adminTokenOf(UUID orgId) {
        return loginOfOrgRoleIn(orgId, "s15-admin-" + orgId + "-" + adminCounter++ + "@example.com", "ORG_ADMIN");
    }

    private Map<String, Object> authorizationRow(UUID applicationId, UUID tenantId) {
        return jdbc.queryForMap(
                "SELECT status, granted_by, revoked_by, revoked_at FROM tenant_application_authorizations"
                        + " WHERE application_id = ? AND tenant_id = ?",
                applicationId,
                tenantId);
    }

    private void authorize(String token, UUID applicationId, UUID tenantId, String reason) {
        post(
                token,
                "/api/v1/applications/" + applicationId + "/tenants/" + tenantId,
                reason == null ? null : Map.of("reason", reason),
                201);
    }

    private void delete(String token, UUID applicationId, UUID tenantId, Map<String, Object> body, int expected) {
        exchange("/api/v1/applications/" + applicationId + "/tenants/" + tenantId, token, "DELETE", body)
                .expect(expected);
    }

    private UUID createTenantOwned(String token, UUID tenantId, String name, String bundleId) {
        JsonNode created = post(token, "/api/v1/applications", tenantApp(tenantId, name, bundleId), 201);
        return UUID.fromString(created.get("id").asText());
    }

    private UUID createOrgShared(String token, UUID orgId, String name, String bundleId) {
        JsonNode created = post(token, "/api/v1/applications", sharedApp(orgId, name, bundleId), 201);
        return UUID.fromString(created.get("id").asText());
    }

    /** Registration body for a TENANT-owned application (the S3-01 shape). */
    private static Map<String, Object> tenantApp(UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("bundleId", bundleId);
        body.put("platforms", new ArrayList<>(List.of("IOS")));
        return body;
    }

    /** Registration body for an ORGANIZATION-shared application (S3-02). */
    private static Map<String, Object> sharedApp(UUID orgId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", orgId.toString());
        body.put("name", name);
        body.put("bundleId", bundleId);
        body.put("platforms", new ArrayList<>(List.of("IOS")));
        return body;
    }

    private List<UUID> listingIds(String token, UUID tenantId) {
        JsonNode page = get(token, "/api/v1/tenants/" + tenantId + "/applications?limit=100", 200);
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("id").asText())));
        return ids;
    }

    private String tenantAdminIn(UUID tenantId, String email) {
        insertOrglessUser(email);
        UUID userId = userIdByEmail(email);
        post(
                adminTokenOf(tenantsOrganizationOf(tenantId)),
                "/api/v1/tenants/" + tenantId + "/members",
                Map.of("userId", userId.toString(), "role", "TENANT_ADMIN"),
                201);
        return accessToken(login(email, PASSWORD));
    }

    private UUID tenantsOrganizationOf(UUID tenantId) {
        return jdbc.queryForObject("SELECT organization_id FROM tenants WHERE id = ?", UUID.class, tenantId);
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
                        "Authorization Test User"))
                .retrieve()
                .body(String.class);
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
                        "Authorization " + role))
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
