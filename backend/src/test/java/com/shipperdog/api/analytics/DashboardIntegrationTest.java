package com.shipperdog.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * End-to-end acceptance flow for the dashboards (issue #50, S11-05,
 * PRD 29): the organization dashboard's blocks reconciling with the
 * list endpoints (AC1), the release-health derivation rule (AC2),
 * the role-filtered pending actions with their deep-link context
 * (AC3), the tenant dashboard's isolation and counts (AC4), and the
 * bounded-read discipline (AC5 — a fixed handful of aggregates, no
 * N+1: the loose wall-clock guard catches the pathological shapes the
 * design forbids). Lifecycle facts the derivation reads (release
 * states, occupancy, approval rounds, gates, holds) are written
 * directly, the S11-04 suite's direct-facts precedent: what is under
 * test here is the READ MODEL, not the state machine that produces
 * the facts (its own suites pin those).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.analytics.rollup.enabled=false"
        })
@Testcontainers
class DashboardIntegrationTest {

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
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static final class World {
        UUID org;
        String adminToken;
        String ownerToken;
        String managerToken;
        String viewerToken;
        String qaToken;
        String securityToken;
        String developerToken;
        String tenantAdminToken;
        String tenantBAdminToken;
        UUID tenantA;
        UUID tenantB;
        UUID app;
        UUID version;
        UUID releaseId;
        UUID testerId;
    }

    /** An organization, two tenants, a shared application authorized to both, one DRAFT release targeting both. */
    private World world(String slug) throws Exception {
        World world = new World();
        world.org = createOrg("Dash " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.viewerToken = loginOfOrgRoleIn(world.org, slug + "-viewer", "VIEWER");
        world.qaToken = loginOfOrgRoleIn(world.org, slug + "-qa", "QA_ENGINEER");
        world.securityToken = loginOfOrgRoleIn(world.org, slug + "-security", "SECURITY_REVIEWER");
        world.developerToken = loginOfOrgRoleIn(world.org, slug + "-developer", "DEVELOPER");
        world.tenantA = createTenant(world.adminToken, world.org, "Dash " + slug + " Bank", "dash-" + slug + "-a");
        world.tenantB = createTenant(world.adminToken, world.org, "Dash " + slug + " Bank B", "dash-" + slug + "-b");
        world.tenantAdminToken = loginOfTenantAdminRoleIn(world.org, world.tenantA, slug + "-tenant-admin");
        world.tenantBAdminToken = loginOfTenantAdminRoleIn(world.org, world.tenantB, slug + "-tenant-b-admin");
        world.app = createSharedApp(
                world.adminToken, world.org, "Dash " + slug + " Banking", "com." + slug.replace("-", "") + ".banking");
        world.version = createVersion(world.ownerToken, world.app, "1.0.0");
        UUID iosArtifact = insertArtifact(world.version, "IOS", 1);
        authorize(world.adminToken, world.app, world.tenantA);
        authorize(world.adminToken, world.app, world.tenantB);
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("applicationId", world.app.toString());
        release.put("versionId", world.version.toString());
        release.put("ring", "qa");
        release.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", iosArtifact.toString())));
        release.put(
                "targets",
                List.of(
                        Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()),
                        Map.of("tenantId", world.tenantB.toString(), "testerGroupIds", List.of())));
        JsonNode created = post(world.managerToken, "/api/v1/releases", release, 201);
        world.releaseId = UUID.fromString(created.get("id").asText());
        world.testerId = linkedTester(world, slug + "-tester");
        return world;
    }

    // -----------------------------------------------------------------------
    // AC1: the counts block, reconciling with the list endpoints
    // -----------------------------------------------------------------------

