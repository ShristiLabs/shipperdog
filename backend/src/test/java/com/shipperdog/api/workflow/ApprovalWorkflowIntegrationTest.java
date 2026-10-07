package com.shipperdog.api.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.AuditCapture;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
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
 * End-to-end acceptance flow for the approval workflow engine (issue
 * #40, S10-01, FR-016, PRD 8.10/13/17/33): the definition surface
 * (AC1 — creation, validation, list/read, the two attachments), the
 * decision authority rules (AC2 — role and order; AC5 — self-approval
 * blocked), the rejection semantics and the rework path (AC3 —
 * progression blocked, the release held in APPROVAL_PENDING, the new
 * round), the crossing gate (AC4 — all steps complete permits the
 * APPROVED transition through the S9-02 policy seam, the satisfied
 * chain frozen into the PRD 16 manifest), and the audit discipline
 * (AC6 — every decision audited, the rejection's reason mandatory) —
 * beside the resolution precedence (environment binding over ring
 * chain), the tenant-scoped steps' applicability rule, the ungated
 * crossing an unconfigured organization keeps, the approval-requested
 * events S12-01 consumes, and the concealed-miss shapes of the
 * approval surface.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
@Import({AuditCapture.class, ApprovalWorkflowIntegrationTest.ApprovalFixtures.class})
class ApprovalWorkflowIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";

    /** FR-016's example chain as the definition's role vocabulary: QA, Security, Product, Release Manager. */
    private static final List<String> FULL_CHAIN =
            List.of("QA_ENGINEER", "SECURITY_REVIEWER", "APP_OWNER", "RELEASE_MANAGER");

    /**
     * The same chain as PRD 13's approval-type vocabulary — what the
     * records carry and the manifest freezes (the 1:1 role-to-type
     * map): roles name WHO signs, types name WHAT they sign.
     */
    private static final List<String> FULL_CHAIN_TYPES = List.of("QA", "SECURITY", "PRODUCT", "RELEASE_MANAGER");

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
    private ApprovalEventRecorder requestedEvents;

    private RestClient client;

    private int versionCounter;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        audit.reset();
        requestedEvents.events.clear();
    }

    // -----------------------------------------------------------------------
    // AC1: the definition surface
    // -----------------------------------------------------------------------

    @Test
    void definitionsCreateListReadAndValidate() {
        World world = world("wf-def");
        JsonNode created =
                createWorkflow(world.managerToken, world.org, "standard-chain", "qa", chain(FULL_CHAIN, null), 201);
        assertThat(created.get("name").asText()).isEqualTo("standard-chain");
        assertThat(created.get("ring").asText()).isEqualTo("qa");
        assertThat(created.get("organizationId").asText()).isEqualTo(world.org.toString());
        assertThat(positionsOf(created)).containsExactly(1, 2, 3, 4);
        assertThat(rolesOf(created)).containsExactlyElementsOf(FULL_CHAIN);

        // The listing: the organization's chains, paginated shape.
        JsonNode listed = get(world.managerToken, "/api/v1/approval-workflows?organizationId=" + world.org, 200);
        assertThat(listed.get("items")).isNotEmpty();
        assertThat(listed.has("next_cursor")).isFalse();

        // The detail: the chain read back; a foreign organization's id conceals.
        JsonNode detail = get(
                world.managerToken,
                "/api/v1/approval-workflows/" + created.get("id").asText() + "?organizationId=" + world.org,
                200);
        assertThat(rolesOf(detail)).containsExactlyElementsOf(FULL_CHAIN);
        JsonNode foreign = get(
                world.managerToken,
                "/api/v1/approval-workflows/" + created.get("id").asText() + "?organizationId=" + world.otherOrg,
                404);
        // The concealment is the organization-grain denial shape (the
        // caller holds no role at the foreign organization).
        assertThat(foreign.get("code").asText()).isEqualTo("ORGANIZATION_NOT_FOUND");

        // The name-only definition (an environment binds it by name).
        JsonNode nameOnly = createWorkflow(
                world.managerToken, world.org, "regulated", null, chain(List.of("COMPLIANCE_REVIEWER"), null), 201);
        assertThat(nameOnly.get("ring").isNull()).isTrue();

        // Validation refuses before any write, keyed by the offending field.
        assertThat(createWorkflow(world.managerToken, world.org, "bad-role", "qa", chain(List.of("WIZARD"), null), 400)
                        .get("errors")
                        .get("steps[0]")
                        .asText())
                .contains("unknown approval role 'WIZARD'");
        assertThat(createWorkflow(
                                world.managerToken,
                                world.org,
                                "dup-role",
                                "qa",
                                chain(List.of("QA_ENGINEER", "QA_ENGINEER"), null),
                                400)
                        .get("errors")
                        .get("steps[1]")
                        .asText())
                .contains("at most once");
        assertThat(createWorkflow(world.managerToken, world.org, "no-steps", "qa", chain(List.of(), null), 400)
                        .get("errors")
                        .get("steps")
                        .asText())
                .contains("at least one approval step");
        assertThat(createWorkflow(
                                world.managerToken,
                                world.org,
                                "Bad_Name",
                                null,
                                chain(List.of("QA_ENGINEER"), null),
                                400)
                        .get("errors")
                        .get("name")
                        .asText())
                .contains("lowercase kebab");
        assertThat(createWorkflow(
                                world.managerToken,
                                world.org,
                                "unknown-ring",
                                "orbit",
                                chain(List.of("QA_ENGINEER"), null),
                                400)
                        .get("code")
                        .asText())
                .isEqualTo("WORKFLOW_RING_UNKNOWN");
        // A tenant scope must sit inside the organization.
        Map<String, Object> foreignScoped = new LinkedHashMap<>();
        foreignScoped.put("role", "QA_ENGINEER");
        foreignScoped.put("tenantId", world.otherTenant.toString());
        assertThat(createWorkflow(world.managerToken, world.org, "foreign-scope", null, List.of(foreignScoped), 400)
                        .get("errors")
                        .get("steps[0]")
                        .asText())
                .contains("must belong to the workflow's organization");

        // One name and one ring attachment per organization.
        createWorkflow(world.managerToken, world.org, "standard-chain", null, chain(List.of("QA_ENGINEER"), null), 409);
        createWorkflow(world.managerToken, world.org, "other-name", "qa", chain(List.of("QA_ENGINEER"), null), 409);

        // A developer reads the chains but does not manage them (the
        // concealed org-grain denial), and the creation is audited.
        assertThat(get(world.developerToken, "/api/v1/approval-workflows?organizationId=" + world.org, 200)
                        .get("items")
                        .size())
                .isEqualTo(2);
        createWorkflow(world.developerToken, world.org, "dev-chain", null, chain(List.of("QA_ENGINEER"), null), 404);
        List<AuditEvent> createdEvents = audit.eventsOfType(AuditEventType.APPROVAL_WORKFLOW_CREATED);
        assertThat(createdEvents).hasSize(2);
        assertThat(createdEvents.get(0).detail()).containsEntry("name", "standard-chain");
        assertThat(createdEvents.get(0).detail().get("steps")).contains("QA_ENGINEER", "RELEASE_MANAGER");
    }

    @Test
    void definitionCreationIsIdempotentUnderAKey() {
        World world = world("wf-idem");
        Map<String, Object> body = definitionBody(world.org, "idem-chain", null, chain(List.of("QA_ENGINEER"), null));
        JsonNode first = exchange("/api/v1/approval-workflows", world.managerToken, "POST", body, "wf-key-1")
                .expect(201);
        JsonNode replay = exchange("/api/v1/approval-workflows", world.managerToken, "POST", body, "wf-key-1")
                .expect(201);
        assertThat(replay.get("id").asText()).isEqualTo(first.get("id").asText());
    }

    // -----------------------------------------------------------------------
    // AC2/AC4/AC5/AC6: decisions, the crossing gate, the manifest record
    // -----------------------------------------------------------------------

    @Test
    void theFullChainGatesTheCrossingAndFreezesIntoTheManifest() {
        World world = world("wf-full");
        createWorkflow(world.managerToken, world.org, "standard-chain", "qa", chain(FULL_CHAIN, null), 201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);

        // The crossing instantiated round 1 and published one request
        // event per step, in chain order (S12-01's feed).
        assertThat(requestedEvents.events).hasSize(4);
        assertThat(requestedEvents.events.stream()
                        .map(ApprovalRequestedEvent::approvalType)
                        .toList())
                .containsExactlyElementsOf(FULL_CHAIN_TYPES);
        assertThat(requestedEvents.events.get(0).releaseRef()).isEqualTo(release.ref());
        assertThat(requestedEvents.events.get(0).roundNumber()).isEqualTo(1);
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_APPROVAL_ROUND_STARTED))
                .hasSize(1);

        JsonNode state = approvalsOf(world, release);
        assertThat(state.get("currentRoundNumber").asInt()).isEqualTo(1);
        assertThat(state.get("currentRoundStatus").asText()).isEqualTo("PENDING");
        assertThat(state.get("rounds").size()).isEqualTo(1);

        // AC2: out-of-order is the precise 409 (current + attempted).
        JsonNode outOfOrder = decide(world.securityToken, world, release, "APPROVE", 2, null, 409);
        assertThat(outOfOrder.get("code").asText()).isEqualTo("APPROVAL_STEP_OUT_OF_ORDER");
        assertThat(outOfOrder.get("errors").get("current").asText()).isEqualTo("1");
        assertThat(outOfOrder.get("errors").get("attempted").asText()).isEqualTo("2");

        // AC2: only the step's role decides it — the QA holder cannot
        // decide the Security step (and the message names the role so
        // the right approver is findable).
        // The chain decides in order; the QA holder signs step 1.
        audit.reset();
        decide(world.qaToken, world, release, "APPROVE", 1, "green build", 200);

        // AC2: only the step's role decides it — with the Security step
        // now current, the QA holder's decision is the precise 403
        // naming the required role and permission so the right
        // approver is findable.
        JsonNode wrongRole = decide(world.qaToken, world, release, "APPROVE", 2, null, 403);
        assertThat(wrongRole.get("code").asText()).isEqualTo("APPROVAL_ROLE_NOT_HELD");
        assertThat(wrongRole.get("errors").get("requiredRole").asText()).isEqualTo("SECURITY_REVIEWER");
        assertThat(wrongRole.get("errors").get("permission").asText()).isEqualTo("app:releases:approve-security");

        decide(world.securityToken, world, release, "APPROVE", 2, null, 200);
        decide(world.ownerToken, world, release, "APPROVE", 3, null, 200);
        List<AuditEvent> granted = audit.eventsOfType(AuditEventType.RELEASE_APPROVAL_GRANTED);
        assertThat(granted).hasSize(3);
        assertThat(granted.get(0).detail()).containsEntry("approvalType", "QA");
        assertThat(granted.get(0).detail()).containsEntry("reason", "green build");
        assertThat(granted.get(0).detail()).containsEntry("position", "1");

        // AC5: the creator signs nothing, whatever roles they hold —
        // the manager created this release AND holds approve-release.
        JsonNode selfApproval = decide(world.managerToken, world, release, "APPROVE", 4, null, 403);
        assertThat(selfApproval.get("code").asText()).isEqualTo("APPROVAL_SELF_APPROVAL");

        // AC4: before the last step the crossing is refused; after it,
        // the same request succeeds — the gate is the policy seam.
        JsonNode blocked = transition(world.managerToken, world, release, "APPROVED", 422);
        assertThat(blocked.get("code").asText()).isEqualTo("RELEASE_TRANSITION_BLOCKED");
        assertThat(blocked.get("errors").get("ReleaseApprovalPolicy").asText())
                .contains("round 1 awaits step 4 of 4 (RELEASE_MANAGER)");

        decide(world.managerTwoToken, world, release, "APPROVE", 4, null, 200);
        state = approvalsOf(world, release);
        assertThat(state.get("currentRoundStatus").asText()).isEqualTo("SATISFIED");
        JsonNode signatures = state.get("rounds").get(0).get("steps");
        assertThat(signatures.size()).isEqualTo(4);
        assertThat(signatures.get(3).get("approverId").asText())
                .isEqualTo(userIdOf(world.managerTwoToken).toString());
        assertThat(signatures.get(3).get("approverRole").asText()).isEqualTo("RELEASE_MANAGER");

        JsonNode approved = transition(world.managerToken, world, release, "APPROVED", 200);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");

        // The PRD 16 manifest freezes the satisfied chain verbatim.
        JsonNode manifest = get(
                world.managerToken, "/api/v1/releases/" + release.id() + "/manifest?applicationId=" + world.app, 200);
        JsonNode document = manifest.get("manifest");
        assertThat(document).isNotNull();
        List<String> requiredTypes = new ArrayList<>();
        document.get("approval").get("required").forEach(entry -> requiredTypes.add(entry.asText()));
        assertThat(requiredTypes).containsExactlyElementsOf(FULL_CHAIN_TYPES);
    }

    // -----------------------------------------------------------------------
    // AC3/AC6: rejection blocks progression; the rework round
    // -----------------------------------------------------------------------

    @Test
    void rejectionBlocksProgressionAndTheReworkRoundUnblocksIt() {
        World world = world("wf-reject");
        createWorkflow(world.managerToken, world.org, "standard-chain", "qa", chain(FULL_CHAIN, null), 201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);

        // AC6: a rejection explains itself — the missing reason is the
        // 422 naming the field, before any write.
        JsonNode missingReason = decide(world.qaToken, world, release, "REJECT", 1, null, 422);
        assertThat(missingReason.get("code").asText()).isEqualTo("APPROVAL_REJECT_REASON_REQUIRED");
        assertThat(missingReason.get("errors").get("reason").asText()).contains("required");

        audit.reset();
        decide(world.qaToken, world, release, "REJECT", 1, "flaky login tests on iOS 18.2", 200);
        List<AuditEvent> rejected = audit.eventsOfType(AuditEventType.RELEASE_APPROVAL_REJECTED);
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).detail()).containsEntry("reason", "flaky login tests on iOS 18.2");

        // AC3: the release stays APPROVAL_PENDING with the rejection
        // recorded; the crossing keeps refusing.
        assertThat(approvalsOf(world, release).get("currentRoundStatus").asText())
                .isEqualTo("REJECTED");
        assertThat(get(world.managerToken, releasePath(world, release), 200)
                        .get("status")
                        .asText())
                .isEqualTo("APPROVAL_PENDING");
        JsonNode blocked = transition(world.managerToken, world, release, "APPROVED", 422);
        assertThat(blocked.get("errors").get("ReleaseApprovalPolicy").asText()).contains("rejected");
        // A terminal round accepts no decisions.
        assertThat(decide(world.securityToken, world, release, "APPROVE", 2, null, 409)
                        .get("code")
                        .asText())
                .isEqualTo("APPROVAL_NOT_ACTIVE");

        // The rework path: a new round of the same definition — never
        // an un-reject. The creator may restart (release management,
        // not a signature); a second release manager approves the
        // final step in the new round.
        JsonNode restarted = restart(world.managerToken, world, release, "reworked after test fixes", 200);
        assertThat(restarted.get("currentRoundNumber").asInt()).isEqualTo(2);
        assertThat(restarted.get("currentRoundStatus").asText()).isEqualTo("PENDING");
        assertThat(restarted.get("rounds").size()).isEqualTo(2);
        assertThat(restarted.get("rounds").get(0).get("status").asText()).isEqualTo("REJECTED");
        assertThat(restarted
                        .get("rounds")
                        .get(0)
                        .get("steps")
                        .get(0)
                        .get("reason")
                        .asText())
                .isEqualTo("flaky login tests on iOS 18.2");
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_APPROVAL_ROUND_STARTED))
                .hasSize(1);

        decide(world.qaToken, world, release, "APPROVE", null, null, 200);
        decide(world.securityToken, world, release, "APPROVE", null, null, 200);
        decide(world.ownerToken, world, release, "APPROVE", null, null, 200);
        decide(world.managerTwoToken, world, release, "APPROVE", null, null, 200);
        transition(world.managerToken, world, release, "APPROVED", 200);
        JsonNode manifest = get(
                world.managerToken, "/api/v1/releases/" + release.id() + "/manifest?applicationId=" + world.app, 200);
        List<String> frozen = new ArrayList<>();
        manifest.get("manifest").get("approval").get("required").forEach(entry -> frozen.add(entry.asText()));
        assertThat(frozen).containsExactlyElementsOf(FULL_CHAIN_TYPES);
    }

    @Test
    void onlyRejectedRoundsRestart() {
        World world = world("wf-restart");
        createWorkflow(world.managerToken, world.org, "qa-only", "qa", chain(List.of("QA_ENGINEER"), null), 201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);

        // A running round is not restartable.
        JsonNode pending = restart(world.managerToken, world, release, null, 409);
        assertThat(pending.get("code").asText()).isEqualTo("APPROVAL_ROUND_NOT_RESTARTABLE");
        assertThat(pending.get("errors").get("current").asText()).isEqualTo("PENDING");

        // A satisfied round did its work; nothing to restart.
        decide(world.qaToken, world, release, "APPROVE", null, null, 200);
        assertThat(restart(world.managerToken, world, release, null, 409)
                        .get("errors")
                        .get("current")
                        .asText())
                .isEqualTo("SATISFIED");

        // A release outside the approval moment accepts no restart.
        transition(world.managerToken, world, release, "APPROVED", 200);
        assertThat(restart(world.managerToken, world, release, null, 409)
                        .get("code")
                        .asText())
                .isEqualTo("APPROVAL_ROUND_NOT_RESTARTABLE");
    }

    // -----------------------------------------------------------------------
    // Resolution: the environment binding over the ring chain; tenant
    // scope; the ungated organization
    // -----------------------------------------------------------------------

    @Test
    void theEnvironmentBindingWinsOverTheRingChain() {
        World world = world("wf-env");
        createWorkflow(
                world.managerToken, world.org, "regulated", null, chain(List.of("COMPLIANCE_REVIEWER"), null), 201);
        createWorkflow(world.managerToken, world.org, "ring-chain", "qa", chain(List.of("QA_ENGINEER"), null), 201);
        UUID production = environmentOf(world, "production");
        patch(world.managerToken, "/api/v1/environments/" + production, Map.of("approvalPolicy", "regulated"), 200);
        put(
                world.managerToken,
                "/api/v1/environments/" + production + "/tenants",
                Map.of("tenantIds", List.of(world.tenantA.toString())),
                200);

        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, production);
        JsonNode state = approvalsOf(world, release);
        // The named environment policy resolved, not the ring chain:
        // one COMPLIANCE step (the Compliance Reviewer's type), position 1.
        assertThat(state.get("rounds").get(0).get("steps").size()).isEqualTo(1);
        assertThat(state.get("rounds")
                        .get(0)
                        .get("steps")
                        .get(0)
                        .get("approvalType")
                        .asText())
                .isEqualTo("COMPLIANCE");

        decide(world.complianceToken, world, release, "APPROVE", null, null, 200);
        transition(world.managerToken, world, release, "APPROVED", 200);
    }

    @Test
    void tenantScopedStepsApplyOnlyToReleasesTargetingTheirTenant() {
        World world = world("wf-scope");
        Map<String, Object> qaScoped = new LinkedHashMap<>();
        qaScoped.put("role", "QA_ENGINEER");
        qaScoped.put("tenantId", world.tenantB.toString());
        createWorkflow(
                world.managerToken,
                world.org,
                "scoped-chain",
                "qa",
                List.of(qaScoped, Map.of("role", "SECURITY_REVIEWER")),
                201);

        // An organization-SHARED application authorized to both
        // tenants: releases may target either (the tenant-owned
        // fixture application cannot — the S3-03 rule).
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("ownershipScope", "ORGANIZATION");
        shared.put("organizationId", world.org.toString());
        shared.put("name", "Wf Scoped Shared");
        shared.put("platforms", List.of("IOS", "ANDROID"));
        shared.put("bundleId", "com.wfscoped.shared");
        shared.put("packageName", "com.wfscoped.shared");
        UUID app = UUID.fromString(post(world.adminToken, "/api/v1/applications", shared, 201)
                .get("id")
                .asText());
        post(world.adminToken, "/api/v1/applications/" + app + "/tenants/" + world.tenantA, Map.of(), 201);
        post(world.adminToken, "/api/v1/applications/" + app + "/tenants/" + world.tenantB, Map.of(), 201);

        // A release NOT targeting tenant B: the scoped step is skipped
        // (never a failure) — the chain runs from the Security step,
        // its definition position preserved and scope-less.
        Release unscoped = releaseAtApprovalPendingFor(world.ownerToken, world.managerToken, app, "qa", world.tenantA);
        JsonNode unscopedState =
                get(world.managerToken, "/api/v1/releases/" + unscoped.id() + "/approvals?applicationId=" + app, 200);
        assertThat(unscopedState.get("rounds").get(0).get("steps").size()).isEqualTo(1);
        assertThat(unscopedState
                        .get("rounds")
                        .get(0)
                        .get("steps")
                        .get(0)
                        .get("position")
                        .asInt())
                .isEqualTo(2);
        assertThat(unscopedState
                        .get("rounds")
                        .get(0)
                        .get("steps")
                        .get(0)
                        .get("tenantId")
                        .isNull())
                .isTrue();
        exchange(
                        "/api/v1/releases/" + unscoped.id() + "/approvals",
                        world.securityToken,
                        "POST",
                        decisionBody(app, "APPROVE", null, null),
                        null)
                .expect(200);
        transitionOf(world.managerToken, app, unscoped, "APPROVED", 200);

        // A release targeting tenant B: the scoped step applies.
        Release scoped = releaseAtApprovalPendingFor(world.ownerToken, world.managerToken, app, "qa", world.tenantB);
        assertThat(get(world.managerToken, "/api/v1/releases/" + scoped.id() + "/approvals?applicationId=" + app, 200)
                        .get("rounds")
                        .get(0)
                        .get("steps")
                        .size())
                .isEqualTo(2);
    }

    @Test
    void anUnconfiguredOrganizationKeepsTheUngatedCrossing() {
        World world = world("wf-ungated");
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);
        // No definition resolved: no round, no gate — the machine
        // S9-02 shipped, and the state view is the honest empty.
        assertThat(approvalsOf(world, release).get("rounds").size()).isZero();
        assertThat(approvalsOf(world, release).get("currentRoundNumber").asInt())
                .isZero();
        assertThat(transition(world.managerToken, world, release, "APPROVED", 200)
                        .get("status")
                        .asText())
                .isEqualTo("APPROVED");
        JsonNode manifest = get(
                world.managerToken, "/api/v1/releases/" + release.id() + "/manifest?applicationId=" + world.app, 200);
        assertThat(manifest.get("manifest").get("approval").get("required").size())
                .isZero();
    }

    // -----------------------------------------------------------------------
    // Concealment: the approval surface's miss shapes
    // -----------------------------------------------------------------------

    @Test
    void theApprovalSurfaceConcealsMissesAndTheGateless() {
        World world = world("wf-conceal");
        createWorkflow(world.managerToken, world.org, "qa-only", "qa", chain(List.of("QA_ENGINEER"), null), 201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);

        // A REAL release of another application, addressed under this
        // application: the seam serves neither — the concealed miss,
        // byte-identical to an unknown id (modulo the trace id).
        String otherOwner = loginOfOrgRoleIn(world.otherOrg, "wf-conceal-other-owner", "APP_OWNER");
        String otherManager = loginOfOrgRoleIn(world.otherOrg, "wf-conceal-other-manager", "RELEASE_MANAGER");
        Release foreignRelease =
                releaseAtApprovalPendingFor(otherOwner, otherManager, world.otherApp, "qa", world.otherTenant);
        JsonNode foreign = exchange(
                        "/api/v1/releases/" + foreignRelease.id() + "/approvals",
                        world.managerToken,
                        "POST",
                        decisionBody(world.app, "APPROVE", null, null),
                        null)
                .expect(404);
        JsonNode miss = exchange(
                        "/api/v1/releases/" + UUID.randomUUID() + "/approvals",
                        world.managerToken,
                        "POST",
                        decisionBody(world.app, "APPROVE", null, null),
                        null)
                .expect(404);
        assertThat(problemOf(foreign)).isEqualTo(problemOf(miss));
        assertThat(foreign.get("code").asText()).isEqualTo("RELEASE_NOT_FOUND");

        // The read route of a foreign application conceals at the
        // permission gate (the application-grain denial shape).
        JsonNode foreignRead = get(
                world.managerToken,
                "/api/v1/releases/" + foreignRelease.id() + "/approvals?applicationId=" + world.otherApp,
                404);
        assertThat(foreignRead.get("code").asText()).isEqualTo("APPLICATION_NOT_FOUND");

        // A caller of a foreign organization receives the concealed
        // denial at the coarse gate — no state is read.
        String otherViewer = loginOfOrgRoleIn(world.otherOrg, "wf-conceal-other-viewer", "VIEWER");
        exchange(
                        "/api/v1/releases/" + release.id() + "/approvals",
                        otherViewer,
                        "POST",
                        decisionBody(world.app, "APPROVE", null, null),
                        null)
                .expect(404);

        // A caller of THIS organization who holds no approval
        // authority passes the read gate and receives the precise
        // refusal naming the step's required role — the release is
        // already visible to them; nothing is concealed.
        JsonNode unauthoritative = exchange(
                        "/api/v1/releases/" + release.id() + "/approvals",
                        world.viewerToken,
                        "POST",
                        decisionBody(world.app, "APPROVE", null, null),
                        null)
                .expect(403);
        assertThat(unauthoritative.get("code").asText()).isEqualTo("APPROVAL_ROLE_NOT_HELD");
    }

    // -----------------------------------------------------------------------
    // Concurrency: the signature's compare-and-set; the crossing's
    // transactional containment
    // -----------------------------------------------------------------------

    /**
     * AC2 under contention: two different holders of the final step's
     * role decide it concurrently and the compare-and-set admits
     * exactly one signature — the loser reads the committed state and
     * is answered from it, never a double-signature and never a lost
     * round. The creator (who also holds RELEASE_MANAGER) is barred by
     * AC5, so the racers are the second and third holders.
     */
    @Test
    void racingApproversDecideTheLastStepExactlyOnce() throws Exception {
        World world = world("wf-race");
        createWorkflow(
                world.managerToken,
                world.org,
                "qa-release",
                "qa",
                chain(List.of("QA_ENGINEER", "RELEASE_MANAGER"), null),
                201);
        Release release = releaseAtApprovalPending(world, "qa", world.tenantA, null);
        decide(world.qaToken, world, release, "APPROVE", 1, null, 200);

        audit.reset();
        CyclicBarrier gate = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Recording>> raced;
        try {
            raced = List.of(
                    pool.submit(() -> gatedDecide(world.managerTwoToken, world, release, 2, gate)),
                    pool.submit(() -> gatedDecide(world.managerThreeToken, world, release, 2, gate)));
        } finally {
            pool.shutdown();
        }
        Recording first = raced.get(0).get(60, TimeUnit.SECONDS);
        Recording second = raced.get(1).get(60, TimeUnit.SECONDS);

        // Exactly one signature won: one 200, one 409. The loser is
        // answered from the committed state — the compare-and-set's
        // out-of-order refusal when it saw the pending row, or the
        // not-active refusal when the winner's commit landed first;
        // both name the same invariant (the step was decided once).
        List<Integer> statuses = new ArrayList<>(List.of(first.status(), second.status()));
        statuses.sort(Integer::compareTo);
        assertThat(statuses).containsExactly(200, 409);
        Recording loser = first.status() == 200 ? second : first;
        assertThat(loser.body().get("code").asText()).isIn("APPROVAL_STEP_OUT_OF_ORDER", "APPROVAL_NOT_ACTIVE");
        String winnerToken = first.status() == 200 ? world.managerTwoToken : world.managerThreeToken;

        // The database carries ONE decided row — the winner's signature.
        List<String> signers = jdbc.queryForList(
                "SELECT ra.approver::text FROM release_approvals ra "
                        + "JOIN release_approval_rounds rr ON rr.id = ra.round_id "
                        + "WHERE rr.release_id = ? AND ra.position = 2 AND ra.status = 'APPROVED'",
                String.class,
                release.id());
        assertThat(signers).containsExactly(userIdOf(winnerToken).toString());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_approvals ra "
                                + "JOIN release_approval_rounds rr ON rr.id = ra.round_id "
                                + "WHERE rr.release_id = ? AND ra.position = 2",
                        Long.class,
                        release.id()))
                .isEqualTo(1L);

        // One granted audit (the loser's write rolled back with its
        // refused decision), the round satisfied by exactly the chain,
        // and the crossing the race left behind is fireable.
        assertThat(audit.eventsOfType(AuditEventType.RELEASE_APPROVAL_GRANTED)).hasSize(1);
        JsonNode state = approvalsOf(world, release);
        assertThat(state.get("currentRoundStatus").asText()).isEqualTo("SATISFIED");
        assertThat(state.get("rounds")
                        .get(0)
                        .get("steps")
                        .get(1)
                        .get("approverId")
                        .asText())
                .isEqualTo(userIdOf(winnerToken).toString());
        assertThat(state.get("rounds")
                        .get(0)
                        .get("steps")
                        .get(1)
                        .get("approverRole")
                        .asText())
                .isEqualTo("RELEASE_MANAGER");
        assertThat(transition(world.managerToken, world, release, "APPROVED", 200)
                        .get("status")
                        .asText())
                .isEqualTo("APPROVED");
    }

    /**
     * Round 1 instantiates INSIDE the READY to APPROVAL_PENDING
     * crossing's transaction: an instantiation failure takes the whole
     * crossing with it. A phantom round carried by a READY release
     * (direct database surgery — the state machine's forward-only rule
     * makes it unreachable through the API) trips the service's
     * double-round guard; the crossing must roll back to READY leaving
     * no orphan round or per-step records behind.
     */
    @Test
    void aFailedCrossingInstantiationRollsBackWithNoOrphanRounds() {
        World world = world("wf-rollback");
        JsonNode workflow = createWorkflow(
                world.managerToken, world.org, "qa-only", "qa", chain(List.of("QA_ENGINEER"), null), 201);
        Release release = releaseAtReady(world);
        jdbc.update(
                "INSERT INTO release_approval_rounds "
                        + "(id, release_id, round_number, workflow_id, status, created_by) "
                        + "VALUES (?, ?, 1, ?, 'PENDING', ?)",
                UUID.randomUUID(),
                release.id(),
                UUID.fromString(workflow.get("id").asText()),
                userIdOf(world.managerToken));

        // The guard answers loudly (a machine bug, not a client error)
        // and the listener's throw unwinds the transition itself.
        assertThat(transition(world.managerToken, world, release, "APPROVAL_PENDING", 500)
                        .get("status")
                        .asInt())
                .isEqualTo(500);

        assertThat(get(world.managerToken, releasePath(world, release), 200)
                        .get("status")
                        .asText())
                .isEqualTo("READY");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_approval_rounds WHERE release_id = ?", Long.class, release.id()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM release_approvals WHERE release_id = ?", Long.class, release.id()))
                .isZero();
    }

    /** One race lane: both decisions released by the same barrier, then fired. */
    private Recording gatedDecide(String token, World world, Release release, int step, CyclicBarrier gate) {
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("race gate failed", e);
        }
        return exchange(
                "/api/v1/releases/" + release.id() + "/approvals",
                token,
                "POST",
                decisionBody(world.app, "APPROVE", step, null),
                null);
    }

    // -----------------------------------------------------------------------
    // Fixtures: the world, the release, the approvals vocabulary
    // -----------------------------------------------------------------------

    /** Records every published approval request (S12-01's feed, observed in-process). */
    static class ApprovalEventRecorder {
        final List<ApprovalRequestedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(ApprovalRequestedEvent event) {
            events.add(event);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ApprovalFixtures {
        @Bean
        ApprovalEventRecorder approvalEventRecorder() {
            return new ApprovalEventRecorder();
        }
    }

    private static final class World {
        UUID org;
        UUID otherOrg;
        UUID otherTenant;
        UUID otherApp;
        String adminToken;
        String ownerToken;
        String managerToken;
        String managerTwoToken;
        String managerThreeToken;
        String qaToken;
        String securityToken;
        String complianceToken;
        String developerToken;
        String viewerToken;
        UUID tenantA;
        UUID tenantB;
        UUID app;
    }

    /** One walked release and its quotable identity. */
    private record Release(UUID id, String ref) {}

    private World world(String slug) {
        World world = new World();
        world.org = createOrg("Wf " + slug + " Corp");
        world.adminToken = loginOfOrgRoleIn(world.org, slug + "-admin", "ORG_ADMIN");
        world.ownerToken = loginOfOrgRoleIn(world.org, slug + "-owner", "APP_OWNER");
        world.managerToken = loginOfOrgRoleIn(world.org, slug + "-manager", "RELEASE_MANAGER");
        world.managerTwoToken = loginOfOrgRoleIn(world.org, slug + "-manager2", "RELEASE_MANAGER");
        world.managerThreeToken = loginOfOrgRoleIn(world.org, slug + "-manager3", "RELEASE_MANAGER");
        world.qaToken = loginOfOrgRoleIn(world.org, slug + "-qa", "QA_ENGINEER");
        world.securityToken = loginOfOrgRoleIn(world.org, slug + "-security", "SECURITY_REVIEWER");
        world.complianceToken = loginOfOrgRoleIn(world.org, slug + "-compliance", "COMPLIANCE_REVIEWER");
        world.developerToken = loginOfOrgRoleIn(world.org, slug + "-dev", "DEVELOPER");
        world.viewerToken = loginOfOrgRoleIn(world.org, slug + "-viewer", "VIEWER");
        world.tenantA = createTenant(world.adminToken, world.org, "Wf " + slug + " Bank", "crm-" + slug + "-a");
        world.tenantB = createTenant(world.adminToken, world.org, "Wf " + slug + " Bank B", "crm-" + slug + "-b");
        world.app = createTenantApp(
                world.adminToken,
                world.tenantA,
                "Wf " + slug + " Banking",
                "com." + slug.replace("-", "") + ".banking");
        // A second organization's application: the concealment probes'
        // foreign target (a release of ANOTHER application's shape).
        world.otherOrg = createOrg("Wf " + slug + " Other Corp");
        String otherAdmin = loginOfOrgRoleIn(world.otherOrg, slug + "-other-admin", "ORG_ADMIN");
        world.otherTenant = createTenant(otherAdmin, world.otherOrg, "Wf Other Bank", "crm-" + slug + "-x");
        world.otherApp = createTenantApp(
                otherAdmin, world.otherTenant, "Wf " + slug + " Other", "com." + slug.replace("-", "") + ".other");
        return world;
    }

    /** A fresh release walked to APPROVAL_PENDING — the approval moment. */
    private Release releaseAtApprovalPending(World world, String ring, UUID targetTenant, UUID environmentId) {
        return releaseAtApprovalPendingFor(
                world.ownerToken, world.managerToken, world.app, ring, targetTenant, environmentId);
    }

    /** The same walk for any application's tokens (the concealment probes' foreign world). */
    private Release releaseAtApprovalPendingFor(
            String ownerToken, String managerToken, UUID applicationId, String ring, UUID targetTenant) {
        return releaseAtApprovalPendingFor(ownerToken, managerToken, applicationId, ring, targetTenant, null);
    }

    private Release releaseAtApprovalPendingFor(
            String ownerToken,
            String managerToken,
            UUID applicationId,
            String ring,
            UUID targetTenant,
            UUID environmentId) {
        String name = String.format("%d.%d.%d", 1 + versionCounter / 10, versionCounter % 10, 7);
        versionCounter++;
        UUID version = createVersion(ownerToken, applicationId, name);
        UUID artifact = insertArtifact(version, "IOS", versionCounter, "READY");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
        body.put("versionId", version.toString());
        body.put("ring", ring);
        if (environmentId != null) {
            body.put("environmentId", environmentId.toString());
        }
        body.put("artifacts", List.of(Map.of("platform", "IOS", "artifactId", artifact.toString())));
        body.put("targets", List.of(Map.of("tenantId", targetTenant.toString(), "testerGroupIds", List.of())));
        JsonNode created = post(managerToken, "/api/v1/releases", body, 201);
        UUID releaseId = UUID.fromString(created.get("id").asText());
        Release release = new Release(releaseId, created.get("releaseRef").asText());
        for (String step : List.of("VALIDATING", "READY", "APPROVAL_PENDING")) {
            Map<String, Object> transition = new LinkedHashMap<>();
            transition.put("applicationId", applicationId.toString());
            transition.put("to", step);
            exchange("/api/v1/releases/" + releaseId + "/transitions", managerToken, "POST", transition, null)
                    .expect(200);
        }
        return release;
    }

    /**
     * A fresh release walked only to READY — the moment BEFORE the
     * crossing whose transactional containment the rollback probe
     * exercises (the same fixture walk, one step short).
     */
    private Release releaseAtReady(World world) {
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
        JsonNode created = post(world.managerToken, "/api/v1/releases", body, 201);
        UUID releaseId = UUID.fromString(created.get("id").asText());
        Release release = new Release(releaseId, created.get("releaseRef").asText());
        for (String step : List.of("VALIDATING", "READY")) {
            Map<String, Object> transition = new LinkedHashMap<>();
            transition.put("applicationId", world.app.toString());
            transition.put("to", step);
            exchange("/api/v1/releases/" + releaseId + "/transitions", world.managerToken, "POST", transition, null)
                    .expect(200);
        }
        return release;
    }

    private JsonNode approvalsOf(World world, Release release) {
        return get(
                world.managerToken, "/api/v1/releases/" + release.id() + "/approvals?applicationId=" + world.app, 200);
    }

    private String releasePath(World world, Release release) {
        return "/api/v1/releases/" + release.id() + "?applicationId=" + world.app;
    }

    private JsonNode createWorkflow(
            String token,
            UUID organizationId,
            String name,
            String ring,
            List<Map<String, Object>> steps,
            int expected) {
        return exchange(
                        "/api/v1/approval-workflows",
                        token,
                        "POST",
                        definitionBody(organizationId, name, ring, steps),
                        null)
                .expect(expected);
    }

    private static Map<String, Object> definitionBody(
            UUID organizationId, String name, String ring, List<Map<String, Object>> steps) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", organizationId.toString());
        body.put("name", name);
        if (ring != null) {
            body.put("ring", ring);
        }
        body.put("steps", steps);
        return body;
    }

    /** The chain vocabulary without tenant scopes, or with one shared scope. */
    private static List<Map<String, Object>> chain(List<String> roles, UUID tenantId) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (String role : roles) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("role", role);
            if (tenantId != null) {
                step.put("tenantId", tenantId.toString());
            }
            steps.add(step);
        }
        return steps;
    }

    private static Map<String, Object> decisionBody(String decision, Integer step, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        if (step != null) {
            body.put("step", step);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        return body;
    }

    private static Map<String, Object> decisionBody(UUID applicationId, String decision, Integer step, String reason) {
        Map<String, Object> body = decisionBody(decision, step, reason);
        body.put("applicationId", applicationId.toString());
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

    /** The transition route against an explicit application (the shared-app probes). */
    private JsonNode transitionOf(String token, UUID applicationId, Release release, String to, int expected) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", applicationId.toString());
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

    private static List<Integer> positionsOf(JsonNode workflow) {
        List<Integer> positions = new ArrayList<>();
        workflow.get("steps").forEach(step -> positions.add(step.get("position").asInt()));
        return positions;
    }

    private static List<String> rolesOf(JsonNode workflow) {
        List<String> roles = new ArrayList<>();
        workflow.get("steps").forEach(step -> roles.add(step.get("requiredRole").asText()));
        return roles;
    }

    /** The problem envelope minus the per-request trace id (the concealment-parity comparison). */
    private static Map<String, Object> problemOf(JsonNode problem) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("type", problem.get("type").asText());
        shape.put("title", problem.get("title").asText());
        shape.put("status", problem.get("status").asInt());
        shape.put("code", problem.get("code").asText());
        shape.put("detail", problem.get("detail").asText());
        return shape;
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
                        "Wf " + role))
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
