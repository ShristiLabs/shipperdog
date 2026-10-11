-- V47__play_production_rollout.sql
-- The staged rollout's intent model (issue #65, S13-05, FR-021,
-- PRD 17/33): ShipperDog owns the intended curve, the provider owns
-- the mechanics -- two new tables, nothing existing touched.
--
--   * channel_rollouts -- ONE rollout state row per release x
--     channel: the intended percentage of the curve (what the
--     operator asked the provider to serve), the percentage last
--     OBSERVED on the provider (what the store actually serves -- the
--     drift the reconciliation reports), and the control state
--     (rolling, halted, complete). Written at the production
--     dispatch's landing and by every rollout control action.
--   * channel_rollout_steps -- the rollout's timeline, the append-
--     only ledger AC3 demands: every action (the production release's
--     START at its initial percentage, each ADVANCE, HALT, RESUME,
--     REAPPLY) with its actor, its from/to percentages, and its
--     moment. The dashboard renders this beside the audit trail (the
--     audit carries the same facts; this table is the timeline's own
--     first-class read).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds two
--     tables with their indexes; nothing is dropped and no existing
--     column is narrowed.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.

-- ---------------------------------------------------------------------------
-- channel_rollouts: the rollout's intent state
-- ---------------------------------------------------------------------------
CREATE TABLE channel_rollouts (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    release_id uuid NOT NULL REFERENCES releases (id),
    -- The channel reference name (the registry's vocabulary): today
    -- 'play'; a future staged-rollout channel rides the same grain.
    channel varchar(64) NOT NULL,
    -- The provider-side track release reference the curve controls
    -- (the distribution record's external id: track#versionCode#pkg).
    external_id varchar(255) NOT NULL,
    -- The curve's intended percentage (1..100): what ShipperDog asked
    -- the provider to serve. 100 = the full list.
    intended_percent integer NOT NULL,
    -- The percentage last observed on the provider (what the store
    -- actually serves); null until a first observation. The intended
    -- vs observed pair IS the drift report.
    observed_percent integer,
    -- The control state (the rollout's own vocabulary, not the
    -- provider's words): rolling, halted, complete.
    status varchar(16) NOT NULL,
    -- The effective-order ring the rollout serves (the promotion
    -- walk's own last step -- the production ring; carried for the
    -- policy consult's ring scope).
    ring_code varchar(64) NOT NULL,
    -- The optimistic-lock stamp (the #51 subscription precedent): two
    -- concurrent control actions on one curve refuse -- the loser
    -- answers 409 ROLLOUT_CONFLICT -- instead of last-write-winning
    -- (a concurrent ADVANCE can never lower the fraction).
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_channel_rollouts_channel CHECK (
        char_length(btrim(channel)) BETWEEN 1 AND 64),
    CONSTRAINT ck_channel_rollouts_intended CHECK (
        intended_percent BETWEEN 1 AND 100),
    CONSTRAINT ck_channel_rollouts_observed CHECK (
        observed_percent IS NULL OR observed_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_channel_rollouts_status CHECK (
        status IN ('ROLLING', 'HALTED', 'COMPLETE')),
    CONSTRAINT ck_channel_rollouts_ring CHECK (
        char_length(btrim(ring_code)) BETWEEN 1 AND 64),
    -- One rollout per release per channel: the curve is the release's
    -- own; a re-landed dispatch re-reports onto the same row.
    CONSTRAINT uq_channel_rollouts_release_channel UNIQUE (release_id, channel)
);

CREATE INDEX idx_channel_rollouts_organization_id
    ON channel_rollouts (organization_id);
CREATE INDEX idx_channel_rollouts_release_id
    ON channel_rollouts (release_id);

-- ---------------------------------------------------------------------------
-- channel_rollout_steps: the rollout's append-only timeline
-- ---------------------------------------------------------------------------
CREATE TABLE channel_rollout_steps (
    id uuid PRIMARY KEY,
    rollout_id uuid NOT NULL REFERENCES channel_rollouts (id),
    -- The action's vocabulary (S13-05's ACs): the production release's
    -- START at its configured percentage, an ADVANCE to a wider
    -- percentage, HALT, RESUME, and the explicit REAPPLY of intent
    -- after drift.
    action varchar(16) NOT NULL,
    from_percent integer,
    to_percent integer NOT NULL,
    actor_id uuid,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_channel_rollout_steps_action CHECK (
        action IN ('START', 'ADVANCE', 'HALT', 'RESUME', 'REAPPLY')),
    CONSTRAINT ck_channel_rollout_steps_from CHECK (
        from_percent IS NULL OR from_percent BETWEEN 1 AND 100),
    CONSTRAINT ck_channel_rollout_steps_to CHECK (
        to_percent BETWEEN 1 AND 100)
);

CREATE INDEX idx_channel_rollout_steps_rollout_id
    ON channel_rollout_steps (rollout_id);
