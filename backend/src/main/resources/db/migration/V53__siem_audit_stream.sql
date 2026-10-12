-- V53__siem_audit_stream.sql
-- The SIEM export & audit stream (issue #71, S14-05, PRD 27/38,
-- FR-024): the org-scoped export configuration that turns the
-- immutable audit trail into a real-time compliance feed -- a
-- per-organization routing of audit events to a customer SIEM.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): new types and
--     tables only; nothing existing is dropped, renamed, or re-typed.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.
--
-- The architecture (docs/siem.md): the notification module's S12-01
-- delivery pipeline carries the stream -- audit events bridge into
-- notification_outbox (one row per published audit event of an
-- organization that holds at least one enabled export, written in the
-- publishing transaction by the outbox recorder's audit listener),
-- the standing relay drains them to the bus as DomainEventName
-- AUDIT_EVENT, and the audit-stream dispatcher fans each one out to
-- the organization's enabled HTTPS exports through the same signed
-- POST discipline the webhook channel uses (AC1). The bucket leg is
-- the periodic batcher over the append-only audit_events store
-- (AC2): a keyset watermark per export, bounded rows per pass,
-- versioned JSONL batches with signed manifests. Delivery state is
-- its own ledger grain (audit_stream_deliveries): one row per
-- (event, export), the at-least-once idempotency collapse, retry
-- with exponential backoff to the attempt ceiling, then FAILED --
-- the visible, replayable dead-letter (AC3).
--
--   * audit_stream_exports: the configuration -- kind (HTTPS or
--     BUCKET), the destination (a public http(s) collector URL or a
--     gs:// bucket URI with optional prefix), the export format for
--     HTTPS (SHIPPERDOG_JSON or SPLUNK_HEC), the event-type filter
--     (AuditEventType names; an empty array streams everything,
--     including the ABAC denial and evaluation-fault families the
--     #70 follow-ups named SIEM candidates), and the SECRETS as
--     secret-manager references only (AC5): signing_secret_ref for
--     the HMAC leg (every delivery; the bucket manifest), and for
--     HTTPS optionally an auth header whose value resolves from a
--     reference at delivery time (the generic collectors'
--     header-auth, the Splunk HEC bearer token). No secret material
--     is ever stored. The version column is the optimistic lock (a
--     concurrent edit refuses with the public 409). The bucket
--     watermark (occurred_at + id) is the batcher's keyset cursor;
--     batch_seq is the last written batch's sequence number.
--   * audit_stream_deliveries: the HTTPS leg's delivery ledger --
--     same shape semantics as notification_deliveries (SCHEDULED
--     retries with next_attempt_at; DELIVERED terminal; FAILED
--     terminal at the ceiling with the last error), one row per
--     (event, export) -- the replay action resets FAILED rows to
--     SCHEDULED, the dead-letter is never lost material.
--   * audit_stream_batches: the bucket leg's batch history -- one
--     row per written batch with its sequence, watermark window,
--     event count, content digest, and both object URIs (the
--     batch and its manifest). The cumulative event_count sum is
--     the completeness answer against the audit table's count for
--     the same window (AC2's verifiable completeness).

-- ---------------------------------------------------------------------------
-- audit_stream_kind: the export's transport leg
-- ---------------------------------------------------------------------------
CREATE TYPE audit_stream_kind AS ENUM (
    'HTTPS',
    'BUCKET'
);

-- ---------------------------------------------------------------------------
-- audit_stream_format: the HTTPS leg's payload shape (the exported
-- event JSON is a versioned public API, docs/siem.md -- the format
-- selects the wrapper, the envelope members are frozen per version)
-- ---------------------------------------------------------------------------
CREATE TYPE audit_stream_format AS ENUM (
    'SHIPPERDOG_JSON',
    'SPLUNK_HEC'
);

-- ---------------------------------------------------------------------------
-- audit_stream_delivery_status: the HTTPS ledger's row lifecycle
-- ---------------------------------------------------------------------------
CREATE TYPE audit_stream_delivery_status AS ENUM (
    'SCHEDULED',
    'DELIVERED',
    'FAILED'
);

-- ---------------------------------------------------------------------------
-- audit_stream_exports: the org-scoped export configuration
-- ---------------------------------------------------------------------------
CREATE TABLE audit_stream_exports (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    uuid NOT NULL REFERENCES organizations (id),
    kind               audit_stream_kind NOT NULL,
    format             audit_stream_format,
    -- The stream's event-type filter: AuditEventType names. An empty
    -- array is valid and means EVERYTHING streams (the SIEM default;
    -- the filter selects, it never widens).
    event_types        jsonb NOT NULL DEFAULT '[]'::jsonb,
    -- The HTTPS destination (kind = HTTPS): a public http(s) URL
    -- through the same SSRF policy the webhook channel applies.
    destination_url    varchar(2048),
    -- The BUCKET destination (kind = BUCKET): gs://bucket/prefix.
    bucket_uri         varchar(2048),
    signing_secret_ref varchar(512) NOT NULL,
    auth_header_name   varchar(64),
    auth_secret_ref    varchar(512),
    enabled            boolean NOT NULL DEFAULT true,
    -- The batcher's keyset watermark (kind = BUCKET): the last
    -- exported row's (occurred_at, id); NULL until the first batch.
    watermark          timestamptz,
    watermark_id       uuid,
    batch_seq          bigint NOT NULL DEFAULT 0,
    version            bigint NOT NULL DEFAULT 0,
    created_by         uuid NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_stream_exports_kind_shape CHECK (
        (kind = 'HTTPS'
            AND format IS NOT NULL
            AND destination_url IS NOT NULL
            AND destination_url ~ '^https?://.+'
            AND char_length(destination_url) <= 2048
            AND bucket_uri IS NULL
            AND watermark IS NULL
            AND watermark_id IS NULL)
        OR (kind = 'BUCKET'
            AND format IS NULL
            AND destination_url IS NULL
            AND bucket_uri IS NOT NULL
            AND bucket_uri ~ '^gs://.+'
            AND char_length(bucket_uri) <= 2048)),
    CONSTRAINT ck_audit_stream_exports_batch_seq_nonnegative CHECK (batch_seq >= 0),
    CONSTRAINT ck_audit_stream_exports_auth_shape CHECK (
        (auth_header_name IS NULL AND auth_secret_ref IS NULL)
        OR (auth_header_name IS NOT NULL AND auth_secret_ref IS NOT NULL))
);

-- The dispatcher's routing read: an organization's live exports.
CREATE INDEX idx_audit_stream_exports_organization
    ON audit_stream_exports (organization_id, enabled);

-- The batcher's scan: enabled bucket exports in id order (one
-- deterministic pass per drain).
CREATE INDEX idx_audit_stream_exports_bucket
    ON audit_stream_exports (kind, enabled, id);

-- ---------------------------------------------------------------------------
-- audit_stream_deliveries: the HTTPS leg's delivery ledger
-- ---------------------------------------------------------------------------
CREATE TABLE audit_stream_deliveries (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        uuid NOT NULL REFERENCES notification_outbox (id),
    -- The export id is a plain reference, deliberately NOT a foreign
    -- key: the ledger is history and survives the export's deletion
    -- (the notification module's own doctrine -- the audit_events
    -- precedent of FK-free entity references that outlive their
    -- entities).
    export_id       uuid NOT NULL,
    -- The organization grain, denormalized from the export for the
    -- log's org-scoped reads (the ledger never re-joins routing).
    organization_id uuid NOT NULL REFERENCES organizations (id),
    format          audit_stream_format NOT NULL,
    status          audit_stream_delivery_status NOT NULL DEFAULT 'SCHEDULED',
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error      varchar(512),
    delivered_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- At-least-once idempotency (AC3): a replayed outbox row re-enters
    -- dispatch, and the unique key collapses the duplicate -- the
    -- collector sees each (event, export) once under its event_id.
    CONSTRAINT uq_audit_stream_deliveries_idempotent
        UNIQUE (event_id, export_id),
    CONSTRAINT ck_audit_stream_deliveries_status_shape CHECK (
        (status = 'SCHEDULED' AND next_attempt_at IS NOT NULL AND delivered_at IS NULL)
        OR (status = 'DELIVERED' AND delivered_at IS NOT NULL AND next_attempt_at IS NULL)
        OR (status = 'FAILED' AND delivered_at IS NULL AND next_attempt_at IS NULL AND last_error IS NOT NULL)),
    CONSTRAINT ck_audit_stream_deliveries_attempts_nonnegative CHECK (attempts >= 0)
);

-- The retry drain's query: due SCHEDULED rows.
CREATE INDEX idx_audit_stream_deliveries_retry
    ON audit_stream_deliveries (status, next_attempt_at);

-- The delivery log's per-export read.
CREATE INDEX idx_audit_stream_deliveries_export
    ON audit_stream_deliveries (export_id, created_at);

-- The dead-letter's read: an organization's terminally failed rows.
CREATE INDEX idx_audit_stream_deliveries_organization
    ON audit_stream_deliveries (organization_id, status, created_at);

-- ---------------------------------------------------------------------------
-- audit_stream_batches: the bucket leg's batch history
-- ---------------------------------------------------------------------------
CREATE TABLE audit_stream_batches (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- A plain reference like the ledger's: the batch history
    -- survives the export's deletion (compliance answers for the
    -- window even after the configuration goes).
    export_id       uuid NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    seq             bigint NOT NULL,
    watermark_from  timestamptz,
    watermark_to    timestamptz NOT NULL,
    event_count     int NOT NULL,
    batch_sha256    varchar(64) NOT NULL,
    object_uri      varchar(2048) NOT NULL,
    manifest_uri    varchar(2048) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_audit_stream_batches_export_seq UNIQUE (export_id, seq),
    CONSTRAINT ck_audit_stream_batches_count_positive CHECK (event_count > 0),
    CONSTRAINT ck_audit_stream_batches_seq_positive CHECK (seq > 0)
);

-- The batch history's per-export read (the completeness ledger).
CREATE INDEX idx_audit_stream_batches_export
    ON audit_stream_batches (export_id, seq);
