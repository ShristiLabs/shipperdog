-- V22__grant_revocation.sql
-- Grant revocation semantics (issue #33, S7-04, INV-007 / FR-024).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only (ADR-0001): adds columns, one enum
--     type, and checks; the one index replacement below follows the
--     V21 unique-index precedent (drop + recreate) and drops no data.
--   * Constraint names use the explicit fk_/ck_/uq_/idx_ prefixes.
--
-- S7-04 turns grant removal into revocation: INV-007's "authorization
-- can be revoked without deleting" as row state, not row death. The
-- V21 design made DELETE the removal primitive with the audit trail
-- as the only history; S7-04's story note supersedes that at the data
-- layer — "no deletes anywhere", the revoked row RETAINED so auditors
-- can ask "what did tester X have on date Y" (the S6-03 retained-row
-- precedent, PRD 16's reproducibility ethos). The changes:
--
--   * `status` (ACTIVE/REVOKED, a new enum type — the membership_status
--     precedent): evaluation considers ACTIVE grants only; a revoked
--     row never authorizes, whatever its expiry says. Existing rows
--     backfill ACTIVE (the only state a V21 row could hold).
--   * The revocation facts on the row: `revoked_at`, `revoked_by`
--     (the revoking actor), and `revocation_reason` (the mandatory
--     1..1000 FR-024 reason, trimmed by the service, kept on the row
--     so the revocation report answers from the data itself).
--   * `uq_version_access_grant` (V21's full-identity unique index)
--     is REPLACED by a PARTIAL twin over ACTIVE rows only: a re-grant
--     after revocation mints a NEW row (the story's acceptance
--     criterion 4 — the revoked row is retained, effective access is
--     the new grant only), so the duplicate authority must scope to
--     the rows that authorize. NULLS NOT DISTINCT carries over
--     unchanged (PostgreSQL 15+ partial unique indexes support it).

-- ---------------------------------------------------------------------------
-- Enum vocabulary
-- ---------------------------------------------------------------------------

CREATE TYPE version_access_status AS ENUM ('ACTIVE', 'REVOKED');

-- ---------------------------------------------------------------------------
-- Columns
-- ---------------------------------------------------------------------------

-- The revocation switch: ACTIVE authorizes (subject to expiry and the
-- chain), REVOKED never does. NOT NULL with the ACTIVE default is the
-- backfill — V21 rows are all ACTIVE by construction.
ALTER TABLE version_access ADD COLUMN status version_access_status NOT NULL
    DEFAULT 'ACTIVE';

-- The revocation facts (all NULL while ACTIVE). The reason rides the
-- row for the revocation report; the audit event carries actor +
-- reason + before/after as well (FR-024).
ALTER TABLE version_access ADD COLUMN revoked_at timestamptz;
ALTER TABLE version_access ADD COLUMN revoked_by uuid CONSTRAINT fk_version_access_revoked_by
    REFERENCES users (id);
ALTER TABLE version_access ADD COLUMN revocation_reason varchar(1000);

-- ---------------------------------------------------------------------------
-- Checks
-- ---------------------------------------------------------------------------

-- Revocation is all-or-nothing: either all three facts are set or none.
ALTER TABLE version_access ADD CONSTRAINT ck_version_access_revocation_shape CHECK (
    (revoked_at IS NULL AND revoked_by IS NULL AND revocation_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revocation_reason IS NOT NULL));

-- ---------------------------------------------------------------------------
-- Constraint replacement (V21's own precedent: drop + recreate the
-- unique index; no data is dropped)
-- ---------------------------------------------------------------------------

-- The duplicate authority scoped to the rows that authorize: the same
-- grant identity may exist any number of times across history, but at
-- most once among ACTIVE rows. Idempotent-create replays and expiry
-- conflicts therefore consider ACTIVE rows only (the service's
-- findSameGrant filter, pinned by tests).
DROP INDEX uq_version_access_grant;
CREATE UNIQUE INDEX uq_version_access_grant ON version_access
    (tenant_id, tester_id, application_id, access_type, version_id, tag, latest_n)
    NULLS NOT DISTINCT WHERE (status = 'ACTIVE');
