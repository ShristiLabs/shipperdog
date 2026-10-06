package com.shipperdog.api.application;

import com.shipperdog.api.application.internal.service.TenantApplicationService;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.AuthorizationService;
import com.shipperdog.api.authorization.ConcealedResourceAccessDeniedException;
import com.shipperdog.api.authorization.DecisionGuarded;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tenant applications listing (issue #11, FR-002 "assign
 * applications" read side, PRD 8.2/15): {@code GET /tenants/{id}/
 * applications} answers which applications the tenant sees — since S3-03
 * (issue #15, AC4) the single effective-visibility resolution: the
 * tenant's owned applications plus the organization-shared applications
 * the tenant is authorized for. The route addresses a tenant, so the
 * tenant module's interceptor binds the tenant context; the permission
 * (tenant:applications:read) keeps the surface read-only for tenant
 * testers while tenant and organization administrators see the same
 * listing. Application management itself (create, edit, archive) is the
 * registry surface (S3-01/S3-02); grant/revoke of the shared-application
 * authorization mapping is {@link ApplicationTenantAuthorizationController}
 * (S3-03).
 *
 * <p>Since S7-01 (issue #30) the listing is {@link DecisionGuarded}:
 * before any data moves, the layered authorization engine decides the
 * subject's context over the tenant (the PRD 11 chain's user,
 * organization, and tenant layers — the application layer's set form
 * IS the sanctioned visibility union the service below runs). A denial
 * renders as the concealed tenant miss, byte-for-byte a genuine
 * {@code TENANT_NOT_FOUND}; the decision itself is audited with the
 * request's correlation id.
 */
@RestController
@RequestMapping("/api/v1/tenants/{id}/applications")
@DecisionGuarded
public class TenantApplicationsController {

    private final TenantApplicationService applications;
    private final AuthorizationService authorization;

    TenantApplicationsController(TenantApplicationService applications, AuthorizationService authorization) {
        this.applications = applications;
        this.authorization = authorization;
    }

    @GetMapping
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:applications:read')")
    PageResponse<TenantApplicationService.ApplicationView> list(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        UUID subject = requireSubject(principal);
        authorization.require(AuthorizationContext.listApplications(subject, id), "tenant");
        ListQuery query = ListQuery.of(limit, cursor);
        Page<TenantApplicationService.ApplicationView> page =
                applications.listVisibleToTenant(id, query.offset(), query.limit());
        return PageResponse.of(page.getContent(), page.hasNext(), query.offset() + query.limit());
    }

    /** An unresolvable subject is a concealed denial, never a 500 (the evaluator's own rule). */
    private static UUID requireSubject(Object principal) {
        UUID userId = userIdOf(principal);
        if (userId == null) {
            throw new ConcealedResourceAccessDeniedException("tenant");
        }
        return userId;
    }

    private static UUID userIdOf(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        return null;
    }
}
