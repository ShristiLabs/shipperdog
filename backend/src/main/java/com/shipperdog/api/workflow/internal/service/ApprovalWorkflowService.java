package com.shipperdog.api.workflow.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.authorization.ApplicationPermissionCheck;
import com.shipperdog.api.authorization.Permission;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.environment.EnvironmentAccess;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseApprovalSource;
import com.shipperdog.api.release.ReleaseException;
import com.shipperdog.api.release.ReleaseTransitionedEvent;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.workflow.ApprovalRequestedEvent;
import com.shipperdog.api.workflow.WorkflowException;
import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowEntity;
import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepEntity;
import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepEntity.Role;
import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalEntity;
import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalEntity.ApprovalType;
import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalRoundEntity;
import com.shipperdog.api.workflow.internal.repository.ApprovalWorkflowRepository;
import com.shipperdog.api.workflow.internal.repository.ApprovalWorkflowStepRepository;
import com.shipperdog.api.workflow.internal.repository.ReleaseApprovalRepository;
import com.shipperdog.api.workflow.internal.repository.ReleaseApprovalRoundRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approval engine's core (issue #40, S10-01, FR-016, PRD 8.10/13/
 * 17/33): the workflow-as-data model the issue's technical note
 * demands — definitions an organization configures, rounds a release
 * runs, signatures the steps collect — and the sequential evaluation
 * that gates the APPROVAL_PENDING to APPROVED crossing through the
 * S9-02 policy seam ({@code ReleaseApprovalPolicy}, this package).
 *
 * <p><strong>Definitions</strong> are organization-grain reference
 * data (the environments standing): a binding name (the vocabulary
 * V27's {@code environments.approval_policy} references), an optional
 * ring attachment (releases STARTING at that ring resolve the chain),
 * and the ordered steps — each a required role from the five-role
 * vocabulary the RBAC matrix grants an approve permission, optionally
 * narrowed to a tenant the release must target. Attachments resolve
 * MOST SPECIFIC FIRST at the APPROVAL_PENDING crossing: the targeted
 * environment's named policy, else the starting ring's chain; an
 * organization that configures neither keeps the ungated machine
 * S9-02 shipped (approvals are CONFIGURABLE, FR-016 — no definition,
 * no round, no gate). Release-template attachment is S10-04's wiring
 * point; this story ships the two attachments the platform models.
 *
 * <p><strong>Rounds</strong> instantiate inside the crossing's
 * transaction (the {@code ReleaseTransitionedEvent} hook — the
 * manifest-freeze listener's pattern): round 1 at the crossing, round
 * N+1 only through the rework restart after a rejection (the issue's
 * documented path — PRD 17 has no back-edge, so the rework is a NEW
 * round against the SAME frozen definition, never a rewrite of the
 * rejected one). The round carries the definition's id: later
 * definition changes never rewrite a round already run.
 *
 * <p><strong>Decisions</strong> are signatures (the issue's technical
 * note): only the step's role-holder may decide, never the release's
 * creator (self-approval blocked), sequentially — the chain's current
 * step only, out-of-order attempts answered with the precise 409 —
 * and exactly once per step (the repository's compare-and-set). A
 * rejection terminates its round and blocks progression (the release
 * stays APPROVAL_PENDING, the transition policy refusing); the last
 * approval satisfies the round and permits the crossing, at which the
 * manifest freezes the satisfied chain (the
 * {@link ReleaseApprovalSource} this service implements).
 *
 * <p>INV-008 discipline: nothing here touches artifacts — approvals
 * read the release's pinned set, never mutate it; the ordering
 * question the issue flags (manifest freeze at APPROVED while
 * approvals run in APPROVAL_PENDING) is the designed one: approvals
 * run against the artifact set INV-004 froze at CREATION, and the
 * manifest — including its approval block — is the document OF the
 * APPROVED crossing, so the chain the policy verified and the chain
 * the manifest froze are the same facts by construction.
 */
@Service
public class ApprovalWorkflowService implements ReleaseApprovalSource {

    /** The binding-name vocabulary (the environments shape): lowercase kebab. */
    private static final Pattern NAME_SHAPE = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    /** A sequential chain longer than this is a modeling error, not a process (the environments caps' spirit). */
    private static final int MAX_STEPS = 10;

    /** The reason cap (V28's database width, the V25 cap). */
    private static final int MAX_REASON_LENGTH = 1000;

    /** The listing's order: name ascending (the binding vocabulary, deterministic). */
    private static final Sort NAME_ORDER = Sort.by("name").ascending();

    /**
     * The step-role to approve-permission map (AC2's authority): each
     * of the five roles the catalog grants exactly one
     * {@code app:releases:approve-*} permission, so the step's role
     * and the permission check are one question answered one way.
     */
    /**
     * The approval-type to approve-permission map (AC2's authority):
     * each of the five chain types the catalog grants exactly one
     * {@code app:releases:approve-*} permission (the type's 1:1 role
     * holds it), so the step's type and the permission check are one
     * question answered one way.
     */
    private static final Map<ApprovalType, Permission> APPROVE_PERMISSION = Map.of(
            ApprovalType.QA, Permission.APP_RELEASES_APPROVE_QA,
            ApprovalType.SECURITY, Permission.APP_RELEASES_APPROVE_SECURITY,
            ApprovalType.COMPLIANCE, Permission.APP_RELEASES_APPROVE_COMPLIANCE,
            ApprovalType.PRODUCT, Permission.APP_RELEASES_APPROVE_PRODUCT,
            ApprovalType.RELEASE_MANAGER, Permission.APP_RELEASES_APPROVE_RELEASE);

    /**
     * The type's signing role (the 1:1 map's inverse): the role
     * recorded on the signature — the role the approver held and
     * signed under at decision time.
     */
    private static final Map<ApprovalType, Role> SIGNING_ROLE = Map.of(
            ApprovalType.QA, Role.QA_ENGINEER,
            ApprovalType.SECURITY, Role.SECURITY_REVIEWER,
            ApprovalType.COMPLIANCE, Role.COMPLIANCE_REVIEWER,
            ApprovalType.PRODUCT, Role.APP_OWNER,
            ApprovalType.RELEASE_MANAGER, Role.RELEASE_MANAGER);

    private final ApprovalWorkflowRepository workflows;
    private final ApprovalWorkflowStepRepository workflowSteps;
    private final ReleaseApprovalRoundRepository rounds;
    private final ReleaseApprovalRepository approvals;
    private final OrganizationAccess organizations;
    private final TenantAccess tenants;
    private final EnvironmentAccess environments;
    private final ApplicationAccess applications;
    private final ReleaseAccess releases;
    private final ApplicationPermissionCheck permissionCheck;
    private final TenantScopeExecutor scopeExecutor;
    private final AuditEventPublisher audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TraceAttributes trace;

    ApprovalWorkflowService(
            ApprovalWorkflowRepository workflows,
            ApprovalWorkflowStepRepository workflowSteps,
            ReleaseApprovalRoundRepository rounds,
            ReleaseApprovalRepository approvals,
            OrganizationAccess organizations,
            TenantAccess tenants,
            EnvironmentAccess environments,
            ApplicationAccess applications,
            ReleaseAccess releases,
            ApplicationPermissionCheck permissionCheck,
            TenantScopeExecutor scopeExecutor,
            AuditEventPublisher audit,
            ApplicationEventPublisher events,
            Clock clock,
            TraceAttributes trace) {
        this.workflows = workflows;
        this.workflowSteps = workflowSteps;
        this.rounds = rounds;
        this.approvals = approvals;
        this.organizations = organizations;
        this.tenants = tenants;
        this.environments = environments;
        this.applications = applications;
        this.releases = releases;
        this.permissionCheck = permissionCheck;
        this.scopeExecutor = scopeExecutor;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // Definitions (AC1): create, list, read
    // -------------------------------------------------------------------------

    /**
     * Creates an approval workflow definition (AC1): the binding name,
     * the optional ring attachment (resolved in the organization's
     * ring vocabulary — the creation surface's own rule, so a
     * definition and a release can never disagree on which ring they
     * mean), and the ordered steps (1..n from list order; each role
     * once — a sequential chain never asks the same role twice; an
     * optional tenant scope must belong to the organization). One name
     * and one ring attachment per organization (V28's guards; the
     * lost-race translation answers the same 409). Audited as
     * {@code APPROVAL_WORKFLOW_CREATED} with the whole chain.
     * Honors {@code Idempotency-Key}.
     */
    @Transactional
    public WorkflowView create(UUID actorId, CreateCommand command) {
        if (command.organizationId() == null) {
            throw invalid(Map.of("organizationId", "organizationId is required"));
        }
        if (!organizations.organizationExists(command.organizationId())) {
            throw new WorkflowException(WorkflowException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
        String name = validatedName(command.name());
        UUID ringId = null;
        if (command.ring() != null && !command.ring().isBlank()) {
            String ringCode = command.ring().trim().toLowerCase(Locale.ROOT);
            ringId = releases.ringIdOf(command.organizationId(), ringCode)
                    .orElseThrow(() -> new WorkflowException(
                            WorkflowException.Code.WORKFLOW_RING_UNKNOWN,
                            "ring is not defined for the organization",
                            Map.of("ring", "unknown ring code '" + ringCode + "'")));
        }
        List<StepCommand> steps = validatedSteps(command.organizationId(), command.steps());
        if (workflows
                        .findByOrganizationIdAndName(command.organizationId(), name)
                        .isPresent()
                || (ringId != null
                        && workflows
                                .findByOrganizationIdAndRingId(command.organizationId(), ringId)
                                .isPresent())) {
            throw workflowExists();
        }

        ApprovalWorkflowEntity workflow = new ApprovalWorkflowEntity(command.organizationId(), name, ringId, actorId);
        try {
            workflow = workflows.saveAndFlush(workflow);
            int position = 1;
            for (StepCommand step : steps) {
                workflowSteps.save(new ApprovalWorkflowStepEntity(
                        workflow.getId(),
                        position,
                        Role.valueOf(step.role().trim().toUpperCase(Locale.ROOT)),
                        step.tenantId()));
                position++;
            }
            workflowSteps.flush();
        } catch (DataIntegrityViolationException e) {
            // Only the duplicate authorities translate (the release
            // module's discipline): anything else is a bug to surface.
            if (e.getMessage() != null
                    && (e.getMessage().contains("uq_approval_workflows_org_name")
                            || e.getMessage().contains("uq_approval_workflows_org_ring"))) {
                throw workflowExists();
            }
            if (e.getMessage() != null && e.getMessage().contains("uq_approval_workflow_steps_role")) {
                throw invalid(Map.of("steps", "each approval role may appear at most once in the chain"));
            }
            throw e;
        }

        trace.organization(command.organizationId());
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("name", name);
        detail.put("ring", ringId == null ? "-" : command.ring().trim().toLowerCase(Locale.ROOT));
        detail.put("steps", chainSummaryOf(steps));
        audit.publish(AuditEvent.builder(AuditEventType.APPROVAL_WORKFLOW_CREATED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(command.organizationId())
                .success(true)
                .detail(detail)
                .build());
        return viewOf(workflow, chainOf(workflow.getId()));
    }

    /** The definition listing (AC1): the organization's chains, name order, standard limit/cursor pagination. */
    @Transactional(readOnly = true)
    public Page<WorkflowView> list(UUID actorId, UUID organizationId, long offset, int limit) {
        mustExistOrganization(organizationId);
        Page<ApprovalWorkflowEntity> page =
                workflows.findByOrganizationId(organizationId, new CursorPageable(offset, limit, NAME_ORDER));
        return page.map(workflow -> viewOf(workflow, chainOf(workflow.getId())));
    }

    /**
     * The definition detail (AC1): the chain read back. The
     * {@code organizationId} query parameter carries the permission
     * target (the release detail's body-addressed shape); a workflow
     * of another organization is the standard concealed miss.
     */
    @Transactional(readOnly = true)
    public WorkflowView get(UUID actorId, UUID organizationId, UUID workflowId) {
        mustExistOrganization(organizationId);
        ApprovalWorkflowEntity workflow = workflows
                .findByIdAndOrganizationId(workflowId, organizationId)
                .orElseThrow(
                        () -> new WorkflowException(WorkflowException.Code.NOT_FOUND, "approval workflow not found"));
        return viewOf(workflow, chainOf(workflow.getId()));
    }

    // -------------------------------------------------------------------------
    // Round 1: the crossing listener's instantiation
    // -------------------------------------------------------------------------

    /**
     * Instantiates round 1 inside the READY to APPROVAL_PENDING
     * crossing's transaction (the {@code ApprovalRoundListener} hook):
     * resolves the organization's definition — the targeted
     * environment's named policy first, else the starting ring's
     * chain — and, when one resolves, writes the round and its
     * per-step records, audits the round, and publishes one
     * {@link ApprovalRequestedEvent} per step (S12-01's feed). No
     * resolved definition writes nothing: approvals are configurable
     * (FR-016), and an organization that configured none keeps the
     * ungated crossing S9-02 shipped — the recorded decision, not a
     * gap. An instantiation failure rolls the whole crossing back
     * (the freeze listener's contract): no APPROVAL_PENDING release
     * ever exists without the approval records its definition
     * demanded.
     *
     * <p>Tenant-scoped steps test applicability against the release's
     * TARGET set (frozen at creation): a step whose tenant is not
     * targeted is not instantiated — skipped, never a failure. A
     * definition whose every step is scoped away instantiates a
     * SATISFIED empty round (nothing was required) — the honest
     * record of the resolution, and the crossing's key by the same
     * act.
     */
    @Transactional
    public void instantiateFromCrossing(ReleaseTransitionedEvent event) {
        if (!"APPROVAL_PENDING".equals(event.toStatus())) {
            return;
        }
        ReleaseAccess.ReleaseFacts facts = releases.factsOf(event.releaseId())
                .orElseThrow(() -> new IllegalStateException(
                        "release " + event.releaseId() + " missing at its own APPROVAL_PENDING crossing"));
        Optional<ApprovalWorkflowEntity> resolved = resolveDefinitionFor(facts);
        if (resolved.isEmpty()) {
            return;
        }
        ApprovalWorkflowEntity workflow = resolved.get();
        if (rounds.findFirstByReleaseIdOrderByRoundNumberDesc(event.releaseId()).isPresent()) {
            // PRD 17 is forward-only: no release re-enters
            // APPROVAL_PENDING, so round 1 twice is a machine bug —
            // answer loudly, never a silent double-round.
            throw new IllegalStateException(
                    "release " + event.releaseRef() + " already carries an approval round at its crossing");
        }
        instantiateRound(
                facts, workflow, 1, event.actorId(), "CROSSING", null, event.organizationId(), event.tenantId());
    }

    // -------------------------------------------------------------------------
    // Decisions (AC2, AC5, AC6): approve / reject
    // -------------------------------------------------------------------------

    /**
     * One approval decision (AC2/AC5/AC6): the body names APPROVE or
     * REJECT and may assert the step it believes it is deciding (an
     * out-of-order or already-decided assertion is the precise 409);
     * the decision always acts on the chain's CURRENT step — the
     * first PENDING one — because a sequential chain is decided in
     * order or not at all. The authority rules run before any write:
     * the release's creator may never decide their own release's
     * chain (AC5), and only a holder of the step's role may decide it
     * (AC2 — the mapped {@code app:releases:approve-*} permission,
     * the programmatic twin of the route's coarse gate). A rejection
     * requires its reason (AC6) and terminates the round — the
     * release stays APPROVAL_PENDING with the rejection recorded, and
     * the crossing stays refused until the rework restart satisfies a
     * new round. The last approval satisfies the round: the crossing
     * becomes fireable through the transition route (the policy
     * permits), and the manifest freezes the satisfied chain. Every
     * decision is audited with its reason. Not idempotency-keyed: the
     * decided step is the replay answer.
     */
    @Transactional
    public ApprovalStateView decide(
            UUID actorId, UUID applicationId, UUID releaseId, String decision, Integer step, String reason) {
        boolean approve;
        if (decision == null || decision.isBlank()) {
            throw invalid(Map.of("decision", "decision is required and must be APPROVE or REJECT"));
        }
        switch (decision.trim().toUpperCase(Locale.ROOT)) {
            case "APPROVE" -> approve = true;
            case "REJECT" -> approve = false;
            default ->
                throw invalid(Map.of("decision", "unknown decision '" + decision.trim() + "' (APPROVE or REJECT)"));
        }
        String validatedReason = validatedReason(approve, reason);
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseAccess.ReleaseFacts facts = factsOf(applicationId, releaseId);
            ReleaseApprovalRoundEntity round = liveRoundOf(facts);
            List<ReleaseApprovalEntity> chain = approvals.findByRoundIdOrderByPositionAsc(round.getId());
            ReleaseApprovalEntity current = currentStepOf(chain).orElse(null);
            if (current == null) {
                throw notActive(facts, round.getStatus().name());
            }
            if (step != null) {
                ReleaseApprovalEntity addressed = chain.stream()
                        .filter(candidate -> candidate.getPosition() == step)
                        .findFirst()
                        .orElseThrow(() -> invalid(Map.of(
                                "step", "unknown step position " + step + " in round " + round.getRoundNumber())));
                if (!addressed.getId().equals(current.getId())) {
                    Map<String, String> errors = new LinkedHashMap<>();
                    errors.put("current", String.valueOf(current.getPosition()));
                    errors.put("attempted", String.valueOf(step));
                    errors.put("roundStatus", round.getStatus().name());
                    throw new WorkflowException(
                            WorkflowException.Code.APPROVAL_STEP_OUT_OF_ORDER,
                            "the decision does not address the chain's current step",
                            errors);
                }
            }
            // The delegation rule (AC5): the creator signs nothing on
            // their own release, whatever roles they hold.
            if (actorId.equals(facts.createdBy())) {
                throw new WorkflowException(
                        WorkflowException.Code.APPROVAL_SELF_APPROVAL,
                        "the release creator may not approve their own release",
                        Map.of("createdBy", facts.createdBy().toString()));
            }
            // The step's role (AC2): the type's mapped approve
            // permission, resolved against the release's application
            // exactly as the route's coarse gate resolves the five-way
            // disjunction. The role the caller must hold is the type's
            // 1:1 signing role, named in the refusal so the right
            // approver is findable.
            Permission required = APPROVE_PERMISSION.get(current.getApprovalType());
            Role signingRole = SIGNING_ROLE.get(current.getApprovalType());
            if (!permissionCheck.permits(actorId, applicationId, required)) {
                throw new WorkflowException(
                        WorkflowException.Code.APPROVAL_ROLE_NOT_HELD,
                        "only a holder of the step's role may decide it",
                        Map.of(
                                "requiredRole", signingRole.name(),
                                "approvalType", current.getApprovalType().name(),
                                "permission", required.code()));
            }

            Instant decidedAt = Instant.now(clock);
            String target = approve ? "APPROVED" : "REJECTED";
            if (approvals.decideIfCurrent(
                            current.getId(), target, actorId, signingRole.name(), validatedReason, decidedAt)
                    != 1) {
                // A concurrent decision moved the step first: answer
                // from the committed state, never a double-signature.
                ReleaseApprovalEntity committed =
                        approvals.findById(current.getId()).orElse(current);
                throw new WorkflowException(
                        WorkflowException.Code.APPROVAL_STEP_OUT_OF_ORDER,
                        "the step was decided concurrently",
                        Map.of(
                                "current", String.valueOf(committed.getPosition()),
                                "attempted", String.valueOf(current.getPosition()),
                                "status", committed.getStatus().name()));
            }
            trace.application(applicationId);
            trace.release(releaseId);
            auditDecided(facts, round, current, actorId, approve, validatedReason, decidedAt);
            if (!approve) {
                if (rounds.transitionStatusIfCurrent(round.getId(), "PENDING", "REJECTED") != 1) {
                    throw notActive(facts, "REJECTED");
                }
            } else {
                List<ReleaseApprovalEntity> refreshed = approvals.findByRoundIdOrderByPositionAsc(round.getId());
                if (currentStepOf(refreshed).isEmpty()
                        && rounds.transitionStatusIfCurrent(round.getId(), "PENDING", "SATISFIED") != 1) {
                    throw notActive(facts, "SATISFIED");
                }
            }
            return stateOf(facts);
        });
    }

    // -------------------------------------------------------------------------
    // The rework path (AC3): restart a rejected round
    // -------------------------------------------------------------------------

    /**
     * The rework restart (AC3's documented path): after a rejection
     * blocks progression, a new round of the SAME frozen definition
     * (the rejected round's workflow — definition changes never fork
     * an in-flight release's semantics) re-opens the chain: round N+1
     * PENDING, one request event per step, audited with the optional
     * restart note. Only a REJECTED round is restartable — a PENDING
     * one is still running and a SATISFIED one already did its work —
     * and only while the release sits in APPROVAL_PENDING (PRD 17 has
     * no back-edge; the rework is a new round, never an un-reject).
     * Requires {@code app:releases:manage}: restarting an approval
     * chain is release management, not an approval signature.
     */
    @Transactional
    public ApprovalStateView restart(UUID actorId, UUID applicationId, UUID releaseId, String reason) {
        String validatedReason = optionalReason(reason);
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseAccess.ReleaseFacts facts = factsOf(applicationId, releaseId);
            ReleaseApprovalRoundEntity latest =
                    rounds.findFirstByReleaseIdOrderByRoundNumberDesc(releaseId).orElse(null);
            if (latest == null || latest.getStatus() != ReleaseApprovalRoundEntity.Status.REJECTED) {
                throw new WorkflowException(
                        WorkflowException.Code.APPROVAL_ROUND_NOT_RESTARTABLE,
                        "only a rejected approval round can be restarted",
                        Map.of(
                                "current",
                                latest == null ? "NONE" : latest.getStatus().name()));
            }
            if (!"APPROVAL_PENDING".equals(facts.status())) {
                throw notActive(facts, facts.status());
            }
            ApprovalWorkflowEntity workflow = workflows
                    .findById(latest.getWorkflowId())
                    .orElseThrow(() -> new IllegalStateException("workflow " + latest.getWorkflowId()
                            + " missing for a restart of release " + facts.releaseRef()));
            ReleaseApprovalRoundEntity round = instantiateRound(
                    facts, workflow, latest.getRoundNumber() + 1, actorId, "RESTART", validatedReason, null, null);
            return stateOf(facts);
        });
    }

    // -------------------------------------------------------------------------
    // The approval read (the rounds, the steps, the signatures)
    // -------------------------------------------------------------------------

    /**
     * The release's approval state: every round with its steps and
     * signatures — the rejected rounds and their reasons beside the
     * live one, the full audit-read of the approval moment. The
     * {@code applicationId} query parameter carries the permission
     * target; a release of another application is the standard
     * concealed miss.
     */
    @Transactional(readOnly = true)
    public ApprovalStateView state(UUID actorId, UUID applicationId, UUID releaseId) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseAccess.ReleaseFacts facts = factsOf(applicationId, releaseId);
            return stateOf(facts);
        });
    }

    // -------------------------------------------------------------------------
    // ReleaseApprovalSource: the manifest's approval feed
    // -------------------------------------------------------------------------

    /**
     * The chain that authorized the release's APPROVED crossing (PRD
     * 16's {@code approval.required}): the latest round's approval
     * types, in chain order, when that round is SATISFIED — the facts
     * the transition policy verified when it permitted the crossing,
     * frozen verbatim into the manifest at the freeze that same
     * crossing triggers. Empty when no round exists or the latest is
     * not SATISFIED (a rejected or pending chain authorizes nothing —
     * the honest empty, the manifest's discipline for absent blocks).
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> approvalTypesOf(UUID releaseId) {
        return rounds.findFirstByReleaseIdOrderByRoundNumberDesc(releaseId)
                .filter(round -> round.getStatus() == ReleaseApprovalRoundEntity.Status.SATISFIED)
                .map(round -> approvals.findByRoundIdOrderByPositionAsc(round.getId()).stream()
                        .map(approval -> approval.getApprovalType().name())
                        .toList())
                .orElseGet(List::of);
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /** One step of a creation request: the required role (the request's string) and the optional tenant scope. */
    public record StepCommand(String role, UUID tenantId) {}

    /** The creation command (the controller's request, already the module's own shape). */
    public record CreateCommand(UUID organizationId, String name, String ring, List<StepCommand> steps) {}

    /** One step of a definition, read back. */
    public record WorkflowStepView(int position, String requiredRole, UUID tenantId) {}

    /** The definition view: identity, binding name, ring attachment, the ordered chain. */
    public record WorkflowView(
            UUID id,
            UUID organizationId,
            String name,
            String ring,
            List<WorkflowStepView> steps,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {}

    /** One step instance of a round: the signature when decided, the demand while pending. */
    public record ApprovalStepView(
            int position,
            String approvalType,
            UUID tenantId,
            String status,
            UUID approverId,
            String approverRole,
            String reason,
            Instant decidedAt) {}

    /** One round: the number, the status, the definition it ran, the steps with their signatures. */
    public record ApprovalRoundView(
            int roundNumber,
            String status,
            UUID workflowId,
            String workflowName,
            UUID createdBy,
            Instant createdAt,
            List<ApprovalStepView> steps) {}

    /**
     * The approval state view: the release's summary beside every
     * round — the live round (or the satisfied one) first-classed as
     * {@code currentRoundNumber}/{@code currentRoundStatus}, and the
     * full history with every rejection's reason (the rework story a
     * Compliance Reviewer reads).
     */
    public record ApprovalStateView(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            String releaseStatus,
            int currentRoundNumber,
            String currentRoundStatus,
            List<ApprovalRoundView> rounds) {}

    // -------------------------------------------------------------------------
    // Helpers: resolution, validation, scope, audit
    // -------------------------------------------------------------------------

    /**
     * The definition a release resolves (the attachment precedence):
     * the targeted environment's NAMED policy first (V27's binding —
     * the most specific deployment context), else the starting ring's
     * chain. An environment binding that names no workflow of the
     * organization falls through to the ring (the organization bound a
     * policy it has not defined; its ring chain is the safer answer,
     * and both legs are explicit configuration).
     */
    private Optional<ApprovalWorkflowEntity> resolveDefinitionFor(ReleaseAccess.ReleaseFacts facts) {
        if (facts.environmentId() != null) {
            Optional<String> policyName = environments.approvalPolicyNameOf(facts.environmentId());
            if (policyName.isPresent()) {
                Optional<ApprovalWorkflowEntity> named =
                        workflows.findByOrganizationIdAndName(facts.organizationId(), policyName.get());
                if (named.isPresent()) {
                    return named;
                }
            }
        }
        if (facts.ringId() != null) {
            return workflows.findByOrganizationIdAndRingId(facts.organizationId(), facts.ringId());
        }
        return Optional.empty();
    }

    /**
     * Writes one round and its per-step records, audits the start,
     * and publishes the request events — the shared body of the
     * crossing instantiation and the rework restart.
     */
    private ReleaseApprovalRoundEntity instantiateRound(
            ReleaseAccess.ReleaseFacts facts,
            ApprovalWorkflowEntity workflow,
            int roundNumber,
            UUID actorId,
            String trigger,
            String note,
            UUID crossingOrganizationId,
            UUID crossingTenantId) {
        List<ApprovalWorkflowStepEntity> definition =
                workflowSteps.findByWorkflowIdOrderByPositionAsc(workflow.getId());
        Set<UUID> targetTenants = releases.targetTenantIdsOf(facts.releaseId());
        List<ApprovalWorkflowStepEntity> applicable = definition.stream()
                .filter(step -> step.getTenantId() == null || targetTenants.contains(step.getTenantId()))
                .toList();
        ReleaseApprovalRoundEntity round =
                new ReleaseApprovalRoundEntity(facts.releaseId(), roundNumber, workflow.getId(), actorId);
        if (applicable.isEmpty()) {
            // Every step scoped away: nothing was required — the round
            // records the resolution and stands satisfied (the
            // crossing's key by the same act).
            round.setStatus(ReleaseApprovalRoundEntity.Status.SATISFIED);
        }
        round = rounds.saveAndFlush(round);
        Instant requestedAt = Instant.now(clock);
        List<ReleaseApprovalEntity> requested = new ArrayList<>(applicable.size());
        for (ApprovalWorkflowStepEntity step : applicable) {
            requested.add(approvals.save(new ReleaseApprovalEntity(
                    facts.releaseId(),
                    round.getId(),
                    step.getPosition(),
                    ApprovalType.of(step.getRequiredRole()),
                    step.getTenantId())));
        }
        approvals.flush();

        trace.release(facts.releaseId());
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("releaseRef", facts.releaseRef());
        detail.put("round", String.valueOf(roundNumber));
        detail.put("workflow", workflow.getName());
        detail.put("trigger", trigger);
        detail.put(
                "steps",
                requested.isEmpty()
                        ? "-"
                        : requested.stream()
                                .map(step -> step.getPosition() + ":"
                                        + step.getApprovalType().name()
                                        + (step.getTenantId() == null ? "" : "@" + step.getTenantId()))
                                .toList()
                                .toString());
        int skipped = definition.size() - applicable.size();
        if (skipped > 0) {
            detail.put("skippedTenantScopedSteps", String.valueOf(skipped));
        }
        if (note != null) {
            detail.put("note", note);
        }
        audit.publish(AuditEvent.builder(AuditEventType.RELEASE_APPROVAL_ROUND_STARTED)
                .occurredAt(requestedAt)
                .userId(actorId)
                .orgId(crossingOrganizationId == null ? facts.organizationId() : crossingOrganizationId)
                .tenantId(crossingTenantId == null ? facts.tenantId() : crossingTenantId)
                .applicationId(facts.applicationId())
                .success(true)
                .detail(detail)
                .build());
        for (ReleaseApprovalEntity step : requested) {
            events.publishEvent(new ApprovalRequestedEvent(
                    facts.releaseId(),
                    facts.releaseRef(),
                    facts.applicationId(),
                    facts.organizationId(),
                    facts.tenantId(),
                    roundNumber,
                    step.getPosition(),
                    step.getApprovalType().name(),
                    actorId,
                    requestedAt));
        }
        return round;
    }

    /** The chain's current step: the first PENDING one, in chain order. */
    private static Optional<ReleaseApprovalEntity> currentStepOf(List<ReleaseApprovalEntity> chain) {
        return chain.stream()
                .filter(step -> step.getStatus() == ReleaseApprovalEntity.Status.PENDING)
                .findFirst();
    }

    /** The release the approval surface acts on, resolved through the seam (the concealed miss). */
    private ReleaseAccess.ReleaseFacts factsOf(UUID applicationId, UUID releaseId) {
        ReleaseAccess.ReleaseFacts facts = releases.factsOf(releaseId).orElse(null);
        if (facts == null || !facts.applicationId().equals(applicationId)) {
            throw new WorkflowException(WorkflowException.Code.RELEASE_NOT_FOUND, "release not found");
        }
        return facts;
    }

    /** The round decisions act on: the latest, PENDING, of a release sitting in APPROVAL_PENDING. */
    private ReleaseApprovalRoundEntity liveRoundOf(ReleaseAccess.ReleaseFacts facts) {
        if (!"APPROVAL_PENDING".equals(facts.status())) {
            throw notActive(facts, facts.status());
        }
        ReleaseApprovalRoundEntity round = rounds.findFirstByReleaseIdOrderByRoundNumberDesc(facts.releaseId())
                .orElse(null);
        if (round == null) {
            throw notActive(facts, "NO_ROUND");
        }
        if (round.getStatus() != ReleaseApprovalRoundEntity.Status.PENDING) {
            throw notActive(facts, round.getStatus().name());
        }
        return round;
    }

    private static WorkflowException notActive(ReleaseAccess.ReleaseFacts facts, String current) {
        return new WorkflowException(
                WorkflowException.Code.APPROVAL_NOT_ACTIVE,
                "the release is not awaiting approval decisions",
                Map.of("releaseStatus", facts.status(), "current", current));
    }

    private void auditDecided(
            ReleaseAccess.ReleaseFacts facts,
            ReleaseApprovalRoundEntity round,
            ReleaseApprovalEntity step,
            UUID actorId,
            boolean approve,
            String reason,
            Instant decidedAt) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("releaseRef", facts.releaseRef());
        detail.put("round", String.valueOf(round.getRoundNumber()));
        detail.put("position", String.valueOf(step.getPosition()));
        detail.put("approvalType", step.getApprovalType().name());
        detail.put("approverRole", SIGNING_ROLE.get(step.getApprovalType()).name());
        if (reason != null) {
            detail.put("reason", reason);
        }
        audit.publish(AuditEvent.builder(
                        approve ? AuditEventType.RELEASE_APPROVAL_GRANTED : AuditEventType.RELEASE_APPROVAL_REJECTED)
                .occurredAt(decidedAt)
                .userId(actorId)
                .orgId(facts.organizationId())
                .tenantId(facts.tenantId())
                .applicationId(facts.applicationId())
                .success(true)
                .detail(detail)
                .build());
    }

    /** The full state view from the release facts (the latest round first-classed). */
    private ApprovalStateView stateOf(ReleaseAccess.ReleaseFacts facts) {
        List<ReleaseApprovalRoundEntity> roundRows = rounds.findByReleaseIdOrderByRoundNumberAsc(facts.releaseId());
        Map<UUID, String> workflowNames = new LinkedHashMap<>();
        for (ApprovalWorkflowEntity workflow : workflows.findAllById(roundRows.stream()
                .map(ReleaseApprovalRoundEntity::getWorkflowId)
                .distinct()
                .toList())) {
            workflowNames.put(workflow.getId(), workflow.getName());
        }
        List<ApprovalRoundView> roundViews = new ArrayList<>(roundRows.size());
        for (ReleaseApprovalRoundEntity round : roundRows) {
            List<ApprovalStepView> stepViews = approvals.findByRoundIdOrderByPositionAsc(round.getId()).stream()
                    .map(step -> new ApprovalStepView(
                            step.getPosition(),
                            step.getApprovalType().name(),
                            step.getTenantId(),
                            step.getStatus().name(),
                            step.getApproverId(),
                            step.getApproverRole() == null
                                    ? null
                                    : step.getApproverRole().name(),
                            step.getReason(),
                            step.getDecidedAt()))
                    .toList();
            roundViews.add(new ApprovalRoundView(
                    round.getRoundNumber(),
                    round.getStatus().name(),
                    round.getWorkflowId(),
                    workflowNames.getOrDefault(round.getWorkflowId(), null),
                    round.getCreatedBy(),
                    round.getCreatedAt(),
                    stepViews));
        }
        ReleaseApprovalRoundEntity latest = roundRows.isEmpty() ? null : roundRows.get(roundRows.size() - 1);
        return new ApprovalStateView(
                facts.releaseId(),
                facts.releaseRef(),
                facts.applicationId(),
                facts.status(),
                latest == null ? 0 : latest.getRoundNumber(),
                latest == null ? null : latest.getStatus().name(),
                List.copyOf(roundViews));
    }

    /** The definition's stored chain, in order (the read-back view's feed). */
    private List<ApprovalWorkflowStepEntity> chainOf(UUID workflowId) {
        return workflowSteps.findByWorkflowIdOrderByPositionAsc(workflowId);
    }

    private WorkflowView viewOf(ApprovalWorkflowEntity workflow, List<ApprovalWorkflowStepEntity> steps) {
        String ring = workflow.getRingId() == null
                ? null
                : releases.ringCodeOf(workflow.getRingId()).orElse(null);
        return new WorkflowView(
                workflow.getId(),
                workflow.getOrganizationId(),
                workflow.getName(),
                ring,
                steps.stream()
                        .map(step -> new WorkflowStepView(
                                step.getPosition(), step.getRequiredRole().name(), step.getTenantId()))
                        .toList(),
                workflow.getCreatedBy(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt());
    }

    private void mustExistOrganization(UUID organizationId) {
        if (!organizations.organizationExists(organizationId)) {
            throw new WorkflowException(WorkflowException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
    }

    private ApplicationAccess.ApplicationProfile profileOf(UUID applicationId) {
        return applications
                .profileOf(applicationId)
                .orElseThrow(() ->
                        new ReleaseException(ReleaseException.Code.APPLICATION_NOT_FOUND, "application not found"));
    }

    /** Establishes the application's owning scope and runs the work inside it (the release surface's executor pattern). */
    private <T> T inScope(ApplicationAccess.ApplicationProfile application, UUID actorId, Supplier<T> work) {
        return application.tenantId() != null
                ? scopeExecutor.inScope(application.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    private static String validatedName(String name) {
        if (name == null || name.isBlank()) {
            throw invalid(Map.of("name", "name is required"));
        }
        String trimmed = name.trim();
        if (!NAME_SHAPE.matcher(trimmed).matches()) {
            throw invalid(Map.of("name", "name is lowercase kebab, at most 64 characters"));
        }
        return trimmed;
    }

    private List<StepCommand> validatedSteps(UUID organizationId, List<StepCommand> request) {
        if (request == null || request.isEmpty()) {
            throw invalid(Map.of("steps", "at least one approval step is required"));
        }
        if (request.size() > MAX_STEPS) {
            throw invalid(Map.of("steps", "at most " + MAX_STEPS + " approval steps are supported"));
        }
        Map<String, String> malformed = new LinkedHashMap<>();
        Set<Role> distinct = new LinkedHashSet<>();
        for (int i = 0; i < request.size(); i++) {
            StepCommand step = request.get(i);
            Role parsed = null;
            if (step.role() == null || step.role().isBlank()) {
                malformed.put("steps[" + i + "]", "role is required for every step");
            } else {
                try {
                    parsed = Role.valueOf(step.role().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    malformed.put(
                            "steps[" + i + "]",
                            "unknown approval role '" + step.role().trim() + "' (QA_ENGINEER, SECURITY_REVIEWER, "
                                    + "COMPLIANCE_REVIEWER, APP_OWNER, RELEASE_MANAGER)");
                }
            }
            if (parsed != null && !distinct.add(parsed)) {
                malformed.put("steps[" + i + "]", "each approval role may appear at most once in the chain");
            }
            if (step.tenantId() != null
                    && !tenants.owningOrganizationId(step.tenantId())
                            .map(owner -> owner.equals(organizationId))
                            .orElse(false)) {
                malformed.put("steps[" + i + "]", "tenantId must belong to the workflow's organization");
            }
        }
        if (!malformed.isEmpty()) {
            throw invalid(malformed);
        }
        return List.copyOf(request);
    }

    /** The reason rule (AC6): mandatory on REJECT, optional on APPROVE; trimmed; capped. */
    private static String validatedReason(boolean approve, String reason) {
        if (reason == null || reason.isBlank()) {
            if (!approve) {
                throw new WorkflowException(
                        WorkflowException.Code.APPROVAL_REJECT_REASON_REQUIRED,
                        "a reason is required to reject an approval step",
                        Map.of("reason", "a reason is required to reject"));
            }
            return null;
        }
        return optionalReason(reason);
    }

    private static String optionalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw invalid(Map.of("reason", "reason is at most " + MAX_REASON_LENGTH + " characters"));
        }
        return trimmed;
    }

    private static String chainSummaryOf(List<StepCommand> steps) {
        return steps.stream()
                .map(step -> step.role() + (step.tenantId() == null ? "" : "@" + step.tenantId()))
                .toList()
                .toString();
    }

    private static WorkflowException workflowExists() {
        return new WorkflowException(
                WorkflowException.Code.WORKFLOW_ALREADY_EXISTS,
                "an approval workflow already exists with this name or ring");
    }

    private static WorkflowException invalid(Map<String, String> errors) {
        return new WorkflowException(WorkflowException.Code.INVALID_REQUEST, "the request is invalid", errors);
    }
}
