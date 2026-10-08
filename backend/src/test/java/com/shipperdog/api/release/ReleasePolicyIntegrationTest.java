package com.shipperdog.api.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
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
 * End-to-end acceptance flow for the declarative policy engine (issue
 * #45, S10-06, PRD 33, FR-026's fail-closed ethos): the §33 examples
 * authorable and correct (AC1 — every rule over the metric, gate,
 * approval, and artifact inputs), BLOCK's 409 with the rule reference
 * and HOLD's explicit privileged acknowledge (AC2), the side-effect
 * free dry-run (AC3), the fail-closed NO_DATA outcome of missing
 * metric data (AC4), the defined evaluation order with the first
 * BLOCK winning and every evaluation logged per attempt (AC5), the
 * CRUD validation the console editor renders (AC6), the
 * skip-relaxation knob the promotion seam reserved, and the scoping
 * dimensions (org, application, ring, environment).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import({AuditCapture.class, ReleasePolicyIntegrationTest.TestMetricSource.class})
class ReleasePolicyIntegrationTest {

    /**
     * The analytics stand-in: the single registrant of the policy
     * metric seam S11-04's feeds will own in production. Values are
     * armed per release; an unarmed metric answers empty — the
     * fail-closed NO_DATA case AC4 pins.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TestMetricSource implements ReleasePolicyMetricSource {

        static final Map<UUID, Map<PolicyMetric, Double>> VALUES = new ConcurrentHashMap<>();

        static void arm(UUID releaseId, PolicyMetric metric, Double value) {
            VALUES.computeIfAbsent(releaseId, key -> new ConcurrentHashMap<>()).put(metric, value);
        }

        @Override
        public Optional<Double> metricOf(UUID releaseId, PolicyMetric metric) {
            return Optional.ofNullable(VALUES.getOrDefault(releaseId, Map.of()).get(metric));
        }
    }

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

    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        versionCounter = 300 + (int) (Math.random() * 100);
    }

    // -----------------------------------------------------------------------
    // AC1: the §33 examples authorable and correct
    // -----------------------------------------------------------------------

    @Test
    void criticalVulnerabilitiesRuleBlocksThePromotionWithTheRuleReference() {
        World world = world("pol-vulns");
        Release release = activeReleaseAt(world, "qa", "pol-vulns");
        createRule(
                world.adminToken,
                world.org,
                "no-critical-vulns",
                "ORGANIZATION",
                "BLOCK",
                10,
                metric(PolicyMetric.CRITICAL_VULNERABILITIES, ">", 0),
                201);
        TestMetricSource.arm(release.id, PolicyMetric.CRITICAL_VULNERABILITIES, 3.0);

        JsonNode blocked = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(blocked.get("errors").get("ReleasePolicyEngine").asText())
                .contains("no-critical-vulns")
                .contains("BLOCK")
                .contains("3.0");

        // A clean release promotes: the rule not met answers nothing.
        Release clean = activeReleaseAt(world, "qa", "pol-vulns-clean");
        TestMetricSource.arm(clean.id, PolicyMetric.CRITICAL_VULNERABILITIES, 0.0);
        assertThat(promote(world.managerToken, world.app, clean.id, null, null, 200)
                        .get("toRing")
                        .asText())
                .isEqualTo("tenant-pilot");
    }

    @Test
    void crashFreeFloorRuleBlocksBelowThreshold() {
        World world = world("pol-crashfree");
        Release release = activeReleaseAt(world, "qa", "pol-crashfree");
        createRule(
                world.adminToken,
                world.org,
                "crash-free-floor",
                "ORGANIZATION",
                "BLOCK",
                10,
                metric(PolicyMetric.CRASH_FREE_RATE, "<", 0.99),
                201);
        TestMetricSource.arm(release.id, PolicyMetric.CRASH_FREE_RATE, 0.9714);

        JsonNode blocked = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(blocked.get("errors").get("ReleasePolicyEngine").asText())
                .contains("crash-free-floor")
                .contains("0.9714");
    }

    @Test
    void lowInstallSuccessOrEngagementHoldsForReview() {
        World world = world("pol-hold");
        Release release = activeReleaseAt(world, "qa", "pol-hold");
        createRule(
                world.adminToken,
                world.org,
                "engagement-floor",
                "ORGANIZATION",
                "HOLD_FOR_REVIEW",
                20,
                anyOf(
                        metric(PolicyMetric.INSTALL_SUCCESS_RATE, "<", 0.9),
                        metric(PolicyMetric.TESTER_ENGAGEMENT, "<", 0.5)),
                201);
        TestMetricSource.arm(release.id, PolicyMetric.TESTER_ENGAGEMENT, 0.2);

        JsonNode held = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(held.get("errors").get("ReleasePolicyEngine").asText())
                .contains("engagement-floor")
                .contains("HOLD_FOR_REVIEW")
                .contains("acknowledge");
    }

    @Test
    void theAllowClauseEvaluatesAndLogsButDecidesNothing() {
        World world = world("pol-allow");
        Release release = activeReleaseAt(world, "qa", "pol-allow");
        createRule(
                world.adminToken,
                world.org,
                "healthy-and-approved",
                "ORGANIZATION",
                "ALLOW",
                30,
                allOf(
                        metric(PolicyMetric.TESTER_APPROVAL_RATE, ">=", 0.8),
                        metric(PolicyMetric.OPEN_CRITICAL_BUGS, "=", 0)),
                201);
        TestMetricSource.arm(release.id, PolicyMetric.TESTER_APPROVAL_RATE, 0.92);
        TestMetricSource.arm(release.id, PolicyMetric.OPEN_CRITICAL_BUGS, 0.0);

        // The ALLOW rule fires and is logged, and the promotion
        // proceeds (v1's ALLOW is the seam V3's automation acts on,
        // deliberately deciding nothing here).
        assertThat(promote(world.managerToken, world.app, release.id, null, null, 200)
                        .get("toRing")
                        .asText())
                .isEqualTo("tenant-pilot");
        JsonNode evaluations = evaluations(world.managerToken, world.app, release.id);
        JsonNode rulesOf = evaluations.get("evaluations").get(0).get("rules");
        assertThat(rulesOf.get(0).get("ruleName").asText()).isEqualTo("healthy-and-approved");
        assertThat(rulesOf.get(0).get("outcome").asText()).isEqualTo("FIRED");
        assertThat(rulesOf.get(0).get("action").asText()).isEqualTo("ALLOW");
    }

    @Test
    void aFailedGateConditionBlocksAndAnOverriddenGateDoesNotFire() {
        World world = world("pol-gate");
        createDefinition(world.adminToken, world.org, "dependency-scan", "tenant-pilot");
        Release release = activeReleaseAt(world, "qa", "pol-gate");
        recordGateResult(
                world.managerToken,
                world.app,
                release.id,
                "dependency-scan",
                "FAILED",
                "https://ci.example.com/scan/1",
                null,
                200);
        createRule(
                world.adminToken,
                world.org,
                "no-failed-scans",
                "ORGANIZATION",
                "BLOCK",
                10,
                Map.of("gate", "dependency-scan", "status", "FAILED"),
                201);

        JsonNode blocked = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(blocked.get("errors").get("ReleasePolicyEngine").asText())
                .contains("no-failed-scans")
                .contains("dependency-scan");

        // The audited override is honored: the same rule does not
        // re-litigate the recorded decision (the S10-03 discipline).
        overrideGate(world.managerToken, world.app, release.id, "dependency-scan", "risk accepted for beta ring");
        assertThat(promote(world.managerToken, world.app, release.id, null, null, 200)
                        .get("toRing")
                        .asText())
                .isEqualTo("tenant-pilot");
    }

    @Test
    void theApprovalConditionFiresOnABlockedStanding() {
        World world = world("pol-approval");
        createWorkflow(world.adminToken, world.org, "pilot-gate", "tenant-pilot", "QA_ENGINEER");
        // The ring must NAME the policy (the promotion leg resolves
        // the target ring's own approval_policy, the #44 standing).
        patchRing(world.adminToken, world.org, "tenant-pilot", Map.of("approvalPolicy", "pilot-gate"));
        Release release = activeReleaseAt(world, "qa", "pol-approval");
        createRule(
                world.adminToken,
                world.org,
                "approvals-complete",
                "ORGANIZATION",
                "BLOCK",
                10,
                Map.of("approvalBlocked", true),
                201);

        // The first attempt opens the round (PENDING) — both the
        // engine's own approval leg and the declarative rule refuse.
        JsonNode blocked = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(blocked.get("errors").get("approval").asText()).contains("pilot-gate");
        assertThat(blocked.get("errors").get("ReleasePolicyEngine").asText()).contains("approvals-complete");

        // The round satisfied: the rule not met, the hop proceeds.
        decideApproval(world.qaToken, world.app, release.id);
        assertThat(promote(world.managerToken, world.app, release.id, null, null, 200)
                        .get("toRing")
                        .asText())
                .isEqualTo("tenant-pilot");
    }

    @Test
    void theArtifactPlatformConditionScopesToTheShippedPlatforms() {
        World world = world("pol-platform");
        Release release = activeReleaseAt(world, "qa", "pol-platform");
        createRule(
                world.adminToken,
                world.org,
                "ios-extra-review",
                "ORGANIZATION",
                "HOLD_FOR_REVIEW",
                10,
                Map.of("artifactPlatform", "ios"),
                201);

        JsonNode held = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(held.get("errors").get("ReleasePolicyEngine").asText())
                .contains("ios-extra-review")
                .contains("platforms shipped: ios");
    }

    // -----------------------------------------------------------------------
    // AC2: BLOCK's 409 and HOLD's explicit privileged acknowledge
    // -----------------------------------------------------------------------

    @Test
    void aHoldRequiresThePrivilegedAcknowledgedActToProceed() {
        World world = world("pol-ack");
        Release release = activeReleaseAt(world, "qa", "pol-ack");
        createRule(
                world.adminToken,
                world.org,
                "engagement-floor",
                "ORGANIZATION",
                "HOLD_FOR_REVIEW",
                20,
                metric(PolicyMetric.TESTER_ENGAGEMENT, "<", 0.5),
                201);
        TestMetricSource.arm(release.id, PolicyMetric.TESTER_ENGAGEMENT, 0.1);

        promote(world.managerToken, world.app, release.id, null, null, 409);
        JsonNode holds = holds(world.managerToken, world.app, release.id);
        assertThat(holds.get("holds").size()).isEqualTo(1);
        UUID holdId = UUID.fromString(holds.get("holds").get(0).get("id").asText());
        assertThat(holds.get("holds").get(0).get("status").asText()).isEqualTo("PENDING");

        // The acknowledge is privileged: a viewer holds no such
        // authority, and the app-scope permission denial conceals as
        // the APPLICATION_NOT_FOUND 404 (the evaluator's standing).
        Map<String, Object> denied = new LinkedHashMap<>();
        denied.put("applicationId", world.app.toString());
        denied.put("reason", "let it pass");
        post(
                world.viewerToken,
                "/api/v1/releases/" + release.id + "/policy-holds/" + holdId + "/acknowledge",
                denied,
                404);

        // The reason is mandatory.
        Map<String, Object> noReason = new LinkedHashMap<>();
        noReason.put("applicationId", world.app.toString());
        post(
                world.managerToken,
                "/api/v1/releases/" + release.id + "/policy-holds/" + holdId + "/acknowledge",
                noReason,
                400);

        audit.reset();
        Map<String, Object> acknowledge = new LinkedHashMap<>();
        acknowledge.put("applicationId", world.app.toString());
        acknowledge.put("reason", "engagement accepted for the pilot ring");
        JsonNode acknowledged = post(
                world.managerToken,
                "/api/v1/releases/" + release.id + "/policy-holds/" + holdId + "/acknowledge",
                acknowledge,
                200);
        assertThat(acknowledged.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(acknowledged.get("acknowledgeReason").asText()).contains("pilot ring");
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_POLICY_HOLD_ACKNOWLEDGED))
                .hasSize(1);

        // The acknowledged decision stands: the next attempt passes
        // the hold and the hop commits.
        assertThat(promote(world.managerToken, world.app, release.id, null, null, 200)
                        .get("toRing")
                        .asText())
                .isEqualTo("tenant-pilot");
        JsonNode evaluations = evaluations(world.managerToken, world.app, release.id);
        JsonNode latest = evaluations.get("evaluations").get(0);
        assertThat(latest.get("verdict").asText()).isEqualTo("ALLOWED");
        assertThat(latest.get("rules").get(0).get("outcome").asText()).isEqualTo("ACKNOWLEDGED");

        // A hold acknowledges once; the standing decision is not a token.
        post(
                world.managerToken,
                "/api/v1/releases/" + release.id + "/policy-holds/" + holdId + "/acknowledge",
                acknowledge,
                409);
    }

    // -----------------------------------------------------------------------
    // AC3: the dry-run answers would-fire rules with no side effects
    // -----------------------------------------------------------------------

    @Test
    void dryRunReturnsWouldFireRulesAndWritesNothing() {
        World world = world("pol-dry");
        Release release = activeReleaseAt(world, "qa", "pol-dry");
        createRule(
                world.adminToken,
                world.org,
                "no-critical-vulns",
                "ORGANIZATION",
                "BLOCK",
                10,
                metric(PolicyMetric.CRITICAL_VULNERABILITIES, ">", 0),
                201);
        createRule(
                world.adminToken,
                world.org,
                "engagement-floor",
                "ORGANIZATION",
                "HOLD_FOR_REVIEW",
                20,
                metric(PolicyMetric.TESTER_ENGAGEMENT, "<", 0.5),
                201);
        TestMetricSource.arm(release.id, PolicyMetric.CRITICAL_VULNERABILITIES, 2.0);
        TestMetricSource.arm(release.id, PolicyMetric.TESTER_ENGAGEMENT, 0.1);

        int evaluationsBefore = countOf("release_policy_evaluations");
        int holdsBefore = countOf("release_policy_holds");

        JsonNode dryRun = get(
                world.viewerToken,
                "/api/v1/releases/" + release.id + "/policy-dry-run?applicationId=" + world.app,
                200);
        assertThat(dryRun.get("ring").asText()).isEqualTo("tenant-pilot");
        assertThat(dryRun.get("verdict").asText()).isEqualTo("BLOCKED");
        assertThat(dryRun.get("rules")).hasSize(2);
        assertThat(dryRun.get("rules").get(0).get("name").asText()).isEqualTo("no-critical-vulns");
        assertThat(dryRun.get("rules").get(0).get("outcome").asText()).isEqualTo("FIRED");
        assertThat(dryRun.get("rules").get(1).get("name").asText()).isEqualTo("engagement-floor");
        assertThat(dryRun.get("rules").get(1).get("outcome").asText()).isEqualTo("FIRED");
        assertThat(dryRun.get("rules").get(1).get("holdId").isNull()).isTrue();

        assertThat(countOf("release_policy_evaluations")).isEqualTo(evaluationsBefore);
        assertThat(countOf("release_policy_holds")).isEqualTo(holdsBefore);
    }

    // -----------------------------------------------------------------------
    // Concurrency and the dry-run's honest refusals (the tester round)
    // -----------------------------------------------------------------------

    /**
     * Two concurrent promote attempts against the same fired HOLD rule
     * both try to record the (release, rule, ring) hold: the unique
     * guard admits one, the loser's insert race is absorbed (one fact,
     * never two), both attempts answer the held 409 — never an
     * untranslated 500 — and exactly one hold row exists.
     */
    @Test
    void concurrentHeldPromotesRecordOneHoldAndNeverAnswer500() throws Exception {
        World world = world("pol-hold-race");
        Release release = activeReleaseAt(world, "qa", "pol-hold-race");
        createRule(
                world.adminToken,
                world.org,
                "engagement-floor",
                "ORGANIZATION",
                "HOLD_FOR_REVIEW",
                20,
                metric(PolicyMetric.TESTER_ENGAGEMENT, "<", 0.5),
                201);
        TestMetricSource.arm(release.id, PolicyMetric.TESTER_ENGAGEMENT, 0.1);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new CopyOnWriteArrayList<>();
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("applicationId", world.app.toString());
                    Recording answer =
                            exchange("/api/v1/releases/" + release.id + "/promote", world.managerToken, "POST", body);
                    statuses.add(answer.status());
                    return null;
                }));
            }
            for (Future<?> call : calls) {
                call.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).hasSize(2).containsOnly(409);
        Integer holdRows = jdbc.queryForObject(
                "SELECT count(*) FROM release_policy_holds WHERE release_id = ?", Integer.class, release.id);
        assertThat(holdRows).isEqualTo(1);
        Integer evaluations = jdbc.queryForObject(
                "SELECT count(*) FROM release_policy_evaluations WHERE release_id = ?", Integer.class, release.id);
        assertThat(evaluations).isEqualTo(2);
    }

    /** The dry-run answers the promote act's own pre-ring refusals honestly: the final ring, the staggered set. */
    @Test
    void dryRunRefusesTheFinalRingAndTheStaggeredSetWithThePromoteActRefusals() {
        World world = world("pol-dry-refuse");
        Release release = activeReleaseAt(world, "qa", "pol-dry-refuse");

        // Walk every ring to the final one: the next-ring arithmetic
        // answers the promote act's own final refusal.
        for (int hop = 0; hop < 3; hop++) {
            promote(world.managerToken, world.app, release.id, null, null, 200);
        }
        JsonNode final_ = get(
                world.viewerToken,
                "/api/v1/releases/" + release.id + "/policy-dry-run?applicationId=" + world.app,
                409);
        assertThat(final_.get("code").asText()).isEqualTo("RELEASE_PROMOTION_RING_FINAL");

        // A staggered target set (a selective promotion moved one
        // tenant forward) dry-runs the split refusal, naming rings.
        UUID tenantB = createTenant(world.adminToken, world.org, "Policy Stagger Bank", "pol-stagger-b");
        UUID sharedApp = createSharedApp(
                world.adminToken, world.org, world.tenantA, tenantB, "Policy Stagger Banking", "com.poldry.shared");
        Release twoTargets = activeReleaseAtForTargets(
                world,
                sharedApp,
                "engineering",
                List.of(
                        Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of()),
                        Map.of("tenantId", tenantB.toString(), "testerGroupIds", List.of())),
                "pol-dry-two");
        promote(world.managerToken, sharedApp, twoTargets.id, List.of(world.tenantA), null, 200);
        JsonNode split = get(
                world.managerToken,
                "/api/v1/releases/" + twoTargets.id + "/policy-dry-run?applicationId=" + sharedApp,
                409);
        assertThat(split.get("code").asText()).isEqualTo("RELEASE_PROMOTION_RING_SPLIT");
    }

    /** A rule cannot be RENAMED onto another rule's name (the update-side unique check, the precise 409). */
    @Test
    void renamingARuleOntoAnotherRulesNameRefuses() {
        World world = world("pol-rename");
        JsonNode first = createRule(
                world.adminToken,
                world.org,
                "first-rule",
                "ORGANIZATION",
                "ALLOW",
                1,
                Map.of("approvalBlocked", true),
                201);
        createRule(
                world.adminToken,
                world.org,
                "second-rule",
                "ORGANIZATION",
                "ALLOW",
                2,
                Map.of("approvalBlocked", true),
                201);
        putRule(
                world.adminToken,
                world.org,
                first.get("id").asText(),
                "second-rule",
                "ORGANIZATION",
                "ALLOW",
                1,
                Map.of("approvalBlocked", true),
                409);
    }

    // -----------------------------------------------------------------------
    // AC4: missing metric data fails closed with the explicit outcome
    // -----------------------------------------------------------------------

    @Test
    void missingMetricDataFailsClosedWithTheNoDataOutcome() {
        World world = world("pol-nodata");
        Release release = activeReleaseAt(world, "qa", "pol-nodata");
        createRule(
                world.adminToken,
                world.org,
                "anr-ceiling",
                "ORGANIZATION",
                "BLOCK",
                10,
                metric(PolicyMetric.ANR_RATE, ">", 0.02),
                201);

        // No analytics registrant answers for this release: the rule
        // fires on the explicit no-data standing — never a zero,
        // never a pass.
        JsonNode blocked = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(blocked.get("errors").get("ReleasePolicyEngine").asText())
                .contains("anr-ceiling")
                .contains("no data")
                .contains("fail closed");

        JsonNode evaluations = evaluations(world.managerToken, world.app, release.id);
        assertThat(evaluations
                        .get("evaluations")
                        .get(0)
                        .get("rules")
                        .get(0)
                        .get("outcome")
                        .asText())
                .isEqualTo("NO_DATA");

        // The dry-run renders the same standing without writing it.
        JsonNode dryRun = get(
                world.viewerToken,
                "/api/v1/releases/" + release.id + "/policy-dry-run?applicationId=" + world.app,
                200);
        assertThat(dryRun.get("rules").get(0).get("outcome").asText()).isEqualTo("NO_DATA");
        assertThat(dryRun.get("rules").get(0).get("detail").asText()).contains("no data");
    }

    // -----------------------------------------------------------------------
    // AC5: defined order, first BLOCK wins, every evaluation logged
    // -----------------------------------------------------------------------

    @Test
    void rulesEvaluateInOrderTheFirstBlockWinsAndEveryAttemptLogs() {
        World world = world("pol-order");
        Release release = activeReleaseAt(world, "qa", "pol-order");
        createRule(
                world.adminToken,
                world.org,
                "engagement-floor",
                "ORGANIZATION",
                "HOLD_FOR_REVIEW",
                20,
                metric(PolicyMetric.TESTER_ENGAGEMENT, "<", 0.5),
                201);
        createRule(
                world.adminToken,
                world.org,
                "later-block",
                "ORGANIZATION",
                "BLOCK",
                30,
                metric(PolicyMetric.CRITICAL_VULNERABILITIES, ">", 0),
                201);
        createRule(
                world.adminToken,
                world.org,
                "first-block",
                "ORGANIZATION",
                "BLOCK",
                10,
                metric(PolicyMetric.CRASH_FREE_RATE, "<", 0.99),
                201);
        TestMetricSource.arm(release.id, PolicyMetric.TESTER_ENGAGEMENT, 0.1);
        TestMetricSource.arm(release.id, PolicyMetric.CRITICAL_VULNERABILITIES, 1.0);
        TestMetricSource.arm(release.id, PolicyMetric.CRASH_FREE_RATE, 0.5);

        // Position order: first-block (10) fires first and WINS; the
        // later BLOCK still logs; the HOLD still logs and is named.
        JsonNode blocked = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(blocked.get("errors").get("ReleasePolicyEngine").asText())
                .contains("first-block")
                .contains("engagement-floor");

        JsonNode evaluations = evaluations(world.managerToken, world.app, release.id);
        JsonNode attempt = evaluations.get("evaluations").get(0);
        assertThat(attempt.get("verdict").asText()).isEqualTo("BLOCKED");
        assertThat(UUID.fromString(attempt.get("winningRuleId").asText())).isEqualTo(ruleIdOf(world, "first-block"));
        assertThat(attempt.get("rules")).hasSize(3);
        assertThat(attempt.get("rules").get(0).get("ruleName").asText()).isEqualTo("first-block");
        assertThat(attempt.get("rules").get(1).get("ruleName").asText()).isEqualTo("engagement-floor");
        assertThat(attempt.get("rules").get(2).get("ruleName").asText()).isEqualTo("later-block");
        assertThat(attempt.get("rules").get(0).get("outcome").asText()).isEqualTo("FIRED");
        assertThat(attempt.get("rules").get(2).get("outcome").asText()).isEqualTo("FIRED");

        // The disabled rule never evaluates; the precheck read logs
        // nothing (only attempts write).
        int before = countOf("release_policy_evaluations");
        get(
                world.viewerToken,
                "/api/v1/releases/" + release.id + "/promotion-precheck?applicationId=" + world.app,
                200);
        assertThat(countOf("release_policy_evaluations")).isEqualTo(before);

        // A second attempt logs a second evaluation (one row per
        // attempt, exactly — the in-tx revalidation never writes).
        promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(countOf("release_policy_evaluations")).isEqualTo(before + 1);
    }

    // -----------------------------------------------------------------------
    // The scoping dimensions: application, ring, environment
    // -----------------------------------------------------------------------

    @Test
    void rulesScopeToTheApplicationAndTheRingEntered() {
        World world = world("pol-scope");
        UUID otherApp = createTenantApp(world.adminToken, world.tenantA, "Scope Other", "com.polscope.other");
        createRule(
                world.adminToken,
                world.org,
                "app-guard",
                "APPLICATION",
                "BLOCK",
                10,
                metric(PolicyMetric.CRITICAL_VULNERABILITIES, ">", 0),
                201,
                world.app);
        createRule(
                world.adminToken,
                world.org,
                "production-guard",
                "RING",
                "BLOCK",
                10,
                metric(PolicyMetric.CRITICAL_VULNERABILITIES, ">", 0),
                201,
                null,
                "production");

        // The application-scoped rule fires for its application...
        Release release = activeReleaseAt(world, "qa", "pol-scope");
        TestMetricSource.arm(release.id, PolicyMetric.CRITICAL_VULNERABILITIES, 5.0);
        JsonNode blocked = promote(world.managerToken, world.app, release.id, null, null, 409);
        assertThat(blocked.get("errors").get("ReleasePolicyEngine").asText()).contains("app-guard");

        // ...not for another application of the same organization.
        Release foreign = activeReleaseAtFor(world, otherApp, "qa", "pol-scope-other");
        TestMetricSource.arm(foreign.id, PolicyMetric.CRITICAL_VULNERABILITIES, 5.0);
        JsonNode dryRun = get(
                world.managerToken,
                "/api/v1/releases/" + foreign.id + "/policy-dry-run?applicationId=" + otherApp,
                200);
        assertThat(dryRun.get("rules")).isEmpty();

        // The ring-scoped rule evaluates only the hop into its ring:
        // the qa -> tenant-pilot hop does not meet it.
        JsonNode tenantPilotDryRun = get(
                world.managerToken,
                "/api/v1/releases/" + foreign.id + "/policy-dry-run?applicationId=" + otherApp,
                200);
        assertThat(tenantPilotDryRun.get("verdict").asText()).isEqualTo("ALLOWED");
        assertThat(promote(world.managerToken, otherApp, foreign.id, null, null, 200)
                        .get("toRing")
                        .asText())
                .isEqualTo("tenant-pilot");

        // ...the hop into production does (walk uat first, then dry-run
        // names the production-scoped rule).
        promote(world.managerToken, otherApp, foreign.id, null, null, 200);
        JsonNode productionDryRun = get(
                world.managerToken,
                "/api/v1/releases/" + foreign.id + "/policy-dry-run?applicationId=" + otherApp,
                200);
        assertThat(productionDryRun.get("ring").asText()).isEqualTo("production");
        assertThat(productionDryRun.get("rules").get(0).get("name").asText()).isEqualTo("production-guard");
    }

    // -----------------------------------------------------------------------
    // The skip-relaxation knob (the seam's widening path)
    // -----------------------------------------------------------------------

    @Test
    void skipsRefuseByDefaultAndAdmitWhenTheKnobNamesTheRing() {
        World world = world("pol-skip");
        Release release = activeReleaseAt(world, "qa", "pol-skip");

        // The #44 default posture stands: one ring at a time.
        JsonNode refused = promote(world.managerToken, world.app, release.id, null, "uat", 409);
        assertThat(refused.get("code").asText()).isEqualTo("RELEASE_PROMOTION_RING_SKIP");
        assertThat(refused.get("errors").get("next").asText()).isEqualTo("tenant-pilot");

        // The knob validates its references.
        putSkipAllowances(world.adminToken, world.org, List.of("not-a-ring"), 400);

        // An unedited organization answers the empty set; the edit
        // widens the walk — the hop carries straight to uat.
        assertThat(get(world.adminToken, "/api/v1/release-policies/skip-allowances?organizationId=" + world.org, 200)
                        .get("rings"))
                .isEmpty();
        putSkipAllowances(world.adminToken, world.org, List.of("uat"), 200);
        JsonNode skipped = promote(world.managerToken, world.app, release.id, null, "uat", 200);
        assertThat(skipped.get("fromRing").asText()).isEqualTo("qa");
        assertThat(skipped.get("toRing").asText()).isEqualTo("uat");

        // A ring the knob does not name still refuses.
        Release another = activeReleaseAt(world, "qa", "pol-skip-2");
        promote(world.managerToken, world.app, another.id, null, null, 200);
        JsonNode stillRefused = promote(world.managerToken, world.app, another.id, null, "production", 409);
        assertThat(stillRefused.get("code").asText()).isEqualTo("RELEASE_PROMOTION_RING_SKIP");
    }

    // -----------------------------------------------------------------------
    // AC6: the CRUD validation the console editor renders
    // -----------------------------------------------------------------------

    @Test
    void ruleValidationRefusesMalformedConditionsAndUnresolvableReferences() {
        World world = world("pol-crud");

        // Unknown metric, unknown operator, non-numeric threshold.
        Map<String, Object> unknownMetric = metric(PolicyMetric.CRASH_FREE_RATE, "<", 0.99);
        unknownMetric.put("metric", "NOT_A_METRIC");
        createRule(world.adminToken, world.org, "bad-metric", "ORGANIZATION", "BLOCK", 10, unknownMetric, 400);
        Map<String, Object> unknownOperator = metric(PolicyMetric.CRASH_FREE_RATE, "~", 0.99);
        createRule(world.adminToken, world.org, "bad-operator", "ORGANIZATION", "BLOCK", 10, unknownOperator, 400);
        createRule(
                world.adminToken,
                world.org,
                "bad-value",
                "ORGANIZATION",
                "BLOCK",
                10,
                Map.of("metric", "CRASH_FREE_RATE", "operator", "<", "value", "high"),
                400);

        // No construct at all; two constructs at once.
        createRule(world.adminToken, world.org, "empty", "ORGANIZATION", "BLOCK", 10, Map.of(), 400);
        createRule(
                world.adminToken,
                world.org,
                "double",
                "ORGANIZATION",
                "BLOCK",
                10,
                Map.of("metric", "CRASH_FREE_RATE", "operator", "<", "value", 0.99, "approvalBlocked", true),
                400);

        // Unresolvable references: the ring is not in the vocabulary,
        // the application is not this organization's, the gate type
        // is neither catalog nor definition.
        createRule(
                world.adminToken,
                world.org,
                "bad-ring",
                "RING",
                "BLOCK",
                10,
                Map.of("approvalBlocked", true),
                400,
                null,
                "not-a-ring");
        createRule(
                world.adminToken,
                world.org,
                "bad-app",
                "APPLICATION",
                "BLOCK",
                10,
                Map.of("approvalBlocked", true),
                400,
                UUID.randomUUID());
        createRule(
                world.adminToken,
                world.org,
                "bad-gate",
                "ORGANIZATION",
                "BLOCK",
                10,
                Map.of("gate", "no-such-gate", "status", "FAILED"),
                400);

        // The §33 shapes author cleanly.
        JsonNode created = createRule(
                world.adminToken,
                world.org,
                "fine-rule",
                "ORGANIZATION",
                "BLOCK",
                10,
                allOf(metric(PolicyMetric.CRASH_FREE_RATE, "<", 0.99), metric(PolicyMetric.ANR_RATE, ">", 0.02)),
                201);
        assertThat(created.get("conditionSummary").asText()).contains("allOf(");

        // One name per organization; the replace keeps it, a second
        // rule cannot take it.
        createRule(
                world.adminToken,
                world.org,
                "fine-rule",
                "ORGANIZATION",
                "ALLOW",
                11,
                Map.of("approvalBlocked", true),
                409);
        putRule(
                world.adminToken,
                world.org,
                created.get("id").asText(),
                "fine-rule",
                "ORGANIZATION",
                "ALLOW",
                11,
                Map.of("approvalBlocked", true),
                200);

        // The rule list renders in the defined order.
        createRule(
                world.adminToken,
                world.org,
                "aaa-first",
                "ORGANIZATION",
                "ALLOW",
                1,
                Map.of("approvalBlocked", true),
                201);
        JsonNode listed = get(world.adminToken, "/api/v1/release-policies?organizationId=" + world.org, 200);
        assertThat(listed.get("items").get(0).get("name").asText()).isEqualTo("aaa-first");
        assertThat(listed.get("items").get(1).get("name").asText()).isEqualTo("fine-rule");

        // A disabled rule never evaluates.
        Release release = activeReleaseAt(world, "qa", "pol-crud");
        TestMetricSource.arm(release.id, PolicyMetric.CRASH_FREE_RATE, 0.1);
        putRule(
                world.adminToken,
                world.org,
                created.get("id").asText(),
                "fine-rule",
                "ORGANIZATION",
                "BLOCK",
                10,
                metric(PolicyMetric.CRASH_FREE_RATE, "<", 0.99),
                200);
        Map<String, Object> disabled = ruleBody(
                world.org, "fine-rule", "ORGANIZATION", "BLOCK", 10, metric(PolicyMetric.CRASH_FREE_RATE, "<", 0.99));
        disabled.put("enabled", false);
        put(
                world.adminToken,
                "/api/v1/release-policies/" + created.get("id").asText() + "?organizationId=" + world.org,
                disabled,
                200);
        assertThat(promote(world.managerToken, world.app, release.id, null, null, 200)
                        .get("toRing")
                        .asText())
                .isEqualTo("tenant-pilot");

        // A rule with recorded holds refuses deletion (the ledger is
        // audit evidence); disabling is the path.
        Release held = activeReleaseAt(world, "qa", "pol-crud-hold");
        TestMetricSource.arm(held.id, PolicyMetric.CRASH_FREE_RATE, 0.1);
        put(
                world.adminToken,
                "/api/v1/release-policies/" + created.get("id").asText() + "?organizationId=" + world.org,
                ruleBody(
                        world.org,
                        "fine-rule",
                        "ORGANIZATION",
                        "HOLD_FOR_REVIEW",
                        10,
                        metric(PolicyMetric.CRASH_FREE_RATE, "<", 0.99)),
                200);
        promote(world.managerToken, world.app, held.id, null, null, 409);
        delete(world.adminToken, world.org, created.get("id").asText(), 409);
    }

    // -----------------------------------------------------------------------
    // The fixture
    // -----------------------------------------------------------------------

    private static final class World {
        UUID org;
        UUID tenantA;
        UUID app;
        String adminToken;
        String ownerToken;
        String managerToken;
        String qaToken;
        String viewerToken;
    }

    private record Release(UUID id, String ref) {}

    private World world(String slug) {
        World world = new World();
        world.org = createOrg("Policy " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.qaToken = loginOfOrgRoleIn(world.org, slug + "-qa", "QA_ENGINEER");
        world.viewerToken = loginOfOrgRoleIn(world.org, slug + "-viewer", "VIEWER");
        world.tenantA = createTenant(world.adminToken, world.org, "Policy " + slug + " Bank", "pol-" + slug);
        world.app = createTenantApp(
                world.adminToken,
                world.tenantA,
                "Policy " + slug + " Banking",
                "com." + slug.replace("-", "") + ".banking");
        return world;
    }

    private Release activeReleaseAt(World world, String ring, String slug) {
        return activeReleaseAtForTargets(
                world,
                world.app,
                ring,
                List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of())),
                slug);
    }

    private Release activeReleaseAtFor(World world, UUID applicationId, String ring, String slug) {
        return activeReleaseAtForTargets(
                world,
                applicationId,
                ring,
                List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of())),
                slug);
    }

    /** The same walk for an explicit target set (the staggered-set fixtures). */
    private Release activeReleaseAtForTargets(
            World world, UUID applicationId, String ring, List<Map<String, Object>> targets, String slug) {
        String name = String.format("%d.%d.%d", 5 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(world.ownerToken, applicationId, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("versionId", version.toString());
        body.put("ring", ring);
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", targets);
        JsonNode created = post(world.managerToken, "/api/v1/releases", body, 201);
        UUID releaseId = UUID.fromString(created.get("id").asText());
        for (String to : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            Map<String, Object> transition = new LinkedHashMap<>();
            transition.put("applicationId", applicationId.toString());
            transition.put("to", to);
            post(world.managerToken, "/api/v1/releases/" + releaseId + "/transitions", transition, 200);
        }
        return new Release(releaseId, created.get("releaseRef").asText());
    }

    /** An ORGANIZATION-shared application both target tenants hold (the two-target fixtures). */
    private UUID createSharedApp(
            String token, UUID organizationId, UUID tenantA, UUID tenantB, String name, String bundleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        UUID appId = UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
        post(token, "/api/v1/applications/" + appId + "/tenants/" + tenantA, Map.of(), 201);
        post(token, "/api/v1/applications/" + appId + "/tenants/" + tenantB, Map.of(), 201);
        return appId;
    }

    // -----------------------------------------------------------------------
    // Policy-rule helpers
    // -----------------------------------------------------------------------

    private static Map<String, Object> metric(PolicyMetric metric, String operator, double value) {
        Map<String, Object> atom = new LinkedHashMap<>();
        atom.put("metric", metric.name());
        atom.put("operator", operator);
        atom.put("value", value);
        return atom;
    }

    private static Map<String, Object> allOf(Map<String, Object>... children) {
        return combinator("allOf", children);
    }

    private static Map<String, Object> anyOf(Map<String, Object>... children) {
        return combinator("anyOf", children);
    }

    private static Map<String, Object> combinator(String kind, Map<String, Object>... children) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put(kind, List.of(children));
        return node;
    }

    private static Map<String, Object> ruleBody(
            UUID organizationId,
            String name,
            String scope,
            String action,
            int position,
            Map<String, Object> condition) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("scope", scope);
        body.put("action", action);
        body.put("condition", condition);
        body.put("position", position);
        return body;
    }

    private JsonNode createRule(
            String token,
            UUID organizationId,
            String name,
            String scope,
            String action,
            int position,
            Map<String, Object> condition,
            int expected) {
        return createRule(token, organizationId, name, scope, action, position, condition, expected, null, null);
    }

    private JsonNode createRule(
            String token,
            UUID organizationId,
            String name,
            String scope,
            String action,
            int position,
            Map<String, Object> condition,
            int expected,
            UUID applicationId) {
        return createRule(
                token, organizationId, name, scope, action, position, condition, expected, applicationId, null);
    }

    private JsonNode createRule(
            String token,
            UUID organizationId,
            String name,
            String scope,
            String action,
            int position,
            Map<String, Object> condition,
            int expected,
            UUID applicationId,
            String ring) {
        Map<String, Object> body = ruleBody(organizationId, name, scope, action, position, condition);
        if (applicationId != null) {
            body.put("applicationId", applicationId.toString());
        }
        if (ring != null) {
            body.put("ring", ring);
        }
        return post(token, "/api/v1/release-policies", body, expected);
    }

    private JsonNode putRule(
            String token,
            UUID organizationId,
            String ruleId,
            String name,
            String scope,
            String action,
            int position,
            Map<String, Object> condition,
            int expected) {
        return put(
                token,
                "/api/v1/release-policies/" + ruleId + "?organizationId=" + organizationId,
                ruleBody(organizationId, name, scope, action, position, condition),
                expected);
    }

    private JsonNode putSkipAllowances(String token, UUID organizationId, List<String> rings, int expected) {
        return put(
                token,
                "/api/v1/release-policies/skip-allowances",
                Map.of("organizationId", organizationId.toString(), "rings", rings),
                expected);
    }

    private void delete(String token, UUID organizationId, String ruleId, int expected) {
        exchange("/api/v1/release-policies/" + ruleId + "?organizationId=" + organizationId, token, "DELETE", null)
                .expect(expected);
    }

    private UUID ruleIdOf(World world, String name) {
        return jdbc.queryForObject(
                "SELECT id FROM release_policy_rules WHERE organization_id = ? AND name = ?",
                UUID.class,
                world.org,
                name);
    }

    private JsonNode promote(
            String bearer, UUID applicationId, UUID releaseId, List<UUID> tenants, String to, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        if (tenants != null) {
            body.put("tenants", tenants.stream().map(UUID::toString).toList());
        }
        if (to != null) {
            body.put("to", to);
        }
        return post(bearer, "/api/v1/releases/" + releaseId + "/promote", body, expected);
    }

    private JsonNode holds(String bearer, UUID applicationId, UUID releaseId) {
        return get(bearer, "/api/v1/releases/" + releaseId + "/policy-holds?applicationId=" + applicationId, 200);
    }

    private JsonNode evaluations(String bearer, UUID applicationId, UUID releaseId) {
        return get(bearer, "/api/v1/releases/" + releaseId + "/policy-evaluations?applicationId=" + applicationId, 200);
    }

    private void createDefinition(String token, UUID organizationId, String gateType, String ring) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("gateType", gateType);
        body.put("ring", ring);
        body.put("required", true);
        post(token, "/api/v1/gate-definitions", body, 201);
    }

    private void recordGateResult(
            String bearer,
            UUID applicationId,
            UUID releaseId,
            String gateType,
            String status,
            String evidenceUrl,
            Double observed,
            int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("status", status);
        body.put("evidenceUrl", evidenceUrl);
        if (observed != null) {
            body.put("observedValue", observed);
        }
        post(bearer, "/api/v1/releases/" + releaseId + "/gates/" + gateType + "/result", body, expected);
    }

    private void overrideGate(String bearer, UUID applicationId, UUID releaseId, String gateType, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("reason", reason);
        post(bearer, "/api/v1/releases/" + releaseId + "/gates/" + gateType + "/override", body, 200);
    }

    private void createWorkflow(String token, UUID organizationId, String name, String ring, String role) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("role", role);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        body.put("ring", ring);
        body.put("steps", List.of(step));
        post(token, "/api/v1/approval-workflows", body, 201);
    }

    /** Edits one of the organization's seeded rings by code (the fixture's ring-handle). */
    private void patchRing(String token, UUID organizationId, String code, Map<String, Object> fields) {
        UUID ringId = jdbc.queryForObject(
                "SELECT id FROM rings WHERE organization_id = ? AND code = ?", UUID.class, organizationId, code);
        exchange(
                        "/api/v1/rings/" + ringId + "?organizationId=" + organizationId,
                        token,
                        "PATCH",
                        new LinkedHashMap<>(fields))
                .expect(200);
    }

    private void decideApproval(String token, UUID applicationId, UUID releaseId) {
        post(
                token,
                "/api/v1/releases/" + releaseId + "/approvals",
                Map.of("applicationId", applicationId.toString(), "decision", "APPROVE"),
                200);
    }

    private int countOf(String table) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    // -----------------------------------------------------------------------
    // The shared fixture plumbing (the promotion suite's shape)
    // -----------------------------------------------------------------------

    private UUID createVersion(String token, UUID applicationId, String versionName) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/applications/" + applicationId + "/versions",
                        Map.of("versionName", versionName),
                        201)
                .get("id")
                .asText());
    }

    private UUID insertArtifact(UUID versionId, String platform, long build) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        String extension = "ANDROID".equals(platform) ? "apk" : "ipa";
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, 'READY'::artifact_status, ?, ?)",
                artifactId,
                versionId,
                platform,
                platform.toLowerCase(Locale.ROOT) + "-" + build + "." + extension,
                "fixture/" + artifactId + "/artifact",
                "b2".repeat(32),
                1024L,
                build,
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", bundleId);
        body.put("packageName", bundleId);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
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
                        "Policy " + role))
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
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
        });
    }

    /** One recorded exchange: the status beside the parsed body (the suite's assertions). */
    private record Recording(int status, JsonNode body) {

        JsonNode expect(int expected) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expected);
            return body;
        }
    }
}
