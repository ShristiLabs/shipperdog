package com.shipperdog.api.otabundle.internal.repository;

import com.shipperdog.api.otabundle.internal.entity.OtaAlertRuleEntity;
import com.shipperdog.api.tenant.TenantScopedRepository;

/**
 * Seam-served access to {@code ota_alert_rules} (S16-11, ADR-0002):
 * the rule rows are org-owned (V61's NULL {@code tenant_id} column
 * beside V56's organization), so every read and write composes
 * through the seam bound in ORGANIZATION scope -- the org predicate
 * ({@code tenantId IS NULL AND organizationId = :context}) rides the
 * mapped attributes by construction. Declares no methods of its own
 * by design: derived queries on seam repositories are rejected by the
 * architecture tests, and extra conditions arrive as {@code
 * Specification} parameters instead. The scheduler's cross-organization
 * pass does not ride this seam at all (it reads through JDBC, the
 * {@code MetricRollupService.runLookback} posture -- a platform-side
 * job has no single organization context to bind).
 */
public interface OtaAlertRuleScopedRepository extends TenantScopedRepository<OtaAlertRuleEntity> {}
