-- V9__application_registry.sql
-- Application registry schema for ShipperDog (issue #13, S3-01).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds columns,
--     checks, and indexes; nothing is dropped or altered destructively.
--   * Constraint names use the explicit ck_/uq_ prefixes.
--
-- The applications table exists from the V1 baseline (id,
-- organization_id, tenant_id, name, bundle_id, package_name, status,
-- timestamps, and the within-scope name guard uq_applications_scope_name).
-- S3-01 adds the registry vocabulary the story demands: a description and
-- the platform set chosen at registration (PRD 8.3 "Platform(s)";
-- iOS/Android per PRD 14/15), plus the issue's acceptance-criterion-2
-- uniqueness guards — bundle id / package name unique per
-- organization+platform. bundle_id IS the iOS platform identifier and
-- package_name IS the Android one (PRD 13), so a partial unique index per
-- identifier column is exactly the per-organization+platform guard.

-- ---------------------------------------------------------------------------
-- Columns
-- ---------------------------------------------------------------------------

-- Free-form product description (PRD 8.3); optional by design.
ALTER TABLE applications ADD COLUMN description varchar(2000);

-- Platform set chosen at registration: at least one platform is
-- mandatory (issue #13 AC1), values fixed to the V1 platform vocabulary.
-- Modeled as a checked varchar array rather than a native enum array:
-- Hibernate maps enum/varchar element arrays cleanly to varchar[] while
-- ddl-auto=validate must stay green, and the membership check pins the
-- vocabulary at the database the same way an enum type would. The
-- platform_type native enum remains reserved for the singular
-- artifacts.platform column of E5 (PRD 13).
--
-- NOT NULL needs a default for rows predating this migration: the only
-- pre-existing rows anywhere are the dev-seed fixture ("Banking", which
-- carries both identifiers) and rows created by pre-V9 test fixtures;
-- both-platforms is the faithful backfill for a greenfield registry with
-- no production deployment. The default is dropped immediately after —
-- new rows must state their platforms explicitly.
ALTER TABLE applications ADD COLUMN platforms varchar(30)[] NOT NULL DEFAULT ARRAY['IOS','ANDROID'];
ALTER TABLE applications ALTER COLUMN platforms DROP DEFAULT;

-- ---------------------------------------------------------------------------
-- Checks
-- ---------------------------------------------------------------------------

-- At least one platform per application (AC1: "creates an app with at
-- least one platform").
ALTER TABLE applications ADD CONSTRAINT ck_applications_platforms_nonempty CHECK (cardinality(platforms) >= 1);

-- The fixed platform vocabulary (IOS/ANDROID; PRD 14/15 list exactly
-- these two for V1). A future platform is an expand-only migration:
-- widen the array, then the service vocabulary.
ALTER TABLE applications ADD CONSTRAINT ck_applications_platforms_vocabulary
    CHECK (platforms <@ ARRAY['IOS','ANDROID']::varchar[]);

-- ---------------------------------------------------------------------------
-- Uniqueness (issue #13, acceptance criterion 2)
-- ---------------------------------------------------------------------------

-- Bundle id (the iOS platform identifier) is unique per organization:
-- duplicate registration of the same store identity is a 409, race-safe
-- through the index. Partial: rows without an iOS identifier (no iOS
-- platform) do not participate.
CREATE UNIQUE INDEX uq_applications_organization_bundle
    ON applications (organization_id, bundle_id)
    WHERE bundle_id IS NOT NULL;

-- Package name (the Android platform identifier) is unique per
-- organization, same shape.
CREATE UNIQUE INDEX uq_applications_organization_package
    ON applications (organization_id, package_name)
    WHERE package_name IS NOT NULL;
