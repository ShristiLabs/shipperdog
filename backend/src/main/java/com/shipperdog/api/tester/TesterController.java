package com.shipperdog.api.tester;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.tester.internal.entity.TesterEntity;
import com.shipperdog.api.tester.internal.service.TesterService;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tester onboarding and lifecycle API (issues #26/#29, S6-01/S6-04,
 * FR-010): organization administrators invite testers by email, the
 * invitee self-onboards through the platform registration endpoint,
 * and the INVITED/ACTIVE/SUSPENDED/DISABLED lifecycle is enforced and
 * audited — DISABLED being S6-04's deactivation/revocation posture
 * (mandatory reason, membership cascade, session kill). The
 * once-shown invitation token (plus its ready-made accept URL) is
 * returned to the inviting administrator — email transport is the
 * notification seam's business (S12), and the S1-03 dev-mode delivery
 * decision applies: the link is visible to the inviter and logged by
 * the default sender.
 */
@RestController
@RequestMapping("/api/v1/testers")
public class TesterController {

    private final TesterService testers;

    TesterController(TesterService testers) {
        this.testers = testers;
    }

    @PostMapping
    @Idempotent
    @PreAuthorize("hasPermission(#request.organizationId(), 'organization', 'org:testers:invite')")
    ResponseEntity<TesterResponse> create(
            @AuthenticationPrincipal Object principal, @RequestBody CreateRequest request) {
        TesterService.CreatedTester created =
                testers.create(requireUserId(principal), request.organizationId(), request.email(), request.fullName());
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(TesterResponse.from(created.tester(), created.invitation()));
    }

    @GetMapping
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:testers:read')")
    PageResponse<TesterResponse> list(
            @AuthenticationPrincipal Object principal,
            @RequestParam UUID organizationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<TesterResponse> page = testers.list(organizationId, status, query.offset(), query.limit())
                .map(tester -> TesterResponse.from(tester, null));
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'tester', 'org:testers:read')")
    TesterResponse get(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        return TesterResponse.from(testers.get(id), null);
    }

    @PostMapping("/{id}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(#id, 'tester', 'org:testers:invite')")
    ReinviteResponse reinvite(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        TesterService.InvitationView invitation = testers.reinvite(requireUserId(principal), id);
        return new ReinviteResponse(
                invitation.status(), invitation.invitationToken(), invitation.acceptUrl(), invitation.expiresAt());
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasPermission(#id, 'tester', 'org:testers:manage')")
    TesterResponse changeStatus(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestBody TesterStatusChangeRequest request) {
        return TesterResponse.from(
                testers.changeStatus(requireUserId(principal), id, parseStatus(request.status()), request.reason()),
                null);
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new TesterException(TesterException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    private static TesterEntity.Status parseStatus(String status) {
        if (status == null) {
            throw new TesterException(TesterException.Code.INVALID_REQUEST, "status is required");
        }
        try {
            return TesterEntity.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new TesterException(TesterException.Code.INVALID_REQUEST, "unknown status: " + status.trim());
        }
    }

    /** Creation request: the tester person record fields (issue #26). */
    public record CreateRequest(UUID organizationId, String email, String fullName) {}

    /**
     * Status change request: ACTIVE, SUSPENDED, or DISABLED (lifecycle
     * rules in service). {@code reason} is mandatory for DISABLED — the
     * deactivation audit trail — and optional (recorded when present)
     * elsewhere.
     */
    public record TesterStatusChangeRequest(String status, String reason) {}

    /**
     * Tester view. On the created paths the response also carries the
     * invitation block; its {@code invitationToken} and
     * {@code acceptUrl} are shown exactly once.
     */
    public record TesterResponse(
            UUID id,
            String email,
            String fullName,
            String status,
            UUID userId,
            UUID organizationId,
            Instant createdAt,
            Instant updatedAt,
            Map<String, Object> invitation) {

        static TesterResponse from(TesterEntity tester, Map<String, Object> invitation) {
            return new TesterResponse(
                    tester.getId(),
                    tester.getEmail(),
                    tester.getFullName(),
                    tester.getStatus().name(),
                    tester.getUserId(),
                    tester.getOrganizationId(),
                    tester.getCreatedAt(),
                    tester.getUpdatedAt(),
                    invitation);
        }
    }

    /** Re-invite result; the token is shown exactly once. */
    public record ReinviteResponse(String status, String invitationToken, String acceptUrl, Instant expiresAt) {}
}
