package com.shipperdog.api.release;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.release.internal.service.RingEligibilityService;
import com.shipperdog.api.release.internal.service.RingEligibilityService.EligibilityView;
import com.shipperdog.api.release.internal.service.RingService;
import com.shipperdog.api.release.internal.service.RingService.CreateCommand;
import com.shipperdog.api.release.internal.service.RingService.RingView;
import com.shipperdog.api.release.internal.service.RingService.UpdateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ring API (issue #43, S10-04, FR-017, PRD 8.10/33): two surfaces
 * in one controller, the gate module's standing. The ring routes are
 * organization-grain ({@code org:rings:manage/read}; the permission
 * target is the organization the body/query carries) — the effective
 * vocabulary, and the CRUD an Organization Admin customizes it with
 * (add, rename, reorder, re-rule; Production last enforced; every
 * write audited). The release-addressed eligibility route carries the
 * application in the query, the release surface's own
 * permission-target shape ({@code app:releases:read}): the console
 * renders who the release's current rings admit, and the optional
 * {@code testerId} answers the ring-visibility decision for one
 * tester — the same arithmetic the portal consumes through the seam.
 */
@RestController
public class RingController {

    private final RingService rings;
    private final RingEligibilityService eligibility;

    RingController(RingService rings, RingEligibilityService eligibility) {
        this.rings = rings;
        this.eligibility = eligibility;
    }

    // -------------------------------------------------------------------------
    // The ring vocabulary (AC1): list, detail, create, edit, delete
    // -------------------------------------------------------------------------

    /**
     * The organization's effective ring vocabulary (AC1): its own rings
     * plus every system default it has not redefined under the same
     * code, in progressive order — Production last. Each ring carries
     * its PRD 8.10 properties and its gate set.
     */
    @GetMapping("/api/v1/rings")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:rings:read')")
    PageResponse<RingView> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        org.springframework.data.domain.Page<RingView> page =
                rings.list(requireUserId(principal), organizationId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /**
     * One ring of the organization's vocabulary: the ring read back
     * with its gate set. The {@code organizationId} query parameter
     * carries the permission target (the body-addressed shape); a ring
     * of another organization conceals exactly like an unknown id.
     */
    @GetMapping("/api/v1/rings/{ringId}")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:rings:read')")
    RingView get(
            @AuthenticationPrincipal Object principal, @PathVariable UUID ringId, @RequestParam UUID organizationId) {
        return rings.get(requireUserId(principal), organizationId, ringId);
    }

    /**
     * Creates an organization ring (AC1): a new code of the
     * organization's own, or a SYSTEM code — the shadow that redefines
     * the code's meaning for the organization. Production-last and the
     * production governance pair (the approval-policy reference, the
     * required gate set) validate before any write; the write is
     * audited. Honors {@code Idempotency-Key}.
     */
    @PostMapping("/api/v1/rings")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#request.organizationId(), 'organization', 'org:rings:manage')")
    RingView create(@AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        return rings.create(
                requireUserId(principal),
                new CreateCommand(
                        request.organizationId(),
                        request.code(),
                        request.name(),
                        request.position(),
                        request.eligibility(),
                        request.approvalPolicy(),
                        request.promotionPolicy(),
                        request.distributionChannel()));
    }

    /**
     * Edits an organization ring (AC1: rename, reorder, re-rule):
     * absent fields keep their standing; an empty {@code eligibility}
     * object CLEARS the rules. The code never moves. System rings
     * refuse ({@code RING_SYSTEM_IMMUTABLE}) — customize by shadowing.
     * Audited. Honors {@code Idempotency-Key}.
     */
    @PatchMapping("/api/v1/rings/{ringId}")
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:rings:manage')")
    RingView update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID ringId,
            @RequestParam UUID organizationId,
            @RequestBody UpdateRequest request) {
        return rings.update(
                requireUserId(principal),
                organizationId,
                ringId,
                new UpdateCommand(
                        request.name(),
                        request.position(),
                        request.eligibility(),
                        request.approvalPolicy(),
                        request.promotionPolicy(),
                        request.distributionChannel()));
    }

    /**
     * Deletes an organization ring: a ring referenced by releases,
     * targets, gate definitions, or approval workflows refuses with
     * {@code RING_IN_USE} — reference data that changes who can
     * receive what is never silently unlinked. System rings refuse.
     * Audited with the full deleted facts.
     */
    @DeleteMapping("/api/v1/rings/{ringId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:rings:manage')")
    void delete(
            @AuthenticationPrincipal Object principal, @PathVariable UUID ringId, @RequestParam UUID organizationId) {
        rings.delete(requireUserId(principal), organizationId, ringId);
    }

    // -------------------------------------------------------------------------
    // Release eligibility (AC2/AC4): the admission read
    // -------------------------------------------------------------------------

    /**
     * The release's ring eligibility (AC2): every release target with
     * its current ring, whether that ring's rules admit it, and the
     * audience distribution narrows to — the release targets INTERSECT
     * ring eligibility arithmetic, inspectable. The optional
     * {@code testerId} adds the ring-visibility decision for one
     * tester (AC4): whether the current rings admit them (the E7
     * grants compose at distribution time and are not consulted here).
     */
    @GetMapping("/api/v1/releases/{releaseId}/eligibility")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:releases:read')")
    EligibilityView eligibility(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID releaseId,
            @RequestParam UUID applicationId,
            @RequestParam(required = false) UUID testerId) {
        return eligibility.eligibility(requireUserId(principal), applicationId, releaseId, testerId);
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new RingException(RingException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The ring creation body (AC1): the owning organization (the
     * permission target), the stable code (lowercase kebab; a SYSTEM
     * code creates the organization's shadow of it), the display name,
     * the progression position (Production last enforced), the
     * optional eligibility rules, and the three references.
     */
    @Schema(
            description = "The ring creation body: organization, code, name, position, the optional eligibility "
                    + "rules, and the approval-policy, promotion-policy, and distribution-channel references.")
    public record CreateRequest(
            UUID organizationId,
            String code,
            String name,
            Integer position,
            Map<String, Object> eligibility,
            String approvalPolicy,
            String promotionPolicy,
            String distributionChannel) {}

    /** The ring edit body: the mutable facts only (the code is the identity); absent fields keep their standing. */
    @Schema(
            description = "The ring edit body: name, position, eligibility (empty object clears), and the three "
                    + "references; the code never moves.")
    public record UpdateRequest(
            String name,
            Integer position,
            Map<String, Object> eligibility,
            String approvalPolicy,
            String promotionPolicy,
            String distributionChannel) {}
}
