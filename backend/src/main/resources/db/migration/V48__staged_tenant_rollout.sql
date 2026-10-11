-- V48__staged_tenant_rollout.sql
-- The staged TENANT rollout and orchestrated rollback (issue #66,
-- S13-06, FR-018/§37, PRD 16/17): ShipperDog's own rings axis -- a
-- release widens across its target TENANT population in controlled
-- stages (pilot tenants -> 10% -> 50% -> 100%), each stage executing
-- across every channel the release dispatches on, with one-action
-- rollback. Four new tables, one enum value, nothing existing touched.
--
--   * staged_rollout_plans -- ONE plan per release: the advancement
--     mode (AUTOMATIC: the policy gate decides between stages;
--     CHECKPOINT: a Release Manager approves each stage) and the
--     plan's own status (PLANNED before the release activates,
--     RUNNING while stages execute, COMPLETE when the final stage
--     reached, ROLLED_BACK by the orchestrated rollback).
--   * staged_rollout_stages -- the plan's ordered stages, each a
--     PERCENT of the target population or an EXPLICIT tenant list,
--     with the resolved cohort size and the stage's own standing.
--   * staged_rollout_cohorts -- the deterministic cohort resolution:
--     one row per (stage, tenant) SELECTED. Resolved once at
--     authoring by the seeded hash order (documented in
--     docs/distribution.md), so membership is stable across retries
--     and inspectable BEFORE advancement (AC1).
--   * staged_rollout_events -- the append-only timeline: every
--     authoring, commencement, stage approval, advancement, policy
--     block, channel application, and the orchestrated rollback
--     (AC4's timeline entries beside the release's own
--     release_status_history ROLLED_BACK row).
--
--   * ALTER TYPE distribution_status ADD VALUE 'ROLLED_BACK' -- the
--     channel_distributions record vocabulary gains the rolled-back
--     state the rollback orchestration writes (AC4: "channel
--     distributions records -> rolled-back state per AC vocabulary").
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001).
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.
--   * ALTER TYPE ... ADD VALUE is additive and never removes a value.

-- The record vocabulary's new terminal state (must precede nothing;
-- no row in THIS migration carries it -- the rollback orchestration
-- writes it).
ALTER TYPE distribution_status ADD VALUE IF NOT EXISTS 'ROLLED_BACK';

