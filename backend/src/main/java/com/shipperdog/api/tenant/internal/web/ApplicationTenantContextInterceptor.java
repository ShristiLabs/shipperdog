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
 * <p>Unknown applications and organization-owned applications (null
 * owner tenant — none exist until S3-02) bind nothing: the permission
 * check conceal-denies the former and the read path stays on the seam
 * for the latter's owning story.
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
        if (ownership.isEmpty() || ownership.get().tenantId() == null) {
            // Unknown application, or the organization-owned mode that
            // arrives with S3-02: nothing to scope to. The permission
            // check conceal-denies unknown ids; org-owned reads are
            // S3-02's surface.
            return true;
        }
        UUID tenantId = ownership.get().tenantId();
        boolean platformAdmin = platformAdmins.isPlatformAdmin(actor);
        if (platformAdmin) {
            audit.record(AuditEvent.builder(AuditEventType.CROSS_TENANT_ACCESS)
                    .userId(actor)
                    .tenantId(tenantId)
                    .applicationId(applicationId)
                    .success(true)
                    .detail(detailOf(request))
                    .build());
        }
        // Bind last: if anything above threw, preHandle never completed
        // and Spring skips afterCompletion for this interceptor, so no
        // context may exist yet — a leaked thread-local on a pooled
        // thread would mis-scope the next request.
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
