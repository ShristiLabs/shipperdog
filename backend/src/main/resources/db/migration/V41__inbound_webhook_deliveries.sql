-- V41__inbound_webhook_deliveries.sql
-- The generic inbound webhook receiver framework's delivery ledger
-- (issue #53, S12-03, FR-022): one row per VERIFIED, processed
-- delivery -- the idempotency grain duplicate deliveries answer from
-- and the operator's consumed-events view. The framework (constant-
-- time HMAC-SHA256 verification over a rotating secret list, a
-- timestamp-window replay seam, this ledger) is shared by every
-- receiver that follows: S12-04's Jenkins/GitLab/App Store/Google
-- Play surfaces and E13's store connectors reuse it unchanged.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): new types and
--     tables only; nothing existing is dropped, renamed, or re-typed.
--   * Constraint names use the explicit ck_/uq_/idx_ prefixes.
--
-- Design notes:
--   * provider is a varchar, NOT a PG enum: the provider set is an
--     open vocabulary by design (each receiver story appends its
--     key), and open vocabularies are row values, not schema
--     migrations. The closed vocabulary -- the processing outcome --
--     IS the enum.
--   * Rows carry only their final outcome and are written inside the
--     processing transaction (a crash leaves nothing, so a
--     redelivery processes fresh; two concurrent copies of one
--     delivery both process idempotently and the second finisher
--     answers DUPLICATE on the unique key below).
--   * Rejected deliveries (failed signature verification) never
--     reach this ledger -- they are audit events only, so a rejected
--     attempt never blocks a later legitimate redelivery of the same
--     delivery id.

-- ---------------------------------------------------------------------------
-- The processing outcomes of a verified delivery
-- ---------------------------------------------------------------------------
CREATE TYPE inbound_webhook_outcome AS ENUM (
    'ACCEPTED',
    'IGNORED'
);

-- ---------------------------------------------------------------------------
-- The processed-delivery ledger
-- ---------------------------------------------------------------------------
CREATE TABLE inbound_webhook_deliveries (
    id uuid PRIMARY KEY,
    provider varchar(32) NOT NULL,
    delivery_id varchar(255) NOT NULL,
    event_type varchar(64) NOT NULL,
    outcome inbound_webhook_outcome NOT NULL,
    received_at timestamptz NOT NULL,
    CONSTRAINT ck_inbound_webhook_deliveries_provider CHECK (char_length(btrim(provider)) > 0),
    CONSTRAINT ck_inbound_webhook_deliveries_delivery_id CHECK (char_length(btrim(delivery_id)) > 0),
    CONSTRAINT uq_inbound_webhook_deliveries_provider_delivery UNIQUE (provider, delivery_id)
);

CREATE INDEX idx_inbound_webhook_deliveries_received_at
    ON inbound_webhook_deliveries (received_at);
