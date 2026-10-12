package com.shipperdog.api.authorization;

import com.shipperdog.api.authorization.internal.abac.AbacPolicyService;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyService.PolicyCommand;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyService.PolicyView;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyService.VersionView;
import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.UUID;
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
 * The ABAC policy API (issue #70, S14-04, PRD 25/38): the
 * organization-grain policy store an organization's governance
 * personas author and every access decision evaluates — {@code
 * org:abac-policies:manage/read} at the organization the
 * body/query carries (the release-policy routes' own shape). The
 * routes are the console editor's backend: the versioned current
 * standing, the immutable version history, and the write surfaces
 * whose validations (the typed condition tree, the selectors, the
 * action/attribute pairing) render inline in the editor.
 */
@RestController
public class AbacPolicyController {

    private final AbacPolicyService policies;

    AbacPolicyController(AbacPolicyService policies) {
        this.policies = policies;
    }

    /**
     * The organization's policies in the DEFINED evaluation order
     * (position, then name): the console editor's list. Standard
     * limit/cursor pagination.
     */
    @GetMapping("/api/v1/abac-policies")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:abac-policies:read')")
    PageResponse<PolicyView> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        org.springframework.data.domain.Page<PolicyView> page =
                policies.list(requireUserId(principal), organizationId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * One policy read back with its condition and the human summary.
     * The {@code organizationId} query parameter carries the
     * permission target; a policy of another organization conceals
     * exactly like an unknown id.
     */
    @GetMapping("/api/v1/abac-policies/{policyId}")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:abac-policies:read')")
    PolicyView get(
            @AuthenticationPrincipal Object principal, @PathVariable UUID policyId, @RequestParam UUID organizationId) {
        return policies.get(requireUserId(principal), organizationId, policyId);
    }

    /**
     * The policy's immutable version history (AC4), newest first:
     * every authored version with its complete facts, the author, and
     * the moment. Rows survive the policy's deletion — the loose-key
     * reads stay valid for the organization that owns them.
     */
    @GetMapping("/api/v1/abac-policies/{policyId}/versions")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:abac-policies:read')")
    PageResponse<VersionView> history(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID policyId,
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        org.springframework.data.domain.Page<VersionView> page =
                policies.history(requireUserId(principal), organizationId, policyId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * Creates a policy (AC4): the name, the effect (DENY or REQUIRE
     * — both only narrow), the governing action, the optional
     * selectors, the typed condition tree, the evaluation position,
     * and the enabled standing. Validations refuse before any write
     * — the condition must parse, the action/attribute pairing must
     * be sound, the id selectors must resolve to this organization —
     * the console editor renders exactly these errors. One name per
     * organization (the precise 409). Audited; born at version 1.
     * Honors {@code Idempotency-Key}.
     */
    @PostMapping("/api/v1/abac-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.organizationId(), 'organization', 'org:abac-policies:manage')")
    PolicyView create(@AuthenticationPrincipal Object principal, @RequestBody PolicyRequest request) {
        return policies.create(requireUserId(principal), commandOf(request));
    }

    /**
     * Replaces a policy (the editor's save): the policy's meaning end
     * to end, revalidated as a whole — a half-edited policy never
     * reaches storage — and VERSIONED: the current row moves to the
     * new version and the immutable history row lands beside it.
     * Audited with the changed facts. Honors {@code Idempotency-Key}.
     */
    @PutMapping("/api/v1/abac-policies/{policyId}")
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:abac-policies:manage')")
    PolicyView update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID policyId,
            @RequestParam UUID organizationId,
            @RequestBody PolicyRequest request) {
        return policies.update(requireUserId(principal), organizationId, policyId, commandOf(request));
    }

    /**
     * Deletes a policy: the current row goes, the immutable version
     * history stays (history is the data), and the next decision sees
     * the policy gone (the write-through cache drop). Audited with
     * the full deleted facts.
     */
    @DeleteMapping("/api/v1/abac-policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:abac-policies:manage')")
    void delete(
            @AuthenticationPrincipal Object principal, @PathVariable UUID policyId, @RequestParam UUID organizationId) {
        policies.delete(requireUserId(principal), organizationId, policyId);
    }

    private static PolicyCommand commandOf(PolicyRequest request) {
        return new PolicyCommand(
                request.organizationId(),
                request.name(),
                request.effect(),
                request.action(),
                request.applicationId(),
                request.environmentId(),
                request.ring(),
                request.condition(),
                request.position(),
                request.enabled());
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new AbacPolicyException(AbacPolicyException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The policy create/replace body: the name, the effect, the
     * governing action, the optional selectors, the typed condition
     * tree, the evaluation position, and the enabled standing.
     */
    @Schema(
            description = "The ABAC policy body: name, effect (DENY, REQUIRE), action "
                    + "(LIST_APPLICATIONS, READ_ARTIFACT, DOWNLOAD, PROMOTE), optional application/environment/ring "
                    + "selectors, the typed condition tree, the evaluation position, and the enabled standing.")
    public record PolicyRequest(
            UUID organizationId,
            String name,
            String effect,
            String action,
            UUID applicationId,
            UUID environmentId,
            String ring,
            Map<String, Object> condition,
            Integer position,
            Boolean enabled) {}
}
