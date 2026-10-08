-- V31__quality_gates.sql
-- Quality gates framework for ShipperDog (issue #42, S10-03, FR-026,
-- PRD 8.10/13/17/32/33).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one enum value,
--     two new tables, one V1 sketch expanded; nothing is dropped.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes; the
--     V1 baseline names stay untouched per the forward-only policy.
--
-- The model (the issue's technical note: gates are EVALUATED FACTS, not
-- workflows — results are recorded with evidence; the promotion
-- decision that consults them is S10-05's):
--
--   * gate_definitions (new): an organization's required-gate catalog,
--     the approval_workflows shape. gate_type stays the V1 open
--     vocabulary (varchar, integration-defined kinds; the FR-026
--     built-ins are described by the CODE catalog, not rows), config
--     is the expected-result payload as data (a coverage threshold, a
--     critical-bug ceiling — validated against the catalog's schema in
--     the service), and the optional ring_id is the PRD 33 attachment:
--     releases STARTING at that ring evaluate this gate set; a NULL
--     ring is the organization-wide default every ring inherits
--     unless a ring-attached definition of the same gate_type
--     overrides it. One definition per organization+gate_type at each
--     ring attachment level.
--   * release_gate_results (new): the append-only submission history —
--     one row per recorded result (external CI submission or metric
--     evaluation), the latest of which wins for the current state.
--     PRD P5: history is permanent; re-running a gate never rewrites
--     what was reported before.
--   * release_gates (V1 baseline sketch, EXPANDED — the
--     release_approvals precedent: V1 modeled the table from PRD 13
--     and no writer ever touched it; S10-03 is its first writer): the
--     CURRENT state per release and gate_type, keyed by V1's own
--     (release_id, gate_type) unique constraint. New columns carry
--     what a recorded result holds — the observed numeric value, the
--     submitter, the source (SUBMITTED vs METRIC), the required flag
--     frozen from the definition at evaluation, and the override
--     record (actor, mandatory reason, moment; an overridden FAILED or
--     NO_DATA gate stops blocking but stays a FAILED/NO_DATA fact).
--
-- gate_status gains NO_DATA (additive): metric-backed gates resolve
-- their values from analytics AT EVALUATION TIME (S11-04's seam); with
-- no data — or no analytics registrant yet — the gate records NO_DATA
-- and blocks fail-closed (PRD 33 ethos), visually distinct from FAILED
-- on the dashboard (the issue's technical note).

-- ---------------------------------------------------------------------------
-- The vocabularies (additive; enum values are never removed)
-- ---------------------------------------------------------------------------

-- The fail-closed "no analytics data yet" state of a metric-backed
-- gate: distinct from FAILED (a reported bad result) and from PENDING
-- (nothing reported yet) — the dashboard renders all three
-- differently, and evaluation blocks on all three until passed or
-- overridden.
ALTER TYPE gate_status ADD VALUE IF NOT EXISTS 'NO_DATA';

-- ---------------------------------------------------------------------------
-- gate_definitions: the organization's required-gate catalog
-- ---------------------------------------------------------------------------

CREATE TABLE gate_definitions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- The V1 open vocabulary (integration-defined gate kinds); the
    -- service normalizes to lowercase kebab and validates the
    -- expected-result config against the built-in catalog's schema.
    gate_type       varchar(64) NOT NULL,
    -- Whether the gate blocks when not passed (FR-026 "required
    -- gates"); a non-required definition records results for
    -- visibility without ever blocking.
    required        boolean NOT NULL DEFAULT true,
    -- The expected-result payload as data: {"threshold": 80} for a
    -- coverage/crash-free gate, {"max": 0} for a critical-bug ceiling,
    -- {} for the pass/fail kinds. Schema-validated in the service
    -- against the catalog entry of gate_type; unknown types carry {}
    -- (the open vocabulary's default: binary reporting).
    config          jsonb NOT NULL DEFAULT '{}'::jsonb,
    -- The PRD 33 ring attachment (NULL = organization-wide default).
    ring_id         uuid REFERENCES rings (id),
    created_by      uuid NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_gate_definitions_type_shape CHECK (gate_type ~ '^[a-z][a-z0-9-]{0,63}$')
);

-- One organization-wide definition per gate type ...
CREATE UNIQUE INDEX uq_gate_definitions_org_type
    ON gate_definitions (organization_id, gate_type)
    WHERE ring_id IS NULL;

-- ... and one ring-attached definition per gate type per ring (the
-- ring-attached row OVERRIDES the organization-wide default of the
-- same type when a release on that ring evaluates).
CREATE UNIQUE INDEX uq_gate_definitions_org_ring_type
    ON gate_definitions (organization_id, ring_id, gate_type)
    WHERE ring_id IS NOT NULL;

CREATE INDEX idx_gate_definitions_organization_id ON gate_definitions (organization_id);

-- ---------------------------------------------------------------------------
-- release_gate_results: the append-only submission history
-- ---------------------------------------------------------------------------

CREATE TABLE release_gate_results (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id    uuid NOT NULL REFERENCES releases (id),
    gate_type     varchar(64) NOT NULL,
    -- PASSED or FAILED as recorded (PENDING is a current-state value,
    -- never a result; NO_DATA records a metric evaluation that found
    -- no analytics data — the fail-closed fact). No CHECK pinning the
    -- outcome vocabulary: the type itself is the authority, and a
    -- check naming NO_DATA could not resolve in this transaction
    -- (PostgreSQL refuses to USE a newly added enum value before
    -- commit; the V28 discipline of service-enforced shape applies).
    outcome       gate_status NOT NULL,
    -- The observed numeric value of a threshold/ceiling gate (the
    -- dashboard's "Coverage 87% (threshold 80%)"), null for the
    -- pass/fail kinds.
    observed_value double precision,
    evidence_url  varchar(1024),
    -- The actor who submitted (the external CI caller), null on
    -- system metric evaluations.
    submitted_by  uuid,
    -- SUBMITTED (an external system's report) or METRIC (the
    -- evaluation's own resolution from analytics).
    source        varchar(16) NOT NULL,
    recorded_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_release_gate_results_source CHECK (source IN ('SUBMITTED', 'METRIC'))
);

CREATE INDEX idx_release_gate_results_release
    ON release_gate_results (release_id, gate_type, recorded_at DESC);

-- ---------------------------------------------------------------------------
-- release_gates: V1's sketch EXPANDED into the current-state record
-- (the release_approvals precedent: expand, never duplicate)
-- ---------------------------------------------------------------------------

-- The required flag frozen from the definition at instantiation /
-- submission; the observed value, submitter, and source of the latest
-- result; and the override record (S10-03 AC3): actor, mandatory
-- reason, moment. An override never rewrites the underlying FAILED or
-- NO_DATA fact — the row keeps reporting what was reported, and the
-- override column set is what evaluation and the dashboard consult.
ALTER TABLE release_gates ADD COLUMN required boolean NOT NULL DEFAULT true;

ALTER TABLE release_gates ADD COLUMN observed_value double precision;

ALTER TABLE release_gates ADD COLUMN submitted_by uuid;

ALTER TABLE release_gates ADD COLUMN source varchar(16);

ALTER TABLE release_gates
    ADD CONSTRAINT ck_release_gates_source CHECK (source IN ('SUBMITTED', 'METRIC'));

ALTER TABLE release_gates ADD COLUMN overridden_by uuid;

ALTER TABLE release_gates ADD COLUMN overridden_reason varchar(1000);

ALTER TABLE release_gates ADD COLUMN overridden_at timestamptz;

-- An override is a complete record or absent (the shape the override
-- route's compare-and-set enforces; NO_DATA and FAILED are the only
-- overridable states by service rule, recorded here as the data
-- invariant the dashboard can rely on).
ALTER TABLE release_gates
    ADD CONSTRAINT ck_release_gates_override_shape CHECK (
        (overridden_by IS NULL AND overridden_reason IS NULL AND overridden_at IS NULL)
        OR (overridden_by IS NOT NULL AND overridden_reason IS NOT NULL AND overridden_at IS NOT NULL)
    );
