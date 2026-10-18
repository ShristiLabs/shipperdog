-- V55__ota_bundles.sql
-- The OTA bundle authoring foundation (issue #164, S16-02, the OTA
-- updates plan's workstream 1; ADR-0010 D1/D2/D3/D5/D7/D8): apps that
-- declare OTA support author generic bundles -- platform-agnostic
-- artifacts riding the existing artifact store -- each with its own
-- versions row, ready to hang off a normal release. The device-side
-- identifier lands with it: the hashed, revocable, app-scoped sdc1.
-- app-token registry (an identifier, not a secret -- ADR-0010 D3).
--
--   * applications.ota_enabled -- the OTA flag the console toggles and
--     the check resolution consults; every existing app is NOT
--     OTA-enabled (the flag's permanent default: new apps opt in).
--   * ota_bundles -- one row per authored bundle. The bundle's bytes
--     are an ordinary artifact (D5: the existing store and its upload
--     sessions serve them; the artifact FK), its identity its OWN
--     versions row (D2: version_id FKs on releases /
--     analytics_events stay intact), and its manifest (sha256, size,
--     platform tag) plus Ed25519 signature and key id land at
--     finalize+sign (D8: the manifest columns are NULL until then,
--     always as coherent pairs). The release link is NULL until the
--     bundle publishes (exactly one bundle per OTA release is the
--     S16-05 rule; the UNIQUE version_id below is its schema floor: a
--     bundle owns its versions row exclusively).
--   * ota_app_tokens -- the sdc1. registry: the token's SHA-256 hex
--     only (write-only doctrine, the V49 sso_session_codes.code_hash
--     precedent; the token itself is returned exactly once at issue),
--     app-scoped, revocable by row.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one NOT NULL
--     DEFAULT false flag, new tables and indexes only; nothing
--     existing is dropped, renamed, or re-typed.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.
--   * Open vocabularies are row values, not schema migrations (the
--     V42 provider doctrine): platform_tag is a shape-checked varchar
--     because the generic-bundle platform set is open (React Native,
--     Flutter, Electron, ...), deliberately not the IOS/ANDROID
--     artifact platform enum.

-- The OTA opt-in: false for every app until the owner toggles it.
ALTER TABLE applications ADD COLUMN ota_enabled boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN applications.ota_enabled IS
    'OTA bundle updates opt-in (S16-02): false until the owner enables OTA for the app; gates bundle authoring and the device check resolution.';

-- ---------------------------------------------------------------------------
-- ota_bundles: the authored generic bundle
-- ---------------------------------------------------------------------------
CREATE TABLE ota_bundles (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- The owning tenant, NULL = organization-shared application (the
    -- applications/releases rows' own shape).
    tenant_id uuid REFERENCES tenants (id),
    application_id uuid NOT NULL REFERENCES applications (id),
    -- The bundle's OWN versions row (ADR-0010 D2): label = bundle_version,
    -- so every version_id FK downstream of releases stays intact.
    version_id uuid NOT NULL REFERENCES versions (id),
    -- NULL until the bundle publishes on a release (the S16-05
    -- exactly-one-bundle rule owns the release-side invariant).
    release_id uuid REFERENCES releases (id),
    -- The bundle bytes are an ordinary artifact (ADR-0010 D5): the
    -- existing store, upload sessions, and download proxy serve them.
    artifact_id uuid NOT NULL REFERENCES artifacts (id),
    -- The bundle's version label (its versions row's version_name; the
    -- UNIQUE grain mirrors uq_versions_application_version).
    bundle_version varchar(100) NOT NULL,
    -- The generic platform tag: an OPEN vocabulary ('android', 'ios',
    -- 'electron', ...) -- generic bundles are platform-agnostic.
    platform_tag varchar(32) NOT NULL,
    -- The manifest, landed at finalize (sha256 hex, exact byte size):
    -- NULL while UPLOADING/VALIDATING, present as a pair thereafter.
    sha256 char(64),
    size_bytes bigint,
    -- The Ed25519 signature over the sha256 and its key id, landed at
    -- sign (ADR-0010 D8: per-app key, secret-store custody; the public
    -- key rides the check response). Present as a pair or not at all.
    signature varchar(512),
    key_id varchar(64),
    -- Per-bundle defaults, overridable per release target at release
    -- time (ADR-0010 D7).
    forced boolean NOT NULL DEFAULT false,
    min_app_version varchar(100),
    -- UPLOADING (created, bytes arriving), VALIDATING (finalize's
    -- manifest computation), SIGNED (finalize+sign complete, ready to
    -- publish), RELEASED (hanging off an active release), REVOKED.
    status varchar(16) NOT NULL,
    created_by uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_ota_bundles_platform_tag CHECK (
        char_length(btrim(platform_tag)) BETWEEN 1 AND 32),
    CONSTRAINT ck_ota_bundles_size CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_ota_bundles_min_app_version CHECK (
        min_app_version IS NULL OR char_length(btrim(min_app_version)) BETWEEN 1 AND 100),
    CONSTRAINT ck_ota_bundles_status CHECK (
        status IN ('UPLOADING', 'VALIDATING', 'SIGNED', 'RELEASED', 'REVOKED')),
    -- The manifest lands as a coherent pair: finalize computes the
    -- sha256 and the byte size together or not at all.
    CONSTRAINT ck_ota_bundles_manifest_shape CHECK (
        (sha256 IS NULL AND size_bytes IS NULL)
        OR (sha256 IS NOT NULL AND size_bytes IS NOT NULL)),
    -- Signing lands as a coherent pair: the signature names its key id.
    CONSTRAINT ck_ota_bundles_signing_shape CHECK (
        (signature IS NULL AND key_id IS NULL)
        OR (signature IS NOT NULL AND key_id IS NOT NULL)),
    -- A bundle owns its versions row exclusively (ADR-0010 D2).
    CONSTRAINT uq_ota_bundles_version UNIQUE (version_id),
    -- The bundle's label is unique inside its application.
    CONSTRAINT uq_ota_bundles_app_bundle_version UNIQUE (application_id, bundle_version)
);

CREATE INDEX idx_ota_bundles_organization_id ON ota_bundles (organization_id);
CREATE INDEX idx_ota_bundles_application_id ON ota_bundles (application_id);
-- The release-side lookup: the S16-05 exactly-one-bundle rule and the
-- check resolution walk release -> bundle.
CREATE INDEX idx_ota_bundles_release_id ON ota_bundles (release_id);

-- ---------------------------------------------------------------------------
-- ota_app_tokens: the hashed sdc1. device-identifier registry
-- ---------------------------------------------------------------------------
CREATE TABLE ota_app_tokens (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    application_id uuid NOT NULL REFERENCES applications (id),
    -- The human label the issue/revoke surfaces distinguish tokens by.
    name varchar(200) NOT NULL,
    -- Write-only: the SHA-256 hex of the sdc1. token. The token itself
    -- is returned exactly once at issue and never stored (the V39
    -- webhook_secret / V49 code_hash doctrine).
    token_hash varchar(64) NOT NULL,
    created_by uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    -- NULL while the token is live; the revoke action stamps it.
    revoked_at timestamptz,
    -- One registry row per issued token: the hash IS the identity.
    CONSTRAINT uq_ota_app_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_ota_app_tokens_name CHECK (
        char_length(btrim(name)) BETWEEN 1 AND 200)
);

CREATE INDEX idx_ota_app_tokens_application_id ON ota_app_tokens (application_id);

COMMENT ON COLUMN ota_app_tokens.token_hash IS
    'Write-only SHA-256 hex of the sdc1. app token (S16-02): the token is shown exactly once at issue; revocation stamps revoked_at and the check filter refuses it.';
