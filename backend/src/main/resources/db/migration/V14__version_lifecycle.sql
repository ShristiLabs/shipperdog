-- V14__version_lifecycle.sql
-- Version lifecycle, revocation, and permanent history (issue #19, S4-03).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): a value is added
--     to the status enum, a new table is created; nothing is dropped,
--     renamed, or narrowed.
--   * Constraint names use the explicit ck_/uq_/idx_ prefixes.
--
-- What S4-03 adds on top of the V1/V12 shape:
--
--   * REVOKED joins the version_status vocabulary (FR-008 "revoke
--     distribution"): the strongest terminal state — a version whose
--     distribution is revoked blocks NEW downloads (E7's decision API
--     consumes the status) while everything stays readable: history is
--     permanent (PRD P5, INV-006) and nothing is ever deleted. The
--     ALTER TYPE is additive only, per the Postgres enum rule the
--     kernel follows: values are appended, never renamed or removed
--     (the V1 DISABLED application label stays reserved-unused by the
--     same discipline). ARCHIVED remains the softer terminal state
--     (read-only, out of the default listing); revoke is reachable
--     from DRAFT, ACTIVE, or ARCHIVED, and REVOKED itself is final.
--
--   * version_status_history: the chronological status timeline the
--     history query returns for audit (issue AC5, FR-024's Compliance
--     Reviewer persona). One row per real transition — creation writes
--     the DRAFT row, archive and revoke-distribution append theirs —
--     and the revoke reason is carried on its row so the timeline is
--     self-contained (the audit log keeps the full who/where record;
--     this table is the version module's own audit surface, readable
--     without crossing the module boundary). The table is a pure child
--     of the seam-served version (ADR-0002 seventh amendment doctrine,
--     now standing): no owning-dimension snapshot, served by no seam
--     repository — every path resolves the version through the scoped
--     parent first.
--
-- Rows written before V14 (dev databases only; production is greenfield
-- behind this kernel) have no DRAFT history row; the timeline of those
-- rows begins with their first post-V14 transition, which is correct —
-- history is recorded going forward, never reconstructed.

-- ---------------------------------------------------------------------------
-- Enum
-- ---------------------------------------------------------------------------

-- Appended after ARCHIVED (enum ordering is unused by the queries; the
-- value's position is irrelevant, its presence is the contract). Not
-- referenced elsewhere in this migration: a new enum value cannot be
-- used in the same transaction that creates it.
ALTER TYPE version_status ADD VALUE 'REVOKED';

-- ---------------------------------------------------------------------------
-- Status timeline
-- ---------------------------------------------------------------------------

CREATE TABLE version_status_history (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id  uuid NOT NULL REFERENCES versions (id),
    status      version_status NOT NULL,
    reason      varchar(1000),
    occurred_at timestamptz NOT NULL DEFAULT now()
);

-- The timeline is read by version (chronological); the seam predicate
-- lives on the parent versions row this table hangs off.
CREATE INDEX idx_version_status_history_version_id
    ON version_status_history (version_id);

-- Service-side trim makes blank reasons impossible through the API;
-- the database caps the width for any non-service writer.
ALTER TABLE version_status_history
    ADD CONSTRAINT ck_version_status_history_reason_length
    CHECK (reason IS NULL OR char_length(reason) <= 1000);
