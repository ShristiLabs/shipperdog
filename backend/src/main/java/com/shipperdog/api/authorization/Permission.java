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

    // tenant scope
    TENANT_TENANTS_READ("tenant:tenants:read"),
    TENANT_TENANTS_UPDATE("tenant:tenants:update"),
    TENANT_MEMBERS_READ("tenant:members:read"),
    TENANT_MEMBERS_MANAGE("tenant:members:manage"),
    TENANT_APPLICATIONS_READ("tenant:applications:read"),
    TENANT_APPLICATIONS_DOWNLOAD("tenant:applications:download"),
    TENANT_FEEDBACK_CREATE("tenant:feedback:create"),

    // app scope (enforced from E3 / issue #13 onward)
    APP_APPLICATIONS_READ("app:applications:read"),
    APP_APPLICATIONS_WRITE("app:applications:write"),
    APP_VERSIONS_READ("app:versions:read"),
    APP_VERSIONS_WRITE("app:versions:write"),
    APP_ARTIFACTS_READ("app:artifacts:read"),
    APP_ARTIFACTS_UPLOAD("app:artifacts:upload"),
    APP_RELEASES_READ("app:releases:read"),
    APP_RELEASES_MANAGE("app:releases:manage"),
    APP_RELEASES_APPROVE_QA("app:releases:approve-qa"),
    APP_RELEASES_APPROVE_SECURITY("app:releases:approve-security"),
    APP_RELEASES_APPROVE_COMPLIANCE("app:releases:approve-compliance"),
    APP_TESTER_GROUPS_MANAGE("app:tester-groups:manage"),
    APP_FEEDBACK_READ("app:feedback:read");

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
