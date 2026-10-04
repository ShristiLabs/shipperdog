-- V3__organization_management.sql
-- Organization management schema for ShipperDog (issue #4, S1-04).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds enum values,
--     columns, and tables; nothing is dropped or altered destructively.
--   * Foreign keys are named fk_<table>_<column>, unique constraints
--     uq_<table>_<cols>, indexes idx_<table>_<column>, and check
--     constraints ck_<table>_<rule>.
--   * Enum values are never removed or renamed: the org_status 'DISABLED'
--     label from V1 stays reserved-unused, and the issue's DEACTIVATED
--     lifecycle value is added per the ALTER TYPE policy.

-- ---------------------------------------------------------------------------
-- Enum types
-- ---------------------------------------------------------------------------

-- Organization membership roles (issue #4). The RBAC engine (S1-05)
-- extends this vocabulary when richer role sets are needed.
CREATE TYPE org_role AS ENUM ('ORG_ADMIN', 'ORG_MEMBER');

-- Issue #4 lifecycle value; V1's 'DISABLED' remains reserved and unused.
ALTER TYPE org_status ADD VALUE IF NOT EXISTS 'DEACTIVATED';

-- ---------------------------------------------------------------------------
-- organizations: external billing/CRM mapping (issue #4 technical note)
-- ---------------------------------------------------------------------------

ALTER TABLE organizations
    ADD COLUMN external_id varchar(100),
    ADD CONSTRAINT uq_organizations_external_id UNIQUE (external_id);

-- ---------------------------------------------------------------------------
-- users: platform-administrator flag
-- ---------------------------------------------------------------------------

-- Platform administrators operate above organization scope: they create
-- organizations, administer any of them, and are exempt from the
-- org-deactivation login block. S1-05 RBAC may generalize this flag into
-- its role model without schema churn.
ALTER TABLE users ADD COLUMN platform_admin boolean NOT NULL DEFAULT false;

-- ---------------------------------------------------------------------------
-- organization_members
-- ---------------------------------------------------------------------------

CREATE TABLE organization_members (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    user_id         uuid NOT NULL,
    role            org_role NOT NULL DEFAULT 'ORG_MEMBER',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_organization_members_org_user UNIQUE (organization_id, user_id),
    CONSTRAINT fk_organization_members_organization_id
        FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_organization_members_user_id
        FOREIGN KEY (user_id) REFERENCES users (id)
);

-- organization_id lookups are served by the prefix of
-- uq_organization_members_org_user.
CREATE INDEX idx_organization_members_user_id ON organization_members (user_id);

-- ---------------------------------------------------------------------------
-- user_invitations: optional organization context (issue #4)
-- ---------------------------------------------------------------------------

-- Org-scoped invitations carry both fields or neither; accepting one
-- grants the membership with the recorded role (application layer).
ALTER TABLE user_invitations
    ADD COLUMN organization_id uuid,
    ADD COLUMN role org_role,
    ADD CONSTRAINT fk_user_invitations_organization_id
        FOREIGN KEY (organization_id) REFERENCES organizations (id),
    ADD CONSTRAINT ck_user_invitations_org_role_pair
        CHECK ((organization_id IS NULL AND role IS NULL)
            OR (organization_id IS NOT NULL AND role IS NOT NULL));

-- ---------------------------------------------------------------------------
-- organization_idp_configs (S14 placeholder)
-- ---------------------------------------------------------------------------

-- Stored record only: configuring an identity provider does nothing
-- functionally until S14-01. config is a JSON document.
CREATE TABLE organization_idp_configs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL,
    provider_type   varchar(64) NOT NULL,
    config          jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_organization_idp_configs_organization UNIQUE (organization_id),
    CONSTRAINT fk_organization_idp_configs_organization_id
        FOREIGN KEY (organization_id) REFERENCES organizations (id)
);
