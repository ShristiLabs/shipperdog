# ADR-0002: Predicate-injection repository seam for tenant isolation

- **Status:** Accepted
- **Date:** 2026-10-05
- **Issue:** #10 (S2-02 — Tenant isolation enforcement)

## Context

INV-001 (tenant A can never see tenant B's data) must be enforced at the
data-access layer by construction — not by endpoint discipline — so no
future endpoint or query can accidentally leak cross-tenant data
(PRD 26). The issue shortlisted two mechanisms: a Hibernate `@Filter`
enabled per session, or Spring Data predicate injection through a
tenant-scoped repository base.

## Decision

Use **Spring Data predicate injection** as the single enforcement seam:

- A request-scoped `TenantContext` (thread-local) carries the target
  tenant, the actor, and the platform-admin override flag. The tenant
  web layer binds it for `/api/v1/tenants/{id}` routes and clears it
  after every request.
- `TenantScopedRepository<T extends TenantScope>` is the mandatory base
  interface for repositories over tenant-scoped entities. Its base-class
  implementation (`TenantScopedRepositoryImpl`) composes an index-backed
  `tenantId = :context` predicate into every query and refuses to run
  without a context (fail-closed throws, never an unscoped query).
- Architecture tests make the seam structural: repositories over
  `TenantScope` entities must extend the seam or be explicitly marked
  `@CrossTenantByDesign` (authorization-engine surface only); seam
  repositories may not declare derived query methods; raw
  `EntityManager`/`Session` usage is banned outside repository packages
  (one recorded pre-existing exception: the audit module's criteria
  reader over platform rows).

Reasons:

1. **Fail-closed by construction.** An unbound context is an exception,
   not a query without a filter. `@Filter` fails the other way: a filter
   that is not enabled is simply absent, so the guarantee depends on
   per-session enablement hooks that this stack does not natively
   guarantee for every session — an enablement gap would be a silent
   fail-open.
2. **Authorization data needs a sanctioned cross-tenant path.** Role
   resolution ("which roles does this user hold, in any tenant") is
   inherently cross-tenant and feeds the evaluator itself. The seam
   model gives that surface an explicit, reviewable marker
   (`@CrossTenantByDesign`); an always-on filter would have to be fought
   from inside the authorization engine.
3. **It matches the codebase's explicit-seam style.** One inspectable
   predicate-composition point (like `UserRolesSnapshot` and
   `RbacPermissionEvaluator` for authorization), no bytecode or session
   lifecycle magic, and plain Spring Data query machinery underneath
   (pagination, count, hints).
4. **Index-backed from day one.** The predicate is an equality on
   `tenant_id`, which every tenant-scoped table already indexes
   (`uq_tenant_members_tenant_user`, `idx_applications_tenant_id`,
   `idx_release_targets_tenant_id`, ...). No schema change was needed.

## Consequences

- M1 tenant-scoped entities with JPA mappings today are
  `tenant_members` (as endpoint data); its guard tests exercise the
  seam directly. Applications, versions, artifacts, testers, and groups
  do not have JPA entities yet (E3+) — when they land, their entities
  implement `TenantScope` and their repositories extend the seam, or
  the architecture tests fail the build. Isolation is therefore proven
  now and inherited later by construction.
- The platform-admin override is explicit and audited: set only from
  `users.platform_admin` (never reachable by organization roles), and
  every platform-admin tenant-scoped request is recorded as a
  `CROSS_TENANT_ACCESS` audit event. Reads stay scoped to the route's
  tenant even under the override; the override authorizes
  administrative cross-tenant writes through `saveScoped`.
- Background jobs touching tenant-scoped data must begin/end their own
  `TenantContext` explicitly; with none bound, the seam refuses.
- Hibernate `@TenantId` was also considered and rejected for the same
  reason as `@Filter` (null/absent tenant resolution is fail-open in
  the resolver contract) plus it cannot express the audited
  platform-admin override path this story requires.

## Amendment (2026-10-05): the inherited JpaRepository surface

Review of the first cut found a HIGH defect: `TenantScopedRepository`
extends `JpaRepository`, and the implementation overrode none of the
inherited CRUD surface — `findById`, `save`, `deleteAllInBatch` and
friends ran without the tenant predicate (live-proven cross-tenant
reads, foreign-tenant writes, and a bulk wipe of every tenant), and the
derived-query architecture rule only bans *declared* methods, so the
inherited surface was invisible to it. The seam now overrides the
entire inherited surface with a fixed policy:

- **Enforced** where a tenant-safe meaning exists: `findById`,
  `findAllById`, `existsById`, `count` compose the tenant predicate (an
  id of another tenant is indistinguishable from an unknown id — the
  same concealment the scoped operations provide); `save`, `saveAll`,
  `saveAndFlush`, `saveAllAndFlush`, and `delete(entity)` verify the
  entity's tenant equals the context — or the audited platform-admin
  override — before delegating; `deleteById` removes only a row of the
  context tenant and treats any other id as a no-op, matching Spring
  Data's own semantics.
- **Refused** with `TenantIsolationException`, with a context bound or
  without one, when no tenant-safe meaning exists: the
  unparameterized and bulk shapes (`findAll()`, `deleteAll*`,
  `deleteAllInBatch*`, `deleteInBatch` — mass-destruction primitives),
  the lazy proxy getters (`getOne`, `getById`, `getReferenceById` — a
  proxy defers the load past any check), and the entire
  `Example`/`findBy` family (a probe with a null `tenantId` matches
  every tenant, so it cannot be scoped by construction).
- **Passed through**: `flush()` alone — it pushes already-guarded
  pending work and issues no query of its own.

Un-refusable is impossible by construction: refusal never depends on a
context being bound, so the worst case stays an exception, never an
unscoped query. Because the base class serves *every* repository, not
only seam repositories, each override applies only when the domain type
implements `TenantScope`; plain repositories keep stock Spring Data
behavior.

Two guards keep the policy complete against future drift:

- `TenantScopedSurfaceCompletenessTest` reflectively enumerates the
  whole `JpaRepository` surface and requires every operation to be
  overridden (a signature-exact allowlist admits only `flush()`) and to
  fail closed without a context — a Spring Data upgrade that adds
  operations the seam does not account for fails the build loudly
  instead of silently reopening the seam.
- An architecture rule rejects seam repositories that extend
  `JpaSpecificationExecutor` or `RevisionRepository`: both surface
  stock Spring Data query methods that declare nothing on the
  subinterface (invisible to the derived-query rule) yet run without
  the tenant predicate. The rule was proven to fire on a violating
  interface.

## Second amendment (2026-10-05): the application registry's organization-scope guard

The application registry (issue #13, S3-01) introduced the first
cross-tenant surface outside the authorization engine:
`ApplicationRegistryRepository`, marked `@CrossTenantByDesign`. Two of
the story's own questions span tenants by definition and have no
tenant-scoped meaning:

1. **Ownership resolution** — the RBAC evaluator's `app:` branch and the
   route-context binding must answer "which organization and tenant own
   this application?" before any tenant context exists. This is
   authorization data in the same sense as role resolution, and it is
   served through the application module's public `ApplicationAccess`
   port (ownership tuples only, never rows).
2. **Organization-wide uniqueness guards** — issue #13's acceptance
   criterion 2 makes bundle id / package name unique per
   organization+platform, so the pre-checks and the post-race
   re-derivation necessarily span the tenants of one organization. They
   answer existence booleans, never rows, and every creator of an
   application holds an organization-level role
   (`app:applications:write` is org-role-granted only), so the probes
   reveal nothing the caller is not entitled to know at organization
   scope.

The sanctioned-surface rule therefore reads: repositories over
`TenantScope` entities extend the seam, or are marked
`@CrossTenantByDesign` with a javadoc justification — the authorization
engine's role resolution and, since S3-01, registry guards whose
question is organization-scoped by the issue's own definition. Marker
carriers must never serve endpoint data paths: application rows are
read and written only through the seam
(`ApplicationScopedRepository`), whose inherited-surface policy above
is unchanged.

Creation also needed a sanctioned way to establish a scope when the
target tenant arrives in the request body (`POST /api/v1/applications`)
and no route interceptor can bind it from the path:
`TenantScopeExecutor` (tenant module) establishes the scope at the
point of use — establish-only (it refuses when any context is already
bound, so the route interceptors always win on tenant-addressing
routes), derives the platform-admin override from
`users.platform_admin` exactly like the interceptor, and audits every
platform-administered execution as `CROSS_TENANT_ACCESS`. It is the
same binding authority, reviewable in one place, and doubles as the
background-job entry point the original decision anticipated.

## Third amendment (2026-10-05): the organization-scope context and the sanctioned scope transition

The ownership scope (issue #14, S3-02, PRD 14) makes organization-owned
rows real: an application is either tenant-owned (one owning tenant) or
organization/shared (null tenant, owned by the organization itself,
distributed to tenants only through S3-03's authorization mapping —
distribution decides, never the other way around). Both facts below
followed from that model:

1. **Organization-owned rows stay behind the seam — via an
   organization-scope context.** The seam's predicate
   (`tenantId = :context`) can never match a null-tenant row, so a new
   context mode serves them: `TenantContext.beginOrganization(orgId,
   actor, override)` (the same fail-closed binding authority as
   `begin`), composed by the seam as
   `tenantId IS NULL AND organizationId = :context` for entities
   implementing the `OrganizationScope` marker (`applications` is the
   first). An organization-scope context over a purely tenant-owned
   entity refuses — no organization-owned meaning exists for its rows —
   and the write guard accepts an entity only when it belongs to the
   context's owning dimension (tenant or organization). The route
   interceptor resolves the application's ownership and binds the
   owning tenant's or the owning organization's scope, and the
   `TenantScopeExecutor` gained the matching establish-only
   `inOrganizationScope` for creation routes and background jobs.
   INV-001 therefore holds for both ownership modes by the same
   construction: neither mode can read or write the other's rows.
2. **The scope transition is the one sanctioned cross-scope write.**
   Converting an application between TENANT and ORGANIZATION ownership
   moves the row between scopes by definition — no single scoped
   context can own both sides of the write, so the seam's write guard
   must refuse it everywhere. The registry guard's single declared,
   id-parameterized `transitionScope` update is the one exception,
   called only from `ApplicationService` after its precondition checks
   (INV-002 service checks on the future owner tenant; the
   authorized-tenants precondition binds when S3-03's mapping exists;
   the zero-distributions precondition binds when M2's release targets
   exist) and audited as `APPLICATION_SCOPE_CHANGED` with before/after
   pairs. It remains true that the marker carrier serves no endpoint
   data path: reads and ordinary writes of application rows still go
   only through the seam.

V1's per-scope name index (`uq_applications_scope_name`) was replaced
by the organization-wide `uq_applications_organization_name` (V10): the
registry's documented name guard has been organization-wide since S3-01
(the error registry says "in use in the organization"), and the
per-scope shape silently under-enforced cross-scope races — two tenants
of one organization could concurrently register the same name with no
index collision. The database is again the final authority for the
documented semantics.

## Fourth amendment (2026-10-05): the distribution-authorization mapping

The tenant-application authorization mapping (issue #15, S3-03, PRD 15)
made organization-shared distribution real: which tenants an
organization's shared applications are authorized for is held as
explicit grant rows (`tenant_application_authorizations`, V11 — one
retained row per application x tenant pair, ACTIVE/REVOKED status
flips, revocation never deletion). Two facts followed:

1. **The mapping is authorization data served by a marked repository —
   neither seam mode can hold it.** A grant row points from an
   organization's distribution decision into one of its tenants; the
   mapping's questions span tenants by definition (an organization's
   authorized-tenant list, the ORGANIZATION-to-TENANT transition
   precondition, "which shared applications may tenant T see?"). A
   tenant-scoped context answers none of them (it sees exactly one
   tenant), and the organization-scope predicate composes
   `tenantId IS NULL` while grant rows carry the authorized tenant —
   so `TenantApplicationAuthorizationRepository` is
   `@CrossTenantByDesign` with declared queries only, the second
   amendment's authorization-data pattern (role resolution's equal).
   The entity implements neither `TenantScope` nor `OrganizationScope`:
   the marker, not a shoehorned scope, carries the sanctioned-ness.
   Application rows themselves never move: the mapping service loads
   the application through the seam inside the route's bound scope
   (the owning organization's, bound by the interceptor), so a foreign
   application is the standard concealed miss before any mapping row
   is touched.

2. **Effective visibility is one declared union query on the registry
   guard.** "Which applications does tenant T see?" resolves as
   tenant-owned rows UNION shared rows carrying an ACTIVE grant for T —
   one arm of that union is null-tenant organization rows that no
   tenant-scoped query can ever match, so the resolution spans scopes
   by definition and lives beside the registry's other sanctioned
   questions (`findVisibleToTenant`). INV-001 holds inside the union by
   construction: the owned arm is exactly `tenantId = T` and the shared
   arm requires an ACTIVE row naming T — no other tenant's rows can
   satisfy either. The portal/catalog listing
   (`TenantApplicationService.listVisibleToTenant`) is the single
   tested resolution point the E7 authorization chain and E8 consume;
   nothing else re-derives visibility. Shared visibility stays opt-in
   (INV-005): without an explicit grant the union degenerates to the
   tenant's own rows, exactly the pre-S3-03 listing.

## Fifth amendment (2026-10-05): the organization-wide registry listing

The application console story (issue #16, S3-04) exposed a reachability
gap the fourth amendment created by design: a shared application with
zero ACTIVE grants is invisible to every effective-visibility listing
(PRD 15 — distribution decides, INV-005), and the registry offered no
other listing — so after registration the organization roles who must
grant the first authorization could no longer reach the application
through any API surface. The console's administration listing
(`GET /api/v1/applications?organizationId=...`) closes the gap and is
a declared query on the registry guard
(`findByOrganizationIdOrderByCreatedAtAscIdAsc`), for the same reason
the union query is: "every application of organization O, both
ownership scopes" is organization-addressed by definition, and neither
seam mode can answer it whole (tenant mode sees one tenant's rows;
organization mode composes `tenantId IS NULL` and would hide the
tenant-owned arm entirely).

INV-001 holds by the query's own predicate — `organizationId = :org`
with no tenant predicate at all — and the caller's authority to ask is
checked before it runs: `app:applications:read` at the organization
target, granted only to organization roles (the evaluator
conceal-denies tenant-only actors and foreign organizations as
`ORGANIZATION_NOT_FOUND`, so the listing leaks nothing about another
organization's registry). The marker carrier still serves no write and
no per-row data path; this amendment adds one read query of the same
character as the union — an organization-wide question the seam cannot
scope — and the doctrine is unchanged: every cross-scope question the
product asks stays a declared, review-critical query in one place.

## Sixth amendment (2026-10-05): versions join the seam through the ownership snapshot

The version layer (issue #17, S4-01, PRD 8.4) made version rows real,
and two facts followed from where versions sit: every version route is
application-addressing (`/api/v1/applications/{id}/versions`), so the
existing route interceptor already binds the application's owning scope
— and the version rows themselves must be servable inside that scope.

1. **Version rows carry their application's owning-dimension snapshot
   and are served by the seam in both modes.** V12 adds
   `organization_id` (NOT NULL, referencing organizations) and
   `tenant_id` (nullable, referencing tenants) to the V1 `versions`
   table — exactly the shape `applications` itself carries — and
   `VersionEntity` implements `OrganizationScope` like the application
   entity. A tenant-scope context serves the owning tenant's version
   rows (`tenantId = :context`), an organization-scope context serves
   the organization's null-tenant rows (`tenantId IS NULL AND
   organizationId = :context`), and neither mode can reach the other's
   rows — INV-001 for versions by the same construction as
   applications, with reads and writes flowing only through
   `VersionScopedRepository`. Creation copies the snapshot from the
   application's profile (tuples, through the public
   `ApplicationAccess` port), never from the caller, so the seam's
   write guard is the final authority that a new row belongs to the
   request's bound scope.

2. **The ownership-scope transition carries versions along — the
   sanctioned companion of the third amendment's transition.** An
   application converting between TENANT and ORGANIZATION ownership
   moves its version rows between scopes by definition: left behind,
   they would strand in a scope the seam no longer binds for the
   application's routes, invisible to every later request. The single
   declared, application-parameterized update
   (`VersionRegistryRepository.reassignOwnership`, on a
   `@CrossTenantByDesign` guard of the same character as the registry
   guard's `transitionScope`) runs inside the application module's
   audited transition, in the same transaction, and the audit event
   carries the count (`versionsCarried`). Creation guards the reverse
   interleaving — a transition committing while a version insert is in
   flight — with a post-flush ownership re-read that follows the same
   sanctioned update; the residual commit-ordering window keeps the
   seam's fail-closed guards as its authorities, so no interleaving
   can produce an unscoped write. Artifacts (E5) anchor to versions,
   not to applications, so nothing else follows the application row.

## Seventh amendment (2026-10-05): version tags, the first landed child of a seam-served version

The sixth amendment anticipated children anchoring to versions through
its artifacts note; the tag junction (issue #18, S4-02, FR-009) is the
first one to land, and it exercises that standing rather than changing
any seam rule. `version_tags` (V13) is a pure `(version_id, tag)`
junction: **the table carries no owning-dimension snapshot and is
served by no seam repository** — every data path resolves the version
through `VersionScopedRepository` inside the request's bound scope
first, then reads or writes only that version's tag rows, and the
listing's tag filter is a subquery composed with (never instead of)
the seam's forced scope predicate. Isolation stays enforced at the
parent the tags hang off, exactly as the sixth amendment's artifacts
clause describes. The replace-set's per-version advisory lock lives
on the child repository precisely because the seam forbids declared
methods on scoped repositories — a serialization point is not a data
path, so it needs no seam standing of its own.

## Eighth amendment (2026-10-06): plain children of seam-served parents are standing doctrine

The lifecycle's status timeline (issue #19, S4-03, FR-008/FR-024)
lands as the second such child — `version_status_history` (V14),
exactly the seventh amendment's shape: no owning-dimension snapshot,
no seam repository, every path resolving the version through the
scoped parent first. Two landed instances make the pattern doctrine
rather than a one-off judgment call: **a table whose every row hangs
off exactly one seam-served parent and is never addressed by its own
routes is a plain child** — it carries no snapshot columns and joins
no seam repository, because re-deriving the parent's isolation at the
child would duplicate the seam's authority (and drift from it);
enforcing it at the parent is one guard, not two. The amendment also
records the pattern's second corollary, first exercised by the tags
lock: a child repository may carry the parent-path's serialization or
compare-and-set points (`version_status_history`'s
`transitionStatusIfCurrent` is the lifecycle's CAS on the parent row)
because the seam's no-declared-methods rule and the registry's
contract binding leave nowhere else legitimate for them, and a
serialization point is not a data path. Future children of versions
— artifacts (E5) among them — inherit this standing by default and
need a new amendment only if they break the every-row-hangs-off-one
parent invariant.

## Ninth amendment (2026-10-06): addressable, cross-parent children rejoin the seam

Artifacts (E5, issue #20, V15) are the first children of versions that
the eighth amendment's own rule pushes back out: a plain child is a
table "whose every row hangs off exactly one seam-served parent and is
never addressed by its own routes", and artifacts break that invariant
twice. The artifact collection route addresses them directly —
`POST /api/v1/artifacts` carries the version id in the request body
(FRD-004's shape, PRD 19), so no version-parent route ever mediates
the write — and the same-content detection of issue #20's fourth
acceptance criterion reads artifacts across every version of the
application, a query with no single parent to resolve first.

The amendment's rule: **a child addressed by its own routes, or read
across parents, carries the full owning-dimension snapshot
(`organization_id` always, `tenant_id` for tenant-owned rows — the V12
versions shape) and joins the seam through a scoped repository**;
strictly single-parent children (version_tags, version_status_history)
stay plain under the eighth amendment. Artifacts implement
`OrganizationScope` and are served by `ArtifactScopedRepository`; the
transient `artifact_upload_sessions` rows follow the same shape —
ephemeral is not exempt from INV-001, and the seam is what makes
finalize's scoped replay read work. This is not a relaxation of the
plain-child doctrine but its boundary: joining the seam is strictly
more isolation (the write guard and the forced predicate both apply),
reserved for rows that cannot inherit a single parent's scope at the
moment they are addressed.

## Tenth amendment (2026-10-06): testers are organization-scoped person records, not seam rows

The tester module's first landing (E6, issue #26, V19) forces the
question this ADR's consequences line has carried since day one:
"testers ... when they land, their entities implement `TenantScope`
and their repositories extend the seam, or the architecture tests
fail the build." They do not — because that line misread PRD 13's
simplified sketch. A tester record is a PERSON (PRD 8.8), not
tenant-scoped data: the tenant relationship is the
`tenant_tester_memberships` junction (S6-03), and a tester's row has
no tenant dimension to filter by. What the record DOES need is an
organization dimension, so one organization's tester funnel never
surfaces another organization's testers (INV-001 read isolation,
applied at the organization grain instead of the tenant grain).

The amendment's rule: **organization-scoped records that carry no
tenant dimension use plain repositories plus explicit
organization-scoped queries** (`findByOrganizationId...`,
`uq_testers_organization_email`), and the RBAC evaluator resolves
their owning organization through a public `TesterAccess` hop — the
exact pattern `application`/`version` targets follow — so every
route over the record is permission-checked at its organization.
`TenantScope` remains reserved for rows with a real tenant dimension;
when `tenant_tester_memberships` (S6-03) and `tester_groups` (S6-02,
tenant-scoped per PRD 13) land, THEY join the seam, and the tester
person record stays a plain organization-scoped parent exactly like
organizations themselves. The consequence line above is corrected by
this amendment rather than edited in place (decision records are
append-only history).

## Eleventh amendment (2026-10-06): tester groups join the seam; junctions may inherit a parent's scope

The tenth amendment reserved this landing: `tester_groups` (S6-02,
issue #27, V1's tables) ARE tenant-scoped user data — the entity
implements `TenantScope`, its endpoint repository extends the seam,
and group routes bind the request's tenant context through the same
route-interceptor authority applications use (the group's owning
tenant, resolved via the tester module's public `TesterGroupAccess`).
Nothing about the plain-child doctrine changes for the groups
themselves.

The new question the landing forces is the junction:
`tester_group_members(group_id, tester_id)` carries NO tenant column
(PRD 13 — the V1 composite-PK precedent), so it cannot implement
`TenantScope` and cannot ride the seam's predicate. The amendment's
rule: **a junction whose every row belongs to exactly one
tenant-scoped parent is reached only THROUGH that parent** — the
service loads the group through the seam under the request's bound
context first, then addresses the junction by that group's id, so
cross-tenant junction rows are unreachable by construction (the
delete path removes children before the guarded parent delete). The
tester-direction read (`idx_tgm_tester_id`) is organization-grain by
nature — a tester's groups span the organization's tenants — and runs
through a `@CrossTenantByDesign` catalog constrained to the tester's
own organization's tenants, the same review-critical carve-out the
authorization engine's role resolution uses, with the caller's
permission checked at the tester target before any row moves.

Consequences: `TenantScope` remains the marker for rows with their own
tenant dimension; future junctions over tenant-scoped parents
(`version_access`-style) follow the via-parent rule; and the groups
catalog joins `TenantMemberRepository` on the review-critical list —
it must never serve endpoint data paths, only ownership hops and the
organization-grain listing.

## Twelfth amendment (2026-10-06): tenant-tester memberships join the seam directly; composite-key seam rows order by their real columns

The tenth amendment reserved this landing too: when
`tenant_tester_memberships` (S6-03, issue #28, V1's table + V20's
`SUSPENDED` value) arrived, it would join the seam. Unlike
`tester_group_members` — the eleventh amendment's via-parent junction,
which carries no tenant column — this junction carries its OWN
`tenant_id`, so the amendment's boundary now has both sides settled:
**a junction that owns its tenant dimension implements `TenantScope`
and rides the seam directly (an `@IdClass` composite key
`(tenant_id, tester_id)` is no obstacle); a junction whose tenant
dimension is a single tenant-scoped parent's follows the via-parent
rule.** The membership's routes ride the tenant path
(`/api/v1/tenants/{id}/testers`), where the existing route interceptor
binds the context the seam enforces; the tester-direction read
(tenants-of-tester) is organization-grain by nature and runs through a
`@CrossTenantByDesign` catalog constrained to the tester's own
organization's tenants — the eleventh amendment's carve-out pattern,
now twice-established.

One mechanical consequence this landing surfaced: the seam's platform
listing order ends in the derived `id` attribute, and Spring Data
cannot translate an order over an `@IdClass` entity's derived id path
(the path is not a singular bindable attribute — a `ClassCastException`
at query assembly). The rule: **composite-key seam rows pass their
EXPLICIT listing order (link time, then a real composite-key column as
the deterministic tiebreaker)**; sorted pageables already bypass the
default, so the seam contract itself is unchanged and future
composite-key seam rows follow the same shape.

## Thirteenth amendment (2026-10-06): the layered engine's artifact decision-input guard

The authorization engine's first landing (E7, issue #30, S7-01)
forces the same question the registries answered in the second
amendment, one level deeper: PRD 11/12's artifact layer ("Is the
artifact available?") must resolve the artifact's version, platform,
and lifecycle status BEFORE any request scope exists, because the
engine decides before the route serves — the artifact collection
route is body-addressed (`GET /api/v1/artifacts/{id}?version_id=`),
so no route interceptor binds a scope the seam could enforce.
`ArtifactRegistryRepository` is therefore `@CrossTenantByDesign`
with exactly one declared operation (`findArtifactById`), served
through the public `ArtifactAccess` port as decision tuples only
(`stateOf`: id, version, platform, status — never rows, never an
endpoint data path): the same authorization-data character as
`findApplicationById` and `findVersionById`, one more level down
the chain. The engine's own reads of that port are point lookups by
primary key, so the decision path stays index-backed (the PRD 42
under-100 ms SLO); the seam-served `ArtifactScopedRepository`
remains the only data path for endpoint reads and writes, unchanged.

The landing also settles the #28 follow-up homed here: the
two-switches effectiveness resolution (`isEffectivelyActive`) is a
membership row by the `tenant_tester_memberships` primary key
`(tenant_id, tester_id)` plus the person row by its primary key —
both PK lookups, no additional index warranted; no migration was
added by S7-01.

## Fourteenth amendment (2026-10-06): the grant catalog joins the cross-tenant-by-design family

S7-02 (issue #31) makes `version_access` a tenant-scoped seam table
(grant administration rides `/api/v1/tenants/{id}/authorization-grants`
under the seam's bound context) and adds a second reader that cannot
run under it: `VersionAccessGrantCatalog`, the layered engine's
grant-entitlement probe. The engine decides mid-request on surfaces
(the artifact read, the inspection API) that bind no tenant context,
exactly like the role resolution of the tenth-through-thirteenth
amendments' catalogs — so the catalog carries `@CrossTenantByDesign`,
declares only its sanctioned questions (the subject's grants for an
application, by tenant and — for organization-shared applications,
whose owning tenant is null — across tenants), and never serves
endpoint data paths. The paired probe indexes
(`idx_version_access_grant_subject`, `idx_version_access_tester_application`)
keep every question an index descent; the isolation test pairs the
marker with the seam rule as for every predecessor.
