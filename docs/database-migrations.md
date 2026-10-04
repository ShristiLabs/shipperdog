# Database migrations

Flyway owns the PostgreSQL schema (ADR-0001). This page records the binding
conventions; every migration must follow them.

## Layout

```text
backend/src/main/resources/db/
  migration/            schema migrations, always applied
    V1__baseline_schema.sql
    V2__identity_auth.sql
    V3__organization_management.sql
    V4__rbac_roles.sql
  seed/                 dev fixture, applied only when the seed location is active
    R__dev_seed.sql
```

- Schema migrations: `V<n>__description.sql`, snake_case, monotonically
  increasing `n`. Never renumber, edit, or delete a migration that has landed.
- The `dev` profile appends `classpath:db/seed` to
  `spring.flyway.locations` (see `backend/src/main/resources/application.yml`);
  the default (production, clean CI) location list is `classpath:db/migration`
  only. Override with the `FLYWAY_LOCATIONS` environment variable if needed.
- The seed is a repeatable migration (`R__`) with fixed UUIDs and
  `ON CONFLICT DO NOTHING`, so re-runs are no-ops. It provides one
  organization (`external_id demo-shipperdog`), two tenants
  (`demo-acme`, `demo-contoso`), and one application; the bootstrap
  admin is a platform administrator and `ORG_ADMIN` of the organization
  (S1-04). Later integration tests rely on these identifiers.

## Table conventions

- Primary keys: `id uuid PRIMARY KEY DEFAULT gen_random_uuid()`. Pure
  junction tables use composite primary keys over their foreign keys
  (`tenant_tester_memberships`, `tester_group_members`, `version_access`,
  `release_targets`, `release_approvals`, `distribution`,
  `environment_config`).
- Audit columns on every table: `created_at timestamptz NOT NULL DEFAULT
  now()` and `updated_at timestamptz NOT NULL DEFAULT now()`. The
  application layer maintains `updated_at` (planned JPA auditing); there are
  no database triggers by decision.
- Foreign keys are declared bare (`REFERENCES` with no explicit action),
  so PostgreSQL applies its default referential action `NO ACTION`:
  deleting (or updating the key of) a referenced row is rejected. There
  is no `ON DELETE CASCADE` anywhere. Deleting rows with dependents is
  intentionally hard.
- Future migrations should name foreign keys explicitly
  (`CONSTRAINT fk_<table>_<column> FOREIGN KEY ...`). The V1 baseline
  predates this rule and carries PostgreSQL-generated
  `<table>_<column>_fkey` names; it stays untouched per the
  forward-only policy.
- Every tenant-scoped table has an index on `organization_id` /
  `tenant_id` as applicable (`idx_<table>_<column>`). Where a UNIQUE
  constraint's leading column already serves the lookup, no extra index is
  created.
- `tenant_id` on `applications` is NULL for organization-owned applications;
  the explicit `ownership_scope` discriminator arrives with S3-02.
- Forward references: `testers.user_id`, `artifacts.uploaded_by`,
  `releases.created_by`, `release_approvals.approver` referenced the
  identity user table before it existed; `V2__identity_auth.sql` (S1-03)
  created `users` and added the promised foreign keys with explicit
  `fk_<table>_<column>` names.
- `V3__organization_management.sql` (S1-04) is the template for these
  conventions: it creates `organization_members` (uuid PK, unique
  `(organization_id, user_id)`, `idx_organization_members_user_id`) and
  `organization_idp_configs` (unique `organization_id`), adds
  `organizations.external_id` (unique), `users.platform_admin`, and the
  optional invitation scope `user_invitations.organization_id` +
  `role` guarded by `ck_user_invitations_org_role_pair` (both set or
  both null). All constraints use the explicit `fk_`/`uq_`/`ck_`/`idx_`
  names.
- `V4__rbac_roles.sql` (S1-05) is the RBAC vocabulary migration: it
  expands `org_role` with the seven PRD section 7 functional roles
  (`APP_OWNER`, `DEVELOPER`, `QA_ENGINEER`, `RELEASE_MANAGER`,
  `SECURITY_REVIEWER`, `COMPLIANCE_REVIEWER`, `VIEWER`) via
  expand-only `ALTER TYPE ... ADD VALUE IF NOT EXISTS`, creates the
  `tenant_role` enum (`TENANT_ADMIN`, `TENANT_TESTER`) and the
  `tenant_members` table (uuid PK, unique `(tenant_id, user_id)` as
  `uq_tenant_members_tenant_user`, named `fk_`s to `tenants` and
  `users`, `idx_tenant_members_user_id`). Tenant membership is
  independent of organization membership. No statements in the
  migration use the new `org_role` values: PostgreSQL forbids using an
  enum value added in the same transaction, and the permission catalog
  lives in code, so no data migration is needed.

## Status and type columns

Status (and fixed-vocabulary type) columns use **PostgreSQL enum types**
(e.g. `release_status`, `artifact_status`). Enum labels mirror the PRD state
machines where they exist (`releases` per section 17, `artifacts` per
section 18); other tables carry minimal documented sets that later stories
extend.

- To add a value: new migration with `ALTER TYPE <type> ADD VALUE '...'`.
  `V3` did this for `org_status` (`DEACTIVATED`, S1-04); the V1 label
  `DISABLED` stays reserved-unused — the application never assigns it.
- Values are never removed or renamed (see rollback policy).
- `org_role` (`ORG_ADMIN`, `ORG_MEMBER` since V3) is the membership-role
  vocabulary; V4 (S1-05) extended it to nine values with the PRD
  section 7 functional roles — the full vocabulary and its
  permission mapping are documented in
  [authorization](authorization.md). `tenant_role` (`TENANT_ADMIN`,
  `TENANT_TESTER`) is the tenant-scoped counterpart, also from V4.
- Exception: `release_gates.gate_type` is `varchar(64)` because gate kinds
  are an open, integration-defined vocabulary.

## Rollback strategy (forward-only policy)

There are no down-migrations and no automated rollback.

- A bad migration is fixed by a **new** migration that corrects the damage.
- Destructive changes (drop column/table, type narrowing) follow
  expand/contract: first migrate to the new shape while keeping the old one
  writable, deploy, then remove the old shape in a **second** migration —
  never in the same step as the introduction.
- Point-in-time recovery of the database is the escape hatch for true
  disasters; Flyway history is rebuildable from source.

## Verifying migrations

- `mvn verify` runs `MigrationIntegrationTest` and `DevSeedIntegrationTest`
  against a clean Testcontainers PostgreSQL: baseline applies cleanly,
  a second migrate run applies nothing, tenant/org indexes exist, enum
  types match the documented values, and the seed fixture is present when
  the seed location is enabled.
- Locally, `docker compose up` runs the API with the `dev` profile: Flyway
  applies schema plus seed on startup and the health endpoint reports UP.
