package com.shipperdog.api.authorization;

import java.util.Arrays;
import java.util.Optional;

/**
 * The permission catalog (issue #5): every permission the API layer can
 * require, modeled as {@code scope:resource:action} per the issue's
 * technical notes. Permissions are plain data — the role-to-permission
 * mapping lives in {@link RolePermissions}, and enforcement in the
 * authorization module's method-security evaluator.
 *
 * <p>Scopes:
 * <ul>
 *   <li>{@code platform:} — above organizations; held only by platform
 *       administrators ({@code users.platform_admin}).</li>
 *   <li>{@code org:} — evaluated against the caller's role in the target
 *       organization ({@code organization_members.role}).</li>
 *   <li>{@code tenant:} — evaluated against the caller's role in the
 *       target tenant ({@code tenant_members.role}), with organization
 *       administrators of the owning organization overlaid (union of roles
 *       across scopes, acceptance criterion 4).</li>
 *   <li>{@code app:} — application-scoped actions; enforced since the
 *       application registry (E3, issue #13) by resolving the
 *       application's owning tenant/organization (creation-time checks
 *       evaluate at the future owner tenant). The first bound endpoints
 *       are {@code app:applications:read} and
 *       {@code app:applications:write}; later modules (E4 onward) bind
 *       the rest as their surfaces arrive.</li>
 * </ul>
 *
 * <p>Values are never removed once an endpoint has required them; new
 * permissions are appended with their owning story.
 */
public enum Permission {
    // platform scope
    PLATFORM_ORGANIZATIONS_CREATE("platform:organizations:create"),
    PLATFORM_AUDIT_READ("platform:audit:read"),
    PLATFORM_AUTHORIZATION_INSPECT("platform:authorization:inspect"), // issue #30 (S7-01)

    // org scope
    ORG_ORGANIZATIONS_READ("org:organizations:read"),
    ORG_ORGANIZATIONS_UPDATE("org:organizations:update"),
    ORG_IDP_CONFIGS_READ("org:idp-configs:read"),
    ORG_IDP_CONFIGS_WRITE("org:idp-configs:write"),
    ORG_MEMBERS_READ("org:members:read"),
    ORG_MEMBERS_INVITE("org:members:invite"),
    ORG_MEMBERS_MANAGE("org:members:manage"),
    ORG_TESTERS_READ("org:testers:read"),
    ORG_TESTERS_INVITE("org:testers:invite"),
    ORG_TESTERS_MANAGE("org:testers:manage"),
    ORG_TENANTS_CREATE("org:tenants:create"),
    ORG_TENANTS_READ("org:tenants:read"),
    ORG_ENVIRONMENTS_READ("org:environments:read"), // issue #58 (S9-04)
    ORG_ENVIRONMENTS_MANAGE("org:environments:manage"), // issue #58 (S9-04)
    // Issue #61 (S13-01): the distribution adapter framework -- channel
    // catalog, credential-delegation bindings, and distribution
    // records. The same governance personas as environments (the
    // channel an environment binds and the accounts its dispatches
    // resolve through are one configuration surface).
    ORG_DISTRIBUTION_READ("org:distribution:read"), // issue #61 (S13-01)
    ORG_DISTRIBUTION_MANAGE("org:distribution:manage"), // issue #61 (S13-01)
    // Issue #40 (S10-01): the approval-chain definitions — release
    // governance configuration (the environments-manage personas).
    ORG_APPROVAL_WORKFLOWS_READ("org:approval-workflows:read"),
    ORG_APPROVAL_WORKFLOWS_MANAGE("org:approval-workflows:manage"),
    // Issue #42 (S10-03): the required-gate definitions — the same
    // governance personas (PRD 32's quality-gate block is the release
    // governance surface the definitions configure).
    ORG_GATE_DEFINITIONS_READ("org:gate-definitions:read"),
    ORG_GATE_DEFINITIONS_MANAGE("org:gate-definitions:manage"),
    // Issue #43 (S10-04): the release rings — the progressive rollout
    // stages and their eligibility rules (FR-017: ring edits change
    // who can receive what, so the surface rides the same governance
    // personas as the chains and the gates it composes with).
    ORG_RINGS_READ("org:rings:read"),
    ORG_RINGS_MANAGE("org:rings:manage"),
    // Issue #45 (S10-06): the declarative release policy rules —
    // the standing BLOCK/HOLD governance an organization authors
    // once and every promotion evaluates (the same governance
    // personas as the chains, gates, and rings it composes with).
    ORG_RELEASE_POLICIES_READ("org:release-policies:read"),
    ORG_RELEASE_POLICIES_MANAGE("org:release-policies:manage"),
    // Issue #50 (S11-05): the organization dashboard — the console's
    // landing read model (PRD 29: counts, per-application release
    // health, role-filtered pending actions). Read-only by
    // construction; held by every functional console role (the
    // pending-actions panel personalizes by role server-side).
    ORG_DASHBOARD_READ("org:dashboard:read"), // issue #50 (S11-05)
    // Issue #51 (S12-01): the notification subscriptions — the
    // org-scoped routing table (FR-023's events x channels x
    // recipients) and the delivery log. Reading is every functional
    // console role's visibility (the rings character); managing is the
    // governance personas that own the rollout surfaces it rides.
    ORG_NOTIFICATIONS_READ("org:notifications:read"), // issue #51 (S12-01)
    ORG_NOTIFICATIONS_MANAGE("org:notifications:manage"), // issue #51 (S12-01)

