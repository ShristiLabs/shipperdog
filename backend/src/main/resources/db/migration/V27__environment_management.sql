-- V27__environment_management.sql
-- Environment management schema for ShipperDog (issue #58, S9-04,
-- FR-025, PRD 8.11/16/25).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): additive columns,
--     three new tables, checks, and indexes; nothing is dropped.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes; the
--     V1 baseline names stay untouched per the forward-only policy.
--
-- The environments table exists from the V1 baseline (issue #1's
-- PRD 13 sketch: organization_id NOT NULL, name unique per organization,
-- environment_status ACTIVE/DISABLED); S9-04 is its first writer, and
-- the story expands rather than duplicates:
--
--   * environments: the stable code + progression position (the rings
--     vocabulary shape — code is the manifest's frozen environment
--     value and the platform's addressing vocabulary; position orders
--     the Dev -> QA -> UAT -> Pilot -> Production progression PRD 8.11
--     sketches), and the two name-only bindings FR-025 requires:
--     distribution_channel and approval_policy are REFERENCE NAMES, not
--     foreign keys — E13 owns channel execution and S10-01 owns policy
--     evaluation; this story binds the names per environment, their
--     owning stories give them meaning.
--   * environment_config_versions (new): the versioned configuration
--     snapshot ledger — one append-only row per config write, carrying
--     the FULL resolved key-value map as jsonb (the snapshot IS the
--     value: retrieval by version answers from one row, never a
--     reconstruction). version is monotonic per environment from 1
--     (never-written reads answer version 0, the honest empty). V1's
--     environment_config junction (unversioned key/value) is retained
--     untouched as legacy scaffolding — it never had a writer (the
--     release_targets.ring_code precedent) and stays reserved-unused.
--     Append-only: no updated_at (the audit_events/version_tags
--     exception), rows are never updated or deleted.
--   * environment_tenants (new): the tenant-assignment junction AC4
--     validates release targets against — which tenants an
--     environment's releases MAY target. Deny-by-default: an
--     environment with no assignments may target no tenant (PRD P3).
--     Composite PK (the junction convention); created_at only.
--   * environment_credentials (new): the per-environment credential
--     BINDINGS — name + secret_reference ONLY (PRD 25: store
--     references, not credentials). The reference shape check
--     (printable, no whitespace) is the schema-review half of the
--     never-material guarantee: the column cannot hold a value with a
--     space or newline, and no column anywhere in this migration can
--     hold secret material because none is ever accepted by the API.
--   * releases.environment_id: the optional environment a release
--     targets (nullable — the release surface predates environments;
--     creation accepts it "when environment specified", AC4). The
--     S9-03 manifest freezes the environment's CODE and the RESOLVED
--     configuration values at the APPROVED crossing (deep values, never
--     pointers — the #57 follow-up this story discharges).
--
-- Seeding: FR-025's five defaults (Development, QA, UAT, Pilot,
-- Production) are seeded for every organization that exists when this
-- migration runs (ON CONFLICT DO NOTHING — idempotent against both the
-- name and code guards). Organizations created AFTER this migration are
-- seeded inside the organization-creation transaction by the
-- environment module's provisioning callback (the S6-02 default-group
-- precedent); the dev seed fixture carries the same block for the demo
-- organization (repeatable, fixed ids).

-- ---------------------------------------------------------------------------
-- environments: the code, the progression position, the name bindings
-- ---------------------------------------------------------------------------

-- The stable, human-facing code: 'production' (lowercase kebab, the
-- rings shape). Frozen verbatim into the release manifest at APPROVED.
ALTER TABLE environments ADD COLUMN code varchar(50);

-- The Dev -> QA -> UAT -> Pilot -> Production progression order
-- (PRD 8.11); custom environments take any non-negative position.
ALTER TABLE environments ADD COLUMN position int;

-- Name-only binding: the distribution channel this environment deploys
-- through (E13 owns channels and their execution; the name is the bind).
ALTER TABLE environments ADD COLUMN distribution_channel varchar(64);

