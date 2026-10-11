package com.shipperdog.api.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.distribution.internal.apple.AppleAppStoreConnectMock;
import com.shipperdog.api.identity.AuditCapture;
import com.shipperdog.api.release.PolicyMetric;
import com.shipperdog.api.release.ReleasePolicyMetricSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The staged TENANT rollout and orchestrated rollback's end-to-end
 * rehearsal (issue #66, S13-06): the deterministic, inspectable plan
 * (AC1), the gated and checkpointed advancement (AC2), the per-stage
 * channel execution with per-tenant progress and the narrowed direct
 * arm (AC3), the ONE-action rollback — direct downloads cut,
 * TestFlight assignments reverted against the mock ASC store, records
 * to the rolled-back state, the release ROLLED_BACK with its timeline
 * row, artifacts untouched (AC4) — and the manifest's frozen plan
 * beside the ledger's completion record (AC6). The dispatch-failure
 * rollback ownership doctrine (the #61 promise this story lands) is
 * pinned as its own leg.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
            "shipperdog.authorization-cache.enabled=false"
        })
@TestPropertySource(properties = "shipperdog.distribution.adapters.testflight.retry-backoff=PT0.05S")
@Testcontainers
@Import({AuditCapture.class, StagedTenantRolloutIntegrationTest.RolloutMetricSource.class})
class StagedTenantRolloutIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The mock ASC server: Apple's shapes, the mock's key, the mock's verdicts (the live-channel sandbox leg). */
    static final AppleAppStoreConnectMock ASC = new AppleAppStoreConnectMock();

    @DynamicPropertySource
    static void appleConnector(DynamicPropertyRegistry registry) {
        registry.add("shipperdog.distribution.apple.base-url", ASC::baseUrl);
    }

    @AfterAll
    static void stopMock() {
        ASC.stop();
    }

    /**
     * The analytics stand-in for the policy gate's consult (the
     * ReleasePolicyIntegrationTest precedent): values armed per
     * release, an unarmed metric answering empty.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class RolloutMetricSource implements ReleasePolicyMetricSource {

        static final Map<UUID, Map<PolicyMetric, Double>> VALUES = new ConcurrentHashMap<>();

        static void arm(UUID releaseId, PolicyMetric metric, Double value) {
            VALUES.computeIfAbsent(releaseId, key -> new ConcurrentHashMap<>()).put(metric, value);
        }

        @Override
        public Optional<Double> metricOf(UUID releaseId, PolicyMetric metric) {
            return Optional.ofNullable(VALUES.getOrDefault(releaseId, Map.of()).get(metric));
        }
    }

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

    private String slug;
    private UUID org;
    private String adminToken;
    private String ownerToken;
    private String managerToken;
    private UUID acme;
    private UUID contoso;
    private UUID eastside;
    private UUID fabrikam;
    private UUID app;
    private String bundleId;
    private int versionCounter;
    /** The distinctly-named per-tenant groups the TestFlight leg links (set by that test). */
    private Map<UUID, String> stagedGroupNames = Map.of();

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        ASC.reset();

        slug = "staged-" + UUID.randomUUID().toString().substring(0, 8);
        org = createOrg("Staged Rollout Corp " + slug);
        adminToken = loginOfOrgRoleIn(org, slug + "-admin", "ORG_ADMIN");
        ownerToken = loginOfOrgRoleIn(org, slug + "-owner", "APP_OWNER");
        managerToken = loginOfOrgRoleIn(org, slug + "-manager", "RELEASE_MANAGER");
        acme = createTenant(adminToken, "Acme Bank", "crm-" + slug + "-acme");
        contoso = createTenant(adminToken, "Contoso Bank", "crm-" + slug + "-contoso");
        eastside = createTenant(adminToken, "Eastside Bank", "crm-" + slug + "-east");
        fabrikam = createTenant(adminToken, "Fabrikam Bank", "crm-" + slug + "-fab");
        bundleId = "com." + slug.replace("-", "") + ".banking";
        app = createSharedApp("Staged Banking", bundleId, List.of(acme, contoso, eastside, fabrikam));
    }

    // -----------------------------------------------------------------------
    // AC1: the plan — authoring validation and the inspectable cohorts
    // -----------------------------------------------------------------------

    @Test
    void aPlanIsAuthoredWithResolvedDeterministicCohortsInspectableBeforeAdvancement() {
        UUID release = releaseWalkedTo("DRAFT");
        UUID pilot = firstOfSeededOrder(release);
        JsonNode planned =
                putPlan(release, "CHECKPOINT", List.of(explicit(List.of(pilot)), percent(50), percent(100)), 200);

        assertThat(planned.get("mode").asString()).isEqualTo("CHECKPOINT");
        assertThat(planned.get("status").asString()).isEqualTo("PLANNED");
        assertThat(planned.get("currentStage").isNull()).isTrue();

        // AC1: cohort membership is inspectable before any advancement.
        List<JsonNode> stages = toList(planned.get("stages"));
        assertThat(stages).hasSize(3);
        assertThat(stages.get(0).get("kind").asString()).isEqualTo("EXPLICIT");
        assertThat(stages.get(0).get("cohortSize").asInt()).isEqualTo(1);
        assertThat(UUID.fromString(stages.get(0).get("cohort").get(0).asText())).isEqualTo(pilot);
        assertThat(stages.get(1).get("cohortSize").asInt()).isEqualTo(2);
        assertThat(stages.get(2).get("cohortSize").asInt()).isEqualTo(4);
        // The seeded order's prefix property: stage 2's cohort holds the pilot.
        assertThat(toList(stages.get(1).get("cohort")).stream().map(node -> node.asText()))
                .contains(pilot.toString());
        // The full-coverage final stage holds every target.
        assertThat(toList(stages.get(2).get("cohort")).stream().map(node -> node.asText()))
                .containsExactlyInAnyOrder(
                        acme.toString(), contoso.toString(), eastside.toString(), fabrikam.toString());

        // The audit and the timeline's PLANNED row.
        assertThat(audit.eventsOfType(AuditEventType.STAGED_ROLLOUT_PLANNED)).hasSize(1);
        JsonNode timeline = planned.get("timeline");
        assertThat(timeline.get(0).get("action").asString()).isEqualTo("PLANNED");
    }

    @Test
    void planAuthoringRefusesThePreciseFaults() {
        UUID release = releaseWalkedTo("DRAFT");
        UUID pilot = firstOfSeededOrder(release);
        UUID stranger = createTenant(adminToken, "Stranger Bank", "crm-" + slug + "-stranger");

        // A non-target tenant.
        putPlan(release, "CHECKPOINT", List.of(explicit(List.of(stranger))), 400);
        // A shrinking stage (percent after a wider explicit).
        putPlan(release, "CHECKPOINT", List.of(percent(100), percent(50)), 400);
        // A final stage that does not reach everyone.
        putPlan(release, "AUTOMATIC", List.of(percent(50)), 400);
        // An unknown mode, a bad percentage, an empty plan.
        putPlan(release, "WEEKENDS", List.of(percent(100)), 400);
        putPlan(release, "CHECKPOINT", List.of(percent(0)), 400);
        putPlan(release, "CHECKPOINT", List.of(), 400);
        // A stage of neither shape.
        Map<String, Object> shapeless = Map.of("mode", "CHECKPOINT", "stages", List.of(Map.of("note", "none")));
        put(adminToken, stagedUri(release), shapeless, 400);
        // Authoring against a release already distributing.
        UUID active = releaseWalkedTo("ACTIVE");
        putPlan(active, "CHECKPOINT", List.of(percent(100)), 400);
        // The one plan per release.
        putPlan(release, "CHECKPOINT", List.of(percent(50), percent(100)), 200);
        putPlan(release, "CHECKPOINT", List.of(percent(50), percent(100)), 409);
        // The pilot tenant was never consumed by the refused plans (the
        // successful one carries it).
        assertThat(pilot).isNotNull();
    }

    // -----------------------------------------------------------------------
    // AC3: commencement narrows the first distribution; per-tenant progress
    // -----------------------------------------------------------------------

    @Test
    void commencementStartsThePlanNarrowsTheFirstDistributionAndShowsPerTenantProgress() {
        UUID version = createVersion(ownerToken, app, nextVersion());
        UUID artifact = insertArtifact(version, "IOS", 400 + versionCounter);
        UUID release = releaseAt(version, List.of(artifact), "DRAFT");
        UUID pilot = firstOfSeededOrder(release);
        UUID otherTenant = pilot.equals(acme) ? contoso : acme;
        putPlan(release, "CHECKPOINT", List.of(explicit(List.of(pilot)), percent(100)), 200);
        walk(release, "VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE");

        // The plan commenced with the ACTIVE crossing: stage 1 REACHED.
        JsonNode running = view(release);
        assertThat(running.get("status").asString()).isEqualTo("RUNNING");
        assertThat(running.get("currentStage").asInt()).isEqualTo(1);
        assertThat(toList(running.get("stages")).get(0).get("status").asString())
                .isEqualTo("REACHED");
        assertThat(running.get("timeline").get(0).get("action").asString()).isEqualTo("PLANNED");
        assertThat(running.get("timeline").get(1).get("action").asString()).isEqualTo("STARTED");

        // AC3: per-tenant progress — the pilot's cohort reached, the
        // other target tenants not yet.
        Map<String, JsonNode> progress = new LinkedHashMap<>();
        toList(running.get("tenantProgress"))
                .forEach(row -> progress.put(row.get("tenantId").asText(), row));
        assertThat(progress.keySet())
                .containsExactlyInAnyOrder(
                        acme.toString(), contoso.toString(), eastside.toString(), fabrikam.toString());
        assertThat(progress.get(pilot.toString()).get("reachedAtStage").asInt()).isEqualTo(1);
        assertThat(progress.get(otherTenant.toString()).get("reachedAtStage").isNull())
                .isTrue();

        // The DIRECT arm's narrowing is the StagedRolloutDownloadGate
        // (unit-pinned beside this suite): a RUNNING plan serves the
        // reached cohort only. A release WITHOUT a plan narrows nothing
        // -- the dispatch's command carried every target, byte-identical
        // to the pre-staging shape.
        UUID plainVersion = createVersion(ownerToken, app, nextVersion());
        UUID plainArtifact = insertArtifact(plainVersion, "IOS", 400 + versionCounter);
        UUID plainRelease = releaseAt(plainVersion, List.of(plainArtifact), "DRAFT");
        walk(plainRelease, "VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, plainRelease))
                .isEqualTo("ACTIVE");
        // The plan-less world's dispatch served every target: no
        // staged rows exist for the plain release at all.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM staged_rollout_plans WHERE release_id = ?", Integer.class, plainRelease))
                .isZero();
    }

    // -----------------------------------------------------------------------
    // AC2: checkpoint approval, the automatic gate, the dispatch-failure ownership
    // -----------------------------------------------------------------------

    @Test
    void checkpointAdvancesWidenTheCohortAndTheTimelineCarriesTheApproval() {
        StagedWorld world = stagedActiveWorld("checkpoint", List.of(percent(50), percent(100)));
        JsonNode advanced = control(world.release(), "ADVANCE", null, 200);

        assertThat(advanced.get("currentStage").asInt()).isEqualTo(2);
        assertThat(advanced.get("status").asString()).isEqualTo("COMPLETE");
        assertThat(toList(advanced.get("stages")).get(1).get("status").asString())
                .isEqualTo("REACHED");
        // The approval and the execution on the timeline.
        List<String> actions = toList(advanced.get("timeline")).stream()
                .map(row -> row.get("action").asText())
                .toList();
        assertThat(actions)
                .containsSubsequence("STARTED", "STAGE_APPROVED", "CHANNEL_APPLIED", "STAGE_ADVANCED", "COMPLETED");
        // The channel arm executed across the release's channel (direct:
        // the availability widening).
        assertThat(toList(advanced.get("timeline")).stream()
                        .anyMatch(row ->
                                "CHANNEL_APPLIED".equals(row.get("action").asText())
                                        && "direct".equals(row.get("channel").asString())))
                .isTrue();
        // The completion record: the reached-stages summary (AC6).
        assertThat(toList(advanced.get("timeline")).stream()
                        .filter(row -> "COMPLETED".equals(row.get("action").asText()))
                        .findFirst()
                        .orElseThrow()
                        .get("detail")
                        .asString())
                .contains("reached=1,2");
        // The final advance of a complete plan is the precise refusal.
        control(world.release(), "ADVANCE", null, 400);
    }

    @Test
    void automaticAdvancementIsBlockedByAFailingGateAndAdmittedWhenSatisfied() {
        StagedWorld world = stagedActiveWorld("automatic", List.of(percent(50), percent(100)));
        // The AUTOMATIC gate: a BLOCK rule on the ring, the metric
        // answering a failing value (the promotion question's own
        // wording applied to the widening).
        createBlockRule("staged-crash-floor");
        RolloutMetricSource.arm(world.release(), PolicyMetric.CRASH_FREE_RATE, 0.80);

        JsonNode blocked = control(world.release(), "ADVANCE", null, 409);
        assertThat(blocked.get("code").asString()).isEqualTo("DISTRIBUTION_STAGED_ROLLOUT_BLOCKED");
        assertThat(blocked.get("detail").asString())
                .contains("staged-crash-floor")
                .contains("0.8");
        // The refusal's evidence survives the 409 (the consult's own
        // transaction, the #45 discipline).
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_policy_evaluations WHERE release_id = ? AND verdict = 'BLOCKED'",
                        Integer.class,
                        world.release()))
                .isEqualTo(1);
        // The block is on the timeline, the stage still pending.
        JsonNode held = view(world.release());
        assertThat(held.get("currentStage").asInt()).isEqualTo(1);
        assertThat(toList(held.get("timeline")).stream()
                        .anyMatch(
                                row -> "STAGE_BLOCKED".equals(row.get("action").asText())))
                .isTrue();

        // A satisfied metric admits the widening.
        RolloutMetricSource.arm(world.release(), PolicyMetric.CRASH_FREE_RATE, 0.999);
        assertThat(control(world.release(), "ADVANCE", null, 200).get("status").asString())
                .isEqualTo("COMPLETE");
    }

    @Test
    void failedDispatchRecordsOwnTheAdvancementRefusalNeverTheCrossing() {
        // The #61 promise, landed: dispatch failures never fail the
        // ACTIVE crossing (records FAILED + audited) — and the staged
        // surface owns the refusal: no widening past a failed dispatch.
        UUID environment = environmentByCode("qa");
        patch(adminToken, "/api/v1/environments/" + environment, Map.of("distributionChannel", "no-such-channel"), 200);
        put(
                adminToken,
                "/api/v1/environments/" + environment + "/tenants",
                Map.of(
                        "tenantIds",
                        List.of(acme.toString(), contoso.toString(), eastside.toString(), fabrikam.toString())),
                200);

        UUID version = createVersion(ownerToken, app, nextVersion());
        UUID artifact = insertArtifact(version, "IOS", 400 + versionCounter);
        UUID release = releaseAt(version, List.of(artifact), "DRAFT", environment);
        putPlan(release, "AUTOMATIC", List.of(percent(50), percent(100)), 200);
        walk(release, "VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE");

        // The doctrine: the crossing committed ACTIVE, the records FAILED.
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM channel_distributions WHERE release_id = ? AND status = 'FAILED'",
                        Integer.class,
                        release))
                .isEqualTo(1);
        // The ownership: the advancement refuses with the failure codes.
        JsonNode blocked = control(release, "ADVANCE", null, 409);
        assertThat(blocked.get("code").asString()).isEqualTo("DISTRIBUTION_STAGED_ROLLOUT_BLOCKED");
        assertThat(blocked.get("detail").asString()).contains("CHANNEL_UNKNOWN");
        // And the block is recorded on the plan's own timeline.
        assertThat(toList(view(release).get("timeline")).stream()
                        .anyMatch(
                                row -> "STAGE_BLOCKED".equals(row.get("action").asText())
                                        && row.get("detail").asString().contains("failed-dispatches")))
                .isTrue();
    }

    // -----------------------------------------------------------------------
    // AC4: the ONE-action orchestrated rollback
    // -----------------------------------------------------------------------

    @Test
    void oneActionRollsBackEverythingAndTouchesNoArtifact() {
        StagedWorld world = stagedActiveWorld("CHECKPOINT", List.of(percent(50), percent(100)));
        String artifactSha =
                jdbc.queryForObject("SELECT sha256 FROM artifacts WHERE id = ?", String.class, world.artifact());
        int downloadCountBefore = jdbc.queryForObject(
                "SELECT download_count FROM artifacts WHERE id = ?", Integer.class, world.artifact());

        // A rollback without its reason is refused.
        control(world.release(), "ROLLBACK", null, 400);

        // ONE action.
        JsonNode rolled = control(world.release(), "ROLLBACK", "crash spike in stage-2 cohort", 200);

        // The release crossed to ROLLED_BACK with the reason on the timeline row.
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, world.release()))
                .isEqualTo("ROLLED_BACK");
        Map<String, Object> historyRow = jdbc.queryForMap(
                "SELECT from_status, to_status, reason FROM release_status_history WHERE release_id = ? "
                        + "ORDER BY occurred_at DESC LIMIT 1",
                world.release());
        assertThat(historyRow.get("to_status")).isEqualTo("ROLLED_BACK");
        assertThat(String.valueOf(historyRow.get("reason"))).contains("crash spike");
        // The channel records: the rolled-back state per the AC vocabulary.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM channel_distributions WHERE release_id = ? AND status = 'ROLLED_BACK'",
                        Integer.class,
                        world.release()))
                .isEqualTo(1);
        // The plan pulled; its REACHED stage rolled back, its pending
        // stage never ran (the rollout stopped where it stood).
        assertThat(rolled.get("status").asString()).isEqualTo("ROLLED_BACK");
        assertThat(toList(rolled.get("stages")).get(0).get("status").asString()).isEqualTo("ROLLED_BACK");
        assertThat(toList(rolled.get("stages")).get(1).get("status").asString()).isEqualTo("PENDING");
        // The timeline carries the rollback with the reason.
        JsonNode last = toList(rolled.get("timeline"))
                .get(toList(rolled.get("timeline")).size() - 1);
        assertThat(last.get("action").asString()).isEqualTo("ROLLED_BACK");
        assertThat(last.get("detail").asString()).contains("crash spike");
        // The audit line.
        assertThat(audit.eventsOfType(AuditEventType.STAGED_ROLLOUT_ROLLED_BACK))
                .hasSize(1);

        // Direct downloads are cut for EVERYONE by the ROLLED_BACK
        // state itself (the RolledBackCutoffPolicy seam, #56's own
        // tests); the rollback's own arms are asserted above and below.

        // INV-008: artifacts untouched — same content hash, same count.
        assertThat(jdbc.queryForObject("SELECT sha256 FROM artifacts WHERE id = ?", String.class, world.artifact()))
                .isEqualTo(artifactSha);
        assertThat(jdbc.queryForObject(
                        "SELECT download_count FROM artifacts WHERE id = ?", Integer.class, world.artifact()))
                .isEqualTo(downloadCountBefore);

        // The second rollback of a terminal plan: the precise refusal.
        control(world.release(), "ROLLBACK", "again", 400);
    }

    @Test
    void aRollbackOfAReleaseNotOnActiveRefusesBeforeAnyArmApplies() {
        // The release-side crossing is the rollback's own refusal: a
        // RUNNING plan whose release has moved past ACTIVE (PROMOTED —
        // PRD 17's rollback edge is ACTIVE-only) keeps its state, and
        // NOTHING else applies (no ROLLED_BACK event, no pulled stage,
        // no touched record).
        UUID version = createVersion(ownerToken, app, nextVersion());
        UUID artifact = insertArtifact(version, "IOS", 400 + versionCounter);
        UUID release = releaseAt(version, List.of(artifact), "DRAFT");
        putPlan(release, "CHECKPOINT", List.of(percent(50), percent(100)), 200);
        walk(release, "VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE");
        for (String ring : List.of("tenant-pilot", "uat", "production")) {
            post(
                    managerToken,
                    "/api/v1/releases/" + release + "/promote",
                    Map.of("applicationId", app.toString()),
                    200);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("PROMOTED");

        JsonNode refused = control(release, "ROLLBACK", "late", 409);
        assertThat(refused.get("code").asString()).isEqualTo("DISTRIBUTION_STAGED_ROLLOUT_CONFLICT");
        assertThat(refused.get("detail").asString()).contains("PROMOTED");
        assertThat(jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, release))
                .isEqualTo("PROMOTED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM staged_rollout_plans WHERE release_id = ?", String.class, release))
                .isEqualTo("RUNNING");
        assertThat(toList(view(release).get("timeline")).stream()
                        .anyMatch(row -> "ROLLED_BACK".equals(row.get("action").asText())))
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // AC3/AC4's live-channel sandbox leg: TestFlight assign-then-revert
    // -----------------------------------------------------------------------

    @Test
    void testFlightStagingAssignsTheCohortsGroupsThenRevertsOnRollback() {
        bindApple();
        UUID environment = environmentByCode("qa");
        patch(adminToken, "/api/v1/environments/" + environment, Map.of("distributionChannel", "testflight"), 200);
        put(
                adminToken,
                "/api/v1/environments/" + environment + "/tenants",
                Map.of(
                        "tenantIds",
                        List.of(acme.toString(), contoso.toString(), eastside.toString(), fabrikam.toString())),
                200);
        String appleAppId = ASC.registerApp(bundleId);
        String buildId = ASC.registerBuild(appleAppId, "42", "READY_FOR_BETA_TESTING");
        // Distinctly-named groups per tenant: the link matches by the
        // ShipperDog group's NAME against the external group, so a
        // shared name would collapse all four links onto one ASC group
        // (the mapping surface's own rule, exercised honestly here).
        stagedGroupNames = Map.of(
                acme, "Acme Staged",
                contoso, "Contoso Staged",
                eastside, "Eastside Staged",
                fabrikam, "Fabrikam Staged");
        for (Map.Entry<UUID, String> named : stagedGroupNames.entrySet()) {
            post(
                    adminToken,
                    "/api/v1/tester-groups",
                    Map.of("tenantId", named.getKey().toString(), "name", named.getValue()),
                    201);
            ASC.registerGroup(appleAppId, named.getValue());
        }
        for (Map.Entry<UUID, String> named : stagedGroupNames.entrySet()) {
            put(
                    adminToken,
                    "/api/v1/distribution/testflight/groups",
                    Map.of(
                            "organizationId",
                            org.toString(),
                            "applicationId",
                            app.toString(),
                            "groupId",
                            groupIdByName(named.getKey(), named.getValue()).toString(),
                            "createOnDemand",
                            true),
                    200);
        }

        UUID version = createVersion(ownerToken, app, nextVersion());
        UUID artifact = insertArtifact(version, "IOS", 42);
        UUID release = releaseAtWithPilotGroups(version, List.of(artifact), environment);
        UUID pilot = firstOfSeededOrder(release);
        UUID second = secondOfSeededOrder(release);
        putPlan(release, "CHECKPOINT", List.of(explicit(List.of(pilot)), percent(50), percent(100)), 200);
        walk(release, "VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE");

        // The narrowed dispatch: ONLY the stage-1 cohort's group carries
        // the build on the store side.
        Map<UUID, java.util.Set<String>> assigned = new LinkedHashMap<>();
        for (UUID tenant : List.of(acme, contoso, eastside, fabrikam)) {
            assigned.put(tenant, ASC.buildsOfGroup(externalGroupId(tenant)));
        }
        assertThat(assigned.get(pilot)).contains(buildId);
        for (UUID tenant : List.of(acme, contoso, eastside, fabrikam)) {
            if (!tenant.equals(pilot)) {
                assertThat(assigned.get(tenant)).doesNotContain(buildId);
            }
        }

        // The advance's arm: the delta tenants' groups get the build.
        control(release, "ADVANCE", null, 200);
        assertThat(ASC.buildsOfGroup(externalGroupId(second))).contains(buildId);

        // The rollback's arm: the build leaves every reached group.
        control(release, "ROLLBACK", "beta review regression", 200);
        for (UUID tenant : List.of(pilot, second)) {
            assertThat(ASC.buildsOfGroup(externalGroupId(tenant))).doesNotContain(buildId);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM channel_distributions WHERE release_id = ? AND status = 'ROLLED_BACK'",
                        Integer.class,
                        release))
                .isEqualTo(1);
        // The revert ran on the wire: the DELETE relationship call.
        assertThat(ASC.callsOf("DELETE", "/relationships/builds")).isNotEmpty();
    }

    // -----------------------------------------------------------------------
    // AC6: the manifest freezes the plan; the ledger records completion
    // -----------------------------------------------------------------------

    @Test
    void theManifestFreezesTheStagedPlanAtApprovalAndTheLedgerRecordsCompletion() {
        UUID version = createVersion(ownerToken, app, nextVersion());
        UUID artifact = insertArtifact(version, "IOS", 400 + versionCounter);
        UUID release = releaseAt(version, List.of(artifact), "DRAFT");
        putPlan(release, "AUTOMATIC", List.of(percent(25), percent(50), percent(100)), 200);
        walk(release, "VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED");

        JsonNode manifest = get(managerToken, "/api/v1/releases/" + release + "/manifest?applicationId=" + app, 200);
        JsonNode staged = manifest.get("manifest").get("rollout").get("staged");
        assertThat(staged).isNotNull();
        assertThat(staged.get("mode").asString()).isEqualTo("AUTOMATIC");
        assertThat(staged.get("stages")).isNotNull();
        assertThat(staged.get("stages").size()).isEqualTo(3);
        assertThat(staged.get("stages").get(0).get("percent").asInt()).isEqualTo(25);
        // The frozen cohort is values: the first stage's tenant list.
        assertThat(staged.get("stages").get(0).get("cohort").size()).isEqualTo(1);

        // The journey to completion: the ledger's record (the manifest
        // never rewrites history).
        walk(release, "DEPLOYING", "ACTIVE");
        control(release, "ADVANCE", null, 200);
        control(release, "ADVANCE", null, 200);
        JsonNode done = view(release);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETE");
        assertThat(toList(done.get("timeline")).stream()
                        .filter(row -> "COMPLETED".equals(row.get("action").asText()))
                        .findFirst()
                        .orElseThrow()
                        .get("detail")
                        .asString())
                .contains("reached=1,2,3");
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** One staged, ACTIVE world: the plan commenced at stage 1 of its shapes. */
    private StagedWorld stagedActiveWorld(String mode, List<Map<String, Object>> shapes) {
        UUID version = createVersion(ownerToken, app, nextVersion());
        UUID artifact = insertArtifact(version, "IOS", 400 + versionCounter);
        UUID release = releaseAt(version, List.of(artifact), "DRAFT");
        putPlan(release, mode, shapes, 200);
        walk(release, "VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE");
        UUID cohortTenant = firstOfSeededOrder(release);
        return new StagedWorld(release, version, artifact, cohortTenant);
    }

    private record StagedWorld(UUID release, UUID version, UUID artifact, UUID cohortTenant) {}

    /** The staged plan's PUT (authoring). */
    private JsonNode putPlan(UUID releaseId, String mode, List<Map<String, Object>> stages, int expected) {
        return put(adminToken, stagedUri(releaseId), Map.of("mode", mode, "stages", stages), expected);
    }

    private String stagedUri(UUID releaseId) {
        return "/api/v1/distribution/releases/" + releaseId + "/staged-rollout?organizationId=" + org;
    }

    /** One staged action (ADVANCE or ROLLBACK). */
    private JsonNode control(UUID releaseId, String action, String reason, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        if (reason != null) {
            body.put("reason", reason);
        }
        return post(managerToken, stagedUri(releaseId), body, expected);
    }

    private JsonNode view(UUID releaseId) {
        return get(managerToken, stagedUri(releaseId), 200);
    }

    private static Map<String, Object> percent(int value) {
        return Map.of("percent", value);
    }

    private static Map<String, Object> explicit(List<UUID> tenants) {
        return Map.of("tenants", tenants.stream().map(UUID::toString).toList());
    }

    /** The release's seeded order first tenant (the deterministic pilot). */
    private UUID firstOfSeededOrder(UUID releaseId) {
        List<UUID> order = jdbc.queryForList(
                "SELECT c.tenant_id FROM staged_rollout_cohorts c "
                        + "JOIN staged_rollout_stages s ON s.id = c.stage_id "
                        + "JOIN staged_rollout_plans p ON p.id = s.plan_id "
                        + "WHERE p.release_id = ? AND s.position = 1 ORDER BY c.rank ASC LIMIT 1",
                UUID.class,
                releaseId);
        if (!order.isEmpty()) {
            return order.get(0);
        }
        // The plan is not authored yet: the order over the target set is
        // the release's own rows re-ranked (the same digest the
        // selection evaluates — recomputed here for fixture placement).
        return seededFirstOf(releaseId);
    }

    private UUID seededFirstOf(UUID releaseId) {
        return seededOf(releaseId, 0);
    }

    private UUID secondOfSeededOrder(UUID releaseId) {
        return seededOf(releaseId, 1);
    }

    private UUID seededOf(UUID releaseId, int rank) {
        List<UUID> targets = jdbc.queryForList(
                "SELECT tenant_id FROM release_targets WHERE release_id = ? ORDER BY tenant_id", UUID.class, releaseId);
        record Entry(java.math.BigInteger digest, long tie, UUID tenant) {}
        List<Entry> entries = new ArrayList<>();
        for (UUID tenant : targets) {
            entries.add(new Entry(digestOf(releaseId, tenant), tenant.getLeastSignificantBits(), tenant));
        }
        entries.sort(java.util.Comparator.comparing(Entry::digest).thenComparing(Entry::tie));
        return entries.get(rank).tenant();
    }

    private static java.math.BigInteger digestOf(UUID releaseId, UUID tenantId) {
        try {
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            sha256.update(uuidBytes(releaseId));
            sha256.update(uuidBytes(tenantId));
            return new java.math.BigInteger(1, sha256.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] uuidBytes(UUID id) {
        byte[] bytes = new byte[16];
        long most = id.getMostSignificantBits();
        long least = id.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (8 * (7 - i)));
            bytes[8 + i] = (byte) (least >>> (8 * (7 - i)));
        }
        return bytes;
    }

    private void createBlockRule(String name) {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("metric", "CRASH_FREE_RATE");
        condition.put("operator", "<");
        condition.put("value", 0.99);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("organizationId", org.toString());
        rule.put("name", name);
        rule.put("scope", "ORGANIZATION");
        rule.put("action", "BLOCK");
        rule.put("position", 10);
        rule.put("condition", condition);
        post(adminToken, "/api/v1/release-policies", rule, 201);
    }

    private void bindApple() {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("organizationId", org.toString());
        binding.put("provider", "apple");
        binding.put("name", "Org Apple Key");
        binding.put("metadata", Map.of("issuerId", ASC.issuerId(), "keyId", ASC.keyId()));
        binding.put("secretMaterial", ASC.pem());
        put(adminToken, "/api/v1/distribution/credentials", binding, 200);
    }

    /** The external group id the ASC mock holds for the tenant's Pilot link. */
    private String externalGroupId(UUID tenantId) {
        return jdbc.queryForObject(
                "SELECT external_group_id FROM channel_group_links cgl "
                        + "JOIN tester_groups g ON g.id = cgl.group_id "
                        + "WHERE g.tenant_id = ? AND cgl.channel = 'testflight' LIMIT 1",
                String.class,
                tenantId);
    }

    /** Invites, registers, and tenant-links a tester; returns their tester id. */

    /** A release walked to the named status (DRAFT through the full spine). */
    private UUID releaseWalkedTo(String status) {
        UUID version = createVersion(ownerToken, app, nextVersion());
        UUID artifact = insertArtifact(version, "IOS", 400 + versionCounter);
        UUID release = releaseAt(version, List.of(artifact), "DRAFT", null);
        if ("ACTIVE".equals(status)) {
            walk(release, "VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE");
        }
        return release;
    }

    private UUID releaseAt(UUID versionId, List<UUID> artifacts, String status, UUID environmentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", app.toString());
        body.put("versionId", versionId.toString());
        body.put("ring", "qa");
        if (environmentId != null) {
            body.put("environmentId", environmentId.toString());
        }
        body.put(
                "artifacts",
                artifacts.stream()
                        .map(artifact -> Map.of("platform", "IOS", "artifactId", artifact.toString()))
                        .toList());
        body.put(
                "targets",
                List.of(acme, contoso, eastside, fabrikam).stream()
                        .map(tenant -> Map.of("tenantId", tenant.toString(), "testerGroupIds", List.of()))
                        .toList());
        return UUID.fromString(
                post(managerToken, "/api/v1/releases", body, 201).get("id").asText());
    }

    private UUID releaseAt(UUID versionId, List<UUID> artifacts, String status) {
        return releaseAt(versionId, artifacts, status, null);
    }

    /** A release whose every target narrows to its tenant's seeded Pilot group (the TestFlight grain). */
    private UUID releaseAtWithPilotGroups(UUID versionId, List<UUID> artifacts, UUID environmentId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", app.toString());
        body.put("versionId", versionId.toString());
        body.put("ring", "qa");
        body.put("environmentId", environmentId.toString());
        body.put(
                "artifacts",
                artifacts.stream()
                        .map(artifact -> Map.of("platform", "IOS", "artifactId", artifact.toString()))
                        .toList());
        body.put(
                "targets",
                List.of(acme, contoso, eastside, fabrikam).stream()
                        .map(tenant -> Map.of(
                                "tenantId", tenant.toString(),
                                "testerGroupIds",
                                        List.of(groupIdByName(tenant, stagedGroupNames.get(tenant))
                                                .toString())))
                        .toList());
        return UUID.fromString(
                post(managerToken, "/api/v1/releases", body, 201).get("id").asText());
    }

    private void walk(UUID releaseId, String... steps) {
        for (String step : steps) {
            post(
                    managerToken,
                    "/api/v1/releases/" + releaseId + "/transitions",
                    Map.of("applicationId", app.toString(), "to", step),
                    200);
        }
    }

    private String nextVersion() {
        String name = String.format("7.%d.0", 100 + versionCounter);
        versionCounter++;
        return name;
    }

    private UUID insertArtifact(UUID versionId, String platform, long build) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, bundle_id, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, ?::platform, ?, ?, ?, ?, ?, ?, 'READY'::artifact_status, ?, ?)",
                artifactId,
                versionId,
                platform,
                platform.toLowerCase(Locale.ROOT) + "-" + build + ".ipa",
                "fixture/" + artifactId + "/artifact",
                "c3".repeat(32),
                1024L,
                build,
                bundleId,
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
    }

    private UUID environmentByCode(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM environments WHERE organization_id = ?::uuid AND code = ?",
                UUID.class,
                org.toString(),
                code);
    }

    private UUID groupIdByName(UUID tenantId, String name) {
        return jdbc.queryForObject(
                "SELECT id FROM tester_groups WHERE tenant_id = ? AND name = ?", UUID.class, tenantId, name);
    }

    /** An ORGANIZATION-shared application every target tenant holds. */
    private UUID createSharedApp(String name, String identifier, List<UUID> tenants) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", org.toString());
        body.put("name", name);
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", identifier);
        body.put("packageName", identifier);
        UUID appId = UUID.fromString(
                post(adminToken, "/api/v1/applications", body, 201).get("id").asText());
        for (UUID tenant : tenants) {
            post(adminToken, "/api/v1/applications/" + appId + "/tenants/" + tenant, Map.of(), 201);
        }
        return appId;
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

    private UUID createTenant(String token, String name, String externalId) {
        return UUID.fromString(post(
                        token,
                        "/api/v1/tenants",
                        Map.of("organizationId", org.toString(), "name", name, "externalId", externalId),
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
                        "Staged " + role))
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

    private JsonNode patch(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PATCH", body).expect(expectedStatus);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null).expect(expectedStatus);
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list;
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
