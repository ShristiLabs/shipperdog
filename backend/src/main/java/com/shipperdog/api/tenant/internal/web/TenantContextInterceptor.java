package com.shipperdog.api.tenant.internal.web;

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
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Binds the {@link TenantContext} for tenant-scoped routes (INV-001,
 * issue #10, PRD 26: request context before repository query). Routes
 * that address a tenant directly — {@code /api/v1/tenants/{id}} and
 * everything beneath — carry the tenant in the path; the interceptor
 * resolves it, records the actor, and the repository seam filters every
 * tenant-scoped query to that tenant for the rest of the request.
 * {@code afterCompletion} clears the context on every outcome, so the
 * thread-local never outlives the request.
 *
 * <p>The platform-administrator override is set only from the
 * {@code users.platform_admin} flag — organization roles can never
 * reach it — and each such admission is audited as
 * {@code CROSS_TENANT_ACCESS}, making the cross-tenant admin path
 * explicit and traceable (issue #10, acceptance criterion 4).
 */
public class TenantContextInterceptor implements HandlerInterceptor {

    /** Tenant-addressing routes; the collection routes are organization-scoped. */
    private static final Pattern TENANT_ROUTE = Pattern.compile(
            "^/api/v1/tenants/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");

    private final PlatformAdmins platformAdmins;
    private final AuditLogger audit;

    public TenantContextInterceptor(PlatformAdmins platformAdmins, AuditLogger audit) {
        this.platformAdmins = platformAdmins;
        this.audit = audit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        Matcher route = TENANT_ROUTE.matcher(uri);
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
        UUID tenantId = UUID.fromString(route.group(1));
        boolean platformAdmin = platformAdmins.isPlatformAdmin(actor);
        if (platformAdmin) {
            audit.record(AuditEvent.builder(AuditEventType.CROSS_TENANT_ACCESS)
                    .userId(actor)
                    .tenantId(tenantId)
                    .success(true)
                    .detail(detailOf(request))
                    .build());
        }
        // Bind last: if anything above threw, preHandle never completed and
        // Spring skips afterCompletion for this interceptor, so no context
        // may exist yet — a leaked thread-local on a pooled thread would
        // mis-scope the next request.
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
