# Authorization (S1-05)

Role-based access control for the whole API: a permission catalog, a
role-to-permission matrix, and a single enforcement point. Implemented by
the `authorization` module
(`backend/src/main/java/com/shipperdog/api/authorization`); role data
lives in the `organization` and `tenant` modules' membership tables.
Roles are evaluated live from the database on every request — tokens
carry no role claims, so role changes take effect immediately.

## Roles

Three vocabularies feed one matrix:

- **Platform Administrator** (PRD 7.1) is not a membership role: it is
  the `users.platform_admin` flag, modeled as the PLATFORM_ADMIN matrix
  role. Platform administrators hold every permission (wildcard).
- **Organization roles** (`org_role`, V3 vocabulary extended by V4):
  `ORG_ADMIN`, `ORG_MEMBER`, `APP_OWNER`, `DEVELOPER`, `QA_ENGINEER`,
  `RELEASE_MANAGER`, `SECURITY_REVIEWER`, `COMPLIANCE_REVIEWER`,
  `VIEWER`. Memberships live in `organization_members`.
- **Tenant roles** (`tenant_role`, V4): `TENANT_ADMIN`, `TENANT_TESTER`.
  Memberships live in `tenant_members` and are independent of
  organization membership — tenant testers (PRD 7.8) need not belong to
  the owning organization.

`ORG_MEMBER` is plain membership and deliberately holds no permissions:
a fresh member can do nothing until granted a functional role
(deny-by-default).

## Permission catalog and matrix

Permissions are `scope:resource:action` codes
(`backend/src/main/java/com/shipperdog/api/authorization/Permission.java`);
codes are never removed once an endpoint requires them. The matrix is
the documented contract (`RolePermissions`); `*` means every catalog
permission:

| Role | Permissions |
|---|---|
| PLATFORM_ADMIN | `*` (wildcard; the `users.platform_admin` flag, PRD 7.1) |
| ORG_ADMIN | `org:organizations:read` `org:organizations:update` `org:idp-configs:read` `org:idp-configs:write` `org:members:read` `org:members:invite` `org:members:manage` `tenant:members:read` `tenant:members:manage` `tenant:applications:read` `app:applications:read` `app:applications:write` `app:versions:read` `app:releases:read` `app:tester-groups:manage` `app:feedback:read` |
| APP_OWNER | `app:applications:read` `app:applications:write` `app:versions:read` `app:versions:write` `app:artifacts:read` `app:releases:read` `app:releases:manage` `app:feedback:read` |
| DEVELOPER | `app:artifacts:read` `app:artifacts:upload` `app:versions:read` `app:releases:read` |
| QA_ENGINEER | `app:applications:read` `app:versions:read` `app:releases:read` `app:releases:approve-qa` `app:tester-groups:manage` `app:feedback:read` |
| RELEASE_MANAGER | `app:applications:read` `app:versions:read` `app:artifacts:read` `app:releases:read` `app:releases:manage` |
| SECURITY_REVIEWER | `app:applications:read` `app:versions:read` `app:releases:read` `app:releases:approve-security` |
| COMPLIANCE_REVIEWER | `app:applications:read` `app:versions:read` `app:releases:read` `app:releases:approve-compliance` |
| VIEWER | `app:applications:read` `app:versions:read` `app:releases:read` |
| TENANT_ADMIN | `tenant:members:read` `tenant:members:manage` `tenant:applications:read` |
| TENANT_TESTER | `tenant:applications:read` `tenant:applications:download` `tenant:feedback:create` |
| ORG_MEMBER | (none — membership only; deny-by-default) |

A user's effective permissions are the **union** of the sets of every
role they hold across organization and tenant scopes.

## Enforcement model

Every `/api/v1` handler declares its security posture with
`@PreAuthorize` — a `hasPermission(#id, '<type>', '<code>')` expression
for permission-checked endpoints, `isAuthenticated()` for self-scoped
ones. An ArchUnit test fails the build if any endpoint ships without a
posture, and a second check fails if an annotation references a code
that is not in the catalog. The only unannotated endpoints are the six
anonymous auth endpoints (login, register, refresh, logout,
password-reset pair) matched by the security chain's `permitAll`.

`RbacPermissionEvaluator` is the single enforcement point behind every
`hasPermission` call:

- **Deny-by-default.** Unauthenticated or unrecognized principals,
  unknown permission codes, and malformed target ids all evaluate to
  deny. A permission no role holds is simply a permission nobody has;
  the `app:` scope is additionally fail-closed for everyone but
  platform administrators until application records are manageable
  (E3), even for roles that list `app:` permissions in the matrix.
