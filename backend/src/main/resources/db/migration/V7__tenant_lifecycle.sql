-- V7__tenant_lifecycle.sql
-- Tenant lifecycle schema for ShipperDog (issue #9, S2-01).
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): adds an enum
--     value; nothing is dropped or altered destructively.
--   * Enum values are never removed or renamed.
--
-- The tenants table itself is complete from the V1 baseline (id,
-- organization_id, name, external_id, status, timestamps, and both
-- uniqueness guards the story needs: uq_tenants_org_name for the
-- within-organization identifier and uq_tenants_external_id for the
-- globally unique customer-system mapping). The only schema gap is the
-- lifecycle vocabulary: V1's tenant_status has no DEACTIVATED value.

-- ---------------------------------------------------------------------------
-- Enum types
-- ---------------------------------------------------------------------------

-- Issue #9 lifecycle value; V1's 'DISABLED' remains reserved and unused,
-- mirroring the org_status precedent (V3).
ALTER TYPE tenant_status ADD VALUE IF NOT EXISTS 'DEACTIVATED';
