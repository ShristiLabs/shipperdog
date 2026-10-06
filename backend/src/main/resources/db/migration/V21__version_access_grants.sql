-- V21__version_access_grants.sql
-- Authorization grants over versions (issue #31, S7-02, FR-011).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds columns,
--     checks, and indexes; the one constraint replacement (the V1
--     composite primary key -> surrogate id) follows the V10/V19
--     constraint-replacement precedent below and drops no data.
--   * Constraint names use the explicit fk_/ck_/uq_/idx_ prefixes.
--
-- The V1 baseline carried `version_access` as PRD 13's simplified
-- junction (version_id x tester_id, access_type DIRECT|GROUP) — a
-- speculative sketch no code ever wrote (S7-02 is the table's first
-- writer; no row can exist on any deployed database). FR-011's four
-- grant shapes do not fit that key: an application-wide, tag-based,
-- or latest-N grant addresses NO single version, and the working
-- sort must also carry the granting tenant and application. V21
-- therefore completes the table the way V12 completed `versions`:
--
--   * The composite PRIMARY KEY (version_id, tester_id) is REPLACED
--     by the platform-standard surrogate `id` (the V10/V19
--     replacement precedent: a widening — every previously valid
--     row stays valid, and the table is provably empty because no
--     writer exists in any released code). `version_id` becomes
--     NULLABLE: only EXPLICIT_VERSION rows carry it.
--   * `access_type` gains the four FR-011 shapes by additive
--     ALTER TYPE ... ADD VALUE (the V4 enum rule: appended, never
--     renamed or removed, and — the rule this file must honor —
--     never REFERENCED elsewhere in the same migration, because
--     PostgreSQL forbids using an enum value in the transaction
--     that adds it). V1's DIRECT/GROUP stay reserved-unused (the
--     `tenant_status` DISABLED precedent). The per-shape pairing
--     (EXPLICIT_VERSION requires version_id, TAG requires tag, ...)
--     is therefore enforced by the service layer plus the
--     literal-free database guards below, not by enum-literal
--     CHECKs — a later migration may pin the pairings once the
--     labels are usable.
--   * The granting dimension: `tenant_id` (the tenant whose admin
--     granted — the row joins the tenant-isolation seam),
--     `application_id` (the addressed application), and the three
--     shape targets `version_id` / `tag` / `latest_n` (at most one
--     set, database-checked).
--   * `expires_at` (PRD 13) is NULL = never expires; expiry is
--     enforced at evaluation time, never by a sweeper (INV-006:
--     history is never rewritten).
--   * `created_by` records the granting actor on the row; every
--     mutation additionally audits actor + reason (issue #31
--     acceptance criterion 7).
--
-- Duplicate semantics: uq_version_access_grant (UNIQUE NULLS NOT
-- DISTINCT over the full grant identity) is the duplicate authority —
-- the same grant twice is one row (the service replays 200 idempotent,
-- the tenant-member precedent), and a re-grant with a different
-- expiry answers 409 AUTHORIZATION_GRANT_CONFLICT.

-- ---------------------------------------------------------------------------
-- Enum vocabulary (four appended values; unused in this file — see header)
-- ---------------------------------------------------------------------------

ALTER TYPE access_type ADD VALUE IF NOT EXISTS 'EXPLICIT_VERSION';
ALTER TYPE access_type ADD VALUE IF NOT EXISTS 'APPLICATION';
ALTER TYPE access_type ADD VALUE IF NOT EXISTS 'TAG';
ALTER TYPE access_type ADD VALUE IF NOT EXISTS 'LATEST_N';

-- ---------------------------------------------------------------------------
-- Columns
-- ---------------------------------------------------------------------------

-- The surrogate primary key (table-standard shape; see header for the
-- replacement precedent). The table is empty (no writer exists), so the
-- NOT NULL addition needs no backfill.
ALTER TABLE version_access ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();

-- The granting tenant: the row is tenant-scoped user data and joins the
-- isolation seam (ADR-0002) — every endpoint path reaches grants through
-- a bound tenant context; the engine's decision probe reads them through
-- the module's @CrossTenantByDesign catalog instead.
ALTER TABLE version_access ADD COLUMN tenant_id uuid NOT NULL CONSTRAINT fk_version_access_tenant
    REFERENCES tenants (id);

-- The addressed application (all four shapes address exactly one).
ALTER TABLE version_access ADD COLUMN application_id uuid NOT NULL CONSTRAINT fk_version_access_application
    REFERENCES applications (id);

