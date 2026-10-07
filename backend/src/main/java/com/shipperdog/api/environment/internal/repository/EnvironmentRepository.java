package com.shipperdog.api.environment.internal.repository;

import com.shipperdog.api.environment.internal.entity.EnvironmentEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage for the environment rows (V1 + V27). A plain
 * {@link JpaRepository} by design — environments are organization-grain
 * (the tenth amendment's pattern, the rings/testers precedent): the row
 * carries the organization dimension and no tenant dimension, so there
 * is nothing for the isolation seam's predicate to filter by. Every
 * question is an explicit organization-scoped query or an id lookup the
 * service pairs with an organization check (the testers module's
 * backstop shape).
 */
public interface EnvironmentRepository extends JpaRepository<EnvironmentEntity, UUID> {

    /** The listing (S9-04): the organization's environments in progression order, then code. */
    Page<EnvironmentEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    /** The duplicate pre-checks of creation (the database guards remain the authority). */
    Optional<EnvironmentEntity> findByOrganizationIdAndCode(UUID organizationId, String code);

    Optional<EnvironmentEntity> findByOrganizationIdAndName(UUID organizationId, String name);

    /** The provisioning callback's idempotence probe (skip a default whose code is taken). */
    boolean existsByOrganizationIdAndCode(UUID organizationId, String code);

    /**
     * The row under a pessimistic write lock: the config, assignment,
     * and binding writes serialize on the environment itself, so the
     * ledger's next version and each replace-set's diff are race-free
     * without retry loops (one lock, no ordering, no deadlock).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EnvironmentEntity e where e.id = :id")
    Optional<EnvironmentEntity> findWithWriteLockById(@Param("id") UUID id);

    /**
     * The current config version of each environment (one grouped
     * query per listing page; environments never configured answer 0
     * by absence, the honest empty).
     */
    @Query("select v.environmentId, max(v.version) from EnvironmentConfigVersionEntity v "
            + "where v.environmentId in :environmentIds group by v.environmentId")
    List<Object[]> currentVersions(@Param("environmentIds") List<UUID> environmentIds);
}
