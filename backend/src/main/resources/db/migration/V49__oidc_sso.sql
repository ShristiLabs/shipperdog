-- -----------------------------------------------------------------------------
-- V49: OIDC single sign-on (issue #67, S14-01, PRD 25 / FR-001)
--
-- The S1-04 placeholder becomes real: organization_idp_configs turns
-- from an opaque jsonb echo into the typed OIDC relying-party
-- configuration, and the identity module gains the flow-state tables
-- the authorization-code + PKCE exchange needs (authorization
-- requests carrying the PKCE verifier and nonce, one-time session
-- codes handing the SPA its tokens, and the subject<->user link
-- table that remembers which corporate identity maps to which
-- account).
--
-- Placeholder rows: the jsonb record was inert by declaration
-- ("configuring an identity provider does nothing functionally until
-- S14-01"), so V3-era rows carry no migratable meaning. Their typed
-- columns stay NULL and every such row reads as not-configured until
-- an administrator authors a real configuration over it; the jsonb
-- column itself is dropped.
-- -----------------------------------------------------------------------------

-- SSO users may be password-less: a JIT-provisioned account has no
-- local password until the user sets one (the account is reachable
-- only through its linked identity provider).
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- The provisioning policy of an organization's SSO configuration.
CREATE TYPE sso_provisioning_mode AS ENUM ('INVITED_ONLY', 'JIT');

ALTER TABLE organization_idp_configs
    DROP CONSTRAINT uq_organization_idp_configs_organization,
    DROP COLUMN config,
    ADD COLUMN issuer_url            varchar(512),
    ADD COLUMN client_id             varchar(255),
    -- PRD 25: store references, not credentials. The client secret's
    -- material lives in the secret store; this column keeps the opaque
    -- reference the token exchange resolves at call time.
    ADD COLUMN client_secret_ref     varchar(512),
    ADD COLUMN scopes                varchar(512),
    -- Which ID-token claim carries the enterprise groups (Okta groups,
    -- Entra roles/groups); empty disables group->role mapping.
    ADD COLUMN group_claim_name      varchar(128),
    -- Which ID-token claim the IdP uses to say the account is
    -- inactive (a custom claim per IdP); empty disables the lockout leg.
    ADD COLUMN inactive_claim_name   varchar(128),
    ADD COLUMN provisioning_mode     sso_provisioning_mode,
    ADD COLUMN jit_default_role      org_role,
    -- Default strict: an IdP attesting an unverified email must not
    -- become a login (configurable relaxation is the org's explicit
    -- decision, recorded in the same row).
    ADD COLUMN require_verified_email boolean NOT NULL DEFAULT true,
    -- group claim value -> org_role; applied on every login.
    ADD COLUMN role_mappings         jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN enabled               boolean NOT NULL DEFAULT false,
    -- One row per provider kind per organization; SAML (S14-02) joins
    -- the same table without another constraint change.
    ADD CONSTRAINT uq_organization_idp_configs_org_provider
        UNIQUE (organization_id, provider_type);

-- The remembered link between a corporate identity (issuer + subject)
-- and a ShipperDog account, scoped to the organization whose provider
-- asserted it. Once linked, logins resolve through the subject and
-- never re-derive the account from email alone.
CREATE TABLE user_idp_identities (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users (id),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    issuer_url      varchar(512) NOT NULL,
    subject         varchar(320) NOT NULL,
    -- Diagnostic only: the email as asserted at link time.
    email_at_link   varchar(255),
    -- 'email' (linked by verified-email match) or 'jit' (provisioned).
    link_kind       varchar(16) NOT NULL,
    linked_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_idp_identities_org_subject
        UNIQUE (organization_id, subject),
    CONSTRAINT uq_user_idp_identities_org_user
        UNIQUE (organization_id, user_id)
);

-- One in-flight authorization request: the state (stored hashed --
-- the raw value is a bearer capability in the browser URL), the nonce
-- the ID token must repeat (hashed likewise), and the PKCE verifier
-- needed at the token exchange. Short-lived, single-use.
CREATE TABLE sso_authorization_requests (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    state_hash      varchar(64) NOT NULL UNIQUE,
    nonce_hash      varchar(64) NOT NULL,
    code_verifier   varchar(128) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    consumed_at     timestamptz
);

-- The one-time code the SSO callback hands the console: the SPA
-- exchanges it once for the same access/refresh pair a local login
-- receives, so token material never rides a URL. Stored hashed.
CREATE TABLE sso_session_codes (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code_hash       varchar(64) NOT NULL UNIQUE,
    user_id         uuid NOT NULL REFERENCES users (id),
    organization_id uuid NOT NULL REFERENCES organizations (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    consumed_at     timestamptz
);
