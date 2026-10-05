-- V6__api_conventions.sql
-- API conventions storage (issue #8, S1-08): the idempotency-key ledger
-- backing the Idempotency-Key header contract on designated POSTs
-- (artifact upload joins in S5-01). One row per (principal, key, method,
-- path); the header value is stored only as a SHA-256 hash so the table
-- never accumulates caller-controlled plaintext.
--
-- Conventions (docs/database-migrations.md):
--   * uuid DEFAULT gen_random_uuid() primary keys; timestamptz audit
--     columns maintained by the application layer.
--   * forward-only: this table is new, no prior state to migrate.

CREATE TABLE idempotency_keys (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    principal_id           UUID         NOT NULL,
    key_hash               VARCHAR(64)  NOT NULL,
    method                 VARCHAR(8)   NOT NULL,
    request_path           VARCHAR(512) NOT NULL,
    state                  VARCHAR(16)  NOT NULL,
    response_status        INT,
    response_content_type  VARCHAR(255),
    response_body          TEXT,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at             TIMESTAMPTZ  NOT NULL,
    CONSTRAINT idempotency_scope UNIQUE (principal_id, key_hash, method, request_path)
);

-- Lazy expiry sweeps prune from this index.
CREATE INDEX idempotency_keys_expires_at_idx ON idempotency_keys (expires_at);
