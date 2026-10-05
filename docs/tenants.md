# Tenants (S2-01)

Tenants are the customer/business entities that make ShipperDog
multi-tenant (PRD 8.2): every tenant belongs to exactly one
organization, and the organization's administrators manage the tenant
lifecycle. A tenant is never a user (PRD 8.2/8.8) — it has no login
surface and no identity coupling. Members (users holding tenant roles)
arrived with S2-03; tester records themselves arrive with S6. The
admin console drives this entire surface for organization
administrators since S2-04 (see [frontend](frontend.md)).
Implemented by the `tenant` module
(`backend/src/main/java/com/shipperdog/api/tenant`). Permissions
referenced below are enforced by the single RBAC enforcement point —
see [authorization](authorization.md) for the matrix and semantics.

## Endpoints (base `/api/v1/tenants`)

| Method | Path | Permission | Result |
|---|---|---|---|
| POST | `/` | `org:tenants:create` at the body's `organizationId` | `201` tenant view (born `ACTIVE`); `400` invalid name/externalId; `404` unknown organization; `409 TENANT_NAME_TAKEN`/`TENANT_EXTERNAL_ID_TAKEN` on duplicates. Honors `Idempotency-Key` (S1-08) |
| GET | `/?organizationId=...&status=...` | `org:tenants:read` at that organization | `200` the organization's tenants, cursor-paginated (`limit`/`cursor`); optional `status` filter (`ACTIVE`/`DEACTIVATED`); `400` missing/invalid `organizationId` |
| GET | `/{id}` | `tenant:tenants:read` at `{id}` | `200` tenant view |
| PUT | `/{id}` | `tenant:tenants:update` at `{id}` | `200`; partial update — null fields unchanged (`name`, `externalId`, `status`); `400` invalid values; `409` name/externalId taken |
| GET | `/{id}/members` | `tenant:members:read` at `{id}` | `200` the tenant's roster with roles and membership status, cursor-paginated (S2-03) |
| POST | `/{id}/members` | `tenant:members:manage` at `{id}` | `201` member view (born `ACTIVE`); same-role duplicate idempotent `200`; `409 TENANT_MEMBER_EXISTS` (different role) / `TENANT_DEACTIVATED`; `404` unknown user/tenant. Honors `Idempotency-Key` (S2-03) |
| PUT | `/{id}/members/{userId}/role` | `tenant:members:manage` at `{id}` | `200`; creates the membership when absent (blocked while deactivated) |
| PUT | `/{id}/members/{userId}/status` | `tenant:members:manage` at `{id}` | `200`; `ACTIVE`/`SUSPENDED` transition — suspending revokes the member's refresh tokens (S2-03) |
| DELETE | `/{id}/members/{userId}` | `tenant:members:manage` at `{id}` | `204`; removal revokes the member's refresh tokens |
| GET | `/{id}/applications` | `tenant:applications:read` at `{id}` | `200` the tenant's own applications, cursor-paginated (S2-03) |

Tenant administrators hold `tenant:tenants:read` on their own tenant
but not `tenant:tenants:update` — the lifecycle is organization-level
administration (the owning organization's `ORG_ADMIN` passes
tenant-scoped checks through the role union rule).

Unknown tenants are `404` for permitted callers; target-scoped
permission denials render as concealed `404`s with the same shape
(PRD 12), and all errors use the problem+json envelope (see
[API conventions](api-conventions.md)).

## Identifier semantics

Two uniqueness guards, both database-backed and race-safe:

