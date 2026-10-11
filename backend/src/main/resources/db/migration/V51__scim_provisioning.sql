-- -----------------------------------------------------------------------------
-- V51: SCIM 2.0 provisioning & deprovisioning (issue #69, S14-03, PRD 25)
--
-- The joiner/leaver automation surface: an identity provider pushes
-- Users and Groups over RFC 7644 to /scim/v2, authenticated by a
-- per-organization bearer token the organization administrator
-- generates (the PAT posture: only the SHA-256 hash is stored; the
-- plaintext is shown once at generation and never again).
--
-- SCIM ids are deliberately separate from the internal users.id UUIDs
-- (the story's technical note): scim_users.id IS the SCIM "id" the IdP
-- addresses resources by, and externalId -- the IdP's own stable id
-- for the person -- is the join key (UNIQUE per organization), so
-- re-provisioning after deactivation finds the same account and the
-- same SCIM id (idempotent create semantics).
--
-- Group -> role/tenant mapping is org-configurable: one row per
-- (group, org role) in scim_group_role_mappings and one row per
-- (group, tenant) in scim_group_tenant_mappings. Membership changes
-- re-derive the affected users' organization role (most privileged
-- mapped value wins, the SSO role-mapping discipline) and tenant
-- memberships.
-- -----------------------------------------------------------------------------

-- The organization's SCIM settings (one row per organization; its
-- absence reads as not-configured). default_role is the floor a
-- provisioned user receives when no mapped group applies.
CREATE TABLE scim_configs (
    organization_id uuid PRIMARY KEY REFERENCES organizations (id),
    enabled         boolean NOT NULL DEFAULT false,
    default_role    org_role NOT NULL DEFAULT 'VIEWER',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- The live bearer token of an organization's SCIM connector. ONE live
-- token per organization: generating a new one (rotation) replaces
-- the row and the old token dies immediately. The material exists
-- only in the administrator's one-time response; this row keeps the
-- SHA-256 hex hash and usage metadata.
CREATE TABLE scim_tokens (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL UNIQUE REFERENCES organizations (id),
    token_hash      varchar(64) NOT NULL UNIQUE,
    -- Optional operator label naming the connector (e.g. "Entra
    -- tenant") -- the "by which IdP" fact of the provisioning log.
    label           varchar(128),
    created_at      timestamptz NOT NULL DEFAULT now(),
    last_used_at    timestamptz,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- SCIM group display name -> organization role. UNIQUE per
-- (organization, group): each group maps to exactly one org role;
-- when several mapped groups match a user, the most privileged wins.
CREATE TABLE scim_group_role_mappings (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    group_name      varchar(255) NOT NULL,
    org_role        org_role NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_scim_group_role_mappings UNIQUE (organization_id, group_name)
);

-- SCIM group display name -> tenant membership. A group may map to
-- several tenants (one row each); members of the group receive the
-- mapped tenant role.
CREATE TABLE scim_group_tenant_mappings (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    group_name      varchar(255) NOT NULL,
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    tenant_role     tenant_role NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_scim_group_tenant_mappings UNIQUE (organization_id, group_name, tenant_id)
);

-- The IdP-provisioned user registry: the stable SCIM id (separate
-- from users.id), the externalId join key, and the userName the IdP
-- addresses the person by. user_name is UNIQUE per organization: a
-- second person with the same userName is a 409, never a silent
-- second account. user_id is UNIQUE per organization: one SCIM
-- record per account per organization (an account may be provisioned
-- by several organizations' connectors -- one row each).
CREATE TABLE scim_users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES users (id),
    external_id     varchar(255) NOT NULL,
    user_name       varchar(320) NOT NULL,
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_scim_users_external_id UNIQUE (organization_id, external_id),
    CONSTRAINT uq_scim_users_user_name UNIQUE (organization_id, user_name),
    CONSTRAINT uq_scim_users_user_id UNIQUE (organization_id, user_id)
);
CREATE INDEX idx_scim_users_organization ON scim_users (organization_id);
CREATE INDEX idx_scim_users_user ON scim_users (user_id);

-- The pushed groups. display_name is UNIQUE per organization: the
-- mapping rules and the membership sync key on it.
CREATE TABLE scim_groups (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    external_id     varchar(255),
    display_name    varchar(255) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_scim_groups_external_id UNIQUE (organization_id, external_id),
    CONSTRAINT uq_scim_groups_display_name UNIQUE (organization_id, display_name)
);
CREATE INDEX idx_scim_groups_organization ON scim_groups (organization_id);

-- Group membership: the (group, user) pairs the IdP pushed. Rows are
-- replaced wholesale by PUT and adjusted by PATCH; deletes follow
-- their group.
CREATE TABLE scim_group_members (
    scim_group_id uuid NOT NULL REFERENCES scim_groups (id) ON DELETE CASCADE,
    scim_user_id  uuid NOT NULL REFERENCES scim_users (id) ON DELETE CASCADE,
    added_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scim_group_id, scim_user_id)
);
CREATE INDEX idx_scim_group_members_user ON scim_group_members (scim_user_id);
