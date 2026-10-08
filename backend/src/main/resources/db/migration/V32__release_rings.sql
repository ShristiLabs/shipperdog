-- V32__release_rings.sql
-- Release rings: seeded defaults, eligibility rules, and policy
-- references for ShipperDog (issue #43, S10-04, FR-017, PRD 8.10/33).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds columns,
--     checks, and rows; nothing is dropped and no existing column is
--     narrowed.
--
-- The rings table is V24's ring data model (the S9-01 sequencing note:
-- reference data pulled forward, five FR-017 system defaults with
-- organization_id NULL and fixed ids). S10-04 owns the behavior this
-- story lands:
--
--   * eligibility (jsonb, NULL): PRD 8.10's "eligibility rules (tenant,
--     tester group)" — {"tenants": [uuid...]} and/or {"groups":
--     [uuid...]}, each key absent meaning that axis is unconstrained.
--     The API validates the shape and the org membership of every
--     listed id; the database stores the JSON as validated (the V31
--     gate-config precedent: no database JSON schema, one authority).
--   * approval_policy (varchar(64), NULL): a NAME-ONLY reference to an
--     approval workflow of the organization, the V27 environments
--     precedent. Names are not foreign keys by design: renaming a
--     workflow would strand a row, so an unresolved name fails closed
--     at resolution (the S10-02 decision) instead of blocking every
--     rename.
--   * promotion_policy (varchar(16), NOT NULL DEFAULT 'MANUAL'): PRD
--     8.10's manual-or-automatic standing. The value lands now; the
--     AUTOMATIC behavior arrives with S10-06's policy engine.
--   * distribution_channel (varchar(64), NULL): PRD 8.10's channel
--     reference — name-only like the environment column; store
--     channels become ring destinations in E13.
--
--   * The per-organization seeds: FR-017's five defaults materialized
--     organization-grain (the V27 environments precedent — the same
--     five-defaults shape S9-04 landed), so every existing
--     organization can rename, reorder, and rule its own rings the day
--     this story ships. The V24 system rows (organization_id NULL,
--     fixed ids) stay untouched: they remain the platform vocabulary
--     and the resolution fallback (uq_rings_scope_code;
--     resolveByCode prefers the organization's ring — unchanged, the
--     V24 contract). New organizations seed through the API's
--     creation hook (RingProvisioning), the EnvironmentProvisioning
--     precedent.

-- ---------------------------------------------------------------------------
-- rings: the S10-04 columns
-- ---------------------------------------------------------------------------

ALTER TABLE rings
    ADD COLUMN eligibility jsonb,
    ADD COLUMN approval_policy varchar(64),
    ADD COLUMN promotion_policy varchar(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN distribution_channel varchar(64),
    ADD CONSTRAINT ck_rings_approval_policy_length
        CHECK (approval_policy IS NULL OR char_length(approval_policy) BETWEEN 1 AND 64),
    ADD CONSTRAINT ck_rings_promotion_policy_shape
        CHECK (promotion_policy IN ('MANUAL', 'AUTOMATIC')),
    ADD CONSTRAINT ck_rings_distribution_channel_length
        CHECK (distribution_channel IS NULL OR char_length(distribution_channel) BETWEEN 1 AND 64);

-- The per-organization eligibility lookup (the effective ring of a
-- code inside one organization).
CREATE INDEX idx_rings_organization_position ON rings (organization_id, position);

-- ---------------------------------------------------------------------------
-- The per-organization seeds (existing organizations; new ones seed
-- through the API's creation hook)
-- ---------------------------------------------------------------------------

INSERT INTO rings (organization_id, code, name, position)
SELECT o.id, d.code, d.name, d.position
FROM organizations o
CROSS JOIN (VALUES
    ('engineering',  'Engineering',  0),
    ('qa',           'QA',           1),
    ('tenant-pilot', 'Tenant Pilot', 2),
    ('uat',          'UAT',          3),
    ('production',   'Production',   4)
) AS d(code, name, position)
ON CONFLICT DO NOTHING;
