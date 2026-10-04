-- V2__identity_auth.sql
-- Identity and authentication schema for ShipperDog (issue #3, S1-03).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): creates new enum
--     types and tables, and adds the foreign keys promised by V1 for its
--     forward-referenced identity columns. Nothing is dropped or altered
--     destructively.
--   * Unlike the V1 baseline, this migration names foreign keys explicitly
--     (fk_<table>_<column>) per the convention recorded in
--     docs/database-migrations.md.
--   * Raw secrets are never stored: password/token columns hold Argon2id
--     hashes or SHA-256 hex digests (varchar(64)) of high-entropy random
--     values, so a database leak does not expose credentials. Digest
--     columns are varchar(64) (V1's sha256 used char(64)) so JPA
--     ddl-auto=validate maps them without type gymnastics.
--   * The user_status 'LOCKED' value is reserved: runtime lockout state
--     lives in users.locked_until; enum values are never removable per
--     policy.
--   * organization membership for users (and therefore org_id/tenant_id
--     token claims) arrives with S1-04/S1-05; this migration keeps the
--     users table free of organization columns so those stories own them.

-- ---------------------------------------------------------------------------
-- Enum types
-- ---------------------------------------------------------------------------

CREATE TYPE user_status AS ENUM ('ACTIVE', 'LOCKED', 'DISABLED');
CREATE TYPE invitation_status AS ENUM ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED');

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------

-- email is stored normalized (lower-cased, trimmed) by the application;
-- failed_login_attempts/locked_until implement the failed-login lockout
-- (configurable threshold, issue #3). Password policy enforcement lives in
-- the application layer.
CREATE TABLE users (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email                 varchar(255) NOT NULL,
    password_hash         varchar(255) NOT NULL,
    full_name             varchar(200) NOT NULL,
    status                user_status NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts int NOT NULL DEFAULT 0,
    locked_until          timestamptz,
    last_login_at         timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- ---------------------------------------------------------------------------
-- refresh_tokens
-- ---------------------------------------------------------------------------

-- One row per issued refresh token (JWT). token_hash is the SHA-256 hex
-- digest of the raw JWT string. family_id groups a token with every token
-- minted from it through rotation; logout, password reset, and detected
-- reuse revoke the whole family.
CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL,
    family_id  uuid NOT NULL,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user_id FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);

-- ---------------------------------------------------------------------------
-- password_reset_tokens
-- ---------------------------------------------------------------------------

-- Single-use, short-TTL password reset links (issue #3). used_at marks the
-- token consumed; expires_at is enforced by the application layer.
CREATE TABLE password_reset_tokens (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_password_reset_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_tokens_user_id
        FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens (user_id);

-- ---------------------------------------------------------------------------
-- user_invitations
-- ---------------------------------------------------------------------------

-- Registration is invitation-only (issue #3). The raw invitation token is
-- returned once at creation; only its SHA-256 digest is stored. Re-inviting
-- an address revokes the previous pending invitation (application layer).
CREATE TABLE user_invitations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email       varchar(255) NOT NULL,
    invited_by  uuid NOT NULL,
    status      invitation_status NOT NULL DEFAULT 'PENDING',
    token_hash varchar(64) NOT NULL,
    expires_at  timestamptz NOT NULL,
    accepted_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_invitations_hash UNIQUE (token_hash),
    CONSTRAINT fk_user_invitations_invited_by FOREIGN KEY (invited_by) REFERENCES users (id)
);

CREATE INDEX idx_user_invitations_email ON user_invitations (email);
CREATE INDEX idx_user_invitations_invited_by ON user_invitations (invited_by);

-- ---------------------------------------------------------------------------
-- personal_access_tokens
-- ---------------------------------------------------------------------------

-- PATs for CLI/CI use (issue #3). The raw token is shown exactly once at
-- creation; token_prefix (first characters of the raw token) identifies the
-- token in listings without exposing it. expires_at NULL means
-- non-expiring; revoked_at marks revocation.
CREATE TABLE personal_access_tokens (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid NOT NULL,
    name         varchar(100) NOT NULL,
    token_prefix varchar(16) NOT NULL,
    token_hash varchar(64) NOT NULL,
    expires_at   timestamptz,
    revoked_at   timestamptz,
    last_used_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_personal_access_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_personal_access_tokens_user_id
        FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_personal_access_tokens_user_id ON personal_access_tokens (user_id);

-- ---------------------------------------------------------------------------
-- Foreign keys promised by V1 for its forward-referenced identity columns
-- (docs/database-migrations.md, "Forward references")
-- ---------------------------------------------------------------------------

ALTER TABLE testers
    ADD CONSTRAINT fk_testers_user_id FOREIGN KEY (user_id) REFERENCES users (id);

ALTER TABLE artifacts
    ADD CONSTRAINT fk_artifacts_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users (id);

ALTER TABLE releases
    ADD CONSTRAINT fk_releases_created_by FOREIGN KEY (created_by) REFERENCES users (id);

ALTER TABLE release_approvals
    ADD CONSTRAINT fk_release_approvals_approver FOREIGN KEY (approver) REFERENCES users (id);
