package com.shipperdog.api.tenant.internal.web;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.identity.PlatformAdmins;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.tester.TesterGroupAccess;
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
 * Binds the {@link TenantContext} for tester-group-addressing routes
 * (issue #27, INV-001): {@code /api/v1/tester-groups/{id}} and
 * everything beneath carry a group in the path, and the tenant whose
 * data is in play is the group's owner — resolved through the tester
 * module's {@link TesterGroupAccess} port, the exact division of labor
 * the {@code ApplicationTenantContextInterceptor} established (issue
 * #13). From there this is the tenant module's regular binding:
 * records the actor, audits every platform-administrator admission as
 * {@code CROSS_TENANT_ACCESS}, and lets the repository seam filter
 * every group query to that tenant for the rest of the request.
 * {@code afterCompletion} clears the context on every outcome, so the
 * thread-local never outlives the request.
 *
 * <p>Unknown groups bind nothing: the permission check
 * conceal-denies them. The collection routes
 * ({@code /api/v1/tester-groups} and its {@code tenantId}/{@code
 * testerId} query forms) carry no group in the path — their target
 * scope arrives in the query/body, and the service establishes it
 * through the {@link com.shipperdog.api.tenant.TenantScopeExecutor}
 * (the creation-route precedent).
 */
public class TesterGroupTenantContextInterceptor implements HandlerInterceptor {

    /** Tester-group-addressing item routes; the collection routes carry no id. */
    private static final Pattern TESTER_GROUP_ROUTE = Pattern.compile(
            "^/api/v1/tester-groups/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/.*)?$");

    private final TesterGroupAccess testerGroups;
    private final PlatformAdmins platformAdmins;
    private final AuditLogger audit;

    public TesterGroupTenantContextInterceptor(
            TesterGroupAccess testerGroups, PlatformAdmins platformAdmins, AuditLogger audit) {
        this.testerGroups = testerGroups;
        this.platformAdmins = platformAdmins;
        this.audit = audit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        Matcher route = TESTER_GROUP_ROUTE.matcher(uri);
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
        UUID groupId = UUID.fromString(route.group(1));
        Optional<UUID> tenantId = testerGroups.owningTenantOf(groupId);
        if (tenantId.isEmpty()) {
            // Unknown group: nothing to scope to, and the permission
            // check conceal-denies it.
            return true;
        }
        boolean platformAdmin = platformAdmins.isPlatformAdmin(actor);
        // Bind last: if anything above threw, preHandle never completed
        // and Spring skips afterCompletion for this interceptor, so no
        // context may exist yet — a leaked thread-local on a pooled
        // thread would mis-scope the next request.
        if (platformAdmin) {
            audit.record(AuditEvent.builder(AuditEventType.CROSS_TENANT_ACCESS)
                    .userId(actor)
                    .tenantId(tenantId.get())
                    .success(true)
                    .detail(detailOf(request))
                    .build());
        }
        TenantContext.begin(tenantId.get(), actor, platformAdmin);
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
