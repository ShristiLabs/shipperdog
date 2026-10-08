package com.shipperdog.api.workflow.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.authorization.ApplicationPermissionCheck;
import com.shipperdog.api.authorization.Permission;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.MetricNames;
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
import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepEntity.ConditionType;
import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepEntity.GroupMode;
import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepEntity.Role;
import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepEntity.StepCondition;
import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalEntity;
import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalEntity.ApprovalType;
import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalRoundEntity;
import com.shipperdog.api.workflow.internal.repository.ApprovalWorkflowRepository;
import com.shipperdog.api.workflow.internal.repository.ApprovalWorkflowStepRepository;
import com.shipperdog.api.workflow.internal.repository.ReleaseApprovalRepository;
import com.shipperdog.api.workflow.internal.repository.ReleaseApprovalRoundRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
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
 * The approval engine's core (issues #40/#41, S10-01/S10-02, FR-016,
 * PRD 8.10/13/17/32/33): the workflow-as-data model the issue's
 * technical note demands — definitions an organization configures,
 * rounds a release runs, signatures the steps collect — and the
 * group-mode evaluation that gates the APPROVAL_PENDING to APPROVED
 * crossing through the S9-02 policy seam ({@code
 * ReleaseApprovalPolicy}, this package).
 *
 * <p><strong>Definitions</strong> are organization-grain reference
 * data (the environments standing): a binding name (the vocabulary
 * V27's {@code environments.approval_policy} references), an optional
 * ring attachment (releases STARTING at that ring resolve the chain),
 * and the ordered steps — each a required role from the five-role
 * vocabulary the RBAC matrix grants an approve permission, optionally
 * narrowed to a tenant the release must target. Since S10-02 the
 * steps are grouped: an ordered list of GROUPS, each ALL (the
 * PARALLEL mode: every step must approve) or ANY (the OR mode: the
 * first approval satisfies, remaining siblings become SKIPPED with
 * the satisfying signature recorded), and each step optionally
 * carrying a CONDITION predicate evaluated at round instantiation
 * (environment equals, feature-flag contains — the stub vocabulary
 * S10-06's expression evaluator migrates) whose false outcome
 * instantiates the step SKIPPED with its rationale recorded.
 * Sequential is the degenerate case: groups of one, all ALL — every
 * S10-01 definition IS that shape, and the legacy flat-step creation
 * body still builds it. Attachments resolve MOST SPECIFIC FIRST at
 * the APPROVAL_PENDING crossing: the targeted environment's named
 * policy, else the starting ring's chain — and since S10-02 a named
 * policy that does not resolve FAILS CLOSED (the
 * {@code APPROVAL_POLICY_UNRESOLVED} refusal rolls the crossing
 * back): an organization that bound a policy by name never silently
 * runs a different chain. An organization that configures neither
 * keeps the ungated machine S9-02 shipped (approvals are
 * CONFIGURABLE, FR-016 — no definition, no round, no gate).
 * Definitions are editable (the console editor's PUT): rounds already
 * run keep their frozen per-step copies, and a later round — a rework
 * restart included — instantiates the definition's CURRENT content,
 * every edit audited ({@code APPROVAL_WORKFLOW_UPDATED}).
 *
 * <p><strong>Rounds</strong> instantiate inside the crossing's
 * transaction (the {@code ReleaseTransitionedEvent} hook — the
 * manifest-freeze listener's pattern): round 1 at the crossing, round
 * N+1 only through the rework restart after a rejection (the issue's
 * documented path — PRD 17 has no back-edge, so the rework is a NEW
 * round, never a rewrite of the rejected one). The round carries the
 * definition's id and every step row its own copy of the position,
 * tenant scope, group position, and group mode: later definition
 * changes never rewrite a round already run.
 *
 * <p><strong>Decisions</strong> are signatures (the issue's technical
 * note): only the step's role-holder may decide, never the release's
 * creator (self-approval blocked), on the ACTIVE group's decidable
 * steps only ({@link ApprovalRoundEvaluator} — the first unsatisfied
 * group's PENDING steps; out-of-group or already-decided attempts
 * answer the precise 409) and exactly once per step (the repository's
 * compare-and-set). An ANY group's first approval skips its remaining
 * siblings (the skip CAS records WHICH signature satisfied the
 * group). A rejection anywhere terminates its round in either mode —
 * a no is never outvoted — and blocks progression (the release stays
 * APPROVAL_PENDING, the transition policy refusing); the last
 * satisfaction completes the round and permits the crossing, at which
 * the manifest freezes the satisfied chain (the
 * {@link ReleaseApprovalSource} this service implements). Every round
 * terminal crossing records the reserved
 * {@code approval_duration} (satisfied or rejected).
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

    /** The condition-operand cap (V29's column width). */
    private static final int MAX_CONDITION_OPERAND = 256;

    /** The listing's order: name ascending (the binding vocabulary, deterministic). */
    private static final Sort NAME_ORDER = Sort.by("name").ascending();

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
    private final MeterRegistry meters;

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
            TraceAttributes trace,
            MeterRegistry meters) {
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
        this.meters = meters;
    }

    // -------------------------------------------------------------------------
    // Definitions (AC1): create, edit, list, read
    // -------------------------------------------------------------------------

    /**
     * Creates an approval workflow definition (AC1): the binding name,
     * the optional ring attachment (resolved in the organization's
     * ring vocabulary — the creation surface's own rule, so a
     * definition and a release can never disagree on which ring they
     * mean), and the ordered steps — flat (S10-01's shape: sequential
     * groups of one) or grouped (S10-02's shape: each group ALL or
     * ANY, each step optionally conditional). Each role once across
     * the definition; an optional tenant scope must belong to the
     * organization; a condition must be well-shaped for its type. One
     * name and one ring attachment per organization (V28's guards; the
     * lost-race translation answers the same 409). Audited as
     * {@code APPROVAL_WORKFLOW_CREATED} with the whole chain. Honors
     * {@code Idempotency-Key}.
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
        UUID ringId = ringIdOf(command.organizationId(), command.ring());
        List<ValidatedGroup> groups = validatedGroups(command.organizationId(), command.steps(), command.groups());
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
        writeSteps(workflow, groups, null);

        trace.organization(command.organizationId());
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("name", name);
        detail.put("ring", detailRingOf(ringId, command.ring()));
        detail.put("steps", summaryOf(groups));
        audit.publish(AuditEvent.builder(AuditEventType.APPROVAL_WORKFLOW_CREATED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(command.organizationId())
                .success(true)
                .detail(detail)
                .build());
        return viewOf(workflow, chainOf(workflow.getId()));
    }

    /**
     * Edits an approval workflow definition (S10-02's console-editor
     * surface): replaces the chain (flat or grouped, the same
     * validation as creation) and re-attaches the ring — a null ring
     * DETACHES (the definition becomes name-only). The NAME never
     * moves: it is the binding vocabulary an environment's
     * {@code approval_policy} references, and renaming would strand
     * the binding. Rounds already run keep their frozen per-step
     * copies (nothing rewrites history); a later round — a rework
     * restart included — instantiates the definition's CURRENT
     * content, and the edit itself is audited as
     * {@code APPROVAL_WORKFLOW_UPDATED} with the full new chain.
     * Honors {@code Idempotency-Key}.
     */
    @Transactional
    public WorkflowView update(UUID actorId, UUID organizationId, UUID workflowId, UpdateCommand command) {
        mustExistOrganization(organizationId);
        ApprovalWorkflowEntity workflow = workflows
                .findByIdAndOrganizationId(workflowId, organizationId)
                .orElseThrow(
                        () -> new WorkflowException(WorkflowException.Code.NOT_FOUND, "approval workflow not found"));
        UUID ringId = ringIdOf(organizationId, command.ring());
        List<ValidatedGroup> groups = validatedGroups(organizationId, command.steps(), command.groups());
        if (ringId != null
                && workflows
                        .findByOrganizationIdAndRingId(organizationId, ringId)
                        .filter(existing -> !existing.getId().equals(workflowId))
                        .isPresent()) {
            throw workflowExists();
        }
        workflow.reattach(ringId);
        writeSteps(workflow, groups, workflowId);

        trace.organization(organizationId);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("name", workflow.getName());
        detail.put("ring", detailRingOf(ringId, command.ring()));
        detail.put("steps", summaryOf(groups));
        audit.publish(AuditEvent.builder(AuditEventType.APPROVAL_WORKFLOW_UPDATED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationId)
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
     * One approval decision (AC2/AC5/AC6, S10-02's group semantics):
     * the body names APPROVE or REJECT and may assert the step it
     * believes it is deciding (an out-of-group, already-decided, or
     * not-yet-reachable assertion is the precise 409). The decision
     * acts on a DECIDABLE step — a PENDING step of the round's ACTIVE
     * group, the first unsatisfied one ({@link
     * ApprovalRoundEvaluator}): sequential chains are groups of one,
     * so this is the S10-01 current-step rule verbatim; a parallel
     * (ALL) group makes every PENDING sibling decidable at once; an
     * ANY group likewise until its first approval skips the rest.
     * Without an assertion the caller's authority addresses the step:
     * exactly one decidable step may match the caller's permissions
     * (zero answers the precise 403 naming the step's role; several
     * demand the explicit assertion). The authority rules run before
     * any write: the release's creator may never decide their own
     * release's chain (AC5), and only a holder of the step's role may
     * decide it (AC2 — the mapped {@code app:releases:approve-*}
     * permission, the programmatic twin of the route's coarse gate).
     * A rejection requires its reason (AC6) and terminates the round —
     * in ANY mode too: a no is never outvoted. An ANY group's first
     * approval skips its remaining PENDING siblings, recording WHICH
     * signature satisfied the group; the last satisfaction satisfies
     * the round (the crossing becomes fireable, the manifest freezes
     * the satisfied chain, the round's duration is recorded). Every
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
            ApprovalRoundEvaluator.Evaluation evaluation = ApprovalRoundEvaluator.evaluate(
                    chain.stream().map(ApprovalRoundEvaluator.Step::of).toList());
            List<Integer> decidable = evaluation.decidablePositions();
            if (decidable.isEmpty()) {
                // A PENDING round with no decidable step is completing
                // (the last decision's terminal crossing has not
                // committed yet) — the serialized answer.
                throw notActive(facts, round.getStatus().name());
            }
            ReleaseApprovalEntity current = chain.stream()
                    .filter(candidate -> candidate.getPosition() == decidable.get(0))
                    .findFirst()
                    .orElseThrow();
            // The delegation rule (AC5): the creator signs nothing on
            // their own release, whatever roles they hold.
            if (actorId.equals(facts.createdBy())) {
                throw new WorkflowException(
                        WorkflowException.Code.APPROVAL_SELF_APPROVAL,
                        "the release creator may not approve their own release",
                        Map.of("createdBy", facts.createdBy().toString()));
            }
            ReleaseApprovalEntity target;
            if (step != null) {
                ReleaseApprovalEntity addressed = chain.stream()
                        .filter(candidate -> candidate.getPosition() == step)
                        .findFirst()
                        .orElseThrow(() -> invalid(Map.of(
                                "step", "unknown step position " + step + " in round " + round.getRoundNumber())));
                if (!decidable.contains(addressed.getPosition())) {
                    Map<String, String> errors = new LinkedHashMap<>();
                    errors.put("current", String.valueOf(current.getPosition()));
                    errors.put("attempted", String.valueOf(step));
                    errors.put("roundStatus", round.getStatus().name());
                    if (evaluation.activeGroupPosition() > 0) {
                        errors.put("group", String.valueOf(evaluation.activeGroupPosition()));
                    }
                    throw new WorkflowException(
                            WorkflowException.Code.APPROVAL_STEP_OUT_OF_ORDER,
                            "the decision does not address a decidable step of the active group",
                            errors);
                }
                target = addressed;
            } else {
                // Implicit addressing: the decidable steps this caller
                // may sign (the mapped approve permission per type).
                List<ReleaseApprovalEntity> held = new ArrayList<>();
                for (ReleaseApprovalEntity candidate : chain) {
                    if (decidable.contains(candidate.getPosition())
                            && permissionCheck.permits(
                                    actorId, applicationId, APPROVE_PERMISSION.get(candidate.getApprovalType()))) {
                        held.add(candidate);
                    }
                }
                if (held.isEmpty()) {
                    Permission required = APPROVE_PERMISSION.get(current.getApprovalType());
                    throw new WorkflowException(
                            WorkflowException.Code.APPROVAL_ROLE_NOT_HELD,
                            "only a holder of the step's role may decide it",
                            Map.of(
                                    "requiredRole",
                                            SIGNING_ROLE
                                                    .get(current.getApprovalType())
                                                    .name(),
                                    "approvalType", current.getApprovalType().name(),
                                    "permission", required.code()));
                }
                if (held.size() > 1) {
                    List<Integer> positions = held.stream()
                            .map(ReleaseApprovalEntity::getPosition)
                            .toList();
                    throw invalid(Map.of(
                            "step",
                            "this caller may decide several steps (positions " + positions
                                    + "); assert the step position being decided"));
                }
                target = held.get(0);
            }
            // The step's role (AC2): the type's mapped approve
            // permission, resolved against the release's application
            // exactly as the route's coarse gate resolves the five-way
            // disjunction. The role the caller must hold is the type's
            // 1:1 signing role, named in the refusal so the right
            // approver is findable.
            Permission required = APPROVE_PERMISSION.get(target.getApprovalType());
            Role signingRole = SIGNING_ROLE.get(target.getApprovalType());
            if (step != null && !permissionCheck.permits(actorId, applicationId, required)) {
                throw new WorkflowException(
                        WorkflowException.Code.APPROVAL_ROLE_NOT_HELD,
                        "only a holder of the step's role may decide it",
                        Map.of(
                                "requiredRole", signingRole.name(),
                                "approvalType", target.getApprovalType().name(),
                                "permission", required.code()));
            }

            Instant decidedAt = Instant.now(clock);
            String targetStatus = approve ? "APPROVED" : "REJECTED";
            if (approvals.decideIfCurrent(
                            target.getId(), targetStatus, actorId, signingRole.name(), validatedReason, decidedAt)
                    != 1) {
                // A concurrent decision moved the step first: answer
                // from the committed state, never a double-signature.
                ReleaseApprovalEntity committed =
                        approvals.findById(target.getId()).orElse(target);
                throw new WorkflowException(
                        WorkflowException.Code.APPROVAL_STEP_OUT_OF_ORDER,
                        "the step was decided concurrently",
                        Map.of(
                                "current", String.valueOf(committed.getPosition()),
                                "attempted", String.valueOf(target.getPosition()),
                                "status", committed.getStatus().name()));
            }
            trace.application(applicationId);
            trace.release(releaseId);
            auditDecided(facts, round, target, actorId, approve, validatedReason, decidedAt);
            if (!approve) {
                // A rejection terminates the round in either mode — a
                // no is never outvoted; the rework path restarts.
                if (rounds.transitionStatusIfCurrent(round.getId(), "PENDING", "REJECTED") == 1) {
                    recordRoundDuration(round, "rejected", decidedAt);
                }
            } else {
                // An ANY group's first approval skips its remaining
                // PENDING siblings — the skip records WHICH signature
                // satisfied the group (deterministic and auditable).
                if (target.getGroupMode() == GroupMode.ANY) {
                    for (ReleaseApprovalEntity sibling : approvals.findByRoundIdOrderByPositionAsc(round.getId())) {
                        if (sibling.getGroupPosition() == target.getGroupPosition()
                                && sibling.getStatus() == ReleaseApprovalEntity.Status.PENDING) {
                            approvals.skipIfPending(
                                    sibling.getId(),
                                    "ANY group " + target.getGroupPosition() + " satisfied by position "
                                            + target.getPosition() + " ("
                                            + target.getApprovalType().name() + ")",
                                    decidedAt);
                        }
                    }
                }
                completeRoundIfTerminal(round, decidedAt);
            }
            return stateOf(facts);
        });
    }

    /**
     * Recomputes the round from the committed rows and makes its
     * terminal crossing when the evaluation says one is due — the
     * single place a round becomes SATISFIED or REJECTED after a
     * decision. A CAS loss means a racing sibling's decision already
     * made this (or the opposite, rejection-precedent) crossing: the
     * committed state is the answer either way, so the caller's own
     * signature stands and the state view reports the truth.
     */
    private void completeRoundIfTerminal(ReleaseApprovalRoundEntity round, Instant decidedAt) {
        ApprovalRoundEvaluator.Evaluation evaluation =
                ApprovalRoundEvaluator.evaluate(approvals.findByRoundIdOrderByPositionAsc(round.getId()).stream()
                        .map(ApprovalRoundEvaluator.Step::of)
                        .toList());
        switch (evaluation.outcome()) {
            case SATISFIED -> {
                if (rounds.transitionStatusIfCurrent(round.getId(), "PENDING", "SATISFIED") == 1) {
                    recordRoundDuration(round, "satisfied", decidedAt);
                }
            }
            case REJECTED -> {
                if (rounds.transitionStatusIfCurrent(round.getId(), "PENDING", "REJECTED") == 1) {
                    recordRoundDuration(round, "rejected", decidedAt);
                }
            }
            case PENDING -> {
                // The round continues (later groups, or remaining
                // siblings of the active one).
            }
        }
    }

    /** Records the reserved {@code approval_duration} at a round's terminal crossing (the span this round ran). */
    private void recordRoundDuration(ReleaseApprovalRoundEntity round, String outcome, Instant terminalAt) {
        Timer.builder(MetricNames.APPROVAL_DURATION)
                .tag("outcome", outcome)
                .register(meters)
                .record(Duration.between(round.getCreatedAt(), terminalAt));
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
     * 16's {@code approval.required}): the latest SATISFIED round's
     * SIGNED steps — the APPROVED rows in chain order; SKIPPED rows
     * demanded nothing (an ANY group satisfied by one signature
     * records that one, a condition-excluded step was never asked) —
     * the facts the transition policy verified when it permitted the
     * crossing, frozen verbatim into the manifest at the freeze that
     * same crossing triggers. Empty when no round exists or the
     * latest is not SATISFIED (a rejected or pending chain authorizes
     * nothing — the honest empty, the manifest's discipline for
     * absent blocks).
     */
    @Override
    @Transactional(readOnly = true)
    public List<String> approvalTypesOf(UUID releaseId) {
        return rounds.findFirstByReleaseIdOrderByRoundNumberDesc(releaseId)
                .filter(round -> round.getStatus() == ReleaseApprovalRoundEntity.Status.SATISFIED)
                .map(round -> approvals.findByRoundIdOrderByPositionAsc(round.getId()).stream()
                        .filter(approval -> approval.getStatus() == ReleaseApprovalEntity.Status.APPROVED)
                        .map(approval -> approval.getApprovalType().name())
                        .toList())
                .orElseGet(List::of);
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /** One trigger predicate of a request: the type string and its operands (validated into the module's own shape). */
    public record ConditionCommand(String type, String key, String value) {}

    /** One step of a creation request: the required role (the request's string), the optional tenant scope and condition. */
    public record StepCommand(String role, UUID tenantId, ConditionCommand condition) {}

    /** One group of a creation request: the mode string (ALL or ANY, default ALL) and its ordered steps. */
    public record GroupCommand(String mode, List<StepCommand> steps) {}

    /** The creation command (the controller's request, already the module's own shape): flat steps OR groups. */
    public record CreateCommand(
            UUID organizationId, String name, String ring, List<StepCommand> steps, List<GroupCommand> groups) {}

    /**
     * The edit command (the definition PUT): the chain replaces (flat
     * or grouped, exactly one shape like creation) and the ring
     * re-attaches (null detaches). The name is not editable — it is
     * the binding vocabulary environments reference.
     */
    public record UpdateCommand(String ring, List<StepCommand> steps, List<GroupCommand> groups) {}

    /** One trigger predicate, read back: the type, its key (flag conditions), its value. */
    public record ConditionView(String type, String key, String value) {}

    /** One step of a definition, read back: its group facts beside the role and scope. */
    public record WorkflowStepView(
            int position,
            String requiredRole,
            UUID tenantId,
            int groupPosition,
            String groupMode,
            ConditionView condition) {}

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

    /**
     * One step instance of a round: the signature when decided, the
     * demand while pending, and the skip rationale when SKIPPED (an
     * ANY-group sibling after the group's first approval, or a
     * condition-excluded step at instantiation).
     */
    public record ApprovalStepView(
            int position,
            String approvalType,
            UUID tenantId,
            int groupPosition,
            String groupMode,
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
     * chain. Since S10-02 a named policy that does not resolve FAILS
     * CLOSED: the organization bound a policy by name, and silently
     * running a different (possibly weaker) chain would be the
     * fail-open hole the #40 review flagged — the crossing refuses
     * with {@code APPROVAL_POLICY_UNRESOLVED} and the release stays
     * one status earlier until the binding or the definition is
     * fixed. An environment that binds NOTHING falls through to the
     * ring chain: absence is not misconfiguration (both legs are
     * explicit configuration, and the ring chain is the organization's
     * own default).
     */
    private Optional<ApprovalWorkflowEntity> resolveDefinitionFor(ReleaseAccess.ReleaseFacts facts) {
        if (facts.environmentId() != null) {
            Optional<String> policyName = environments.approvalPolicyNameOf(facts.environmentId());
            if (policyName.isPresent()) {
                return Optional.of(workflows
                        .findByOrganizationIdAndName(facts.organizationId(), policyName.get())
                        .orElseThrow(() -> new WorkflowException(
                                WorkflowException.Code.APPROVAL_POLICY_UNRESOLVED,
                                "the environment's approval policy '" + policyName.get()
                                        + "' does not resolve to a defined workflow",
                                Map.of("approvalPolicy", policyName.get()))));
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
     * crossing instantiation and the rework restart. Each applicable
     * definition step instantiates with its copied group facts; a
     * step whose CONDITION evaluates false instantiates SKIPPED with
     * its evaluation rationale recorded (the row and the round-start
     * audit both carry it); a tenant-scoped step whose tenant is not
     * targeted does not instantiate at all (the S10-01 applicability
     * rule, unchanged). A round whose every step is skipped or scoped
     * away is born SATISFIED — nothing was required.
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
        ConditionContext context = conditionContextOf(facts);
        Instant requestedAt = Instant.now(clock);
        List<ReleaseApprovalEntity> demanded = new ArrayList<>(definition.size());
        List<ReleaseApprovalEntity> skippedByCondition = new ArrayList<>();
        int scopedAway = 0;
        for (ApprovalWorkflowStepEntity step : definition) {
            if (step.getTenantId() != null && !targetTenants.contains(step.getTenantId())) {
                scopedAway++;
                continue;
            }
            ConditionOutcome outcome = step.condition() == null ? null : evaluateCondition(step.condition(), context);
            if (outcome != null && !outcome.met()) {
                skippedByCondition.add(ReleaseApprovalEntity.skippedAtInstantiation(
                        facts.releaseId(),
                        null,
                        step.getPosition(),
                        ApprovalType.of(step.getRequiredRole()),
                        step.getTenantId(),
                        step.getGroupPosition(),
                        step.getGroupMode(),
                        outcome.rationale(),
                        requestedAt));
                continue;
            }
            demanded.add(new ReleaseApprovalEntity(
                    facts.releaseId(),
                    null,
                    step.getPosition(),
                    ApprovalType.of(step.getRequiredRole()),
                    step.getTenantId(),
                    step.getGroupPosition(),
                    step.getGroupMode()));
        }
        ReleaseApprovalRoundEntity round =
                new ReleaseApprovalRoundEntity(facts.releaseId(), roundNumber, workflow.getId(), actorId);
        if (demanded.isEmpty()) {
            // Every step scoped away or excluded by its condition:
            // nothing was required — the round records the resolution
            // and stands satisfied (the crossing's key by the same
            // act).
            round.setStatus(ReleaseApprovalRoundEntity.Status.SATISFIED);
        }
        try {
            round = rounds.saveAndFlush(round);
        } catch (DataIntegrityViolationException e) {
            // The double-restart insert race (the #40 review's
            // follow-up): two concurrent restarts of the same rejected
            // round both read it REJECTED and both insert round N+1 —
            // the unique guard admits one. The loser answers from the
            // committed state with the restart conflict, never an
            // untranslated 500. The crossing path (round 1) cannot
            // race itself (the state machine's CAS serializes the
            // crossing), so a violation there is a machine bug that
            // rolls the crossing back loudly.
            if (e.getMessage() != null
                    && e.getMessage().contains("uq_release_approval_rounds_release_number")
                    && "RESTART".equals(trigger)) {
                ReleaseApprovalRoundEntity committed = rounds.findFirstByReleaseIdOrderByRoundNumberDesc(
                                facts.releaseId())
                        .orElse(null);
                throw new WorkflowException(
                        WorkflowException.Code.APPROVAL_ROUND_NOT_RESTARTABLE,
                        "only a rejected approval round can be restarted",
                        Map.of(
                                "current",
                                committed == null
                                        ? "NONE"
                                        : committed.getStatus().name()));
            }
            throw e;
        }
        List<ReleaseApprovalEntity> requested = new ArrayList<>(demanded.size() + skippedByCondition.size());
        for (ReleaseApprovalEntity row : demanded) {
            row.assignRound(round.getId());
            requested.add(approvals.save(row));
        }
        for (ReleaseApprovalEntity row : skippedByCondition) {
            row.assignRound(round.getId());
            requested.add(approvals.save(row));
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
                demanded.isEmpty()
                        ? "-"
                        : demanded.stream()
                                .map(step -> step.getPosition()
                                        + ":"
                                        + step.getApprovalType().name()
                                        + (step.getGroupMode() == GroupMode.ANY
                                                ? "(ANY@" + step.getGroupPosition() + ")"
                                                : "")
                                        + (step.getTenantId() == null ? "" : "@" + step.getTenantId()))
                                .toList()
                                .toString());
        if (scopedAway > 0) {
            detail.put("skippedTenantScopedSteps", String.valueOf(scopedAway));
        }
        if (!skippedByCondition.isEmpty()) {
            detail.put(
                    "skippedConditionalSteps",
                    skippedByCondition.stream()
                            .map(step -> step.getPosition() + ":"
                                    + step.getApprovalType().name() + " — " + step.getReason())
                            .toList()
                            .toString());
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
        for (ReleaseApprovalEntity step : demanded) {
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

    /** The frozen facts a condition predicate evaluates against (the environment the release targets). */
    private record ConditionContext(String environmentCode, Map<String, String> featureFlags) {

        static ConditionContext none() {
            return new ConditionContext(null, Map.of());
        }
    }

    /** One condition evaluation's outcome: met or not, with the rationale either way. */
    private record ConditionOutcome(boolean met, String rationale) {}

    /**
     * Assembles the condition context from the release's targeted
     * environment (the manifest freeze's own source — the environment
     * state and its resolved configuration snapshot, deep values):
     * the environment's code and its resolved feature flags. A
     * release naming no environment evaluates against the empty
     * context (no code, no flags — predicates answer false with that
     * recorded).
     */
    private ConditionContext conditionContextOf(ReleaseAccess.ReleaseFacts facts) {
        if (facts.environmentId() == null) {
            return ConditionContext.none();
        }
        String code = environments
                .stateOf(facts.environmentId())
                .map(state -> state.code())
                .orElse(null);
        Map<String, String> flags =
                environments.configSnapshotOf(facts.environmentId()).entries();
        return new ConditionContext(code, flags);
    }

    /**
     * Evaluates one trigger predicate against the context and renders
     * the rationale both ways — the audit answer to "WHY was this
     * step included/excluded" (the issue's AC).
     */
    private static ConditionOutcome evaluateCondition(StepCondition condition, ConditionContext context) {
        return switch (condition.type()) {
            case ENVIRONMENT_EQUALS -> {
                String actual = context.environmentCode() == null ? "none targeted" : context.environmentCode();
                yield new ConditionOutcome(
                        condition.value().equals(context.environmentCode()),
                        "condition " + condition.describe() + " "
                                + (condition.value().equals(context.environmentCode()) ? "met" : "unmet")
                                + " (environment=" + actual + ")");
            }
            case FEATURE_FLAG_CONTAINS -> {
                String actual = context.featureFlags().get(condition.key());
                boolean present = actual != null;
                boolean met = present
                        && (condition.value() == null || condition.value().equals(actual));
                String actualRendered = present ? condition.key() + "=" + actual : condition.key() + " absent";
                yield new ConditionOutcome(
                        met,
                        "condition " + condition.describe() + " " + (met ? "met" : "unmet") + " (" + actualRendered
                                + ")");
            }
        };
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
                            step.getGroupPosition(),
                            step.getGroupMode().name(),
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
                                step.getPosition(),
                                step.getRequiredRole().name(),
                                step.getTenantId(),
                                step.getGroupPosition(),
                                step.getGroupMode().name(),
                                step.condition() == null
                                        ? null
                                        : new ConditionView(
                                                step.condition().type().name(),
                                                step.condition().key(),
                                                step.condition().value())))
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

    /** Resolves the optional ring-attachment code to its id in the organization's vocabulary (or null: none named). */
    private UUID ringIdOf(UUID organizationId, String ring) {
        if (ring == null || ring.isBlank()) {
            return null;
        }
        String ringCode = ring.trim().toLowerCase(Locale.ROOT);
        return releases.ringIdOf(organizationId, ringCode)
                .orElseThrow(() -> new WorkflowException(
                        WorkflowException.Code.WORKFLOW_RING_UNKNOWN,
                        "ring is not defined for the organization",
                        Map.of("ring", "unknown ring code '" + ringCode + "'")));
    }

    private static String detailRingOf(UUID ringId, String ring) {
        return ringId == null ? "-" : ring.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Validates the requested chain, flat or grouped (AC1 plus
     * S10-02's group vocabulary): exactly one shape per request, at
     * least one step and at most {@code MAX_STEPS} of them (groups
     * included), every group non-empty, its mode ALL or ANY, each role
     * once across the WHOLE definition (a chain never asks the same
     * role twice, whatever the topology), every tenant scope inside
     * the organization, every condition well-shaped for its type. A
     * flat body validates under {@code steps[i]} keys (the S10-01
     * request contract, unchanged); a grouped body under
     * {@code groups[g].steps[i]}.
     */
    private List<ValidatedGroup> validatedGroups(
            UUID organizationId, List<StepCommand> flatSteps, List<GroupCommand> groups) {
        boolean flat = flatSteps != null && !flatSteps.isEmpty();
        boolean grouped = groups != null && !groups.isEmpty();
        if (!flat && !grouped) {
            throw invalid(Map.of("steps", "at least one approval step is required"));
        }
        if (flat && grouped) {
            throw invalid(Map.of("steps", "pass either steps (sequential) or groups (ALL/ANY), not both"));
        }
        Map<String, String> malformed = new LinkedHashMap<>();
        Set<Role> distinct = new LinkedHashSet<>();
        List<ValidatedGroup> validated = new ArrayList<>();
        if (flat) {
            if (flatSteps.size() > MAX_STEPS) {
                throw invalid(Map.of("steps", "at most " + MAX_STEPS + " approval steps are supported"));
            }
            // The S10-01 shape: sequential groups of one, all ALL.
            for (int i = 0; i < flatSteps.size(); i++) {
                validated.add(new ValidatedGroup(
                        GroupMode.ALL,
                        List.of(validatedStep(
                                organizationId, flatSteps.get(i), "steps[" + i + "]", malformed, distinct))));
            }
        } else {
            if (groups.size() > MAX_STEPS) {
                throw invalid(Map.of("groups", "at most " + MAX_STEPS + " approval groups are supported"));
            }
            int totalSteps = groups.stream()
                    .mapToInt(group -> group.steps() == null ? 0 : group.steps().size())
                    .sum();
            if (totalSteps < 1) {
                throw invalid(Map.of("steps", "at least one approval step is required"));
            }
            if (totalSteps > MAX_STEPS) {
                throw invalid(Map.of("steps", "at most " + MAX_STEPS + " approval steps are supported"));
            }
            for (int g = 0; g < groups.size(); g++) {
                GroupCommand group = groups.get(g);
                GroupMode mode = GroupMode.ALL;
                if (group.mode() != null && !group.mode().isBlank()) {
                    try {
                        mode = GroupMode.valueOf(group.mode().trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        malformed.put(
                                "groups[" + g + "].mode",
                                "unknown group mode '" + group.mode().trim() + "' (ALL or ANY)");
                    }
                }
                if (group.steps() == null || group.steps().isEmpty()) {
                    malformed.put("groups[" + g + "]", "every group needs at least one step");
                    continue;
                }
                List<ValidatedStep> steps = new ArrayList<>(group.steps().size());
                for (int i = 0; i < group.steps().size(); i++) {
                    steps.add(validatedStep(
                            organizationId,
                            group.steps().get(i),
                            "groups[" + g + "].steps[" + i + "]",
                            malformed,
                            distinct));
                }
                validated.add(new ValidatedGroup(mode, steps));
            }
        }
        if (!malformed.isEmpty()) {
            throw invalid(malformed);
        }
        return validated;
    }

    /** One step of either body shape: role vocabulary, tenant scope, condition shape. */
    private ValidatedStep validatedStep(
            UUID organizationId, StepCommand step, String key, Map<String, String> malformed, Set<Role> distinct) {
        Role parsed = null;
        if (step.role() == null || step.role().isBlank()) {
            malformed.put(key, "role is required for every step");
        } else {
            try {
                parsed = Role.valueOf(step.role().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                malformed.put(
                        key,
                        "unknown approval role '" + step.role().trim() + "' (QA_ENGINEER, SECURITY_REVIEWER, "
                                + "COMPLIANCE_REVIEWER, APP_OWNER, RELEASE_MANAGER)");
            }
        }
        if (parsed != null && !distinct.add(parsed)) {
            malformed.put(key, "each approval role may appear at most once in the chain");
        }
        if (step.tenantId() != null
                && !tenants.owningOrganizationId(step.tenantId())
                        .map(owner -> owner.equals(organizationId))
                        .orElse(false)) {
            malformed.put(key, "tenantId must belong to the workflow's organization");
        }
        return new ValidatedStep(parsed, step.tenantId(), validatedCondition(step.condition(), key, malformed));
    }

    /**
     * One trigger predicate's shape (S10-02's stub vocabulary):
     * {@code ENVIRONMENT_EQUALS} names its environment code in
     * {@code value} (a key is not its shape);
     * {@code FEATURE_FLAG_CONTAINS} names its flag in {@code key},
     * optionally pinning the exact {@code value}. Operands cap at the
     * column width (256).
     */
    private static StepCondition validatedCondition(ConditionCommand raw, String key, Map<String, String> malformed) {
        if (raw == null || (raw.type() == null && raw.key() == null && raw.value() == null)) {
            if (raw != null && raw.type() == null && (raw.key() != null || raw.value() != null)) {
                malformed.put(
                        key + ".condition", "a condition needs a type (ENVIRONMENT_EQUALS or FEATURE_FLAG_CONTAINS)");
            }
            return null;
        }
        if (raw.type() == null) {
            malformed.put(key + ".condition", "a condition needs a type (ENVIRONMENT_EQUALS or FEATURE_FLAG_CONTAINS)");
            return null;
        }
        String type = raw.type().trim().toUpperCase(Locale.ROOT);
        String trimmedKey = raw.key() == null ? null : raw.key().trim();
        String trimmedValue = raw.value() == null ? null : raw.value().trim();
        if (trimmedKey != null && trimmedKey.isEmpty()) {
            trimmedKey = null;
        }
        if (trimmedValue != null && trimmedValue.isEmpty()) {
            trimmedValue = null;
        }
        for (String operand : new String[] {trimmedKey, trimmedValue}) {
            if (operand != null && operand.length() > MAX_CONDITION_OPERAND) {
                malformed.put(
                        key + ".condition", "condition operands are at most " + MAX_CONDITION_OPERAND + " characters");
                return null;
            }
        }
        return switch (type) {
            case "ENVIRONMENT_EQUALS" -> {
                if (trimmedKey != null) {
                    malformed.put(key + ".condition", "ENVIRONMENT_EQUALS takes value (the environment code), not key");
                }
                if (trimmedValue == null) {
                    malformed.put(key + ".condition", "ENVIRONMENT_EQUALS requires value (the environment code)");
                }
                yield trimmedValue == null || trimmedKey != null
                        ? null
                        : new StepCondition(ConditionType.ENVIRONMENT_EQUALS, null, trimmedValue);
            }
            case "FEATURE_FLAG_CONTAINS" -> {
                if (trimmedKey == null) {
                    malformed.put(key + ".condition", "FEATURE_FLAG_CONTAINS requires key (the flag name)");
                }
                yield trimmedKey == null
                        ? null
                        : new StepCondition(ConditionType.FEATURE_FLAG_CONTAINS, trimmedKey, trimmedValue);
            }
            default -> {
                malformed.put(
                        key + ".condition",
                        "unknown condition type '" + raw.type().trim()
                                + "' (ENVIRONMENT_EQUALS or FEATURE_FLAG_CONTAINS)");
                yield null;
            }
        };
    }

    /**
     * Writes the definition's step rows from the validated groups:
     * the definition-wide position 1..n from the flattened order, the
     * group position 1..m from the group order, the mode on every row
     * of its group. An edit (existingId) replaces the chain — the
     * rows carry no children, and rounds hold their own frozen copies.
     * The duplicate-authority translations answer the same 400/409 as
     * the pre-checks (the release module's discipline); anything else
     * is a bug to surface.
     */
    private void writeSteps(ApprovalWorkflowEntity workflow, List<ValidatedGroup> groups, UUID existingId) {
        try {
            if (existingId != null) {
                // The entity-by-entity delete keeps the persistence
                // context consistent (a bulk delete would strand the
                // deleted rows' managed copies and turn the re-inserts
                // into UPDATEs on vanished rows) — and a chain carries
                // at most ten rows, so per-entity deletes are the
                // honest price of staying inside the repository seam
                // (queries never leave the repository package, the
                // tenant-isolation arch rule).
                workflowSteps.deleteAll(workflowSteps.findByWorkflowIdOrderByPositionAsc(existingId));
                workflowSteps.flush();
                workflows.saveAndFlush(workflow);
            } else {
                workflow = workflows.saveAndFlush(workflow);
            }
            int position = 1;
            int groupPosition = 1;
            for (ValidatedGroup group : groups) {
                for (ValidatedStep step : group.steps()) {
                    workflowSteps.save(new ApprovalWorkflowStepEntity(
                            workflow.getId(),
                            position,
                            step.role(),
                            step.tenantId(),
                            groupPosition,
                            group.mode(),
                            step.condition()));
                    position++;
                }
                groupPosition++;
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
    }

    /** One validated step: the parsed role, the optional tenant scope, the optional condition. */
    private record ValidatedStep(Role role, UUID tenantId, StepCondition condition) {}

    /** One validated group: the satisfaction mode and its ordered steps. */
    private record ValidatedGroup(GroupMode mode, List<ValidatedStep> steps) {}

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

    /** The chain summary the definition audits render: groups with their modes, steps with their scopes and conditions. */
    private static String summaryOf(List<ValidatedGroup> groups) {
        List<String> rendered = new ArrayList<>();
        for (ValidatedGroup group : groups) {
            List<String> steps = new ArrayList<>();
            for (ValidatedStep step : group.steps()) {
                steps.add(step.role().name()
                        + (step.tenantId() == null ? "" : "@" + step.tenantId())
                        + (step.condition() == null
                                ? ""
                                : " if " + step.condition().describe()));
            }
            rendered.add(group.mode().name() + "[" + String.join(", ", steps) + "]");
        }
        return rendered.toString();
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
