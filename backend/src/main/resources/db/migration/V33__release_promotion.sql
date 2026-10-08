-- V33__release_promotion.sql
-- The promotion engine's history and approval-round ring facts
-- (issue #44, S10-05, FR-018, PRD 8.10/17/18/33, INV-008).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one new table,
--     one new nullable column; nothing is dropped and no existing
--     column is narrowed.
--
--   * release_promotions (new): the append-only promotion timeline PRD
--     18 demands ("promotion should preserve release history"). One
--     row per governed promote ATTEMPT: COMPLETED hops and BLOCKED
--     precheck refusals both record (PRD 33: "a failed gate blocks
--     promotion with recorded evidence"; the blockers member carries
--     the complete named list with evidence links, exactly what the
--     409 answered). Pre-ring refusals (invalid state, a split move
--     set, no next ring, a skip) answer the 409 alone — they are
--     request-shape and state conflicts, not governed evidence, and
--     record nothing. Rows are never updated or deleted: the timeline
--     is the product P5 promises. from/to ring ids reference the rings
--     vocabulary; tenants is the jsonb array of tenant ids the attempt
--     moved (or attempted to move). INV-008 needs no column here by
--     design: a promotion row records a ring move and nothing else —
--     the engine writes no artifact or manifest column anywhere, and
--     the integration suite pins artifact SHA-256 and manifest digest
--     equality across every hop.
--
--   * release_approval_rounds.promotion_ring_id (new, NULL): marks a
--     round opened by the PROMOTION engine — the ring the round gates
--     the release INTO. Crossing and rework rounds keep NULL; the
--     promotion engine resolves its round by (release, promotion_ring)
--     so each hop's approvals are its own, and the latest-round rule
--     of the crossing policies is untouched (a promotion round exists
--     only after the release crossed APPROVAL_PENDING, so it can never
--     shadow the crossing's own round).

-- ---------------------------------------------------------------------------
-- release_promotions: the append-only ring-hop timeline
-- ---------------------------------------------------------------------------

CREATE TABLE release_promotions (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id   uuid NOT NULL REFERENCES releases (id),
    -- The ring the moved targets sat on, and the ring they moved to:
    -- the hop's two ends, referenced as stored (the target rows' own
    -- ring ids at attempt time).
    from_ring_id uuid NOT NULL REFERENCES rings (id),
    to_ring_id   uuid REFERENCES rings (id),
    -- BLOCKED attempts record the ring the hop would have entered as
    -- to_ring_id (a governed refusal always resolved its hop); the
    -- shape check keeps COMPLETED rows honest about their two ends.
    tenants      jsonb NOT NULL,
    status       varchar(16) NOT NULL,
    blockers     jsonb,
    actor_id     uuid NOT NULL,
    occurred_at  timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_release_promotions_status CHECK (status IN ('COMPLETED', 'BLOCKED')),
    CONSTRAINT ck_release_promotions_ring_shape CHECK (
        (status = 'COMPLETED' AND to_ring_id IS NOT NULL)
        OR (status = 'BLOCKED')
    )
);

-- The timeline read (chronological per release) and the actor trail.
CREATE INDEX idx_release_promotions_release
    ON release_promotions (release_id, occurred_at);

-- ---------------------------------------------------------------------------
-- release_approval_rounds: the promotion-round marker
-- ---------------------------------------------------------------------------

ALTER TABLE release_approval_rounds
    ADD COLUMN promotion_ring_id uuid REFERENCES rings (id);
