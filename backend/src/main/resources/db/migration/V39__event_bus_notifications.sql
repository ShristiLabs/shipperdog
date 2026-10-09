-- V39__event_bus_notifications.sql
-- The domain-event bus and the notification service (issue #51,
-- S12-01, FR-023, PRD 51): the transactional outbox that makes every
-- wired domain transition a durable event, and the subscription /
-- delivery model that turns those events into notifications.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): new types and
--     tables only; nothing existing is dropped, renamed, or re-typed.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.
--
-- The architecture (ADR-0007, docs/notifications.md): domain modules
-- publish their typed in-process events inside the committing
-- transaction (the established discipline); the notification module's
-- outbox recorder observes each one BEFORE_COMMIT and appends a
-- notification_outbox row in the SAME transaction (the
-- PersistentAuditEventSink pattern from S1-06 -- a business change and
-- its bus event commit atomically, so a crash can never separate
-- them). A scheduled relay drains PENDING rows and publishes them to
-- the in-process bus, marking RELAYED -- at-least-once by
-- construction (a crash between publish and mark replays the row),
-- with consumers idempotent by event id (the delivery unique key).
-- The bus port (DomainEventBus) is the documented Kafka / Cloud
-- Pub/Sub upgrade seam: swapping the relay's publish to a broker is a
-- new implementation of one interface, no domain change.
--
--   * notification_outbox: the durable event store -- the frozen
--     public event-name vocabulary (DomainEventName), the PRD 51
--     entity context columns (organization always; tenant /
--     application / version / artifact / release when the transition
--     carries them), the originating request's correlation id, the
--     event-specific payload as jsonb, and the relay bookkeeping
--     (state, attempts, next_attempt_at, relayed_at).
--   * notification_subscriptions: the org-scoped routing table of
--     FR-023 -- event types x channel x recipients. Channels are
--     IN_APP (the inbox row IS the delivery), EMAIL (the EmailSender
--     seam; SMTP transport is S12-02), and WEBHOOK (HMAC-signed
--     HTTP POST with retry/backoff). Recipients are organization
--     roles and/or explicit user ids; a webhook subscription carries
--     its URL and signing secret instead (the secret is write-only:
--     no served view returns it, no log line carries it). The
--     webhook URL is capped at 2048 characters and must pass the
--     application-side SSRF policy (a publicly routable http(s)
--     host) -- the recipient key below is sized to the same cap so a
--     valid URL can never fail the ledger write. The version column
--     is the optimistic lock: a stale concurrent edit refuses with
--     the public 409 rather than silently resurrecting a rotated
--     secret or replaced URL.
--   * notification_deliveries: the delivery log -- one row per
--     (event, subscription, recipient), the at-least-once idempotency
--     grain. SCHEDULED rows retry with exponential backoff until the
--     attempt ceiling, then stay FAILED with the last error for the
--     log; DELIVERED rows are terminal.

-- ---------------------------------------------------------------------------
-- notification_channel: FR-023's V1.5 delivery channels
-- ---------------------------------------------------------------------------
CREATE TYPE notification_channel AS ENUM (
    'IN_APP',
    'EMAIL',
    'WEBHOOK'
);

-- ---------------------------------------------------------------------------
-- notification_relay_state: the outbox row's relay lifecycle
-- ---------------------------------------------------------------------------
CREATE TYPE notification_relay_state AS ENUM (
    'PENDING',
    'RELAYED',
    'FAILED'
);

-- ---------------------------------------------------------------------------
-- notification_delivery_status: one delivery's lifecycle
-- ---------------------------------------------------------------------------
CREATE TYPE notification_delivery_status AS ENUM (
    'SCHEDULED',
    'DELIVERED',
    'FAILED'
);

-- ---------------------------------------------------------------------------
-- notification_outbox: the durable domain-event store (the outbox)
-- ---------------------------------------------------------------------------
CREATE TABLE notification_outbox (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_name      varchar(64) NOT NULL,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    tenant_id       uuid REFERENCES tenants (id),
    application_id  uuid REFERENCES applications (id),
    version_id      uuid REFERENCES versions (id),
    artifact_id     uuid REFERENCES artifacts (id),
    release_id      uuid REFERENCES releases (id),
    correlation_id  varchar(64),
    payload         jsonb NOT NULL,
    occurred_at     timestamptz NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    relay_state     notification_relay_state NOT NULL DEFAULT 'PENDING',
    relay_attempts  int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    relayed_at      timestamptz,
    CONSTRAINT ck_notification_outbox_relay_shape CHECK (
        (relay_state = 'PENDING' AND relayed_at IS NULL)
        OR (relay_state IN ('RELAYED', 'FAILED') AND relayed_at IS NOT NULL)),
    CONSTRAINT ck_notification_outbox_attempts_nonnegative CHECK (relay_attempts >= 0)
);

