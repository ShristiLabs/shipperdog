-- V15__artifact_upload.sql
-- Artifact upload and object storage schema for ShipperDog (issue #20,
-- S5-01, FR-004/FR-007, PRD 24, INV-003).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds columns,
--     checks, indexes, and one table; nothing is dropped. The single
--     constraint relaxation (build_number DROP NOT NULL) widens what a
--     row may carry — no data is lost, no unique guarantee is weakened
--     for rows that keep the value (see the parked-constraint note).
--   * Constraint names use the explicit ck_/uq_/idx_ prefixes.
--
-- What the story demands and this migration provides:
--
--   * The owning-dimension snapshot (organization_id NOT NULL, tenant_id
--     nullable): artifacts join the tenant-isolation seam (ADR-0002)
--     exactly the way versions do (V12). The eighth amendment made
--     plain children of seam-served parents the standing default, and
--     its own rule says a child that breaks the one-parent invariant
--     needs a new amendment: artifacts break it twice — the artifact
--     collection route addresses them directly (POST /api/v1/artifacts,
--     FR-004, PRD 19), and the same-content detection of issue #20's
--     fourth acceptance criterion spans every version of the
--     application. ADR-0002's ninth amendment records the split:
--     addressable, cross-parent children carry the snapshot and join
--     the seam; strictly single-parent children (version_tags,
--     version_status_history) stay plain.
--   * build_number becomes nullable: the build number is EXTRACTED
--     metadata (FR-005, S5-02/03) — at upload time (S5-01) it is known
--     only when the client pins it for CI builds. PostgreSQL UNIQUE
--     semantics treat NULLs as distinct, so V1's
--     uq_artifacts_version_platform_build holds exactly the intended
--     meaning: a pinned (version, platform, build) triple can land
--     once; unpinned rows re-upload freely as NEW artifacts — which is
--     issue #20's third acceptance criterion ("re-upload of same file
--     creates a NEW artifact", INV-003's no-replace corollary).
--   * The parked narrower UNIQUE (version_id, platform) from the #19
--     follow-ups is REJECTED, deliberately: one artifact per
--     version+platform would make every re-upload a 409 and contradict
--     the story's own re-upload criterion. V1's composite stays the
--     authority; the version view's per-platform slot is a list (V12's
--     forward-compatible shape), and E7/E9 pick the artifact per
--     platform when distribution needs exactly one.
--   * artifact_upload_sessions: the short-lived direct-to-storage
--     handoff for large binaries (issue #20 AC5, the >100 MB rule).
--     A session row is created after the same guards the multipart
--     path enforces, carries the pre-allocated artifact id and the
--     GCS resumable-session URL the client PUTs bytes to, and is
--     deleted at finalize — the durable artifact row replaces it. The
--     rows carry the owning-dimension snapshot and are served by the
--     seam: ephemeral is not exempt from INV-001. created_by is a
--     plain uuid with no foreign key (the idempotency_keys precedent:
--     transient rows do not wedge the user lifecycle).
--   * No binaries anywhere in PostgreSQL (FR-007): the table stores
--     metadata plus the storage_key; bytes live in object storage
--     under the PRD 24 layout
--     /{organizationId}/{tenantId}/{applicationId}/{versionId}/{artifactId}/artifact
--     (organization-shared rows use the _shared placeholder in the
--     tenant slot — the layout is addressing, never authorization,
--     PRD 24's own note).

-- ---------------------------------------------------------------------------
-- Artifacts: the owning-dimension snapshot (seam columns, V12 shape)
-- ---------------------------------------------------------------------------

-- The owning organization of the application the version (and through
-- it, the artifact) belongs to; never null once backfilled.
ALTER TABLE artifacts ADD COLUMN organization_id uuid REFERENCES organizations (id);

-- The owning tenant of the application, or NULL when the version hangs
-- under an organization-shared application (PRD 14): the seam's
-- organization-scope predicate serves exactly the NULL-tenant rows of
-- one organization.
ALTER TABLE artifacts ADD COLUMN tenant_id uuid REFERENCES tenants (id);

-- Backfill from each artifact's version (a no-op on the empty greenfield
-- table but correct on any state; the version carries the snapshot since
-- V12).
UPDATE artifacts a
SET organization_id = v.organization_id,
    tenant_id = v.tenant_id
FROM versions v
WHERE a.version_id = v.id;

ALTER TABLE artifacts ALTER COLUMN organization_id SET NOT NULL;

-- ---------------------------------------------------------------------------
-- Artifacts: build number becomes extracted-later metadata
-- ---------------------------------------------------------------------------

-- FR-005 makes the build number extracted metadata (S5-02/03); the
-- upload API pins it only when the client knows it. Non-negative when
-- present; NULLs stay distinct in uq_artifacts_version_platform_build,
-- which is exactly the re-upload-means-new-artifact rule.
ALTER TABLE artifacts ALTER COLUMN build_number DROP NOT NULL;

ALTER TABLE artifacts ADD CONSTRAINT ck_artifacts_build_number_nonnegative
    CHECK (build_number IS NULL OR build_number >= 0);

-- ---------------------------------------------------------------------------
-- Artifacts: seam predicates and the dedup lookup
-- ---------------------------------------------------------------------------

CREATE INDEX idx_artifacts_tenant_id ON artifacts (tenant_id);

CREATE INDEX idx_artifacts_organization_scope
    ON artifacts (organization_id)
    WHERE tenant_id IS NULL;

-- The same-content detection (issue #20 AC4) probes by sha256 within the
-- bound scope; informational, never a rejection.
CREATE INDEX idx_artifacts_sha256 ON artifacts (sha256);

-- ---------------------------------------------------------------------------
-- Artifact upload sessions (the >100 MB direct-to-storage path)
-- ---------------------------------------------------------------------------

CREATE TABLE artifact_upload_sessions (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    tenant_id     uuid REFERENCES tenants (id),
    version_id    uuid NOT NULL REFERENCES versions (id),
    artifact_id   uuid NOT NULL,
    platform      platform NOT NULL,
    file_name     varchar(255) NOT NULL,
    build_number  bigint,
    storage_key   varchar(1024) NOT NULL,
    session_url   varchar(2048) NOT NULL,
    expires_at    timestamptz NOT NULL,
    created_by    uuid,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_artifact_upload_sessions_artifact UNIQUE (artifact_id),
    CONSTRAINT uq_artifact_upload_sessions_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_artifact_upload_sessions_build_number_nonnegative
        CHECK (build_number IS NULL OR build_number >= 0)
);

-- The pre-allocated artifact id becomes artifacts.id at finalize, and the
-- storage key is the one the bytes already landed under.
CREATE INDEX idx_artifact_upload_sessions_tenant_id
    ON artifact_upload_sessions (tenant_id);

CREATE INDEX idx_artifact_upload_sessions_organization_scope
    ON artifact_upload_sessions (organization_id)
    WHERE tenant_id IS NULL;

-- Session expiry probes (a future sweeper's scan; finalize checks expiry
-- per row today).
CREATE INDEX idx_artifact_upload_sessions_expires_at
    ON artifact_upload_sessions (expires_at);
