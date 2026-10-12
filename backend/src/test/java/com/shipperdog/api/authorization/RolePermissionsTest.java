package com.shipperdog.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.tenant.TenantRole;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Permission-catalog invariants (issue #5, acceptance criterion 1): all
 * PRD section 7 roles are represented in the documented matrix, codes
 * are well-formed and unique, ORG_MEMBER stays permission-free
 * (deny-by-default), and the reviewer/approver roles hold exactly their
 * approval kinds.
 */
class RolePermissionsTest {

    @Test
    void everyPrdSection7RoleIsMapped() {
        // PLATFORM_ADMIN is the users.platform_admin wildcard; ORG_MEMBER
        // is membership without catalog permissions (documented empty).
        assertThat(Arrays.stream(OrgRole.values())
                        .filter(role -> !RolePermissions.of(role).isEmpty()))
                .containsExactlyInAnyOrder(
                        OrgRole.ORG_ADMIN,
                        OrgRole.APP_OWNER,
                        OrgRole.DEVELOPER,
                        OrgRole.QA_ENGINEER,
                        OrgRole.RELEASE_MANAGER,
                        OrgRole.SECURITY_REVIEWER,
                        OrgRole.COMPLIANCE_REVIEWER,
                        OrgRole.VIEWER);
        assertThat(RolePermissions.of(OrgRole.ORG_MEMBER))
                .as("ORG_MEMBER deliberately holds no permissions (deny-by-default)")
                .isEmpty();
        assertThat(Arrays.stream(TenantRole.values()).map(RolePermissions::of))
                .allSatisfy(permissions -> assertThat(permissions).isNotEmpty());
    }

    @Test
    void permissionCodesAreWellFormedAndUnique() {
        List<String> codes =
                Arrays.stream(Permission.values()).map(Permission::code).toList();
        assertThat(codes).doesNotHaveDuplicates();
        // The four RBAC scopes plus the one capability scope: `analytics:`
        // (issue #72, S15-01) is the telemetry token's ingest-only
        // authority — cataloged so the annotation checklist resolves the
        // ingest route's hasAuthority leg, but a capability scope by
        // definition NEVER reaches a role mapping (asserted below).
        assertThat(codes)
                .allSatisfy(code -> assertThat(code).matches("^(platform|org|tenant|app|analytics):[a-z-]+:[a-z-]+$"));
        for (Permission permission : Permission.values()) {
            assertThat(Permission.fromCode(permission.code()))
                    .as("fromCode round-trips %s", permission.code())
                    .contains(permission);
        }
        assertThat(Permission.fromCode("org:does:not:exist")).isEmpty();
        assertThat(Permission.fromCode("made:up")).isEmpty();
    }

    @Test
    void capabilityScopedPermissionsAreNeverRoleGranted() {
        // Issue #72 (S15-01): the `analytics:` scope is a capability
        // scope, not an RBAC scope — its one authority rides exclusively
        // on the stateless HMAC telemetry token the download issuance
        // mints. If any role ever mapped it, the narrow-capability
        // guarantee would be gone, so this invariant fails the build.
        List<Permission> roleGranted = Stream.concat(
                        Stream.of(OrgRole.values()).flatMap(role -> RolePermissions.of(role).stream()),
                        Stream.of(TenantRole.values()).flatMap(role -> RolePermissions.of(role).stream()))
                .filter(permission -> permission.scope().equals("analytics"))
                .toList();
        assertThat(roleGranted)
                .as("no role may hold a capability-scoped permission")
                .isEmpty();
    }

    @Test
    void viewerIsReadOnly() {
        Set<Permission> viewer = RolePermissions.of(OrgRole.VIEWER);
        assertThat(viewer)
                .containsExactlyInAnyOrder(
                        Permission.APP_APPLICATIONS_READ,
                        Permission.APP_VERSIONS_READ,
                        Permission.APP_RELEASES_READ,
                        // S9-04 (issue #58): environment reads join the
                        // read-only surface (releases reference environments).
                        Permission.ORG_ENVIRONMENTS_READ,
                        // S10-01 (issue #40): approval-chain reads join the
                        // read-only surface (a viewer can see the chains
                        // their releases run, never decide them).
                        Permission.ORG_APPROVAL_WORKFLOWS_READ,
                        // S10-03 (issue #42): gate-definition reads join
                        // the same read-only surface (a viewer sees the
                        // gates their releases must pass, never overrides).
                        Permission.ORG_GATE_DEFINITIONS_READ,
                        // S10-04 (issue #43): ring reads join the
                        // read-only surface (a viewer sees the ladder
                        // their releases climb, never edits it).
                        Permission.ORG_RINGS_READ,
                        // S10-06 (issue #45): policy-rule reads join
                        // the same surface (a viewer sees the standing
                        // rules, never authors or acknowledges them).
                        Permission.ORG_RELEASE_POLICIES_READ,
                        // S14-04 (issue #70): ABAC policy reads join
                        // the read-only surface (a viewer sees the
                        // attribute rules, never authors them).
                        Permission.ORG_ABAC_POLICIES_READ,
                        // S11-04 (issue #49): the release analytics
                        // reads join the read-only surface (a viewer
                        // sees the dashboards' numbers, never repairs
                        // the rollups).
                        Permission.APP_ANALYTICS_READ,
                        // S11-05 (issue #50): the organization
                        // dashboard joins the read-only surface (a
                        // viewer sees the landing counts and health,
                        // never a pending-action queue — the server
                        // role-filters those away).
                        Permission.ORG_DASHBOARD_READ,
                        // S12-01 (issue #51): notification-subscription
                        // reads join the same surface (a viewer sees the
                        // routing table and the delivery log, never
                        // edits who hears about what).
                        Permission.ORG_NOTIFICATIONS_READ,
                        // S13-01 (issue #61): distribution reads join the
                        // same surface (a viewer sees the channel catalog
                        // and the credential chain's bindings — references
                        // only — never rotates or deletes one).
                        Permission.ORG_DISTRIBUTION_READ);
    }

    @Test
    void onlyDevelopersUploadArtifactsAndOnlyReviewersApprove() {
        assertThat(RolePermissions.of(OrgRole.DEVELOPER)).contains(Permission.APP_ARTIFACTS_UPLOAD);
        assertThat(RolePermissions.of(OrgRole.APP_OWNER)).doesNotContain(Permission.APP_ARTIFACTS_UPLOAD);
        assertThat(RolePermissions.of(OrgRole.QA_ENGINEER))
                .contains(Permission.APP_RELEASES_APPROVE_QA)
                .doesNotContain(Permission.APP_RELEASES_APPROVE_SECURITY, Permission.APP_RELEASES_APPROVE_COMPLIANCE);
        assertThat(RolePermissions.of(OrgRole.SECURITY_REVIEWER))
                .contains(Permission.APP_RELEASES_APPROVE_SECURITY)
                .doesNotContain(Permission.APP_RELEASES_APPROVE_QA, Permission.APP_RELEASES_APPROVE_COMPLIANCE);
        assertThat(RolePermissions.of(OrgRole.COMPLIANCE_REVIEWER))
                .contains(Permission.APP_RELEASES_APPROVE_COMPLIANCE)
                .doesNotContain(Permission.APP_RELEASES_APPROVE_QA, Permission.APP_RELEASES_APPROVE_SECURITY);
    }

    @Test
    void organizationAdminHoldsOrganizationAndTenantSurfacePermissions() {
        Set<Permission> admin = RolePermissions.of(OrgRole.ORG_ADMIN);
        assertThat(admin)
                .contains(
                        Permission.ORG_ORGANIZATIONS_READ,
                        Permission.ORG_ORGANIZATIONS_UPDATE,
                        Permission.ORG_MEMBERS_READ,
                        Permission.ORG_MEMBERS_INVITE,
                        Permission.ORG_MEMBERS_MANAGE,
                        Permission.ORG_TESTERS_READ,
                        Permission.ORG_TESTERS_INVITE,
                        Permission.ORG_TESTERS_MANAGE,
                        Permission.TENANT_MEMBERS_READ,
                        Permission.TENANT_MEMBERS_MANAGE)
                .doesNotContain(Permission.PLATFORM_ORGANIZATIONS_CREATE);
    }

    @Test
    void testerSurfacePermissionsBelongToOrganizationAdminsOnly() {
        assertThat(RolePermissions.of(OrgRole.ORG_ADMIN))
                .contains(Permission.ORG_TESTERS_READ, Permission.ORG_TESTERS_INVITE, Permission.ORG_TESTERS_MANAGE);
        Arrays.stream(OrgRole.values())
                .filter(role -> role != OrgRole.ORG_ADMIN)
                .forEach(role -> assertThat(RolePermissions.of(role))
                        .as("%s", role)
                        .doesNotContain(Permission.ORG_TESTERS_READ));
    }

    @Test
    void demotionDetectionUsesSetLoss() {
        assertThat(RolePermissions.lostOnOrgRoleChange(OrgRole.ORG_ADMIN, OrgRole.DEVELOPER))
                .isNotEmpty();
        assertThat(RolePermissions.lostOnOrgRoleChange(OrgRole.ORG_MEMBER, OrgRole.VIEWER))
                .as("pure upgrades never count as demotions")
                .isEmpty();
        assertThat(RolePermissions.lostOnTenantRoleChange(TenantRole.TENANT_ADMIN, TenantRole.TENANT_TESTER))
                .isNotEmpty();
    }
}
