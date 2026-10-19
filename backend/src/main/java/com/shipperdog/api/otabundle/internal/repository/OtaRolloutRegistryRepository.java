package com.shipperdog.api.otabundle.internal.repository;

import com.shipperdog.api.otabundle.internal.entity.OtaRolloutPlanEntity;
import com.shipperdog.api.tenant.CrossTenantByDesign;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The sanctioned cross-scope point probe over {@code
 * ota_rollout_plans} (S16-06, issue #168): deliberately cross-scope,
 * explicitly marked {@link CrossTenantByDesign} per ADR-0002 (the
 * registry-repository pattern the release module established, issue
 * #56) — the S16-07 device-check route's rollout resolution runs on a
 * device surface (the {@code sdc1.} filter's request) whose thread
 * binds no operator scope, by construction not the plan's owning
 * organization's, so the seam-served repository cannot answer and must
 * not be asked to.
 *
 * <p>The probe is consumed ONLY through {@code
 * OtaRolloutAccess.rolloutStandingOf}, which answers the standing
 * TUPLE (status, serving stage, basis points, salt) — authorization
 * data for the check route's composition, never a row to a caller,
 * never an endpoint data path. Reads and writes of the plan rows go
 * through {@code OtaRolloutPlanScopedRepository}, the tenant-isolation
 * seam; an architecture test pairs this marker with the seam rule.
 */
@CrossTenantByDesign
public interface OtaRolloutRegistryRepository extends JpaRepository<OtaRolloutPlanEntity, UUID> {

    /**
     * The release's plan (V56's one-plan-per-release unique): the
     * device-check resolution's probe. Empty when the release carries
     * no plan.
     */
    Optional<OtaRolloutPlanEntity> findByReleaseId(UUID releaseId);
}
