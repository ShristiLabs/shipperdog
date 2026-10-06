package com.shipperdog.api.authorization.internal.evaluator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.authorization.ConcealedResourceAccessDeniedException;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantRole;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
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
 * role for tenant checks (union across scopes), and — since the
 * application registry (issue #13, E3) — resolves {@code app:} grants
 * through the application's owning tenant/organization the same way.
 * Since issue #8 an authenticated denial scoped to a target id throws
 * {@link ConcealedResourceAccessDeniedException} (the concealed-404 rule,
 * PRD 12); only target-less or malformed checks return false. Companion
 * to the end-to-end {@code RbacFlowIntegrationTest}, which exercises the
 * same decisions through HTTP.
 */
class RbacPermissionEvaluatorTest {

    @Test
    void failsClosedOnUnauthenticatedOrUnrecognizedPrincipals() {
        RbacPermissionEvaluator evaluator = evaluator(grants(snapshot(UUID.randomUUID(), false, Map.of(), Map.of())));
        Authentication unauthenticated = new UsernamePasswordAuthenticationToken(jwt(UUID.randomUUID()), null);
        Authentication stringPrincipal = new UsernamePasswordAuthenticationToken("not-a-principal", null, List.of());

        assertThat(evaluator.hasPermission(unauthenticated, UUID.randomUUID(), "organization", "org:members:read"))
                .as("unauthenticated callers are denied")
                .isFalse();
        assertThatThrownBy(() ->
                        evaluator.hasPermission(stringPrincipal, UUID.randomUUID(), "organization", "org:members:read"))
                .as("an authenticated caller without a user id is still concealed on target-scoped checks")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);
    }

    @Test
    void failsClosedOnUnknownPermissionsAndMalformedTargets() {
        UUID userId = UUID.randomUUID();
        Authentication auth = jwtAuth(userId);
        RbacPermissionEvaluator evaluator = evaluator(grants(snapshot(userId, false, Map.of(), Map.of())));

        assertThatThrownBy(() -> evaluator.hasPermission(auth, UUID.randomUUID(), "organization", "made:up:code"))
                .as("permissions outside the catalog are denied (concealed)")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);
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
        RbacPermissionEvaluator evaluator = evaluator(grants(snapshot(userId, true, Map.of(), Map.of())));

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
        RbacPermissionEvaluator evaluator = evaluator(grants(
                snapshot(userId, false, Map.of(orgOne, OrgRole.ORG_ADMIN, orgTwo, OrgRole.ORG_MEMBER), Map.of())));

        assertThat(evaluator.hasPermission(auth, orgOne, "organization", "org:members:read"))
                .as("ORG_ADMIN holds org:members:read at their organization")
                .isTrue();
        assertThatThrownBy(() -> evaluator.hasPermission(auth, orgTwo, "organization", "org:members:read"))
                .as("ORG_MEMBER holds no catalog permissions (concealed denial)")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);
        assertThatThrownBy(() -> evaluator.hasPermission(auth, UUID.randomUUID(), "organization", "org:members:read"))
                .as("roles never apply outside their organization (concealed denial)")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);
    }

    @Test
    void testerTargetsResolveTheOwningOrganizationForOrgScopeGrants() {
        UUID userId = UUID.randomUUID();
        UUID owningOrg = UUID.randomUUID();
        UUID foreignOrg = UUID.randomUUID();
        UUID testerId = UUID.randomUUID();
        UUID foreignTesterId = UUID.randomUUID();
        Authentication auth = jwtAuth(userId);
        RbacPermissionEvaluator evaluator = evaluator(
                unknownApplications(),
                unknownVersions(),
                testers(Map.of(testerId, owningOrg, foreignTesterId, foreignOrg)),
                grants(snapshot(userId, false, Map.of(owningOrg, OrgRole.ORG_ADMIN), Map.of())));

        assertThat(evaluator.hasPermission(auth, testerId, "tester", "org:testers:invite"))
                .as("the owning organization's admin holds org:testers:invite through the tester target (issue #26)")
                .isTrue();
        assertThatThrownBy(() -> evaluator.hasPermission(auth, foreignTesterId, "tester", "org:testers:invite"))
                .as("a tester of another organization is a concealed miss")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);
        assertThatThrownBy(() -> evaluator.hasPermission(auth, UUID.randomUUID(), "tester", "org:testers:read"))
                .as("an unknown tester is a concealed miss")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);
    }

    @Test
    void tenantScopeGrantsUnionTheTenantRoleAndTheOwningOrganizationRole() {
        UUID userId = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID owningOrg = UUID.randomUUID();
        Authentication auth = jwtAuth(userId);
        TenantAccess ownedByOrg = owningOrg(tenant, owningOrg);

        RbacPermissionEvaluator tenantAdmin = evaluator(grants(
                new UserRolesSnapshot(userId, false, Map.of(), Map.of(tenant, TenantRole.TENANT_ADMIN), ownedByOrg)));
        assertThat(tenantAdmin.hasPermission(auth, tenant, "tenant", "tenant:members:read"))
                .as("TENANT_ADMIN holds tenant:members:read at their tenant")
                .isTrue();

        RbacPermissionEvaluator tenantTester = evaluator(grants(
                new UserRolesSnapshot(userId, false, Map.of(), Map.of(tenant, TenantRole.TENANT_TESTER), ownedByOrg)));
        assertThatThrownBy(() -> tenantTester.hasPermission(auth, tenant, "tenant", "tenant:members:read"))
                .as("TENANT_TESTER lacks tenant:members:read (concealed denial)")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);

        RbacPermissionEvaluator orgOverlay = evaluator(grants(
                new UserRolesSnapshot(userId, false, Map.of(owningOrg, OrgRole.ORG_ADMIN), Map.of(), ownedByOrg)));
        assertThat(orgOverlay.hasPermission(auth, tenant, "tenant", "tenant:members:manage"))
                .as("ORG_ADMIN of the owning organization holds tenant permissions (union)")
                .isTrue();

        RbacPermissionEvaluator noGrantChannel = evaluator(
                grants(new UserRolesSnapshot(userId, false, Map.of(owningOrg, OrgRole.VIEWER), Map.of(), ownedByOrg)));
        assertThatThrownBy(() -> noGrantChannel.hasPermission(auth, tenant, "tenant", "tenant:members:read"))
                .as("VIEWER in the owning organization grants nothing in the tenant (concealed denial)")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);

        RbacPermissionEvaluator unknownTenant =
                evaluator(grants(new UserRolesSnapshot(userId, false, Map.of(), Map.of(), unknownTenants())));
        assertThatThrownBy(() -> unknownTenant.hasPermission(auth, UUID.randomUUID(), "tenant", "tenant:members:read"))
                .as("tenants without an owning organization fall back to membership only (concealed denial)")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);
    }

    @Test
    void appScopeResolvesThroughTheOwningTenantOrganizationUnion() {
        UUID userId = UUID.randomUUID();
        UUID owningOrg = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        Authentication auth = jwtAuth(userId);
        TenantAccess ownedByOrg = owningOrg(tenant, owningOrg);

        // VIEWER lists app:applications:read (issue #5 matrix): granted at
        // the owning organization of a tenant-owned application (E3, issue #13).
        RbacPermissionEvaluator orgViewer = evaluator(
                applications(application, owningOrg, tenant),
                grants(new UserRolesSnapshot(userId, false, Map.of(owningOrg, OrgRole.VIEWER), Map.of(), ownedByOrg)));
        assertThat(orgViewer.hasPermission(auth, application, "application", "app:applications:read"))
                .as("an owning-organization VIEWER reads the application")
                .isTrue();

        // WRITE is not in VIEWER's set: concealed denial on the application target.
        assertThatThrownBy(() -> orgViewer.hasPermission(auth, application, "application", "app:applications:write"))
                .as("read-only org roles cannot manage the application (concealed denial)")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);

        // TENANT_ADMIN does not list app: permissions: the tenant role alone
        // grants nothing at application scope.
        RbacPermissionEvaluator tenantAdmin = evaluator(
                applications(application, owningOrg, tenant),
                grants(new UserRolesSnapshot(
                        userId, false, Map.of(), Map.of(tenant, TenantRole.TENANT_ADMIN), ownedByOrg)));
        assertThatThrownBy(() -> tenantAdmin.hasPermission(auth, application, "application", "app:applications:read"))
                .as("tenant roles use the tenant-scoped listing, not app: grants (concealed denial)")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);

        // Creation-time check at the future owner tenant: the org overlay
        // carries ORG_ADMIN's app:applications:write into the tenant target.
        RbacPermissionEvaluator orgAdmin = evaluator(
                unknownApplications(),
                grants(new UserRolesSnapshot(
                        userId, false, Map.of(owningOrg, OrgRole.ORG_ADMIN), Map.of(), ownedByOrg)));
        assertThat(orgAdmin.hasPermission(auth, tenant, "tenant", "app:applications:write"))
                .as("an owning-organization administrator may register applications in the tenant")
                .isTrue();
        assertThatThrownBy(() -> tenantAdmin.hasPermission(auth, tenant, "tenant", "app:applications:write"))
                .as("tenant administrators do not hold creation rights (concealed denial)")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);
    }

    @Test
    void appScopeResolvesVersionTargetsThroughTheVersionApplicationOwnerHop() {
        UUID userId = UUID.randomUUID();
        UUID owningOrg = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        Authentication auth = jwtAuth(userId);
        TenantAccess ownedByOrg = owningOrg(tenant, owningOrg);

        // The artifact routes are body-addressed by version id (issue
        // #20, FR-004): the version target hops version to application
        // to owner exactly like the application target — DEVELOPER's
        // app:artifacts:upload granted at the owning organization.
        RbacPermissionEvaluator developer = evaluator(
                unknownApplications(),
                versions(version, application, owningOrg, tenant),
                grants(new UserRolesSnapshot(
                        userId, false, Map.of(owningOrg, OrgRole.DEVELOPER), Map.of(), ownedByOrg)));
        assertThat(developer.hasPermission(auth, version, "version", "app:artifacts:upload"))
                .as("a DEVELOPER of the owning organization uploads artifacts")
                .isTrue();

        // APP_OWNER reads artifacts but holds no upload: concealed
        // denial, indistinguishable from an unknown version.
        RbacPermissionEvaluator owner = evaluator(
                unknownApplications(),
                versions(version, application, owningOrg, tenant),
                grants(new UserRolesSnapshot(
                        userId, false, Map.of(owningOrg, OrgRole.APP_OWNER), Map.of(), ownedByOrg)));
        assertThatThrownBy(() -> owner.hasPermission(auth, version, "version", "app:artifacts:upload"))
                .as("read-only roles cannot upload (concealed denial)")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);

        // Unknown versions deny concealed through the same hop.
        RbacPermissionEvaluator unknown = evaluator(
                unknownApplications(),
                unknownVersions(),
                grants(new UserRolesSnapshot(
                        userId, false, Map.of(owningOrg, OrgRole.DEVELOPER), Map.of(), ownedByOrg)));
        assertThatThrownBy(() -> unknown.hasPermission(auth, UUID.randomUUID(), "version", "app:artifacts:upload"))
                .as("unknown version ids conceal")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);
    }

    @Test
    void appScopeFailsClosedOnUnknownApplicationsAndMalformedTargets() {
        UUID userId = UUID.randomUUID();
        Authentication auth = jwtAuth(userId);
        RbacPermissionEvaluator evaluator =
                evaluator(unknownApplications(), grants(snapshot(userId, false, Map.of(), Map.of())));

        assertThatThrownBy(
                        () -> evaluator.hasPermission(auth, UUID.randomUUID(), "application", "app:applications:read"))
                .as("unknown applications deny concealed, like every scoped miss")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);
        assertThatThrownBy(() -> evaluator.hasPermission(auth, UUID.randomUUID(), "team", "app:applications:read"))
                .as("unrecognized target types deny concealed too (any target-scoped id conceals)")
                .isInstanceOf(ConcealedResourceAccessDeniedException.class);
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /** Evaluator under test; audit emission and tracing are no-ops. */
    private static RbacPermissionEvaluator evaluator(RequestRoleGrants grants) {
        return evaluator(unknownApplications(), grants);
    }

    /** Evaluator under test with an application registry stub. */
    private static RbacPermissionEvaluator evaluator(ApplicationAccess applications, RequestRoleGrants grants) {
        return evaluator(applications, unknownVersions(), grants);
    }

    /** Evaluator under test with registry stubs for both hops (issue #20). */
    private static RbacPermissionEvaluator evaluator(
            ApplicationAccess applications, VersionAccess versions, RequestRoleGrants grants) {
        return evaluator(applications, versions, unknownTesters(), grants);
    }

    /** Evaluator under test with registry stubs including the tester hop (issue #26). */
    private static RbacPermissionEvaluator evaluator(
            ApplicationAccess applications, VersionAccess versions, TesterAccess testers, RequestRoleGrants grants) {
        return new RbacPermissionEvaluator(
                grants,
                applications,
                versions,
                testers,
                new AuditLogger(event -> {}, Clock.systemUTC()),
                new SimpleMeterRegistry(),
                new TraceAttributes(ObservationRegistry.NOOP));
    }

    /** Tester registry stub resolving nothing (the tester hop stays inert). */
    private static TesterAccess unknownTesters() {
        return testers(Map.of());
    }

    /** Tester registry stub resolving the given tester-to-organization map (issue #26). */
    private static TesterAccess testers(Map<UUID, UUID> ownership) {
        return new TesterAccess() {
            @Override
            public Optional<UUID> owningOrganizationOf(UUID id) {
                return Optional.ofNullable(ownership.get(id));
            }
        };
    }

    /** Version registry stub resolving nothing (the version hop stays inert). */
    private static VersionAccess unknownVersions() {
        return versions(null, null, null, null);
    }

    /** Version registry stub resolving exactly one tenant-owned version (issue #20). */
    private static VersionAccess versions(UUID versionId, UUID applicationId, UUID organizationId, UUID tenantId) {
        return new VersionAccess() {
            @Override
            public int reassignOwnership(UUID application, UUID tenant, java.time.Instant updatedAt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<VersionAccess.VersionOwnership> ownershipOf(UUID id) {
                return id.equals(versionId)
                        ? Optional.of(new VersionAccess.VersionOwnership(id, applicationId, organizationId, tenantId))
                        : Optional.empty();
            }

            @Override
            public List<UUID> versionIdsOfApplication(UUID application) {
                return List.of();
            }

            @Override
            public Optional<VersionAccess.VersionRecord> recordOf(UUID id) {
                return Optional.empty();
            }
        };
    }

    /** Registry stub resolving exactly one tenant-owned application. */
    private static ApplicationAccess applications(UUID applicationId, UUID organizationId, UUID tenantId) {
        return new ApplicationAccess() {
            @Override
            public Optional<ApplicationAccess.Ownership> ownershipOf(UUID id) {
                return id.equals(applicationId)
                        ? Optional.of(new ApplicationAccess.Ownership(id, organizationId, tenantId))
                        : Optional.empty();
            }

            @Override
            public Optional<ApplicationAccess.ApplicationProfile> profileOf(UUID id) {
                return ownershipOf(id)
                        .map(ownership -> new ApplicationAccess.ApplicationProfile(
                                ownership.applicationId(),
                                ownership.organizationId(),
                                ownership.tenantId(),
                                List.of("IOS"),
                                "ACTIVE",
                                "com.acme.banking",
                                null));
            }
        };
    }

    /** Registry stub resolving nothing. */
    private static ApplicationAccess unknownApplications() {
        return new ApplicationAccess() {
            @Override
            public Optional<ApplicationAccess.Ownership> ownershipOf(UUID id) {
                return Optional.empty();
            }

            @Override
            public Optional<ApplicationAccess.ApplicationProfile> profileOf(UUID id) {
                return Optional.empty();
            }
        };
    }

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

            @Override
            public boolean acceptsNewAffiliations(UUID tenantId) {
                return false;
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

            @Override
            public boolean acceptsNewAffiliations(UUID tenant) {
                return tenant.equals(tenantId);
            }
        };
    }
}
