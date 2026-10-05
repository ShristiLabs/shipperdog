-- V10__application_ownership_scope.sql
-- Application ownership scope for ShipperDog (issue #14, S3-02).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds a type, a
--     column, and a check. The one index replacement below is a constraint
--     TIGHTENING to the semantics the registry already documents — see the
--     note there; it drops no data and fails loudly if any organization
--     already spans names across tenants.
--   * Constraint names use the explicit ck_/uq_ prefixes.
--
-- PRD 14's key architectural decision: an application is owned by exactly
-- one tenant (TENANT) or shared at organization level (ORGANIZATION) and
-- later distributed to selected tenants (S3-03 decides the distribution
-- mapping; PRD 15). The discriminator makes INV-002 enforceable at the
-- database: a tenant-scoped application MUST carry its owning tenant, an
-- organization-scoped one MUST carry none.

-- ---------------------------------------------------------------------------
-- Vocabulary and column
-- ---------------------------------------------------------------------------

-- Ownership vocabulary, a native enum like the lifecycle vocabularies
-- (application_status, tenant_status).
CREATE TYPE application_ownership_scope AS ENUM ('TENANT', 'ORGANIZATION');

-- Every row predating this migration is tenant-owned (the dev-seed Banking
-- application and test fixtures): backfill through the default, then drop
-- it so new rows state their scope explicitly (the V9 platforms
-- precedent).
ALTER TABLE applications ADD COLUMN ownership_scope application_ownership_scope NOT NULL DEFAULT 'TENANT';
ALTER TABLE applications ALTER COLUMN ownership_scope DROP DEFAULT;

-- ---------------------------------------------------------------------------
-- Checks
-- ---------------------------------------------------------------------------

-- INV-002 / issue #14 acceptance criterion 1: exactly one of an owning
-- tenant (TENANT scope) or a null tenant + ORGANIZATION scope — the issue
-- technical notes' CHECK, verbatim semantics.
ALTER TABLE applications ADD CONSTRAINT ck_applications_ownership_scope_owner
    CHECK ((ownership_scope = 'TENANT' AND tenant_id IS NOT NULL)
        OR (ownership_scope = 'ORGANIZATION' AND tenant_id IS NULL));

-- ---------------------------------------------------------------------------
-- Uniqueness coherence across scopes
-- ---------------------------------------------------------------------------

-- The registry's documented name guard is organization-wide (S3-01; the
-- error registry says "already in use in the organization"), but V1's name
-- index was per owning scope — the COALESCE trick existed to fold the
-- then-future organization-owned rows into one namespace. Per-scope shape
-- silently under-enforced the documented guard: two tenants of one
-- organization racing the same name both inserted (the indexes did not
-- collide and the pre-checks raced past each other). Now that both
-- ownership modes exist on one surface, the database becomes the final
-- authority for the documented semantics: one name per organization,
-- across scopes. Existing data cannot violate it unless an organization
-- already spans a name across tenants — none does (greenfield; the dev
-- seed carries one application). V9's bundle/package indexes already hold
-- exactly this org-wide shape for the identifiers.
DROP INDEX uq_applications_scope_name;
CREATE UNIQUE INDEX uq_applications_organization_name
    ON applications (organization_id, name);