- **Live roles, not token claims.** Roles resolve from database
  memberships on the request's first check. Role changes therefore
  apply to existing access tokens immediately — stricter than the
  issue's "next token refresh at the latest" — and demotions
  additionally revoke refresh families (see below).
- **Per-request memoization.** The resolved `UserRolesSnapshot`
  (platform flag, organization roles in ACTIVE organizations, tenant
  roles) loads at most once per user per request, so endpoints
  evaluating several permissions cost three indexed membership queries
  per request, not one lookup per check. Tenant-owner resolutions are
  memoized inside the snapshot.
- **Scope resolution.** `platform:` — platform administrators only
  (wildcard). `org:` — the caller's role in the target organization
  grants the permission per the matrix; roles never apply outside their
  organization. `tenant:` — the caller's tenant role, with the owning
  organization's role overlaid: an organization administrator holds
  tenant permissions inside their organization's tenants (union across
  scopes). Roles held in DEACTIVATED organizations are excluded from
  the snapshot, so deactivation strips effective permissions without
  touching memberships.

## Opaque denials (PRD 12)

Every permission denial maps to the same body — `403 {"error":"forbidden"}` —
with no hint about which permission was missing or whether the target
resource exists. A denied caller cannot distinguish an existing
organization from an unknown id; a permitted caller still learns
existence through ordinary `404`s. Denial detail (who/what) belongs to
the audit trail, never the HTTP response. Since S1-06 every
authenticated denial is recorded there automatically as an
`ACCESS_DENIED` event (see [audit](audit.md)).

## Role-assignment endpoints

| Method | Path | Permission | Result |
|---|---|---|---|
| GET | `/api/v1/organizations/{id}/members` | `org:members:read` | `200` members with roles and display fields |
| PUT | `/api/v1/organizations/{id}/members/{userId}/role` | `org:members:manage` | `200` member view; `400` unknown role; `404` unknown organization/member; `409` `last_org_admin` or deactivated organization |
| DELETE | `/api/v1/organizations/{id}/members/{userId}` | `org:members:manage` | `204`; same error contract; always revokes the member's sessions |
| GET | `/api/v1/tenants/{id}/members` | `tenant:members:read` | `200` members with roles |
| PUT | `/api/v1/tenants/{id}/members/{userId}/role` | `tenant:members:manage` | `200` member view; creates the membership when absent (`404` unknown user/tenant); `400` unknown role |
| DELETE | `/api/v1/tenants/{id}/members/{userId}` | `tenant:members:manage` | `204`; `404` unknown tenant/member |

Organization-scoped invitations (`POST /api/v1/auth/invitations` with
`organizationId`) require `org:members:invite` at that organization and
grant the recorded role on acceptance.

Guards and side effects:

- **Last-admin guard.** An organization always keeps at least one
  `ORG_ADMIN`: the last one cannot be demoted or removed (`409
  last_org_admin`), by anyone including platform administrators.
  Admin-affecting mutations serialize per organization through a row
  lock on the organization row, so concurrent demotions/removals cannot
  race past the count. There is deliberately no last-`TENANT_ADMIN`
  guard: an administrator of the owning organization (or a platform
  administrator) can always reassign, so a tenant cannot be orphaned.
- **Demotion revocation.** A permission-losing role change demotes: the
  member's refresh tokens are revoked immediately (`TOKEN_REVOKED`
  audit event, reason `role_demoted`) so the change takes effect on
  their next session at the latest. Pure upgrades revoke nothing.
  Membership removal always revokes (reason `membership_removed`).
- **Disabled accounts.** A `DISABLED` user cannot refresh (the refresh
  family is revoked on the attempt) and cannot use PATs — existing
  sessions do not outlive the disable.

## Audit events

`ORG_MEMBER_ROLE_CHANGED` and `TENANT_MEMBER_ROLE_CHANGED` carry
`role.before`/`role.after` and `sessionsRevoked`; `ORG_MEMBER_REMOVED`
and `TENANT_MEMBER_REMOVED` carry the removed role; `TOKEN_REVOKED`
carries the machine-readable reason. See
[authentication](authentication.md) for the identity-side events.

## Extension path

`UserRolesSnapshot` is the seam the ABAC engine (S14-04) extends with
resource attributes; `app:` permissions bind to endpoints as their
owning modules land (E3 onward). The authorization engine series
(S7-01..S7-05) builds policy administration on this catalog without
changing the enforcement point.
