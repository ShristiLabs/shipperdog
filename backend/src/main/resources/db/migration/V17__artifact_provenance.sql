-- V17__artifact_provenance.sql
-- Build-provenance capture for ShipperDog artifacts (issue #23, S5-04,
-- FR-005, PRD 8.5).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds two
--     nullable jsonb columns, nothing is dropped or narrowed.
--   * Constraint names use the explicit ck_ prefix.
--
-- What the story demands and this migration provides:
--
--   * artifacts.provenance (jsonb): the client-asserted build
--     provenance captured at upload time — git commit, git branch,
--     pipeline name, CI run URL, build timestamp — written ONCE with
--     the registration insert and never updated (INV-003: the entity
--     has no setters and the column has no writer after insert). The
--     document keys (gitCommit, gitBranch, pipeline, ciRunUrl,
--     buildTimestamp) are the FROZEN M2 CI-integration contract the
--     S12-03/S12-04 integrations consume; only supplied members are
--     stored — an absent claim is an absent key, never a fabricated
--     value (the story's second criterion).
--
--   * Why a dedicated column rather than a namespaced key inside
--     extracted_metadata (V16): that document is written ONCE by the
--     async extraction job over a write-once guard; an upload-time
--     provenance document inside it would make the extraction write
--     impossible. Provenance is upload-time input, extraction is a
--     server-derived outcome — two writers, two columns.
--
--   * artifact_upload_sessions.provenance (jsonb): the resumable path
--     captures provenance at session creation (the upload-time moment
--     of that surface); finalize replays it into the registered row,
--     so a session registered hours later still carries the claims
--     its creating client asserted.
--
--   * Trust model (documented in docs/artifacts.md): the values are
--     SHAPE-VALIDATED CLAIMS of the uploading client — hex commit,
--     http(s) URL, ISO-8601 instant — never verified facts in M1.
--     No attestation exists yet; accountability anchors on the
--     authenticated uploader (uploaded_by) and the audit trail.
--
--   * Nullable by design (AC2): manual uploads may omit provenance
--     entirely; NULL is the honest representation of "not asserted".

-- ---------------------------------------------------------------------------
-- Artifacts: the provenance document
-- ---------------------------------------------------------------------------

ALTER TABLE artifacts ADD COLUMN provenance jsonb;

-- A stored provenance is always a JSON object (or absent); the key
-- freeze itself is enforced by the single writer that validates the
-- five names in code before serializing.
ALTER TABLE artifacts ADD CONSTRAINT ck_artifacts_provenance_object
    CHECK (provenance IS NULL OR jsonb_typeof(provenance) = 'object');

-- ---------------------------------------------------------------------------
-- Upload sessions: carrying the resumable path's captured provenance
-- ---------------------------------------------------------------------------

ALTER TABLE artifact_upload_sessions ADD COLUMN provenance jsonb;

ALTER TABLE artifact_upload_sessions ADD CONSTRAINT ck_artifact_upload_sessions_provenance_object
    CHECK (provenance IS NULL OR jsonb_typeof(provenance) = 'object');
