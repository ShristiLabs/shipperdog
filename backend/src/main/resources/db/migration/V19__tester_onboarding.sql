-- V19__tester_onboarding.sql
-- Tester onboarding and invitations (issue #26, S6-01).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only (ADR-0001): new enum values, new
--     columns, one nullable-widening (the V15 build_number precedent),
--     and one constraint REPLACEMENT following the V10
--     uq_applications_scope_name precedent. Nothing is dropped that
--     holds data; no enum value is renamed or removed.
--   * Foreign keys fk_<table>_<column>, unique constraints
--     uq_<table>_<cols>, check constraints ck_<table>_<rule>, indexes
--     idx_<table>_<column>.
--   * Enum values appended are not used by statements in this
--     migration (PostgreSQL forbids using a value added in the same
--     transaction); the application assigns them.
--
-- Design decisions recorded for reviewers:
--
-- The tester record is the ORGANIZATION-scoped person record of PRD
-- 8.8/13: testers carry no tenant column of their own because tenant
-- attachment is the tenant_tester_memberships junction (S6-03) and
-- group attachment is tester_groups (S6-02). PRD 13's simplified
-- testers sketch (id, user_id, status) omitted the organization
-- dimension; without it, any organization's tester listing would
-- surface every other organization's testers (INV-001 leak), so the
-- organization dimension is added here explicitly.
--
-- user_id becomes NULLABLE (the V15 build_number widening precedent):
-- an INVITED tester exists before the invitee has accepted and created
-- an account; acceptance (S1-03 register) links the user. V1's
-- uq_testers_user stays valid under PostgreSQL's NULLs-distinct
-- semantics and remains the one-user-per-tester-row authority. The
-- testers table has never had a writer (the module starts here), so
-- the new NOT NULL columns apply to an empty table.
--
-- tester_status gains INVITED (the pre-account state this story
-- creates testers in) and SUSPENDED (the revocable park state, the
-- V8 tenant_member_status vocabulary precedent). V1's DISABLED stays
-- reserved-unused; S6-04 owns full revocation semantics.
--
-- The invitation machinery is REUSED, not duplicated: user_invitations
-- gains a third flavor. V2 created platform invitations (no scope),
-- V3 added org-member invitations (organization + role); this
-- migration adds tester-onboarding invitations (organization +
-- tester_id, no role). V3's ck_user_invitations_org_role_pair (both
-- set or both null) cannot express the tester flavor, so it is
-- REPLACED by the broader ck_user_invitations_scope covering all
-- three flavors — the V10 constraint-replacement precedent, a
-- widening (every previously valid row stays valid), applied in one
-- step because the new flavor is introduced in the same migration
-- that defines it.

-- ---------------------------------------------------------------------------
-- Enum types
-- ---------------------------------------------------------------------------

ALTER TYPE tester_status ADD VALUE IF NOT EXISTS 'INVITED';
ALTER TYPE tester_status ADD VALUE IF NOT EXISTS 'SUSPENDED';

-- ---------------------------------------------------------------------------
-- testers: the organization-scoped tester person record
-- ---------------------------------------------------------------------------

ALTER TABLE testers
    ADD COLUMN organization_id uuid NOT NULL,
    ADD COLUMN email varchar(255) NOT NULL,
    ADD COLUMN full_name varchar(200) NOT NULL,
    ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE testers
    ADD CONSTRAINT fk_testers_organization_id
        FOREIGN KEY (organization_id) REFERENCES organizations (id);

-- Email is stored normalized (lower-cased, trimmed) by the application;
-- one tester record per address per organization. Cross-organization
-- duplicates are legitimate: the same person may be a tester of two
-- organizations, each with its own onboarding state.
ALTER TABLE testers
    ADD CONSTRAINT uq_testers_organization_email UNIQUE (organization_id, email);

-- organization_id lookups are served by the prefix of
-- uq_testers_organization_email; suspended-tester login checks query by
-- user_id through uq_testers_user.

-- ---------------------------------------------------------------------------
-- user_invitations: tester-onboarding flavor
-- ---------------------------------------------------------------------------

ALTER TABLE user_invitations
    ADD COLUMN tester_id uuid,
    ADD CONSTRAINT fk_user_invitations_tester_id
        FOREIGN KEY (tester_id) REFERENCES testers (id);

ALTER TABLE user_invitations
    DROP CONSTRAINT ck_user_invitations_org_role_pair;

ALTER TABLE user_invitations
    ADD CONSTRAINT ck_user_invitations_scope CHECK (
        (organization_id IS NULL AND role IS NULL AND tester_id IS NULL)
        OR (organization_id IS NOT NULL AND role IS NOT NULL AND tester_id IS NULL)
        OR (organization_id IS NOT NULL AND role IS NULL AND tester_id IS NOT NULL));

-- One live invitation per tester: concurrent mints (double-clicked
-- invite, racing re-invite) collapse onto one PENDING row instead of
-- leaving two single-use tokens for the same tester.
CREATE UNIQUE INDEX uq_user_invitations_pending_tester
    ON user_invitations (tester_id)
    WHERE status = 'PENDING' AND tester_id IS NOT NULL;

-- Tester-acceptance lookups (register resolves the token by hash; the
-- tester surface reports the pending invitation's state).
CREATE INDEX idx_user_invitations_tester_id ON user_invitations (tester_id);
