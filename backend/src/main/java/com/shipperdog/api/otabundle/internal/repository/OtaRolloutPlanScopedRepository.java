package com.shipperdog.api.otabundle.internal.repository;

import com.shipperdog.api.otabundle.internal.entity.OtaRolloutPlanEntity;
import com.shipperdog.api.tenant.TenantScopedRepository;

/**
 * Seam-served access to {@code ota_rollout_plans} (S16-06, ADR-0002):
 * the plan rows are org-owned (V60's NULL {@code tenant_id} column
 * beside V56's organization), so every read and write composes
 * through the seam bound in ORGANIZATION scope -- the org predicate
 * ({@code tenantId IS NULL AND organizationId = :context}) rides the
 * mapped attributes by construction. Declares no methods of its own
 * by design: derived queries on seam repositories are rejected by the
 * architecture tests, and extra conditions arrive as {@code
 * Specification} parameters instead.
 */
public interface OtaRolloutPlanScopedRepository extends TenantScopedRepository<OtaRolloutPlanEntity> {}
