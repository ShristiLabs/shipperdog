# Tester groups

Tenant-scoped logical tester collections (S6-02, issue #27, PRD 8.9,
FR-010 "assign groups"): the targeting units release targets (E9) and
ring eligibility rules (S10-04) will consume — "target Pilot" instead
of enumerating testers. Groups are tenant data (they ride the tenant
isolation seam per ADR-0002's tenth amendment); org-level groups are
out of scope by the story.

## Model

- `tester_groups(tenant_id, name)` — names unique per tenant
  (`uq_tester_groups_tenant_name`); both columns arrived in the V1
  baseline, so S6-02 needed no new migration.
- `tester_group_members(group_id, tester_id)` — composite-PK junction
  (the V1 precedent): a tester may join any number of groups, the
  same pair exists at most once, and duplicate inserts are
  database-refused. The table carries no tenant column — its tenant
  dimension is the parent group's (ADR-0002, eleventh amendment), so
  the service reaches members only through a group the seam already
  served in the request's tenant context.
- Both resolution directions are index-backed for E7: the junction
  PK's `group_id` prefix serves group→tester, `idx_tgm_tester_id`
  serves tester→groups (authorization grants will resolve groups to
  testers under the decision-latency SLO).

Since S6-03 (issue #28), group membership enforces the tenant link:
a tester may join a tenant's groups only as an effectively-active
member of that tenant — an ACTIVE `tenant_tester_memberships` row
under an ACTIVE person record (see
[tenant-tester memberships](tenant-tester-memberships.md)). A
visible-but-unlinked or suspended tester answers the explicit
`409 TESTER_NOT_TENANT_MEMBER`; a foreign organization's tester
remains indistinguishable from a missing one (concealed
`TESTER_NOT_FOUND`). The S6-02 grain — existence within the group
tenant's owning organization — still runs first, so concealment never
leaks; the pre-assignment of not-yet-active testers is no longer
possible through the API (link the tester once their record is
ACTIVE).

## Endpoints

All routes check `app:tester-groups:manage` — the catalog permission
ORG_ADMIN and QA_ENGINEER have held since S1-05 (the QA Engineer is
the story's persona); the catalog deliberately carries no separate
read grant. Item routes check it at the `'tester-group'` target (the
evaluator resolves the group's owning tenant with the owning
organization's role overlaid — the same union as tenant
permissions); denials conceal as `TESTER_GROUP_NOT_FOUND`, identical
to a genuine miss — including for platform administrators, whose
permission wildcard passes on any target but still sees the miss for
an unknown or deleted group, never a seam failure.

| Route | Behavior |
|---|---|
| `POST /api/v1/tester-groups` | create under the body's `tenantId`; `201`; `409 TESTER_GROUP_NAME_TAKEN` on a duplicate name (per tenant); `409 TESTER_GROUP_TENANT_DEACTIVATED` for a deactivated tenant; `404` unknown tenant; honors `Idempotency-Key` |
| `GET /api/v1/tester-groups?tenantId=...` | the tenant's groups, cursor-paginated, creation-ordered |
| `GET /api/v1/tester-groups?testerId=...` | the tester's groups across their organization's tenants (organization grain; name-ordered; exactly one of `tenantId`/`testerId` required — neither yields the target-less 403, both yield `400`) |
| `GET /api/v1/tester-groups/{id}` | group view |
| `PUT /api/v1/tester-groups/{id}` | rename (`409` on a taken name); seeded groups are editable like any other |
| `DELETE /api/v1/tester-groups/{id}` | delete the group and its memberships (`204`) |
| `GET /api/v1/tester-groups/{id}/members` | the group's testers, cursor-paginated, join-ordered, with person-record fields (`email`, `fullName`, `status`) and `addedAt` |
| `POST /api/v1/tester-groups/{id}/members` | add a tester; idempotent — `201 {added:true}`, re-add `200 {added:false}` (nothing re-written or re-audited); honors `Idempotency-Key` |
| `DELETE /api/v1/tester-groups/{id}/members/{testerId}` | remove; idempotent — an absent membership is a no-op `204` |

The collection routes carry the target tenant/tester in the query, so
the service establishes the tenant scope through the
`TenantScopeExecutor` (the `POST /api/v1/applications` precedent);
the item routes get their context bound by the tenant module's
tester-group route interceptor, which resolves the group's owning
tenant before the handler runs.

## Default-group seeding

Tenant creation seeds the configurable default groups inside the
creation transaction — a seeding failure fails the whole creation (a
tenant never lands half-provisioned). The default list is PRD 8.9's
examples; seeded rows are ordinary editable groups, and the seed is
skipped per name if a group of that name already exists. Tenants
created before S6-02 keep their original state (no retro-seeding);
the dev seed fixture provisions the demo tenants the same way.

| Property | Env | Default |
|---|---|---|
| `shipperdog.tester.default-groups` | `TESTER_DEFAULT_GROUPS` | `Internal QA,Tenant QA,UAT,Pilot,Executive Beta,External Beta` |

Values are comma-separated, trimmed, de-duplicated; an empty value
disables seeding.

## Deactivated tenants

The issue #9 affiliation rule extends to groups: a DEACTIVATED tenant
takes no new groups and no new memberships (`409
TESTER_GROUP_TENANT_DEACTIVATED`), but stays administrable — reads,
renames, deletions, and member removals continue, mirroring tenant
membership semantics.

## Audit events

| Event | Emitted when |
|---|---|
| `TESTER_GROUP_CREATED` | group created (seeded carries `seeded=true`; tenant id included) |
| `TESTER_GROUP_UPDATED` | rename, with `name.before`/`name.after` |
| `TESTER_GROUP_DELETED` | deletion, with the removed roster size |
| `TESTER_GROUP_MEMBER_ADDED` | a fresh membership (idempotent replays audit nothing) |
| `TESTER_GROUP_MEMBER_REMOVED` | an actual removal (no-op removals audit nothing) |

## Race discipline

Membership addition is the TesterCreationAttempt pattern (issue #26)
applied to the junction: a non-transactional orchestrator over a
transactional attempt bean, so a lost composite-key race rolls back
only that attempt and the loser is answered with the concurrent
winner (the idempotent 200) re-derived on a fresh transaction — never
a commit-time 500. Group creation races answer the precise `409`:
the insert is flushed inside the guard and the constraint is the
authority.

See [testers](testers.md) for the person record, [tenants](tenants.md)
for the tenant lifecycle, and the [authorization
matrix](authorization.md) for the permission catalog.
