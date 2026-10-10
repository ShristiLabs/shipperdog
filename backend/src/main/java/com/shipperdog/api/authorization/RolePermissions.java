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
 *                       org:gate-definitions:read/manage (S10-03)
 *                       tenant:tenants:read         tenant:tenants:update
 *                       tenant:members:read         tenant:members:manage
 *                       tenant:testers:read/manage (S6-03)
 *                       tenant:applications:read
 *                       tenant:authorization-grants:read/manage (S7-02)
 *                       app:applications:read/write app:versions:read
 *                       app:releases:read           app:tester-groups:manage
 *                       app:releases:gate-override  (S10-03)
 *                       app:feedback:read           app:feedback:triage (S11-02)
 * APP_OWNER             app:applications:read/write app:versions:read/write
 *                       app:artifacts:read          app:releases:read/manage
 *                       app:releases:approve-product (S10-01)
 *                       app:feedback:read           app:feedback:triage (S11-02)
 *                       org:environments:read/manage (S9-04)
 *                       org:approval-workflows:read/manage (S10-01)
 *                       org:gate-definitions:read/manage (S10-03)
 *                       tenant:authorization-grants:read/manage (S7-02,
 *                       through the owning-organization overlay)
 * DEVELOPER             app:artifacts:read          app:artifacts:upload
 *                       app:versions:read           app:releases:read
 *                       org:environments:read       (S9-04)
 *                       org:approval-workflows:read (S10-01)
 *                       org:gate-definitions:read   (S10-03)
 * QA_ENGINEER           app:applications:read       app:versions:read
 *                       app:releases:read           app:releases:approve-qa
 *                       app:tester-groups:manage    app:feedback:read
 *                       app:feedback:triage         (S11-02)
 *                       org:environments:read       (S9-04)
 *                       org:approval-workflows:read (S10-01)
 *                       org:gate-definitions:read   (S10-03)
 * RELEASE_MANAGER       app:applications:read       app:versions:read
 *                       app:artifacts:read          app:releases:read/manage
 *                       app:releases:approve-release (S10-01)
 *                       app:releases:gate-override  (S10-03)
 *                       app:feedback:triage         (S11-02)
 *                       org:environments:read/manage (S9-04)
 *                       org:approval-workflows:read/manage (S10-01)
 *                       org:gate-definitions:read/manage (S10-03)
 * SECURITY_REVIEWER     app:applications:read       app:versions:read
 *                       app:releases:read           app:releases:approve-security
 *                       app:feedback:read (S11-01 — the restricted
 *                       visibility's second reader: SECURITY_ISSUE
 *                       reports are visible to this role and org
 *                       admins alone)
 *                       app:feedback:triage (S11-02 — the same
 *                       restricted bar governs triaging what only
 *                       this role and org admins can see)
 *                       org:environments:read       (S9-04)
 *                       org:approval-workflows:read (S10-01)
 *                       org:gate-definitions:read   (S10-03)
 * COMPLIANCE_REVIEWER   app:applications:read       app:versions:read
 *                       app:releases:read           app:releases:approve-compliance
 *                       org:environments:read       (S9-04)
 *                       org:approval-workflows:read (S10-01)
 *                       org:gate-definitions:read   (S10-03)
 * VIEWER                app:applications:read       app:versions:read
 *                       app:releases:read           org:environments:read (S9-04)
 *                       org:approval-workflows:read (S10-01)
 *                       org:gate-definitions:read   (S10-03)
 * TENANT_ADMIN          tenant:tenants:read         tenant:members:read
 *                       tenant:members:manage       tenant:applications:read
 *                       tenant:testers:read/manage (S6-03)
 *                       tenant:authorization-grants:read/manage (S7-02)
 *                       tenant:devices:read/manage/export (S11-03)
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
            Permission.ORG_DISTRIBUTION_READ,
            Permission.ORG_ENVIRONMENTS_MANAGE,
            Permission.ORG_DISTRIBUTION_MANAGE,
            // S10-01 (issue #40): the approval-chain definitions.
            Permission.ORG_APPROVAL_WORKFLOWS_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_MANAGE,
            // S10-03 (issue #42): the required-gate definitions, and
            // the override authority of a blocking gate — the
            // organization administrator's governance standing.
            Permission.ORG_GATE_DEFINITIONS_READ,
            Permission.ORG_GATE_DEFINITIONS_MANAGE,
            // S10-04 (issue #43): the release rings and their
            // eligibility rules — the rollout ladder itself.
            Permission.ORG_RINGS_READ,
            Permission.ORG_RINGS_MANAGE,
            // S10-06 (issue #45): the declarative policy rules —
            // the standing BLOCK/HOLD governance of the promotions
            // the organization runs, and the acknowledge authority
            // of a fired HOLD (the gate-override standing).
            Permission.ORG_RELEASE_POLICIES_READ,
            Permission.ORG_RELEASE_POLICIES_MANAGE,
            Permission.APP_RELEASES_POLICY_ACKNOWLEDGE,
            Permission.APP_RELEASES_GATE_OVERRIDE,
            Permission.TENANT_TENANTS_READ,
            Permission.TENANT_TENANTS_UPDATE,
            Permission.TENANT_MEMBERS_READ,
            Permission.TENANT_MEMBERS_MANAGE,
            Permission.TENANT_TESTERS_READ,
            Permission.TENANT_TESTERS_MANAGE,
            Permission.TENANT_APPLICATIONS_READ,
            Permission.TENANT_AUTHORIZATION_GRANTS_READ,
            Permission.TENANT_AUTHORIZATION_GRANTS_MANAGE,
            // S11-03 (issue #48): the device registry through the
            // owning-organization overlay — the tenant administrators'
            // authorities, held by the organization administrator over
            // every tenant they own (the tenant:testers shape).
            Permission.TENANT_DEVICES_READ,
            Permission.TENANT_DEVICES_MANAGE,
            Permission.TENANT_DEVICES_EXPORT,
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_APPLICATIONS_WRITE,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.APP_TESTER_GROUPS_MANAGE,
            Permission.APP_FEEDBACK_READ,
            // S11-02 (issue #47): the triage authority — the
            // administrator's standing over every working surface.
            Permission.APP_FEEDBACK_TRIAGE,
            // S11-04 (issue #49): the release analytics — the app-scope
            // reads and recompute, and the tenant-grain series and
            // events ingest through the owning-organization overlay
            // (the tenant:devices shape).
            Permission.APP_ANALYTICS_READ,
            Permission.APP_ANALYTICS_MANAGE,
            Permission.TENANT_ANALYTICS_READ,
            Permission.TENANT_ANALYTICS_INGEST,
            // S11-05 (issue #50): the landing dashboards — the
            // organization read directly, the tenant grain through the
            // owning-organization overlay (the tenant:analytics shape).
            Permission.ORG_DASHBOARD_READ,
            Permission.TENANT_DASHBOARD_READ,
            // S12-01 (issue #51): the notification routing — the
            // administrator owns who hears about what.
            Permission.ORG_NOTIFICATIONS_READ,
            Permission.ORG_NOTIFICATIONS_MANAGE);

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
            // S11-02 (issue #47): the owner triages the reports their
            // application's releases collect.
            Permission.APP_FEEDBACK_TRIAGE,
            // S9-04 (issue #58): the application owner defines the
            // environments their releases flow through (the story's
            // persona).
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_DISTRIBUTION_READ,
            Permission.ORG_ENVIRONMENTS_MANAGE,
            Permission.ORG_DISTRIBUTION_MANAGE,
            // S10-01 (issue #40): the approval chains their releases
            // run (the same governance persona).
            Permission.ORG_APPROVAL_WORKFLOWS_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_MANAGE,
            // S10-03 (issue #42): the quality gates those releases
            // must pass (still the same governance persona).
            Permission.ORG_GATE_DEFINITIONS_READ,
            Permission.ORG_GATE_DEFINITIONS_MANAGE,
            // S10-04 (issue #43): the rings those releases roll out
            // through (still the same governance persona).
            Permission.ORG_RINGS_READ,
            Permission.ORG_RINGS_MANAGE,
            // S10-06 (issue #45): the policy rules those releases
            // mechanically answer to (still the same persona).
            Permission.ORG_RELEASE_POLICIES_READ,
            Permission.ORG_RELEASE_POLICIES_MANAGE,
            // S7-02 (issue #31): app owners administer tester grants
            // inside their organization's tenants through the
            // owning-organization overlay (PRD 26's tenant-admin /
            // app-owner grant authority).
            Permission.TENANT_AUTHORIZATION_GRANTS_READ,
            Permission.TENANT_AUTHORIZATION_GRANTS_MANAGE,
            // S11-04 (issue #49): the release analytics of the
            // applications they own — the reads and the rollup
            // recompute trigger.
            Permission.APP_ANALYTICS_READ,
            Permission.APP_ANALYTICS_MANAGE,
            // S11-05 (issue #50): the organization dashboard — the
            // owner's landing view of their applications' health and
            // the Product steps waiting on them.
            Permission.ORG_DASHBOARD_READ,
            // S12-01 (issue #51): the notification routing their
            // releases' events ride (the same governance persona).
            Permission.ORG_NOTIFICATIONS_READ,
            Permission.ORG_NOTIFICATIONS_MANAGE);

    private static final Set<Permission> DEVELOPER = Set.of(
            Permission.APP_ARTIFACTS_READ,
            Permission.APP_ARTIFACTS_UPLOAD,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_DISTRIBUTION_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_READ,
            // S10-03 (issue #42): the gate vocabulary their CI reports
            // into (the submission itself rides app:releases:manage).
            Permission.ORG_GATE_DEFINITIONS_READ,
            // S10-04 (issue #43): the rollout ladder their releases
            // climb (read-only; the rollout is governed elsewhere).
            Permission.ORG_RINGS_READ,
            // S10-06 (issue #45): the policy rules that ladder's
            // promotions answer to (read-only).
            Permission.ORG_RELEASE_POLICIES_READ,
            // S12-01 (issue #51): the notification routing (read-only;
            // the delivery visibility their integrations answer to).
            Permission.ORG_NOTIFICATIONS_READ);

    private static final Set<Permission> QA_ENGINEER = Set.of(
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.APP_RELEASES_APPROVE_QA,
            Permission.APP_TESTER_GROUPS_MANAGE,
            Permission.APP_FEEDBACK_READ,
            // S11-02 (issue #47): the story's own persona — the QA
            // engineer works the triage queue (severity, lifecycle,
            // assignment) whose critical findings gate promotions.
            Permission.APP_FEEDBACK_TRIAGE,
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_DISTRIBUTION_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_READ,
            // S10-03 (issue #42): the story's own persona — the QA
            // engineer reads the gates their evidence feeds.
            Permission.ORG_GATE_DEFINITIONS_READ,
            // S10-04 (issue #43): and the rings the QA stage sits in.
            Permission.ORG_RINGS_READ,
            // S10-06 (issue #45): the rules that govern climbing
            // them (read-only).
            Permission.ORG_RELEASE_POLICIES_READ,
            // S11-04 (issue #49): the release health numbers their
            // evidence feeds (read-only).
            Permission.APP_ANALYTICS_READ,
            // S11-05 (issue #50): the organization dashboard — the QA
            // persona's landing view of the queues they work.
            Permission.ORG_DASHBOARD_READ,
            // S12-01 (issue #51): the notification routing (read-only;
            // the critical-report deliveries are their working mail).
            Permission.ORG_NOTIFICATIONS_READ);

    private static final Set<Permission> RELEASE_MANAGER = Set.of(
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_VERSIONS_READ,
            Permission.APP_ARTIFACTS_READ,
            Permission.APP_RELEASES_READ,
            Permission.APP_RELEASES_MANAGE,
            // S10-01 (issue #40): the Release Manager's own final
            // sign-off of FR-016's chain.
            Permission.APP_RELEASES_APPROVE_RELEASE,
            // S10-03 (issue #42): the governed exception — overriding
            // a blocking gate is the Release Manager's audited act.
            Permission.APP_RELEASES_GATE_OVERRIDE,
            // S9-04 (issue #58): the release manager binds environments
            // to the releases they govern.
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_DISTRIBUTION_READ,
            Permission.ORG_ENVIRONMENTS_MANAGE,
            Permission.ORG_DISTRIBUTION_MANAGE,
            // S10-01 (issue #40): and configures the approval chains
            // those releases run (the story's persona).
            Permission.ORG_APPROVAL_WORKFLOWS_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_MANAGE,
            // S10-03 (issue #42): and the quality gates those releases
            // must pass (the same persona).
            Permission.ORG_GATE_DEFINITIONS_READ,
            Permission.ORG_GATE_DEFINITIONS_MANAGE,
            // S10-04 (issue #43): and the rings those releases widen
            // through (the same persona).
            Permission.ORG_RINGS_READ,
            Permission.ORG_RINGS_MANAGE,
            // S10-06 (issue #45): the policy rules that mechanically
            // govern those promotions (the same persona), and the
            // HOLD acknowledge (the gate-override standing).
            Permission.ORG_RELEASE_POLICIES_READ,
            Permission.ORG_RELEASE_POLICIES_MANAGE,
            Permission.APP_RELEASES_POLICY_ACKNOWLEDGE,
            // S11-02 (issue #47): the release manager triages the
            // reports whose open critical bugs can block their
            // promotions (the working surface of the metric they
            // answer to).
            Permission.APP_FEEDBACK_TRIAGE,
            // S11-04 (issue #49): the story's own persona — the
            // Release Manager reads the FR-029 numbers their promotion
            // decisions answer to, and repairs their history through
            // the recompute trigger.
            Permission.APP_ANALYTICS_READ,
            Permission.APP_ANALYTICS_MANAGE,
            // S11-05 (issue #50): the organization dashboard — the
            // release manager's landing view of what needs them (their
            // approvals, the failed gates they may override).
            Permission.ORG_DASHBOARD_READ,
            // S12-01 (issue #51): the notification routing their
            // releases' events ride (the same governance persona).
            Permission.ORG_NOTIFICATIONS_READ,
            Permission.ORG_NOTIFICATIONS_MANAGE);

    private static final Set<Permission> SECURITY_REVIEWER = Set.of(
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.APP_RELEASES_APPROVE_SECURITY,
            // S11-01 (issue #46): the security-issue feedback's
            // restricted visibility names this role (with org admins)
            // as its reader set — the grant is the route access; the
            // row-level restriction lives in the feedback module.
            Permission.APP_FEEDBACK_READ,
            // S11-02 (issue #47): the security reviewer triages the
            // restricted reports only they and the org admins can see
            // (classifying a security issue's severity is theirs, not
            // the general queue's).
            Permission.APP_FEEDBACK_TRIAGE,
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_DISTRIBUTION_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_READ,
            // S10-03 (issue #42): the scan gates are theirs to read.
            Permission.ORG_GATE_DEFINITIONS_READ,
            // S10-04 (issue #43): the security stage's ring is theirs
            // to read.
            Permission.ORG_RINGS_READ,
            // S10-06 (issue #45): the story's own persona — the
            // Security Reviewer authors no rules here (a rule they
            // demand is a governance decision), but reads every
            // standing rule and its evaluation trail.
            Permission.ORG_RELEASE_POLICIES_READ,
            // S11-05 (issue #50): the organization dashboard — the
            // security persona's landing view of the Security steps
            // waiting on them.
            Permission.ORG_DASHBOARD_READ,
            // S12-01 (issue #51): the notification routing (read-only;
            // the security-event deliveries are theirs to watch).
            Permission.ORG_NOTIFICATIONS_READ);

    private static final Set<Permission> COMPLIANCE_REVIEWER = Set.of(
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.APP_RELEASES_APPROVE_COMPLIANCE,
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_DISTRIBUTION_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_READ,
            // S10-03 (issue #42): the override records are theirs to
            // audit.
            Permission.ORG_GATE_DEFINITIONS_READ,
            // S10-04 (issue #43): the ring edits are theirs to audit
            // too (they change who can receive what).
            Permission.ORG_RINGS_READ,
            // S10-06 (issue #45): the policy rules' evaluations are
            // theirs to audit the same way.
            Permission.ORG_RELEASE_POLICIES_READ,
            // S11-05 (issue #50): the organization dashboard — the
            // compliance persona's landing view of the Compliance
            // steps waiting on them.
            Permission.ORG_DASHBOARD_READ,
            // S12-01 (issue #51): the notification routing and its
            // delivery log (read-only; the audit persona's character).
            Permission.ORG_NOTIFICATIONS_READ);

    private static final Set<Permission> VIEWER = Set.of(
            Permission.APP_APPLICATIONS_READ,
            Permission.APP_VERSIONS_READ,
            Permission.APP_RELEASES_READ,
            Permission.ORG_ENVIRONMENTS_READ,
            Permission.ORG_DISTRIBUTION_READ,
            Permission.ORG_APPROVAL_WORKFLOWS_READ,
            Permission.ORG_GATE_DEFINITIONS_READ,
            Permission.ORG_RINGS_READ,
            Permission.ORG_RELEASE_POLICIES_READ,
            // S11-04 (issue #49): the dashboards' numbers are read-only
            // surface (S11-05 renders them for this persona too).
            Permission.APP_ANALYTICS_READ,
            // S11-05 (issue #50): the organization dashboard's counts
            // and health view (read-only surface, the same character).
            Permission.ORG_DASHBOARD_READ,
            // S12-01 (issue #51): the notification routing (read-only,
            // the same character).
            Permission.ORG_NOTIFICATIONS_READ);

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
            Permission.TENANT_AUTHORIZATION_GRANTS_MANAGE,
            // S11-03 (issue #48): the device registry — read, manage,
            // and the export download (AC3's admin bar).
            Permission.TENANT_DEVICES_READ,
            Permission.TENANT_DEVICES_MANAGE,
            Permission.TENANT_DEVICES_EXPORT,
            // S11-04 (issue #49): the tenant-grain series (the tenant
            // dashboard's numbers, S11-05 renders) and the frozen SDK
            // events API's write authority (CI and operations until
            // S15-01's device authentication evolves it).
            Permission.TENANT_ANALYTICS_READ,
            Permission.TENANT_ANALYTICS_INGEST,
            // S11-05 (issue #50): the tenant dashboard — the tenant
            // administrator's landing view (PRD 29's tenant block).
            Permission.TENANT_DASHBOARD_READ);

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