-- ---------------------------------------------------------------------------
-- staged_rollout_plans: the rollout's intent state
-- ---------------------------------------------------------------------------
CREATE TABLE staged_rollout_plans (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    release_id uuid NOT NULL REFERENCES releases (id),
    -- The application and version denormalized from the release's own
    -- facts at authoring: the download gate resolves a plan by VERSION
    -- (the download's addressing grain) without a release-module read,
    -- and the audit lines carry the application reference.
    application_id uuid NOT NULL,
    version_id uuid NOT NULL,
    -- The advancement mode: AUTOMATIC evaluates the S10-06 policy
    -- engine (and the dispatch-failure ownership rule) between stages;
    -- CHECKPOINT requires a Release Manager's explicit stage approval.
    mode varchar(16) NOT NULL,
    -- PLANNED (authored, the release not yet ACTIVE), RUNNING (the
    -- release activated and stages executing), COMPLETE (the final
    -- stage reached), ROLLED_BACK (the orchestrated rollback).
    status varchar(16) NOT NULL,
    -- The 1-based position of the serving stage (the highest REACHED);
    -- null while PLANNED.
    current_stage integer,
    -- The optimistic-lock stamp (the #51 subscription precedent, the
    -- V47 channel_rollouts precedent): two concurrent actions on one
    -- plan refuse -- the loser answers 409 -- instead of
    -- last-write-winning.
    version bigint NOT NULL DEFAULT 0,
    created_by uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_staged_rollout_plans_mode CHECK (mode IN ('AUTOMATIC', 'CHECKPOINT')),
    CONSTRAINT ck_staged_rollout_plans_status CHECK (
        status IN ('PLANNED', 'RUNNING', 'COMPLETE', 'ROLLED_BACK')),
    CONSTRAINT ck_staged_rollout_plans_current_stage CHECK (
        current_stage IS NULL OR current_stage >= 1),
    -- One plan per release: the staged rollout is the release's own.
    CONSTRAINT uq_staged_rollout_plans_release UNIQUE (release_id)
);

CREATE INDEX idx_staged_rollout_plans_organization_id
    ON staged_rollout_plans (organization_id);
CREATE INDEX idx_staged_rollout_plans_version_id
    ON staged_rollout_plans (version_id);

-- ---------------------------------------------------------------------------
-- staged_rollout_stages: the plan's ordered stages
-- ---------------------------------------------------------------------------
CREATE TABLE staged_rollout_stages (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL REFERENCES staged_rollout_plans (id),
    -- The stage's order, 1-based: the widening order.
    position integer NOT NULL,
    -- PERCENT (the seeded selection takes the first ceil(p% * N / 100)
    -- tenants of the hash order) or EXPLICIT (the named tenant list).
    kind varchar(8) NOT NULL,
    -- The percentage for PERCENT stages (1..100); null for EXPLICIT.
    percent integer,
    -- The resolved cohort's size (denormalized at authoring: the
    -- view's inspectable answer before advancement).
    cohort_size integer NOT NULL,
    -- PENDING, REACHED (the stage executed across the channels), or
    -- ROLLED_BACK (a reached stage the orchestrated rollback pulled).
    status varchar(16) NOT NULL,
    -- The moment the stage was reached; null while PENDING.
    reached_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_staged_rollout_stages_position CHECK (position >= 1),
    CONSTRAINT ck_staged_rollout_stages_kind CHECK (kind IN ('PERCENT', 'EXPLICIT')),
    CONSTRAINT ck_staged_rollout_stages_percent CHECK (
        percent IS NULL OR percent BETWEEN 1 AND 100),
    CONSTRAINT ck_staged_rollout_stages_percent_coherent CHECK (
        kind = 'EXPLICIT' OR percent IS NOT NULL),
    CONSTRAINT ck_staged_rollout_stages_cohort_size CHECK (cohort_size >= 0),
    CONSTRAINT ck_staged_rollout_stages_status CHECK (
        status IN ('PENDING', 'REACHED', 'ROLLED_BACK')),
    CONSTRAINT uq_staged_rollout_stages_plan_position UNIQUE (plan_id, position)
);

CREATE INDEX idx_staged_rollout_stages_plan_id
    ON staged_rollout_stages (plan_id);

-- ---------------------------------------------------------------------------
-- staged_rollout_cohorts: the deterministic cohort resolution
-- ---------------------------------------------------------------------------
CREATE TABLE staged_rollout_cohorts (
    id uuid PRIMARY KEY,
    stage_id uuid NOT NULL REFERENCES staged_rollout_stages (id),
    tenant_id uuid NOT NULL REFERENCES tenants (id),
    -- The selection's seeded rank (the tenant's position in the
    -- release's hash order, 0-based): carried so the resolution is
    -- not just inspectable but REPRODUCIBLE -- the documented
    -- algorithm's own ordering, stored beside its output.
    rank integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_staged_rollout_cohorts_rank CHECK (rank >= 0),
    -- One row per stage and tenant: membership is the row.
    CONSTRAINT uq_staged_rollout_cohorts_stage_tenant UNIQUE (stage_id, tenant_id)
);

CREATE INDEX idx_staged_rollout_cohorts_stage_id
    ON staged_rollout_cohorts (stage_id);
CREATE INDEX idx_staged_rollout_cohorts_tenant_id
    ON staged_rollout_cohorts (tenant_id);

-- ---------------------------------------------------------------------------
-- staged_rollout_events: the append-only timeline
-- ---------------------------------------------------------------------------
CREATE TABLE staged_rollout_events (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL REFERENCES staged_rollout_plans (id),
    -- The timeline's vocabulary: the authoring (PLANNED), the ACTIVE
    -- crossing's commencement (STARTED), a checkpoint stage approval
    -- (STAGE_APPROVED), a stage's execution (STAGE_ADVANCED), a
    -- policy or dispatch-failure refusal (STAGE_BLOCKED), one channel
    -- arm's application (CHANNEL_APPLIED), the plan's completion
    -- (COMPLETED), and the orchestrated rollback (ROLLED_BACK).
    action varchar(20) NOT NULL,
    -- The stage the action serves, when one does.
    stage_position integer,
    -- The channel an arm applied to, when one did.
    channel varchar(64),
    -- The machine-readable outcome/detail (a refusal's reason, an
    -- arm's result); never secret material.
    detail varchar(512),
    actor_id uuid,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT ck_staged_rollout_events_action CHECK (
        action IN ('PLANNED', 'STARTED', 'STAGE_APPROVED', 'STAGE_ADVANCED',
                   'STAGE_BLOCKED', 'CHANNEL_APPLIED', 'COMPLETED', 'ROLLED_BACK')),
    CONSTRAINT ck_staged_rollout_events_channel CHECK (
        channel IS NULL OR char_length(btrim(channel)) BETWEEN 1 AND 64),
    CONSTRAINT ck_staged_rollout_events_detail CHECK (
        detail IS NULL OR char_length(btrim(detail)) > 0)
);

CREATE INDEX idx_staged_rollout_events_plan_id
    ON staged_rollout_events (plan_id);
