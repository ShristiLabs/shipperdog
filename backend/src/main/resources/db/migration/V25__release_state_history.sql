-- V25__release_state_history.sql
-- Release state machine history (issue #56, S9-02, FR-024, PRD 17).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one new table;
--     nothing is dropped, renamed, or narrowed.
--   * Constraint names use the explicit ck_/idx_ prefixes.
--
-- What S9-02 adds:
--
--   * release_status_history: the append-only transition timeline the
--     history API returns (issue AC5, FR-024) — one row per real
--     transition, from the creation's DRAFT row (from_status NULL, the
--     only row a release is born with) through every legal crossing of
--     PRD 17's state machine. The V14 version_status_history doctrine
--     applied to the release module's own audit surface: the row
--     carries the actor (the issue's actor/timestamp demand — the
--     version timeline left who to the audit log; releases are the
--     timeline the S9-05 dashboard renders, so who is on the row), the
--     mandatory reason of the failure and rollback rows (the S4-03
--     revoke analog), and the transition timestamps. The table is a
--     pure child of the seam-served release (the seventh amendment
--     doctrine, standing since V14): no owning-dimension snapshot, no
--     seam repository — every path resolves the release through the
--     scoped parent first.
--
--   * No enum change: V1's release_status already carries the full
--     PRD 17 vocabulary (all eleven values, present since the baseline
--     sketch); S9-01 wrote exactly DRAFT, and this story is their
--     first mover. The compare-and-set discipline the transitions use
--     (the V14 CAS precedent) lives in the repository layer as a
--     native UPDATE, not in the schema.
--
-- Rows written before V25 (dev databases only; production is greenfield
-- behind this kernel) have no DRAFT history row; their timeline begins
-- with the first post-V25 transition — history is recorded going
-- forward, never reconstructed (the V14 rule).

CREATE TABLE release_status_history (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id  uuid NOT NULL REFERENCES releases (id),
    -- NULL on exactly one row per release: the creation's DRAFT row
    -- (a release is born, it does not transition into DRAFT).
    from_status release_status,
    to_status   release_status NOT NULL,
    -- Mandatory for the failure (VALIDATING/DEPLOYING -> FAILED) and
    -- rollback (ACTIVE -> ROLLED_BACK) transitions — the service
    -- refuses a blank one; optional everywhere else.
    reason      varchar(1000),
    -- Who moved the release (the issue's actor/timestamp demand); a
    -- bare uuid like releases.created_by — the audit log keeps the
    -- full who/where record, this column is the timeline's attribution.
    actor_id    uuid NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

-- The timeline is read by release (chronological); the seam predicate
-- lives on the parent releases row this table hangs off.
CREATE INDEX idx_release_status_history_release_id
    ON release_status_history (release_id);

-- Service-side trim makes blank reasons impossible through the API;
-- the database caps the width for any non-service writer (the V14 cap).
ALTER TABLE release_status_history
    ADD CONSTRAINT ck_release_status_history_reason_length
    CHECK (reason IS NULL OR char_length(reason) <= 1000);
