-- V43__distribution_adapter_framework.sql
-- The distribution adapter framework's schema (issue #61, S13-01,
-- FR-019, PRD 8.7/13/19/25): three tables.
--
--   * store_accounts -- the credential-delegation chain's rows (PRD
--     25): store accounts configured at the organization level,
--     inherited downward, overridable at the application and
--     environment levels. The row is a REFERENCE HOLDER ONLY: id,
--     provider, name, metadata, secret_reference -- no column can
--     hold secret material because no writer accepts any (the V27
--     environment_credentials doctrine, now organization-wide). The
--     schema test of AC3 pins the column set exactly.
--   * channel_distributions -- the adapter-driven distribution
--     records (PRD 8.7/13): one row per release x artifact x channel,
--     transitioning per the adapter's reports, carrying the external
--     id stores reconcile by (AC4). The V1 `distribution` table
--     (enum-typed channel of the PORTAL/EMAIL sketch) stays
--     reserved-unused, the environment_config precedent: the channel
--     vocabulary here is the varchar(64) reference name V27/V32
--     established, and the lifecycle reuses V1's own
--     distribution_status vocabulary -- PENDING, DISTRIBUTING,
--     DISTRIBUTED, FAILED -- as the column's type.
--   * managed_secrets -- the OSS envelope-encryption store's
--     ciphertext (ADR-0008): wrapped data key + AEAD ciphertext ONLY,
--     keyed by the opaque reference. Production posture is GCP Secret
--     Manager + Cloud KMS (PRD 25), where this table stays empty; the
--     envelope store is the self-hostable default that keeps the
--     at-rest guarantee local (KEK from the deployment environment,
--     never committed). Plaintext material exists only in JVM memory
--     between the binding write and the encrypt call.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): three new
--     tables and indexes only; nothing existing is dropped, renamed,
--     or re-typed.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.

-- ---------------------------------------------------------------------------
-- store_accounts: the delegation chain (PRD 25)
-- ---------------------------------------------------------------------------
CREATE TABLE store_accounts (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- NULL application = the organization-level default account; a
    -- set application (NULL environment) = the app-level override;
    -- both set = the environment-level override. Production is not
    -- QA because they are different rows under different environments.
    application_id uuid REFERENCES applications (id),
    environment_id uuid REFERENCES environments (id),
    provider varchar(32) NOT NULL,
    name varchar(100) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    secret_reference varchar(512) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_store_accounts_provider CHECK (char_length(btrim(provider)) > 0),
    CONSTRAINT ck_store_accounts_name CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
    -- The reference is printable and whitespace-free (the V27 shape
    -- rule): a reference is an opaque locator, never prose.
    CONSTRAINT ck_store_accounts_secret_reference_shape CHECK (
        secret_reference ~ '^[[:print:]]+$' AND secret_reference !~ '[[:space:]]'),
    -- An environment-level binding must name its application (the
    -- chain's levels are org, app, app+env -- never env alone).
    CONSTRAINT ck_store_accounts_level_coherent CHECK (
        environment_id IS NULL OR application_id IS NOT NULL),
    -- One account per provider per level: the chain's resolution is
    -- unambiguous by construction (NULLS NOT DISTINCT so the
    -- org-level row -- NULL application, NULL environment -- is
    -- unique too, which plain UNIQUE would not enforce).
    CONSTRAINT uq_store_accounts_level_provider
        UNIQUE NULLS NOT DISTINCT (organization_id, application_id, environment_id, provider)
);

CREATE INDEX idx_store_accounts_organization_id ON store_accounts (organization_id);
CREATE INDEX idx_store_accounts_application_id ON store_accounts (application_id);
CREATE INDEX idx_store_accounts_environment_id ON store_accounts (environment_id);

-- ---------------------------------------------------------------------------
-- channel_distributions: the adapter-driven records (PRD 8.7/13)
-- ---------------------------------------------------------------------------
CREATE TABLE channel_distributions (
    id uuid PRIMARY KEY,
    release_id uuid NOT NULL REFERENCES releases (id),
    artifact_id uuid NOT NULL REFERENCES artifacts (id),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    channel varchar(64) NOT NULL,
    status distribution_status NOT NULL DEFAULT 'PENDING',
    external_id varchar(255),
    failure_code varchar(128),
    attempts int NOT NULL DEFAULT 0,
    last_attempt_at timestamptz,
    distributed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_channel_distributions_channel CHECK (char_length(btrim(channel)) BETWEEN 1 AND 64),
    CONSTRAINT ck_channel_distributions_attempts CHECK (attempts >= 0)
);

-- The record's own grain: one row per release artifact per channel;
-- the adapter's report is the only writer of status/external_id.
CREATE UNIQUE INDEX uq_channel_distributions_release_artifact_channel
    ON channel_distributions (release_id, artifact_id, channel);

CREATE INDEX idx_channel_distributions_release_id ON channel_distributions (release_id);
CREATE INDEX idx_channel_distributions_artifact_id ON channel_distributions (artifact_id);
CREATE INDEX idx_channel_distributions_organization_id ON channel_distributions (organization_id);

-- ---------------------------------------------------------------------------
-- managed_secrets: the envelope store's ciphertext (ADR-0008)
-- ---------------------------------------------------------------------------
CREATE TABLE managed_secrets (
    id uuid PRIMARY KEY,
    secret_reference varchar(512) NOT NULL,
    -- The AEAD-encrypted material and the KEK-wrapped data key: both
    -- ciphertext by construction (AES-256-GCM, AAD-bound to the
    -- reference and key id); neither column can hold plaintext
    -- because the store never writes it.
    ciphertext text NOT NULL,
    wrapped_data_key text NOT NULL,
    key_id varchar(64) NOT NULL,
    algorithm varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_managed_secrets_secret_reference CHECK (
        secret_reference ~ '^[[:print:]]+$' AND secret_reference !~ '[[:space:]]'),
    CONSTRAINT uq_managed_secrets_secret_reference UNIQUE (secret_reference)
);
