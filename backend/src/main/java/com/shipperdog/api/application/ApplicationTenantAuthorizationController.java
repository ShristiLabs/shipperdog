package com.shipperdog.api.application;

import com.shipperdog.api.application.internal.service.TenantApplicationAuthorizationService;
import com.shipperdog.api.application.internal.service.TenantApplicationAuthorizationService.AuthorizationGrant;
import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The shared-application tenant authorization API (issue #15, S3-03,
 * PRD 15): {@code POST /applications/{id}/tenants/{tenantId}} authorizes
 * a tenant for an organization-shared application, {@code DELETE}
 * de-authorizes it (the soft, auditable flip), and {@code GET
 * /applications/{id}/tenants} lists the authorized tenants — the
 * {@code Banking 7.12.0 -> Acme YES, Contoso YES, Delta NO} list.
 *
 * <p>The routes address an application, so the application interceptor
 * binds the owning organization's scope for shared applications and the
 * seam filters the application load; {@code app:} permissions resolve
 * through the application's owner — organization-shared rows check the
 * owning organization, so only organization-level roles
 * (ORG_ADMIN, APP_OWNER) manage the mapping and authorized tenants
 * never can (their surface is the tenant listing; resource-scoped
 * denials render as concealed 404s, PRD 12 — an unauthorized tenant
 * learns nothing about the application, AC2). Both write operations
 * accept an optional reason, captured on the audit event (AC5). The
 * POST honors {@code Idempotency-Key} and is idempotent by state (an
 * already-authorized tenant is a success without side effects).
 */
@RestController
@RequestMapping("/api/v1/applications/{id}/tenants")
public class ApplicationTenantAuthorizationController {

    private final TenantApplicationAuthorizationService authorizations;

    ApplicationTenantAuthorizationController(TenantApplicationAuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @PostMapping("/{tenantId}")
    @Idempotent
    @PreAuthorize("hasPermission(#id, 'application', 'app:applications:write')")
    ResponseEntity<TenantApplicationAuthorizationService.AuthorizationView> authorize(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @PathVariable UUID tenantId,
            @RequestBody(required = false) ReasonRequest request) {
        AuthorizationGrant grant = authorizations.authorize(requireUserId(principal), id, tenantId, reasonOf(request));
        return ResponseEntity.status(grant.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(grant.view());
    }

    @DeleteMapping("/{tenantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#id, 'application', 'app:applications:write')")
    void deauthorize(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @PathVariable UUID tenantId,
            @RequestBody(required = false) ReasonRequest request) {
        authorizations.deauthorize(requireUserId(principal), id, tenantId, reasonOf(request));
    }

    @GetMapping
    @PreAuthorize("hasPermission(#id, 'application', 'app:applications:read')")
    PageResponse<TenantApplicationAuthorizationService.AuthorizationView> list(
            @PathVariable UUID id,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        Page<TenantApplicationAuthorizationService.AuthorizationView> page =
                authorizations.list(id, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    private static String reasonOf(ReasonRequest request) {
        return request == null ? null : TenantApplicationAuthorizationService.optionalReason(request.reason());
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new ApplicationException(ApplicationException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The optional grant/revocation motivation (AC5): captured on the
     * audit event's reason field, never stored on the row — the audit
     * trail is the compliance record.
     */
    public record ReasonRequest(String reason) {}
}
