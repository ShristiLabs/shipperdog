-- V52__abac_policy_engine.sql
-- The ABAC policy engine's versioned rule store (issue #70, S14-04,
-- PRD 25/38, the S10-06 seam's documented extension).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): two new
--     tables; nothing is dropped and no existing column is narrowed.
--
--   * abac_policies (new): the organization's attribute-based access
--     rules as DATA — the release_policy_rules pattern (V34)
--     applied to authorization attributes. One row per policy: a
--     name unique per organization, an EFFECT (DENY — the condition
--     firing refuses; REQUIRE — the condition must hold for every
--     request the selectors match; both only ever NARROW access,
--     PRD 25's deny-override composition over the RBAC base layer),
--     an ACTION selector (the decision the policy governs:
--     LIST_APPLICATIONS, READ_ARTIFACT, DOWNLOAD of the layered
--     engine, or PROMOTE of the promotion consult), optional
--     application/environment/ring selectors (null = any), the typed
--     condition tree as jsonb (the AbacCondition vocabulary: the
--     PolicyCondition discipline — JSON, validated at write, never a
--     string expression language), the evaluation position, the
--     enabled standing, and the CURRENT version number. Selectors
--     follow the V34 precedents: application_id and environment_id
--     are foreign keys (hard reference data); ring is a NAME-ONLY
--     code (the organization's vocabulary may redefine it; an
--     unresolved code at evaluation simply does not match).
--
--   * abac_policy_versions (new): the immutable version history
--     (AC4): every create and replace appends one row — the complete
--     authored facts, the author, the moment — and the policy row's
--     version counter moves with it. policy_id is LOOSE (no foreign
--     key) by the evaluation-log precedent (V34): history survives
--     the policy's deletion, so "what rule version denied this
--     promotion last month" stays answerable from the data.

-- ---------------------------------------------------------------------------
-- abac_policies: the current standing of each policy
-- ---------------------------------------------------------------------------

CREATE TABLE abac_policies (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    name            varchar(64) NOT NULL,
    -- DENY: the condition firing refuses the request.
    -- REQUIRE: the condition must hold for every request the
    -- selectors match (the PRD 25 example's shape). Both narrow.
    effect          varchar(16) NOT NULL,
    -- The decision the policy governs (the engine's actions plus the
    -- promotion consult's).
    action          varchar(24) NOT NULL,
    -- Optional applicability selectors; null means "any".
    application_id  uuid REFERENCES applications (id),
    environment_id  uuid REFERENCES environments (id),
    ring            varchar(64),
    condition       jsonb NOT NULL,
    position        integer NOT NULL,
    enabled         boolean NOT NULL DEFAULT true,
    -- The current version number (v1 at create; +1 per replace).
    version         integer NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_abac_policies_org_name UNIQUE (organization_id, name),
    CONSTRAINT ck_abac_policies_effect CHECK (effect IN ('DENY', 'REQUIRE')),
    CONSTRAINT ck_abac_policies_action CHECK (
        action IN ('LIST_APPLICATIONS', 'READ_ARTIFACT', 'DOWNLOAD', 'PROMOTE')
    ),
    CONSTRAINT ck_abac_policies_position CHECK (position >= 0 AND position <= 999),
    CONSTRAINT ck_abac_policies_version CHECK (version >= 1)
);

CREATE INDEX idx_abac_policies_org ON abac_policies (organization_id, position, name);

-- ---------------------------------------------------------------------------
-- abac_policy_versions: the immutable authored history
-- ---------------------------------------------------------------------------

CREATE TABLE abac_policy_versions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- LOOSE by design (the V34 evaluation-log precedent): the history
    -- survives the policy row's deletion.
    policy_id       uuid NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- Name snapshot at authoring time (rename is a new version).
    name            varchar(64) NOT NULL,
    version         integer NOT NULL,
    effect          varchar(16) NOT NULL,
    action          varchar(24) NOT NULL,
    application_id  uuid,
    environment_id  uuid,
    ring            varchar(64),
    condition       jsonb NOT NULL,
    position        integer NOT NULL,
    enabled         boolean NOT NULL,
    authored_by     uuid NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_abac_policy_versions UNIQUE (policy_id, version),
    CONSTRAINT ck_abac_policy_versions_effect CHECK (effect IN ('DENY', 'REQUIRE')),
    CONSTRAINT ck_abac_policy_versions_action CHECK (
        action IN ('LIST_APPLICATIONS', 'READ_ARTIFACT', 'DOWNLOAD', 'PROMOTE')
    )
);

CREATE INDEX idx_abac_policy_versions_policy ON abac_policy_versions (policy_id, version);
CREATE INDEX idx_abac_policy_versions_org ON abac_policy_versions (organization_id, created_at);
