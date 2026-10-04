package com.shipperdog.api.authorization.internal.evaluator;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantRole;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit coverage of the single enforcement point (issue #5): the evaluator
 * fails closed on every malformed or out-of-scope input, grants only via
 * the documented matrix per scope, overlays the owning organization's
 * role for tenant checks (union across scopes), and keeps {@code app:}
 * permissions denied until application records exist (E3). Companion to
 * the end-to-end {@code RbacFlowIntegrationTest}, which exercises the
 * same decisions through HTTP.
 */
class RbacPermissionEvaluatorTest {

    @Test
    void failsClosedOnUnauthenticatedOrUnrecognizedPrincipals() {
        RbacPermissionEvaluator evaluator =
                new RbacPermissionEvaluator(grants(snapshot(UUID.randomUUID(), false, Map.of(), Map.of())));
        Authentication unauthenticated = new UsernamePasswordAuthenticationToken(jwt(UUID.randomUUID()), null);
        Authentication stringPrincipal = new UsernamePasswordAuthenticationToken("not-a-principal", null, List.of());

        assertThat(evaluator.hasPermission(unauthenticated, UUID.randomUUID(), "organization", "org:members:read"))
                .as("unauthenticated callers are denied")
                .isFalse();
        assertThat(evaluator.hasPermission(stringPrincipal, UUID.randomUUID(), "organization", "org:members:read"))
                .as("principals without a user id are denied")
                .isFalse();
    }

    @Test
    void failsClosedOnUnknownPermissionsAndMalformedTargets() {
        UUID userId = UUID.randomUUID();
        Authentication auth = jwtAuth(userId);
        RbacPermissionEvaluator evaluator =
                new RbacPermissionEvaluator(grants(snapshot(userId, false, Map.of(), Map.of())));

        assertThat(evaluator.hasPermission(auth, UUID.randomUUID(), "organization", "made:up:code"))
                .as("permissions outside the catalog are denied")
                .isFalse();
        assertThat(evaluator.hasPermission(auth, null, "organization", "org:members:read"))
                .as("missing target id is denied")
                .isFalse();
        assertThat(evaluator.hasPermission(auth, "", "organization", "org:members:read"))
                .as("blank target id is denied")
                .isFalse();
        assertThat(evaluator.hasPermission(auth, "not-a-uuid", "organization", "org:members:read"))
                .as("malformed target id is denied, not an error")
                .isFalse();
        assertThat(evaluator.hasPermission(auth, "an-object", "org:members:read"))
                .as("the object-based check form is not supported and stays denied")
                .isFalse();
    }

    @Test
    void platformAdministratorsPassPlatformScopeByWildcard() {
        UUID userId = UUID.randomUUID();
        Authentication auth = jwtAuth(userId);
        RbacPermissionEvaluator evaluator =
                new RbacPermissionEvaluator(grants(snapshot(userId, true, Map.of(), Map.of())));

        assertThat(evaluator.hasPermission(auth, UUID.randomUUID(), "platform", "platform:organizations:create"))
                .as("platform administrators hold every permission (PRD 7.1 wildcard)")
                .isTrue();
    }

    @Test
    void orgScopeGrantsFollowTheMatrixPerOrganization() {
        UUID userId = UUID.randomUUID();
        UUID orgOne = UUID.randomUUID();
        UUID orgTwo = UUID.randomUUID();
        Authentication auth = jwtAuth(userId);
        RbacPermissionEvaluator evaluator = new RbacPermissionEvaluator(grants(
                snapshot(userId, false, Map.of(orgOne, OrgRole.ORG_ADMIN, orgTwo, OrgRole.ORG_MEMBER), Map.of())));

        assertThat(evaluator.hasPermission(auth, orgOne, "organization", "org:members:read"))
                .as("ORG_ADMIN holds org:members:read at their organization")
                .isTrue();
        assertThat(evaluator.hasPermission(auth, orgTwo, "organization", "org:members:read"))
                .as("ORG_MEMBER holds no catalog permissions")
                .isFalse();
        assertThat(evaluator.hasPermission(auth, UUID.randomUUID(), "organization", "org:members:read"))
                .as("roles never apply outside their organization")
                .isFalse();
    }

    @Test
    void tenantScopeGrantsUnionTheTenantRoleAndTheOwningOrganizationRole() {
        UUID userId = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID owningOrg = UUID.randomUUID();
        Authentication auth = jwtAuth(userId);
        TenantAccess ownedByOrg = owningOrg(tenant, owningOrg);

        RbacPermissionEvaluator tenantAdmin = new RbacPermissionEvaluator(grants(
                new UserRolesSnapshot(userId, false, Map.of(), Map.of(tenant, TenantRole.TENANT_ADMIN), ownedByOrg)));
        assertThat(tenantAdmin.hasPermission(auth, tenant, "tenant", "tenant:members:read"))
                .as("TENANT_ADMIN holds tenant:members:read at their tenant")
                .isTrue();

        RbacPermissionEvaluator tenantTester = new RbacPermissionEvaluator(grants(
                new UserRolesSnapshot(userId, false, Map.of(), Map.of(tenant, TenantRole.TENANT_TESTER), ownedByOrg)));
        assertThat(tenantTester.hasPermission(auth, tenant, "tenant", "tenant:members:read"))
                .as("TENANT_TESTER lacks tenant:members:read")
                .isFalse();

        RbacPermissionEvaluator orgOverlay = new RbacPermissionEvaluator(grants(
                new UserRolesSnapshot(userId, false, Map.of(owningOrg, OrgRole.ORG_ADMIN), Map.of(), ownedByOrg)));
        assertThat(orgOverlay.hasPermission(auth, tenant, "tenant", "tenant:members:manage"))
                .as("ORG_ADMIN of the owning organization holds tenant permissions (union)")
                .isTrue();

        RbacPermissionEvaluator noGrantChannel = new RbacPermissionEvaluator(
                grants(new UserRolesSnapshot(userId, false, Map.of(owningOrg, OrgRole.VIEWER), Map.of(), ownedByOrg)));
        assertThat(noGrantChannel.hasPermission(auth, tenant, "tenant", "tenant:members:read"))
                .as("VIEWER in the owning organization grants nothing in the tenant")
                .isFalse();

        RbacPermissionEvaluator unknownTenant = new RbacPermissionEvaluator(
                grants(new UserRolesSnapshot(userId, false, Map.of(), Map.of(), unknownTenants())));
        assertThat(unknownTenant.hasPermission(auth, UUID.randomUUID(), "tenant", "tenant:members:read"))
                .as("tenants without an owning organization fall back to membership only")
                .isFalse();
    }

    @Test
    void appScopeFailsClosedUntilApplicationRecordsExist() {
        UUID userId = UUID.randomUUID();
        Authentication auth = jwtAuth(userId);
        RbacPermissionEvaluator evaluator = new RbacPermissionEvaluator(
                grants(snapshot(userId, false, Map.of(UUID.randomUUID(), OrgRole.VIEWER), Map.of())));

        assertThat(evaluator.hasPermission(auth, UUID.randomUUID(), "application", "app:applications:read"))
                .as("app: permissions stay denied even for roles that list them (E3 fail-closed)")
                .isFalse();
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    private static Authentication jwtAuth(UUID userId) {
        return new UsernamePasswordAuthenticationToken(jwt(userId), null, List.of());
    }

    private static Jwt jwt(UUID userId) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(userId.toString())
                .build();
    }

    private static UserRolesSnapshot snapshot(
            UUID userId, boolean platformAdmin, Map<UUID, OrgRole> orgRoles, Map<UUID, TenantRole> tenantRoles) {
        return new UserRolesSnapshot(userId, platformAdmin, orgRoles, tenantRoles, unknownTenants());
    }

    private static RequestRoleGrants grants(UserRolesSnapshot snapshot) {
        return new RequestRoleGrants(null, null, null) {
            @Override
            UserRolesSnapshot snapshot(UUID userId) {
                return snapshot;
            }
        };
    }

    private static TenantAccess unknownTenants() {
        return new TenantAccess() {
            @Override
            public Map<UUID, TenantRole> membershipsOf(UUID userId) {
                return Map.of();
            }

            @Override
            public Optional<TenantRole> roleInTenant(UUID tenantId, UUID userId) {
                return Optional.empty();
            }

            @Override
            public Optional<UUID> owningOrganizationId(UUID tenantId) {
                return Optional.empty();
            }
        };
    }

    private static TenantAccess owningOrg(UUID tenantId, UUID organizationId) {
        return new TenantAccess() {
            @Override
            public Map<UUID, TenantRole> membershipsOf(UUID userId) {
                return Map.of();
            }

            @Override
            public Optional<TenantRole> roleInTenant(UUID tenant, UUID userId) {
                return Optional.empty();
            }

            @Override
            public Optional<UUID> owningOrganizationId(UUID tenant) {
                return tenant.equals(tenantId) ? Optional.of(organizationId) : Optional.empty();
            }
        };
    }
}
