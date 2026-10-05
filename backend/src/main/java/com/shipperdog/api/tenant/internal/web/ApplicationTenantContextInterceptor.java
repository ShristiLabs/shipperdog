package com.shipperdog.api.tenant.internal.web;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.identity.PlatformAdmins;
import com.shipperdog.api.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Binds the {@link TenantContext} for application-addressing routes
 * (issue #13, INV-001): {@code /api/v1/applications/{id}} and everything
 * beneath carry an application in the path, and the tenant whose data is
 * in play is the application's owner — resolved through the application
 * module's {@link ApplicationAccess} port. From there this interceptor is
 * the tenant module's regular binding: records the actor, audits every
 * platform-administrator admission as {@code CROSS_TENANT_ACCESS}, and
 * lets the repository seam filter every application query to that tenant
 * for the rest of the request. {@code afterCompletion} clears the
 * context on every outcome, so the thread-local never outlives the
 * request.
 *
 * <p>Unknown applications bind nothing: the permission check
 * conceal-denies them. Organization-owned applications (null owner
 * tenant, S3-02/PRD 14) bind the owning organization's scope instead of
 * a tenant's — the seam's organization-scope mode serves those rows for
 * the rest of the request, and the same fail-closed and audit rules
 * apply (ADR-0002 third amendment).
 */
public class ApplicationTenantContextInterceptor implements HandlerInterceptor {

    /** Application-addressing item routes; the collection routes carry no id. */
    private static final Pattern APPLICATION_ROUTE = Pattern.compile(
            "^/api/v1/applications/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");

    private final ApplicationAccess applications;
    private final PlatformAdmins platformAdmins;
    private final AuditLogger audit;

    public ApplicationTenantContextInterceptor(
            ApplicationAccess applications, PlatformAdmins platformAdmins, AuditLogger audit) {
        this.applications = applications;
        this.platformAdmins = platformAdmins;
        this.audit = audit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        Matcher route = APPLICATION_ROUTE.matcher(uri);
        if (!route.matches()) {
            return true;
        }
        UUID actor = actorOf(SecurityContextHolder.getContext().getAuthentication());
        if (actor == null) {
            // Unauthenticated calls die in the security chain before the
            // dispatcher; if one ever reaches here, no context is bound
            // and the seam stays fail-closed.
            return true;
        }
        UUID applicationId = UUID.fromString(route.group(1));
        Optional<ApplicationAccess.Ownership> ownership = applications.ownershipOf(applicationId);
        if (ownership.isEmpty()) {
            // Unknown application: nothing to scope to, and the
            // permission check conceal-denies it.
            return true;
        }
        boolean platformAdmin = platformAdmins.isPlatformAdmin(actor);
        // Bind last: if anything above threw, preHandle never completed
        // and Spring skips afterCompletion for this interceptor, so no
        // context may exist yet — a leaked thread-local on a pooled
        // thread would mis-scope the next request.
        if (ownership.get().tenantId() == null) {
            // Organization-owned (S3-02, PRD 14): scope to the owning
            // organization; the seam serves null-tenant rows of that
            // organization only.
            if (platformAdmin) {
                audit.record(AuditEvent.builder(AuditEventType.CROSS_TENANT_ACCESS)
                        .userId(actor)
                        .orgId(ownership.get().organizationId())
                        .applicationId(applicationId)
                        .success(true)
                        .detail(detailOf(request))
                        .build());
            }
            TenantContext.beginOrganization(ownership.get().organizationId(), actor, platformAdmin);
            return true;
        }
        UUID tenantId = ownership.get().tenantId();
        if (platformAdmin) {
            audit.record(AuditEvent.builder(AuditEventType.CROSS_TENANT_ACCESS)
                    .userId(actor)
                    .tenantId(tenantId)
                    .applicationId(applicationId)
                    .success(true)
                    .detail(detailOf(request))
                    .build());
        }
        TenantContext.begin(tenantId, actor, platformAdmin);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.end();
    }

    /** The acting user id from either principal form; null when anonymous. */
    private static UUID actorOf(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        return null;
    }

    private static Map<String, String> detailOf(HttpServletRequest request) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("method", request.getMethod());
        detail.put("uri", request.getRequestURI());
        return detail;
    }
}
