package com.shipperdog.api.organization;

/**
 * Organization membership roles (V3 vocabulary extended by issue #5 with
 * the PRD section 7 functional roles). ORG_ADMIN administers the
 * organization (settings, status, invitations, members, IdP record);
 * ORG_MEMBER is plain membership and deliberately carries no permission
 * catalog entries (deny-by-default, PRD P3). The Platform Administrator
 * role is not an enum value: it stays the {@code users.platform_admin}
 * flag and is modeled as the PLATFORM_ADMIN catalog role. The
 * role-to-permission matrix lives in the authorization module's
 * {@code RolePermissions}.
 */
public enum OrgRole {
    ORG_ADMIN,
    ORG_MEMBER,
    APP_OWNER,
    DEVELOPER,
    QA_ENGINEER,
    RELEASE_MANAGER,
    SECURITY_REVIEWER,
    COMPLIANCE_REVIEWER,
    VIEWER
}
