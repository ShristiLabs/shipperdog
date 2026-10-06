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
 * Declares ONLY the one sanctioned question — a subject's grants for
 * one application through one tenant, one descent of
 * {@code idx_version_access_grant_subject} — and answers rows the
 * caller filters by expiry and shape; never the inherited
 * {@code findById} family, and never an endpoint response.
 */
@CrossTenantByDesign
public interface VersionAccessGrantCatalog extends JpaRepository<VersionAccessGrantEntity, UUID> {

    /**
     * Every grant (any expiry — the caller filters at evaluation
     * time) the tester holds for the application through the tenant.
     */
    List<VersionAccessGrantEntity> findByTenantIdAndTesterIdAndApplicationId(
            UUID tenantId, UUID testerId, UUID applicationId);

    /**
     * The shared-application decision probe (issue #31 AC2): the
     * subject's grants for the application across tenants — one
     * {@code idx_version_access_tester_application} descent. For an
     * organization-shared application the engine's owning tenant is
     * null, so the contributor resolves by subject + application and
     * matches each grant's own tenant live (effective membership and
     * the S3-03 mapping) — never an endpoint data path.
     */
    List<VersionAccessGrantEntity> findByTesterIdAndApplicationId(UUID testerId, UUID applicationId);
}