-- Name-only binding: the approval policy releases into this environment
-- require (S10-01 owns policies and their evaluation; the name is the
-- bind; the manifest's approval block freezes the resolved list then).
ALTER TABLE environments ADD COLUMN approval_policy varchar(64);

-- No environment rows exist anywhere (the module has been an empty
-- package since issue #1): the backfills are no-ops on every real
-- database today, kept for correctness on any state (the V12/V24
-- discipline).
UPDATE environments SET code = lower(regexp_replace(name, '[^a-zA-Z0-9]+', '-', 'g')) WHERE code IS NULL;
UPDATE environments SET position = 0 WHERE position IS NULL;

ALTER TABLE environments ALTER COLUMN code SET NOT NULL;
ALTER TABLE environments ALTER COLUMN position SET NOT NULL;

ALTER TABLE environments
    ADD CONSTRAINT ck_environments_code_shape CHECK (code ~ '^[a-z][a-z0-9-]{0,49}$');
ALTER TABLE environments
    ADD CONSTRAINT ck_environments_position_nonnegative CHECK (position >= 0);
ALTER TABLE environments
    ADD CONSTRAINT ck_environments_name_length CHECK (char_length(name) BETWEEN 1 AND 100);

-- One code per organization (the code is the addressing vocabulary);
-- V1's uq_environments_org_name stays the display-name guard.
CREATE UNIQUE INDEX uq_environments_org_code ON environments (organization_id, code);

-- ---------------------------------------------------------------------------
-- FR-025's seeded defaults for every organization existing at migration
-- time (later organizations are seeded by the provisioning callback)
-- ---------------------------------------------------------------------------

INSERT INTO environments (organization_id, code, name, position)
SELECT o.id, d.code, d.name, d.position
FROM organizations o
CROSS JOIN (VALUES
    ('development', 'Development', 0),
    ('qa',          'QA',          1),
    ('uat',         'UAT',         2),
    ('pilot',       'Pilot',       3),
    ('production',  'Production',  4)
) AS d(code, name, position)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- environment_config_versions: the versioned configuration snapshots
-- ---------------------------------------------------------------------------

CREATE TABLE environment_config_versions (
    environment_id uuid NOT NULL,
    version        bigint NOT NULL,
    -- The FULL resolved key-value map at this version (an object; the
    -- snapshot IS the value — versioned retrieval answers from one row).
    entries        jsonb NOT NULL,
    created_by     uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (environment_id, version),
    CONSTRAINT fk_environment_config_versions_environment
        FOREIGN KEY (environment_id) REFERENCES environments (id),
    CONSTRAINT ck_environment_config_versions_version_positive CHECK (version >= 1),
    CONSTRAINT ck_environment_config_versions_entries_object
        CHECK (jsonb_typeof(entries) = 'object')
);

-- ---------------------------------------------------------------------------
-- environment_tenants: which tenants an environment's releases may target
-- ---------------------------------------------------------------------------

CREATE TABLE environment_tenants (
    environment_id uuid NOT NULL,
    tenant_id      uuid NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (environment_id, tenant_id),
    CONSTRAINT fk_environment_tenants_environment
        FOREIGN KEY (environment_id) REFERENCES environments (id),
    CONSTRAINT fk_environment_tenants_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

-- The release-creation validation probes by tenant (AC4).
CREATE INDEX idx_environment_tenants_tenant_id ON environment_tenants (tenant_id);

-- ---------------------------------------------------------------------------
-- environment_credentials: reference-only credential bindings
-- ---------------------------------------------------------------------------

CREATE TABLE environment_credentials (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    environment_id  uuid NOT NULL,
    -- The binding's label: 'apple-store', 'api-signing' (kebab, unique
    -- per environment).
    name            varchar(100) NOT NULL,
    -- The SECRET REFERENCE ONLY (PRD 25): an opaque identifier into the
    -- secret store. Never material: the shape check forbids whitespace,
    -- and no API surface accepts or returns anything but this reference.
    secret_reference varchar(500) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_environment_credentials_environment_name UNIQUE (environment_id, name),
    CONSTRAINT fk_environment_credentials_environment
        FOREIGN KEY (environment_id) REFERENCES environments (id),
    CONSTRAINT ck_environment_credentials_name_shape CHECK (name ~ '^[a-z][a-z0-9-]{0,99}$'),
    -- No whitespace, printable only (the length bound is the varchar
    -- itself; PostgreSQL caps regex repetition counts at 255, so the
    -- shape check carries no explicit count).
    CONSTRAINT ck_environment_credentials_reference_shape
        CHECK (secret_reference ~ '^[[:graph:]]+$')
);

-- ---------------------------------------------------------------------------
-- releases: the optional environment a release targets
-- ---------------------------------------------------------------------------

ALTER TABLE releases ADD COLUMN environment_id uuid;

ALTER TABLE releases
    ADD CONSTRAINT fk_releases_environment FOREIGN KEY (environment_id) REFERENCES environments (id);
