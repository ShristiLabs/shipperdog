package com.shipperdog.api.otabundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.otabundle.internal.repository.OtaRolloutPlanScopedRepository;
import com.shipperdog.api.otabundle.internal.service.OtaRolloutService;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The S16-06 acceptance flow (issue #168, ADR-0010 workstream 3,
 * D6/D10): the rollout plan's full life -- attached and COMMENCED on a
 * live OTA release, advanced stage by stage to COMPLETE, halted (the
 * manual, audit-trailed stop), paused, resumed -- with every act
 * leaving its three records ({@code ota_rollout_events} row, audit
 * entry, bus event) in one transaction, concurrent control answering
 * the optimistic-lock 409 instead of a lost update, and the
 * attachment's refusals precise. The deterministic bucketing itself
 * is pinned exactly by {@code OtaDeviceBucketingTest}; the
 * {@code OtaRolloutAccess} standing seam S16-07 will compose with is
 * asserted beside the view.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class OtaRolloutIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The compose GCS emulator image, in a container (ADR-0003) -- the bundle fixture streams bytes through it. */
    @Container
    static final GenericContainer<?> GCS = new GenericContainer<>(
                    DockerImageName.parse("fsouza/fake-gcs-server:1.56.1"))
            .withCommand("-scheme", "http", "-host", "0.0.0.0", "-port", "4443")
            .withExposedPorts(4443);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @DynamicPropertySource
    static void artifactStorage(DynamicPropertyRegistry registry) {
        registry.add(
                "shipperdog.artifact.storage.host", () -> "http://" + GCS.getHost() + ":" + GCS.getMappedPort(4443));
    }

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private OtaRolloutAccess rolloutAccess;

    @Autowired
    private OtaRolloutService rollouts;

    @Autowired
    private OtaRolloutPlanScopedRepository planRows;

    @Autowired
    private TenantScopeExecutor scopeExecutor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private RestClient client;

    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // -----------------------------------------------------------------------
    // AC1 + AC4: attach/commence, advance one stage at a time, complete
    // -----------------------------------------------------------------------

    @Test
    void rolloutCommencesAdvancesAndCompletesOneStageAtATime() throws Exception {
        OtaWorld world = otaWorld("ro-life");
        UUID releaseId = activeOtaRelease(world, "1.4.2");

        Map<String, Object> plan = rolloutBody(world, releaseId, "CHECKPOINT", stages(500, 2500, 10000));
        JsonNode attached = post(world.managerToken, "/api/v1/ota/rollouts", plan, 201);
        assertThat(attached.get("status").asText()).isEqualTo("RUNNING");
        assertThat(attached.get("currentStage").asInt()).isEqualTo(1);
        assertThat(attached.get("mode").asText()).isEqualTo("CHECKPOINT");
        assertThat(attached.get("planSalt").asString()).hasSize(32).matches("[0-9a-f]{32}");

        // The commencement's three records, in one transaction: the
        // timeline row, the audit entry, the bus event.
        assertThat(timelineActions(releaseId)).containsExactly("COMMENCED");
        assertThat(auditCount(releaseId, "OTA_ROLLOUT_COMMENCED")).isEqualTo(1);
        assertThat(outboxActions(releaseId)).containsExactly("COMMENCED");

        // The standing seam S16-07 composes with (the carry-forward):
        // the serving stage's cumulative basis points and the salt.
        var standing = rolloutAccess.rolloutStandingOf(world.app, releaseId).orElseThrow();
        assertThat(standing.status()).isEqualTo("RUNNING");
        assertThat(standing.currentStage()).isEqualTo(1);
        assertThat(standing.currentBasisPoints()).isEqualTo(500);
        assertThat(standing.planSalt()).isEqualTo(attached.get("planSalt").asString());

        // The ladder: stage 1 REACHED, the rest PENDING.
        JsonNode view =
                get(world.managerToken, "/api/v1/ota/rollouts/" + releaseId + "?applicationId=" + world.app, 200);
        assertThat(view.get("stages").size()).isEqualTo(3);
        assertThat(view.get("stages").get(0).get("status").asText()).isEqualTo("REACHED");
        assertThat(view.get("stages").get(1).get("status").asText()).isEqualTo("PENDING");
        assertThat(view.get("stages").get(0).get("reachedAt")).isNotNull();

        // One stage at a time: the advance lands stage 2, nothing more.
        JsonNode advanced = control(world, releaseId, "ADVANCE", null, 200);
        assertThat(advanced.get("currentStage").asInt()).isEqualTo(2);
        assertThat(advanced.get("status").asText()).isEqualTo("RUNNING");
        assertThat(timelineActions(releaseId)).containsExactly("COMMENCED", "ADVANCED");
        assertThat(outboxActions(releaseId)).containsExactlyInAnyOrder("COMMENCED", "ADVANCED");
        assertThat(auditCount(releaseId, "OTA_ROLLOUT_ADVANCED")).isEqualTo(1);

        // The serving basis widened -- the standing seam follows.
        assertThat(rolloutAccess
                        .rolloutStandingOf(world.app, releaseId)
                        .orElseThrow()
                        .currentBasisPoints())
                .isEqualTo(2500);

        // The final advance completes the rollout: ADVANCED + COMPLETED
        // rows, both bus events, the COMPLETE standing.
        JsonNode completed = control(world, releaseId, "ADVANCE", null, 200);
        assertThat(completed.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(completed.get("currentStage").asInt()).isEqualTo(3);
        assertThat(timelineActions(releaseId)).containsExactly("COMMENCED", "ADVANCED", "ADVANCED", "COMPLETED");
        assertThat(outboxActions(releaseId))
                .containsExactlyInAnyOrder("COMMENCED", "ADVANCED", "ADVANCED", "COMPLETED");
        assertThat(auditCount(releaseId, "OTA_ROLLOUT_COMPLETED")).isEqualTo(1);
        assertThat(rolloutAccess
                        .rolloutStandingOf(world.app, releaseId)
                        .orElseThrow()
                        .currentBasisPoints())
                .isEqualTo(10000);

        // Nothing advances past the final stage.
        assertThat(control(world, releaseId, "ADVANCE", null, 409).get("code").asText())
                .isEqualTo("ROLLOUT_ACTION_INVALID");

        // No auto-halt anywhere (D10): nothing but the operator moved
        // the plan -- the timeline carries no act nobody performed.
        assertThat(timelineActions(releaseId)).hasSize(4);
    }

    // -----------------------------------------------------------------------
    // AC2: halt -> HALTED, pause -> PAUSED, resume -> RUNNING
    // -----------------------------------------------------------------------

    @Test
    void haltPauseAndResumeAreAuditedOperatorCrossings() throws Exception {
        OtaWorld world = otaWorld("ro-halt");
        UUID releaseId = activeOtaRelease(world, "2.0.0");
        post(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, releaseId, "CHECKPOINT", stages(1000, 10000)),
                201);

        // The halt says why (mandatory): a reasonless halt is refused.
        assertThat(control(world, releaseId, "HALT", null, 400).get("code").asText())
                .isEqualTo("INVALID_REQUEST");

        JsonNode halted = control(world, releaseId, "HALT", "crash regression on android", 200);
        assertThat(halted.get("status").asText()).isEqualTo("HALTED");
        assertThat(timelineActions(releaseId)).containsExactly("COMMENCED", "HALTED");
        assertThat(timelineDetailOf(releaseId, "HALTED")).contains("crash regression on android");
        assertThat(auditCount(releaseId, "OTA_ROLLOUT_HALTED")).isEqualTo(1);
        assertThat(outboxActions(releaseId)).containsExactlyInAnyOrder("COMMENCED", "HALTED");

        // A halted plan neither advances nor re-halts; it resumes.
        assertThat(control(world, releaseId, "ADVANCE", null, 409).get("code").asText())
                .isEqualTo("ROLLOUT_ACTION_INVALID");
        assertThat(control(world, releaseId, "HALT", "again", 409).get("code").asText())
                .isEqualTo("ROLLOUT_ACTION_INVALID");

        JsonNode resumed = control(world, releaseId, "RESUME", "hotfix verified", 200);
        assertThat(resumed.get("status").asText()).isEqualTo("RUNNING");
        assertThat(timelineActions(releaseId)).containsExactly("COMMENCED", "HALTED", "RESUMED");

        // The pause: the temporary stop (its own timeline vocabulary,
        // V60's addition), freezing at the current stage.
        JsonNode paused = control(world, releaseId, "PAUSE", "weekend freeze", 200);
        assertThat(paused.get("status").asText()).isEqualTo("PAUSED");
        assertThat(paused.get("currentStage").asInt()).isEqualTo(1);
        assertThat(timelineActions(releaseId)).containsExactly("COMMENCED", "HALTED", "RESUMED", "PAUSED");
        assertThat(auditCount(releaseId, "OTA_ROLLOUT_PAUSED")).isEqualTo(1);

        // A paused plan does not advance; it is still stoppable (pause
        // was only ever the temporary stop); it resumes in place.
        assertThat(control(world, releaseId, "ADVANCE", null, 409).get("code").asText())
                .isEqualTo("ROLLOUT_ACTION_INVALID");
        assertThat(control(world, releaseId, "HALT", "escalation", 200)
                        .get("status")
                        .asText())
                .isEqualTo("HALTED");
        assertThat(control(world, releaseId, "RESUME", null, 200).get("status").asText())
                .isEqualTo("RUNNING");
        assertThat(timelineActions(releaseId))
                .containsExactly("COMMENCED", "HALTED", "RESUMED", "PAUSED", "HALTED", "RESUMED");

        // The stop never retreated the ladder: stage 1 still the
        // serving stage, its REACHED standing intact.
        JsonNode view =
                get(world.managerToken, "/api/v1/ota/rollouts/" + releaseId + "?applicationId=" + world.app, 200);
        assertThat(view.get("currentStage").asInt()).isEqualTo(1);
        assertThat(view.get("stages").get(0).get("status").asText()).isEqualTo("REACHED");
        assertThat(view.get("stages").get(1).get("status").asText()).isEqualTo("PENDING");

        // A live plan has nothing to resume.
        assertThat(control(world, releaseId, "RESUME", null, 409).get("code").asText())
                .isEqualTo("ROLLOUT_ACTION_INVALID");
        assertThat(control(world, releaseId, "PAUSE", null, 200).get("status").asText())
                .isEqualTo("PAUSED");
    }

    // -----------------------------------------------------------------------
    // AC3: concurrent control answers the optimistic-lock 409, never a lost update
    // -----------------------------------------------------------------------

    @Test
    void concurrentControlOnOnePlanRefusesTheLoser() throws Exception {
        OtaWorld world = otaWorld("ro-race");
        UUID releaseId = activeOtaRelease(world, "3.0.0");
        post(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, releaseId, "CHECKPOINT", stages(500, 10000)),
                201);

        // Two concurrent halts of one plan: PostgreSQL serializes the
        // writes; exactly one lands, the other answers 409 (either the
        // optimistic-lock conflict or -- in the fully serialized worst
        // case -- the already-halted refusal), and exactly ONE halt
        // lands on the timeline. No lost update: no double row, no
        // version moved twice.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger halted = new AtomicInteger();
        List<Future<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int attempt = i;
            attempts.add(pool.submit(() -> {
                start.await();
                Recording recording = postRaw(
                        world.managerToken,
                        "/api/v1/ota/rollouts/" + releaseId + "/halt",
                        Map.of("applicationId", world.app.toString(), "reason", "concurrent halt " + attempt));
                if (recording.status == 200) {
                    halted.incrementAndGet();
                }
                return recording.status;
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> attempt : attempts) {
            statuses.add(attempt.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(halted.get()).isEqualTo(1);
        assertThat(timelineActions(releaseId)).containsExactly("COMMENCED", "HALTED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ota_rollout_events WHERE plan_id ="
                                + " (SELECT id FROM ota_rollout_plans WHERE release_id = ?) AND action = 'HALTED'",
                        Integer.class,
                        releaseId))
                .isEqualTo(1);
        // The optimistic stamp moved exactly once (0 at birth, 1 after
        // the one halt that landed).
        assertThat(jdbc.queryForObject(
                        "SELECT version FROM ota_rollout_plans WHERE release_id = ?", Long.class, releaseId))
                .isEqualTo(1L);
    }

    /**
     * The optimistic-lock translation, pinned deterministically (no
     * race to win): a transaction loads the plan, a concurrent halt
     * commits beneath it, and the stale transaction's own control act
     * -- the service's re-read answered from its persistence context's
     * cached row -- flushes into the version guard and answers the
     * module's 409 code, the #181 flush-inside-the-guard discipline.
     */
    @Test
    void aStaleControlActAnswersTheOptimisticConflict() throws Exception {
        OtaWorld world = otaWorld("ro-stale");
        UUID releaseId = activeOtaRelease(world, "3.1.0");
        post(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, releaseId, "CHECKPOINT", stages(500, 10000)),
                201);

        TransactionDefinition definition = new DefaultTransactionDefinition();
        TransactionStatus held = transactionManager.getTransaction(definition);
        // The stale load, inside the held transaction and the org scope
        // (the binding ends with the lambda; the transaction stays).
        scopeExecutor.inOrganizationScope(
                world.org,
                world.managerId,
                () -> planRows.findOneScoped((root, query, builder) -> builder.equal(root.get("releaseId"), releaseId))
                        .orElseThrow());
        // The concurrent act commits beneath it.
        control(world, releaseId, "PAUSE", null, 200);
        // The stale transaction's own act: joined by the service (its
        // own scope binding is fresh -- the load's ended), the re-read
        // answered from the persistence context's cached row, the
        // flush refuses, the catch translates.
        try {
            rollouts.control(world.managerId, world.app, releaseId, "HALT", "stale writer");
            throw new IllegalStateException("the stale act was not refused");
        } catch (OtaBundleException refused) {
            assertThat(refused.code()).isEqualTo(OtaBundleException.Code.ROLLOUT_CONFLICT);
        } finally {
            transactionManager.rollback(held);
        }
        // The winner's pause stands; the stale halt wrote nothing.
        assertThat(timelineActions(releaseId)).containsExactly("COMMENCED", "PAUSED");
    }

    // -----------------------------------------------------------------------
    // The attachment's refusals (AC1's guard rails)
    // -----------------------------------------------------------------------

    @Test
    void attachmentRefusesHonestly() throws Exception {
        OtaWorld world = otaWorld("ro-guard");
        JsonNode bundle = authorSignedBundle(world, "4.0.0", "ios");
        String ring = otaRing(world);
        UUID releaseId = createOtaRelease(world, bundle, ring);

        // A DRAFT release is not distributing: the precise 409.
        Recording notLive = postRaw(
                world.managerToken, "/api/v1/ota/rollouts", rolloutBody(world, releaseId, "CHECKPOINT", stages(10000)));
        assertThat(notLive.status).isEqualTo(409);
        assertThat(notLive.body.get("code").asText()).isEqualTo("ROLLOUT_RELEASE_NOT_LIVE");

        // A live STORE release carries no bundle: not this surface's.
        UUID storeVersion = createVersion(world.ownerToken, world.app, "9.0." + versionCounter++);
        UUID storeRelease = createStoreRelease(world, storeVersion);
        for (String state : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            transition(world.managerToken, world.app, storeRelease, state, null, 200);
        }
        Recording notOta = postRaw(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, storeRelease, "CHECKPOINT", stages(10000)));
        assertThat(notOta.status).isEqualTo(409);
        assertThat(notOta.body.get("code").asText()).isEqualTo("ROLLOUT_RELEASE_NOT_OTA");

        // The walk to live, then the attach lands and refuses a second.
        for (String state : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            transition(world.managerToken, world.app, releaseId, state, null, 200);
        }
        post(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, releaseId, "CHECKPOINT", stages(10000)),
                201);
        Recording duplicate = postRaw(
                world.managerToken, "/api/v1/ota/rollouts", rolloutBody(world, releaseId, "CHECKPOINT", stages(10000)));
        assertThat(duplicate.status).isEqualTo(409);
        assertThat(duplicate.body.get("code").asText()).isEqualTo("ROLLOUT_CONFLICT");

        // The ladder's shape: descending thresholds, a final stage
        // short of everyone, a staircase beyond ten, and a mode nobody
        // defined -- each names its field.
        Recording descending = postRaw(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, UUID.randomUUID(), "CHECKPOINT", stages(5000, 2500, 10000)));
        assertThat(descending.status).isEqualTo(400);
        assertThat(descending.body.get("errors").get("stages.1.basisPoints")).isNotNull();
        Recording shortFinal = postRaw(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, UUID.randomUUID(), "CHECKPOINT", stages(1000, 9000)));
        assertThat(shortFinal.status).isEqualTo(400);
        assertThat(shortFinal.body.get("errors").get("stages.1.basisPoints").asString())
                .contains("10000");
        List<Map<String, Object>> eleven = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            eleven.add(stage(Math.min(1000 * i, 10000)));
        }
        Recording tooMany = postRaw(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, UUID.randomUUID(), "CHECKPOINT", eleven));
        assertThat(tooMany.status).isEqualTo(400);
        assertThat(tooMany.body.get("errors").get("stages")).isNotNull();
        Recording badMode = postRaw(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, UUID.randomUUID(), "WHENEVER", stages(10000)));
        assertThat(badMode.status).isEqualTo(400);
        assertThat(badMode.body.get("errors").get("mode")).isNotNull();

        // The unknown release and the foreign application conceal.
        assertThat(postRaw(
                                world.managerToken,
                                "/api/v1/ota/rollouts",
                                rolloutBody(world, UUID.randomUUID(), "CHECKPOINT", stages(10000)))
                        .status)
                .isEqualTo(404);
        UUID foreignApp =
                createTenantApp(world.ownerToken, world.tenantId, "Ro Foreign App", "com.ro.foreign", List.of("IOS"));
        jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", foreignApp);
        Map<String, Object> foreignBody = rolloutBody(world, releaseId, "CHECKPOINT", stages(10000));
        foreignBody.put("applicationId", foreignApp.toString());
        assertThat(postRaw(world.managerToken, "/api/v1/ota/rollouts", foreignBody).status)
                .isEqualTo(404);
        assertThat(get(
                                world.managerToken,
                                "/api/v1/ota/rollouts/" + UUID.randomUUID() + "?applicationId=" + world.app,
                                404)
                        .get("code")
                        .asText())
                .isEqualTo("ROLLOUT_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // AC2 (the AUTOMATIC mode's gate): the policy engine's verdict
    // -----------------------------------------------------------------------

    @Test
    void automaticAdvancementConsultsThePolicyEngine() throws Exception {
        OtaWorld world = otaWorld("ro-auto");
        UUID releaseId = activeOtaRelease(world, "5.0.0");
        post(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, releaseId, "AUTOMATIC", stages(1000, 10000)),
                201);

        // The organization's BLOCK rule over the crash-free metric,
        // and a failing standing beneath it: 95 of 100 sessions.
        createBlockRule(world, "ota-crash-floor");
        insertSession(releaseId, world, 100, 5);

        Recording blocked = postRaw(
                world.managerToken,
                "/api/v1/ota/rollouts/" + releaseId + "/advance",
                Map.of("applicationId", world.app.toString()));
        assertThat(blocked.status).isEqualTo(409);
        assertThat(blocked.body.get("code").asText()).isEqualTo("ROLLOUT_BLOCKED");
        assertThat(blocked.body.get("detail").asString()).contains("ota-crash-floor");
        // The refusal left no act on the timeline (nothing happened)
        // and its evidence in the engine's own evaluation ledger.
        assertThat(timelineActions(releaseId)).containsExactly("COMMENCED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_policy_evaluations WHERE release_id = ? AND verdict = 'BLOCKED'",
                        Integer.class,
                        releaseId))
                .isEqualTo(1);

        // A recovered standing admits the widening: 995 of 1000.
        insertSession(releaseId, world, 900, 0);
        JsonNode widened = control(world, releaseId, "ADVANCE", null, 200);
        assertThat(widened.get("currentStage").asInt()).isEqualTo(2);
        assertThat(timelineActions(releaseId)).containsExactly("COMMENCED", "ADVANCED", "COMPLETED");
    }

    // -----------------------------------------------------------------------
    // AC1's terminal birth: the single-stage ladder is born COMPLETE
    // -----------------------------------------------------------------------

    @Test
    void aSingleStagePlanIsBornCompleteAndRefusesEveryControl() throws Exception {
        OtaWorld world = otaWorld("ro-born");
        UUID releaseId = activeOtaRelease(world, "6.0.0");

        // The one-stage ladder commences straight through to COMPLETE:
        // birth's COMMENCED row, the terminal COMPLETED row beside it,
        // both audit lines, both bus events, the full-space standing.
        JsonNode born = post(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, releaseId, "CHECKPOINT", stages(10000)),
                201);
        assertThat(born.get("status").asText()).isEqualTo("COMPLETE");
        assertThat(born.get("currentStage").asInt()).isEqualTo(1);
        assertThat(timelineActions(releaseId)).containsExactly("COMMENCED", "COMPLETED");
        assertThat(auditCount(releaseId, "OTA_ROLLOUT_COMMENCED")).isEqualTo(1);
        assertThat(auditCount(releaseId, "OTA_ROLLOUT_COMPLETED")).isEqualTo(1);
        assertThat(outboxActions(releaseId)).containsExactlyInAnyOrder("COMMENCED", "COMPLETED");
        var standing = rolloutAccess.rolloutStandingOf(world.app, releaseId).orElseThrow();
        assertThat(standing.status()).isEqualTo("COMPLETE");
        assertThat(standing.currentBasisPoints()).isEqualTo(10000);

        // A complete plan refuses every control act: nothing to halt
        // (D10's stop is for a serving rollout), nothing to pause,
        // nothing to resume.
        assertThat(control(world, releaseId, "HALT", "late", 409).get("code").asText())
                .isEqualTo("ROLLOUT_ACTION_INVALID");
        assertThat(control(world, releaseId, "PAUSE", null, 409).get("code").asText())
                .isEqualTo("ROLLOUT_ACTION_INVALID");
        assertThat(control(world, releaseId, "RESUME", null, 409).get("code").asText())
                .isEqualTo("ROLLOUT_ACTION_INVALID");
    }

    // -----------------------------------------------------------------------
    // AC1's shape bounds and the S16-07 seam's misses
    // -----------------------------------------------------------------------

    @Test
    void basisPointAndNoteBoundsRefuseAndTheSeamConceals() throws Exception {
        OtaWorld world = otaWorld("ro-bounds");
        UUID releaseId = activeOtaRelease(world, "6.1.0");
        post(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, releaseId, "CHECKPOINT", stages(1000, 10000)),
                201);

        // The threshold space is closed [1, 10000]: zero and
        // ten-thousand-and-one each name their field. The shape
        // validation runs before any release resolution, so the random
        // release id never resolves -- the refusal is the shape's own.
        Recording zeroBasis = postRaw(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, UUID.randomUUID(), "CHECKPOINT", stages(0, 10000)));
        assertThat(zeroBasis.status).isEqualTo(400);
        assertThat(zeroBasis.body.get("errors").get("stages.0.basisPoints")).isNotNull();
        Recording overBasis = postRaw(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, UUID.randomUUID(), "CHECKPOINT", stages(1, 10001)));
        assertThat(overBasis.status).isEqualTo(400);
        assertThat(overBasis.body.get("errors").get("stages.1.basisPoints")).isNotNull();

        // The note is 1 to 512 characters when present: blank and
        // overlong each name their field.
        Recording blankNote = postRaw(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, UUID.randomUUID(), "CHECKPOINT", List.of(noteStage(10000, " "))));
        assertThat(blankNote.status).isEqualTo(400);
        assertThat(blankNote.body.get("errors").get("stages.0.note")).isNotNull();
        Recording longNote = postRaw(
                world.managerToken,
                "/api/v1/ota/rollouts",
                rolloutBody(world, UUID.randomUUID(), "CHECKPOINT", List.of(noteStage(10000, "n".repeat(513)))));
        assertThat(longNote.status).isEqualTo(400);
        assertThat(longNote.body.get("errors").get("stages.0.note")).isNotNull();

        // The S16-07 seam's misses answer empty: an unknown release, a
        // release planned under another application, and the null shape
        // the device route must never assume away -- while the plan's
        // own pair stays present.
        UUID foreignApp = createTenantApp(
                world.ownerToken, world.tenantId, "Ro Bounds Foreign", "com.ro.bounds.foreign", List.of("IOS"));
        jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", foreignApp);
        assertThat(rolloutAccess.rolloutStandingOf(world.app, UUID.randomUUID()))
                .isEmpty();
        assertThat(rolloutAccess.rolloutStandingOf(foreignApp, releaseId)).isEmpty();
        assertThat(rolloutAccess.rolloutStandingOf(null, releaseId)).isEmpty();
        assertThat(rolloutAccess.rolloutStandingOf(world.app, releaseId)).isPresent();
    }

    // -----------------------------------------------------------------------
    // The world and its helpers
    // -----------------------------------------------------------------------

    private record OtaWorld(
            UUID org,
            UUID tenantId,
            UUID app,
            UUID managerId,
            String adminToken,
            String ownerToken,
            String managerToken,
            String developerToken) {}

    /** The OTA rollout world: an org, its personas, a tenant app with OTA enabled. */
    private OtaWorld otaWorld(String suffix) {
        UUID orgId = createOrg("Ro " + suffix + " Corp");
        String adminToken = loginOfOrgRoleIn(orgId, "ro-" + suffix + "-admin", "ORG_ADMIN");
        String ownerToken = loginOfOrgRoleIn(orgId, "ro-" + suffix + "-owner", "APP_OWNER");
        String managerToken = loginOfOrgRoleIn(orgId, "ro-" + suffix + "-manager", "RELEASE_MANAGER");
        String developerToken = loginOfOrgRoleIn(orgId, "ro-" + suffix + "-dev", "DEVELOPER");
        UUID managerId = userIdOf("ro-" + suffix + "-manager@example.com");
        UUID tenantId = createTenant(adminToken, orgId, "Ro " + suffix + " Bank", "crm-ro-" + suffix);
        UUID appId = createTenantApp(
                ownerToken,
                tenantId,
                "Ro " + suffix + " App",
                "com.ro." + suffix.replace("-", "") + ".bank",
                List.of("IOS", "ANDROID"));
        jdbc.update("UPDATE applications SET ota_enabled = true WHERE id = ?", appId);
        return new OtaWorld(orgId, tenantId, appId, managerId, adminToken, ownerToken, managerToken, developerToken);
    }

    /** The org's own ota-channel ring under an explicit code (one creation per code per world). */
    private String otaRing(OtaWorld world) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", world.org.toString());
        body.put("code", "ota");
        body.put("name", "OTA ota");
        body.put("position", 3);
        body.put("distributionChannel", "ota");
        post(world.adminToken, "/api/v1/rings", body, 201);
        return "ota";
    }

    /** One bundle authored, bytes streamed, finalized and signed: the SIGNED bundle view. */
    private JsonNode authorSignedBundle(OtaWorld world, String version, String tag) {
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("bundleVersion", version);
        body.put("platformTag", tag);
        body.put("fileName", "bundle-" + version + ".zip");
        JsonNode draft = post(world.developerToken, "/api/v1/ota/bundles", body, 201);
        byte[] bytes = payload(512);
        client.put()
                .uri(URI.create(draft.get("upload").get("uploadUrl").asText()))
                .header("Content-Range", "bytes 0-" + (bytes.length - 1) + "/" + bytes.length)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
                .exchange((request, response) -> new Recording(
                        response.getStatusCode().value(),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        mapper.createObjectNode()));
        return post(
                        world.developerToken,
                        "/api/v1/ota/bundles/" + draft.get("id").asText() + "/finalize",
                        Map.of("versionId", draft.get("versionId").asText()),
                        201)
                .get("bundle");
    }

    /** The publish: the OTA release on the ota ring, born DRAFT with its bundle RELEASED. */
    private UUID createOtaRelease(OtaWorld world, JsonNode bundle, String ringCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("versionId", bundle.get("versionId").asText());
        body.put("ring", ringCode);
        body.put(
                "artifacts",
                List.of(Map.of("platform", "IOS", "artifactId", bundle.get("id").asText())));
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("tenantId", world.tenantId.toString());
        target.put("testerGroupIds", List.of());
        body.put("targets", List.of(target));
        return UUID.fromString(post(world.managerToken, "/api/v1/releases", body, 201)
                .get("id")
                .asText());
    }

    /** The full walk to ACTIVE, then the rollout attachment target. */
    private UUID activeOtaRelease(OtaWorld world, String bundleVersion) {
        JsonNode bundle = authorSignedBundle(world, bundleVersion, "ios");
        UUID releaseId = createOtaRelease(world, bundle, otaRing(world));
        for (String state : List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")) {
            transition(world.managerToken, world.app, releaseId, state, null, 200);
        }
        return releaseId;
    }

    private UUID createStoreRelease(OtaWorld world, UUID versionId) {
        UUID artifactId = UUID.randomUUID();
        Map<String, Object> owner =
                jdbc.queryForMap("SELECT organization_id, tenant_id FROM versions WHERE id = ?", versionId);
        jdbc.update(
                "INSERT INTO artifacts (id, version_id, platform, file_name, storage_key, sha256, size, "
                        + "build_number, status, organization_id, tenant_id) "
                        + "VALUES (?, ?, 'IOS'::platform, ?, ?, ?, ?, ?, 'READY'::artifact_status, ?, ?)",
                artifactId,
                versionId,
                "fixture-" + versionCounter + ".ipa",
                "fixture/" + artifactId + "/artifact",
                "a1".repeat(32),
                1024L,
                400L + versionCounter,
                owner.get("organization_id"),
                owner.get("tenant_id"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("versionId", versionId.toString());
        body.put("ring", "qa");
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifactId.toString())));
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("tenantId", world.tenantId.toString());
        target.put("testerGroupIds", List.of());
        body.put("targets", List.of(target));
        return UUID.fromString(post(world.managerToken, "/api/v1/releases", body, 201)
                .get("id")
                .asText());
    }

    private void transition(String token, UUID applicationId, UUID releaseId, String to, String reason, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("to", to);
        if (reason != null) {
            body.put("reason", reason);
        }
        post(token, "/api/v1/releases/" + releaseId + "/transitions", body, expected);
    }

    private void createBlockRule(OtaWorld world, String name) {
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put("metric", "CRASH_FREE_RATE");
        condition.put("operator", "<");
        condition.put("value", 0.99);
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("organizationId", world.org.toString());
        rule.put("name", name);
        rule.put("scope", "ORGANIZATION");
        rule.put("action", "BLOCK");
        rule.put("position", 10);
        rule.put("condition", condition);
        post(world.adminToken, "/api/v1/release-policies", rule, 201);
    }

    /** One SESSION report row (the crash-free metric's release-keyed standing). */
    private void insertSession(UUID releaseId, OtaWorld world, int sessions, int crashes) {
        jdbc.update(
                "INSERT INTO analytics_events (id, organization_id, tenant_id, application_id, version_id, "
                        + "release_id, event_type, sessions, crash_sessions, anr_sessions, occurred_at, received_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::uuid, 'SESSION', ?, ?, 0, now(), now())",
                UUID.randomUUID(),
                world.org,
                world.tenantId,
                world.app,
                jdbc.queryForObject("SELECT version_id FROM releases WHERE id = ?", UUID.class, releaseId),
                releaseId,
                sessions,
                crashes);
    }

    private Map<String, Object> rolloutBody(
            OtaWorld world, UUID releaseId, String mode, List<Map<String, Object>> stages) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("releaseId", releaseId.toString());
        body.put("mode", mode);
        body.put("stages", stages);
        return body;
    }

    private static List<Map<String, Object>> stages(int... basisPoints) {
        List<Map<String, Object>> stages = new ArrayList<>();
        for (int basis : basisPoints) {
            stages.add(stage(basis));
        }
        return stages;
    }

    private static Map<String, Object> stage(int basisPoints) {
        return new LinkedHashMap<>(Map.of("basisPoints", basisPoints));
    }

    /** One ladder stage carrying a note (the note's own shape tests). */
    private static Map<String, Object> noteStage(int basisPoints, String note) {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("basisPoints", basisPoints);
        stage.put("note", note);
        return stage;
    }

    private JsonNode control(OtaWorld world, UUID releaseId, String action, String reason, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        if (reason != null) {
            body.put("reason", reason);
        }
        return post(
                world.managerToken, "/api/v1/ota/rollouts/" + releaseId + "/" + action.toLowerCase(), body, expected);
    }

    /** The plan's timeline actions, oldest first (the release's one plan). */
    private List<String> timelineActions(UUID releaseId) {
        return jdbc.queryForList(
                "SELECT e.action FROM ota_rollout_events e"
                        + " JOIN ota_rollout_plans p ON p.id = e.plan_id"
                        + " WHERE p.release_id = ? ORDER BY e.occurred_at ASC, e.id ASC",
                String.class,
                releaseId);
    }

    private String timelineDetailOf(UUID releaseId, String action) {
        return jdbc.queryForObject(
                "SELECT e.detail FROM ota_rollout_events e"
                        + " JOIN ota_rollout_plans p ON p.id = e.plan_id"
                        + " WHERE p.release_id = ? AND e.action = ?",
                String.class,
                releaseId,
                action);
    }

    private int auditCount(UUID releaseId, String eventType) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = ? AND detail ->> 'releaseId' = ?",
                Integer.class,
                eventType,
                releaseId.toString());
    }

    /**
     * The rollout bus events' actions for the release. Order-agnostic:
     * the outbox's ids are random UUIDs (V39) and cross-transaction
     * ordering is the dispatcher's own concern -- the contract here is
     * ONE row per committed act.
     */
    private List<String> outboxActions(UUID releaseId) {
        return jdbc.queryForList(
                "SELECT payload ->> 'action' FROM notification_outbox"
                        + " WHERE event_name = 'OTA_ROLLOUT_TRANSITIONED' AND release_id = ?",
                String.class,
                releaseId);
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

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId, List<String> platforms) {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", tenantId.toString());
        body.put("name", name);
        body.put("platforms", platforms);
        body.put("bundleId", platforms.contains("IOS") ? bundleId : null);
        body.put("packageName", platforms.contains("ANDROID") ? bundleId : null);
        return UUID.fromString(
                post(token, "/api/v1/applications", body, 201).get("id").asText());
    }

    private UUID createTenant(String token, UUID organizationId, String name, String externalId) {
        JsonNode created = post(
                token,
                "/api/v1/tenants",
                Map.of("organizationId", organizationId.toString(), "name", name, "externalId", externalId),
                201);
        return UUID.fromString(created.get("id").asText());
    }

    /** Creates one organization per test; repeated calls for the same name are a fixture bug. */
    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
    private String loginOfOrgRoleIn(UUID orgId, String localPart, String role) {
        String email = localPart + "@example.com";
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
                        "Ro " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    private UUID userIdOf(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
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

    private JsonNode post(String bearer, String uri, Map<String, Object> body, Integer expectedStatus) {
        Recording recording = exchange(uri, bearer, "POST", body);
        if (expectedStatus != null) {
            assertThat(recording.status)
                    .as("POST %s -> %s", uri, recording.body)
                    .isEqualTo(expectedStatus);
        }
        return recording.body;
    }

    private Recording postRaw(String bearer, String uri, Map<String, Object> body) {
        return exchange(uri, bearer, "POST", body);
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

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ('a' + (i % 26));
        }
        return bytes;
    }

    private record Recording(int status, String contentType, JsonNode body) {

        JsonNode expect(int expectedStatus) {
            assertThat(status).as("HTTP status for %s", body.toString()).isEqualTo(expectedStatus);
            return body;
        }
    }
}
