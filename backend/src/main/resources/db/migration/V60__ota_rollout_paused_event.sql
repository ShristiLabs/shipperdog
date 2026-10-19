-- V60__ota_rollout_paused_event.sql
-- Two gap-fixes the S16-06 service build surfaced over V56's frozen
-- rollout triple (issue #168, ADR-0010 D6/D10) -- the "schema ready
-- from V56, do not add migrations unless a gap truly requires it"
-- clause anticipated exactly these:
--
--   1. The PAUSED action joins the timeline's vocabulary. V56 wrote
--      "every operator action lands here" over ota_rollout_events but
--      pinned the action CHECK without a PAUSED value, so the pause --
--      one of the four control actions the rollout surface owns --
--      could not append the timeline row the S16-06 acceptance
--      criteria require of EVERY action. The constraint REWRITE (the
--      V57 precedent of rewriting V38's closed CHECK disjunctions)
--      widens the closed vocabulary by exactly that one value.
--
--   2. ota_rollout_plans gains the NULL tenant_id column the
--      org-owned doctrine (OrganizationScope: "the row carries a null
--      tenantId and its owning dimension is the organization")
--      presupposes but V56 omitted: the tenant-isolation seam (ADR
--      0002) composes its organization-scope predicate
--      (tenantId IS NULL AND organizationId = :context) on MAPPED
--      attributes, so an org-grain row without the column cannot ride
--      the seam at all. Expand-only ADD COLUMN, always NULL by the
--      service (the plan serves the release's whole device
--      population -- the application's owning organization's grain,
--      never one tenant's), exactly the shape V58's ota_signing_keys
--      and every other org-scoped row carries.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only (ADR-0001): one new nullable column
--     on a this-milestone table, one CHECK constraint rewrite; no
--     existing column is dropped, renamed, or re-typed, and no
--     existing action value leaves the vocabulary.
--   * Constraint names keep the explicit ck_ prefix.

ALTER TABLE ota_rollout_events DROP CONSTRAINT ck_ota_rollout_events_action;

ALTER TABLE ota_rollout_events ADD CONSTRAINT ck_ota_rollout_events_action CHECK (
        action IN ('COMMENCED', 'HALTED', 'PAUSED', 'RESUMED', 'ADVANCED', 'COMPLETED'));

ALTER TABLE ota_rollout_plans ADD COLUMN tenant_id uuid;

-- The org-grain guard the doctrine names: the plan is the
-- organization's own row, never one tenant's.
ALTER TABLE ota_rollout_plans ADD CONSTRAINT ck_ota_rollout_plans_org_grain CHECK (tenant_id IS NULL);
