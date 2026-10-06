-- V18__artifact_validation.sql
-- Validation pipeline columns for ShipperDog artifacts (issue #24,
-- S5-05, FR-006, PRD 18/42, INV-003).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): two nullable
--     columns are added to artifacts; nothing is dropped, no constraint
--     is tightened, existing rows keep their meaning untouched.
--   * Constraint names use the explicit ck_ prefix.
--
-- What the story demands and this migration provides:
--
--   * artifacts.format (varchar 8): the STRUCTURAL container verdict
--     the pipeline's FORMAT stage records — IPA on iOS, APK or AAB on
--     Android (the S5-03 detection, never the file name's claim).
--     First-class because FR-006's channel rule is a distribution-time
--     enforcement over a recorded fact: an AAB upload stays valid
--     (Play-bound) while E8's direct-download channel refuses AAB by
--     reading this column. Write-once in code (the binary is
--     immutable, so the verdict cannot change); NULL until the first
--     validation attempt records it.
--
--   * artifacts.validation (jsonb): the per-stage record FR-006
--     demands — the LATEST attempt's outcome (PASSED|FAILED|BLOCKED),
--     the attempts counter (revalidations are re-runnable and
--     idempotent), the structural format the attempt observed, and
--     every stage's status/reason in pipeline order. Rewritten per
--     attempt (unlike the write-once extraction documents): the row
--     carries current state, the audit trail carries history. NULL
--     until the first attempt.
--
--   * The artifact_status enum needs NO change: PRD 18's vocabulary
--     (UPLOADING, VALIDATING, READY, DISTRIBUTED, ARCHIVED, FAILED)
--     is exactly the V1 baseline enum — this story formalizes the
--     TRANSITIONS in code (ArtifactStateMachine), not the vocabulary.
--     PRD 18 has no REVOKED state; revocation is E7's authorization
--     lifecycle (INV-007), never an artifact-row state.
--
--   * INV-003 stays structural: a validation transition touches only
--     status, format, and the validation document — never storage_key,
--     sha256, size, provenance, or the extraction documents.

-- ---------------------------------------------------------------------------
-- Artifacts: the channel-format fact and the validation document
-- ---------------------------------------------------------------------------

ALTER TABLE artifacts ADD COLUMN format varchar(8);

ALTER TABLE artifacts ADD CONSTRAINT ck_artifacts_format_vocabulary
    CHECK (format IS NULL OR format IN ('IPA', 'APK', 'AAB'));

ALTER TABLE artifacts ADD COLUMN validation jsonb;

ALTER TABLE artifacts ADD CONSTRAINT ck_artifacts_validation_object
    CHECK (validation IS NULL OR jsonb_typeof(validation) = 'object');
