-- V46__google_play_connector.sql
-- The Google Play connector's schema (issue #64, S13-04, FR-021,
-- PRD 19/25): one new table, nothing existing touched.
--
--   * channel_release_notes -- the per-locale release notes an
--     operator authors BEFORE approval for a channel whose listing
--     metadata is not in play (the Play testing tracks of this story
--     submit release notes only; the full listing surfaces belong to
--     later stories). The manifest's store-submission block freezes
--     these rows at the APPROVED crossing exactly like the V45
--     metadata rows do -- PRD 16's immutable copy of what was sent --
--     and the dispatch at the ACTIVE crossing sends THE FROZEN BLOCK.
--     Channel-generic by design (the V45 grain, minus the listing
--     fields this surface does not own).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds one table
--     with its indexes; nothing is dropped and no existing column is
--     narrowed.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.

-- ---------------------------------------------------------------------------
-- channel_release_notes: the authored store release notes
-- ---------------------------------------------------------------------------
CREATE TABLE channel_release_notes (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- The application whose store release the notes describe (the
    -- manifest freeze and the dispatch both resolve by it).
    application_id uuid NOT NULL REFERENCES applications (id),
    -- The channel reference name (the registry's vocabulary): today
    -- 'play'; a future store whose release notes ride the same grain
    -- authors here too.
    channel varchar(64) NOT NULL,
    -- The store locale the row describes (Play's BCP-47 vocabulary,
    -- e.g. en-US or de-DE).
    locale varchar(32) NOT NULL,
    -- The locale's release-note text the submission sends. Google's
    -- own cap is 500 characters; the column tolerates the authored
    -- maximum and the authoring surface enforces the provider's cap.
    notes varchar(512) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_channel_release_notes_channel CHECK (
        char_length(btrim(channel)) BETWEEN 1 AND 64),
    CONSTRAINT ck_channel_release_notes_locale CHECK (
        locale ~ '^[a-z]{2}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_channel_release_notes_notes CHECK (
        char_length(btrim(notes)) BETWEEN 1 AND 512),
    -- One row per locale per channel per application: the authoring
    -- grain (a second write to the same locale rotates the row, never
    -- a duplicate submission source).
    CONSTRAINT uq_channel_release_notes_app_channel_locale
        UNIQUE (application_id, channel, locale)
);

CREATE INDEX idx_channel_release_notes_organization_id
    ON channel_release_notes (organization_id);
CREATE INDEX idx_channel_release_notes_application_id
    ON channel_release_notes (application_id);
