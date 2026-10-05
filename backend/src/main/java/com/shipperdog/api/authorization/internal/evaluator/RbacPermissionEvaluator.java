package com.shipperdog.api.authorization.internal.evaluator;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.authorization.ConcealedResourceAccessDeniedException;
import com.shipperdog.api.authorization.Permission;
import com.shipperdog.api.authorization.RolePermissions;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.tenant.TenantRole;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The single enforcement point of the RBAC engine (issue #5). Controller
 * methods declare permissions with {@code @PreAuthorize("hasPermission(
 * #targetId, 'scope', 'permission-code')")}; Spring Security's method
 * interception routes every check here.
 *
 * <p>Evaluation is deny-by-default and live: roles resolve from database
 * memberships on the request's first check (memoized by
 * {@link RequestRoleGrants}), never from token claims, so role changes
 * apply immediately. Decision inputs stay inspectable in
 * {@link UserRolesSnapshot} — the seam the ABAC engine (S14-04) extends
 * with resource attributes.
 *
 * <p>Scope resolution:
 * <ul>
 *   <li>{@code platform:} — platform administrators only
 *       ({@code users.platform_admin}).</li>
 *   <li>{@code org:} — the caller's role in the target organization
 *       grants the permission per {@link RolePermissions}.</li>
 *   <li>{@code tenant:} — the caller's role in the target tenant, with
 *       the owning organization's role overlaid (union across scopes,
 *       acceptance criterion 4): an organization administrator holds
 *       tenant permissions inside their organization's tenants.</li>
 *   <li>{@code app:} — denied fail-closed until application records are
 *       manageable (E3) and ownership resolution exists; platform
 *       administrators still pass.</li>
 * </ul>
 *
 * <p>Denials carry no HTTP detail (PRD 12): resource-scoped denials throw
 * {@link ConcealedResourceAccessDeniedException} and render as the same
 * 404-shaped envelope a genuine miss produces; target-less permission
 * denials stay one opaque 403. The audit trail, not the response, carries
 * the who/what.
 */
@Component
public class RbacPermissionEvaluator implements PermissionEvaluator {

    private final RequestRoleGrants grants;
    private final AuditLogger audit;
    private final MeterRegistry meters;
    private final TraceAttributes trace;

    RbacPermissionEvaluator(RequestRoleGrants grants, AuditLogger audit, MeterRegistry meters, TraceAttributes trace) {
        this.grants = grants;
        this.audit = audit;
        this.meters = meters;
        this.trace = trace;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        // Only the id-based form is used by this codebase; anything else
        // is a programming slip and stays denied (deny-by-default).
        return false;
    }

    @Override
    public boolean hasPermission(
            Authentication authentication, Serializable targetId, String targetType, Object permission) {
        // Entity context for the request's trace (issue #7): the guarded
        // target is exactly the organization/tenant the request operates
        // on, so the span carries it without every controller repeating
        // itself. Ids only, never names (PRD section 28).
        if (targetId instanceof UUID id) {
            if ("organization".equals(targetType)) {
                trace.organization(id);
            } else if ("tenant".equals(targetType)) {
                trace.tenant(id);
            }
        }
        // SLO measurability from day one (PRD sections 28 and 42):
        // authorization_decision_duration, tagged by outcome so the
        // under-100 ms target is observable per decision path. Only the
        // decision itself is timed — the denial's audit write happens
        // after the clock stops so it cannot skew the SLO signal.
        boolean allowed = false;
        Timer.Sample sample = Timer.start(meters);
        try {
            allowed = decide(authentication, targetId, permission);
        } finally {
            sample.stop(Timer.builder(MetricNames.AUTHORIZATION_DECISION_DURATION)
                    .tag("outcome", allowed ? "allow" : "deny")
                    .register(meters));
        }
        if (!allowed && authentication != null && authentication.isAuthenticated()) {
            userIdOf(authentication).ifPresent(actor -> recordDenial(actor, permission));
            if (targetId instanceof UUID) {
                // PRD 12 / issue #8: a denial on a specific resource must
                // not reveal that the resource exists — render the same
                // 404-shaped envelope a genuine miss produces. Target-less
                // permission checks (creation rights) stay opaque 403s.
                throw new ConcealedResourceAccessDeniedException(targetType);
            }
        }
        return allowed;
    }

    private boolean decide(Authentication authentication, Serializable targetId, Object permission) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        UUID userId = userIdOf(authentication).orElse(null);
        if (userId == null) {
            return false;
        }
        Permission required =
                permission instanceof String code ? Permission.fromCode(code).orElse(null) : null;
        if (required == null) {
            return false;
        }
        UserRolesSnapshot snapshot = grants.snapshot(userId);
        if (snapshot.platformAdmin()) {
            // PRD 7.1 wildcard: platform administrators hold every
            // permission by construction (documented in the matrix).
            return RolePermissions.platformAdminPasses(required);
        }
        UUID scopeId = targetId instanceof UUID id
                ? id
                : targetId instanceof String text && !text.isBlank() ? parseUuid(text) : null;
        return switch (required.scope()) {
            case "platform" -> false; // only the platform wildcard above
            case "org" -> scopeId != null && orgGrants(snapshot, scopeId, required);
            case "tenant" -> scopeId != null && tenantGrants(snapshot, scopeId, required);
            default -> false; // app scope: fail-closed until E3
        };
    }

    /**
     * Opaque 403s (PRD 12) leave no HTTP trace of what was denied, so the
     * audit trail is the only record (issue #6): every authenticated
     * permission denial emits an ACCESS_DENIED event carrying the actor,
     * the required permission, and the attempted endpoint. Unauthenticated
     * attempts are 401s at the filter chain and are not audited here.
     */
    private void recordDenial(UUID actor, Object permission) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("permission", permission == null ? "unknown" : permission.toString());
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            detail.put("method", attributes.getRequest().getMethod());
            detail.put("uri", attributes.getRequest().getRequestURI());
        }
        audit.record(AuditEvent.builder(AuditEventType.ACCESS_DENIED)
                .userId(actor)
                .success(false)
                .detail(detail)
                .build());
    }

    private boolean orgGrants(UserRolesSnapshot snapshot, UUID organizationId, Permission required) {
        OrgRole role = snapshot.orgRole(organizationId);
        return role != null && RolePermissions.of(role).contains(required);
    }

    private boolean tenantGrants(UserRolesSnapshot snapshot, UUID tenantId, Permission required) {
        TenantRole tenantRole = snapshot.tenantRole(tenantId);
        if (tenantRole != null && RolePermissions.of(tenantRole).contains(required)) {
            return true;
        }
        // Union across scopes: the owning organization's role also grants
        // tenant permissions inside that organization's tenants (e.g.
        // ORG_ADMIN holds tenant:members:manage per the matrix).
        return snapshot.owningOrganization(tenantId)
                .map(snapshot::orgRole)
                .map(role -> role != null && RolePermissions.of(role).contains(required))
                .orElse(false);
    }

    private static Optional<UUID> userIdOf(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            return Optional.ofNullable(parseUuid(jwt.getSubject()));
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return Optional.of(patPrincipal.userId());
        }
        return Optional.empty();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
