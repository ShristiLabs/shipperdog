-- V29__approval_modes.sql
-- Advanced approval modes for ShipperDog (issue #41, S10-02, FR-016,
-- PRD 8.10/13/17/32/33).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): two new enums,
--     one enum VALUE, additive columns, backfills that are no-ops on
--     greenfield rows; nothing that carries data is dropped.
--
-- The model (the issue's scope: "sequential groups; within a group:
-- ALL | ANY", conditional steps with trigger predicates):
--
--   * approval_workflow_steps (expanded): a definition is now an
--     ordered list of GROUPS. group_position (1..m contiguous) orders
--     the groups; group_mode is the group's satisfaction rule — ALL
--     (the PARALLEL mode: every step must approve) or ANY (the OR
--     mode: the first approval satisfies the group and the remaining
--     siblings become optional-skipped). Sequential is the degenerate
--     case the technical note names: groups of one, all ALL — exactly
--     what every V28 definition already is, so the backfill below
--     copies position into group_position and the default mode is
--     ALL. The optional condition_* columns carry a step's trigger
--     predicate (S10-02's stub vocabulary; S10-06's expression
--     evaluator migrates them): ENVIRONMENT_EQUALS (value = the
--     environment code) and FEATURE_FLAG_CONTAINS (key [+ value] in
--     the environment's resolved configuration). A step whose
--     condition evaluates false at round instantiation instantiates
--     SKIPPED with its evaluation rationale recorded — never a silent
--     omission.
--   * release_approvals (expanded): the per-step rows of a round copy
--     their group facts (group_position, group_mode) from the
--     definition at instantiation, beside the position they already
--     copy — the round freezes the definition's shape it ran. The
--     approval_status vocabulary gains SKIPPED (the terminal status
--     of an ANY-group sibling after the group's first approval, and
--     of a condition-excluded step at instantiation): added here, and
--     the decision-shape CHECK that references it lands in V30 — a
--     new enum value cannot be USED in the transaction that adds it,
--     and Flyway runs each migration as one transaction.

-- ---------------------------------------------------------------------------
-- The vocabularies (additive; enum values are never removed)
-- ---------------------------------------------------------------------------

-- A group's satisfaction rule: ALL = the PARALLEL mode (every step),
-- ANY = the OR mode (first approval satisfies, siblings skip).
CREATE TYPE approval_group_mode AS ENUM ('ALL', 'ANY');

-- The condition-predicate stub vocabulary (S10-02; S10-06 migrates
-- these into the policy engine's expression format):
--   ENVIRONMENT_EQUALS    - the release's targeted environment code
--                           equals condition_value.
--   FEATURE_FLAG_CONTAINS - the environment's resolved configuration
--                           contains condition_key (optionally with
--                           exactly condition_value).
CREATE TYPE approval_step_condition_type AS ENUM (
    'ENVIRONMENT_EQUALS',
    'FEATURE_FLAG_CONTAINS'
);

-- The skip status: an ANY-group sibling after the group's first
-- approval, or a condition-excluded step at instantiation. Terminal
-- like APPROVED/REJECTED; carries no signature, always a reason. Used
-- by V30's decision-shape check and the service's skip CAS.
ALTER TYPE approval_status ADD VALUE IF NOT EXISTS 'SKIPPED';

-- ---------------------------------------------------------------------------
-- approval_workflow_steps: the group columns and the condition columns
-- ---------------------------------------------------------------------------

-- 1-based, contiguous per definition (the service derives from the
-- request's group order). V28 definitions backfill to position —
-- every one a sequential group of one.
ALTER TABLE approval_workflow_steps ADD COLUMN group_position int NOT NULL DEFAULT 1;

ALTER TABLE approval_workflow_steps
    ADD CONSTRAINT ck_approval_workflow_steps_group_position CHECK (group_position >= 1);

ALTER TABLE approval_workflow_steps ADD COLUMN group_mode approval_group_mode NOT NULL DEFAULT 'ALL';

-- The optional trigger predicate. NULL = unconditional step. A
-- FEATURE_FLAG_CONTAINS condition must name its key; a condition
-- without a type is not a condition (all three columns move together).
ALTER TABLE approval_workflow_steps ADD COLUMN condition_type approval_step_condition_type;

ALTER TABLE approval_workflow_steps ADD COLUMN condition_key varchar(256);

ALTER TABLE approval_workflow_steps ADD COLUMN condition_value varchar(256);

ALTER TABLE approval_workflow_steps
    ADD CONSTRAINT ck_approval_workflow_steps_condition_shape CHECK (
        (condition_type IS NULL AND condition_key IS NULL AND condition_value IS NULL)
        OR (condition_type IS NOT NULL
            AND (condition_type <> 'FEATURE_FLAG_CONTAINS' OR condition_key IS NOT NULL))
    );

UPDATE approval_workflow_steps SET group_position = position;

-- ---------------------------------------------------------------------------
-- release_approvals: the group facts (copied at instantiation)
-- ---------------------------------------------------------------------------

-- The group facts copied from the definition at instantiation, beside
-- the position: the round's rows are the frozen copy of the
-- definition it ran. V28 rows backfill to their own position.
ALTER TABLE release_approvals ADD COLUMN group_position int NOT NULL DEFAULT 1;

ALTER TABLE release_approvals ADD COLUMN group_mode approval_group_mode NOT NULL DEFAULT 'ALL';

ALTER TABLE release_approvals
    ADD CONSTRAINT ck_release_approvals_group_position CHECK (group_position >= 1);

UPDATE release_approvals SET group_position = position;
