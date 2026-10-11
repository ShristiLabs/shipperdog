-- -----------------------------------------------------------------------------
-- V50: SAML single sign-on (issue #68, S14-02, PRD 25 / FR-001)
--
-- SAML joins OIDC on the provider-keyed organization_idp_configs table
-- (the V49 unique constraint anticipated it): a SAML row reuses the
-- provisioning/linkage columns (provisioning_mode, jit_default_role,
-- require_verified_email, group_claim_name as the SAML attribute name,
-- role_mappings) and stores its provider material in new nullable
-- columns -- the raw IdP metadata XML as uploaded (the audit source of
-- truth) plus the parsed entityID the assertion Issuer must equal.
--
-- The SP keeps ONE keypair per deployment (optional): unsigned
-- AuthnRequests and metadata without a signing KeyDescriptor when
-- unset, Redirect-binding signatures when configured via environment
-- (material never lives in this database).
--
-- enforce_sso_login: the organization may make SSO the only credential
-- -- password login is refused for its members (platform
-- administrators keep their break-glass local login).
-- -----------------------------------------------------------------------------

ALTER TABLE organization_idp_configs
    -- Raw IdP metadata XML (EntityDescriptor) exactly as uploaded;
    -- re-parsed on use, size-capped at the API boundary.
    ADD COLUMN saml_metadata_xml       text,
    -- The parsed IdP entityID: the value Response/Assertion Issuer and
    -- the audience/recipient checks pin against.
    ADD COLUMN saml_idp_entity_id      varchar(512),
    -- Which enveloped signatures are REQUIRED (at least one of the two;
    -- the API refuses wantAssertionsSigned=false together with
    -- wantResponseSigned=false). Workspace signs the Response only,
    -- Entra the Assertion, Okta either.
    ADD COLUMN saml_want_assertions_signed boolean NOT NULL DEFAULT true,
    ADD COLUMN saml_want_response_signed   boolean NOT NULL DEFAULT false,
    -- true: this organization's members may not password-login (the
    -- SAML mandate); platform administrators are exempt by code.
    ADD COLUMN enforce_sso_login       boolean NOT NULL DEFAULT false;

-- IdP-initiated flows and metadata rotation look configs up by the
-- asserted Issuer; a partial index keeps it cheap and OIDC rows out.
CREATE INDEX idx_org_idp_configs_saml_entity_id
    ON organization_idp_configs (saml_idp_entity_id)
    WHERE saml_idp_entity_id IS NOT NULL;

-- One in-flight SP-initiated SAML authorization request: the
-- AuthnRequest ID (stored hashed -- InResponseTo must match it) and
-- the RelayState (hashed likewise; the raw value is a bearer
-- capability riding the browser URL). Short-lived, single-use,
-- consumed atomically exactly like the OIDC state row, and swept by
-- the same opportunistic hygiene at every login start.
CREATE TABLE saml_authorization_requests (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES organizations (id),
    request_id_hash     varchar(64) NOT NULL UNIQUE,
    relay_state_hash    varchar(64) NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL,
    consumed_at         timestamptz
);
