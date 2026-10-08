package com.shipperdog.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
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
 * End-to-end acceptance flow for the advanced approval modes (issue
 * #41, S10-02, FR-016, PRD 32): the group vocabulary (AC1 — ALL groups
 * demand every step, ANY groups satisfy on the first approval with
 * the siblings skipped and the satisfying signature recorded), the
 * conditional steps (AC2 — predicate evaluated at round instantiation,
 * the rationale recorded on the row and in the audit), the mixed
 * topologies (AC3 — the PRD FR-016 example), the editable definitions
 * (AC4 — the console editor's PUT, validation refusing malformed
 * shapes), and the folded #40 follow-ups: the fail-closed dangling
 * environment binding, the double-restart insert race's precise 409,
 * the programmatic probe's ACCESS_DENIED audit beside its decision
 * meter, and the reserved approval_duration recorded at every round's
 * terminal crossing.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import(AuditCapture.class)
class ApprovalModesIntegrationTest {

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
    private MeterRegistry meters;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        meters.clear();
    }

    // -----------------------------------------------------------------------
    // AC1/AC3: the PARALLEL mode and the mixed topology
    // -----------------------------------------------------------------------

    /**
     * AC3's exact topology — (QA) -> (Security AND Compliance) ->
     * (Product) — completes only per its semantics: the parallel pair
     * is decidable together (either signing order), the crossing is
     * refused while either sibling waits (the group-shaped refusal
     * naming both), an out-of-group attempt answers the precise 409,
     * and the manifest freezes all four types at APPROVED.
     */
    @Test
    void theMixedTopologyCompletesOnlyPerItsSemantics() {
        World world = world("wf-mixed");
        createGroups(
                world.managerToken,
                world.org,
                "enterprise",
                "qa",
                List.of(
                        group("ALL", List.of(step("QA_ENGINEER"))),
                        group("ALL", List.of(step("SECURITY_REVIEWER"), step("COMPLIANCE_REVIEWER"))),
                        group("ALL", List.of(step("APP_OWNER")))),
                201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);

        // Group 1 gates the parallel pair: the out-of-group attempts
        // answer the precise 409, current naming the group's first
        // decidable step.
        assertThat(decide(world.securityToken, world, release, "APPROVE", 2, null, 409)
                        .get("code")
                        .asText())
                .isEqualTo("APPROVAL_STEP_OUT_OF_ORDER");
        decide(world.qaToken, world, release, "APPROVE", 1, null, 200);

        // The crossing's refusal names the parallel group and BOTH of
        // its demands (the dashboard's waiting-on list, the policy's).
        JsonNode blocked = transition(world.managerToken, world, release, "APPROVED", 422);
        assertThat(blocked.get("errors").get("ReleaseApprovalPolicy").asText())
                .contains("awaits group 2 (ALL, every step required)")
                .contains("SECURITY", "COMPLIANCE");

        // Either sibling order satisfies: Compliance signs first here.
        decide(world.complianceToken, world, release, "APPROVE", 3, null, 200);
        assertThat(transition(world.managerToken, world, release, "APPROVED", 422)
                        .get("errors")
                        .get("ReleaseApprovalPolicy")
                        .asText())
                .contains("SECURITY");
        decide(world.securityToken, world, release, "APPROVE", 2, null, 200);
        decide(world.ownerToken, world, release, "APPROVE", 4, null, 200);
        assertThat(approvalsOf(world, release).get("currentRoundStatus").asText())
                .isEqualTo("SATISFIED");

        JsonNode approved = transition(world.managerToken, world, release, "APPROVED", 200);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        // The manifest freezes the signed chain in CHAIN order — the
        // deterministic projection (the same committed rows freeze the
        // same document whatever the signing interleaving was;
        // Compliance happened to sign before Security here).
        List<String> frozen = manifestApprovalTypes(world, release);
        assertThat(frozen).containsExactly("QA", "SECURITY", "COMPLIANCE", "PRODUCT");
    }

    /** AC1's ALL rule under rejection: any sibling's no rejects the round (a no is never outvoted). */
    @Test
    void aParallelGroupsRejectionTerminatesTheRound() {
        World world = world("wf-par-rej");
        createGroups(
                world.managerToken,
                world.org,
                "parallel",
                "qa",
                List.of(group("ALL", List.of(step("SECURITY_REVIEWER"), step("COMPLIANCE_REVIEWER")))),
                201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);

        decide(world.securityToken, world, release, "APPROVE", 1, "scanned", 200);
        decide(world.complianceToken, world, release, "REJECT", 2, "policy exception expired", 200);
        assertThat(approvalsOf(world, release).get("currentRoundStatus").asText())
                .isEqualTo("REJECTED");
        // The approved signature stands beside the rejection — the
        // recorded facts of one governance moment.
        JsonNode steps = approvalsOf(world, release).get("rounds").get(0).get("steps");
        assertThat(steps.get(0).get("status").asText()).isEqualTo("APPROVED");
        assertThat(steps.get(1).get("status").asText()).isEqualTo("REJECTED");
    }

    // -----------------------------------------------------------------------
    // AC1: the ANY mode
    // -----------------------------------------------------------------------

    /**
     * AC1's OR rule: the first approval satisfies an ANY group, the
     * remaining sibling becomes SKIPPED with the satisfying signature
     * recorded in its reason, the round completes past it, and the
     * manifest freezes exactly the signed chain.
     */
    @Test
    void anyGroupSatisfiesOnFirstApprovalAndRecordsWhichSignatureSatisfiedIt() {
        World world = world("wf-any");
        createGroups(
                world.managerToken,
                world.org,
                "either-of",
                "qa",
                List.of(
                        group("ANY", List.of(step("SECURITY_REVIEWER"), step("COMPLIANCE_REVIEWER"))),
                        group("ALL", List.of(step("APP_OWNER")))),
                201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);

        // Compliance's signature satisfies the OR; Security's row
        // records the skip WITH ITS REASON NAMING THE SATISFIER.
        decide(world.complianceToken, world, release, "APPROVE", 2, "cleared", 200);
        JsonNode steps = approvalsOf(world, release).get("rounds").get(0).get("steps");
        assertThat(steps.get(0).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(steps.get(0).get("reason").asText()).contains("satisfied by position 2 (COMPLIANCE)");
        assertThat(steps.get(0).get("approverId").isNull()).isTrue();
        assertThat(steps.get(0).get("decidedAt").isNull()).isFalse();
        assertThat(steps.get(0).get("groupMode").asText()).isEqualTo("ANY");

        // The skipped sibling is no longer decidable.
        assertThat(decide(world.securityToken, world, release, "APPROVE", 1, null, 409)
                        .get("code")
                        .asText())
                .isEqualTo("APPROVAL_STEP_OUT_OF_ORDER");

        decide(world.ownerToken, world, release, "APPROVE", 3, null, 200);
        transition(world.managerToken, world, release, "APPROVED", 200);
        // The manifest freezes the SIGNED chain only: the skip
        // demanded nothing, so SECURITY is absent.
        assertThat(manifestApprovalTypes(world, release)).containsExactly("COMPLIANCE", "PRODUCT");
    }

    /** AC6's reject ordering inside ANY: a rejection rejects the round even though its sibling already approved. */
    @Test
    void anAnyGroupsRejectionIsNeverOutvoted() {
        World world = world("wf-any-rej");
        createGroups(
                world.managerToken,
                world.org,
                "either-reject",
                "qa",
                List.of(group("ANY", List.of(step("SECURITY_REVIEWER"), step("COMPLIANCE_REVIEWER")))),
                201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);

        decide(world.securityToken, world, release, "REJECT", 1, "critical CVE in dependency chain", 200);
        assertThat(approvalsOf(world, release).get("currentRoundStatus").asText())
                .isEqualTo("REJECTED");
        // The round is dead: the sibling's approval finds no live round.
        assertThat(decide(world.complianceToken, world, release, "APPROVE", 2, null, 409)
                        .get("code")
                        .asText())
                .isEqualTo("APPROVAL_NOT_ACTIVE");
    }

    /**
     * The implicit addressing of the decision body: a caller holding
     * authority over SEVERAL decidable steps must assert which one
     * (the PLATFORM administrator passes every approve permission —
     * the org admin holds none of them, the RBAC matrix); the
     * assertion decides precisely that step.
     */
    @Test
    void aMultiAuthorityCallerMustAssertTheStepInsideAnAnyGroup() {
        World world = world("wf-assert");
        createGroups(
                world.managerToken,
                world.org,
                "assert-step",
                "qa",
                List.of(group("ANY", List.of(step("SECURITY_REVIEWER"), step("COMPLIANCE_REVIEWER")))),
                201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);
        String platformToken = accessToken(login(DEV_ADMIN_EMAIL, DEV_ADMIN_PASSWORD));

        JsonNode ambiguous = decide(platformToken, world, release, "APPROVE", null, null, 400);
        assertThat(ambiguous.get("errors").get("step").asText()).contains("[1, 2]");

        decide(platformToken, world, release, "APPROVE", 2, null, 200);
        JsonNode steps = approvalsOf(world, release).get("rounds").get(0).get("steps");
        assertThat(steps.get(1).get("status").asText()).isEqualTo("APPROVED");
        assertThat(steps.get(0).get("status").asText()).isEqualTo("SKIPPED");
    }

    // -----------------------------------------------------------------------
    // AC2: conditional steps
    // -----------------------------------------------------------------------

    /**
     * AC2: a conditional step is included or excluded per its
     * predicate evaluated at the round's instantiation, with the
     * rationale recorded — the SKIPPED row carries it in its reason,
     * and the round-start audit lists every excluded conditional.
     */
    @Test
    void conditionalStepsFollowThePredicateAndRecordTheirRationale() {
        World world = world("wf-cond");
        Map<String, Object> ifProduction = new LinkedHashMap<>();
        ifProduction.put("role", "COMPLIANCE_REVIEWER");
        ifProduction.put("condition", Map.of("type", "ENVIRONMENT_EQUALS", "value", "production"));
        createGroups(
                world.managerToken,
                world.org,
                "regulated",
                null,
                List.of(group("ALL", List.of(step("QA_ENGINEER"), ifProduction))),
                201);

        // A production-targeted release: the condition is MET, the
        // Compliance step is DEMANDED. The definition has no ring
        // attachment — the ENVIRONMENT's named policy binds it.
        UUID production = environmentOf(world, "production");
        patch(world.managerToken, "/api/v1/environments/" + production, Map.of("approvalPolicy", "regulated"), 200);
        assignEnvironmentTenants(world, production, world.tenantA);
        Release regulated = releaseAtApprovalPending(world, "qa", world.tenantA, production);
        JsonNode regulatedState = approvalsOf(world, regulated);
        assertThat(regulatedState.get("rounds").get(0).get("steps").size()).isEqualTo(2);
        decide(world.qaToken, world, regulated, "APPROVE", 1, null, 200);
        decide(world.complianceToken, world, regulated, "APPROVE", 2, null, 200);
        transition(world.managerToken, world, regulated, "APPROVED", 200);

        // A staging-targeted release: the condition is UNMET, the step
        // instantiates SKIPPED with its rationale recorded, and the
        // audit carries the exclusion beside the step list. Staging is
        // a CUSTOM environment (the seed's five defaults carry no
        // staging) bound to the same named policy.
        audit.reset();
        UUID staging = createEnvironment(world, "staging", "Staging", "regulated");
        assignEnvironmentTenants(world, staging, world.tenantA);
        Release unregulated = releaseAtApprovalPending(world, "qa", world.tenantA, staging);
        JsonNode steps = approvalsOf(world, unregulated).get("rounds").get(0).get("steps");
        assertThat(steps.get(1).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(steps.get(1).get("reason").asText())
                .contains("ENVIRONMENT_EQUALS(production)")
                .contains("unmet")
                .contains("environment=staging");
        decide(world.qaToken, world, unregulated, "APPROVE", 1, null, 200);
        assertThat(approvalsOf(world, unregulated).get("currentRoundStatus").asText())
                .isEqualTo("SATISFIED");
        List<AuditEvent> starts = audit.eventsOfType(AuditEventType.RELEASE_APPROVAL_ROUND_STARTED);
        assertThat(starts.get(0).detail().get("skippedConditionalSteps")).contains("ENVIRONMENT_EQUALS(production)");
    }

    /** The flag predicate: the environment's resolved configuration decides, value-pinned or presence-only. */
    @Test
    void featureFlagConditionsEvaluateAgainstTheEnvironmentConfiguration() {
        World world = world("wf-flag");
        Map<String, Object> ifRegulatedFlag = new LinkedHashMap<>();
        ifRegulatedFlag.put("role", "COMPLIANCE_REVIEWER");
        ifRegulatedFlag.put(
                "condition", Map.of("type", "FEATURE_FLAG_CONTAINS", "key", "payments.eu-krn", "value", "on"));
        createGroups(
                world.managerToken,
                world.org,
                "flag-regulated",
                null,
                List.of(group("ALL", List.of(step("QA_ENGINEER"), ifRegulatedFlag))),
                201);

        UUID staging = createEnvironment(world, "staging", "Staging", "flag-regulated");
        assignEnvironmentTenants(world, staging, world.tenantA);
        put(
                world.managerToken,
                "/api/v1/environments/" + staging + "/config",
                Map.of("entries", Map.of("payments.eu-krn", "on", "theme", "dark")),
                200);

        // Flag on: demanded.
        Release flagged = releaseAtApprovalPending(world, "qa", world.tenantA, staging);
        assertThat(approvalsOf(world, flagged).get("rounds").get(0).get("steps").size())
                .isEqualTo(2);
        decide(world.qaToken, world, flagged, "APPROVE", 1, null, 200);
        decide(world.complianceToken, world, flagged, "APPROVE", 2, null, 200);
        transition(world.managerToken, world, flagged, "APPROVED", 200);

        // Flag flipped off: excluded with the recorded actual.
        put(
                world.managerToken,
                "/api/v1/environments/" + staging + "/config",
                Map.of("entries", Map.of("payments.eu-krn", "off", "theme", "dark")),
                200);
        Release unflagged = releaseAtApprovalPending(world, "qa", world.tenantA, staging);
        JsonNode steps = approvalsOf(world, unflagged).get("rounds").get(0).get("steps");
        assertThat(steps.get(1).get("status").asText()).isEqualTo("SKIPPED");
        assertThat(steps.get(1).get("reason").asText()).contains("payments.eu-krn=off");
    }

    // -----------------------------------------------------------------------
    // AC4: the definition surface's group shapes and edits
    // -----------------------------------------------------------------------

    @Test
    void groupedDefinitionsValidateTheirShapeBeforeAnyWrite() {
        World world = world("wf-shape");
        // Both shapes at once; an empty group; an unknown mode; a role
        // twice across groups; malformed conditions — each keyed to
        // its field, before any write.
        Map<String, Object> both =
                groupedBody(world.org, "both", "qa", List.of(group("ALL", List.of(step("QA_ENGINEER")))));
        both.put("steps", List.of(Map.of("role", "APP_OWNER")));
        assertThat(post(world.managerToken, "/api/v1/approval-workflows", both, 400)
                        .get("errors")
                        .get("steps")
                        .asText())
                .contains("not both");

        assertThat(createGroups(
                                world.managerToken,
                                world.org,
                                "empty-group",
                                null,
                                List.of(group("ALL", List.of()), group("ALL", List.of(step("QA_ENGINEER")))),
                                400)
                        .get("errors")
                        .get("groups[0]")
                        .asText())
                .contains("at least one step");

        assertThat(createGroups(
                                world.managerToken,
                                world.org,
                                "bad-mode",
                                null,
                                List.of(group("MAJORITY", List.of(step("QA_ENGINEER")))),
                                400)
                        .get("errors")
                        .get("groups[0].mode")
                        .asText())
                .contains("unknown group mode 'MAJORITY'");

        assertThat(createGroups(
                                world.managerToken,
                                world.org,
                                "dup-across-groups",
                                null,
                                List.of(
                                        group("ALL", List.of(step("QA_ENGINEER"))),
                                        group("ANY", List.of(step("QA_ENGINEER")))),
                                400)
                        .get("errors")
                        .get("groups[1].steps[0]")
                        .asText())
                .contains("at most once");

        Map<String, Object> missingValue = new LinkedHashMap<>();
        missingValue.put("role", "COMPLIANCE_REVIEWER");
        missingValue.put("condition", Map.of("type", "ENVIRONMENT_EQUALS"));
        assertThat(createGroups(
                                world.managerToken,
                                world.org,
                                "bad-condition",
                                null,
                                List.of(group("ALL", List.of(step("QA_ENGINEER"), missingValue))),
                                400)
                        .get("errors")
                        .get("groups[0].steps[1].condition")
                        .asText())
                .contains("ENVIRONMENT_EQUALS requires value");

        // The read-back carries the group facts (the editor's model).
        JsonNode created = createGroups(
                world.managerToken,
                world.org,
                "well-formed",
                null,
                List.of(
                        group("ALL", List.of(step("QA_ENGINEER"))),
                        group("ANY", List.of(step("SECURITY_REVIEWER"), step("COMPLIANCE_REVIEWER")))),
                201);
        assertThat(created.get("steps").size()).isEqualTo(3);
        assertThat(created.get("steps").get(0).get("groupPosition").asInt()).isEqualTo(1);
        assertThat(created.get("steps").get(1).get("groupPosition").asInt()).isEqualTo(2);
        assertThat(created.get("steps").get(1).get("groupMode").asText()).isEqualTo("ANY");
        assertThat(created.get("steps").get(2).get("groupMode").asText()).isEqualTo("ANY");
        assertThat(created.get("steps").get(0).get("condition").isNull()).isTrue();
    }

    /**
     * AC4's editor: a definition is editable — the chain replaces, the
     * ring re-attaches or detaches, the edit is audited, and a rework
     * restart instantiates the definition's CURRENT content (the
     * rounds already run keep their frozen copies).
     */
    @Test
    void definitionsAreEditableAndRestartsInstantiateCurrentContent() {
        World world = world("wf-edit");
        JsonNode created = createGroups(
                world.managerToken, world.org, "v1", "qa", List.of(group("ALL", List.of(step("QA_ENGINEER")))), 201);
        UUID workflowId = UUID.fromString(created.get("id").asText());

        // The edit: Security replaces QA, and the ring detaches (the
        // body names none).
        Map<String, Object> edited =
                groupedBody(world.org, "v1", null, List.of(group("ALL", List.of(step("SECURITY_REVIEWER")))));
        JsonNode updated = put(
                world.managerToken,
                "/api/v1/approval-workflows/" + workflowId + "?organizationId=" + world.org,
                edited,
                200);
        assertThat(updated.get("ring").isNull()).isTrue();
        assertThat(updated.get("steps").get(0).get("requiredRole").asText()).isEqualTo("SECURITY_REVIEWER");
        assertThat(audit.eventsOfType(AuditEventType.APPROVAL_WORKFLOW_UPDATED)).hasSize(1);

        // The detached ring no longer resolves: this release crosses
        // ungated (no definition), proving the detach took.
        Release ungated = releaseAtApprovalPending(world, "qa", world.tenantA, null);
        assertThat(approvalsOf(world, ungated).get("rounds").size()).isZero();
        transition(world.managerToken, world, ungated, "APPROVED", 200);

        // A re-bound definition's rework restart runs the CURRENT
        // chain: reject round 1, edit the chain, restart, and the new
        // round demands the edited step.
        Map<String, Object> rebind =
                groupedBody(world.org, "v1", "qa", List.of(group("ALL", List.of(step("QA_ENGINEER")))));
        put(
                world.managerToken,
                "/api/v1/approval-workflows/" + workflowId + "?organizationId=" + world.org,
                rebind,
                200);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);
        decide(world.qaToken, world, release, "REJECT", 1, "flaky", 200);
        put(
                world.managerToken,
                "/api/v1/approval-workflows/" + workflowId + "?organizationId=" + world.org,
                groupedBody(world.org, "v1", "qa", List.of(group("ALL", List.of(step("COMPLIANCE_REVIEWER"))))),
                200);
        restart(world.managerToken, world, release, "reworked onto compliance", 200);
        JsonNode round2 = approvalsOf(world, release).get("rounds").get(1);
        assertThat(round2.get("steps").size()).isEqualTo(1);
        assertThat(round2.get("steps").get(0).get("approvalType").asText()).isEqualTo("COMPLIANCE");
        // Round 1 keeps its frozen copy (QA), untouched by the edit.
        assertThat(approvalsOf(world, release)
                        .get("rounds")
                        .get(0)
                        .get("steps")
                        .get(0)
                        .get("approvalType")
                        .asText())
                .isEqualTo("QA");
    }

    // -----------------------------------------------------------------------
    // The folded #40 follow-ups
    // -----------------------------------------------------------------------

    /** The dangling environment binding fails CLOSED: the crossing refuses, the release stays READY. */
    @Test
    void aDanglingEnvironmentBindingFailsClosed() {
        World world = world("wf-dangling");
        UUID production = environmentOf(world, "production");
        assignEnvironmentTenants(world, production, world.tenantA);
        patch(world.managerToken, "/api/v1/environments/" + production, Map.of("approvalPolicy", "ghost-chain"), 200);

        Release release = releaseAtReady(world, production);
        transitionOf(world.managerToken, world, release, "VALIDATING", 200);
        transitionOf(world.managerToken, world, release, "READY", 200);
        JsonNode refused = transitionOf(world.managerToken, world, release, "APPROVAL_PENDING", 422);
        assertThat(refused.get("code").asText()).isEqualTo("APPROVAL_POLICY_UNRESOLVED");
        assertThat(refused.get("errors").get("approvalPolicy").asText()).isEqualTo("ghost-chain");
        assertThat(get(world.managerToken, releasePath(world, release), 200)
                        .get("status")
                        .asText())
                .isEqualTo("READY");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_approval_rounds WHERE release_id = ?", Long.class, release.id()))
                .isZero();

        // Defining the named workflow unblocks the same crossing.
        createGroups(
                world.managerToken,
                world.org,
                "ghost-chain",
                null,
                List.of(group("ALL", List.of(step("COMPLIANCE_REVIEWER")))),
                201);
        transitionOf(world.managerToken, world, release, "APPROVAL_PENDING", 200);
        assertThat(approvalsOf(world, release).get("rounds").get(0).get("steps").size())
                .isEqualTo(1);
    }

    /**
     * The double-restart insert race: two concurrent restarts of the
     * same rejected round both read it REJECTED; the unique guard
     * admits ONE round N+1 and the loser answers the precise 409 from
     * the committed state — never an untranslated 500.
     */
    @Test
    void concurrentRestartsTranslateTheInsertRaceToThePreciseConflict() throws Exception {
        World world = world("wf-race-restart");
        createGroups(
                world.managerToken, world.org, "racy", "qa", List.of(group("ALL", List.of(step("QA_ENGINEER")))), 201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);
        decide(world.qaToken, world, release, "REJECT", 1, "rework needed", 200);

        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Recording>> raced;
        try {
            raced = List.of(
                    pool.submit(() -> gatedRestart(world.managerToken, world, release, gate)),
                    pool.submit(() -> gatedRestart(world.managerTwoToken, world, release, gate)));
        } finally {
            pool.shutdown();
        }
        Recording first = raced.get(0).get(60, TimeUnit.SECONDS);
        Recording second = raced.get(1).get(60, TimeUnit.SECONDS);

        List<Integer> statuses = new ArrayList<>(List.of(first.status(), second.status()));
        statuses.sort(Integer::compareTo);
        assertThat(statuses).as("exactly one restart wins").containsExactly(200, 409);
        Recording loser = first.status() == 200 ? second : first;
        assertThat(loser.body().get("code").asText()).isEqualTo("APPROVAL_ROUND_NOT_RESTARTABLE");
        assertThat(loser.body().get("errors").get("current").asText()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_approval_rounds WHERE release_id = ?", Long.class, release.id()))
                .isEqualTo(2L);
    }

    /**
     * The programmatic probe's denial (#40's follow-up): a caller who
     * holds no approval authority is refused by the precise 403 AND
     * the evaluator records the ACCESS_DENIED audit beside the
     * decision meter's deny leg — the programmatic twin of the
     * annotation path's discipline.
     */
    @Test
    void probeDenialsAreAuditedAndMetered() {
        World world = world("wf-probe");
        createGroups(
                world.managerToken, world.org, "probe", "qa", List.of(group("ALL", List.of(step("QA_ENGINEER")))), 201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);

        audit.reset();
        JsonNode refused = decide(world.viewerToken, world, release, "APPROVE", null, null, 403);
        assertThat(refused.get("code").asText()).isEqualTo("APPROVAL_ROLE_NOT_HELD");
        List<AuditEvent> denials = audit.eventsOfType(AuditEventType.ACCESS_DENIED);
        assertThat(denials).hasSize(1);
        assertThat(denials.get(0).detail()).containsEntry("permission", "app:releases:approve-qa");
        assertThat(denials.get(0).detail()).containsEntry("method", "POST");
        // The probe feeds the same decision-timer family as the
        // annotation path: the deny leg carries this refusal.
        assertThat(decisionCount("deny")).isGreaterThanOrEqualTo(1L);
    }

    private long decisionCount(String outcome) {
        Timer timer = meters.find("shipperdog.authorization.decision.duration")
                .tag("outcome", outcome)
                .timer();
        return timer == null ? 0L : timer.count();
    }

    /** The reserved approval_duration lands at every round's terminal crossing, tagged by outcome. */
    @Test
    void approvalDurationIsRecordedAtTerminalCrossings() {
        World world = world("wf-duration");
        createGroups(
                world.managerToken,
                world.org,
                "timed",
                "qa",
                List.of(group("ALL", List.of(step("QA_ENGINEER"))), group("ALL", List.of(step("APP_OWNER")))),
                201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);

        // The rejection leg first (a rework round is restartable only
        // from a rejection): round 1 dies, the timer records it.
        decide(world.qaToken, world, release, "REJECT", 1, "regression found", 200);
        assertThat(timerCount("rejected")).isEqualTo(1L);
        assertThat(timerCount("satisfied")).isZero();

        restart(world.managerToken, world, release, "one more pass", 200);
        decide(world.qaToken, world, release, "APPROVE", 1, null, 200);
        // Mid-round: no satisfied recording yet.
        assertThat(timerCount("satisfied")).isZero();
        decide(world.ownerToken, world, release, "APPROVE", 2, null, 200);
        assertThat(timerCount("satisfied")).isEqualTo(1L);
        assertThat(timerCount("rejected")).isEqualTo(1L);
    }

    private long timerCount(String outcome) {
        Timer timer = meters.find("shipperdog.approval.duration")
                .tag("outcome", outcome)
                .timer();
        return timer == null ? 0L : timer.count();
    }

    // -----------------------------------------------------------------------
    // Fixtures: the world, the release, the vocabulary
    // -----------------------------------------------------------------------

    private static final class World {
        UUID org;
        String adminToken;
        String ownerToken;
        String managerToken;
        String managerTwoToken;
        String qaToken;
        String securityToken;
        String complianceToken;
        String viewerToken;
        UUID tenantA;
        UUID app;
    }

    private record Release(UUID id, String ref) {}

    private World world(String slug) {
        World world = new World();
        world.org = createOrg("Wf Modes " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.managerTwoToken = loginOfOrgRoleIn(world.org, slug + "-manager2", "RELEASE_MANAGER");
        world.qaToken = loginOfOrgRoleIn(world.org, slug + "-qa", "QA_ENGINEER");
        world.securityToken = loginOfOrgRoleIn(world.org, slug + "-security", "SECURITY_REVIEWER");
        world.complianceToken = loginOfOrgRoleIn(world.org, slug + "-compliance", "COMPLIANCE_REVIEWER");
        world.viewerToken = loginOfOrgRoleIn(world.org, slug + "-viewer", "VIEWER");
        world.tenantA = createTenant(world.adminToken, world.org, "Modes " + slug + " Bank", "crm-" + slug + "-a");
        world.app = createTenantApp(
                world.adminToken,
                world.tenantA,
                "Modes " + slug + " Banking",
                "com." + slug.replace("-", "") + ".modes");
        return world;
    }

    /** A fresh release walked to APPROVAL_PENDING — the approval moment. */
    private Release releaseAtApprovalPending(World world, String ring, UUID targetTenant, UUID environmentId) {
        String name = String.format("%d.%d.%d", 2 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(world.ownerToken, world.app, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("versionId", version.toString());
        body.put("ring", ring);
        if (environmentId != null) {
            body.put("environmentId", environmentId.toString());
        }
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", List.of(Map.of("tenantId", targetTenant.toString(), "testerGroupIds", List.of())));
        JsonNode created = post(world.managerToken, "/api/v1/releases", body, 201);
        Release release = new Release(
                UUID.fromString(created.get("id").asText()),
                created.get("releaseRef").asText());
        for (String step : List.of("VALIDATING", "READY", "APPROVAL_PENDING")) {
            transitionOf(world.managerToken, world, release, step, 200);
        }
        return release;
    }

    /** A fresh release walked only to DRAFT — the dangling-binding probe builds the rest itself. */
    private Release releaseAtReady(World world, UUID environmentId) {
        String name = String.format("%d.%d.%d", 2 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(world.ownerToken, world.app, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("versionId", version.toString());
        body.put("ring", "qa");
        body.put("environmentId", environmentId.toString());
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", List.of(Map.of("tenantId", world.tenantA.toString(), "testerGroupIds", List.of())));
        JsonNode created = post(world.managerToken, "/api/v1/releases", body, 201);
        return new Release(
                UUID.fromString(created.get("id").asText()),
                created.get("releaseRef").asText());
    }

    private JsonNode approvalsOf(World world, Release release) {
        return get(
                world.managerToken, "/api/v1/releases/" + release.id() + "/approvals?applicationId=" + world.app, 200);
    }

    private String releasePath(World world, Release release) {
        return "/api/v1/releases/" + release.id() + "?applicationId=" + world.app;
    }

    private List<String> manifestApprovalTypes(World world, Release release) {
        JsonNode manifest = get(
                world.managerToken, "/api/v1/releases/" + release.id() + "/manifest?applicationId=" + world.app, 200);
        List<String> types = new ArrayList<>();
        manifest.get("manifest").get("approval").get("required").forEach(entry -> types.add(entry.asText()));
        return types;
    }

    private static Map<String, Object> step(String role) {
        return Map.of("role", role);
    }

    private static Map<String, Object> group(String mode, List<Map<String, Object>> steps) {
        return Map.of("mode", mode, "steps", steps);
    }

    private static Map<String, Object> groupedBody(
            UUID organizationId, String name, String ring, List<Map<String, Object>> groups) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        if (ring != null) {
            body.put("ring", ring);
        }
        body.put("groups", groups);
        return body;
    }

    private JsonNode createGroups(
            String token,
            UUID organizationId,
            String name,
            String ring,
            List<Map<String, Object>> groups,
            int expected) {
        return post(token, "/api/v1/approval-workflows", groupedBody(organizationId, name, ring, groups), expected);
    }

    private static Map<String, Object> decisionBody(UUID applicationId, String decision, Integer step, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("decision", decision);
        if (step != null) {
            body.put("step", step);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        return body;
    }

    private JsonNode decide(
            String token, World world, Release release, String decision, Integer step, String reason, int expected) {
        return exchange(
                        "/api/v1/releases/" + release.id() + "/approvals",
                        token,
                        "POST",
                        decisionBody(world.app, decision, step, reason),
                        null)
                .expect(expected);
    }

    private JsonNode restart(String token, World world, Release release, String note, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        if (note != null) {
            body.put("reason", note);
        }
        return exchange("/api/v1/releases/" + release.id() + "/approvals/rounds", token, "POST", body, null)
                .expect(expected);
    }

    /** One race lane: both restarts released by the same barrier, then fired. */
    private Recording gatedRestart(String token, World world, Release release, CyclicBarrier gate) {
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("race gate failed", e);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        return exchange("/api/v1/releases/" + release.id() + "/approvals/rounds", token, "POST", body, null);
    }

    private JsonNode transitionOf(String token, World world, Release release, String to, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("to", to);
        return exchange("/api/v1/releases/" + release.id() + "/transitions", token, "POST", body, null)
                .expect(expected);
    }

    private JsonNode transition(String token, World world, Release release, String to, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", world.app.toString());
        body.put("to", to);
        return exchange("/api/v1/releases/" + release.id() + "/transitions", token, "POST", body, null)
                .expect(expected);
    }

    private UUID environmentOf(World world, String code) {
        JsonNode environments = get(world.managerToken, "/api/v1/environments?organizationId=" + world.org, 200);
        for (JsonNode item : environments.get("items")) {
            if (code.equals(item.get("code").asText())) {
                return UUID.fromString(item.get("id").asText());
            }
        }
        throw new IllegalStateException("environment '" + code + "' not seeded for the organization");
    }

    /** Creates a CUSTOM environment beyond the five seeded defaults
     * (the S9-04 surface), bound to the named approval policy — the
     * binding the conditional tests resolve their ring-less
     * definitions through. */
    private UUID createEnvironment(World world, String code, String name, String approvalPolicy) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", world.org.toString());
        body.put("code", code);
        body.put("name", name);
        body.put("position", 5);
        body.put("distributionChannel", "play-internal");
        body.put("approvalPolicy", approvalPolicy);
        return UUID.fromString(post(world.managerToken, "/api/v1/environments", body, 201)
                .get("id")
                .asText());
    }

    private void assignEnvironmentTenants(World world, UUID environmentId, UUID tenantId) {
        put(
                world.managerToken,
                "/api/v1/environments/" + environmentId + "/tenants",
                Map.of("tenantIds", List.of(tenantId.toString())),
                200);
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

    /** Inserts an artifact row directly (READY) — the release fixture's binary-free shortcut. */
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
                "b2".repeat(32),
                1024L,
                build,
                "READY",
                owner.get("organization_id"),
                owner.get("tenant_id"));
        return artifactId;
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
        return mapper.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
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
                        "Modes " + role))
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
        return exchange(uri, bearer, "POST", body, null).expect(expectedStatus);
    }

    private JsonNode put(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PUT", body, null).expect(expectedStatus);
    }

    private JsonNode patch(String bearer, String uri, Map<String, Object> body, int expectedStatus) {
        return exchange(uri, bearer, "PATCH", body, null).expect(expectedStatus);
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
