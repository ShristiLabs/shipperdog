-- V40__slack_teams_notifiers.sql
-- The Slack and Microsoft Teams channels (issue #52, S12-02, FR-023):
-- two new values of the existing notification_channel vocabulary, riding
-- the webhook machinery S12-01 shipped (URL + signing secret + target
-- policy + retry ledger unchanged -- the only new surface is the
-- provider-shaped message the dispatcher renders per channel).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): enum values are
--     appended, never reordered or removed; nothing existing is dropped
--     or re-typed.
--   * Constraint names use the explicit ck_ prefix.
--
-- The recipient-shape check is re-added WIDER, not weaker: the
-- URL-carrying branch now admits every channel that is not IN_APP or
-- EMAIL -- the SLACK and TEAMS values this migration appends. The
-- predicate deliberately references only PRE-EXISTING enum values
-- (channel NOT IN ('IN_APP', 'EMAIL')) so the whole migration stays
-- one Flyway transaction: PostgreSQL holds ALTER TYPE ... ADD VALUE
-- inside a transaction since 12, but a new value may not be USED by a
-- later statement in that same transaction -- this shape never names
-- the new values in SQL at all.
--
-- The delivery ledger needs no change: its channel column is the same
-- enum type (the new values flow through it), and recipient_key keeps
-- the 2056 ceiling sized in V39 ("slack:" and "teams:" prefixes are
-- shorter than the "webhook:" the ceiling was sized around).

-- ---------------------------------------------------------------------------
-- FR-023's chat channels: new notification_channel values
-- ---------------------------------------------------------------------------
ALTER TYPE notification_channel ADD VALUE IF NOT EXISTS 'SLACK';
ALTER TYPE notification_channel ADD VALUE IF NOT EXISTS 'TEAMS';

-- ---------------------------------------------------------------------------
-- The recipient-shape check, re-admitted for the URL-carrying channels
-- ---------------------------------------------------------------------------
ALTER TABLE notification_subscriptions
    DROP CONSTRAINT ck_notification_subscriptions_recipient_shape;

ALTER TABLE notification_subscriptions
    ADD CONSTRAINT ck_notification_subscriptions_recipient_shape CHECK (
        (channel IN ('IN_APP', 'EMAIL')
            AND jsonb_array_length(recipient_roles) + jsonb_array_length(recipient_user_ids) > 0
            AND webhook_url IS NULL
            AND webhook_secret IS NULL)
        OR (channel NOT IN ('IN_APP', 'EMAIL')
            AND webhook_url IS NOT NULL
            AND webhook_secret IS NOT NULL
            AND webhook_url ~ '^https?://.+'
            AND char_length(webhook_url) <= 2048
            AND char_length(webhook_secret) >= 16));