    @Test
    void orgCountsReconcileWithListEndpoints() throws Exception {
        World world = world("reconcile");
        // A second application (tenant-owned this time) and a third
        // tenant: the counts must see both ownership scopes and every
        // tenant of the organization, exactly as the listings do.
        UUID tenantOwned = createTenantOwnedApp(world, "Dash Reconcile Ledger", "com.dashreconcile.ledger");
        createTenant(world.adminToken, world.org, "Dash Reconcile Bank C", "dash-reconcile-c");

        JsonNode dashboard = get(world.adminToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        JsonNode counts = dashboard.get("counts");

        assertThat(counts.get("applications").asInt())
                .isEqualTo(listTotal(world.adminToken, "/api/v1/applications?organizationId=" + world.org));
        assertThat(counts.get("tenants").asInt())
                .isEqualTo(listTotal(world.adminToken, "/api/v1/tenants?organizationId=" + world.org));
        assertThat(counts.get("activeReleases").asInt())
                .isEqualTo(releaseListingCount(
                        world, "DRAFT,VALIDATING,READY,APPROVAL_PENDING,APPROVED,DEPLOYING,ACTIVE,PROMOTED"));
        assertThat(counts.get("pendingApprovals").asInt()).isEqualTo(0);
        assertThat(counts.get("testing").asInt()).isEqualTo(0);
        assertThat(counts.get("production").asInt()).isEqualTo(0);
        assertThat(dashboard.get("organizationId").asText()).isEqualTo(world.org.toString());
        assertThat(tenantOwned).isNotNull();
    }

    @Test
    void statusCountsFollowTheReleaseStates() throws Exception {
        World world = world("states");
        setReleaseStatus(world.releaseId, "APPROVAL_PENDING");
        JsonNode dashboard = get(world.adminToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        assertThat(dashboard.get("counts").get("pendingApprovals").asInt()).isEqualTo(1);
        assertThat(dashboard.get("counts").get("activeReleases").asInt()).isEqualTo(1);

        setReleaseStatus(world.releaseId, "ACTIVE");
        dashboard = get(world.adminToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        // The release occupies the qa ring (position 1) for both
        // targets: in distribution, not yet at the final ring.
        assertThat(dashboard.get("counts").get("testing").asInt()).isEqualTo(1);
        assertThat(dashboard.get("counts").get("production").asInt()).isEqualTo(0);

        moveTargetsToRing(world.releaseId, "production");
        dashboard = get(world.adminToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        assertThat(dashboard.get("counts").get("testing").asInt()).isEqualTo(0);
        assertThat(dashboard.get("counts").get("production").asInt()).isEqualTo(1);

        setReleaseStatus(world.releaseId, "COMPLETED");
        dashboard = get(world.adminToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        assertThat(dashboard.get("counts").get("activeReleases").asInt()).isEqualTo(0);
        assertThat(dashboard.get("counts").get("production").asInt()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // AC2: the health derivation rule
    // -----------------------------------------------------------------------

    @Test
    void healthDerivesBlockedTestingHealthyInactive() throws Exception {
        World world = world("health");
        UUID appB = createSharedApp(world.adminToken, world.org, "Dash Health Payments", "com.dashhealth.payments");
        UUID appC = createSharedApp(world.adminToken, world.org, "Dash Health Loans", "com.dashhealth.loans");
        authorize(world.adminToken, appB, world.tenantA);
        authorize(world.adminToken, appB, world.tenantB);
        authorize(world.adminToken, appC, world.tenantA);

        // A draft-only application carries no in-flight release: INACTIVE.
        assertThat(healthOf(world.adminToken, world.org, world.app)).isEqualTo("INACTIVE");

        // The world's release goes ACTIVE at the qa ring: TESTING.
        setReleaseStatus(world.releaseId, "ACTIVE");
        assertThat(healthOf(world.adminToken, world.org, world.app)).isEqualTo("TESTING");

        // Every target reaches the final ring: HEALTHY (production-active).
        moveTargetsToRing(world.releaseId, "production");
        assertThat(healthOf(world.adminToken, world.org, world.app)).isEqualTo("HEALTHY");

        // A pending policy hold blocks the application regardless of health.
        insertPendingHold(world.releaseId);
        JsonNode row = healthRowOf(world.adminToken, world.org, world.app);
        assertThat(row.get("health").asText()).isEqualTo("BLOCKED");
        assertThat(row.get("blockedFindings").asInt()).isEqualTo(1);
        clearHolds(world.releaseId);
        assertThat(healthOf(world.adminToken, world.org, world.app)).isEqualTo("HEALTHY");

        // A failed, un-overridden gate blocks; the governed override
        // (reason and moment recorded — the shape check) clears it.
        UUID gate = insertFailedGate(world.releaseId, "SECURITY_SCAN");
        assertThat(healthOf(world.adminToken, world.org, world.app)).isEqualTo("BLOCKED");
        jdbc.update(
                "UPDATE release_gates SET overridden_by = ?, overridden_reason = 'fixture override', "
                        + "overridden_at = now() WHERE id = ?",
                userIdOf(world.adminToken),
                gate);
        assertThat(healthOf(world.adminToken, world.org, world.app)).isEqualTo("HEALTHY");

        // A FAILED release state blocks.
        setReleaseStatus(world.releaseId, "FAILED");
        assertThat(healthOf(world.adminToken, world.org, world.app)).isEqualTo("BLOCKED");

        // Blocked outranks Testing: app B holds a testing release AND
        // a failed one — Blocked wins (attention goes where needed).
        // (One release per application and version — each app needs
        // its own version rows.)
        UUID versionB = createVersion(world.ownerToken, appB, "1.0.0");
        UUID releaseB = createDraftRelease(world.managerToken, appB, versionB, world.tenantA);
        setReleaseStatus(releaseB, "ACTIVE");
        UUID versionB2 = createVersion(world.ownerToken, appB, "1.0.1");
        UUID releaseB2 = createDraftRelease(world.managerToken, appB, versionB2, world.tenantB);
        setReleaseStatus(releaseB2, "FAILED");
        assertThat(healthOf(world.adminToken, world.org, appB)).isEqualTo("BLOCKED");

        // And app C, with releases in no block-worthy shape, stays INACTIVE.
        assertThat(healthOf(world.adminToken, world.org, appC)).isEqualTo("INACTIVE");
    }

    // -----------------------------------------------------------------------
    // AC3: pending actions, role-filtered, with deep-link context
    // -----------------------------------------------------------------------

    @Test
    void pendingActionsAreRoleFilteredAndActionable() throws Exception {
        World world = world("actions");
        setReleaseStatus(world.releaseId, "APPROVAL_PENDING");
        insertApprovalSteps(world.releaseId, userIdOf(world.managerToken), List.of("QA", "SECURITY", "PRODUCT"), "ALL");
        insertFailedGate(world.releaseId, "AUTOMATED_TESTS");

        // The QA persona: their own decidable step, no security queue,
        // no failed-gate queue (not their authority).
        JsonNode qa = get(world.qaToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        JsonNode qaApprovals = qa.get("pendingActions").get("approvals");
        assertThat(qaApprovals.size()).isEqualTo(1);
        assertThat(qaApprovals.get(0).get("approvalType").asText()).isEqualTo("QA");
        assertThat(qaApprovals.get(0).get("decidableByCaller").asBoolean()).isTrue();
        assertThat(qaApprovals.get(0).get("releaseRef").asText()).startsWith("R-");
        assertThat(qaApprovals.get(0).get("applicationId").asText()).isEqualTo(world.app.toString());
        assertThat(qaApprovals.get(0).get("applicationName").asText()).contains("Banking");
        assertThat(qaApprovals.get(0).get("roundNumber").asInt()).isEqualTo(1);
        assertThat(qa.get("pendingActions").get("reviews").size()).isZero();
        assertThat(qa.get("pendingActions").get("failedGates").size()).isZero();

        // The security persona: their SECURITY step in the approvals
        // queue AND the same step in the reviews queue.
        JsonNode security = get(world.securityToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        JsonNode secApprovals = security.get("pendingActions").get("approvals");
        assertThat(secApprovals.size()).isEqualTo(1);
        assertThat(secApprovals.get(0).get("approvalType").asText()).isEqualTo("SECURITY");
        JsonNode reviews = security.get("pendingActions").get("reviews");
        assertThat(reviews.size()).isEqualTo(1);
        assertThat(reviews.get(0).get("approvalType").asText()).isEqualTo("SECURITY");

        // The release manager created the release themselves: their
        // own RELEASE_MANAGER step would be blocked by the
        // self-approval guard — and this round carries none, so their
        // queue is empty; they DO see the failed gate.
        JsonNode manager = get(world.managerToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        assertThat(manager.get("pendingActions").get("approvals").size()).isZero();
        JsonNode failedGates = manager.get("pendingActions").get("failedGates");
        assertThat(failedGates.size()).isEqualTo(1);
        assertThat(failedGates.get(0).get("gateType").asText()).isEqualTo("AUTOMATED_TESTS");
        assertThat(failedGates.get(0).get("releaseRef").asText()).startsWith("R-");
        assertThat(failedGates.get(0).get("applicationId").asText()).isEqualTo(world.app.toString());

        // The organization administrator sees the whole queue
        // (informational) and every restricted category, with the
        // decidable flag honestly false everywhere (an administrator
        // signs nothing).
        JsonNode admin = get(world.adminToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        JsonNode adminApprovals = admin.get("pendingActions").get("approvals");
        assertThat(adminApprovals.size()).isEqualTo(3);
        for (JsonNode step : adminApprovals) {
            assertThat(step.get("decidableByCaller").asBoolean()).isFalse();
        }
        assertThat(admin.get("pendingActions").get("reviews").size()).isEqualTo(1);
        assertThat(admin.get("pendingActions").get("failedGates").size()).isEqualTo(1);

        // The viewer sees counts and health only.
        JsonNode viewer = get(world.viewerToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        assertThat(viewer.get("pendingActions").get("approvals").size()).isZero();
        assertThat(viewer.get("pendingActions").get("reviews").size()).isZero();
        assertThat(viewer.get("pendingActions").get("failedGates").size()).isZero();
    }

    @Test
    void approvalStepsFollowTheActiveGroupOnly() throws Exception {
        World world = world("groups");
        setReleaseStatus(world.releaseId, "APPROVAL_PENDING");
        // Group 1 (ALL, QA) fully decided APPROVED; group 2 (ALL,
        // SECURITY + PRODUCT) is the active one; group 3 must not
        // surface while group 2 runs (sequential groups).
        UUID round = insertRound(world.releaseId, 1, userIdOf(world.managerToken));
        insertStep(world.releaseId, round, 1, "QA", "APPROVED", 1, "ALL");
        insertStep(world.releaseId, round, 2, "SECURITY", "PENDING", 2, "ALL");
        insertStep(world.releaseId, round, 3, "PRODUCT", "PENDING", 2, "ALL");
        insertStep(world.releaseId, round, 4, "RELEASE_MANAGER", "PENDING", 3, "ALL");

        JsonNode admin = get(world.adminToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        JsonNode approvals = admin.get("pendingActions").get("approvals");
        assertThat(approvals.size()).isEqualTo(2);
        List<String> types = new ArrayList<>();
        for (JsonNode step : approvals) {
            types.add(step.get("approvalType").asText());
        }
        types.sort(String::compareTo);
        assertThat(types).containsExactly("PRODUCT", "SECURITY");
    }

    @Test
    void satisfiedAnyGroupStopsSurfacingItsSiblings() throws Exception {
        World world = world("anymode");
        setReleaseStatus(world.releaseId, "APPROVAL_PENDING");
        // Group 1 is an ANY group: one APPROVED signature satisfies
        // it, so its still-PENDING sibling is NOT a waiting step —
        // the queue moves to group 2's step (the evaluator's ANY
        // rule, mirrored by the read model's active-group SQL).
        UUID round = insertRound(world.releaseId, 1, userIdOf(world.managerToken));
        insertStep(world.releaseId, round, 1, "QA", "APPROVED", 1, "ANY");
        insertStep(world.releaseId, round, 2, "SECURITY", "PENDING", 1, "ANY");
        insertStep(world.releaseId, round, 3, "PRODUCT", "PENDING", 2, "ALL");

        JsonNode admin = get(world.adminToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        JsonNode approvals = admin.get("pendingActions").get("approvals");
        assertThat(approvals.size()).isEqualTo(1);
        assertThat(approvals.get(0).get("approvalType").asText()).isEqualTo("PRODUCT");
    }

    // -----------------------------------------------------------------------
    // AC4: the tenant dashboard, isolated by the route's tenant
    // -----------------------------------------------------------------------

    @Test
    void tenantDashboardServesIsolatedCounts() throws Exception {
        World world = world("tenant");
        // Two more linked testers on tenant A (one suspended: the
        // effective view excludes them) and a tenant-owned second app.
        linkedTester(world, "tenant-extra-1");
        UUID suspended = linkedTester(world, "tenant-extra-2");
        jdbc.update(
                "UPDATE tenant_tester_memberships SET status = 'SUSPENDED' WHERE tenant_id = ? AND tester_id = ?",
                world.tenantA,
                suspended);
        UUID ledger = createTenantOwnedApp(world, "Dash Tenant Ledger", "com.dashtenant.ledger");
        createVersion(world.ownerToken, ledger, "2.0.0");
        createVersion(world.ownerToken, world.app, "1.1.0");

        JsonNode dashboard = get(world.tenantAdminToken, "/api/v1/tenants/" + world.tenantA + "/dashboard", 200);
        // The catalog union: the shared authorized app plus the
        // tenant-owned ledger.
        assertThat(dashboard.get("counts").get("applications").asInt()).isEqualTo(2);
        // The working set of both applications (1.0.0 + 1.1.0 of the
        // shared app, 1.0.0 of the ledger).
        assertThat(dashboard.get("counts").get("availableVersions").asInt()).isEqualTo(3);
        // Two effective testers: the suspended membership excludes.
        assertThat(dashboard.get("counts").get("activeTesters").asInt()).isEqualTo(2);
        // The DRAFT release targeting this tenant is in flight.
        assertThat(dashboard.get("counts").get("pendingReleases").asInt()).isEqualTo(1);
        JsonNode pending = dashboard.get("pendingReleases");
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.get(0).get("status").asText()).isEqualTo("DRAFT");
        assertThat(pending.get(0).get("ring").asText()).isEqualTo("testing");

        // Tenant B's own view: the same authorized shared app, no
        // owned ledger, one tester-free world.
        JsonNode b = get(world.tenantBAdminToken, "/api/v1/tenants/" + world.tenantB + "/dashboard", 200);
        assertThat(b.get("counts").get("applications").asInt()).isEqualTo(1);
        assertThat(b.get("counts").get("activeTesters").asInt()).isEqualTo(0);

        // A tenant admin cannot read a sibling tenant's dashboard even
        // inside the same organization: the concealed miss.
        get(world.tenantAdminToken, "/api/v1/tenants/" + world.tenantB + "/dashboard", 404);
        get(world.tenantAdminToken, "/api/v1/tenants/" + UUID.randomUUID() + "/dashboard", 404);
    }

    @Test
    void tenantProductionOccupancyLabelsTheRing() throws Exception {
        World world = world("label");
        setReleaseStatus(world.releaseId, "ACTIVE");
        // The starting qa ring holds tenant A; tenant B's target hops
        // to production — the same release is testing for one tenant
        // and production for the other, per-target occupancy.
        jdbc.update(
                "UPDATE release_targets SET ring_id = rings.id FROM rings WHERE release_id = ? "
                        + "AND rings.code = 'production' AND tenant_id = ?",
                world.releaseId,
                world.tenantB);
        JsonNode dashboard = get(world.tenantAdminToken, "/api/v1/tenants/" + world.tenantA + "/dashboard", 200);
        JsonNode pending = dashboard.get("pendingReleases");
        assertThat(pending.get(0).get("ring").asText()).isEqualTo("testing");
        JsonNode b = get(world.tenantBAdminToken, "/api/v1/tenants/" + world.tenantB + "/dashboard", 200);
        assertThat(b.get("pendingReleases").get(0).get("ring").asText()).isEqualTo("production");
    }

    // -----------------------------------------------------------------------
    // AC5: the bounded read
    // -----------------------------------------------------------------------

    @Test
    void dashboardReadsStayInsideTheLatencyBudget() throws Exception {
        World world = world("latency");
        setReleaseStatus(world.releaseId, "APPROVAL_PENDING");
        insertApprovalSteps(world.releaseId, userIdOf(world.managerToken), List.of("QA"), "ALL");
        // A cold read and ten warm reads, all inside the p95 budget
        // the issue names: the aggregate design's guard (a per-row
        // scan over the catalog would blow past this long before a
        // real deployment's data volumes would).
        for (int i = 0; i < 10; i++) {
            long started = System.nanoTime();
            get(world.adminToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
            get(world.tenantAdminToken, "/api/v1/tenants/" + world.tenantA + "/dashboard", 200);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        }
    }

    // -----------------------------------------------------------------------
    // Permission edges
    // -----------------------------------------------------------------------

    @Test
    void dashboardPermissionsFollowTheMatrix() throws Exception {
        World world = world("perms");
        // Every functional console role reads the organization
        // dashboard; the developer (the CLI persona without registry
        // read) and a plain tenant administrator do not — concealed.
        get(world.viewerToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        get(world.qaToken, "/api/v1/organizations/" + world.org + "/dashboard", 200);
        get(world.developerToken, "/api/v1/organizations/" + world.org + "/dashboard", 404);
        get(world.tenantAdminToken, "/api/v1/organizations/" + world.org + "/dashboard", 404);
        get(world.adminToken, "/api/v1/organizations/" + UUID.randomUUID() + "/dashboard", 404);
        // The organization's overlay carries the tenant dashboard to
        // the organization administrator; the developer holds no
        // tenant grain either.
        get(world.adminToken, "/api/v1/tenants/" + world.tenantA + "/dashboard", 200);
        get(world.developerToken, "/api/v1/tenants/" + world.tenantA + "/dashboard", 404);
        get(world.tenantAdminToken, "/api/v1/tenants/" + UUID.randomUUID() + "/dashboard", 404);
    }

    // -----------------------------------------------------------------------
    // Fact writers (the S11-04 direct-facts precedent)
    // -----------------------------------------------------------------------

    private void setReleaseStatus(UUID releaseId, String status) {
        jdbc.update(
                "UPDATE releases SET status = ?::release_status, updated_at = now() WHERE id = ?", status, releaseId);
    }

    private void moveTargetsToRing(UUID releaseId, String ringCode) {
        jdbc.update(
                "UPDATE release_targets t SET ring_id = ("
                        + "  SELECT o.id FROM rings o WHERE o.code = ? "
                        + "  ORDER BY o.organization_id = (SELECT organization_id FROM releases WHERE id = ?) DESC, "
                        + "           o.organization_id NULLS LAST LIMIT 1) "
                        + "WHERE t.release_id = ?",
                ringCode,
                releaseId,
                releaseId);
    }

    private UUID insertFailedGate(UUID releaseId, String gateType) {
        UUID gate = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO release_gates (id, release_id, gate_type, status, evidence_url, evaluated_at, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'FAILED', NULL, now(), now(), now())",
                gate,
                releaseId,
                gateType);
        return gate;
    }

    private void insertPendingHold(UUID releaseId) {
        UUID org = jdbc.queryForObject("SELECT organization_id FROM releases WHERE id = ?", UUID.class, releaseId);
        // A standing HOLD rule the fired hold references (the FK's own
        // shape; the rule store's own suite pins its API).
        UUID rule = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO release_policy_rules (id, organization_id, name, scope, action, condition, position, "
                        + "enabled, created_at, updated_at) VALUES (?, ?, 'dash-fixture-hold', 'ORGANIZATION', "
                        + "'HOLD_FOR_REVIEW', '{}'::jsonb, 1, true, now(), now())",
                rule,
                org);
        jdbc.update(
                "INSERT INTO release_policy_holds (id, release_id, rule_id, organization_id, ring, status, evidence, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'qa', 'PENDING', '{}'::jsonb, now(), now())",
                UUID.randomUUID(),
                releaseId,
                rule,
                org);
    }

    private void clearHolds(UUID releaseId) {
        jdbc.update("DELETE FROM release_policy_holds WHERE release_id = ?", releaseId);
    }

    private UUID insertRound(UUID releaseId, int roundNumber, UUID createdBy) {
        UUID workflow = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO approval_workflows (id, organization_id, name, created_by) VALUES (?, ?, ?, ?)",
                workflow,
                jdbc.queryForObject("SELECT organization_id FROM releases WHERE id = ?", UUID.class, releaseId),
                "dash-fixture-chain",
                createdBy);
        UUID round = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO release_approval_rounds (id, release_id, round_number, workflow_id, status, "
                        + "created_by, created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', ?, now(), now())",
                round,
                releaseId,
                roundNumber,
                workflow,
                createdBy);
        return round;
    }

    private void insertStep(
            UUID releaseId,
            UUID roundId,
            int position,
            String approvalType,
            String status,
            int groupPosition,
            String groupMode) {
        // The decision-shape check: a decided row carries its moment,
        // its approver, and the role they signed under (and the group
        // vocabulary starts at 1 — the ck constraints).
        if ("PENDING".equals(status)) {
            jdbc.update(
                    "INSERT INTO release_approvals (id, release_id, round_id, position, approval_type, status, "
                            + "group_position, group_mode, created_at, updated_at) VALUES (?, ?, ?, ?, "
                            + "?::approval_type, 'PENDING', ?, ?::approval_group_mode, now(), now())",
                    UUID.randomUUID(),
                    releaseId,
                    roundId,
                    position,
                    approvalType,
                    groupPosition,
                    groupMode);
        } else {
            jdbc.update(
                    "INSERT INTO release_approvals (id, release_id, round_id, position, approval_type, status, "
                            + "group_position, group_mode, approver, approver_role, decided_at, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?::approval_type, ?::approval_status, ?, ?::approval_group_mode, "
                            + "(SELECT created_by FROM release_approval_rounds WHERE id = ?), "
                            + "(SELECT CASE ? WHEN 'QA' THEN 'QA_ENGINEER' WHEN 'SECURITY' THEN 'SECURITY_REVIEWER' "
                            + "WHEN 'COMPLIANCE' THEN 'COMPLIANCE_REVIEWER' WHEN 'PRODUCT' THEN 'APP_OWNER' "
                            + "ELSE 'RELEASE_MANAGER' END::approval_step_role), now(), now(), now())",
                    UUID.randomUUID(),
                    releaseId,
                    roundId,
                    position,
                    approvalType,
                    status,
                    groupPosition,
                    groupMode,
                    roundId,
                    approvalType);
        }
    }

    /** One ALL-group PENDING round carrying the named step types, created by {@code createdBy}. */
    private void insertApprovalSteps(UUID releaseId, UUID createdBy, List<String> types, String groupMode) {
        UUID round = insertRound(releaseId, 1, createdBy);
        int position = 1;
        for (String type : types) {
            insertStep(releaseId, round, position++, type, "PENDING", 1, groupMode);
        }
    }

    private UUID createDraftRelease(String token, UUID applicationId, UUID versionId, UUID tenantId) {
        UUID artifact = insertArtifact(versionId, "IOS", System.nanoTime() % 100000);
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("applicationId", applicationId.toString());
        release.put("versionId", versionId.toString());
        release.put("ring", "qa");
        release.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        release.put("targets", List.of(Map.of("tenantId", tenantId.toString(), "testerGroupIds", List.of())));
        return UUID.fromString(
                post(token, "/api/v1/releases", release, 201).get("id").asText());
    }

    private UUID createTenantOwnedApp(World world, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "TENANT");
        body.put("tenantId", world.tenantA.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS"));
        body.put("bundleId", bundleId);
        return UUID.fromString(post(world.adminToken, "/api/v1/applications", body, 201)
                .get("id")
                .asText());
    }

    private String healthOf(String token, UUID org, UUID applicationId) {
        return healthRowOf(token, org, applicationId).get("health").asText();
    }

    private JsonNode healthRowOf(String token, UUID org, UUID applicationId) {
        JsonNode dashboard = get(token, "/api/v1/organizations/" + org + "/dashboard", 200);
        for (JsonNode row : dashboard.get("applications")) {
            if (applicationId.toString().equals(row.get("applicationId").asText())) {
                return row;
            }
        }
        throw new IllegalStateException("application row not found");
    }

    /** Walks a cursor-paginated listing to its total item count (the reconciliation source of truth). */
    private int listTotal(String token, String uri) {
        int total = 0;
        String cursor = null;
        do {
            StringBuilder page = new StringBuilder(uri);
            page.append(uri.contains("?") ? '&' : '?').append("limit=100");
            if (cursor != null) {
                page.append("&cursor=").append(cursor);
            }
            JsonNode body = get(token, page.toString(), 200);
            total += body.get("items").size();
            cursor = body.hasNonNull("next_cursor") ? body.get("next_cursor").asText() : null;
        } while (cursor != null);
        return total;
    }

    /** The release listing's count over a status set (the query-param-addressed listing, walked to its end). */
    private int releaseListingCount(World world, String statuses) {
        int total = 0;
        for (String status : statuses.split(",")) {
            String cursor = null;
            do {
                StringBuilder uri = new StringBuilder(
                        "/api/v1/releases?applicationId=" + world.app + "&limit=100&status=" + status);
                if (cursor != null) {
                    uri.append("&cursor=").append(cursor);
                }
                JsonNode body = get(world.adminToken, uri.toString(), 200);
                total += body.get("items").size();
                cursor =
                        body.hasNonNull("next_cursor") ? body.get("next_cursor").asText() : null;
            } while (cursor != null);
        }
        return total;
    }

    // -----------------------------------------------------------------------
    // HTTP helpers (the analytics suite's shapes)
    // -----------------------------------------------------------------------

    private UUID insertArtifact(UUID versionId, String platform, long build) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        String extension = "ANDROID".equals(platform) ? "apk" : "ipa";
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, ?::artifact_status, ?, ?)",
                artifactId,
                versionId,
                platform,
                platform.toLowerCase(java.util.Locale.ROOT) + "-" + build + "." + extension,
                "fixture/" + artifactId + "/artifact",
                "a1".repeat(32),
                1024L,
                build,
                "READY",
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        Exchange result = client.get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .exchange((request, response) -> new Exchange(
                        response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(result.status()).as("GET %s -> %d", uri, expectedStatus).isEqualTo(expectedStatus);
        try {
            return mapper.readTree(result.raw() == null || result.raw().isBlank() ? "{}" : result.raw());
        } catch (Exception e) {
            throw new IllegalStateException("could not decode the exchange body", e);
        }
    }

    private record Exchange(int status, String raw) {}

    private JsonNode post(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        record PostResult(int status, String raw) {}

        PostResult result = client.post()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(bearer))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> new PostResult(
                        response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(result.status())
                .as("POST %s -> %d, body=%s", uri, expectedStatus, result.raw())
                .isEqualTo(expectedStatus);
        try {
            return mapper.readTree(result.raw() == null || result.raw().isBlank() ? "{}" : result.raw());
        } catch (Exception e) {
            throw new IllegalStateException("could not decode the exchange body", e);
        }
    }

    private UUID createVersion(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", versionName),
                        201)
                .get("id")
                .asText());
    }

    private UUID createSharedApp(String token, UUID organizationId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private void authorize(String token, UUID applicationId, UUID tenantId) {
        post(token, "/api/v1/applications/" + applicationId + "/tenants/" + tenantId, Map.of(), 201);
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/tenants",
                        Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                        201)
                .get("id")
                .asText());
    }

    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    private String loginOfOrgRoleIn(UUID orgId, String slug, String role) {
        String email = slug + "@example.com";
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
                        "Dash " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    private String loginOfTenantAdminRoleIn(UUID orgId, UUID tenantId, String slug) {
        String email = slug + "@example.com";
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        JsonNode invitation = post(
                devToken, "/api/v1/auth/invitations", Map.of("email", email, "organizationId", orgId.toString()), 201);
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
                        "Dash Tenant Admin"))
                .retrieve()
                .body(String.class);
        String userToken = accessToken(login(email, PASSWORD));
        String orgAdminToken = loginOfOrgRoleIn(orgId, slug + "-orgadmin", "ORG_ADMIN");
        post(
                orgAdminToken,
                "/api/v1/tenants/" + tenantId + "/members",
                Map.of("userId", userIdOf(userToken).toString(), "role", "TENANT_ADMIN"),
                201);
        return userToken;
    }

    /** Invites, registers, and tenant-links one tester; returns their tester id. */
    private UUID linkedTester(World world, String slug) {
        String email = slug + "@example.com";
        JsonNode invited = post(
                world.adminToken,
                "/api/v1/testers",
                Map.of("organizationId", world.org.toString(), "email", email, "fullName", "Dash Tester " + slug),
                201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invited.get("invitation").get("invitationToken").asString(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Dash Tester " + slug))
                .retrieve()
                .body(String.class);
        post(
                world.adminToken,
                "/api/v1/tenants/" + world.tenantA + "/testers",
                Map.of("testerId", invited.get("id").asText()),
                201);
        return UUID.fromString(invited.get("id").asText());
    }

    private UUID userIdOf(String bearer) {
        String claims = bearer.split("\\.")[1];
        try {
            return UUID.fromString(mapper.readTree(Base64.getUrlDecoder().decode(claims))
                    .get("sub")
                    .asText());
        } catch (Exception e) {
            throw new IllegalStateException("could not decode the token", e);
        }
    }

    private String login(String email, String password) {
        return client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", password))
                .retrieve()
                .body(String.class);
    }

    private String accessToken(String loginResponse) {
        try {
            return mapper.readTree(loginResponse).get("accessToken").asString();
        } catch (Exception e) {
            throw new IllegalStateException("could not decode the login response", e);
        }
    }
}
