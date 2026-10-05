package com.shipperdog.api.application.internal.repository;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.application.internal.entity.ApplicationEntity;
import com.shipperdog.api.tenant.CrossTenantByDesign;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The organization-scope registry guard over {@code applications}
 * (issue #13): deliberately cross-tenant, explicitly marked
 * {@link CrossTenantByDesign} per ADR-0002 (second amendment).
 *
 * <p>Two sanctioned questions span tenants by the issue's own
 * definition, and both are answered here, review-critical and in one
 * place:
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
 * </ul>
 *
 * <p>Like every {@link CrossTenantByDesign} repository, this interface
 * uses only its own declared derived queries — the inherited
 * {@code JpaRepository} surface stays seam-enforced for
 * {@code TenantScope} entities (ADR-0002 amendment) and would refuse
 * without a bound context. This repository must never serve endpoint
 * data paths: reads and writes of application rows go through
 * {@link ApplicationScopedRepository}, the tenant-isolation seam. An
 * architecture test pairs this marker with the seam rule.
 */
@CrossTenantByDesign
public interface ApplicationRegistryRepository extends JpaRepository<ApplicationEntity, UUID>, ApplicationAccess {

    /**
     * Registry lookup by id for ownership resolution — a declared query,
     * deliberately not the inherited (seam-enforced) {@code findById}.
     */
    Optional<ApplicationEntity> findApplicationById(UUID id);

    /** Within-scope name guard (uq_applications_scope_name). */
    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

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
