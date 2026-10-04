-- V1__baseline_schema.sql
-- Baseline relational schema for ShipperDog (issue #2, S1-02).
--
-- Conventions (see docs/database-migrations.md):
--   * Flyway is the sole owner of schema; JPA ddl-auto is validate.
--   * Primary keys: uuid DEFAULT gen_random_uuid(); pure junction tables
--     use composite primary keys.
--   * Audit columns: created_at/updated_at timestamptz NOT NULL DEFAULT
--     now(); maintained by the application layer (no DB triggers).
--   * Status/type vocabularies: PostgreSQL enum types; values evolve via
--     ALTER TYPE ... ADD VALUE in a NEW migration, never removed.
--   * Foreign keys: NO ACTION on delete (PostgreSQL default for bare
--     REFERENCES); no ON DELETE cascades by design.
--   * Tenant-scoped tables carry an index on organization_id/tenant_id.
--   * user_id/uploaded_by/created_by/approver are forward references to
--     the identity user table (S1-03); FK constraints land with that story.

-- ---------------------------------------------------------------------------
-- Enum types
-- ---------------------------------------------------------------------------

CREATE TYPE org_status AS ENUM ('ACTIVE', 'DISABLED');
CREATE TYPE tenant_status AS ENUM ('ACTIVE', 'DISABLED');
CREATE TYPE application_status AS ENUM ('ACTIVE', 'DISABLED', 'ARCHIVED');
CREATE TYPE version_status AS ENUM ('DRAFT', 'ACTIVE', 'ARCHIVED');
CREATE TYPE artifact_status AS ENUM (
    'UPLOADING', 'VALIDATING', 'READY', 'DISTRIBUTED', 'ARCHIVED', 'FAILED');
CREATE TYPE platform AS ENUM ('IOS', 'ANDROID');
CREATE TYPE tester_status AS ENUM ('ACTIVE', 'DISABLED');
CREATE TYPE membership_status AS ENUM ('ACTIVE', 'REVOKED');
CREATE TYPE access_type AS ENUM ('DIRECT', 'GROUP');
CREATE TYPE release_status AS ENUM (
    'DRAFT', 'VALIDATING', 'READY', 'APPROVAL_PENDING', 'APPROVED',
    'DEPLOYING', 'ACTIVE', 'PROMOTED', 'ROLLED_BACK', 'COMPLETED', 'FAILED');
CREATE TYPE approval_type AS ENUM ('QA', 'SECURITY', 'PRODUCT', 'MANUAL');
CREATE TYPE approval_status AS ENUM ('PENDING', 'APPROVED', 'REJECTED');
CREATE TYPE distribution_channel AS ENUM ('PORTAL', 'EMAIL');
CREATE TYPE distribution_status AS ENUM (
    'PENDING', 'DISTRIBUTING', 'DISTRIBUTED', 'FAILED');
CREATE TYPE environment_status AS ENUM ('ACTIVE', 'DISABLED');
CREATE TYPE feedback_type AS ENUM (
    'BUG', 'FEEDBACK', 'CRASH', 'FEATURE_REQUEST', 'UX_ISSUE', 'SECURITY_ISSUE');
CREATE TYPE feedback_status AS ENUM ('OPEN', 'RESOLVED', 'DISMISSED');
CREATE TYPE gate_status AS ENUM ('PENDING', 'PASSED', 'FAILED');

-- ---------------------------------------------------------------------------
-- organizations
-- ---------------------------------------------------------------------------

CREATE TABLE organizations (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       varchar(200) NOT NULL,
    status     org_status NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- tenants
-- ---------------------------------------------------------------------------

CREATE TABLE tenants (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    name            varchar(200) NOT NULL,
    external_id     varchar(100) NOT NULL,
    status          tenant_status NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenants_external_id UNIQUE (external_id),
    CONSTRAINT uq_tenants_org_name UNIQUE (organization_id, name)
);

CREATE INDEX idx_tenants_organization_id ON tenants (organization_id);

-- ---------------------------------------------------------------------------
-- applications
-- ---------------------------------------------------------------------------

-- tenant_id is NULL for organization-owned applications; the explicit
-- ownership_scope discriminator is deliberately NOT introduced here and
-- lands with S3-02 as its own migration.
CREATE TABLE applications (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    tenant_id      uuid REFERENCES tenants (id),
    name           varchar(200) NOT NULL,
    bundle_id      varchar(255),
    package_name   varchar(255),
    status         application_status NOT NULL DEFAULT 'ACTIVE',
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_applications_organization_id ON applications (organization_id);
CREATE INDEX idx_applications_tenant_id ON applications (tenant_id);
-- Unique name per owning scope; COALESCE folds org-owned (NULL tenant) rows
-- into one namespace so uniqueness holds for both ownership modes.
CREATE UNIQUE INDEX uq_applications_scope_name
    ON applications (organization_id,
                     COALESCE(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
                     name);

-- ---------------------------------------------------------------------------
-- versions
-- ---------------------------------------------------------------------------

CREATE TABLE versions (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id uuid NOT NULL REFERENCES applications (id),
    version_name  varchar(100) NOT NULL,
    status        version_status NOT NULL DEFAULT 'DRAFT',
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_versions_application_version UNIQUE (application_id, version_name)
);

-- application_id lookups are served by the prefix of
-- uq_versions_application_version; no separate index needed.

-- ---------------------------------------------------------------------------
-- artifacts
-- ---------------------------------------------------------------------------

CREATE TABLE artifacts (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id   uuid NOT NULL REFERENCES versions (id),
    platform     platform NOT NULL,
    file_name    varchar(255) NOT NULL,
    storage_key  varchar(1024) NOT NULL,
    sha256       char(64) NOT NULL,
    size         bigint NOT NULL CHECK (size >= 0),
    build_number bigint NOT NULL,
    status       artifact_status NOT NULL DEFAULT 'UPLOADING',
    uploaded_by  uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_artifacts_storage_key UNIQUE (storage_key),
    CONSTRAINT uq_artifacts_version_platform_build
        UNIQUE (version_id, platform, build_number)
);

CREATE INDEX idx_artifacts_version_id ON artifacts (version_id);

-- ---------------------------------------------------------------------------
-- testers
-- ---------------------------------------------------------------------------

CREATE TABLE testers (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL,
    status     tester_status NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_testers_user UNIQUE (user_id)
);

-- ---------------------------------------------------------------------------
-- tenant_tester_memberships (junction)
-- ---------------------------------------------------------------------------

CREATE TABLE tenant_tester_memberships (
    tenant_id  uuid NOT NULL REFERENCES tenants (id),
    tester_id  uuid NOT NULL REFERENCES testers (id),
    status     membership_status NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, tester_id)
);

CREATE INDEX idx_ttm_tester_id ON tenant_tester_memberships (tester_id);

-- ---------------------------------------------------------------------------
-- tester_groups
-- ---------------------------------------------------------------------------

CREATE TABLE tester_groups (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid NOT NULL REFERENCES tenants (id),
    name       varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tester_groups_tenant_name UNIQUE (tenant_id, name)
);

-- tenant_id lookups are served by the prefix of uq_tester_groups_tenant_name.

-- ---------------------------------------------------------------------------
-- tester_group_members (junction)
-- ---------------------------------------------------------------------------

CREATE TABLE tester_group_members (
    group_id   uuid NOT NULL REFERENCES tester_groups (id),
    tester_id  uuid NOT NULL REFERENCES testers (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, tester_id)
);

CREATE INDEX idx_tgm_tester_id ON tester_group_members (tester_id);

-- ---------------------------------------------------------------------------
-- version_access (junction)
-- ---------------------------------------------------------------------------

CREATE TABLE version_access (
    version_id  uuid NOT NULL REFERENCES versions (id),
    tester_id   uuid NOT NULL REFERENCES testers (id),
    access_type access_type NOT NULL,
    expires_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (version_id, tester_id)
);

CREATE INDEX idx_version_access_tester_id ON version_access (tester_id);

-- ---------------------------------------------------------------------------
-- releases
-- ---------------------------------------------------------------------------

CREATE TABLE releases (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id uuid NOT NULL REFERENCES applications (id),
    version_id     uuid NOT NULL REFERENCES versions (id),
    status         release_status NOT NULL DEFAULT 'DRAFT',
    created_by     uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_releases_application_version UNIQUE (application_id, version_id)
);

-- application_id/version_id lookups are served by the unique constraint.

-- ---------------------------------------------------------------------------
-- release_targets (junction)
-- ---------------------------------------------------------------------------

CREATE TABLE release_targets (
    release_id uuid NOT NULL REFERENCES releases (id),
    tenant_id  uuid NOT NULL REFERENCES tenants (id),
    ring_id    varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (release_id, tenant_id)
);

CREATE INDEX idx_release_targets_tenant_id ON release_targets (tenant_id);

-- ---------------------------------------------------------------------------
-- release_approvals (junction)
-- ---------------------------------------------------------------------------

-- "decided_at" maps the PRD §13 "timestamp" attribute.
CREATE TABLE release_approvals (
    release_id     uuid NOT NULL REFERENCES releases (id),
    approval_type  approval_type NOT NULL,
    approver       uuid,
    status         approval_status NOT NULL DEFAULT 'PENDING',
    decided_at     timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (release_id, approval_type)
);

-- ---------------------------------------------------------------------------
-- distribution (junction)
-- ---------------------------------------------------------------------------

CREATE TABLE distribution (
    release_id  uuid NOT NULL REFERENCES releases (id),
    artifact_id uuid NOT NULL REFERENCES artifacts (id),
    channel     distribution_channel NOT NULL,
    status      distribution_status NOT NULL DEFAULT 'PENDING',
    external_id varchar(255),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (release_id, artifact_id, channel)
);

CREATE INDEX idx_distribution_artifact_id ON distribution (artifact_id);

-- ---------------------------------------------------------------------------
-- environments
-- ---------------------------------------------------------------------------

CREATE TABLE environments (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    name            varchar(100) NOT NULL,
    status          environment_status NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_environments_org_name UNIQUE (organization_id, name)
);

CREATE INDEX idx_environments_organization_id ON environments (organization_id);

-- ---------------------------------------------------------------------------
-- environment_config (junction)
-- ---------------------------------------------------------------------------

CREATE TABLE environment_config (
    environment_id uuid NOT NULL REFERENCES environments (id),
    key            varchar(100) NOT NULL,
    value          text NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (environment_id, key)
);

-- ---------------------------------------------------------------------------
-- devices
-- ---------------------------------------------------------------------------

CREATE TABLE devices (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tester_id  uuid NOT NULL REFERENCES testers (id),
    tenant_id  uuid NOT NULL REFERENCES tenants (id),
    platform   platform NOT NULL,
    model      varchar(255),
    os_version varchar(64),
    identifier varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_devices_tenant_identifier UNIQUE (tenant_id, identifier)
);

CREATE INDEX idx_devices_tester_id ON devices (tester_id);

-- ---------------------------------------------------------------------------
-- feedback
-- ---------------------------------------------------------------------------

CREATE TABLE feedback (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id   uuid NOT NULL REFERENCES releases (id),
    artifact_id  uuid REFERENCES artifacts (id),
    tester_id    uuid NOT NULL REFERENCES testers (id),
    device_id    uuid REFERENCES devices (id),
    type         feedback_type NOT NULL,
    description  text NOT NULL,
    status       feedback_status NOT NULL DEFAULT 'OPEN',
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_feedback_release_id ON feedback (release_id);
CREATE INDEX idx_feedback_tester_id ON feedback (tester_id);

-- ---------------------------------------------------------------------------
-- release_gates
-- ---------------------------------------------------------------------------

-- gate_type is deliberately an open vocabulary (integration-defined gate
-- kinds), so it is varchar; only status columns are enum-typed.
CREATE TABLE release_gates (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    release_id   uuid NOT NULL REFERENCES releases (id),
    gate_type    varchar(64) NOT NULL,
    status       gate_status NOT NULL DEFAULT 'PENDING',
    evidence_url varchar(1024),
    evaluated_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_release_gates_release_type UNIQUE (release_id, gate_type)
);
