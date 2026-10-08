package com.shipperdog.api.workflow;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.workflow.internal.service.ApprovalWorkflowService;
import com.shipperdog.api.workflow.internal.service.ApprovalWorkflowService.ApprovalStateView;
import com.shipperdog.api.workflow.internal.service.ApprovalWorkflowService.CreateCommand;
import com.shipperdog.api.workflow.internal.service.ApprovalWorkflowService.UpdateCommand;
import com.shipperdog.api.workflow.internal.service.ApprovalWorkflowService.WorkflowView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The approval workflow API (issue #40, S10-01, FR-016, PRD 8.10/33):
 * two surfaces in one controller, the module the PRD 22 map places
 * approvals in. The definition routes are organization-grain (the
 * environments shape — the permission target is the organization the
 * body/query carries, and the item read takes {@code organizationId}
 * as the body-addressed query parameter the release detail
 * established); the approval-action routes are release-addressed and
 * carry the application in the body/query, the release surface's own
 * permission-target shape, composed under the release collection the
 * same way the grant routes compose under the tenant collection.
 *
 * <p><strong>Definitions</strong> ({@code /api/v1/approval-workflows},
 * {@code org:approval-workflows:manage/read}): a binding name (the
 * vocabulary V27's {@code environments.approval_policy} references),
 * an optional ring attachment, and the ordered steps — each a
 * required role from the five-role approval vocabulary, optionally
 * narrowed to a tenant the release must target. Creation honors
 * {@code Idempotency-Key}; the listing paginates with the standard
 * limit/cursor.
 *
 * <p><strong>Approvals</strong> ({@code /api/v1/releases/{releaseId}/
 * approvals}, the issue's AC2-AC6): the decision route's
 * {@code @PreAuthorize} is the coarse gate — the release READ
 * authority every approver role holds, concealing foreign callers
 * before any state is read (the evaluator's throwing denial makes
 * permission disjunctions unusable as gates; see
 * {@link #RELEASE_READ_AUTHORITY}) — and the service answers the
 * precise question per step: only the step's role-holder decides
 * (the mapped permission through the programmatic check), never the
 * release's creator, always the chain's current step (out-of-order
 * is the precise 409). Reject requires its reason and opens the
 * rework path ({@code POST .../approvals/rounds},
 * {@code app:releases:manage}: restarting a chain is release
 * management, not a signature). The read route answers the whole
 * audit-view — every round, every step, every recorded signature
 * ({@code app:releases:read}).
 */
@RestController
public class WorkflowController {

    /**
     * The decision route's coarse gate. Deliberately the release READ
     * authority, not an approve-permission disjunction: the RBAC
     * evaluator THROWS its concealed denial on the first failing leg
     * (PRD 12 concealment — a denial must not reveal that the
     * resource exists), so a SpEL {@code or} chain can never fall
     * through to a later permission; every approver role holds
     * {@code app:releases:read} (the RBAC matrix), the read gate
     * conceals foreign callers exactly like every other release
     * route, and the service's precise check — the step's mapped
     * approve permission through the programmatic application-grant
     * probe — answers WHICH step this caller may decide with the
     * precise 403 naming the required role.
     */
    private static final String RELEASE_READ_AUTHORITY =
            "hasPermission(#request.applicationId(), 'application', 'app:releases:read')";

    private final ApprovalWorkflowService workflows;

    WorkflowController(ApprovalWorkflowService workflows) {
        this.workflows = workflows;
    }

    // -------------------------------------------------------------------------
    // Definitions (AC1)
    // -------------------------------------------------------------------------

    /**
     * Creates an approval workflow definition (AC1 + S10-02's group
     * vocabulary): the binding name, the optional ring attachment,
     * and the ordered chain — flat steps (S10-01's sequential shape)
     * or groups (each ALL — the PARALLEL mode — or ANY — the OR mode
     * — with optionally conditional steps). Validations refuse before
     * any write — the organization must exist, the ring must resolve
     * in its vocabulary, the chain is at least one and at most ten
     * steps, each role once across the definition, every tenant scope
     * inside the organization, every condition well-shaped. One name
     * and one ring attachment per organization (the precise 409);
     * audited with the whole chain.
     */
    @PostMapping("/api/v1/approval-workflows")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.organizationId(), 'organization', 'org:approval-workflows:manage')")
    WorkflowView create(@AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        return workflows.create(
                requireUserId(principal),
                new CreateCommand(
                        request.organizationId(),
                        request.name(),
                        request.ring(),
                        WorkflowRequests.stepCommandsOf(request.steps()),
                        WorkflowRequests.groupCommandsOf(request.groups())));
    }

    /**
     * Edits an approval workflow definition (S10-02's console-editor
     * surface, issue #41 AC4): replaces the chain (flat or grouped,
     * the creation shape) and re-attaches the ring — an absent ring
     * DETACHES. The name never moves (it is the binding vocabulary an
     * environment's {@code approval_policy} references). Rounds
     * already run keep their frozen per-step copies; a later round —
     * a rework restart included — instantiates the definition's
     * current content. Audited as {@code APPROVAL_WORKFLOW_UPDATED}.
     * The {@code organizationId} query parameter carries the
     * permission target; a workflow of another organization conceals
     * exactly like an unknown id.
     */
    @PutMapping("/api/v1/approval-workflows/{workflowId}")
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:approval-workflows:manage')")
    WorkflowView update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID workflowId,
            @RequestParam UUID organizationId,
            @RequestBody UpdateRequest request) {
        return workflows.update(
                requireUserId(principal),
                organizationId,
                workflowId,
                new UpdateCommand(
                        request.ring(),
                        WorkflowRequests.stepCommandsOf(request.steps()),
                        WorkflowRequests.groupCommandsOf(request.groups())));
    }

    /**
     * The definition listing (AC1): the organization's chains, name
     * order, standard limit/cursor pagination.
     * {@code organizationId} is required — it is the permission
     * target and the listing's scope.
     */
    @GetMapping("/api/v1/approval-workflows")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:approval-workflows:read')")
    PageResponse<WorkflowView> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<WorkflowView> page =
                workflows.list(requireUserId(principal), organizationId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * The definition detail (AC1): the chain read back. The
     * {@code organizationId} query parameter carries the permission
     * target (the body-addressed shape); a workflow of another
     * organization conceals exactly like an unknown id.
     */
    @GetMapping("/api/v1/approval-workflows/{workflowId}")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:approval-workflows:read')")
    WorkflowView get(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID workflowId,
            @RequestParam UUID organizationId) {
        return workflows.get(requireUserId(principal), organizationId, workflowId);
    }

    // -------------------------------------------------------------------------
    // Approvals (AC2-AC6)
    // -------------------------------------------------------------------------

    /**
     * One approval decision (AC2/AC5/AC6): the body names APPROVE or
     * REJECT, may assert the step being decided (an out-of-order or
     * already-decided assertion is the precise 409), and carries the
     * reason — mandatory on REJECT. The decision acts on the chain's
     * current step; only the step's role-holder may decide it, and
     * never the release's creator. Approve satisfies the round at the
     * last step (the APPROVED crossing becomes fireable); reject
     * terminates the round and blocks progression (the release stays
     * APPROVAL_PENDING, the rejection recorded). Every decision is
     * audited with its reason. Answers the refreshed approval state.
     */
    @PostMapping("/api/v1/releases/{releaseId}/approvals")
    @PreAuthorize(RELEASE_READ_AUTHORITY)
    ApprovalStateView decide(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestBody DecisionRequest request) {
        Integer step = request.step() == null ? null : request.step();
        return workflows.decide(
                requireUserId(principal),
                request.applicationId(),
                releaseId,
                request.decision(),
                step,
                request.reason());
    }

    /**
     * The release's approval state (the audit read): every round with
     * its steps and recorded signatures — the rejected rounds and
     * their reasons beside the live one, plus the release's lifecycle
     * status for context. The {@code applicationId} query parameter
     * carries the permission target (the body-addressed shape); a
     * release of another application conceals exactly like an unknown
     * id.
     */
    @GetMapping("/api/v1/releases/{releaseId}/approvals")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    ApprovalStateView state(
            @AuthenticationPrincipal Object principal, @PathVariable UUID releaseId, @RequestParam UUID applicationId) {
        return workflows.state(requireUserId(principal), applicationId, releaseId);
    }

    /**
     * The rework restart (AC3's documented path): a rejected round's
     * chain re-opens as a NEW round of the same frozen definition —
     * round N+1 pending, one request event per step, the optional
     * note audited. Only a rejected round is restartable, and only
     * while the release sits in APPROVAL_PENDING (PRD 17 has no
     * back-edge; the rework is a new round, never an un-reject).
     * Requires {@code app:releases:manage}: restarting an approval
     * chain is release management, not an approval signature. Not
     * idempotency-keyed: the round number is the replay answer.
     */
    @PostMapping("/api/v1/releases/{releaseId}/approvals/rounds")
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:manage')")
    ApprovalStateView restart(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestBody RestartRequest request) {
        return workflows.restart(requireUserId(principal), request.applicationId(), releaseId, request.reason());
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new WorkflowException(WorkflowException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The definition creation body (AC1 + S10-02): the owning
     * organization, the binding name (lowercase kebab — the vocabulary
     * an environment's {@code approval_policy} references), the
     * optional starting-ring attachment (resolved in the
     * organization's ring vocabulary), and the ordered chain — either
     * {@code steps} (S10-01's flat sequential shape; list order IS
     * chain order) or {@code groups} (each with its ALL/ANY mode and
     * its own ordered steps), never both.
     */
    @Schema(
            description = "The approval workflow creation body: owning organization, binding name, optional ring "
                    + "attachment, and the ordered approval chain — either flat steps (sequential) or groups (each "
                    + "ALL = every step must approve, or ANY = first approval satisfies and siblings skip), never "
                    + "both; each step a required role, optionally narrowed to a tenant the release must target and "
                    + "optionally carrying a trigger condition evaluated at round instantiation.")
    public record CreateRequest(
            UUID organizationId, String name, String ring, List<StepRequest> steps, List<GroupRequest> groups) {}

    /**
     * The definition edit body (S10-02): the chain replaces (exactly
     * the creation shapes) and the ring re-attaches (absent detaches).
     */
    @Schema(
            description = "The approval workflow edit body: the chain replaces and the ring attachment re-binds "
                    + "(absent ring detaches); the name is not editable.")
    public record UpdateRequest(String ring, List<StepRequest> steps, List<GroupRequest> groups) {}

    /**
     * One step of a definition: the required role, the optional tenant
     * scope, and the optional trigger condition (S10-02).
     */
    public record StepRequest(String role, UUID tenantId, ConditionRequest condition) {}

    /**
     * One group of a definition (S10-02): the satisfaction mode — ALL
     * (the PARALLEL mode: every step must approve) or ANY (the OR
     * mode: the first approval satisfies, remaining siblings become
     * SKIPPED with the satisfying signature recorded) — and its
     * ordered steps.
     */
    public record GroupRequest(String mode, List<StepRequest> steps) {}

    /**
     * One trigger predicate (S10-02's stub vocabulary; S10-06's
     * expression evaluator migrates it): ENVIRONMENT_EQUALS takes
     * {@code value} (the environment code); FEATURE_FLAG_CONTAINS
     * takes {@code key} (the flag name) and optionally {@code value}
     * (the exact flag value).
     */
    public record ConditionRequest(String type, String key, String value) {}

    /**
     * The decision body (AC2/AC5/AC6): the application id (the
     * permission target, the body-addressed shape), APPROVE or
     * REJECT, the optional assertion of the step being decided, and
     * the reason — mandatory on REJECT, optional on APPROVE.
     */
    @Schema(
            description = "The approval decision body: the permission-target application id, APPROVE or REJECT, the "
                    + "optional step-position assertion (an out-of-order assertion answers 409), and the reason "
                    + "(mandatory on REJECT).")
    public record DecisionRequest(UUID applicationId, String decision, Integer step, String reason) {}

    /** The restart body: the application id and the optional note audited with the new round. */
    public record RestartRequest(UUID applicationId, String reason) {}
}
