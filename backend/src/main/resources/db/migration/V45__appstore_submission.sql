-- V45__appstore_submission.sql
-- The App Store production connector's schema (issue #63, S13-03,
-- FR-020, PRD 16/17/19/25): one new table and one additive column.
--
--   * channel_submission_metadata -- the per-localization store
--     metadata an operator authors BEFORE approval (AC1): description,
--     keywords, screenshot references, and the release notes the
--     submission sends. The manifest's store-submission block freezes
--     these rows at the APPROVED crossing (PRD 16's immutable copy of
--     what was sent -- ASC shows current state, the manifest shows the
--     submission), and the dispatch at the ACTIVE crossing sends THE
--     FROZEN BLOCK, so manifest and wire can never drift apart.
--     Channel-generic by design: Google Play's listing metadata (#64)
--     authors through the same grain.
--   * channel_distributions.failure_detail -- the actionable,
--     human-facing failure reason beside the taxonomy code (AC4): the
--     provider's own words for a rejected submission ("You must
--     provide a value for the key ..."), carried on the record the
--     console renders. The taxonomy code stays the closed vocabulary;
--     the detail is the reason a Release Manager acts on.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one new table
--     with its indexes, and a nullable column ADDED to an existing
--     table (no drop, rename, or re-type); nothing existing narrows.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.

-- ---------------------------------------------------------------------------
-- channel_submission_metadata: the authored store metadata (AC1)
-- ---------------------------------------------------------------------------
CREATE TABLE channel_submission_metadata (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- The application whose store listing this metadata describes (the
    -- manifest freeze and the dispatch both resolve by it).
    application_id uuid NOT NULL REFERENCES applications (id),
    -- The channel reference name (the registry's vocabulary): today
    -- 'appstore'; #64's Play listing metadata authors here too.
    channel varchar(64) NOT NULL,
    -- The store locale the row describes (Apple's BCP-47 vocabulary,
    -- e.g. en-US or de-DE).
    locale varchar(32) NOT NULL,
    description text NOT NULL,
    -- Apple's own cap is 100 characters; the mock ASC enforces the
    -- provider contract and the column tolerates the authored maximum.
    keywords varchar(128),
    -- Screenshot references (asset ids or URIs the operator manages);
    -- the connector pins them in the manifest -- ASC-side assets are
    -- uploaded through ASC's own surfaces (the story's minimal field
    -- set, documented in docs/distribution.md).
    screenshot_refs jsonb NOT NULL DEFAULT '[]',
    -- The locale's what's-new (release notes) the submission sends.
    release_notes varchar(4000),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_channel_submission_metadata_channel CHECK (
        char_length(btrim(channel)) BETWEEN 1 AND 64),
    CONSTRAINT ck_channel_submission_metadata_locale CHECK (
        locale ~ '^[a-z]{2}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_channel_submission_metadata_description CHECK (
        char_length(btrim(description)) BETWEEN 1 AND 4000),
    CONSTRAINT ck_channel_submission_metadata_keywords CHECK (
        keywords IS NULL OR char_length(keywords) <= 128),
    CONSTRAINT ck_channel_submission_metadata_release_notes CHECK (
        release_notes IS NULL OR char_length(release_notes) <= 4000),
    -- One row per locale per channel per application: the authoring
    -- grain (a second write to the same locale rotates the row, never
    -- a duplicate submission source).
    CONSTRAINT uq_channel_submission_metadata_app_channel_locale
        UNIQUE (application_id, channel, locale)
);

CREATE INDEX idx_channel_submission_metadata_organization_id
    ON channel_submission_metadata (organization_id);
CREATE INDEX idx_channel_submission_metadata_application_id
    ON channel_submission_metadata (application_id);

-- ---------------------------------------------------------------------------
-- The actionable failure reason (AC4)
-- ---------------------------------------------------------------------------
ALTER TABLE channel_distributions ADD COLUMN failure_detail varchar(512);
