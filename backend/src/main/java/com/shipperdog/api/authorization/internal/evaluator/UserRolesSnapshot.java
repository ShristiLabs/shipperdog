package com.shipperdog.api.authorization.internal.evaluator;

import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantRole;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The decision inputs of one user's RBAC evaluation (issue #5): platform
 * flag, roles held in ACTIVE organizations, and roles held in tenants.
 * Kept as an explicit, inspectable object because this is the seam where
 * the ABAC engine (S14-04) later extends decision inputs with resource
 * attributes.
 *
 * <p>Roles in DEACTIVATED organizations are excluded at load time, so
 * deactivation strips effective permissions without touching
 * memberships.
 */
final class UserRolesSnapshot {

    private final UUID userId;
    private final boolean platformAdmin;
    private final Map<UUID, OrgRole> orgRoles;
    private final Map<UUID, TenantRole> tenantRoles;
    private final TenantAccess tenants;

    /** Tenant-to-owning-organization resolutions memoized per request. */
    private final Map<UUID, Optional<UUID>> tenantOwners = new ConcurrentHashMap<>();

    UserRolesSnapshot(
            UUID userId,
            boolean platformAdmin,
            Map<UUID, OrgRole> orgRoles,
            Map<UUID, TenantRole> tenantRoles,
            TenantAccess tenants) {
        this.userId = userId;
        this.platformAdmin = platformAdmin;
        this.orgRoles = Map.copyOf(orgRoles);
        this.tenantRoles = Map.copyOf(tenantRoles);
        this.tenants = tenants;
    }

    UUID userId() {
        return userId;
    }

    boolean platformAdmin() {
        return platformAdmin;
    }

    OrgRole orgRole(UUID organizationId) {
        return orgRoles.get(organizationId);
    }

    TenantRole tenantRole(UUID tenantId) {
        return tenantRoles.get(tenantId);
    }

    /** The organization owning the tenant, memoized; empty when unknown. */
    Optional<UUID> owningOrganization(UUID tenantId) {
        return tenantOwners.computeIfAbsent(tenantId, tenants::owningOrganizationId);
    }
}
