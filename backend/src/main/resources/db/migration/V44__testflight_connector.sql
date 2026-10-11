-- V44__testflight_connector.sql
-- The Apple TestFlight connector's schema (issue #62, S13-02, FR-020,
-- PRD 19/25): one new table and two additive columns.
--
--   * channel_group_links -- the tester-group <-> provider-group
--     mapping (AC2): one row per (channel, tester group), carrying
--     the provider-side group id the link's sync resolved (an ASC
--     beta group id) and the last sync's moment. Channel-generic by
--     design: Google Play's tester lists (#64) link through the same
--     grain without a second table. The row is a MAPPING, never a
--     membership mirror: provider-side membership is reconciled, not
--     stored (AC5's drift answer).
--   * channel_distributions.provider_status / provider_status_at --
--     the provider's own lifecycle word for a distributed artifact
--     (AC4: Apple's beta-review states), recorded verbatim beside the
--     record's own status vocabulary with the moment it was observed.
--     Null until a store connector's refresh observes one -- Direct
--     answers none (no provider to poll).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one new table
--     with its index, and nullable columns ADDED to an existing table
--     (no drop, rename, or re-type); nothing existing narrows.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.

-- ---------------------------------------------------------------------------
-- channel_group_links: the group mapping (AC2)
-- ---------------------------------------------------------------------------
CREATE TABLE channel_group_links (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- The tester group's owning tenant (tester_groups is tenant-scoped,
    -- S6-02); the link addresses the group through it.
    tenant_id uuid NOT NULL REFERENCES tenants (id),
    -- The application whose ASC app the link binds against (the sync
    -- resolves the credential chain AT this scope -- an app-level
    -- apple binding must serve the sync exactly as it serves the
    -- dispatch, PRD 25).
    application_id uuid NOT NULL REFERENCES applications (id),
    group_id uuid NOT NULL REFERENCES tester_groups (id),
    -- The channel reference name (the registry's vocabulary): today
    -- 'testflight'; #64's Play adapter links through the same grain.
    channel varchar(64) NOT NULL,
    -- The provider-side group id (an ASC beta group id). Set at link
    -- time (existing group named or created on demand) and stable for
    -- the link's life: a provider group is never re-pointed, a new
    -- mapping is a new row.
    external_group_id varchar(255) NOT NULL,
    external_group_name varchar(255) NOT NULL,
    last_synced_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_channel_group_links_channel CHECK (char_length(btrim(channel)) BETWEEN 1 AND 64),
    CONSTRAINT ck_channel_group_links_external_group_id CHECK (
        external_group_id ~ '^[[:print:]]+$' AND external_group_id !~ '[[:space:]]'),
    CONSTRAINT ck_channel_group_links_external_group_name CHECK (
        char_length(btrim(external_group_name)) BETWEEN 1 AND 255),
    -- One link per group per channel: the mapping grain (create under
    -- a second link is a 409, never a silent second provider group).
    CONSTRAINT uq_channel_group_links_channel_group UNIQUE (channel, group_id)
);

CREATE INDEX idx_channel_group_links_organization_id ON channel_group_links (organization_id);
CREATE INDEX idx_channel_group_links_group_id ON channel_group_links (group_id);

-- ---------------------------------------------------------------------------
-- The provider lifecycle observation columns (AC4)
-- ---------------------------------------------------------------------------
ALTER TABLE channel_distributions ADD COLUMN provider_status varchar(128);
ALTER TABLE channel_distributions ADD COLUMN provider_status_at timestamptz;
