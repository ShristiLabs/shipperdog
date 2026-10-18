-- V58__ota_signing_keys.sql
-- The app-scoped Ed25519 signing-key registry (issue #165, S16-03,
-- ADR-0010 D8): the private key lives behind the secret-store port
-- (ADR-0008 -- envelope-encrypted or GCP Secret Manager, selected by
-- shipperdog.secrets.provider) and THIS table keeps only the public
-- half and the store reference. One ACTIVE key per application (the
-- partial unique index); rotation is generational -- a new row under a
-- new key_id, the old row's revoked_at stamped -- so old bundle
-- signatures keep verifying against the embedded public keys while new
-- bundles sign under the new key (ADR-0010's "rotation is a console
-- concern, not a migration").
--
-- V55 landed the bundle columns this registry serves (ota_bundles.key_id
-- names its signer) but no key-custody row: the secret-store port mints
-- opaque references (local-envelope://... / gcp-secret-manager://...),
-- so the app -> reference mapping needs a relational home or a
-- generated key would be unfindable after the request that made it.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): a new table and
--     its indexes only; nothing existing is dropped, renamed, or
--     re-typed.
--   * Constraint names use the explicit ck_/uq_/idx_/fk_ prefixes.

CREATE TABLE ota_signing_keys (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organizations (id),
    -- Mirrors the owning application's dimension: NULL when the
    -- application is organization-shared (the applications row's own
    -- shape).
    tenant_id uuid REFERENCES tenants (id),
    application_id uuid NOT NULL REFERENCES applications (id),
    -- The public key identifier that rides bundle rows (ota_bundles.
    -- key_id) and the device-check manifest; opaque, stable per key
    -- generation.
    key_id varchar(64) NOT NULL,
    -- Base64 of the 32-byte Ed25519 public key (44 chars): the half
    -- consoles copy for SDK embedding. Never the private material.
    public_key varchar(64) NOT NULL,
    -- The secret-store port's opaque reference to the private key; the
    -- reference is all this row ever keeps (the ADR-0008 doctrine).
    secret_reference varchar(2048) NOT NULL,
    -- Records D8's chosen scheme; Ed25519 via JCA only (ADR-0009).
    algorithm varchar(16) NOT NULL DEFAULT 'Ed25519',
    created_by uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    -- NULL while the key is active; rotation stamps it.
    revoked_at timestamptz,
    CONSTRAINT ck_ota_signing_keys_key_id CHECK (
        char_length(btrim(key_id)) BETWEEN 1 AND 64),
    CONSTRAINT ck_ota_signing_keys_algorithm CHECK (algorithm = 'Ed25519'),
    CONSTRAINT uq_ota_signing_keys_key UNIQUE (key_id)
);

CREATE INDEX idx_ota_signing_keys_application_id ON ota_signing_keys (application_id);

-- One ACTIVE key per application: a second live key for the same app
-- would sign bundles devices cannot distinguish; rotation revokes the
-- old row in the same transaction the new row lands.
CREATE UNIQUE INDEX uq_ota_signing_keys_active
    ON ota_signing_keys (application_id)
    WHERE revoked_at IS NULL;

COMMENT ON TABLE ota_signing_keys IS
    'App-scoped OTA bundle signing keys (S16-03, ADR-0010 D8): public half and secret-store reference only; one ACTIVE key per application; rotation is a new key_id with the old row revoked.';
