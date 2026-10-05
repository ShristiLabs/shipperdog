-- V12__version_management.sql
-- Version management schema for ShipperDog (issue #17, S4-01).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds columns,
--     checks, and indexes; nothing is dropped or altered destructively.
--   * Constraint names use the explicit ck_/uq_/idx_ prefixes.
--
-- The versions table exists from the V1 baseline (id, application_id,
-- version_name, status, timestamps, and the duplicate-name authority
-- uq_versions_application_version). S4-01 adds what the story demands:
--
--   * The ownership snapshot (organization_id NOT NULL, tenant_id
--     nullable): versions join the tenant-isolation seam (ADR-0002)
--     the way applications already do — a version row belongs to the
--     same owning dimension as its application (the owning tenant, or
--     the organization itself for a shared application, PRD 14), and
--     the seam's predicate needs the owning columns on the row it
--     serves. The snapshot is written from the application's profile
--     at creation and carried along by the audited ownership-scope
--     transition (ApplicationService.transitionScope), the same
--     sanctioned cross-scope update pattern of ADR-0002's third
--     amendment.
--   * The normalized sort key: version_name stays the human string
--     (PRD 8.4 "7.12.0"), sort_key is the fixed-width numeric
--     encoding (five-digit zero-padded segments) whose lexicographic
--     order is exactly semver numeric order — "10.0.0" sorts after
--     "9.9.9", which the raw string cannot do. The issue's technical
--     note ("store version_name as string + normalized sort key
--     (semver ordering for UI)") pins this shape.
--
-- No rows exist anywhere (greenfield; E4 is the first writer), but the
-- backfills below keep the migration correct on any database state,
-- mirroring the V9/V10 discipline: add nullable, backfill from the
-- application, then tighten NOT NULL.

-- ---------------------------------------------------------------------------
-- Columns
-- ---------------------------------------------------------------------------

-- The owning organization of the application this version belongs to;
-- never null once backfilled (every application carries one). The
-- foreign keys mirror the applications precedent (V1): the snapshot is
-- a live reference to the owning dimension, never a free-form value.
ALTER TABLE versions ADD COLUMN organization_id uuid REFERENCES organizations (id);

-- The owning tenant of the application, or NULL when the application is
-- organization-shared (PRD 14): the seam's organization-scope predicate
-- serves exactly the NULL-tenant rows of one organization.
ALTER TABLE versions ADD COLUMN tenant_id uuid REFERENCES tenants (id);

-- The normalized semver ordering key; see the header note.
ALTER TABLE versions ADD COLUMN sort_key varchar(17);

-- ---------------------------------------------------------------------------
-- Backfill (no-op on an empty table; correct on any state)
-- ---------------------------------------------------------------------------

UPDATE versions v
SET organization_id = a.organization_id,
    tenant_id = a.tenant_id
FROM applications a
WHERE a.id = v.application_id
  AND v.organization_id IS NULL;

UPDATE versions
SET sort_key = concat_ws('.',
        lpad(split_part(version_name, '.', 1), 5, '0'),
        lpad(split_part(version_name, '.', 2), 5, '0'),
        lpad(split_part(version_name, '.', 3), 5, '0'))
WHERE sort_key IS NULL;

ALTER TABLE versions ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE versions ALTER COLUMN sort_key SET NOT NULL;

-- ---------------------------------------------------------------------------
-- Checks
-- ---------------------------------------------------------------------------

-- The semver-style name vocabulary (issue #17 AC1): a numeric triple,
-- each segment 0..99999 without leading zeros (the semver rule; the
-- width cap keeps sort_key's five-digit padding total). Pre-releases
-- and build suffixes are deliberately out: tagging is S4-02's
-- vocabulary (QA, Pilot, ...), not the version name's.
ALTER TABLE versions ADD CONSTRAINT ck_versions_version_name_semver
    CHECK (version_name ~ '^(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})\.(0|[1-9][0-9]{0,4})$');

-- The sort key's fixed shape: exactly three five-digit segments, so the
-- service-side encoding and the database agree on what orders rows.
ALTER TABLE versions ADD CONSTRAINT ck_versions_sort_key_shape
    CHECK (sort_key ~ '^[0-9]{5}\.[0-9]{5}\.[0-9]{5}$');

-- ---------------------------------------------------------------------------
-- Indexes (seam predicates)
-- ---------------------------------------------------------------------------

-- Tenant-scope seam predicate (tenant_id = :context). Application-id
-- lookups stay served by the prefix of uq_versions_application_version.
CREATE INDEX idx_versions_tenant_id ON versions (tenant_id);

-- Organization-scope seam predicate (tenant_id IS NULL AND
-- organization_id = :context): only the organization-owned rows.
CREATE INDEX idx_versions_organization_scope
    ON versions (organization_id)
    WHERE tenant_id IS NULL;
