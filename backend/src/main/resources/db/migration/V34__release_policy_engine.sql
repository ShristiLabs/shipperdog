-- V34__release_policy_engine.sql
-- The declarative release policy engine's rule store, hold ledger,
-- evaluation log, and skip-relaxation settings (issue #45, S10-06,
-- PRD 33, FR-026's fail-closed ethos).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): five new
--     tables; nothing is dropped and no existing column is narrowed.
--
--   * release_policy_rules (new): PRD 33's declarative rules as DATA,
--     organization-grain reference data (the rings/workflow-definitions
--     pattern). One row per rule: a name unique per organization, a
--     SCOPE (organization-wide, application, ring, or environment),
--     an ACTION (BLOCK, HOLD_FOR_REVIEW, ALLOW), the typed condition
--     tree as jsonb (the S10-02 conditional-predicate format: JSON,
--     validated at write, never a string expression language), the
--     evaluation position (the defined order AC5 pins), and the
--     enabled standing. Scope references are shape-checked columns:
--     application_id and environment_id are foreign keys (their rows
--     are hard reference data), ring is a NAME-ONLY code (the V27/V32
--     precedent — the ring vocabulary is customizable; an unresolved
--     code at evaluation time simply does not match, and write-time
--     validation refuses unresolvable codes).
--
--   * release_policy_holds (new): the HOLD_FOR_REVIEW ledger. A fired
--     HOLD rule records one PENDING hold per (release, rule, ring) —
--     the ring the hop would enter — and the explicit privileged
--     acknowledge (S10-06 AC2) flips it to ACKNOWLEDGED with actor,
--     reason, and timestamp. One row per triple (the unique
--     constraint): the acknowledged decision STANDS for later hops of
--     the same release into the same ring — the acknowledge is a
--     human decision about the rule's condition, not a one-shot
--     token. rule_id is a hard foreign key: a rule with holds cannot
--     be deleted (POLICY_RULE_IN_USE) — the ledger is audit evidence.
--
--   * release_policy_evaluations (new) + release_policy_evaluation_
--     rules (new): the evaluation log (AC5's "all evaluations logged
--     per promotion attempt"). One evaluation row per promote attempt
--     (trigger PROMOTE; the precheck and dry-run surfaces never
--     write) carrying the attempt's verdict; one child row per rule
--     evaluated — in order — with the outcome (NOT_MET, FIRED,
--     NO_DATA, ACKNOWLEDGED) and the evidence detail. Child rows keep
--     a LOOSE rule_id and a rule_name snapshot: the log is history,
--     and history must survive a rule's later deletion (unlike the
--     hold ledger, which the delete guard protects instead).
--
--   * release_policy_settings (new): the skip-relaxation knob — the
--     named rings a promotion of this organization may name BEYOND the
--     next ring (the #44 posture's documented relaxation path, now a
--     registrant of the ReleasePromotionPolicy seam). One row per
--     organization (created lazily on first edit; absence IS the
--     empty set — the default posture stands for every organization
--     that never edits it).

-- ---------------------------------------------------------------------------
-- release_policy_rules: the declarative rule store
-- ---------------------------------------------------------------------------

CREATE TABLE release_policy_rules (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    name            varchar(64) NOT NULL,
    -- The scoping dimension: which promotions the rule evaluates for.
    scope           varchar(16) NOT NULL,
    -- Present iff scope = 'APPLICATION' (the release's application).
    application_id  uuid REFERENCES applications (id),
    -- Present iff scope = 'RING' (the hop's TARGET ring code — the
    -- governance is for ENTERING the ring).
    ring            varchar(64),
    -- Present iff scope = 'ENVIRONMENT' (the release's targeted environment).
    environment_id  uuid REFERENCES environments (id),
    action          varchar(16) NOT NULL,
    condition       jsonb NOT NULL,
    position        integer NOT NULL,
    enabled         boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_release_policy_rules_org_name UNIQUE (organization_id, name),
    CONSTRAINT ck_release_policy_rules_scope CHECK (scope IN ('ORGANIZATION', 'APPLICATION', 'RING', 'ENVIRONMENT')),
    CONSTRAINT ck_release_policy_rules_action CHECK (action IN ('BLOCK', 'HOLD_FOR_REVIEW', 'ALLOW')),
    CONSTRAINT ck_release_policy_rules_scope_shape CHECK (
        (scope = 'ORGANIZATION' AND application_id IS NULL AND ring IS NULL AND environment_id IS NULL)
        OR (scope = 'APPLICATION' AND application_id IS NOT NULL AND ring IS NULL AND environment_id IS NULL)
        OR (scope = 'RING' AND application_id IS NULL AND ring IS NOT NULL AND environment_id IS NULL)
        OR (scope = 'ENVIRONMENT' AND application_id IS NULL AND ring IS NULL AND environment_id IS NOT NULL)
    ),
    CONSTRAINT ck_release_policy_rules_position CHECK (position >= 0 AND position <= 999)
);

CREATE INDEX idx_release_policy_rules_org ON release_policy_rules (organization_id, position, name);

-- ---------------------------------------------------------------------------
-- release_policy_holds: the HOLD_FOR_REVIEW ledger
-- ---------------------------------------------------------------------------

CREATE TABLE release_policy_holds (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id          uuid NOT NULL REFERENCES releases (id),
    rule_id             uuid NOT NULL REFERENCES release_policy_rules (id),
    organization_id     uuid NOT NULL REFERENCES organizations (id),
    -- The ring the blocked hop would have entered (the hold guards entry).
    ring                varchar(64) NOT NULL,
    status              varchar(16) NOT NULL,
    -- The fired condition's evidence snapshot (what the rule saw).
    evidence            jsonb,
    acknowledged_by     uuid,
    acknowledged_at     timestamptz,
    acknowledge_reason  varchar(512),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_release_policy_holds_triple UNIQUE (release_id, rule_id, ring),
    CONSTRAINT ck_release_policy_holds_status CHECK (status IN ('PENDING', 'ACKNOWLEDGED')),
    CONSTRAINT ck_release_policy_holds_ack_shape CHECK (
        (status = 'PENDING' AND acknowledged_by IS NULL AND acknowledged_at IS NULL AND acknowledge_reason IS NULL)
        OR (status = 'ACKNOWLEDGED' AND acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL
            AND acknowledge_reason IS NOT NULL)
    )
);

CREATE INDEX idx_release_policy_holds_release ON release_policy_holds (release_id, created_at);
CREATE INDEX idx_release_policy_holds_rule ON release_policy_holds (rule_id);

-- ---------------------------------------------------------------------------
-- release_policy_evaluations: the per-attempt evaluation log
-- ---------------------------------------------------------------------------

CREATE TABLE release_policy_evaluations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id      uuid NOT NULL REFERENCES releases (id),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- The ring the evaluated hop would have entered.
    ring            varchar(64) NOT NULL,
    trigger         varchar(16) NOT NULL,
    verdict         varchar(16) NOT NULL,
    -- The first BLOCK rule (FIRED or fail-closed NO_DATA) when one
    -- won; the LOOSE id of a rule the log's children snapshot by name.
    winning_rule_id uuid,
    actor_id        uuid NOT NULL,
    occurred_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_release_policy_evaluations_trigger CHECK (trigger IN ('PROMOTE')),
    CONSTRAINT ck_release_policy_evaluations_verdict CHECK (verdict IN ('ALLOWED', 'BLOCKED', 'HELD'))
);

CREATE INDEX idx_release_policy_evaluations_release ON release_policy_evaluations (release_id, occurred_at);

-- ---------------------------------------------------------------------------
-- release_policy_evaluation_rules: one logged outcome per evaluated rule
-- ---------------------------------------------------------------------------

CREATE TABLE release_policy_evaluation_rules (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    evaluation_id  uuid NOT NULL REFERENCES release_policy_evaluations (id) ON DELETE CASCADE,
    -- LOOSE by design: history survives the rule's deletion.
    rule_id        uuid NOT NULL,
    rule_name      varchar(64) NOT NULL,
    rule_position  integer NOT NULL,
    action         varchar(16) NOT NULL,
    outcome        varchar(16) NOT NULL,
    detail         varchar(1024),
    CONSTRAINT uq_release_policy_evaluation_rules UNIQUE (evaluation_id, rule_id),
    CONSTRAINT ck_release_policy_evaluation_rules_outcome
        CHECK (outcome IN ('NOT_MET', 'FIRED', 'NO_DATA', 'ACKNOWLEDGED'))
);

CREATE INDEX idx_release_policy_evaluation_rules_eval
    ON release_policy_evaluation_rules (evaluation_id, rule_position);

-- ---------------------------------------------------------------------------
-- release_policy_settings: the skip-relaxation knob
-- ---------------------------------------------------------------------------

CREATE TABLE release_policy_settings (
    organization_id uuid PRIMARY KEY REFERENCES organizations (id),
    -- The ring codes a promotion may name beyond the next ring
    -- (validated against the organization's vocabulary on write).
    skip_allowances jsonb NOT NULL DEFAULT '[]'::jsonb,
    updated_at      timestamptz NOT NULL DEFAULT now()
);
