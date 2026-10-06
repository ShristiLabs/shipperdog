# Tenant-tester memberships (S6-03)

Which testers belong to which tenant, with status (PRD 8.8/13, FR-010
"assign tenant"): `tenant_tester_memberships` is the PORTAL-access
join between the organization-scoped tester person record (S6-01) and
the tenant — the first layer the E7 authorization chain evaluates
before any version grant. Implemented by the `tester` module
(`backend/src/main/java/com/shipperdog/api/tester`); the routes ride
the tenant path (`/api/v1/tenants/{id}/testers`), where the tenant
module's route interceptor binds the tenant context the isolation seam
enforces for every membership read and write (the membership carries
its OWN tenant column, so it is a `TenantScope` seam row directly —
ADR-0002 tenth amendment's reserved landing, twelfth amendment's
confirmation).

## The two membership models (deliberately separate)

| | `tenant_members` (S2-03) | `tenant_tester_memberships` (S6-03) |
|---|---|---|
| Who | console users (an account with a tenant ROLE) | tester persons (portal access) |
| Key | `(tenant, user)` + role | `(tenant, tester)` + status |
| Switch | `tenant_member_status` ACTIVE/SUSPENDED | `membership_status` ACTIVE/SUSPENDED/REVOKED (V20) |
| Permission | `tenant:members:*` | `tenant:testers:*` |
| Removal | row deleted | row RETAINED REVOKED (history) |

A tester needs no tenant role; a console member is not a tester. The
two surfaces evolve independently (the V8 note that split the status
vocabularies).

## Effective access: both switches on

Access through a membership requires the person record `ACTIVE` AND
the membership `ACTIVE` — the two-switches rule, tested in all four
combinations:

| tester status | membership status | effective |
|---|---|---|
| ACTIVE | ACTIVE | yes |
| ACTIVE | SUSPENDED | no |
| SUSPENDED | ACTIVE | no |
| SUSPENDED | SUSPENDED | no |

Effectiveness is a LIVE query — the port's own contract keeps the
documented staleness TTL at zero for every direct consumer: a
suspension or removal reflects on the very next request. Since S7-03
the layered engine's decision path resolves this probe through the
Redis decision-input cache (see
[authorization](authorization.md)): write-through invalidation drops
the cached flag inside every membership and person-status mutation
before commit, with the 15-minute TTL as the backstop — the same
next-request freshness, bounded by an invalidation-miss window.
Every listing row and item view carries the computed
`effectiveAccess` flag; the cross-module surface
(`TenantTesterMembershipAccess`) exposes `isActiveMember` /
`isEffectivelyActive` for the E7 chain, and group membership (S6-02)
enforces the same precondition — joining a tenant's groups requires
being an effectively-active member of that tenant.

## Lifecycle

```text
link (POST)              ACTIVE ---- suspend (PUT status) ----> SUSPENDED
  ^                        |  ^                                  |
  | reactivates the        |  | reactivate (PUT status)          |
  | retained row           v  |                                  |
REVOKED <------------- removal (DELETE; row RETAINED, never deleted)
```

- Only an `ACTIVE` person record can be linked (`409
  TESTER_NOT_ACTIVE`); the membership switch then varies
  independently (acceptance criterion 1).
