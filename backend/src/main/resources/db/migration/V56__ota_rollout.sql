-- V56__ota_rollout.sql
-- The OTA device-rollout and alert machinery (issue #164, S16-02, the
-- OTA updates plan's workstream 1; ADR-0010 D6/D10): an OTA release
-- widens across its DEVICE population in controlled stages -- but
-- devices are not enumerable the way the V48 tenant cohorts are, so
-- the widening rides deterministic on-the-fly bucketing
-- (hash(device_id, plan_salt) mod 10000 < stage.basis_points --
-- stable per device, no cohort table), and the plan/stage/event
-- triple MIRRORS V48's proven shape: the optimistic-lock version
-- bigint, the append-only event timeline, and ONE plan per release.
-- Halting is a human, audit-trailed action (ADR-0010 D10's alert +
-- manual-halt governance -- no auto-halt); the alert rules that raise
-- the flags land here too, evaluated by the S16-11 scheduler against
-- the S16-09 rollups.
--
--   * ota_rollout_plans -- ONE plan per released bundle: the
--     advancement mode and the plan's own status (RUNNING from
--     publish, HALTED/PAUSED by operator action, COMPLETE when the
--     final stage's basis reaches 10000), the plan_salt that salts
--     the device bucketing, and the optimistic-lock version stamp.
--   * ota_rollout_stages -- the plan's ordered widening stages, each
--     a cumulative basis-point threshold of the device hash space.
--   * ota_rollout_events -- the append-only operator timeline:
--     commenced, halted, resumed, advanced, completed.
--   * ota_alert_rules / ota_alert_firings -- the persisted alert
--     definitions (metric, comparator, threshold, window, severity)
--     and the firings they raise; delivery fans out via the
--     notification bus (ADR-0007) beside the console badge.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): new tables and
--     indexes only; nothing existing is dropped, renamed, or re-typed.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.

-- ---------------------------------------------------------------------------
-- ota_rollout_plans: the rollout's intent state (the V48 mirror)
-- ---------------------------------------------------------------------------
CREATE TABLE ota_rollout_plans (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    release_id uuid NOT NULL REFERENCES releases (id),
    -- The application and version denormalized from the release's own
    -- facts at authoring (the V48 precedent): funnel reads and audit
    -- lines resolve without a release-module join.
    application_id uuid NOT NULL,
    version_id uuid NOT NULL,
    -- The advancement mode: AUTOMATIC widens on the policy gate's
    -- verdict; CHECKPOINT requires an operator's explicit approval.
    mode varchar(16) NOT NULL,
    -- RUNNING (from publish), HALTED (the manual, audit-trailed stop),
    -- PAUSED (a temporary operator stop), COMPLETE (final stage
    -- reached).
    status varchar(16) NOT NULL,
    -- The 1-based position of the serving stage (the highest reached).
    current_stage integer,
    -- The salt of the deterministic device bucketing (D6): mixed into
    -- hash(device_id, plan_salt) so two plans' bucket orders are
    -- independent. Public-by-design: device ids are not enumerable,
    -- so the salt alone reveals nothing.
    plan_salt varchar(64) NOT NULL,
    -- The optimistic-lock stamp (the #51 subscription, V47
    -- channel_rollouts, and V48 staged_rollout precedents): two
    -- concurrent actions on one plan refuse -- the loser answers 409
    -- -- instead of last-write-winning.
    version bigint NOT NULL DEFAULT 0,
    created_by uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_ota_rollout_plans_mode CHECK (mode IN ('AUTOMATIC', 'CHECKPOINT')),
    CONSTRAINT ck_ota_rollout_plans_status CHECK (
        status IN ('RUNNING', 'HALTED', 'PAUSED', 'COMPLETE')),
    CONSTRAINT ck_ota_rollout_plans_current_stage CHECK (
        current_stage IS NULL OR current_stage >= 1),
    CONSTRAINT ck_ota_rollout_plans_plan_salt CHECK (
        char_length(btrim(plan_salt)) BETWEEN 1 AND 64),
    -- One plan per release: the rollout is the release's own (the
    -- V48 uq_staged_rollout_plans_release mirror).
    CONSTRAINT uq_ota_rollout_plans_release UNIQUE (release_id)
);

CREATE INDEX idx_ota_rollout_plans_organization_id
    ON ota_rollout_plans (organization_id);
CREATE INDEX idx_ota_rollout_plans_version_id
    ON ota_rollout_plans (version_id);