-- The TAG shape's tag value (the public version-tag vocabulary, V13's
-- shape pinned here again so a grant can never carry a tag the tag
-- surface would reject); NULL for the other shapes.
ALTER TABLE version_access ADD COLUMN tag varchar(50);

-- The LATEST_N shape's window bound; NULL for the other shapes. The
-- bound is 1..100 — "latest 3" is FR-011's example; a bound wider than
-- any working set would be an application-wide grant written slower.
ALTER TABLE version_access ADD COLUMN latest_n integer;

-- The granting actor on the row (the audit trail carries actor + reason
-- for every mutation; this column is the row's own provenance).
ALTER TABLE version_access ADD COLUMN created_by uuid NOT NULL CONSTRAINT fk_version_access_created_by
    REFERENCES users (id);

-- ---------------------------------------------------------------------------
-- Constraint replacement (V10/V19 precedent — see header)
-- ---------------------------------------------------------------------------

-- The surrogate primary key replaces the V1 composite (version_id,
-- tester_id) — a widening on the provably empty table: every previously
-- valid row stays valid under the surrogate. The composite must drop
-- BEFORE version_id relaxes its NOT NULL (a primary-key column cannot
-- drop NOT NULL while keyed).
ALTER TABLE version_access DROP CONSTRAINT version_access_pkey;
ALTER TABLE version_access ADD CONSTRAINT pk_version_access PRIMARY KEY (id);

-- The EXPLICIT_VERSION target becomes nullable: only that shape carries
-- a version; the widening is safe on the provably empty table.
ALTER TABLE version_access ALTER COLUMN version_id DROP NOT NULL;

-- ---------------------------------------------------------------------------
-- Checks (literal-free: the enum rule forbids referencing the new labels
-- in this migration; the service pins the per-shape pairing)
-- ---------------------------------------------------------------------------

-- At most one shape target per row (the pairing with access_type is the
-- service's contract; this guard makes a mixed-target row impossible
-- even if that contract is ever violated).
ALTER TABLE version_access ADD CONSTRAINT ck_version_access_single_target CHECK (
    ((CASE WHEN version_id IS NOT NULL THEN 1 ELSE 0 END)
        + (CASE WHEN tag IS NOT NULL THEN 1 ELSE 0 END)
        + (CASE WHEN latest_n IS NOT NULL THEN 1 ELSE 0 END)) <= 1);

-- The tag vocabulary shape (V13's ck_version_tags_tag_shape twin).
ALTER TABLE version_access ADD CONSTRAINT ck_version_access_tag_shape
    CHECK (tag IS NULL OR tag ~ '^[A-Z0-9][A-Z0-9_-]{0,49}$');

-- The latest-N window bound.
ALTER TABLE version_access ADD CONSTRAINT ck_version_access_latest_n_bound
    CHECK (latest_n IS NULL OR (latest_n >= 1 AND latest_n <= 100));

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------

-- The duplicate authority: the full grant identity, NULLS NOT DISTINCT
-- so the three NULL-target shapes (APPLICATION and the target columns of
-- the other shapes) participate in uniqueness (PostgreSQL 15+; the
-- platform runs 17).
CREATE UNIQUE INDEX uq_version_access_grant ON version_access
    (tenant_id, tester_id, application_id, access_type, version_id, tag, latest_n)
    NULLS NOT DISTINCT;

-- The decision-time and effective-set probe: every evaluation asks
-- "which grants does this tester hold for this application through this
-- tenant" — one index descent (the PRD 42 SLO; S7-03 caches above it).
-- The leading column also serves the seam predicate (tenant_id = :ctx).
CREATE INDEX idx_version_access_grant_subject ON version_access (tenant_id, tester_id, application_id);

-- The shared-application decision probe (AC2: a grant may address an
-- application the tenant HOLDS per the S3-03 mapping, not only one it
-- owns): for an organization-shared application the engine hands the
-- contributor a null owning tenant, so the subject's grants for the
-- application resolve across tenants by (tester_id, application_id)
-- and the contributor matches each grant's tenant live.
CREATE INDEX idx_version_access_tester_application ON version_access (tester_id, application_id);

-- The latest-N rank probe: counting the working-set versions newer than
-- a candidate needs (application_id, sort_key) — V12's sort_key is the
-- ordering authority; the listing's default-window filter composes with
-- it. Serves the effective-set computation as the same descent.
CREATE INDEX idx_versions_application_sort ON versions (application_id, sort_key);
