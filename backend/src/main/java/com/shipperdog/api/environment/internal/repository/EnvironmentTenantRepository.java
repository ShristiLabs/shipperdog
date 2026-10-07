package com.shipperdog.api.environment.internal.repository;

import com.shipperdog.api.environment.internal.entity.EnvironmentTenantEntity;
import com.shipperdog.api.environment.internal.entity.EnvironmentTenantId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the tenant-assignment junction (V27, AC4). A plain
 * {@link JpaRepository} — the {@code ReleaseTargetRepository} plain
 * child doctrine: every caller resolves the environment through its
 * organization-checked path first, then asks about exactly that
 * environment's rows. The assignment write replaces the set; the
 * release surface's creation validation reads the set in bulk through
 * the module's public port.
 */
public interface EnvironmentTenantRepository extends JpaRepository<EnvironmentTenantEntity, EnvironmentTenantId> {

    /** The assigned tenants of one environment, tenant-ordered for the stable view. */
    List<EnvironmentTenantEntity> findByEnvironmentIdOrderByTenantIdAsc(UUID environmentId);

    /** The paginated read of one environment's assignments (the list-endpoint convention). */
    Page<EnvironmentTenantEntity> findByEnvironmentId(UUID environmentId, Pageable pageable);

    /** Deletes the removed half of a replace-set assignment (never individually addressed). */
    void deleteByEnvironmentIdAndTenantIdIn(UUID environmentId, List<UUID> tenantIds);
}
