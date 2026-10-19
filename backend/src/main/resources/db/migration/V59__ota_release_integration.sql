-- V59__ota_release_integration.sql
-- S16-05 (issue #167), ADR-0010 workstream 3: the OTA release linkage's
-- schema floor. Two additions, both additive:
--
--   * release_targets.forced / min_app_version -- ADR-0010 D7: the
--     per-bundle defaults are OVERRIDABLE per release target at release
--     time. NULL (the default) means "the bundle's own value applies" --
--     the tri-state is the point: an override of false is a real fact,
--     distinct from no override at all. The check manifest (S16-07)
--     resolves override ?? bundle default at serve time.
--   * uq_ota_bundles_release -- exactly one bundle per OTA release, at
--     the schema floor: the release-side twin of V55's
--     uq_ota_bundles_version (a bundle owns its versions row
--     exclusively; a release owns its bundle exclusively). Partial:
--     release_id is NULL until the bundle publishes and after a
--     rollback returns it, so only live RELEASED links are unique.
--
-- Conventions (see docs/database-migrations.md): expand-only,
-- forward-only (ADR-0001); every column nullable or defaulted; no
-- destructive change.

ALTER TABLE release_targets
    ADD COLUMN forced boolean NULL,
    ADD COLUMN min_app_version varchar(100) NULL;

COMMENT ON COLUMN release_targets.forced IS
    'ADR-0010 D7 per-target override of the bundle''s forced default; NULL = the bundle''s value applies (S16-05)';
COMMENT ON COLUMN release_targets.min_app_version IS
    'ADR-0010 D7 per-target override of the bundle''s min_app_version default; NULL = the bundle''s value applies (S16-05)';

CREATE UNIQUE INDEX uq_ota_bundles_release
    ON ota_bundles (release_id)
    WHERE release_id IS NOT NULL;

COMMENT ON INDEX uq_ota_bundles_release IS
    'Exactly one bundle per OTA release (S16-05): only live RELEASED links are unique; a rollback returns the bundle to the pool';
