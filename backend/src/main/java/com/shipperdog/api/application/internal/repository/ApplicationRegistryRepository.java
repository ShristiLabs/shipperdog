package com.shipperdog.api.application.internal.repository;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.application.internal.entity.ApplicationEntity;
import com.shipperdog.api.application.internal.entity.ApplicationEntity.OwnershipScope;
import com.shipperdog.api.application.internal.entity.TenantApplicationAuthorizationEntity;
import com.shipperdog.api.tenant.CrossTenantByDesign;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The organization-scope registry guard over {@code applications}
 * (issue #13): deliberately cross-tenant, explicitly marked
 * {@link CrossTenantByDesign} per ADR-0002 (second amendment).
 *
 * <p>Three sanctioned operations span tenants by the issues' own
 * definition, all declared here, review-critical and in one place:
 *
 * <ul>
 *   <li><strong>Ownership resolution</strong> (via {@link ApplicationAccess})
 *       — which organization and tenant own an application. This is
 *       authorization data: the RBAC evaluator's {@code app:} branch and
 *       the tenant module's route-context binding resolve through it.</li>
 *   <li><strong>Organization-wide uniqueness guards</strong> — the
 *       bundle-id/package-name/name uniqueness of issue #13's acceptance
 *       criterion 2 is per organization+platform, so the pre-checks and
 *       the post-race re-derivation necessarily span the tenants of one
 *       organization. Every creator of an application holds an
 *       organization-level role ({@code app:applications:write} is
 *       org-role-granted only), so the probes reveal nothing the caller
 *       is not entitled to know at organization scope; they answer
 *       existence booleans, never rows.</li>
 *   <li><strong>The ownership-scope transition</strong> (issue #14, S3-02)
 *       — converting an application between TENANT and ORGANIZATION
 *       ownership moves the row between scopes by definition: no single
 *       scoped context can own both sides of the write, so the seam's
 *       write guard must refuse it everywhere. The single declared,
 *       id-parameterized transition below is the one sanctioned write,
 *       called only from the service after its precondition checks and
 *       audited as {@code APPLICATION_SCOPE_CHANGED} (ADR-0002 third
 *       amendment).</li>
 *   <li><strong>Effective-visibility resolution</strong> (issue #15,
 *       S3-03, PRD 15) — "which applications may tenant T see?" is
 *       tenant-owned rows UNION shared rows the tenant is authorized
 *       for: it spans the tenants of one organization by definition (one
 *       arm of the union is null-tenant organization rows, invisible to
 *       any tenant-scoped query). The declared union query below serves
 *       the portal/catalog listing and is the single resolution point
 *       E7/E8 reuse; it answers through the mapping's ACTIVE grants
 *       only, so an unauthorized tenant learns nothing.</li>
 * </ul>
 *
 * <p>Like every {@link CrossTenantByDesign} repository, this interface
 * uses only its own declared queries — the inherited
 * {@code JpaRepository} surface stays seam-enforced for
 * {@code TenantScope} entities (ADR-0002 amendment) and would refuse
 * without a bound context. Beyond the sanctioned transition above, this
 * repository must never serve endpoint data paths: reads and writes of
 * application rows go through {@link ApplicationScopedRepository}, the
 * tenant-isolation seam. An architecture test pairs this marker with
 * the seam rule.
 */
@CrossTenantByDesign
public interface ApplicationRegistryRepository extends JpaRepository<ApplicationEntity, UUID>, ApplicationAccess {

    /**
     * Registry lookup by id for ownership resolution — a declared query,
     * deliberately not the inherited (seam-enforced) {@code findById}.
     */
    Optional<ApplicationEntity> findApplicationById(UUID id);

    /**
     * The sanctioned ownership-scope transition (issue #14, AC3): moves
     * one row between scopes — setting ORGANIZATION nulls the owning
     * tenant, setting TENANT installs the new owner. Single-row by id,
     * executed by {@code ApplicationService} only after its INV-002 and
     * precondition checks, and audited as
     * {@code APPLICATION_SCOPE_CHANGED}. Returns the rows moved (1, or 0
     * when the application vanished concurrently).
     */
    @Modifying
    @Query("update ApplicationEntity a set a.ownershipScope = :scope, a.tenantId = :tenantId, a.updatedAt = :updatedAt"
            + " where a.id = :id")
    int transitionScope(
            @Param("id") UUID id,
            @Param("scope") OwnershipScope scope,
            @Param("tenantId") UUID tenantId,
            @Param("updatedAt") Instant updatedAt);

    /** Organization-wide name guard (uq_applications_organization_name, V10). */
    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    /**
     * The effective-visibility resolution (issue #15, AC4, PRD 15): the
     * applications of one organization visible to the tenant — its
     * tenant-owned rows plus the organization-shared rows carrying an
     * ACTIVE authorization for the tenant. The single query behind the
     * portal/catalog listing (reused by E7/E8); platform listing order
     * (createdAt ASC, id ASC) is pinned in the query so pagination is
     * deterministic without the seam's default-sort machinery.
     */
    @Query("select a from ApplicationEntity a where a.organizationId = :organizationId and (a.tenantId = :tenantId"
            + " or (a.ownershipScope = :sharedScope and a.id in (select m.applicationId"
            + " from TenantApplicationAuthorizationEntity m where m.tenantId = :tenantId"
            + " and m.status = :authorizedStatus))) order by a.createdAt asc, a.id asc")
    Page<ApplicationEntity> findVisibleToTenant(
            @Param("organizationId") UUID organizationId,
            @Param("tenantId") UUID tenantId,
            @Param("sharedScope") OwnershipScope sharedScope,
            @Param("authorizedStatus") TenantApplicationAuthorizationEntity.Status authorizedStatus,
            Pageable pageable);

    /** Name guard excluding the application being updated. */
    boolean existsByOrganizationIdAndNameAndIdNot(UUID organizationId, String name, UUID id);

    /** iOS identifier guard (uq_applications_organization_bundle). */
    boolean existsByOrganizationIdAndBundleId(UUID organizationId, String bundleId);

    /** iOS identifier guard excluding the application being updated. */
    boolean existsByOrganizationIdAndBundleIdAndIdNot(UUID organizationId, String bundleId, UUID id);

    /** Android identifier guard (uq_applications_organization_package). */
    boolean existsByOrganizationIdAndPackageName(UUID organizationId, String packageName);

    /** Android identifier guard excluding the application being updated. */
    boolean existsByOrganizationIdAndPackageNameAndIdNot(UUID organizationId, String packageName, UUID id);

    /** Ownership resolution for the evaluator and route binding; tuples only. */
    @Override
    default Optional<Ownership> ownershipOf(UUID applicationId) {
        return findApplicationById(applicationId)
                .map(application ->
                        new Ownership(application.getId(), application.getOrganizationId(), application.getTenantId()));
    }
}
