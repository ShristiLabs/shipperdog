package com.shipperdog.api.authorization.internal.evaluator;

import com.shipperdog.api.authorization.Permission;
import com.shipperdog.api.authorization.RolePermissions;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.tenant.TenantRole;
import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

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
 * <p>Denials carry no detail — the handler maps every denial to the same
 * opaque 403 (PRD 12).
 */
@Component
public class RbacPermissionEvaluator implements PermissionEvaluator {

    private final RequestRoleGrants grants;

    RbacPermissionEvaluator(RequestRoleGrants grants) {
        this.grants = grants;
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
