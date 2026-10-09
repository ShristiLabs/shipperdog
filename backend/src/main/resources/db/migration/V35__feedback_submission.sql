-- V35__feedback_submission.sql
-- Structured tester feedback: the submission-time context columns and
-- the attachment store (issue #46, S11-01, FR-027, PRD 8.13).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): the V1 feedback
--     table is EXPANDED, never rebuilt -- its feedback_type enum
--     already carries all six PRD 8.13 values and its OPEN/RESOLVED/
--     DISMISSED status vocabulary stays untouched (triage transitions
--     are S11-02; submissions are born OPEN and immutable here).
--     Nothing is dropped and no enum value is renamed.
--
--   * feedback (expanded): the context the API attaches SERVER-side
--     (AC2 -- the client never supplies identity fields): the owning
--     organization and the denormalized application/version beside
--     V1's release/artifact/tester/device references, plus the
--     SUBMITTING tenant -- the release target the report came from.
--     V1 had no tenant dimension on feedback and the release row's
--     owning-application snapshot is a different tenant's; the two
--     columns coexist deliberately. device_model/os are REPORTED
--     free text (device registrations are S11-03; the device_id FK
--     stays null until then). The table is empty in every environment
--     (no code has written it), so the new columns arrive nullable
--     and are tightened to NOT NULL in the same migration -- the V24
--     add-then-tighten shape with a zero-row backfill.
--
--   * feedback_attachments (new): one row per uploaded file, the V25
--     child doctrine -- a plain child of the feedback row. The bytes
--     live in object storage under the tenant-scoped prefix
--     {organizationId}/{submitterTenantId}/feedback/{feedbackId}/
--     {attachmentId} (AC3); the row carries the metadata: the
--     category the extension resolved to (SCREENSHOT/VIDEO/LOG), the
--     byte size, the SHA-256 computed while streaming the write, and
--     the server-derived storage key -- never a client path.

-- ---------------------------------------------------------------------------
-- feedback: the submission-time context snapshot
-- ---------------------------------------------------------------------------

ALTER TABLE feedback ADD COLUMN organization_id uuid REFERENCES organizations (id);
ALTER TABLE feedback ADD COLUMN submitter_tenant_id uuid REFERENCES tenants (id);
ALTER TABLE feedback ADD COLUMN application_id uuid REFERENCES applications (id);
ALTER TABLE feedback ADD COLUMN version_id uuid REFERENCES versions (id);
-- Reported device context (PRD 8.13's Device/OS attributes as free
-- text until S11-03 links registered device rows).
ALTER TABLE feedback ADD COLUMN device_model varchar(256);
ALTER TABLE feedback ADD COLUMN os varchar(128);

-- The zero-row backfill (the table has never been written), then the
-- tighten: every report carries its full server-attached context.
ALTER TABLE feedback ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE feedback ALTER COLUMN submitter_tenant_id SET NOT NULL;
ALTER TABLE feedback ALTER COLUMN application_id SET NOT NULL;
ALTER TABLE feedback ALTER COLUMN version_id SET NOT NULL;

-- Named foreign keys for the added references (the V1 baseline
-- predates the naming rule; new columns follow it).
ALTER TABLE feedback
    ADD CONSTRAINT fk_feedback_organization_id
        FOREIGN KEY (organization_id) REFERENCES organizations (id);
ALTER TABLE feedback
    ADD CONSTRAINT fk_feedback_submitter_tenant_id
        FOREIGN KEY (submitter_tenant_id) REFERENCES tenants (id);
ALTER TABLE feedback
    ADD CONSTRAINT fk_feedback_application_id
        FOREIGN KEY (application_id) REFERENCES applications (id);
ALTER TABLE feedback
    ADD CONSTRAINT fk_feedback_version_id
        FOREIGN KEY (version_id) REFERENCES versions (id);

-- The tenant dimension is indexed (the tenant filter of the listing
-- and the tenant-scoped object prefix both key on it); the owning
-- organization joins the same convention every tenant-scoped table
-- follows.
CREATE INDEX idx_feedback_organization_id ON feedback (organization_id);
CREATE INDEX idx_feedback_submitter_tenant_id ON feedback (submitter_tenant_id);

-- ---------------------------------------------------------------------------
-- feedback_attachments: the attachment metadata (bytes in object storage)
-- ---------------------------------------------------------------------------

CREATE TABLE feedback_attachments (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    feedback_id   uuid NOT NULL REFERENCES feedback (id),
    file_name     varchar(256) NOT NULL,
    category      varchar(16) NOT NULL,
    content_type  varchar(128) NOT NULL,
    size_bytes    bigint NOT NULL,
    checksum      char(64) NOT NULL,
    storage_key   varchar(512) NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_feedback_attachments_feedback_file UNIQUE (feedback_id, file_name),
    CONSTRAINT ck_feedback_attachments_category CHECK (category IN ('SCREENSHOT', 'VIDEO', 'LOG')),
    CONSTRAINT ck_feedback_attachments_size CHECK (size_bytes > 0),
    CONSTRAINT fk_feedback_attachments_feedback_id
        FOREIGN KEY (feedback_id) REFERENCES feedback (id)
);

CREATE INDEX idx_feedback_attachments_feedback_id ON feedback_attachments (feedback_id);