-- ---------------------------------------------------------------------------
-- ota_rollout_stages: the plan's ordered widening stages
-- ---------------------------------------------------------------------------
CREATE TABLE ota_rollout_stages (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL REFERENCES ota_rollout_plans (id),
    -- The stage's order, 1-based: the widening order.
    position integer NOT NULL,
    -- The CUMULATIVE basis-point threshold (1..10000): a device is in
    -- the serving stage when hash(device_id, plan_salt) mod 10000 is
    -- below the stage's threshold (D6). The final stage carries 10000.
    basis_points integer NOT NULL,
    -- The operator's note for the stage (a checkpoint's rationale,
    -- the halt's reason trail companion).
    note varchar(512),
    -- PENDING (not yet serving) or REACHED (the stage is serving).
    status varchar(16) NOT NULL,
    -- The moment the stage was reached; null while PENDING.
    reached_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_ota_rollout_stages_position CHECK (position >= 1),
    CONSTRAINT ck_ota_rollout_stages_basis_points CHECK (
        basis_points BETWEEN 1 AND 10000),
    CONSTRAINT ck_ota_rollout_stages_note CHECK (
        note IS NULL OR char_length(btrim(note)) > 0),
    CONSTRAINT ck_ota_rollout_stages_status CHECK (status IN ('PENDING', 'REACHED')),
    CONSTRAINT uq_ota_rollout_stages_plan_position UNIQUE (plan_id, position)
);

CREATE INDEX idx_ota_rollout_stages_plan_id ON ota_rollout_stages (plan_id);

-- ---------------------------------------------------------------------------
-- ota_rollout_events: the append-only operator timeline
-- ---------------------------------------------------------------------------
CREATE TABLE ota_rollout_events (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL REFERENCES ota_rollout_plans (id),
    -- The timeline's vocabulary: the publish-time commencement
    -- (COMMENCED), the manual halt (HALTED), a resume (RESUMED), a
    -- stage's advancement (ADVANCED), and the final stage's completion
    -- (COMPLETED). Every operator action lands here (the audit trail
    -- beside the audit_events entry the service writes).
    action varchar(20) NOT NULL,
    -- The stage the action serves, when one does.
    stage_position integer,
    -- The machine-readable outcome/detail (a refusal's reason); never
    -- secret material.
    detail varchar(512),
    actor_id uuid,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_ota_rollout_events_action CHECK (
        action IN ('COMMENCED', 'HALTED', 'RESUMED', 'ADVANCED', 'COMPLETED')),
    CONSTRAINT ck_ota_rollout_events_stage_position CHECK (
        stage_position IS NULL OR stage_position >= 1),
    CONSTRAINT ck_ota_rollout_events_detail CHECK (
        detail IS NULL OR char_length(btrim(detail)) > 0)
);

CREATE INDEX idx_ota_rollout_events_plan_id ON ota_rollout_events (plan_id);

-- ---------------------------------------------------------------------------
-- ota_alert_rules: the persisted alert definitions (D10)
-- ---------------------------------------------------------------------------
CREATE TABLE ota_alert_rules (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    application_id uuid NOT NULL REFERENCES applications (id),
    -- NULL = the rule watches the application's OTA funnel as a whole;
    -- set = it watches one bundle (the crash-rate comparison's target).
    ota_bundle_id uuid REFERENCES ota_bundles (id),
    -- The rollup metric the rule evaluates (the S16-09 dictionary's
    -- OTA names; an open row-value vocabulary).
    metric varchar(64) NOT NULL,
    comparator varchar(8) NOT NULL,
    threshold double precision NOT NULL,
    -- The evaluation lookback window, in minutes.
    window_minutes integer NOT NULL,
    severity varchar(16) NOT NULL,
    created_by uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_ota_alert_rules_metric CHECK (
        char_length(btrim(metric)) BETWEEN 1 AND 64),
    CONSTRAINT ck_ota_alert_rules_comparator CHECK (
        comparator IN ('GT', 'GTE', 'LT', 'LTE')),
    CONSTRAINT ck_ota_alert_rules_window CHECK (
        window_minutes BETWEEN 1 AND 10080),
    CONSTRAINT ck_ota_alert_rules_severity CHECK (
        severity IN ('INFO', 'WARNING', 'CRITICAL'))
);

CREATE INDEX idx_ota_alert_rules_organization_id
    ON ota_alert_rules (organization_id);
CREATE INDEX idx_ota_alert_rules_application_id
    ON ota_alert_rules (application_id);

-- ---------------------------------------------------------------------------
-- ota_alert_firings: the raised firings, resolvable
-- ---------------------------------------------------------------------------
CREATE TABLE ota_alert_firings (
    id uuid PRIMARY KEY,
    rule_id uuid NOT NULL REFERENCES ota_alert_rules (id),
    -- The bundle the firing evaluated (the rule's own scope, stamped
    -- per firing so the timeline reads without a join).
    ota_bundle_id uuid REFERENCES ota_bundles (id),
    -- The observed value that crossed the rule's threshold.
    observed_value double precision NOT NULL,
    -- The machine-readable context (the rollup bucket, the comparison);
    -- never secret material.
    detail varchar(512),
    fired_at timestamptz NOT NULL,
    -- NULL while the firing stands (the console badge); stamped when
    -- the metric recovers inside the rule's window.
    resolved_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_ota_alert_firings_detail CHECK (
        detail IS NULL OR char_length(btrim(detail)) > 0)
);

-- The rule's firing history (the S16-11 console reads).
CREATE INDEX idx_ota_alert_firings_rule_fired_at
    ON ota_alert_firings (rule_id, fired_at);
-- The standing firings only: the console's active-alert badge.
CREATE INDEX idx_ota_alert_firings_unresolved
    ON ota_alert_firings (rule_id) WHERE resolved_at IS NULL;

