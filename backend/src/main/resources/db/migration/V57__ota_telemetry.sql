-- V57__ota_telemetry.sql
-- The OTA telemetry surface (issue #164, S16-02, the OTA updates
-- plan's workstream 1; ADR-0010 D9): the analytics_event_type
-- vocabulary gains the six OTA event types, the V38 CHECK shapes are
-- re-admitted WIDER so the new values insert cleanly, and the event
-- store + rollups gain the nullable OTA dimension columns.
--
--   * ALTER TYPE ... ADD VALUE x6 -- OTA_CHECK (server-side, D9: the
--     check outcome that knows a release), and the five device
--     lifecycle events the sdt1.-bound ingest carries (OTA_SEEN,
--     OTA_DOWNLOAD, OTA_APPLIED, OTA_FAILED, OTA_ROLLED_BACK).
--   * ck_analytics_events_result_shape / _session_shape -- the V38
--     closed disjunctions re-admitted wider (the V40 precedent). The
--     re-admitted predicates deliberately name ONLY the four
--     pre-existing enum values: a new enum value may not be USED by a
--     later statement in its own adding transaction, so the OTA
--     family is admitted as the explicit remainder -- "every type
--     except the pre-existing four" -- and the OTA result vocabulary
--     (OTA_DOWNLOAD/SUCCEEDED|FAILED, OTA_FAILED/checksum_mismatch|
--     apply_error|signature_invalid, ...) rides the OPEN row-value
--     doctrine (the V42 provider precedent: SDK-reported reasons are
--     row values, not schema migrations).
--   * analytics_events.ota_bundle_id / ota_payload -- the bundle
--     attribution (NOT NULL for OTA types, enforced by the new
--     ck_analytics_events_ota_shape below) and the free-form detail
--     object the OTA events carry (a jsonb object or NULL).
--   * metric_hourly / metric_daily .ota_bundle_id -- the nullable
--     bundle dimension on the rollups. The PRIMARY KEY (application,
--     metric, bucket, release, ring) is UNTOUCHED: the existing grain
--     is exactly preserved (pre-existing writers name their columns
--     and land NULL here; OTA metrics are distinct metric names, so
--     the bundle-carrying rows never collide with the legacy grain).
--     Partial bundle-window indexes serve the S16-10 dashboard reads.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): enum values
--     appended, two CHECK constraints re-admitted WIDER (never
--     weaker), nullable columns added, one in-place varchar length
--     widen (result 16 -> 32, no rewrite, no standing value touched);
--     nothing dropped or re-typed.
--   * The ADD VALUE + same-file constraint re-admission pattern is
--     the V48/V40 sequencing discipline: the added values are never
--     referenced by name in THIS file.
--
-- A note for future migrations: any analytics_event_type value added
-- after V57 lands in the open-remainder branch of these predicates by
-- construction -- if it needs a CLOSED result vocabulary or must not
-- carry the OTA columns, its own migration owns the next re-admission
-- (exactly what this migration does to V38's).

-- The six OTA values (nothing later in THIS file names them).
ALTER TYPE analytics_event_type ADD VALUE IF NOT EXISTS 'OTA_CHECK';
ALTER TYPE analytics_event_type ADD VALUE IF NOT EXISTS 'OTA_SEEN';
ALTER TYPE analytics_event_type ADD VALUE IF NOT EXISTS 'OTA_DOWNLOAD';
ALTER TYPE analytics_event_type ADD VALUE IF NOT EXISTS 'OTA_APPLIED';
ALTER TYPE analytics_event_type ADD VALUE IF NOT EXISTS 'OTA_FAILED';
ALTER TYPE analytics_event_type ADD VALUE IF NOT EXISTS 'OTA_ROLLED_BACK';

-- The bundle attribution and the free-form OTA detail object.
ALTER TABLE analytics_events ADD COLUMN ota_bundle_id uuid REFERENCES ota_bundles (id);
ALTER TABLE analytics_events ADD COLUMN ota_payload jsonb;

-- The OTA failure vocabulary needs the room: V38's result varchar(16)
-- holds INSTALL/TESTER_VERDICT words, but the plan's OTA reasons
-- (checksum_mismatch, signature_invalid) are 17 characters. A pure
-- length widen -- varchar(16) -> varchar(32), in place, no rewrite,
-- every standing value untouched (the widened ck below caps the OTA
-- remainder's result at the same 32).
ALTER TABLE analytics_events ALTER COLUMN result TYPE varchar(32);

-- ---------------------------------------------------------------------------
-- The V38 CHECK constraints, re-admitted wider (the V40 precedent)
-- ---------------------------------------------------------------------------

-- result_shape: the four pre-existing branches verbatim, plus the
-- open remainder -- the OTA family (and any future type) admits
-- result NULL or a non-empty trimmed value up to 32 characters.
ALTER TABLE analytics_events DROP CONSTRAINT ck_analytics_events_result_shape;
ALTER TABLE analytics_events ADD CONSTRAINT ck_analytics_events_result_shape CHECK (
    (event_type = 'INSTALL' AND result IN ('SUCCEEDED', 'FAILED'))
    OR (event_type = 'TESTER_VERDICT' AND result IN ('APPROVED', 'REJECTED'))
    OR (event_type IN ('SESSION', 'UNINSTALL') AND result IS NULL)
    OR (event_type NOT IN ('INSTALL', 'SESSION', 'UNINSTALL', 'TESTER_VERDICT')
        AND (result IS NULL OR char_length(btrim(result)) BETWEEN 1 AND 32)));

-- session_shape: the SESSION aggregate contract verbatim; the
-- non-SESSION branch re-admitted in closed-set form so the OTA family
-- is explicit (the OTA events carry no session aggregates -- the
-- crash/ANR funnel rides SESSION events, attributed to the bundle
-- through the release).
ALTER TABLE analytics_events DROP CONSTRAINT ck_analytics_events_session_shape;
ALTER TABLE analytics_events ADD CONSTRAINT ck_analytics_events_session_shape CHECK (
    (event_type = 'SESSION'
        AND sessions IS NOT NULL AND sessions > 0
        AND crash_sessions IS NOT NULL AND crash_sessions >= 0
        AND anr_sessions IS NOT NULL AND anr_sessions >= 0
        AND crash_sessions + anr_sessions <= sessions)
    OR (event_type <> 'SESSION'
        AND sessions IS NULL AND crash_sessions IS NULL AND anr_sessions IS NULL));

-- ota_shape (new): the OTA columns' own coherence, naming only the
-- pre-existing four -- the pre-existing family NEVER carries bundle
-- attribution (the NULL landings of every existing row validate
-- trivially), and the OTA remainder ALWAYS carries the bundle.
ALTER TABLE analytics_events ADD CONSTRAINT ck_analytics_events_ota_shape CHECK (
    (event_type IN ('INSTALL', 'SESSION', 'UNINSTALL', 'TESTER_VERDICT')
        AND ota_bundle_id IS NULL AND ota_payload IS NULL)
    OR (event_type NOT IN ('INSTALL', 'SESSION', 'UNINSTALL', 'TESTER_VERDICT')
        AND ota_bundle_id IS NOT NULL
        AND (ota_payload IS NULL OR jsonb_typeof(ota_payload) = 'object')));

-- The per-bundle funnel reads (the S16-09/S16-10 probes: a bundle's
-- events by type and time -- the idx_analytics_events_release shape,
-- one dimension over).
CREATE INDEX idx_analytics_events_ota_bundle
    ON analytics_events (ota_bundle_id, event_type, occurred_at);

COMMENT ON COLUMN analytics_events.ota_bundle_id IS
    'The OTA bundle attribution (S16-02): NOT NULL for the OTA event family (ck_analytics_events_ota_shape), NULL for every pre-existing type.';
COMMENT ON COLUMN analytics_events.ota_payload IS
    'The OTA event detail object (S16-02): a jsonb object or NULL -- failure reasons, rollout context; never secret material.';

-- ---------------------------------------------------------------------------
-- The rollups' nullable bundle dimension (grain untouched)
-- ---------------------------------------------------------------------------

ALTER TABLE metric_hourly ADD COLUMN ota_bundle_id uuid REFERENCES ota_bundles (id);
ALTER TABLE metric_daily ADD COLUMN ota_bundle_id uuid REFERENCES ota_bundles (id);

-- The bundle-dimension window reads (partial: the legacy rows carry
-- NULL and stay out of the index entirely).
CREATE INDEX idx_metric_hourly_ota_bundle_window
    ON metric_hourly (ota_bundle_id, metric, bucket_start)
    WHERE ota_bundle_id IS NOT NULL;
CREATE INDEX idx_metric_daily_ota_bundle_window
    ON metric_daily (ota_bundle_id, metric, bucket_start)
    WHERE ota_bundle_id IS NOT NULL;

COMMENT ON COLUMN metric_hourly.ota_bundle_id IS
    'The OTA bundle dimension (S16-02): nullable; the PRIMARY KEY grain (application, metric, bucket, release, ring) is unchanged -- OTA metrics are distinct metric names carrying the dimension, legacy rows land NULL.';
