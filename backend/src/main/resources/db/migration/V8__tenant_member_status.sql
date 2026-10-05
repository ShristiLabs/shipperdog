-- V8__tenant_member_status.sql
-- Membership status switch for tenant members (issue #11, S2-03).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one new enum type
--     and one added column; nothing is dropped or altered destructively.
--   * Enum values are never removed or renamed.
--
-- A membership row carries its own status, independent of the user
-- account status (issue #11 technical notes): both switches must be on
-- for tenant access — the account switch lives with identity (login and
-- token refresh refuse disabled accounts), the membership switch lives
-- here. SUSPENDED parks the member's tenant role without deleting the
-- row, so suspension is reversible and the roster keeps the member.
--
-- V1's membership_status vocabulary (ACTIVE/REVOKED) belongs to
-- tenant_tester_memberships (S6); tenant members get their own
-- vocabulary so the two surfaces evolve independently.

CREATE TYPE tenant_member_status AS ENUM ('ACTIVE', 'SUSPENDED');

ALTER TABLE tenant_members
    ADD COLUMN status tenant_member_status NOT NULL DEFAULT 'ACTIVE';
