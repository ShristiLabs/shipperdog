package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.authorization.AuthorizationAction;
import com.shipperdog.api.authorization.AuthorizationEntitlement;
import com.shipperdog.api.authorization.Permission;
import com.shipperdog.api.authorization.RolePermissions;
import com.shipperdog.api.authorization.internal.evaluator.RequestRoleGrants;
import com.shipperdog.api.authorization.internal.evaluator.UserRolesSnapshot;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The S7-01 default entitlement contributor (issue #30): the console
 * role overlay. A subject holding the RBAC authority of the action at
 * the application's owning organization or tenant — exactly the
 * authority the {@code RbacPermissionEvaluator} checks for the console
 * surface — is entitled, so the engine-guarded routes keep serving
 * the console personas they always served. This is the engine
 * DELEGATING console entitlement to the RBAC snapshot (one evaluation
 * authority decides resource access; RBAC keeps guarding management
 * actions), not a second permission engine: the matrix stays the one
 * documented contract.
 *
 * <p>Every persona the matrix cannot reach — a linked tester with
 * effective tenant access and no console role — answers NOT entitled
 * here, and until S7-02 registers the {@code version_access} grant
 * contributor nothing else answers for them: deny-by-default (INV-005)
 * is the resting state, by construction.
 *
 * <p>Union semantics over the roles the snapshot holds, the same
 * composition as the evaluator: the tenant role at the owning tenant,
 * the owning organization's role overlaid, the organization role for
 * organization-shared applications, the platform wildcard above all.
 */
@Component
@Order(200)
class RoleOverlayEntitlement implements AuthorizationEntitlement {

    private final RequestRoleGrants grants;

    RoleOverlayEntitlement(RequestRoleGrants grants) {
        this.grants = grants;
    }

    @Override
    public boolean isEntitled(EntitlementQuery query) {
        Set<Permission> required = requiredPermissions(query.action());
        if (required.isEmpty()) {
            return false;
        }
        UserRolesSnapshot snapshot = grants.snapshot(query.subjectUserId());
        if (snapshot.platformAdmin()) {
            return true;
        }
        if (query.tenantId() != null) {
            com.shipperdog.api.tenant.TenantRole tenantRole = snapshot.tenantRole(query.tenantId());
            if (tenantRole != null && intersects(RolePermissions.of(tenantRole), required)) {
                return true;
            }
            boolean overlay = snapshot.owningOrganization(query.tenantId())
                    .map(snapshot::orgRole)
                    .map(role -> role != null && intersects(RolePermissions.of(role), required))
                    .orElse(false);
            if (overlay) {
                return true;
            }
        }
        if (query.organizationId() != null) {
            com.shipperdog.api.organization.OrgRole orgRole = snapshot.orgRole(query.organizationId());
            if (orgRole != null && intersects(RolePermissions.of(orgRole), required)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The console authority each action maps to: downloads are the
     * tester-portal grant ({@code tenant:applications:download}, the
     * TENANT_TESTER console role) or the console artifact read
     * ({@code app:artifacts:read} — whoever may read the artifact may
     * fetch its bytes); the artifact fetch is either of the same two
     * (the read is the metadata of the binary); the catalog listing is
     * the tenant applications read the route already checks.
     */
    private static Set<Permission> requiredPermissions(AuthorizationAction action) {
        return switch (action) {
            case DOWNLOAD, READ_ARTIFACT ->
                EnumSet.of(Permission.TENANT_APPLICATIONS_DOWNLOAD, Permission.APP_ARTIFACTS_READ);
            case LIST_APPLICATIONS -> EnumSet.of(Permission.TENANT_APPLICATIONS_READ);
        };
    }

    private static boolean intersects(Set<Permission> held, Set<Permission> required) {
        for (Permission permission : required) {
            if (held.contains(permission)) {
                return true;
            }
        }
        return false;
    }
}
