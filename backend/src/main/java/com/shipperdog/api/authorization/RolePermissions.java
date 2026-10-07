package com.shipperdog.api.authorization;

import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.tenant.TenantRole;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The documented permission matrix (issue #5, acceptance criterion 1):
 * role x action x scope. Roles come from three vocabularies — the
 * platform-administrator flag ({@code users.platform_admin}, modeled as
 * the PLATFORM_ADMIN role without schema churn per the V3 note),
 * {@link OrgRole} for organization scope, and {@link TenantRole} for
 * tenant scope. A user's effective permissions are the UNION of the sets
 * of every role they hold across org and tenant scopes (acceptance
 * criterion 4); the method-security evaluator performs that union per
 * request.
 *
 * <p>Matrix (PRD section 7 responsibilities; {@code *} = all catalog
 * permissions):
 *
 * <pre>
 * Role                  Permissions
 * --------------------  --------------------------------------------------
 * PLATFORM_ADMIN        * (wildcard; users.platform_admin flag, PRD 7.1)
 * ORG_ADMIN             org:organizations:read      org:organizations:update
 *                       org:idp-configs:read        org:idp-configs:write
 *                       org:members:read            org:members:invite
 *                       org:members:manage
 *                       org:testers:read/invite/manage (S6-01)
 *                       org:tenants:create          org:tenants:read
 *                       org:environments:read/manage (S9-04)
 *                       org:approval-workflows:read/manage (S10-01)
 *                       tenant:tenants:read         tenant:tenants:update
 *                       tenant:members:read         tenant:members:manage
 *                       tenant:testers:read/manage (S6-03)
 *                       tenant:applications:read
 *                       tenant:authorization-grants:read/manage (S7-02)
 *                       app:applications:read/write app:versions:read
 *                       app:releases:read           app:tester-groups:manage
 *                       app:feedback:read
 * APP_OWNER             app:applications:read/write app:versions:read/write
 *                       app:artifacts:read          app:releases:read/manage
 *                       app:releases:approve-product (S10-01)
 *                       app:feedback:read
 *                       org:environments:read/manage (S9-04)
 *                       org:approval-workflows:read/manage (S10-01)
 *                       tenant:authorization-grants:read/manage (S7-02,
 *                       through the owning-organization overlay)
 * DEVELOPER             app:artifacts:read          app:artifacts:upload
 *                       app:versions:read           app:releases:read
 *                       org:environments:read       (S9-04)
 *                       org:approval-workflows:read (S10-01)
 * QA_ENGINEER           app:applications:read       app:versions:read
 *                       app:releases:read           app:releases:approve-qa
 *                       app:tester-groups:manage    app:feedback:read
 *                       org:environments:read       (S9-04)
 *                       org:approval-workflows:read (S10-01)
 * RELEASE_MANAGER       app:applications:read       app:versions:read
 *                       app:artifacts:read          app:releases:read/manage
 *                       app:releases:approve-release (S10-01)
 *                       org:environments:read/manage (S9-04)
 *                       org:approval-workflows:read/manage (S10-01)
 * SECURITY_REVIEWER     app:applications:read       app:versions:read
 *                       app:releases:read           app:releases:approve-security
 *                       org:environments:read       (S9-04)
 *                       org:approval-workflows:read (S10-01)
 * COMPLIANCE_REVIEWER   app:applications:read       app:versions:read
 *                       app:releases:read           app:releases:approve-compliance
 *                       org:environments:read       (S9-04)
 *                       org:approval-workflows:read (S10-01)
 * VIEWER                app:applications:read       app:versions:read
 *                       app:releases:read           org:environments:read (S9-04)
 *                       org:approval-workflows:read (S10-01)
 * TENANT_ADMIN          tenant:tenants:read         tenant:members:read
 *                       tenant:members:manage       tenant:applications:read
 *                       tenant:testers:read/manage (S6-03)
 *                       tenant:authorization-grants:read/manage (S7-02)
 * TENANT_TESTER         tenant:applications:read    tenant:applications:download
 *                       tenant:feedback:create
 * ORG_MEMBER            (none — membership only; deny-by-default, PRD P3)
 * </pre>
 *
 * <p>{@code app:} permissions are live since the application registry
 * (E3, issue #13): grants evaluate through the application's owning
 * tenant/organization (the union above), and creation-time checks
 * evaluate at the future owner tenant — application-scope role
 * memberships (INV-002) extend this with S3-02.
 */
public final class RolePermissions {

    private RolePermissions() {}

    private static final Set<Permission> ORG_ADMIN = Set.of(
            Permission.ORG_ORGANIZATIONS_READ,
            Permission.ORG_ORGANIZATIONS_UPDATE,
            Permission.ORG_IDP_CONFIGS_READ,
            Permission.ORG_IDP_CONFIGS_WRITE,
            Permission.ORG_MEMBERS_READ,
            Permission.ORG_MEMBERS_INVITE,
            Permission.ORG_MEMBERS_MANAGE,
            Permission.ORG_TESTERS_READ,
            Permission.ORG_TESTERS_INVITE,
            Permission.ORG_TESTERS_MANAGE,
            Permission.ORG_TENANTS_CREATE,
            Permission.ORG_TENANTS_READ,
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_ENVIRONMENTS_MANAGE,
            // S10-01 (issue #40): the approval-chain definitions.
            Permission.ORG_APPROVAL_WORKFLOWS_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_MANAGE,
            Permission.TENANT_TENANTS_READ,
            Permission.TENANT_TENANTS_UPDATE,
            Permission.TENANT_MEMBERS_READ,
            Permission.TENANT_MEMBERS_MANAGE,
            Permission.TENANT_TESTERS_READ,
            Permission.TENANT_TESTERS_MANAGE,
            Permission.TENANT_APPLICATIONS_READ,
            Permission.TENANT_AUTHORIZATION_GRANTS_READ,
            Permission.TENANT_AUTHORIZATION_GRANTS_MANAGE,
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_APPLICATIONS_WRITE,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.APP_TESTER_GROUPS_MANAGE,
            Permission.APP_FEEDBACK_READ);

    private static final Set<Permission> APP_OWNER = Set.of(
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_APPLICATIONS_WRITE,
            Permission.APP_VERSIONS_READ,
            Permission.APP_VERSIONS_WRITE,
            Permission.APP_ARTIFACTS_READ,
            Permission.APP_RELEASES_READ,
            Permission.APP_RELEASES_MANAGE,
            // S10-01 (issue #40): the Product step of FR-016's chain —
            // the application owner signs their own release.
            Permission.APP_RELEASES_APPROVE_PRODUCT,
            Permission.APP_FEEDBACK_READ,
            // S9-04 (issue #58): the application owner defines the
            // environments their releases flow through (the story's
            // persona).
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_ENVIRONMENTS_MANAGE,
            // S10-01 (issue #40): the approval chains their releases
            // run (the same governance persona).
            Permission.ORG_APPROVAL_WORKFLOWS_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_MANAGE,
            // S7-02 (issue #31): app owners administer tester grants
            // inside their organization's tenants through the
            // owning-organization overlay (PRD 26's tenant-admin /
            // app-owner grant authority).
            Permission.TENANT_AUTHORIZATION_GRANTS_READ,
            Permission.TENANT_AUTHORIZATION_GRANTS_MANAGE);

    private static final Set<Permission> DEVELOPER = Set.of(
            Permission.APP_ARTIFACTS_READ,
            Permission.APP_ARTIFACTS_UPLOAD,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_READ);

    private static final Set<Permission> QA_ENGINEER = Set.of(
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.APP_RELEASES_APPROVE_QA,
            Permission.APP_TESTER_GROUPS_MANAGE,
            Permission.APP_FEEDBACK_READ,
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_READ);

    private static final Set<Permission> RELEASE_MANAGER = Set.of(
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_VERSIONS_READ,
            Permission.APP_ARTIFACTS_READ,
            Permission.APP_RELEASES_READ,
            Permission.APP_RELEASES_MANAGE,
            // S10-01 (issue #40): the Release Manager's own final
            // sign-off of FR-016's chain.
            Permission.APP_RELEASES_APPROVE_RELEASE,
            // S9-04 (issue #58): the release manager binds environments
            // to the releases they govern.
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_ENVIRONMENTS_MANAGE,
            // S10-01 (issue #40): and configures the approval chains
            // those releases run (the story's persona).
            Permission.ORG_APPROVAL_WORKFLOWS_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_MANAGE);

    private static final Set<Permission> SECURITY_REVIEWER = Set.of(
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.APP_RELEASES_APPROVE_SECURITY,
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_READ);

    private static final Set<Permission> COMPLIANCE_REVIEWER = Set.of(
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.APP_RELEASES_APPROVE_COMPLIANCE,
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_READ);

    private static final Set<Permission> VIEWER = Set.of(
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_READ);

    /** ORG_MEMBER deliberately holds nothing (deny-by-default, PRD P3). */
    private static final Set<Permission> ORG_MEMBER = Set.of();

    private static final Map<OrgRole, Set<Permission>> BY_ORG_ROLE = Map.of(
            OrgRole.ORG_ADMIN,
            ORG_ADMIN,
            OrgRole.APP_OWNER,
            APP_OWNER,
            OrgRole.DEVELOPER,
            DEVELOPER,
            OrgRole.QA_ENGINEER,
            QA_ENGINEER,
            OrgRole.RELEASE_MANAGER,
            RELEASE_MANAGER,
            OrgRole.SECURITY_REVIEWER,
            SECURITY_REVIEWER,
            OrgRole.COMPLIANCE_REVIEWER,
            COMPLIANCE_REVIEWER,
            OrgRole.VIEWER,
            VIEWER,
            OrgRole.ORG_MEMBER,
            ORG_MEMBER);

    private static final Set<Permission> TENANT_ADMIN = Set.of(
            Permission.TENANT_TENANTS_READ,
            Permission.TENANT_MEMBERS_READ,
            Permission.TENANT_MEMBERS_MANAGE,
            Permission.TENANT_TESTERS_READ,
            Permission.TENANT_TESTERS_MANAGE,
            Permission.TENANT_APPLICATIONS_READ,
            Permission.TENANT_AUTHORIZATION_GRANTS_READ,
            Permission.TENANT_AUTHORIZATION_GRANTS_MANAGE);

    private static final Set<Permission> TENANT_TESTER = Set.of(
            Permission.TENANT_APPLICATIONS_READ,
            Permission.TENANT_APPLICATIONS_DOWNLOAD,
            Permission.TENANT_FEEDBACK_CREATE);

    private static final Map<TenantRole, Set<Permission>> BY_TENANT_ROLE =
            Map.of(TenantRole.TENANT_ADMIN, TENANT_ADMIN, TenantRole.TENANT_TESTER, TENANT_TESTER);

    /**
     * Whether the platform administrator passes every check. Deliberately a
     * wildcard (PRD 7.1: organization configuration, tenant management,
     * security, integrations, global policies): new permissions are granted
     * to platform administrators by construction, and the matrix documents
     * the wildcard instead of enumerating it.
     */
    public static boolean platformAdminPasses(Permission permission) {
        return true;
    }

    /** Permissions granted by an organization role at that organization. */
    public static Set<Permission> of(OrgRole role) {
        return BY_ORG_ROLE.get(role);
    }

    /** Permissions granted by a tenant role at that tenant. */
    public static Set<Permission> of(TenantRole role) {
        return BY_TENANT_ROLE.get(role);
    }

    /** Permissions lost when changing {@code from} to {@code to} (org scope). */
    public static Set<Permission> lostOnOrgRoleChange(OrgRole from, OrgRole to) {
        return lostOnRoleChange(of(from), of(to));
    }

    /** Permissions lost when changing {@code from} to {@code to} (tenant scope). */
    public static Set<Permission> lostOnTenantRoleChange(TenantRole from, TenantRole to) {
        return lostOnRoleChange(of(from), of(to));
    }

    private static Set<Permission> lostOnRoleChange(Set<Permission> from, Set<Permission> to) {
        Set<Permission> lost = new HashSet<>(from);
        lost.removeAll(to);
        return Set.copyOf(lost);
    }
}
