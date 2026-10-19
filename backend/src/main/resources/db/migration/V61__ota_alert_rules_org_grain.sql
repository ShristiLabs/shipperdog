-- V61__ota_alert_rules_org_grain.sql
-- The one gap-fix the S16-11 alert build surfaced over V56's frozen
-- alert pair (issue #173, ADR-0010 D10): ota_alert_rules is an
-- org-grain row (a rule watches an application's whole OTA funnel --
-- the device population's grain, exactly the rollout plans' own
-- doctrine) but V56 omitted the NULL tenant_id column the
-- org-owned doctrine presupposes. The tenant-isolation seam (ADR
-- 0002) composes its organization-scope predicate
-- (tenantId IS NULL AND organizationId = :context) on MAPPED
-- attributes, so an org-grain row without the column cannot ride the
-- seam at all -- V60's own words over ota_rollout_plans, the exact
-- same gap. Expand-only ADD COLUMN, always NULL by the service (the
-- rule serves the application's whole device population, never one
-- tenant's), precisely the shape V58's ota_signing_keys, V60's
-- ota_rollout_plans, and every other org-scoped row carries.
--
-- ota_alert_firings needs no such column by design: a firing row is
-- addressed only through its rule (the join carries the scope), the
-- ota_rollout_events doctrine -- a plain repository over an
-- append-mostly table whose every caller resolves the owning rule
-- through the seam first.
--
-- Conventions (see docs/database-migrations.md):
--   * Expand-only, forward-only migration (ADR-0001): one new
--     nullable column on a this-milestone table; nothing existing is
--     dropped, renamed, or re-typed.
--   * Constraint names keep the explicit ck_ prefix.

ALTER TABLE ota_alert_rules ADD COLUMN tenant_id uuid;

-- The org-grain guard the doctrine names: the rule is the
-- organization's own row, never one tenant's.
ALTER TABLE ota_alert_rules ADD CONSTRAINT ck_ota_alert_rules_org_grain CHECK (tenant_id IS NULL);
