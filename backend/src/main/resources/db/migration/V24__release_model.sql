-- V24__release_model.sql
-- Release model, creation and targets schema for ShipperDog (issue #55,
-- S9-01, FR-015, PRD 8.6/8.10/13/17).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds tables,
--     columns, checks, indexes, and one non-destructive column rename;
--     nothing is dropped.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes; the
--     V1 baseline names stay untouched per the forward-only policy.
--
-- The releases and release_targets tables exist from the V1 baseline
-- (issue #1's PRD 13 sketch); S9-01 is their first writer, and the story
-- expands rather than duplicates:
--
--   * rings (new): the S10-04 ring DATA MODEL pulled forward into this
--     story's schema per the issue's sequencing note — a reference table
--     carrying FR-017's five system defaults (organization_id IS NULL;
--     per-organization custom rings arrive with E10's behavior stories).
--     Only the data model lands here; ring behavior (eligibility rules,
--     approval policy, quality gates) stays in E10.
--   * releases: the owning-dimension snapshot (organization_id NOT NULL,
--     tenant_id nullable — the V12 versions shape), the human-quotable
--     release number (R-1001 style, one global sequence), and the
--     starting ring reference. Releases are addressed by their own
--     top-level routes (POST/GET /api/v1/releases), so ADR-0002's ninth
--     amendment applies: an addressable child carries the snapshot and
--     joins the seam (ReleaseEntity implements OrganizationScope).
--   * release_targets: the V1 ring_id varchar(64) becomes ring_code (a
--     non-destructive rename; the legacy column is retained per the
--     forward-only policy) and a real ring_id uuid reference replaces it
--     as the authority. Rows hang off exactly one seam-served release
--     and are addressed by no route of their own — a plain child under
--     the eighth amendment: no snapshot columns, no seam repository.
--   * release_target_groups (new): the per-target tester-group sets, a
--     plain child of release_targets (composite foreign key to the
--     parent's composite primary key; the via-parent rule).
--   * release_artifacts (new): the per-platform artifact selection (one
--     artifact per platform per release, uq_release_artifacts_release_
--     platform), a plain child of releases.
--
-- Greenfield note: no release rows exist anywhere (the release module has
-- been an empty package since issue #1), so every backfill below is a
-- no-op on every real database today — they keep the migration correct
-- on any state, the V12 discipline.

-- ---------------------------------------------------------------------------
-- rings: the reference table (S10-04's data model, pulled forward)
-- ---------------------------------------------------------------------------

CREATE TABLE rings (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NULL = a system default ring visible to every organization;
    -- set = an organization's custom ring (E10).
    organization_id uuid REFERENCES organizations (id),
    code            varchar(50) NOT NULL,
    name            varchar(100) NOT NULL,
    -- The progressive-distribution order (PRD 8.10: Ring 0 Engineering
    -- ... Ring 4 Production); promotion moves upward through it (E10).
    position        int NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rings_code_shape CHECK (code ~ '^[a-z][a-z0-9-]{0,49}$'),
    CONSTRAINT ck_rings_name_length CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_rings_position_nonnegative CHECK (position >= 0)
);

-- One code per scope: system rings unique among system rings (the NULL
-- organization coalesced to the nil uuid — PostgreSQL UNIQUE treats
-- NULLs as distinct), organization rings unique within their
-- organization. An organization may override a system code's MEANING
-- only by defining its own ring under the same code within its scope;
-- resolution prefers the organization's ring.
CREATE UNIQUE INDEX uq_rings_scope_code
    ON rings (COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid), code);

CREATE INDEX idx_rings_organization_id ON rings (organization_id);

-- FR-017's system defaults (PRD 8.10's five rings, fixed ids so the
-- release module's tests and any later reference data stay stable).
INSERT INTO rings (id, organization_id, code, name, position) VALUES
    ('00000000-0000-0000-0000-000000000001', NULL, 'engineering',  'Engineering',  0),
    ('00000000-0000-0000-0000-000000000002', NULL, 'qa',           'QA',           1),
    ('00000000-0000-0000-0000-000000000003', NULL, 'tenant-pilot', 'Tenant Pilot', 2),
    ('00000000-0000-0000-0000-000000000004', NULL, 'uat',          'UAT',          3),
    ('00000000-0000-0000-0000-000000000005', NULL, 'production',   'Production',   4);

-- ---------------------------------------------------------------------------
-- releases: the owning-dimension snapshot, the quotable id, the ring
-- ---------------------------------------------------------------------------

-- The owning organization of the application the release distributes;
-- never null once backfilled. The snapshot mirrors the applications and
-- versions precedent (V12): a release row belongs to the same owning
-- dimension as its application, and the seam's predicate needs the
-- owning columns on the row it serves.
ALTER TABLE releases ADD COLUMN organization_id uuid REFERENCES organizations (id);

-- The owning tenant of the application, or NULL when the application is
-- organization-shared (PRD 14): the seam's organization-scope predicate
-- serves exactly the NULL-tenant rows of one organization.
ALTER TABLE releases ADD COLUMN tenant_id uuid REFERENCES tenants (id);

-- Backfill from each release's application (a no-op on the empty table;
-- correct on any state, the V12 discipline).
UPDATE releases r
SET organization_id = a.organization_id,
    tenant_id = a.tenant_id
FROM applications a
WHERE a.id = r.application_id
  AND r.organization_id IS NULL;

ALTER TABLE releases ALTER COLUMN organization_id SET NOT NULL;

-- The human-quotable release number (issue AC5, FR-015's R-1028 style):
-- one global sequence rendered as 'R-' || release_number. The API keeps
-- uuid addressing (the platform's uniform resource shape and concealed
-- 404s); the number is the human vocabulary — quotable in chat, tickets,
-- and audit trails — unique by construction and never reused.
CREATE SEQUENCE release_number_seq START WITH 1001;

ALTER TABLE releases ADD COLUMN release_number bigint NOT NULL DEFAULT nextval('release_number_seq');

CREATE UNIQUE INDEX uq_releases_release_number ON releases (release_number);

-- The ring the release starts at (FR-015's creation ring; PRD 16's
-- manifest field). Every release_target row carries the same ring at
-- creation (PRD 13 models ring on the target); E10's promotion stories
-- move them.
ALTER TABLE releases ADD COLUMN starting_ring_id uuid NOT NULL
    CONSTRAINT fk_releases_starting_ring REFERENCES rings (id);

-- The application-status list filter (AC5) composes with the seam's
-- application predicate; the (application_id, version_id) prefix of
-- uq_releases_application_version already serves the version filter.
CREATE INDEX idx_releases_application_status ON releases (application_id, status);

-- Tenant-scope seam predicate (tenant_id = :context).
CREATE INDEX idx_releases_tenant_id ON releases (tenant_id);

-- Organization-scope seam predicate (tenant_id IS NULL AND
-- organization_id = :context): only the organization-owned rows.
CREATE INDEX idx_releases_organization_scope
    ON releases (organization_id)
    WHERE tenant_id IS NULL;

-- ---------------------------------------------------------------------------
-- release_targets: a real ring reference (the V1 varchar becomes legacy)
-- ---------------------------------------------------------------------------

-- Non-destructive rename: V1's ring_id varchar(64) sketch becomes
-- ring_code, retained (never dropped) per the forward-only policy. No
-- writer ever populated it — the column is historical scaffolding. The
-- single constraint relaxation (DROP NOT NULL, the V15 build_number
-- precedent) widens what a row may carry: the uuid reference below is
-- the authority, and the legacy column holds nothing going forward.
ALTER TABLE release_targets RENAME COLUMN ring_id TO ring_code;

ALTER TABLE release_targets ALTER COLUMN ring_code DROP NOT NULL;

ALTER TABLE release_targets ADD COLUMN ring_id uuid REFERENCES rings (id);

-- Defensive backfill for hypothetical legacy rows (none exist): a legacy
-- code matching a system ring resolves to it; anything else has no
-- writer and no meaning.
UPDATE release_targets t
SET ring_id = r.id
FROM rings r
WHERE r.code = t.ring_code
  AND r.organization_id IS NULL
  AND t.ring_id IS NULL;

ALTER TABLE release_targets ALTER COLUMN ring_id SET NOT NULL;

ALTER TABLE release_targets
    ADD CONSTRAINT fk_release_targets_ring FOREIGN KEY (ring_id) REFERENCES rings (id);

-- ---------------------------------------------------------------------------
-- release_target_groups: the per-target tester-group selection
-- ---------------------------------------------------------------------------

CREATE TABLE release_target_groups (
    release_id uuid NOT NULL,
    tenant_id  uuid NOT NULL,
    group_id   uuid NOT NULL REFERENCES tester_groups (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (release_id, tenant_id, group_id),
    CONSTRAINT fk_release_target_groups_target
        FOREIGN KEY (release_id, tenant_id) REFERENCES release_targets (release_id, tenant_id),
    CONSTRAINT fk_release_target_groups_group FOREIGN KEY (group_id) REFERENCES tester_groups (id)
);

CREATE INDEX idx_release_target_groups_group_id ON release_target_groups (group_id);

-- ---------------------------------------------------------------------------
-- release_artifacts: the per-platform artifact selection
-- ---------------------------------------------------------------------------

CREATE TABLE release_artifacts (
    release_id  uuid NOT NULL,
    artifact_id uuid NOT NULL REFERENCES artifacts (id),
    -- The platform vocabulary of V1's platform type (IOS/ANDROID); the
    -- creation API's per-platform slots land here, one artifact per
    -- platform per release.
    platform    varchar(10) NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (release_id, artifact_id),
    CONSTRAINT fk_release_artifacts_release FOREIGN KEY (release_id) REFERENCES releases (id),
    CONSTRAINT fk_release_artifacts_artifact FOREIGN KEY (artifact_id) REFERENCES artifacts (id),
    CONSTRAINT uq_release_artifacts_release_platform UNIQUE (release_id, platform),
    CONSTRAINT ck_release_artifacts_platform_shape CHECK (platform IN ('IOS', 'ANDROID'))
);

CREATE INDEX idx_release_artifacts_artifact_id ON release_artifacts (artifact_id);
