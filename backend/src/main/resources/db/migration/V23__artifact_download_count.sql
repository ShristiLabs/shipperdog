-- V23__artifact_download_count.sql
-- Per-artifact download counter (issue #38, S8-04, FR-014, the
-- story's technical note: "count downloads now — simple increment +
-- audit event"; S11-04 formalizes aggregation).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one NOT NULL
--     column with a default is added to artifacts; nothing is
--     dropped, no constraint is tightened, existing rows answer 0
--     (nobody downloaded anything before download issuance existed).
--
-- What the story demands and this migration provides:
--
--   * artifacts.download_count (bigint NOT NULL, default 0): one
--     increment per ALLOWED download issuance, written only by the
--     entity's guarded recordDownloadIssued() — the aggregate the
--     S11-04 analytics story will read beside the DOWNLOAD_ISSUED
--     audit trail and the shipperdog.download.count metric. Not a
--     binary field: INV-003 freezes storage_key/sha256/size and the
--     status composes through the PRD 18 state machine; a counter is
--     neither, and the audit trail (not this column) remains the
--     history of record.

ALTER TABLE artifacts
    ADD COLUMN download_count BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN artifacts.download_count IS
    'per-artifact download issuance counter (S8-04); incremented once per ALLOWED issuance, aggregate read by S11-04 analytics';
