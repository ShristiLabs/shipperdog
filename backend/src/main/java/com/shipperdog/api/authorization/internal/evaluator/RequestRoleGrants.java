package com.shipperdog.api.authorization.internal.evaluator;

import com.shipperdog.api.identity.PlatformAdmins;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.tenant.TenantAccess;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Loads and memoizes the acting user's {@link UserRolesSnapshot} for the
 * current request (issue #5). Snapshot loading costs three indexed
 * queries (platform flag, org memberships, tenant memberships) and is
 * performed at most once per user per request, so endpoints evaluating
 * several permissions do not turn into N+1 membership lookups. Role data
 * is read live from the database — never from token claims — so role
 * changes take effect immediately.
 */
@Component
@RequestScope
public class RequestRoleGrants {

    private final PlatformAdmins platformAdmins;
    private final OrganizationAccess organizations;
    private final TenantAccess tenants;

    private UserRolesSnapshot cached;

    public RequestRoleGrants(PlatformAdmins platformAdmins, OrganizationAccess organizations, TenantAccess tenants) {
        this.platformAdmins = platformAdmins;
        this.organizations = organizations;
        this.tenants = tenants;
    }

    /**
     * The snapshot for the user, loading it on first use in this
     * request. Public within the module's internal packages: the
     * layered engine (S7-01) resolves the same per-request snapshot
     * the RBAC evaluator does, so one request costs one snapshot
     * however many decisions it makes.
     */
    public UserRolesSnapshot snapshot(UUID userId) {
        UserRolesSnapshot current = cached;
        if (current == null || !current.userId().equals(userId)) {
            current = new UserRolesSnapshot(
                    userId,
                    platformAdmins.isPlatformAdmin(userId),
                    organizations.activeOrganizationRoles(userId),
                    tenants.membershipsOf(userId),
                    tenants);
            cached = current;
        }
        return current;
    }
}
