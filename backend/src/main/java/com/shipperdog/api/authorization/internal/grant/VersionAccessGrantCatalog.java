package com.shipperdog.api.authorization.internal.grant;

import com.shipperdog.api.tenant.CrossTenantByDesign;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Cross-tenant, by-design lookups over {@code version_access} (issue
 * #31, S7-02) — the grant analog of the role-resolution catalogs the
 * engine already reads: the E7 chain's decision-time probe runs
 * without a bound tenant context (the artifact read and the
 * inspection surface are not tenant-path routes), so the seam
 * repository cannot serve it.
 *
 * <p>Marked {@link CrossTenantByDesign} so the isolation architecture
 * test accepts it as authorization-engine surface; endpoint data
 * paths use {@link VersionAccessGrantScopedRepository} instead
 * (the {@code TenantTesterMembershipCatalog} precedent exactly).
 * Declares ONLY the sanctioned questions — a subject's grants for one
 * application through one tenant, and the per-tenant set the decision
 * cache loads — and answers rows the caller filters by status and
 * expiry (S7-04: a REVOKED row never authorizes; the filters are the
 * callers' contracts, pinned by tests); never the inherited
 * {@code findById} family, and never an endpoint response.
 */
@CrossTenantByDesign
public interface VersionAccessGrantCatalog extends JpaRepository<VersionAccessGrantEntity, UUID> {

    /**
     * Every grant (any status or expiry — the caller filters at
     * evaluation time) the tester holds for the application through
     * the tenant.
     */
    List<VersionAccessGrantEntity> findByTenantIdAndTesterIdAndApplicationId(
            UUID tenantId, UUID testerId, UUID applicationId);

    /**
     * Every grant the tester holds through the tenant, all
     * applications (issue #32, S7-03): the decision-input cache's
     * loader — one prefix descent of
     * {@code idx_version_access_grant_subject} (the composite probe
     * index's tenant + tester prefix), filtered by status and
     * application in memory. The per-tenant SET is the cached unit per
     * the story's technical note (a grant mutation drops exactly this
     * key); the application filter stays at evaluation time so one
     * entry serves every application the tenant granted. Since S7-04
     * (issue #33) the loader caches ACTIVE grants ONLY — a revoked row
     * is history, never a decision input; authorization data only,
     * never an endpoint data path.
     */
    List<VersionAccessGrantEntity> findByTenantIdAndTesterId(UUID tenantId, UUID testerId);
}
