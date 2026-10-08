package com.shipperdog.api.release;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.release.internal.policy.ReleasePolicyService;
import com.shipperdog.api.release.internal.policy.ReleasePolicyService.CreateCommand;
import com.shipperdog.api.release.internal.policy.ReleasePolicyService.DryRunView;
import com.shipperdog.api.release.internal.policy.ReleasePolicyService.EvaluationsView;
import com.shipperdog.api.release.internal.policy.ReleasePolicyService.HoldsView;
import com.shipperdog.api.release.internal.policy.ReleasePolicyService.RuleView;
import com.shipperdog.api.release.internal.policy.ReleasePolicyService.SkipAllowancesView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The release policy API (issue #45, S10-06, PRD 33, FR-026's
 * fail-closed ethos): two surfaces in one controller, the ring
 * module's standing. The rule routes are organization-grain ({@code
 * org:release-policies:manage/read}; the permission target is the
 * organization the body/query carries) — the declarative rule store
 * and the skip-relaxation settings an Organization Admin governs.
 * The evaluation routes are release-addressed and carry the
 * application in the query/body ({@code app:releases:read}), the
 * release surface's own permission-target shape: the on-demand
 * dry-run (the same evaluation the promote act runs, answered as a
 * read), the hold ledger, the evaluation log, and the explicit
 * privileged acknowledge ({@code app:releases:policy-acknowledge},
 * the gate-override discipline — its own audited authority).
 */
@RestController
public class ReleasePolicyController {

    private final ReleasePolicyService policies;

    ReleasePolicyController(ReleasePolicyService policies) {
        this.policies = policies;
    }

    // -------------------------------------------------------------------------
    // The rule store (AC1/AC6)
    // -------------------------------------------------------------------------

    /**
     * The organization's policy rules in the DEFINED evaluation order
     * (position, then name): the governance console's list. Standard
     * limit/cursor pagination.
     */
    @GetMapping("/api/v1/release-policies")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:release-policies:read')")
    PageResponse<RuleView> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<RuleView> page = policies.list(requireUserId(principal), organizationId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * One policy rule read back with its condition and the human
     * summary of it. The {@code organizationId} query parameter
     * carries the permission target; a rule of another organization
     * conceals exactly like an unknown id.
     */
    @GetMapping("/api/v1/release-policies/{ruleId}")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:release-policies:read')")
    RuleView get(
            @AuthenticationPrincipal Object principal, @PathVariable UUID ruleId, @RequestParam UUID organizationId) {
        return policies.get(requireUserId(principal), organizationId, ruleId);
    }

    /**
     * Creates a policy rule (AC1/AC6): the name, the scope and its
     * reference, the action (BLOCK, HOLD_FOR_REVIEW, ALLOW), the
     * typed condition tree, the evaluation position, and the enabled
     * standing. Validations refuse before any write — the condition
     * must parse (the typed tree, bounded) and every reference must
     * resolve (the application belongs to the organization, the ring
     * code is in its vocabulary, the environment is its own, the gate
     * type is a known kind) — the console editor renders exactly
     * these errors. One name per organization (the precise 409).
     * Audited. Honors {@code Idempotency-Key}.
     */
    @PostMapping("/api/v1/release-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.organizationId(), 'organization', 'org:release-policies:manage')")
    RuleView create(@AuthenticationPrincipal Object principal, @RequestBody RuleRequest request) {
        return policies.create(
                requireUserId(principal),
                new CreateCommand(
                        request.organizationId(),
                        request.name(),
                        request.scope(),
                        request.applicationId(),
                        request.ring(),
                        request.environmentId(),
                        request.action(),
                        request.condition(),
                        request.position(),
                        request.enabled()));
    }

    /**
     * Replaces a policy rule (the editor's save): the rule's meaning
     * end to end, revalidated as a whole — a half-edited rule never
     * reaches storage. Audited with the changed facts. Honors
     * {@code Idempotency-Key}.
     */
    @PutMapping("/api/v1/release-policies/{ruleId}")
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:release-policies:manage')")
    RuleView update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID ruleId,
            @RequestParam UUID organizationId,
            @RequestBody RuleRequest request) {
        return policies.update(
                requireUserId(principal),
                organizationId,
                ruleId,
                new CreateCommand(
                        request.organizationId(),
                        request.name(),
                        request.scope(),
                        request.applicationId(),
                        request.ring(),
                        request.environmentId(),
                        request.action(),
                        request.condition(),
                        request.position(),
                        request.enabled()));
    }

    /**
     * Deletes a policy rule: a rule the hold ledger references
     * refuses ({@code POLICY_RULE_IN_USE} — the ledger is audit
     * evidence; disable the rule instead). The evaluation log's name
     * snapshots survive by design. Audited with the full deleted
     * facts.
     */
    @DeleteMapping("/api/v1/release-policies/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:release-policies:manage')")
    void delete(
            @AuthenticationPrincipal Object principal, @PathVariable UUID ruleId, @RequestParam UUID organizationId) {
        policies.delete(requireUserId(principal), organizationId, ruleId);
    }

    // -------------------------------------------------------------------------
    // The skip-relaxation knob (the seam's widening path)
    // -------------------------------------------------------------------------

