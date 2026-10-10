-- V42__store_status_events.sql
-- The store receivers' normalized internal events (issue #54, S12-04,
-- FR-022, PRD 19/51): one row per VERIFIED store notification the
-- receiver mapped -- App Store Server Notifications (JWS signedPayload)
-- and Google Play RTDN Pub/Sub pushes. This is the "state-sync
-- foundation" E13's connectors consume: the mapping is deliberately
-- THIN (provider, its own event id, a stable event-type vocabulary,
-- and the store-side identifiers -- bundle id, purchase token,
-- subscription/sku reference), because E13 owns the semantics of what
-- a notification MEANS for a release. No business interpretation is
-- persisted here.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): new table and a
--     new index only; nothing existing is dropped, renamed, or
--     re-typed.
--   * Constraint names use the explicit ck_/uq_/idx_ prefixes.
--
-- Design notes:
--   * provider is a varchar like the V41 ledger's, for the same
--     reason: an open vocabulary is a row value, not a migration.
--   * The UNIQUE (provider, provider_event_id) key is the mapping's
--     own idempotency grain, INDEPENDENT of the transport ledger's
--     (provider, delivery_id) key: a redelivery under a new transport
--     id that carries an already-mapped notification converges on the
--     same single row (the receiver's claim insert answers 0 affected
--     rows and the delivery is acknowledged, never re-mapped).
--   * bundle_id and purchase_token are NULLable by design: Apple
--     notifications carry a bundle id but no purchase token; Google
--     Play RTDN envelopes carry a purchase token but NO package name
--     (the subscription's app is resolved by E13 from the token).
--   * The second index below serves the provenance backfill's
--     commit-matched lookup (the #53 deferred hardening): the
--     receiver's native query filters artifacts by
--     provenance ->> 'gitCommit' = :commit over SETTLED rows, which
--     without an expression index is a full-table scan that grows
--     with the artifact registry. The partial predicate covers every
--     row the query can match.

-- ---------------------------------------------------------------------------
-- The normalized store-status events (E13's connector input)
-- ---------------------------------------------------------------------------
CREATE TABLE store_status_events (
    id uuid PRIMARY KEY,
    provider varchar(32) NOT NULL,
    provider_event_id varchar(255) NOT NULL,
    event_type varchar(128) NOT NULL,
    bundle_id varchar(255),
    purchase_token varchar(512),
    external_ref varchar(255),
    received_at timestamptz NOT NULL,
    CONSTRAINT ck_store_status_events_provider CHECK (char_length(btrim(provider)) > 0),
    CONSTRAINT ck_store_status_events_provider_event_id CHECK (char_length(btrim(provider_event_id)) > 0),
    CONSTRAINT ck_store_status_events_event_type CHECK (char_length(btrim(event_type)) > 0),
    CONSTRAINT uq_store_status_events_provider_event UNIQUE (provider, provider_event_id)
);

CREATE INDEX idx_store_status_events_received_at
    ON store_status_events (received_at);

-- ---------------------------------------------------------------------------
-- The provenance backfill's commit-matched lookup, served by an index
-- (issue #53 deferred finding, folded into #54)
-- ---------------------------------------------------------------------------
CREATE INDEX idx_artifacts_provenance_git_commit
    ON artifacts ((provenance ->> 'gitCommit'))
    WHERE provenance ? 'gitCommit';
