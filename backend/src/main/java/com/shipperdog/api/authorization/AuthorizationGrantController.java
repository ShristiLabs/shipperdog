package com.shipperdog.api.authorization;

import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantService;
import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * Authorization-grant administration API (issue #31, S7-02,
 * FR-011/PRD 13): the four grant shapes' CRUD plus the effective-
 * access view, riding the tenant path
 * ({@code /api/v1/tenants/{id}/authorization-grants}) where the
 * route interceptor binds the tenant context the isolation seam
 * enforces for every grant read and write.
 *
 * <p>Permissions: {@code tenant:authorization-grants:read} and
 * {@code tenant:authorization-grants:manage} (new with this story) —
 * held by TENANT_ADMIN and, through the owning-organization overlay,
 * ORG_ADMIN and APP_OWNER (PRD 26's tenant-admin / app-owner grant
 * authority). The routes are administration surface (RBAC-guarded),
 * not resource serving — the {@code @DecisionGuarded} contract does
 * not apply; the GRANTS feed the layered engine through the
 * registered entitlement contributor instead. Resource-scoped
 * denials render as the concealed 404 a genuine miss produces
 * (PRD 12). Listing pages with the platform cursor convention;
 * creation honors {@code Idempotency-Key}; every mutation carries a
 * mandatory reason (audited with the actor, issue #31 acceptance
 * criterion 7).
 *
 * <p>Duplicate semantics: the same grant (tester, application,
 * shape, target) replays the stored row {@code 200 created=false}
 * (idempotent, no write, no audit); the same identity with a
 * different expiry answers {@code 409 AUTHORIZATION_GRANT_CONFLICT}
 * — renewal is the expiry route's job.
 */
@RestController
public class AuthorizationGrantController {

    private final VersionAccessGrantService grants;

    AuthorizationGrantController(VersionAccessGrantService grants) {
        this.grants = grants;
    }

    /**
     * Creates a grant (acceptance criteria 1-4): one of the four
     * FR-011 shapes — {@code EXPLICIT_VERSION} (carries
     * {@code versionId}), {@code APPLICATION} (carries no target),
     * {@code TAG} (carries {@code tag}), {@code LATEST_N} (carries
     * {@code latestN} 1..100) — with optional {@code expiresAt}
     * (null = never expires; enforced at evaluation time) and the
     * mandatory audit {@code reason}.
     */
    @PostMapping("/api/v1/tenants/{id}/authorization-grants")
    @Idempotent
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:authorization-grants:manage')")
    ResponseEntity<GrantCreatedResponse> create(
            @AuthenticationPrincipal Object principal, @PathVariable UUID id, @RequestBody CreateRequest request) {
        VersionAccessGrantService.CreateResult result = grants.create(
                requireUserId(principal),
                id,
                new VersionAccessGrantService.CreateCommand(
                        request.testerId(),
                        request.applicationId(),
                        request.accessType(),
                        request.versionId(),
                        request.tag(),
                        request.latestN(),
                        parseInstant(request.expiresAt()),
                        request.reason()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new GrantCreatedResponse(result.view(), result.created()));
    }

    /**
     * The tenant's grants, creation-ordered, cursor-paginated;
     * optional {@code testerId} / {@code applicationId} filters.
     */
    @GetMapping("/api/v1/tenants/{id}/authorization-grants")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:authorization-grants:read')")
    PageResponse<VersionAccessGrantService.GrantView> list(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID testerId,
            @RequestParam(required = false) UUID applicationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<VersionAccessGrantService.GrantView> page =
                grants.list(id, testerId, applicationId, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /** One grant inside the caller's tenant context. */
    @GetMapping("/api/v1/tenants/{id}/authorization-grants/{grantId}")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:authorization-grants:read')")
    VersionAccessGrantService.GrantView get(
            @AuthenticationPrincipal Object principal, @PathVariable UUID id, @PathVariable UUID grantId) {
        return grants.get(id, grantId);
    }

    /**
     * The effective-access view (acceptance criteria 5 and 6): every
     * version the tester's live grants cover for the application —
     * union-composed, newest first, each entry with its contributing
     * grants and TTL — plus the live two-switches flag.
     */
    @GetMapping("/api/v1/tenants/{id}/authorization-grants/effective")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:authorization-grants:read')")
    VersionAccessGrantService.EffectiveAccessView effective(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestParam UUID testerId,
            @RequestParam UUID applicationId) {
        return grants.effective(id, testerId, applicationId);
    }

    /**
     * Changes a grant's expiry (acceptance criterion 5's TTL
     * administration): extend, shorten, or clear to never-expires
     * ({@code "expiresAt": null}); idempotent for the same value.
     */
    @PutMapping("/api/v1/tenants/{id}/authorization-grants/{grantId}/expiry")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:authorization-grants:manage')")
    VersionAccessGrantService.GrantView changeExpiry(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @PathVariable UUID grantId,
            @RequestBody ExpiryRequest request) {
        return grants.changeExpiry(
                requireUserId(principal), id, grantId, parseInstant(request.expiresAt()), request.reason());
    }

    /**
     * Removes a grant: the row is deleted, the removal audited with
     * actor + reason (the audit trail is the history; S7-04 builds
     * revocation flows above this primitive).
     */
    @DeleteMapping("/api/v1/tenants/{id}/authorization-grants/{grantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:authorization-grants:manage')")
    void delete(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @PathVariable UUID grantId,
            @RequestParam(required = false) String reason) {
        grants.delete(requireUserId(principal), id, grantId, reason);
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new AuthorizationGrantException(
                AuthorizationGrantException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new AuthorizationGrantException(
                    AuthorizationGrantException.Code.INVALID_REQUEST, "expiresAt must be an ISO-8601 instant");
        }
    }

    /** Create request: the shape, its target, optional expiry, mandatory reason. */
    public record CreateRequest(
            UUID testerId,
            UUID applicationId,
            String accessType,
            UUID versionId,
            String tag,
            Integer latestN,
            String expiresAt,
            String reason) {}

    /** Create outcome: the grant view and the created/replayed flag. */
    public record GrantCreatedResponse(VersionAccessGrantService.GrantView grant, boolean created) {}

    /** Expiry change request: the next expiry (null = never expires) and the reason. */
    public record ExpiryRequest(String expiresAt, String reason) {}
}