- Suspend/reactivate is reversible and audited with before/after;
  it continues on a DEACTIVATED tenant (an existing membership is not
  a new affiliation — the issue #11 tenant-member precedent).
- Removal retains the row `REVOKED`: history stays visible in the
  read-only queries (`status=REVOKED`, `status=ALL`, the
  tenants-of-tester listing) and in the audit trail (acceptance
  criterion 4). A REVOKED row is not administrable through the status
  route (`409 TESTER_INVALID_TRANSITION`) — a re-link (POST)
  reactivates it, audited `reactivated=true`.
- A DEACTIVATED tenant takes no NEW links or reactivations (the
  issue #9 affiliation rule; `409 TENANT_DEACTIVATED`) while the
  switch and removal of existing memberships continue.
- The concurrent-link race collapses onto one row: the link route is
  a non-transactional orchestrator over the attempt's own
  transaction, and a lost `(tenant, tester)` primary-key race is
  answered with the concurrent winner re-derived on a fresh
  transaction (the #26/#27 race discipline — this story applied it to
  `TenantMemberService.addMember` too, fixing the dead in-transaction
  catch that 500'd lost member-add races).
- **Deactivation cascade (S6-04, issue #29)**: deactivating the TESTER
  (`PUT /api/v1/testers/{id}/status` with `DISABLED` — see
  [testers](testers.md)) suspends every ACTIVE membership of that
  tester in the same transaction, audited per row as
  `TENANT_TESTER_MEMBERSHIP_STATUS_CHANGED` with
  `cascade=tester_deactivated` and the mandatory reason. Rows are
  flipped, never deleted or REVOKED (INV-007): already-SUSPENDED
  rows stay SUSPENDED and REVOKED history rides through untouched.
  Reactivating the tester restores NOTHING automatically — each
  membership waits for its per-tenant re-enable (`PUT status` with
  `ACTIVE`) or re-link.

## Endpoints

Tenant-grain (base `/api/v1/tenants/{id}`, permission
`tenant:testers:read` / `tenant:testers:manage` — TENANT_ADMIN, and
ORG_ADMIN of the owning organization through the overlay):

| Method | Path | Result |
|---|---|---|
| POST | `/testers` | `201 {linked:true}` (born ACTIVE); duplicate `200 {linked:false}`; re-link of a retained REVOKED row `200 {reactivated:true}`; `409 TESTER_NOT_ACTIVE`/`TENANT_DEACTIVATED`; `404` unknown/foreign tester. Honors `Idempotency-Key` |
| GET | `/testers` | default: the EFFECTIVE view (tenant-scoped visibility); `status=ACTIVE/SUSPENDED/REVOKED`: that administrative view; `status=ALL`: every retained row. Cursor-paginated; rows carry `testerStatus`, `membershipStatus`, `effectiveAccess`, `linkedAt` |
| GET | `/testers/{testerId}` | one membership view (the four-combination observable); `404 TENANT_TESTER_MEMBERSHIP_NOT_FOUND` |
| PUT | `/testers/{testerId}/status` | `200` on ACTIVE/SUSPENDED; `409 TESTER_INVALID_TRANSITION` on a REVOKED row |
| DELETE | `/testers/{testerId}` | `204`, idempotent; the row turns REVOKED and is retained |

Organization-grain (read-only):

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/testers/{id}/tenants` | `200` the tester's tenants across their OWN organization (names, lifecycle status, membership status, `effectiveAccess`), link-ordered, history included; permission `org:testers:read` at the `tester` target |

Unknown memberships, foreign tenants, and permission denials all
render as the concealed 404 a genuine miss produces (PRD 12); all
errors use the problem+json envelope (see [API
conventions](api-conventions.md) and the [error code
registry](error-codes.md)). Mutations audit
`TENANT_TESTER_MEMBERSHIP_CREATED` / `_STATUS_CHANGED` / `_REMOVED`
(see [audit](audit.md)).

## Schema and indexes (V1 + V20)

The table is V1 baseline: `(tenant_id, tester_id)` primary key,
`status membership_status`, `created_at`, `updated_at`, and
`idx_ttm_tester_id` for the reverse direction. V20 appends the
`SUSPENDED` value (expand-only, ADR-0001; the V8 vocabulary
precedent) so suspend and removal stay distinct states. No new index:
the PK prefix `(tenant_id, tester_id)` already serves the hot
authorization probe (an equality descent that reads the row, and so
its status); tenant-grain listings walk the same prefix, and
tenants-of-tester walks `idx_ttm_tester_id`. The S7-03 cache
strategy landed WITHOUT schema (Redis above these probes — the
index stays the loader's path); no revisit owed.
