-- V54__telemetry_sdk.sql
-- The tester telemetry SDK (issue #72, S15-01, FR-029, PRD 37): the
-- frozen events contract grows exactly ONE additive optional member --
-- the client event id -- and the dedup index that turns the contract's
-- documented at-least-once delivery into idempotent landing by
-- (device, release, event id), the acceptance criteria's dedup key.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one nullable
--     column and one partial unique index; nothing existing is
--     dropped, renamed, or re-typed.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.
--
-- docs/analytics.md froze the S11-04 contract with this addition
-- named in advance ("the optional client event id S15-01 may add is
-- additive to this contract"): events WITHOUT an event id keep the
-- contract's original at-least-once semantics byte-for-byte (a
-- retried batch lands twice); events WITH one land at most once per
-- (device, release, event id) — the SDK emits one on every event, so
-- its offline queue's retries are idempotent end to end.
--
-- The device component of the key uses a sentinel for "no device"
-- (COALESCE to the nil uuid) rather than NULLS NOT DISTINCT: the
-- expression form behaves identically on every supported PostgreSQL
-- version and the index serves exactly this probe (the re-filter
-- dedup the ingest service performs before every write), never a
-- general query plan.

ALTER TABLE analytics_events ADD COLUMN event_id uuid;

CREATE UNIQUE INDEX uq_analytics_events_client_id
    ON analytics_events (
        release_id,
        COALESCE(device_id, '00000000-0000-0000-0000-000000000000'::uuid),
        event_id)
    WHERE event_id IS NOT NULL;

COMMENT ON COLUMN analytics_events.event_id IS
    'The optional client event id (S15-01): dedup key with (device, release) for at-least-once delivery; NULL rows keep the original non-idempotent contract.';
