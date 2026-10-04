-- V4__rbac_roles.sql
-- RBAC role vocabulary and tenant-scoped memberships (issue #5, S1-05).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds enum values and
--     one table; nothing is dropped or altered destructively.
--   * Foreign keys are named fk_<table>_<column>, unique constraints
--     uq_<table>_<cols>, indexes idx_<table>_<column>, and check
--     constraints ck_<table>_<rule>.
--   * Enum values are never removed or renamed. The new org_role values are
--     not used by statements in this migration (PostgreSQL forbids using a
--     value added in the same transaction), which is fine: the permission
--     catalog lives in code and no data migration is required.

-- ---------------------------------------------------------------------------
-- org_role: PRD section 7 organization-scoped roles (issue #5)
-- ---------------------------------------------------------------------------

-- ORG_ADMIN and ORG_MEMBER exist since V3. V4 adds the functional roles of
-- PRD section 7; ORG_MEMBER stays the membership default and deliberately
-- carries no catalog permissions (deny-by-default).
ALTER TYPE org_role ADD VALUE IF NOT EXISTS 'APP_OWNER';
ALTER TYPE org_role ADD VALUE IF NOT EXISTS 'DEVELOPER';
ALTER TYPE org_role ADD VALUE IF NOT EXISTS 'QA_ENGINEER';
ALTER TYPE org_role ADD VALUE IF NOT EXISTS 'RELEASE_MANAGER';
ALTER TYPE org_role ADD VALUE IF NOT EXISTS 'SECURITY_REVIEWER';
ALTER TYPE org_role ADD VALUE IF NOT EXISTS 'COMPLIANCE_REVIEWER';
ALTER TYPE org_role ADD VALUE IF NOT EXISTS 'VIEWER';

-- Platform Administrator (PRD 7.1) is NOT an enum value: it stays the
-- users.platform_admin flag introduced in V3 and is modeled as the
-- PLATFORM_ADMIN role of the permission catalog without schema churn.

-- ---------------------------------------------------------------------------
-- tenant_role: tenant-scoped roles (issue #5)
-- ---------------------------------------------------------------------------

CREATE TYPE tenant_role AS ENUM ('TENANT_ADMIN', 'TENANT_TESTER');

-- ---------------------------------------------------------------------------
-- tenant_members: role assignments inside a tenant (issue #5)
-- ---------------------------------------------------------------------------

-- Tenant membership is independent of organization membership: tenant
-- testers (PRD 7.8) are not necessarily organization members. Tenant CRUD
-- itself arrives with E2 (issues #9-#12); this table is the RBAC surface.
CREATE TABLE tenant_members (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid NOT NULL,
    user_id    uuid NOT NULL,
    role       tenant_role NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_members_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT fk_tenant_members_tenant_id
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_tenant_members_user_id
        FOREIGN KEY (user_id) REFERENCES users (id)
);

-- tenant_id lookups are served by the prefix of
-- uq_tenant_members_tenant_user.
CREATE INDEX idx_tenant_members_user_id ON tenant_members (user_id);
