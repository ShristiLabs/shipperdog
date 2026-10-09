-- V36__feedback_triage.sql
-- Feedback triage and release linkage (issue #47, S11-02, FR-027,
-- PRD 8.13/32/33): the QA surface that works a report after it lands
-- and the columns the open-critical-bugs metric reads.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): the V1
--     feedback_status vocabulary is EXPANDED, never rewritten. The
--     born state keeps its name — OPEN is the NEW of the issue's
--     triage vocabulary — and the two states the S11-02 acceptance
--     criteria add, TRIAGED (the report is being worked) and REJECTED
--     (the wont-fix answer beside RESOLVED), arrive as enum values.
--     Nothing is dropped and no value is renamed; DISMISSED keeps its
--     historical meaning with no new incoming edge (its rows re-open
--     through the same TRIAGED door as every other resolved state).
--     ALTER TYPE ... ADD VALUE is legal inside the migration's
--     transaction because no statement here USES the new values.
--
--   * feedback (expanded): the triage columns. severity is the QA
--     engineer's classification (CRITICAL/MAJOR/MINOR) — null until
--     triage assigns it, because the SUBMITTING tester does not
--     classify (one vocabulary, one authority). assignee_user_id is
--     the organization member working the report. resolution_note is
--     the mandatory explanation a RESOLVED or REJECTED crossing
--     records. Submissions stay immutable: these columns mutate only
--     through the triage actions, never the description, type, or
--     attachments (the S11-01 posture, kept).
--
--   * The metric index: the open-critical-bugs gate and policy metric
--     is a real-time count over this table (the issue's technical
--     note — volumes are low in M2; S11-04's pipeline can formalize
--     the feed later). The composite (release_id, severity, status)
--     serves the count and the release dashboard's rollup alike.

-- ---------------------------------------------------------------------------
-- feedback_status: the triage vocabulary appended (expand-only)
-- ---------------------------------------------------------------------------
ALTER TYPE feedback_status ADD VALUE IF NOT EXISTS 'TRIAGED';
ALTER TYPE feedback_status ADD VALUE IF NOT EXISTS 'REJECTED';

-- ---------------------------------------------------------------------------
-- feedback: the triage columns
-- ---------------------------------------------------------------------------
CREATE TYPE feedback_severity AS ENUM ('CRITICAL', 'MAJOR', 'MINOR');

ALTER TABLE feedback ADD COLUMN severity feedback_severity;
ALTER TABLE feedback ADD COLUMN assignee_user_id uuid REFERENCES users (id);
ALTER TABLE feedback ADD COLUMN resolution_note varchar(4000);

ALTER TABLE feedback
    ADD CONSTRAINT fk_feedback_assignee_user_id
        FOREIGN KEY (assignee_user_id) REFERENCES users (id);

-- The metric and rollup index: counts key on the release grain with
-- the severity/status predicate beside it.
CREATE INDEX idx_feedback_release_severity_status ON feedback (release_id, severity, status);
