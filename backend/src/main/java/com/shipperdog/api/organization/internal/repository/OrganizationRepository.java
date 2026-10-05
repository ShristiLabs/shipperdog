package com.shipperdog.api.organization.internal.repository;

import com.shipperdog.api.organization.internal.entity.OrganizationEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {

    boolean existsByExternalId(String externalId);

    /**
     * Finds one organization with a row-level lock (SELECT ... FOR
     * UPDATE): admin-affecting member mutations serialize per
     * organization so the last-admin guard cannot race between two
     * concurrent demotions/removals (issue #5 review finding).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrganizationEntity o where o.id = :id")
    Optional<OrganizationEntity> findWithLockingById(@Param("id") UUID id);

    /** Organizations the user belongs to, any role, oldest first, paged (issue #8). */
    @Query(
            value = "select o from OrganizationEntity o, OrganizationMemberEntity m "
                    + "where o.id = m.organizationId and m.userId = :userId",
            countQuery = "select count(o) from OrganizationEntity o, OrganizationMemberEntity m "
                    + "where o.id = m.organizationId and m.userId = :userId")
    Page<OrganizationEntity> findAllForMember(@Param("userId") UUID userId, Pageable pageable);
}
