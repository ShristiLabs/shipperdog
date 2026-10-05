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

