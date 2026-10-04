package com.shipperdog.api.authorization.internal.evaluator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shipperdog.api.identity.PlatformAdmins;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantRole;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Memoization contract of the per-request role loader (issue #5): the
 * snapshot (platform flag, org memberships, tenant memberships) loads at
 * most once per user per request even when an endpoint evaluates several
 * permissions, and tenant-owner resolutions are cached inside the
 * snapshot. Both keep RBAC checks at three indexed queries per request
 * instead of one lookup per {@code @PreAuthorize}.
 */
class RequestRoleGrantsTest {

    private final PlatformAdmins platformAdmins = mock(PlatformAdmins.class);
    private final OrganizationAccess organizations = mock(OrganizationAccess.class);
    private final TenantAccess tenants = mock(TenantAccess.class);

    @Test
    void snapshotLoadsOncePerUserPerRequest() {
        UUID userId = UUID.randomUUID();
        when(platformAdmins.isPlatformAdmin(userId)).thenReturn(false);
        when(organizations.activeOrganizationRoles(userId)).thenReturn(Map.of());
        when(tenants.membershipsOf(userId)).thenReturn(Map.of());
        RequestRoleGrants grants = new RequestRoleGrants(platformAdmins, organizations, tenants);

        UserRolesSnapshot first = grants.snapshot(userId);
        UserRolesSnapshot second = grants.snapshot(userId);

        assertThat(second).isSameAs(first);
        verify(platformAdmins, times(1)).isPlatformAdmin(userId);
        verify(organizations, times(1)).activeOrganizationRoles(userId);
        verify(tenants, times(1)).membershipsOf(userId);
    }

    @Test
    void snapshotReloadsWhenTheActingUserChanges() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        when(platformAdmins.isPlatformAdmin(any(UUID.class))).thenReturn(false);
        when(organizations.activeOrganizationRoles(any(UUID.class))).thenReturn(Map.of());
        when(tenants.membershipsOf(any(UUID.class))).thenReturn(Map.of());
        RequestRoleGrants grants = new RequestRoleGrants(platformAdmins, organizations, tenants);

        UserRolesSnapshot aliceSnapshot = grants.snapshot(alice);
        UserRolesSnapshot bobSnapshot = grants.snapshot(bob);

        assertThat(bobSnapshot).isNotSameAs(aliceSnapshot);
        assertThat(bobSnapshot.userId()).isEqualTo(bob);
        verify(platformAdmins, times(2)).isPlatformAdmin(any(UUID.class));
    }

    @Test
    void snapshotCarriesRolesAndMemoizesTenantOwnerLookups() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID owningOrgId = UUID.randomUUID();
        when(platformAdmins.isPlatformAdmin(userId)).thenReturn(true);
        when(organizations.activeOrganizationRoles(userId)).thenReturn(Map.of(orgId, OrgRole.ORG_ADMIN));
        when(tenants.membershipsOf(userId)).thenReturn(Map.of(tenantId, TenantRole.TENANT_ADMIN));
        when(tenants.owningOrganizationId(tenantId)).thenReturn(Optional.of(owningOrgId));
        RequestRoleGrants grants = new RequestRoleGrants(platformAdmins, organizations, tenants);

        UserRolesSnapshot snapshot = grants.snapshot(userId);

        assertThat(snapshot.platformAdmin()).isTrue();
        assertThat(snapshot.orgRole(orgId)).isEqualTo(OrgRole.ORG_ADMIN);
        assertThat(snapshot.tenantRole(tenantId)).isEqualTo(TenantRole.TENANT_ADMIN);
        assertThat(snapshot.owningOrganization(tenantId)).contains(owningOrgId);
        assertThat(snapshot.owningOrganization(tenantId))
                .as("the owning-organization resolution is memoized inside the snapshot")
                .contains(owningOrgId);
        verify(tenants, times(1)).owningOrganizationId(tenantId);
    }
}
