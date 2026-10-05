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
