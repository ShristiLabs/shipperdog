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
  the owning organization. Since S2-03 each membership also carries a
  status (`ACTIVE`/`SUSPENDED`, V8): only ACTIVE memberships grant the
  role (see [Membership suspension](#guards-and-side-effects)).

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
| ORG_ADMIN | `org:organizations:read` `org:organizations:update` `org:idp-configs:read` `org:idp-configs:write` `org:members:read` `org:members:invite` `org:members:manage` `org:testers:read` `org:testers:invite` `org:testers:manage` `org:tenants:create` `org:tenants:read` `tenant:tenants:read` `tenant:tenants:update` `tenant:members:read` `tenant:members:manage` `tenant:testers:read` `tenant:testers:manage` `tenant:applications:read` `app:applications:read` `app:applications:write` `app:versions:read` `app:releases:read` `app:tester-groups:manage` `app:feedback:read` |
| APP_OWNER | `app:applications:read` `app:applications:write` `app:versions:read` `app:versions:write` `app:artifacts:read` `app:releases:read` `app:releases:manage` `app:feedback:read` |
| DEVELOPER | `app:artifacts:read` `app:artifacts:upload` `app:versions:read` `app:releases:read` |
| QA_ENGINEER | `app:applications:read` `app:versions:read` `app:releases:read` `app:releases:approve-qa` `app:tester-groups:manage` `app:feedback:read` |
| RELEASE_MANAGER | `app:applications:read` `app:versions:read` `app:artifacts:read` `app:releases:read` `app:releases:manage` |
| SECURITY_REVIEWER | `app:applications:read` `app:versions:read` `app:releases:read` `app:releases:approve-security` |
| COMPLIANCE_REVIEWER | `app:applications:read` `app:versions:read` `app:releases:read` `app:releases:approve-compliance` |
| VIEWER | `app:applications:read` `app:versions:read` `app:releases:read` |
| TENANT_ADMIN | `tenant:tenants:read` `tenant:members:read` `tenant:members:manage` `tenant:testers:read` `tenant:testers:manage` `tenant:applications:read` |
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
  the `app:` scope was fail-closed for everyone but platform
  administrators until application records became manageable — it is
  enforced since the application registry (S3-01, issue #13), with
  grants resolving through the owning tenant/organization.
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
  scopes). `app:` — since the application registry (S3-01), resolved
  through the application's owning tenant with the organization overlay;
  organization-shared rows (S3-02, PRD 14 — null owning tenant) resolve
  at the owning organization. `version` targets (S5-01, issue #20)
  resolve the same way through the version's application owner: the
  artifact routes are body-addressed by version id, so the check hops
  version -> application -> owner exactly once at the enforcement
  point. `tester` targets (S6-01, issue #26) resolve the record's
  owning organization through the public `TesterAccess` hop for
  `org:` permissions — the tester person record is
  organization-scoped (ADR-0002 tenth amendment), so item-addressed
  tester routes check `org:testers:*` at the record's organization
  and a foreign organization's tester is a concealed miss.
  `tester-group` targets (S6-02, issue #27) resolve the group's
  owning tenant with the organization overlay through the public
  `TesterGroupAccess` hop — groups are tenant-scoped seam rows — and
  an app-scope check at a `tester` target (the groups-per-tester
  listing) resolves the person record's owning organization the same
  way the org-scope hop does. The tenant-tester membership routes
  (S6-03, issue #28) introduce no new target type: the tenant-path
  routes check `tenant:testers:*` at the plain `tenant` target, and
  the org-grain tenants-of-tester listing checks `org:testers:read`
  at the `tester` target through the existing hop.
  Creation-time checks evaluate at the
  future owner tenant for tenant-owned registrations and at the owning
  organization for organization-shared ones, so
  `app:applications:write` authority is organization-level by
  construction. The shared-application tenant authorization mapping
  (S3-03, PRD 15) rides the same authority: `POST`/`DELETE`/`GET
  /api/v1/applications/{id}/tenants` checks
  `app:applications:write`/`read` at the application — organization
  roles only, so an authorized tenant can never widen its own
  visibility, and the mapping is one input layer of the E7
  authorization chain (PRD 11/12) rather than a second permission
  engine. Roles held in DEACTIVATED organizations are excluded
  from the snapshot, so deactivation strips effective permissions
  without touching memberships.

## Opaque denials (PRD 12)

Since S1-08 denials render through the platform's single error envelope
(RFC 7807 problem+json with a stable `code` and the request's
`trace_id` — see [API conventions](api-conventions.md)) under one rule:

- **Resource-scoped denials are concealed as `404`.** When a
  `hasPermission(#id, ...)` check fails for an authenticated caller, the
  response is byte-for-byte what a genuine not-found for that resource
  type produces (`ORGANIZATION_NOT_FOUND` / `TENANT_NOT_FOUND` /
  `APPLICATION_NOT_FOUND` / `VERSION_NOT_FOUND` — the version target's
  mapping since S5-02, when the artifact read began permission-checking
  the version it carries; title
  `Not Found`, detail `Resource not found.`). A denied caller cannot
  distinguish an existing organization from an unknown id; a permitted
  caller still learns existence through ordinary `404`s.
- **Target-less denials stay opaque `403`s** (`FORBIDDEN`, no detail
  about which permission was missing) — e.g. creation rights the caller
  does not hold anywhere.

Denial detail (who/what/which permission) belongs to the audit trail,
never the HTTP response. Since S1-06 every authenticated denial is
recorded there automatically as an `ACCESS_DENIED` event (see
[audit](audit.md)).

## Tenant isolation at the data layer (S2-02)

Endpoint checks decide **who may enter a tenant-scoped route**; since
S2-02 a second, structural layer guarantees that **everything read or
written inside it belongs to that tenant** (INV-001, PRD 26):

- The web layer binds a request-scoped `TenantContext` (thread-local)
  for `/api/v1/tenants/{id}` routes: target tenant, actor, and the
  platform-admin override flag. It is cleared when the request
  completes.
- Repositories over tenant-scoped entities extend
  `TenantScopedRepository`, whose base class composes an index-backed
  `tenantId = context` predicate into **every** query — reads,
  existence checks, and writes — so no caller can span tenants. A query
  without a bound context throws instead of running (fail-closed), and
  a write targeting another tenant is refused. Cross-tenant fetches by
  id surface as empty results, which the services render as the same
  opaque `404` a genuine miss produces (PRD 12).
- The seam is mandatory: architecture tests reject a repository over a
  `TenantScope` entity that bypasses it (the only exemption,
  `@CrossTenantByDesign`, marks the authorization engine's role
  resolution — membership queries that are cross-tenant by design and
  never serve endpoint data), reject derived query methods on seam
  repositories, ban raw `EntityManager`/`Session` usage outside
  repository packages, and reject unscoped Spring Data query interfaces
  (`JpaSpecificationExecutor`, `RevisionRepository`) on seam
  repositories.
- The inherited `JpaRepository` surface is policy, not a bypass: the
  base class overrides all of it — id-based reads behave scoped (a
  foreign id reads as a genuine miss), writes and single-entity deletes
  are tenant-verified, and every operation without a tenant-safe
  meaning (bulk deletes, unparameterized listings, lazy proxies,
  `Example` queries) refuses — with a reflective completeness guard
  that fails the build when a Spring Data upgrade adds operations the
  seam does not account for (ADR-0002 amendment).
- The **platform-admin override** is explicit, audited, and closed to
  organization roles: it is set only from `users.platform_admin`, and
  every platform-admin request on a tenant-scoped route records a
  `CROSS_TENANT_ACCESS` audit event (actor, tenant, method/URI). Reads
  stay scoped to the route's tenant even for platform administrators;
  the override authorizes administrative cross-tenant writes.

Applications have ridden the seam since their tenant-scoped listing
(S2-03), and the registry (S3-01) adds two sanctioned cross-tenant
surfaces recorded in ADR-0002's second amendment: the
`@CrossTenantByDesign` registry guard (ownership resolution and
organization-wide uniqueness booleans — never endpoint data paths) and
the `TenantScopeExecutor` that establishes the tenant context for
creation routes and background jobs (establish-only; the route
interceptors always win). When versions, artifacts, testers, and tester
groups get JPA entities (E4+), their entities implement `TenantScope`
and their repositories extend the seam — isolation is inherited by
construction, not re-implemented per module. The mechanism decision
(predicate injection over a Hibernate session filter) and its
trade-offs are recorded in
[ADR-0002](adr/ADR-0002-tenant-isolation-data-seam.md).

## Tenant lifecycle endpoints

Tenant lifecycle (issue #9) is organization-level administration. An
organization administrator manages every tenant their organization owns
(creation and listing are checked against the owning organization; reads
and updates against the tenant itself, where the owning organization's
role overlays the caller's tenant role per the union rule). Tenant
administrators can read their tenant but not change it.

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/tenants` | `org:tenants:create` at the target `organizationId` (body) | `201` tenant view; `400` invalid name/externalId; `404` unknown organization; `409` `TENANT_NAME_TAKEN` or `TENANT_EXTERNAL_ID_TAKEN`; honors `Idempotency-Key` |
| GET | `/api/v1/tenants?organizationId=...&status=...` | `org:tenants:read` at that organization | `200` tenants, cursor-paginated; `status` filter (`ACTIVE`/`DEACTIVATED`); `400` missing/invalid `organizationId` |
| GET | `/api/v1/tenants/{id}` | `tenant:tenants:read` | `200` tenant view |
| PUT | `/api/v1/tenants/{id}` | `tenant:tenants:update` | `200` tenant view; partial update (null fields unchanged); `400` invalid fields or status; `409` name/externalId taken |

Deactivation is reversible (`ACTIVE` <-> `DEACTIVATED`) and blocks new
memberships (`409 TENANT_DEACTIVATED`) while preserving all data; see
[Tenants](tenants.md). Creation requests missing an `organizationId`
are denied by the permission check before the body is read
(authorization precedes validation platform-wide).

Tester onboarding (S6-01, issue #26) is organization-level
administration of organization-scoped person records (ADR-0002 tenth
amendment); tenant attachment arrives with S6-03.

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/testers` | `org:testers:invite` at the target `organizationId` (body) | `201` INVITED tester with the once-shown invitation block; duplicate address `200` existing tester (idempotent); `400` invalid email/missing `fullName`; `404` unknown organization; honors `Idempotency-Key` |
| GET | `/api/v1/testers?organizationId=...&status=...` | `org:testers:read` at that organization | `200` testers, cursor-paginated, email-ordered; `status` filter (`INVITED`/`ACTIVE`/`SUSPENDED`) |
| GET | `/api/v1/testers/{id}` | `org:testers:read` (`tester` target) | `200` tester view |
| POST | `/api/v1/testers/{id}/invitations` | `org:testers:invite` (`tester` target) | `201` fresh invitation (revokes the live one); `409 TESTER_ALREADY_ACTIVE`/`TESTER_INVALID_TRANSITION`/`TESTER_INVITATION_IN_FLIGHT` (concurrent re-invite lost — retry) |
| PUT | `/api/v1/testers/{id}/status` | `org:testers:manage` (`tester` target) | `200`; `ACTIVE <-> SUSPENDED` only (`INVITED -> ACTIVE` is the accept flow's); `409 TESTER_INVALID_TRANSITION` |

Acceptance runs through the platform registration endpoint with a
tester-flavored invitation (no permission — the token is the
credential) and grants no organization membership; the invitation
lifecycle and the suspended-tester credential blocks are documented in
[Testers](testers.md).

Tester groups (S6-02, issue #27) are tenant-scoped seam data checked
with the catalog's `app:tester-groups:manage` (ORG_ADMIN and
QA_ENGINEER since S1-05; no separate read grant exists yet):

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/tester-groups` | `app:tester-groups:manage` at the target `tenantId` (body) | `201` group; `409 TESTER_GROUP_NAME_TAKEN`/`TESTER_GROUP_TENANT_DEACTIVATED`; `404` unknown tenant; honors `Idempotency-Key` |
| GET | `/api/v1/tester-groups?tenantId=...` | `app:tester-groups:manage` at that tenant | `200` groups, cursor-paginated, creation-ordered |
| GET | `/api/v1/tester-groups?testerId=...` | `app:tester-groups:manage` (`tester` target) | `200` the tester's groups across their organization's tenants, name-ordered |
| GET | `/api/v1/tester-groups/{id}` | `app:tester-groups:manage` (`tester-group` target) | `200` group view |
| PUT | `/api/v1/tester-groups/{id}` | `app:tester-groups:manage` (`tester-group` target) | `200` renamed; `409 TESTER_GROUP_NAME_TAKEN` |
| DELETE | `/api/v1/tester-groups/{id}` | `app:tester-groups:manage` (`tester-group` target) | `204`; memberships removed with the group |
| GET | `/api/v1/tester-groups/{id}/members` | `app:tester-groups:manage` (`tester-group` target) | `200` testers, cursor-paginated, join-ordered |
| POST | `/api/v1/tester-groups/{id}/members` | `app:tester-groups:manage` (`tester-group` target) | `201 {added:true}`; re-add `200 {added:false}` (idempotent); `404 TESTER_NOT_FOUND` (unknown/foreign tester); honors `Idempotency-Key` |
| DELETE | `/api/v1/tester-groups/{id}/members/{testerId}` | `app:tester-groups:manage` (`tester-group` target) | `204`, idempotent (absent membership is a no-op) |

Default-group seeding on tenant creation runs inside the tenant
module's creation transaction; details and the race discipline are
documented in [Tester groups](tester-groups.md).

## Role-assignment endpoints

| Method | Path | Permission | Result |
|---|---|---|---|
| GET | `/api/v1/organizations/{id}/members` | `org:members:read` | `200` members with roles and display fields, cursor-paginated |
| PUT | `/api/v1/organizations/{id}/members/{userId}/role` | `org:members:manage` | `200` member view; `400` unknown role; `404` unknown organization/member; `409` `LAST_ORG_ADMIN` or `ORGANIZATION_DEACTIVATED` |
| DELETE | `/api/v1/organizations/{id}/members/{userId}` | `org:members:manage` | `204`; same error contract; always revokes the member's sessions |
| GET | `/api/v1/tenants/{id}/members` | `tenant:members:read` | `200` members with roles and membership status, cursor-paginated |
| POST | `/api/v1/tenants/{id}/members` | `tenant:members:manage` | `201` member view (born `ACTIVE`); same-role duplicate is idempotent `200` (no write, no audit); `409 TENANT_MEMBER_EXISTS` on a different-role duplicate; `409 TENANT_DEACTIVATED` while deactivated; `404` unknown user/tenant; `400` missing/unknown role; honors `Idempotency-Key` |
| PUT | `/api/v1/tenants/{id}/members/{userId}/role` | `tenant:members:manage` | `200` member view; creates the membership when absent (`404` unknown user/tenant; `409` `TENANT_DEACTIVATED` while deactivated); `400` unknown role |
| PUT | `/api/v1/tenants/{id}/members/{userId}/status` | `tenant:members:manage` | `200` member view; `ACTIVE`/`SUSPENDED` transition (suspending revokes sessions); `404` unknown tenant/member; `400` invalid status |
| DELETE | `/api/v1/tenants/{id}/members/{userId}` | `tenant:members:manage` | `204`; `404` unknown tenant/member |
| GET | `/api/v1/tenants/{id}/applications` | `tenant:applications:read` | `200` the tenant's own applications, cursor-paginated (S2-03; registry management is S3-01) |

### Tenant-tester membership endpoints (S6-03)

The portal-access join — separate vocabulary from the console-member
routes above on purpose (`tenant:testers:*` vs `tenant:members:*`):
a tester holds no tenant role, and a console member is not a tester
(see [Tenant-tester memberships](tenant-tester-memberships.md)).

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/tenants/{id}/testers` | `tenant:testers:manage` | `201 {linked:true}` (born `ACTIVE`); duplicate link idempotent `200 {linked:false}`; re-link of a retained REVOKED row `200 {reactivated:true}`; `409 TESTER_NOT_ACTIVE` (INVITED/SUSPENDED person record) / `TENANT_DEACTIVATED` (new affiliations only); `404` unknown/foreign tester or tenant; honors `Idempotency-Key` |
| GET | `/api/v1/tenants/{id}/testers` | `tenant:testers:read` | `200` the EFFECTIVE view by default (person record ACTIVE and membership ACTIVE — tenant-scoped visibility); `status=ACTIVE|SUSPENDED|REVOKED` the administrative view; `status=ALL` every retained row (history); cursor-paginated, rows carry both statuses and `effectiveAccess` |
| GET | `/api/v1/tenants/{id}/testers/{testerId}` | `tenant:testers:read` | `200` membership view with `testerStatus`, `membershipStatus`, `effectiveAccess`; `404 TENANT_TESTER_MEMBERSHIP_NOT_FOUND` |
| PUT | `/api/v1/tenants/{id}/testers/{testerId}/status` | `tenant:testers:manage` | `200` membership view; `ACTIVE`/`SUSPENDED` transition (live-query effectiveness — excluded on the next request); `409 TESTER_INVALID_TRANSITION` on a REVOKED row (re-link instead) — the switch continues on a deactivated tenant |
| DELETE | `/api/v1/tenants/{id}/testers/{testerId}` | `tenant:testers:manage` | `204`, idempotent; removal RETAINS the row REVOKED (history preserved), never deletes |
| GET | `/api/v1/testers/{id}/tenants` | `org:testers:read` (`tester` target) | `200` the tester's tenants across their own organization, link-ordered, history included — one working set (the org-grain form, the groups-per-tester precedent) |

Error responses use the platform's problem+json envelope with stable
codes (see the [error code registry](error-codes.md)).

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
  Membership removal always revokes (reason `membership_removed`), and
  suspending a tenant membership revokes too (reason
  `membership_suspended`); reactivation revokes nothing.
- **Membership suspension (S2-03).** A tenant membership carries its
  own status (`tenant_members.status`, V8) independent of the user
  account status — access needs both switches on. Only `ACTIVE`
  memberships resolve to tenant roles in the live snapshot, so a
  suspended member loses tenant permissions on the next request, and
  the organization overlay (the owning organization's roles) is
  untouched by suspension.
- **Tester deactivation (S6-04, issue #29).** Deactivating a tester
  (`DISABLED`) is revocation, not parking: the tester's login, refresh,
  and PAT use are refused (audit reason `tester_deactivated`), the
  linked account's refresh families are revoked in the deactivating
  transaction (reason `tester_deactivated`), and every ACTIVE
  tenant-tester membership cascades to SUSPENDED — so the two-switches
  rule (person record ACTIVE AND membership ACTIVE) denies every
  effective-access decision on the next evaluation. Reactivation
  restores nothing silently: each membership waits for its per-tenant
  re-enable. See [testers](testers.md) for the full semantics and the
  revocation latency SLA.
- **Disabled accounts.** A `DISABLED` user cannot refresh (the refresh
  family is revoked on the attempt) and cannot use PATs — existing
  sessions do not outlive the disable.

## Application registry endpoints (S3-01)

Application registry management (issue #13) is organization-level
authority: `app:applications:write` is granted only to organization
roles (`ORG_ADMIN`, `APP_OWNER`), and the creation check evaluates at
the future owner tenant, so tenant roles reach applications through
the tenant-scoped listing instead.

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/applications` | `app:applications:write` at the body's `tenantId` | `201` application view (born `ACTIVE`); `400` invalid platforms/identifiers; `409` `APPLICATION_NAME_TAKEN` / `APPLICATION_BUNDLE_TAKEN` / `APPLICATION_PACKAGE_TAKEN` / `APPLICATION_TENANT_DEACTIVATED`; `404` unknown tenant (concealed); honors `Idempotency-Key` |
| GET | `/api/v1/applications/{id}` | `app:applications:read` at the application | `200` application view; `404` unknown or denied (concealed, identical envelope) |
| PATCH | `/api/v1/applications/{id}` | `app:applications:write` at the application | `200` partial update (null fields unchanged); `400` invalid edits; `409` identifier/name conflicts; the reversible `ACTIVE`/`ARCHIVED` transition |
| POST | `/api/v1/applications/{id}/versions` | `app:versions:write` at the application | `201` version view (born `DRAFT`, semver-style name, per-platform artifact slots empty until E5); `400` invalid name; `409` `VERSION_NAME_TAKEN` / `APPLICATION_ARCHIVED`; `404` unknown or denied application (concealed); honors `Idempotency-Key` (S4-01) |
| GET | `/api/v1/applications/{id}/versions` | `app:versions:read` at the application | `200` limit/cursor page, newest first by the normalized semver sort key, optional conjunctive `tag` filter (S4-02); the default window is the working set (`DRAFT` and the M2-reserved `ACTIVE`) — the terminal states return with the comma-separated `include` filter, `archived` and/or `revoked` (S4-03, unknown values `400`); `404` unknown or denied application (concealed) (S4-01) |
| GET | `/api/v1/applications/{id}/versions/{versionId}` | `app:versions:read` at the application | `200` version detail with the empty release-history section (M2 fills it); `404` unknown version or application (concealed) (S4-01) |
| PUT | `/api/v1/applications/{id}/versions/{versionId}/tags` | `app:versions:write` at the application | `200` tags view (replace-set: trimmed, UPPER-normalized, deduplicated, at most 20 distinct values; empty clears; inherently idempotent, no `Idempotency-Key`); `400` `INVALID_REQUEST` on malformed tags or over-cap sets; `409` `VERSION_ARCHIVED` / `VERSION_REVOKED` on a read-only terminal version (S4-03); `404` unknown version or application (concealed); a real change is audited `VERSION_TAGS_CHANGED` (S4-02) |
| POST | `/api/v1/applications/{id}/versions/{versionId}/archive` | `app:versions:write` at the application | `200` version detail (now `ARCHIVED`: read-only, out of the default listing — `include=archived` returns it — fully readable forever); `409` `VERSION_ARCHIVED` on a repeat, `VERSION_REVOKED` past the stronger terminal; `404` unknown version or application (concealed); audited `VERSION_ARCHIVED`; no `Idempotency-Key` — the state is the replay answer (S4-03) |
| POST | `/api/v1/applications/{id}/versions/{versionId}/revoke-distribution` | `app:versions:write` at the application | `200` version detail (now `REVOKED`: new downloads blocked, nothing deleted, still readable per authorization — INV-006); `400` missing/blank/oversized `reason` (1..1000 characters, trimmed, mandatory); `409` `VERSION_REVOKED` on a repeat; `404` unknown version or application (concealed); audited `VERSION_DISTRIBUTION_REVOKED` with the reason (FR-024); reachable from `DRAFT`, `ACTIVE`, and `ARCHIVED` (S4-03) |
| GET | `/api/v1/applications/{id}/versions/{versionId}/history` | `app:versions:read` at the application | `200` audit history: version summary, chronological status timeline (creation's `DRAFT` row, every archive, every revoke with its reason), and the chronological artifact section (empty until E5); `404` unknown version or application (concealed); read-only forever (S4-03) |

Identifier and lifecycle edits are audited (`APPLICATION_CREATED`,
`APPLICATION_UPDATED`) with before/after pairs; see
[error codes](error-codes.md) for the full application table and
[API conventions](api-conventions.md) for the idempotency registry.

## Artifact upload endpoints (S5-01)

Artifact upload (issue #20) is the `DEVELOPER` story — the role's
`app:artifacts:upload` grant — checked at the **version** every route
carries: the surface is body-addressed (the version id rides the
multipart form or the JSON body, never the path), so the evaluator
resolves the target version -> application -> owning tenant with the
organization overlay, and a caller without the grant on that
application receives the concealed 404 — indistinguishable from an
unknown version id. The same union rule as everywhere applies: an
organization-level `DEVELOPER` reaches the artifact surface of every
tenant of their organization.

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/api/v1/artifacts` | `app:artifacts:upload` at the form's `version_id` | `201` registered artifact view (born `VALIDATING`, in-stream SHA-256, `duplicateOf` informational); `400` invalid fields/platform not registered; `415` `ARTIFACT_FILE_TYPE_REJECTED`; `413` `ARTIFACT_TOO_LARGE` (ceiling or over the resumable threshold); `409` `ARTIFACT_BUILD_TAKEN` / `APPLICATION_ARCHIVED` / `VERSION_IMMUTABLE`; `404` unknown or denied version (concealed); honors `Idempotency-Key` |
| POST | `/api/v1/artifacts/upload-sessions` | `app:artifacts:upload` at the body's `versionId` | `201` session view (`artifactId`, `uploadUrl`, `expiresAt`); same guard codes as the multipart path, plus `400` on a non-positive `sizeHint` and `413` on an over-ceiling hint; `404` unknown or denied version (concealed); honors `Idempotency-Key` |
| GET | `/api/v1/artifacts/{artifactId}?version_id={versionId}` | `app:artifacts:read` at the query's `version_id` | `200` artifact view with the extraction tier (`bundleId`, `versionName`, `minimumOs`, `buildNumber`, `extraction.status` — always `PENDING` in an upload response, settled here: `SUCCESS`/`FAILED`/`BLOCKED` with `errorCode`/`errorMessage`); `404` unknown artifact under the caller's own version (`ARTIFACT_NOT_FOUND`) or unknown/denied version (concealed `VERSION_NOT_FOUND`) (S5-02, issue #21) |
| POST | `/api/v1/artifacts/{artifactId}/finalize` | `app:artifacts:upload` at the body's `versionId` | `201 created=true` first registration, `200 created=false` replaying the registered artifact on retry; `400` no bytes/empty; `410` `UPLOAD_SESSION_EXPIRED`; `413` over-ceiling stored bytes; `409` `VERSION_IMMUTABLE` (freeze re-checked at finalize) / `APPLICATION_ARCHIVED`; `404` unknown artifact, session of another version, or denied version (all concealed, identical envelope); no `Idempotency-Key` — the state is the replay answer |

Uploads of a frozen version are refused before any byte moves, and
finalize re-checks the freeze — a version revoked mid-upload fails
closed. See [API conventions](api-conventions.md) for the upload
shapes and [error codes](error-codes.md) for the artifact table.

## Audit events

`ORG_MEMBER_ROLE_CHANGED` and `TENANT_MEMBER_ROLE_CHANGED` carry
`role.before`/`role.after` and `sessionsRevoked`; `TENANT_MEMBER_ADDED`
carries the assigned role and birth status; `TENANT_MEMBER_STATUS_CHANGED`
carries `status.before`/`status.after` and `sessionsRevoked`;
`ORG_MEMBER_REMOVED` and `TENANT_MEMBER_REMOVED` carry the removed
role; `TOKEN_REVOKED` carries the machine-readable reason. See
[authentication](authentication.md) for the identity-side events.

## Extension path

`UserRolesSnapshot` is the seam the ABAC engine (S14-04) extends with
resource attributes; `app:` permissions bind to endpoints as their
owning modules land (the registry since S3-01; `app:versions:read` and
`app:versions:write` since S4-01; `app:artifacts:upload` since S5-01;
the rest of E5 onward). The authorization engine series
(S7-01..S7-05) builds policy administration on this catalog without
changing the enforcement point.