    // tenant scope
    TENANT_TENANTS_READ("tenant:tenants:read"),
    TENANT_TENANTS_UPDATE("tenant:tenants:update"),
    TENANT_MEMBERS_READ("tenant:members:read"),
    TENANT_MEMBERS_MANAGE("tenant:members:manage"),
    TENANT_TESTERS_READ("tenant:testers:read"), // issue #28 (S6-03)
    TENANT_TESTERS_MANAGE("tenant:testers:manage"), // issue #28 (S6-03)
    TENANT_APPLICATIONS_READ("tenant:applications:read"),
    TENANT_APPLICATIONS_DOWNLOAD("tenant:applications:download"),
    TENANT_AUTHORIZATION_GRANTS_READ("tenant:authorization-grants:read"), // issue #31 (S7-02)
    TENANT_AUTHORIZATION_GRANTS_MANAGE("tenant:authorization-grants:manage"), // issue #31 (S7-02)
    TENANT_FEEDBACK_CREATE("tenant:feedback:create"),
    // Issue #48 (S11-03): the device registry's three authorities —
    // the read and the manage of the admin surface, and the export as
    // its OWN download authority (AC3's download-restricted-to-admins:
    // the identifier's mass disclosure is governable apart from
    // routine management). Identifiers are semi-sensitive, so every
    // device surface is admin-only by construction.
    TENANT_DEVICES_READ("tenant:devices:read"), // issue #48 (S11-03)
    TENANT_DEVICES_MANAGE("tenant:devices:manage"), // issue #48 (S11-03)
    TENANT_DEVICES_EXPORT("tenant:devices:export"), // issue #48 (S11-03)
    // Issue #49 (S11-04): the analytics reads and the events ingest —
    // the tenant-grain series for the tenant dashboards (S11-05
    // renders), and the frozen SDK events API's write authority (held
    // by tenant administrators until S15-01's device authentication
    // evolves it).
    TENANT_ANALYTICS_READ("tenant:analytics:read"), // issue #49 (S11-04)
    TENANT_ANALYTICS_INGEST("tenant:analytics:ingest"), // issue #49 (S11-04)
    // Issue #50 (S11-05): the tenant dashboard — the tenant-grain
    // landing read model (PRD 29's tenant block), held by tenant
    // administrators with the owning organization's overlay.
    TENANT_DASHBOARD_READ("tenant:dashboard:read"), // issue #50 (S11-05)

    // app scope (enforced from E3 / issue #13 onward)
    APP_APPLICATIONS_READ("app:applications:read"),
    APP_APPLICATIONS_WRITE("app:applications:write"),
    APP_VERSIONS_READ("app:versions:read"),
    APP_VERSIONS_WRITE("app:versions:write"),
    APP_ARTIFACTS_READ("app:artifacts:read"),
    APP_ARTIFACTS_UPLOAD("app:artifacts:upload"),
    APP_RELEASES_READ("app:releases:read"),
    APP_RELEASES_MANAGE("app:releases:manage"),
    // Issue #49 (S11-04): the release analytics surface — the reads
    // (per-release current values, series over the rollups) and the
    // rollup recompute trigger (backfill and late-history repair, the
    // release governance personas).
    APP_ANALYTICS_READ("app:analytics:read"), // issue #49 (S11-04)
    APP_ANALYTICS_MANAGE("app:analytics:manage"), // issue #49 (S11-04)
    APP_RELEASES_APPROVE_QA("app:releases:approve-qa"),
    APP_RELEASES_APPROVE_SECURITY("app:releases:approve-security"),
    APP_RELEASES_APPROVE_COMPLIANCE("app:releases:approve-compliance"),
    // Issue #40 (S10-01): the two approval steps of FR-016's example
    // chain the catalog pre-dated this story did not cover — Product
    // (the application owner, PRD 7.3) and the Release Manager's own
    // final sign-off (PRD 7.6). With the three above, every
    // approval_step_role maps to exactly one approve permission.
    APP_RELEASES_APPROVE_PRODUCT("app:releases:approve-product"),
    APP_RELEASES_APPROVE_RELEASE("app:releases:approve-release"),
    // Issue #42 (S10-03): the gate override — the governed exception
    // path of FR-026 (a privileged role overrides a FAILED or NO_DATA
    // gate with a mandatory, audited reason). Deliberately its own
    // authority, not a leg of app:releases:manage: overriding a
    // quality refusal is the exception the audit trail exists for,
    // never a routine release-management act.
    APP_RELEASES_GATE_OVERRIDE("app:releases:gate-override"),
    // Issue #45 (S10-06): the policy-hold acknowledge — proceeding
    // past a fired HOLD_FOR_REVIEW rule is the explicit privileged
    // decision AC2 demands, audited with its mandatory reason. Its
    // own authority, the gate-override precedent: the exception the
    // audit trail exists for, never a routine release-management act.
    APP_RELEASES_POLICY_ACKNOWLEDGE("app:releases:policy-acknowledge"),
    APP_TESTER_GROUPS_MANAGE("app:tester-groups:manage"),
    APP_FEEDBACK_READ("app:feedback:read"),
    // Issue #47 (S11-02): the feedback triage authority — classifying
    // severity, moving a report through its lifecycle, assigning the
    // member who works it. Deliberately separate from the read: the
    // QA working surface is a smaller circle than everyone who may
    // see the reports, and the SECURITY_ISSUE restricted visibility
    // keeps its own bar above both (the triage actions conceal those
    // rows exactly as the reads do).
    APP_FEEDBACK_TRIAGE("app:feedback:triage");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    /** The {@code scope:resource:action} string used in annotations. */
    public String code() {
        return code;
    }

    /** The scope qualifier ({@code platform}, {@code org}, ...). */
    public String scope() {
        return code.substring(0, code.indexOf(':'));
    }

    /** Resolves a permission code; empty when no such permission exists. */
    public static Optional<Permission> fromCode(String code) {
        return Arrays.stream(values())
                .filter(permission -> permission.code.equals(code))
                .findFirst();
    }
}
