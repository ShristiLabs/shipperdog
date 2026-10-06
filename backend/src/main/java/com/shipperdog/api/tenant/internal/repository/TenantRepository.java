package com.shipperdog.api.tenant.internal.repository;

import com.shipperdog.api.tenant.internal.entity.TenantEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<TenantEntity, UUID> {

    /** Globally-unique customer-system mapping guard (uq_tenants_external_id). */
    boolean existsByExternalId(String externalId);

    /** Backing lookup for the guard; the update path excludes the tenant itself. */
    Optional<TenantEntity> findByExternalId(String externalId);

    /** Within-organization identifier guard (uq_tenants_org_name). */
    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    /** The organization's tenants, unpaged (issue #27 TenantAccess hop). */
    List<TenantEntity> findByOrganizationId(UUID organizationId);

    /** Organization-scoped listing (issue #9); organizations own their tenants. */
    Page<TenantEntity> findByOrganizationIdOrderByCreatedAtAscIdAsc(UUID organizationId, Pageable pageable);

    /** Organization-scoped listing filtered by lifecycle status (issue #9). */
    Page<TenantEntity> findByOrganizationIdAndStatusOrderByCreatedAtAscIdAsc(
            UUID organizationId, TenantEntity.Status status, Pageable pageable);
}