- **`name`** — the within-organization identifier
  (`uq_tenants_org_name` on `(organization_id, name)`): two
  organizations may each have an "Acme Bank", one organization may not
  have two. Duplicate within the org is `409 TENANT_NAME_TAKEN`
  (issue #9, acceptance criterion 1).
- **`externalId`** — the customer-system mapping (billing/CRM), required
  and globally unique (`uq_tenants_external_id`): one customer record
  maps to exactly one tenant across the platform. Duplicate is `409
  TENANT_EXTERNAL_ID_TAKEN`.

The service pre-checks both for precise codes and re-derives the
conflicting guard after a lost race (`DataIntegrityViolationException`
on the constraint is the final authority — no driver-message parsing).

## Lifecycle semantics

- Tenants are born `ACTIVE` (V1 default).
- `DEACTIVATED` (V7 adds the enum value; V1's `DISABLED` label stays
  reserved-unused, mirroring `org_status`):
  - **reversible** — `PUT /{id}` with `"status": "ACTIVE"` restores it;
  - **blocks new affiliations** — adding a membership to a deactivated
    tenant is `409 TENANT_DEACTIVATED` (the membership analog of "no
    new invitations"); tester invitations (S6-01) and distributions
    (S7) check the same state when they arrive;
  - **preserves everything** — no cascade, no deletion; existing
    members, applications, and data remain intact and readable
    (acceptance criterion 3).
- Every mutation writes an audit event — `TENANT_CREATED` with the
  created attributes, `TENANT_UPDATED` with `field.before`/`field.after`
  pairs for each changed field — carrying actor, organization, and
  tenant ids (see [audit logging](audit.md)).

## Members (S2-03)

Tenant members are org users holding a tenant role (`TENANT_ADMIN` or
`TENANT_TESTER`; tenant testers need not belong to the owning
organization). `POST /{id}/members` assigns a user with a role —
memberships are born `ACTIVE`, a same-role duplicate assignment is
idempotent (`200`, no second row, no audit — nothing changed), and a
different-role duplicate is `409 TENANT_MEMBER_EXISTS` (role changes
belong to the role endpoint). Assignment to a deactivated tenant is
blocked like every new affiliation.

Every membership carries its own status, independent of the user
account status — **both switches must be on for access** (issue #11
technical notes):

- the **account switch** lives with identity (disabled accounts cannot
  log in or refresh);
- the **membership switch** (`tenant_members.status`, V8) is
  `ACTIVE`/`SUSPENDED`. Only ACTIVE memberships resolve to tenant
  roles, so a suspended member loses tenant permissions on their next
  request; suspending also revokes their refresh tokens
  (`membership_suspended`), so their tenant context ends at the next
  token refresh at the latest. Suspension is reversible (reactivation
  revokes nothing), keeps the row, and never touches the owning
  organization's roles — the org overlay survives suspension.

Member management is administrators-only: `tenant:members:manage`
(tenant administrators and the owning organization's administrators).
Tenant testers hold no member permissions at all — their read surface
is the applications listing; even the roster is concealed from them.
Removal deletes the membership and revokes the member's refresh tokens
(`membership_removed`). Every change is audited:
`TENANT_MEMBER_ADDED` (role + birth status),
`TENANT_MEMBER_ROLE_CHANGED`, `TENANT_MEMBER_STATUS_CHANGED`
(`status.before`/`status.after`, `sessionsRevoked`), and
`TENANT_MEMBER_REMOVED`.

## Applications (S2-03, S3-01, S3-02)

`GET /{id}/applications` returns the applications the tenant owns —
the `applications.tenant_id` rows for that tenant, cursor-paginated
and carrying the registry fields (platforms, identifiers,
description, status, ownership scope since S3-01/S3-02), readable by
both tenant roles (`tenant:applications:read`). The
listing runs through the isolation seam, so rows of other tenants and
organization-shared applications (null `tenant_id`) are invisible by
construction. FR-002's "assign applications" write side arrived with
the application registry: creation and management are S3-01
(`POST`/`GET`/`PATCH /api/v1/applications`, organization-level
`app:applications:write` authority — see
[authorization](authorization.md)). Ownership scope (S3-02, PRD 14):
an application is `TENANT`-owned — exactly one owning tenant — or
`ORGANIZATION`-shared, owned by the organization itself, readable by
organization roles org-wide and convertible both ways through an
audited `APPLICATION_SCOPE_CHANGED` transition
(`PATCH /api/v1/applications/{id}` with `ownershipScope`; converting
to TENANT names the new owner tenant and enforces the INV-002 checks).
Shared applications — an application reachable from more than one
tenant — arrive with S3-03 and will extend the tenant listing;
distribution decides which tenants receive a shared application,
never the other way around (PRD 14/15). A deactivated
tenant keeps a readable listing (deactivation blocks new affiliations,
never reads).

## Isolation (S2-02, INV-001)

Tenant isolation is enforced in two layers, so no endpoint or future
query can leak cross-tenant data:

- **Endpoint layer** (since S1-05): every tenant-scoped route carries a
  `@PreAuthorize` target check; callers without a role in the tenant
  (or its owning organization) receive the concealed `404` — identical
  shape to an unknown tenant.
- **Data layer** (S2-02): the request's tenant context filters every
  tenant-scoped query at the repository seam — `tenantId = context` is
  composed into the query by construction, a query without a context
  fails closed, and cross-tenant writes are refused. The inherited
  `JpaRepository` surface is enforced or refused the same way, guarded
  by a reflective completeness test against Spring Data upgrades
  (ADR-0002 amendment). See
  [authorization](authorization.md) and
  [ADR-0002](adr/ADR-0002-tenant-isolation-data-seam.md).

Platform administrators may operate on any tenant (PRD 7.1); since
S2-02 that override is explicit and audited: every platform-admin
request on a tenant-scoped route records a `CROSS_TENANT_ACCESS` audit
event (actor, tenant, method/URI). Organization roles can never take
this path.

### Deactivated tenants (recorded decisions)

Deactivation blocks **new** affiliations only; a deactivated tenant
stays administrable so it can be inspected and reactivated:

- member listings stay readable to the tenant's administrators;
- role changes, suspension/reactivation, and membership removals stay
  possible for administrators of the owning organization;
- data-layer isolation is identical in every lifecycle state — the seam
  filters by tenant context regardless of status.
