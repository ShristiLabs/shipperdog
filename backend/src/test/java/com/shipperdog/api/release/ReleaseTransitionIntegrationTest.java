package com.shipperdog.api.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.authorization.AuthorizationDistributionPolicy;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
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
 * End-to-end acceptance flow for the release state machine (issue #56,
 * S9-02, PRD 17, FR-024): the exhaustive transition matrix at the API
 * level (AC6 — every one of the 121 pairs asserted against a release
 * actually sitting in the source state), the transition preconditions
 * (AC2 — the failure/rollback reason, the policy seam's refusal), the
 * structured failure reasons and the terminal walls (AC3), the
 * ROLLED_BACK direct-channel download cutoff with artifacts staying
 * readable (AC4), and the audited, actor-carrying, append-only state
 * history (AC5) — plus the compare-and-set race that keeps two
 * concurrent transitions from double-committing or un-terminaling.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import({AuditCapture.class, ReleaseTransitionIntegrationTest.TransitionFixtures.class})
class ReleaseTransitionIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** The PRD 17 spine: how a fresh release reaches each source state. */
    private static final Map<String, List<String>> WALKS = Map.ofEntries(
            Map.entry("DRAFT", List.of()),
            Map.entry("VALIDATING", List.of("VALIDATING")),
            Map.entry("READY", List.of("VALIDATING", "READY")),
            Map.entry("APPROVAL_PENDING", List.of("VALIDATING", "READY", "APPROVAL_PENDING")),
            Map.entry("APPROVED", List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED")),
            Map.entry("DEPLOYING", List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING")),
            Map.entry("ACTIVE", List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE")),
            Map.entry(
                    "PROMOTED",
                    List.of("VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE", "PROMOTED")),
            Map.entry(
                    "COMPLETED",
                    List.of(
                            "VALIDATING",
                            "READY",
                            "APPROVAL_PENDING",
                            "APPROVED",
                            "DEPLOYING",
                            "ACTIVE",
                            "PROMOTED",
                            "COMPLETED")),
            Map.entry("FAILED", List.of("VALIDATING", "FAILED")),
            Map.entry(
                    "ROLLED_BACK",
                    List.of(
                            "VALIDATING",
                            "READY",
                            "APPROVAL_PENDING",
                            "APPROVED",
                            "DEPLOYING",
                            "ACTIVE",
                            "ROLLED_BACK")));

    private static final List<String> ALL_STATES = List.of(
            "DRAFT",
            "VALIDATING",
            "READY",
            "APPROVAL_PENDING",
            "APPROVED",
            "DEPLOYING",
            "ACTIVE",
            "PROMOTED",
            "ROLLED_BACK",
            "COMPLETED",
            "FAILED");

    /** The legal targets per source, PRD 17 verbatim (mirrors the unit matrix pin). */
    private static final Map<String, List<String>> LEGAL = Map.ofEntries(
            Map.entry("DRAFT", List.of("VALIDATING")),
            Map.entry("VALIDATING", List.of("READY", "FAILED")),
            Map.entry("READY", List.of("APPROVAL_PENDING")),
            Map.entry("APPROVAL_PENDING", List.of("APPROVED")),
            Map.entry("APPROVED", List.of("DEPLOYING")),
            Map.entry("DEPLOYING", List.of("ACTIVE", "FAILED")),
            Map.entry("ACTIVE", List.of("PROMOTED", "ROLLED_BACK")),
            Map.entry("PROMOTED", List.of("COMPLETED")),
            Map.entry("ROLLED_BACK", List.of()),
            Map.entry("COMPLETED", List.of()),
            Map.entry("FAILED", List.of()));

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

    @Autowired
    private List<AuthorizationDistributionPolicy> distributionPolicies;

    @Autowired
    private TransitionEventRecorder eventRecorder;

    @Autowired
    private GatePolicy gatePolicy;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        eventRecorder.events.clear();
        gatePolicy.denyApprovals.set(false);
    }

    // -----------------------------------------------------------------------
    // AC6: the exhaustive API-level matrix — every pair from its source
    // -----------------------------------------------------------------------

    @Test
    void everyPairOfTheMatrixAnswersPerPrd17FromAReleaseActuallyInThatState() throws Exception {
        World world = tenantOwnedWorld("rel-matrix");
        for (String from : ALL_STATES) {
            for (String to : ALL_STATES) {
                Fixture fixture = releaseIn(world, from);
                boolean legal = LEGAL.get(from).contains(to);
                if (legal) {
                    JsonNode moved =
                            transition(world.managerToken, fixture.releaseId(), world.app, to, walkReason(to), 200);
                    assertThat(moved.get("status").asText())
                            .as("%s -> %s is a PRD 17 edge; the release must land on %s", from, to, to)
                            .isEqualTo(to);
                } else {
                    JsonNode rejected =
                            transition(world.managerToken, fixture.releaseId(), world.app, to, walkReason(to), 409);
                    assertThat(rejected.get("code").asText())
                            .as("%s -> %s is not a PRD 17 edge", from, to)
                            .isEqualTo("RELEASE_TRANSITION_INVALID");
                    assertThat(rejected.get("errors").get("current").asText()).isEqualTo(from);
                    assertThat(rejected.get("errors").get("attempted").asText()).isEqualTo(to);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // AC1-AC3: the happy walk, the reason rule, the terminal walls
    // -----------------------------------------------------------------------

    @Test
    void theHappyPathWalksTheWholeDiagramAuditedAndEvented() throws Exception {
        World world = tenantOwnedWorld("rel-happy");
        Fixture release = releaseIn(world, "DRAFT");
        UUID actor = userIdOf(world.managerToken);
        List<String> spine = List.of(
                "VALIDATING", "READY", "APPROVAL_PENDING", "APPROVED", "DEPLOYING", "ACTIVE", "PROMOTED", "COMPLETED");
        String previous = "DRAFT";
        for (String state : spine) {
            JsonNode detail = transition(world.managerToken, release.releaseId(), world.app, state, null, 200);
            assertThat(detail.get("status").asText()).isEqualTo(state);
            assertThat(detail.get("releaseRef").asText()).matches("R-[0-9]+");

            // One audit event per real transition, before/after on the row.
            List<AuditEvent> events = audit.eventsOfType(AuditEventType.RELEASE_TRANSITIONED);
            assertThat(events).as("exactly one event per committed crossing").hasSize(1);
            AuditEvent event = events.get(0);
            assertThat(event.detail()).containsEntry("fromStatus", previous);
            assertThat(event.detail()).containsEntry("toStatus", state);
            assertThat(event.userId()).isEqualTo(actor);
            assertThat(event.applicationId()).isEqualTo(world.app);
            audit.reset();

            // The transition event carries the complete record for the
            // bus that follows (S12-01).
            assertThat(eventRecorder.events)
                    .as("one in-process event per crossing")
                    .hasSize(1);
            ReleaseTransitionedEvent published = eventRecorder.events.get(0);
            assertThat(published.fromStatus()).isEqualTo(previous);
            assertThat(published.toStatus()).isEqualTo(state);
            assertThat(published.releaseId()).isEqualTo(release.releaseId());
            assertThat(published.actorId()).isEqualTo(actor);
            assertThat(published.versionId()).isEqualTo(release.versionId());
            eventRecorder.events.clear();

            previous = state;
        }

        // COMPLETED is terminal: nothing exits it.
        transition(world.managerToken, release.releaseId(), world.app, "DRAFT", null, 409);
        // The timeline carries the creation row plus one row per crossing.
        JsonNode history = get(world.managerToken, historyPath(release.releaseId(), world.app), 200);
        assertThat(history.get("transitions").size()).isEqualTo(spine.size() + 1);
        assertThat(history.get("transitions").get(0).get("from").isNull()).isTrue();
        assertThat(history.get("transitions").get(0).get("to").asText()).isEqualTo("DRAFT");
        assertThat(history.get("transitions").get(0).get("actorId").asText())
                .isEqualTo(userIdOf(world.managerToken).toString());
    }

    @Test
    void failureTransitionsRequireAReasonCarryItAndAreTerminal() throws Exception {
        World world = tenantOwnedWorld("rel-fail");
        Fixture release = releaseIn(world, "VALIDATING");

        // The missing reason is a 422 naming the field, before any write.
        JsonNode rejected = transition(world.managerToken, release.releaseId(), world.app, "FAILED", null, 422);
        assertThat(rejected.get("code").asText()).isEqualTo("RELEASE_TRANSITION_REASON_REQUIRED");
        assertThat(rejected.get("errors").get("reason").asText()).contains("FAILED");
        assertThat(statusOf(world, release.releaseId())).isEqualTo("VALIDATING");

        JsonNode failed = transition(
                world.managerToken,
                release.releaseId(),
                world.app,
                "FAILED",
                "checksum mismatch on the ANDROID build",
                200);
        assertThat(failed.get("status").asText()).isEqualTo("FAILED");
        // The walk's VALIDATING event and this one are both captured; the
        // failure event is the one that landed on FAILED.
        AuditEvent failureEvent = audit.eventsOfType(AuditEventType.RELEASE_TRANSITIONED).stream()
                .filter(event -> "FAILED".equals(event.detail().get("toStatus")))
                .findFirst()
                .orElseThrow();
        assertThat(failureEvent.detail().get("reason")).isEqualTo("checksum mismatch on the ANDROID build");

        // The reason rides the timeline row; FAILED is a wall.
        JsonNode history = get(world.managerToken, historyPath(release.releaseId(), world.app), 200);
        JsonNode last =
                history.get("transitions").get(history.get("transitions").size() - 1);
        assertThat(last.get("from").asText()).isEqualTo("VALIDATING");
        assertThat(last.get("to").asText()).isEqualTo("FAILED");
        assertThat(last.get("reason").asText()).isEqualTo("checksum mismatch on the ANDROID build");
        for (String to : ALL_STATES) {
            transition(world.managerToken, release.releaseId(), world.app, to, walkReason(to), 409);
        }

        // The DEPLOYING failure edge carries the same discipline.
        Fixture deploying = releaseIn(world, "DEPLOYING");
        transition(world.managerToken, deploying.releaseId(), world.app, "FAILED", null, 422);
        assertThat(transition(
                                world.managerToken,
                                deploying.releaseId(),
                                world.app,
                                "FAILED",
                                "tenant quota exceeded mid-deployment",
                                200)
                        .get("status")
                        .asText())
                .isEqualTo("FAILED");
    }

    @Test
    void anOptionalReasonIsAcceptedOnOrdinaryTransitionsAndBlankMeansNone() throws Exception {
        World world = tenantOwnedWorld("rel-optional-reason");
        Fixture release = releaseIn(world, "DRAFT");
        transition(
                world.managerToken, release.releaseId(), world.app, "VALIDATING", "  nightly validation pass  ", 200);
        JsonNode history = get(world.managerToken, historyPath(release.releaseId(), world.app), 200);
        JsonNode last =
                history.get("transitions").get(history.get("transitions").size() - 1);
        assertThat(last.get("reason").asText()).isEqualTo("nightly validation pass");

        Fixture quiet = releaseIn(world, "DRAFT");
        transition(world.managerToken, quiet.releaseId(), world.app, "VALIDATING", "   ", 200);
        JsonNode quietHistory = get(world.managerToken, historyPath(quiet.releaseId(), world.app), 200);
        assertThat(quietHistory.get("transitions").get(1).get("reason").isNull())
                .isTrue();

        // An overlong reason is the 400 of the field vocabulary (the
        // V25 database check pins the same width).
        transition(world.managerToken, quiet.releaseId(), world.app, "READY", "x".repeat(1001), 400);
    }

    // -----------------------------------------------------------------------
    // AC4: ROLLED_BACK cuts off downloads; version and artifacts stay
    // -----------------------------------------------------------------------

    @Test
    void rollBackCutsOffDownloadsWhileTheVersionAndArtifactsStayReadable() throws Exception {
        World world = tenantOwnedWorld("rel-rollback");
        Fixture release = releaseIn(world, "ACTIVE");

        // Before the rollback nothing blocks: no policy answers for the
        // version on any artifact of it.
        assertThat(cutoffPoliciesFor(release.versionId())).isEmpty();

        transition(
                world.managerToken,
                release.releaseId(),
                world.app,
                "ROLLED_BACK",
                "crash spike in the field; pulling the direct channel",
                200);
        assertThat(statusOf(world, release.releaseId())).isEqualTo("ROLLED_BACK");

        // The cutoff policy answers for the version — the exact seam the
        // distribution layer consults on every DOWNLOAD decision.
        assertThat(cutoffPoliciesFor(release.versionId())).hasSize(1);

        // INV-006/007 posture: rollback never deletes; reads remain
        // fully authorized — the version detail and the artifact read
        // of the owning side stay 200.
        get(world.ownerToken, "/api/v1/applications/" + world.app + "/versions/" + release.versionId(), 200);
        get(world.ownerToken, "/api/v1/artifacts/" + release.artifactId() + "?version_id=" + release.versionId(), 200);

        // The rollback reason is mandatory and rides the timeline.
        JsonNode history = get(world.managerToken, historyPath(release.releaseId(), world.app), 200);
        JsonNode last =
                history.get("transitions").get(history.get("transitions").size() - 1);
        assertThat(last.get("to").asText()).isEqualTo("ROLLED_BACK");
        assertThat(last.get("reason").asText()).contains("crash spike");

        // ROLLED_BACK is terminal in M2 (store rollback orchestration is
        // S13-06): nothing exits it.
        for (String to : ALL_STATES) {
            transition(world.managerToken, release.releaseId(), world.app, to, walkReason(to), 409);
        }
    }

    // -----------------------------------------------------------------------
    // The CAS race: one crossing, one row, one event
    // -----------------------------------------------------------------------

    @Test
    void racingTransitionsCommitExactlyOneCrossing() throws Exception {
        World world = tenantOwnedWorld("rel-race");
        Fixture release = releaseIn(world, "DRAFT");
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Recording>> runs = List.of(
                    racers.submit(() ->
                            raceTransition(world.managerToken, release.releaseId(), world.app, "VALIDATING", start)),
                    racers.submit(() ->
                            raceTransition(world.managerToken, release.releaseId(), world.app, "VALIDATING", start)));
            Recording first = runs.get(0).get(30, TimeUnit.SECONDS);
            Recording second = runs.get(1).get(30, TimeUnit.SECONDS);
            // Exactly one crossing wins; the loser re-reads the committed
            // state and answers the precise conflict — never a second
            // commit, never a 500.
            assertThat(List.of(first.status(), second.status())).containsExactlyInAnyOrder(200, 409);
            Recording loser = first.status() == 409 ? first : second;
            assertThat(loser.body().get("code").asText()).isEqualTo("RELEASE_TRANSITION_INVALID");
            assertThat(loser.body().get("errors").get("current").asText()).isEqualTo("VALIDATING");
            assertThat(statusOf(world, release.releaseId())).isEqualTo("VALIDATING");
            assertThat(historyRowCount(release.releaseId())).isEqualTo(2);
            assertThat(audit.eventsOfType(AuditEventType.RELEASE_TRANSITIONED))
                    .as("the loser never audits a crossing")
                    .hasSize(1);
            assertThat(eventRecorder.events).as("the loser never publishes").hasSize(1);
        } finally {
            racers.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // AC5: the history read and its concealment
    // -----------------------------------------------------------------------

    @Test
    void historyStartsWithTheCreationRowAndConcealsMisses() throws Exception {
        World world = tenantOwnedWorld("rel-history");
        Fixture release = releaseIn(world, "READY");
        UUID manager = userIdOf(world.managerToken);

        JsonNode history = get(world.managerToken, historyPath(release.releaseId(), world.app), 200);
        assertThat(history.get("releaseRef").asText()).matches("R-[0-9]+");
        assertThat(history.get("status").asText()).isEqualTo("READY");
        JsonNode rows = history.get("transitions");
        assertThat(rows.size()).isEqualTo(3);
        assertThat(rows.get(0).get("to").asText()).isEqualTo("DRAFT");
        assertThat(rows.get(0).get("actorId").asText()).isEqualTo(manager.toString());
        assertThat(rows.get(1).get("from").asText()).isEqualTo("DRAFT");
        assertThat(rows.get(1).get("to").asText()).isEqualTo("VALIDATING");
        assertThat(rows.get(2).get("from").asText()).isEqualTo("VALIDATING");
        assertThat(rows.get(2).get("to").asText()).isEqualTo("READY");

        // The miss shapes: unknown release, and a real release addressed
        // under another application — the same concealed 404.
        get(world.managerToken, historyPath(UUID.randomUUID(), world.app), 404);
        UUID otherApp = createTenantApp(world.ownerToken, world.tenantA, "Rel History Other", "com.relhistory.other");
        JsonNode miss = get(world.managerToken, historyPath(release.releaseId(), otherApp), 404);
        assertThat(miss.get("code").asText()).isEqualTo("RELEASE_NOT_FOUND");

        // A persona without app:releases:read conceals through the
        // application-grain check, identical to the detail route.
        String tenantAdminToken = loginOfTenantAdminRoleIn(world.org, world.tenantA, "rel-history-tenant-admin");
        assertThat(get(tenantAdminToken, historyPath(release.releaseId(), world.app), 404)
                        .get("code")
                        .asText())
                .isEqualTo("APPLICATION_NOT_FOUND");
    }

    // -----------------------------------------------------------------------
    // Request shape: the 400, the policy seam's 422, manage-only authority
    // -----------------------------------------------------------------------

    @Test
    void unknownTargetsAre400AndThePolicySeamRendersIts422() throws Exception {
        World world = tenantOwnedWorld("rel-shape");
        Fixture release = releaseIn(world, "APPROVAL_PENDING");

        JsonNode unknown = transition(world.managerToken, release.releaseId(), world.app, "ACTVE", null, 400);
        assertThat(unknown.get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(unknown.get("errors").get("to").asText()).contains("ACTVE");
        transition(world.managerToken, release.releaseId(), world.app, null, null, 400);

        // The guard seam: a registered policy's refusal is the 422 keyed
        // by the contributor (E10's approval evaluator composes here;
        // the fixture plays it for one crossing).
        gatePolicy.denyApprovals.set(true);
        JsonNode blocked = transition(world.managerToken, release.releaseId(), world.app, "APPROVED", null, 422);
        assertThat(blocked.get("code").asText()).isEqualTo("RELEASE_TRANSITION_BLOCKED");
        assertThat(blocked.get("errors").get("GatePolicy").asText()).contains("approvals incomplete");
        assertThat(statusOf(world, release.releaseId())).isEqualTo("APPROVAL_PENDING");

        // The denial is a refusal, not a rewrite: with the condition
        // cleared the same caller completes the crossing.
        gatePolicy.denyApprovals.set(false);
        assertThat(transition(world.managerToken, release.releaseId(), world.app, "APPROVED", null, 200)
                        .get("status")
                        .asText())
                .isEqualTo("APPROVED");

        // Manage is the transition authority; read alone does not move a
        // release: the DEVELOPER persona (app:releases:read, no manage)
        // reads the timeline but its crossing conceals at the
        // application grain, while a genuine release miss under a valid
        // application renders the release code — the route's two miss
        // shapes, each precise.
        String developerToken = loginOfOrgRoleIn(world.org, "rel-shape-dev", "DEVELOPER");
        Fixture fresh = releaseIn(world, "DRAFT");
        assertThat(get(developerToken, historyPath(fresh.releaseId(), world.app), 200)
                        .get("status")
                        .asText())
                .isEqualTo("DRAFT");
        JsonNode denied = transition(developerToken, fresh.releaseId(), world.app, "VALIDATING", null, 404);
        assertThat(denied.get("code").asText())
                .as("the manage denial conceals at the application grain")
                .isEqualTo("APPLICATION_NOT_FOUND");
        assertThat(transition(world.managerToken, UUID.randomUUID(), world.app, "VALIDATING", null, 404)
                        .get("code")
                        .asText())
                .as("a genuine release miss under a valid application renders the release code")
                .isEqualTo("RELEASE_NOT_FOUND");
        assertThat(statusOf(world, fresh.releaseId())).isEqualTo("DRAFT");
    }

    // -----------------------------------------------------------------------
    // Scope and layering pins: the org-mode seam, version revocation,
    // idempotency discipline, and the distinct-target race
    // -----------------------------------------------------------------------

    @Test
    void sharedApplicationReleasesTransitionUnderTheOrganizationScope() throws Exception {
        World world = sharedWorld("rel-shared-machine");
        authorize(world.ownerToken, world.app, world.tenantA);
        UUID release = releaseIn(world, "ACTIVE").releaseId();

        // The seam's organization-scope mode (null tenant on the row)
        // serves the crossings exactly like the tenant mode does.
        transition(world.managerToken, release, world.app, "ROLLED_BACK", "pulled for the shared-scope pin", 200);
        assertThat(statusOf(world, release)).isEqualTo("ROLLED_BACK");
        JsonNode history = get(world.managerToken, historyPath(release, world.app), 200);
        assertThat(history.get("status").asText()).isEqualTo("ROLLED_BACK");
        assertThat(history.get("transitions").size())
                .as("creation row, the six crossings to ACTIVE, and the rollback")
                .isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM releases WHERE id = ?", String.class, release))
                .isNull();
    }

    @Test
    void versionRevocationDoesNotStopTheReleaseMachine() throws Exception {
        World world = tenantOwnedWorld("rel-revoked-version");
        Fixture release = releaseIn(world, "DRAFT");

        // The layers are deliberately independent: revoking the
        // version's distribution blocks downloads at the VERSION grain
        // (S4-03's wall), while the release lifecycle is the governance
        // record of the distribution event and keeps moving — a
        // compliance takedown of the binaries does not strand the
        // release in limbo; it is rolled back or completed on its own
        // facts. This pins the intended layering, not an oversight.
        post(
                world.ownerToken,
                "/api/v1/applications/" + world.app + "/versions/" + release.versionId() + "/revoke-distribution",
                Map.of("reason", "binaries pulled while the release decision is pending"),
                200);
        assertThat(transition(world.managerToken, release.releaseId(), world.app, "VALIDATING", null, 200)
                        .get("status")
                        .asText())
                .isEqualTo("VALIDATING");
        assertThat(transition(world.managerToken, release.releaseId(), world.app, "READY", null, 200)
                        .get("status")
                        .asText())
                .isEqualTo("READY");
    }

    @Test
    void transitionsAreNotIdempotencyKeyedTheStateIsTheReplayAnswer() throws Exception {
        World world = tenantOwnedWorld("rel-idempotent-machine");
        Fixture release = releaseIn(world, "DRAFT");
        String key = UUID.randomUUID().toString();

        // The same Idempotency-Key on two DIFFERENT crossings: the
        // route has no replay store (the lifecycle precedent — the
        // state itself is the answer), so the second request is a real
        // crossing, not a replay of the first response.
        exchange(
                        "/api/v1/releases/" + release.releaseId() + "/transitions",
                        world.managerToken,
                        "POST",
                        Map.of("applicationId", world.app.toString(), "to", "VALIDATING"),
                        key)
                .expect(200);
        exchange(
                        "/api/v1/releases/" + release.releaseId() + "/transitions",
                        world.managerToken,
                        "POST",
                        Map.of("applicationId", world.app.toString(), "to", "READY"),
                        key)
                .expect(200);
        assertThat(statusOf(world, release.releaseId())).isEqualTo("READY");
        assertThat(historyRowCount(release.releaseId())).isEqualTo(3);
    }

    @Test
    void racingDistinctLegalCrossingsNeverUnTerminal() throws Exception {
        World world = tenantOwnedWorld("rel-race-distinct");
        UUID release = releaseIn(world, "VALIDATING").releaseId();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            // READY and FAILED are BOTH legal from VALIDATING, and the
            // graph admits no second chapter: whichever crossing commits
            // first leaves the release in a state from which the OTHER
            // target is illegal (READY's only edge is APPROVAL_PENDING;
            // nothing exits FAILED). So exactly one racer commits — the
            // CAS plus the forward-only table close both orders. The
            // final state is the winner's target and the timeline
            // matches the responses exactly: never a double commit,
            // never an un-terminal.
            List<Future<Recording>> runs = List.of(
                    racers.submit(() -> raceTransition(world.managerToken, release, world.app, "READY", start)),
                    racers.submit(() -> raceTransitionWithReason(
                            world.managerToken, release, world.app, "FAILED", "race reason for FAILED", start)));
            Recording ready = runs.get(0).get(30, TimeUnit.SECONDS);
            Recording failed = runs.get(1).get(30, TimeUnit.SECONDS);
            assertThat(List.of(ready.status(), failed.status()))
                    .as("exactly one distinct-target racer commits")
                    .containsExactlyInAnyOrder(200, 409);
            Recording winner = ready.status() == 200 ? ready : failed;
            assertThat(winner.body().get("status").asString())
                    .as("the final state is the winner's target")
                    .isEqualTo(statusOf(world, release));
            assertThat(statusOf(world, release)).isIn("READY", "FAILED");
            assertThat(historyRowCount(release))
                    .as("creation row, the walk's VALIDATING, and the one committed crossing")
                    .isEqualTo(3);
        } finally {
            racers.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** The transition-event recorder (the S12-01 listener stand-in). */
    static class TransitionEventRecorder {
        final List<ReleaseTransitionedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(ReleaseTransitionedEvent event) {
            events.add(event);
        }
    }

    /** A gate policy the blocked-crossing test flips; permitting by default. */
    static final class GatePolicy implements ReleaseTransitionPolicy {
        final AtomicBoolean denyApprovals = new AtomicBoolean(false);

        @Override
        public Optional<String> denial(TransitionCheck check) {
            if (denyApprovals.get() && "APPROVED".equals(check.toStatus())) {
                return Optional.of("approvals incomplete (fixture)");
            }
            return Optional.empty();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransitionFixtures {
        @Bean
        GatePolicy gatePolicy() {
            return new GatePolicy();
        }

        @Bean
        TransitionEventRecorder transitionEventRecorder() {
            return new TransitionEventRecorder();
        }
    }

    private static final class World {
        UUID org;
        String adminToken;
        String ownerToken;
        String managerToken;
        UUID tenantA;
        UUID app;
        UUID version;
        UUID iosArtifact;
    }

    /** A fresh release of a fresh version, walked to the requested source state. */
    private Fixture releaseIn(World world, String state) {
        String name = String.format("%d.%d.%d", 1 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(world.ownerToken, world.app, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter, "READY");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("versionId", version.toString());
        body.put("ring", "qa");
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of())));
        UUID release = UUID.fromString(post(world.managerToken, "/api/v1/releases", body, 201)
                .get("id")
                .asText());
        for (String step : WALKS.get(state)) {
            transition(world.managerToken, release, world.app, step, walkReason(step), 200);
        }
        return new Fixture(release, version, artifact);
    }

    /** One walked release and the version + artifact it distributes. */
    private record Fixture(UUID releaseId, UUID versionId, UUID artifactId) {}

    private int versionCounter;

    /** The walk steps that need a reason get one; ordinary steps carry none. */
    private static String walkReason(String to) {
        return "FAILED".equals(to) || "ROLLED_BACK".equals(to) ? "walk reason for " + to : null;
    }

    private String statusOf(World world, UUID releaseId) {
        return jdbc.queryForObject("SELECT status FROM releases WHERE id = ?", String.class, releaseId);
    }

    private int historyRowCount(UUID releaseId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM release_status_history WHERE release_id = ?", Integer.class, releaseId);
    }

    /** The registered distribution policies that block the version's downloads. */
    private List<AuthorizationDistributionPolicy> cutoffPoliciesFor(UUID versionId) {
        List<AuthorizationDistributionPolicy> blocking = new ArrayList<>();
        for (AuthorizationDistributionPolicy policy : distributionPolicies) {
            if (policy.blocksDownload(null, versionId)) {
                blocking.add(policy);
            }
        }
        return blocking;
    }

    private String historyPath(UUID releaseId, UUID applicationId) {
        return "/api/v1/releases/" + releaseId + "/history?applicationId=" + applicationId;
    }

    private JsonNode transition(
            String bearer, UUID releaseId, UUID applicationId, String to, String reason, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        if (to != null) {
            body.put("to", to);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        return exchange("/api/v1/releases/" + releaseId + "/transitions", bearer, "POST", body, null)
                .expect(expected);
    }

    /** One racer's transition attempt, released simultaneously with its peer by the barrier. */
    private Recording raceTransition(
            String bearer, UUID releaseId, UUID applicationId, String to, CyclicBarrier start) {
        return raceTransitionWithReason(bearer, releaseId, applicationId, to, null, start);
    }

    /** The reason-carrying racer (the FAILED edge needs one; the bare overload carries none). */
    private Recording raceTransitionWithReason(
            String bearer, UUID releaseId, UUID applicationId, String to, String reason, CyclicBarrier start) {
        try {
            start.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("to", to);
        if (reason != null) {
            body.put("reason", reason);
        }
        return exchange("/api/v1/releases/" + releaseId + "/transitions", bearer, "POST", body, null);
    }

    private World tenantOwnedWorld(String slug) {
        World world = new World();
        world.org = createOrg("Rel " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.tenantA = createTenant(world.adminToken, world.org, "Rel " + slug + " Bank", "crm-" + slug + "-a");
        world.app = createTenantApp(
                world.adminToken,
                world.tenantA,
                "Rel " + slug + " Banking",
                "com." + slug.replace("-", "") + ".banking");
        world.version = createVersion(world.ownerToken, world.app, "1.0.0");
        world.iosArtifact = insertArtifact(world.version, "IOS", 1, "READY");
        return world;
    }

    /**
     * One organization with an ORGANIZATION-shared application: the
     * release rows serve the organization-scope mode of the seam (null
     * tenant). The target tenant needs an ACTIVE authorization before
     * creation accepts it (the S3-03 effective-visibility rule).
     */
    private World sharedWorld(String slug) {
        World world = new World();
        world.org = createOrg("Rel " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.tenantA = createTenant(world.adminToken, world.org, "Rel " + slug + " Bank A", "crm-" + slug + "-a");
        Map<String, Object> body = new HashMap<>();
        body.put("ownershipScope", "ORGANIZATION");
        body.put("organizationId", world.org.toString());
        body.put("name", "Rel " + slug + " Shared");
        body.put("platforms", List.of("IOS", "ANDROID"));
        body.put("bundleId", "com." + slug.replace("-", "") + ".shared");
        body.put("packageName", "com." + slug.replace("-", "") + ".shared");
        world.app = UUID.fromString(post(world.adminToken, "/api/v1/applications", body, 201)
                .get("id")
                .asText());
        world.version = createVersion(world.ownerToken, world.app, "1.0.0");
        world.iosArtifact = insertArtifact(world.version, "IOS", 1, "READY");
        return world;
    }

    /** Grants the shared application to the tenant (the S3-03 authorization surface). */
    private void authorize(String token, UUID applicationId, UUID tenantId) {
        post(token, "/api/v1/applications/" + applicationId + "/tenants/" + tenantId, Map.of(), 201);
    }

    /** Inserts an artifact row directly (READY) — the release fixture's binary-free shortcut. */
    private UUID insertArtifact(UUID versionId, String platform, long build, String status) {
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
                platform.toLowerCase(Locale.ROOT) + "-" + build + "." + extension,
                "fixture/" + artifactId + "/artifact",
                "a1".repeat(32),
                1024L,
                build,
                status,
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
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

    private UUID createTenantApp(String token, UUID tenantId, String name, String bundleId) {
        Map<String, Object> body = new HashMap<>();
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

    private UUID userIdOf(String bearer) {
        return UUID.fromString(decode(bearer).get("sub").asText());
    }

    private JsonNode decode(String token) {
        String claims = token.split("\\.")[1];
        return mapper.readTree(Base64.getUrlDecoder().decode(claims));
    }

    /** Creates one organization per test; repeated calls for the same name are a fixture bug. */
    private UUID createOrg(String orgName) {
        String devToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));
        return UUID.fromString(post(devToken, "/api/v1/organizations", Map.of("name", orgName), 201)
                .get("id")
                .asText());
    }

    /** Registers a user with the given role into an EXISTING organization; returns their token. */
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
                        "Release " + role))
                .retrieve()
                .body(String.class);
        return accessToken(login(email, PASSWORD));
    }

    /** Registers a TENANT_ADMIN of the given tenant (the tenant persona of the concealment probes). */
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
                        "Release Tenant Admin"))
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
        return exchange(uri, bearer, "POST", body, null).expect(expectedStatus);
    }

    private JsonNode get(String bearer, String uri, int expectedStatus) {
        return exchange(uri, bearer, "GET", null, null).expect(expectedStatus);
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
