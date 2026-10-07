-- V28__approval_workflows.sql
-- Approval workflow engine schema for ShipperDog (issue #40, S10-01,
-- FR-016, PRD 8.10/13/17/33).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): two new enums,
--     four new tables, checks, and indexes; nothing is dropped.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes; the
--     V1 baseline names stay untouched per the forward-only policy.
--
-- The model (the issue's technical note: workflow as DATA, not code —
-- S10-02 extends the same tables with mode flags):
--
--   * approval_workflows (new): an organization's named approval chain
--     definition. The NAME is the binding vocabulary V27 reserved:
--     environments.approval_policy references a workflow of the owning
--     organization by this name (a REFERENCE NAME, not a foreign key —
--     the V27 decision, so an environment can bind before its policy
--     exists and renaming never strands rows). The optional ring_id is
--     the direct attachment: releases STARTING at that ring (as
--     resolved in the organization's vocabulary — an organization ring
--     shadows the system ring of the same code) resolve this workflow
--     at the APPROVAL_PENDING crossing. One workflow per ring per
--     organization; one name per organization.
--   * approval_workflow_steps (new): the ordered chain — one row per
--     step, position 1..n contiguous (the service derives positions
--     from the request's list order; the schema pins positivity and
--     per-chain uniqueness of both position and role). required_role
--     is the approval_step_role vocabulary below — exactly the five
--     PRD 7 roles the RBAC matrix grants an app:releases:approve-*
--     permission, so every step is satisfiable by construction. The
--     optional tenant_id narrows a step to releases TARGETING that
--     tenant (the issue's "optional tenant scope"): a step whose
--     tenant is not targeted is not instantiated for that release's
--     round — skipped, never failed.
--   * release_approval_rounds (new): one row per approval ROUND of a
--     release. Round 1 instantiates inside the READY to
--     APPROVAL_PENDING crossing's transaction (the S9-02 event seam);
--     a REJECTED round's rework path is a NEW round (round_number
--     increments, the rejected round stays for the audit trail —
--     PRD P5: history is permanent). status is the round vocabulary
--     below; the APPROVAL_PENDING to APPROVED crossing is denied while
--     the latest round is PENDING or REJECTED (the transition policy
--     this story registers) and permitted when it is SATISFIED — or
--     when no round exists at all: approval workflows are
--     CONFIGURABLE (FR-016), and an organization that defines none
--     keeps the ungated machine S9-02 shipped.
--   * release_approvals (V1 baseline, EXPANDED — the releases
--     precedent: V1 sketched the table from PRD 13 and no writer ever
--     touched it; S10-01 is its first writer and expands rather than
--     duplicates): one row per step of a round, carrying PRD 13's own
--     columns — approval_type (the PRD 13 vocabulary, extended below),
--     approver, status, decided_at — beside the round linkage, the
--     chain position, and the signature role. The signature
--     discipline (the issue's technical note): approver_role records
--     the role the approver signed under AT decision time — roles
--     change later, the record never does. reason rides rejects
--     (mandatory) and optional approvals. Rows are decided exactly
--     once (PENDING to a terminal status through the service's
--     compare-and-set); nothing is ever updated after decision and no
--     row is ever deleted.
--
-- Greenfield note: the workflow module has been an empty package
-- since issue #1 and release_approvals never had a writer; every
-- table below starts empty, and no backfill is needed (the V12
-- discipline applies vacuously).

-- ---------------------------------------------------------------------------
-- The vocabularies (additive; enum values are never removed)
-- ---------------------------------------------------------------------------

-- Exactly the five roles the RBAC matrix grants an approve permission
-- (app:releases:approve-qa/-security/-compliance/-product/-release):
-- a step outside this vocabulary would be unsatisfiable, so the schema
-- refuses it before the service ever reads it.
CREATE TYPE approval_step_role AS ENUM (
    'QA_ENGINEER',
    'SECURITY_REVIEWER',
    'COMPLIANCE_REVIEWER',
    'APP_OWNER',
    'RELEASE_MANAGER'
);

-- One round's status: the whole sequential chain PENDING until its
-- last step approves (SATISFIED), or terminal from the first reject
-- (REJECTED — sequential chains stop at the first no).
CREATE TYPE release_approval_round_status AS ENUM ('PENDING', 'SATISFIED', 'REJECTED');

-- PRD 13's approval_type vocabulary, extended additively for the two
-- chain kinds FR-016 names that V1's sketch lacked: the Compliance
-- reviewer's sign-off (PRD 7.9) and the Release Manager's own final
-- step (PRD 7.6). The step-role to approval-type map is 1:1 and
-- total: QA_ENGINEER -> QA, SECURITY_REVIEWER -> SECURITY,
-- COMPLIANCE_REVIEWER -> COMPLIANCE, APP_OWNER -> PRODUCT,
-- RELEASE_MANAGER -> RELEASE_MANAGER — the role names WHO may sign,
-- the type names WHAT they sign (PRD 13's own vocabulary; MANUAL
-- stays V1 legacy scaffolding, unwritten and unwrit).
ALTER TYPE approval_type ADD VALUE IF NOT EXISTS 'COMPLIANCE';
ALTER TYPE approval_type ADD VALUE IF NOT EXISTS 'RELEASE_MANAGER';

-- ---------------------------------------------------------------------------
-- approval_workflows: the named, optionally ring-attached definitions
-- ---------------------------------------------------------------------------

CREATE TABLE approval_workflows (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- The binding vocabulary (V27's environments.approval_policy
    -- references this name); lowercase kebab, the rings shape.
    name            varchar(64) NOT NULL,
    -- The direct attachment: releases starting at this ring (as
    -- resolved for the organization) resolve this workflow. NULL =
    -- a name-only definition an environment binds by name.
    ring_id         uuid REFERENCES rings (id),
    created_by      uuid NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_approval_workflows_name_shape CHECK (name ~ '^[a-z][a-z0-9-]{0,63}$')
);

-- One name per organization (the environment binding resolves within
-- the owning organization's vocabulary).
CREATE UNIQUE INDEX uq_approval_workflows_org_name ON approval_workflows (organization_id, name);

-- One workflow per ring per organization: two chains on one ring
-- would make resolution ambiguous.
CREATE UNIQUE INDEX uq_approval_workflows_org_ring
    ON approval_workflows (organization_id, ring_id)
    WHERE ring_id IS NOT NULL;

CREATE INDEX idx_approval_workflows_organization_id ON approval_workflows (organization_id);

-- ---------------------------------------------------------------------------
-- approval_workflow_steps: the ordered chain of a definition
-- ---------------------------------------------------------------------------

CREATE TABLE approval_workflow_steps (
    workflow_id   uuid NOT NULL REFERENCES approval_workflows (id),
    -- 1-based, contiguous (the service derives from list order).
    position      int NOT NULL,
    required_role approval_step_role NOT NULL,
    -- The optional tenant scope: the step applies only to releases
    -- targeting this tenant (skipped otherwise — never a failure).
    tenant_id     uuid REFERENCES tenants (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workflow_id, position),
    CONSTRAINT ck_approval_workflow_steps_position CHECK (position >= 1),
    -- A sequential chain never asks the same role twice.
    CONSTRAINT uq_approval_workflow_steps_role UNIQUE (workflow_id, required_role)
);

CREATE INDEX idx_approval_workflow_steps_tenant_id ON approval_workflow_steps (tenant_id);

-- ---------------------------------------------------------------------------
-- release_approval_rounds: one approval round per crossing/rework
-- ---------------------------------------------------------------------------

CREATE TABLE release_approval_rounds (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id  uuid NOT NULL REFERENCES releases (id),
    round_number int NOT NULL,
    -- The definition the round instantiated (frozen reference: later
    -- definition changes never rewrite a round already run).
    workflow_id uuid NOT NULL REFERENCES approval_workflows (id),
    status      release_approval_round_status NOT NULL DEFAULT 'PENDING',
    -- The actor whose act created the round: the caller of the
    -- APPROVAL_PENDING crossing (round 1) or of the restart (later
    -- rounds); a bare uuid, the audit log keeps the full who.
    created_by  uuid NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_release_approval_rounds_number CHECK (round_number >= 1)
);

CREATE UNIQUE INDEX uq_release_approval_rounds_release_number
    ON release_approval_rounds (release_id, round_number);

CREATE INDEX idx_release_approval_rounds_release_id ON release_approval_rounds (release_id);

CREATE INDEX idx_release_approval_rounds_workflow_id ON release_approval_rounds (workflow_id);

-- ---------------------------------------------------------------------------
-- release_approvals: V1's sketch EXPANDED into the per-step signature
-- records of a round (the releases precedent: expand, never duplicate)
-- ---------------------------------------------------------------------------

-- The round linkage and the chain position (V1 carried neither: its
-- sketch modeled one approval per type per release; rounds and
-- re-work demand one per step PER ROUND, and the id re-key below is
-- the V15 constraint-relaxation precedent applied to a table no
-- writer ever touched — the (release_id, approval_type) primary key
-- is superseded by the round-position uniqueness, which admits the
-- same type again in a later round, exactly what rework means).
ALTER TABLE release_approvals DROP CONSTRAINT release_approvals_pkey;

ALTER TABLE release_approvals ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE release_approvals
    ADD CONSTRAINT pk_release_approvals PRIMARY KEY (id);

ALTER TABLE release_approvals ADD COLUMN round_id uuid NOT NULL;

ALTER TABLE release_approvals
    ADD CONSTRAINT fk_release_approvals_round FOREIGN KEY (round_id) REFERENCES release_approval_rounds (id);

ALTER TABLE release_approvals ADD COLUMN position int NOT NULL;

ALTER TABLE release_approvals
    ADD CONSTRAINT ck_release_approvals_position CHECK (position >= 1);

-- The step's applicability scope, copied from the definition (null =
-- every release of the definition's attachment).
ALTER TABLE release_approvals ADD COLUMN tenant_id uuid REFERENCES tenants (id);

-- The signature's role (the issue's technical note): the role the
-- approver signed under, recorded at decision time — roles change
-- later, the record does not. Null while PENDING.
ALTER TABLE release_approvals ADD COLUMN approver_role approval_step_role;

-- Mandatory on rejects (AC6), optional on approvals.
ALTER TABLE release_approvals ADD COLUMN reason varchar(1000);

-- One row per step position of a round (the re-keyed authority).
CREATE UNIQUE INDEX uq_release_approvals_round_position ON release_approvals (round_id, position);

-- A decided record carries its signature; a pending one carries none
-- (the service's compare-and-set enforces the crossing; the check
-- pins the shape V1's sketch implied and the round model demands).
ALTER TABLE release_approvals
    ADD CONSTRAINT ck_release_approvals_decision_shape CHECK (
        (status = 'PENDING' AND approver IS NULL AND approver_role IS NULL AND decided_at IS NULL)
        OR (status <> 'PENDING' AND approver IS NOT NULL AND approver_role IS NOT NULL
            AND decided_at IS NOT NULL)
    );

ALTER TABLE release_approvals
    ADD CONSTRAINT ck_release_approvals_reject_reason CHECK (status <> 'REJECTED' OR reason IS NOT NULL);

CREATE INDEX idx_release_approvals_release_id ON release_approvals (release_id);

CREATE INDEX idx_release_approvals_round_id ON release_approvals (round_id);