-- The relay's drain query: due PENDING rows in occurrence order.
CREATE INDEX idx_notification_outbox_relay
    ON notification_outbox (relay_state, next_attempt_at, occurred_at);

-- The per-organization event history read (the delivery log joins it).
CREATE INDEX idx_notification_outbox_organization
    ON notification_outbox (organization_id, occurred_at);

-- ---------------------------------------------------------------------------
-- notification_subscriptions: the org-scoped routing table (FR-023)
-- ---------------------------------------------------------------------------
CREATE TABLE notification_subscriptions (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    uuid NOT NULL REFERENCES organizations (id),
    event_types        jsonb NOT NULL,
    channel            notification_channel NOT NULL,
    recipient_roles    jsonb NOT NULL,
    recipient_user_ids jsonb NOT NULL,
    webhook_url        text,
    webhook_secret     text,
    enabled            boolean NOT NULL DEFAULT true,
    version            bigint NOT NULL DEFAULT 0,
    created_by         uuid NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_subscriptions_event_types_non_empty CHECK (
        jsonb_typeof(event_types) = 'array' AND jsonb_array_length(event_types) > 0),
    CONSTRAINT ck_notification_subscriptions_recipient_shape CHECK (
        (channel IN ('IN_APP', 'EMAIL')
            AND jsonb_array_length(recipient_roles) + jsonb_array_length(recipient_user_ids) > 0
            AND webhook_url IS NULL AND webhook_secret IS NULL)
        OR (channel = 'WEBHOOK'
            AND webhook_url IS NOT NULL
            AND webhook_secret IS NOT NULL
            AND webhook_url ~ '^https?://.+'
            AND char_length(webhook_url) <= 2048
            AND char_length(webhook_secret) >= 16))
);

-- The dispatcher's routing read: an organization's live subscriptions.
CREATE INDEX idx_notification_subscriptions_organization
    ON notification_subscriptions (organization_id, enabled);

-- ---------------------------------------------------------------------------
-- notification_deliveries: the delivery log + retry ledger
-- ---------------------------------------------------------------------------
CREATE TABLE notification_deliveries (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        uuid NOT NULL REFERENCES notification_outbox (id),
    subscription_id uuid NOT NULL REFERENCES notification_subscriptions (id),
    -- The organization grain, denormalized from the subscription for
    -- the log's org-scoped reads (the ledger never re-joins routing).
    organization_id uuid NOT NULL REFERENCES organizations (id),
    channel         notification_channel NOT NULL,
    -- The canonical recipient rendering, sized to the webhook URL cap
    -- ("webhook:" + 2048; user:/email: keys are far inside it) so a
    -- URL the subscription rules accept can never fail this column.
    recipient_key   varchar(2056) NOT NULL,
    status          notification_delivery_status NOT NULL DEFAULT 'SCHEDULED',
    attempts        int NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error      varchar(512),
    delivered_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- At-least-once idempotency (AC3): a replayed outbox row (crash
    -- between publish and mark) re-enters dispatch, and the unique
    -- key collapses the duplicate -- consumers see each (event,
    -- subscription, recipient) once.
    CONSTRAINT uq_notification_deliveries_idempotent
        UNIQUE (event_id, subscription_id, recipient_key),
    CONSTRAINT ck_notification_deliveries_status_shape CHECK (
        (status = 'SCHEDULED' AND next_attempt_at IS NOT NULL AND delivered_at IS NULL)
        OR (status = 'DELIVERED' AND delivered_at IS NOT NULL AND next_attempt_at IS NULL)
        OR (status = 'FAILED' AND delivered_at IS NULL AND next_attempt_at IS NULL AND last_error IS NOT NULL)),
    CONSTRAINT ck_notification_deliveries_attempts_nonnegative CHECK (attempts >= 0)
);

-- The retry runner's drain query: due SCHEDULED rows.
CREATE INDEX idx_notification_deliveries_retry
    ON notification_deliveries (status, next_attempt_at);

-- The delivery log's per-subscription read.
CREATE INDEX idx_notification_deliveries_subscription
    ON notification_deliveries (subscription_id, created_at);

-- The inbox read: a user's in-app rows, newest first.
CREATE INDEX idx_notification_deliveries_recipient
    ON notification_deliveries (recipient_key, channel, created_at);
