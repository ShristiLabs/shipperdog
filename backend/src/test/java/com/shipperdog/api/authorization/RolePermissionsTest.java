package com.shipperdog.api.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.tenant.TenantRole;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
        assertThat(codes).allSatisfy(code -> assertThat(code).matches("^(platform|org|tenant|app):[a-z-]+:[a-z-]+$"));
        for (Permission permission : Permission.values()) {
            assertThat(Permission.fromCode(permission.code()))
                    .as("fromCode round-trips %s", permission.code())
                    .contains(permission);
        }
        assertThat(Permission.fromCode("org:does:not:exist")).isEmpty();
        assertThat(Permission.fromCode("made:up")).isEmpty();
    }

    @Test
    void viewerIsReadOnly() {
        Set<Permission> viewer = RolePermissions.of(OrgRole.VIEWER);
        assertThat(viewer)
                .containsExactlyInAnyOrder(
                        Permission.APP_APPLICATIONS_READ, Permission.APP_VERSIONS_READ, Permission.APP_RELEASES_READ);
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
                        Permission.TENANT_MEMBERS_READ,
                        Permission.TENANT_MEMBERS_MANAGE)
                .doesNotContain(Permission.PLATFORM_ORGANIZATIONS_CREATE);
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