    /**
     * The organization's skip allowances: the named rings a promotion
     * may carry BEYOND the next (the #44 default posture's documented
     * relaxation). Absent settings answer the empty set.
     */
    @GetMapping("/api/v1/release-policies/skip-allowances")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:release-policies:read')")
    SkipAllowancesView skipAllowances(@AuthenticationPrincipal Object principal, @RequestParam UUID organizationId) {
        return policies.skipAllowances(requireUserId(principal), organizationId);
    }

    /**
     * Replaces the skip allowances (the whole set): every named ring
     * must resolve in the organization's effective vocabulary.
     * Audited — widening the promotion walk is a governed act.
     * Honors {@code Idempotency-Key}.
     */
    @PutMapping("/api/v1/release-policies/skip-allowances")
    @Idempotent
    @PreAuthorize("hasPermission(#request.organizationId(), 'organization', 'org:release-policies:manage')")
    SkipAllowancesView replaceSkipAllowances(
            @AuthenticationPrincipal Object principal, @RequestBody SkipAllowancesRequest request) {
        return policies.replaceSkipAllowances(requireUserId(principal), request.organizationId(), request.rings());
    }

    // -------------------------------------------------------------------------
    // The evaluation surfaces: dry-run, holds, acknowledge, the log
    // -------------------------------------------------------------------------

    /**
     * The on-demand dry-run (AC3): the SAME evaluation the promote act
     * runs — the next ring resolved the promotion engine's way, every
     * scoped rule answered with its outcome (NOT_MET, FIRED, NO_DATA,
     * ACKNOWLEDGED) and its evidence — as a pure read. Nothing is
     * written: no evaluation row, no hold. Requires {@code
     * app:releases:read}.
     */
    @GetMapping("/api/v1/releases/{releaseId}/policy-dry-run")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    DryRunView dryRun(
            @AuthenticationPrincipal Object principal, @PathVariable UUID releaseId, @RequestParam UUID applicationId) {
        return policies.dryRun(requireUserId(principal), applicationId, releaseId);
    }

    /**
     * The release's hold ledger (AC2): every fired HOLD rule's demand
     * — PENDING first-class, ACKNOWLEDGED with actor, reason, and
     * moment — the surface the console's acknowledge button reads.
     * Requires {@code app:releases:read}.
     */
    @GetMapping("/api/v1/releases/{releaseId}/policy-holds")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    HoldsView holds(
            @AuthenticationPrincipal Object principal, @PathVariable UUID releaseId, @RequestParam UUID applicationId) {
        return policies.holds(requireUserId(principal), applicationId, releaseId);
    }

    /**
     * The explicit privileged acknowledge (AC2): a PENDING hold flips
     * to ACKNOWLEDGED with the actor, the MANDATORY reason, and the
     * moment — audited, the gate-override discipline. The decision
     * stands: later hops of the same release into the same ring pass
     * the hold. Requires {@code app:releases:policy-acknowledge}.
     */
    @PostMapping("/api/v1/releases/{releaseId}/policy-holds/{holdId}/acknowledge")
    @Idempotent
    @PreAuthorize("hasPermission(#request.applicationId(), 'application', 'app:releases:policy-acknowledge')")
    HoldsView.HoldView acknowledge(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @PathVariable UUID holdId,
            @RequestBody AcknowledgeRequest request) {
        return policies.acknowledge(
                requireUserId(principal), request.applicationId(), releaseId, holdId, request.reason());
    }

    /**
     * The release's evaluation log (AC5): every promote attempt's
     * policy evaluation, newest first — the verdict, the winning
     * rule, and every per-rule outcome in the order they were
     * evaluated, each with its evidence. Requires {@code
     * app:releases:read}.
     */
    @GetMapping("/api/v1/releases/{releaseId}/policy-evaluations")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    EvaluationsView evaluations(
            @AuthenticationPrincipal Object principal, @PathVariable UUID releaseId, @RequestParam UUID applicationId) {
        return policies.evaluations(requireUserId(principal), applicationId, releaseId);
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new ReleaseException(ReleaseException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The rule create/replace body: the name, the scope and its
     * reference, the action, the typed condition tree, the evaluation
     * position, and the enabled standing.
     */
    @Schema(
            description = "The policy rule body: name, scope and its reference, action (BLOCK, HOLD_FOR_REVIEW, "
                    + "ALLOW), the typed condition tree, the evaluation position, and the enabled standing.")
    public record RuleRequest(
            UUID organizationId,
            String name,
            String scope,
            UUID applicationId,
            String ring,
            UUID environmentId,
            String action,
            Map<String, Object> condition,
            Integer position,
            Boolean enabled) {}

    /** The skip-allowances body: the whole replacement set of ring codes. */
    @Schema(description = "The skip-allowances body: the whole replacement set of ring codes.")
    public record SkipAllowancesRequest(UUID organizationId, List<String> rings) {}

    /** The acknowledge body: the permission-target application id and the MANDATORY reason. */
    @Schema(description = "The policy-hold acknowledge body: the application id and the mandatory reason.")
    public record AcknowledgeRequest(UUID applicationId, String reason) {}
}
