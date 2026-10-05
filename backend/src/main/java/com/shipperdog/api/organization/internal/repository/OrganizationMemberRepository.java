package com.shipperdog.api.organization.internal.repository;

import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.internal.entity.OrganizationEntity;
import com.shipperdog.api.organization.internal.entity.OrganizationMemberEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMemberEntity, UUID> {

    /** Earliest memberships of the user in organizations with the given status. */
    @Query("select m from OrganizationMemberEntity m, OrganizationEntity o "
            + "where m.organizationId = o.id and m.userId = :userId and o.status = :status "
            + "order by m.createdAt asc, m.id asc")
    List<OrganizationMemberEntity> findByUserIdAndOrganizationStatus(
            @Param("userId") UUID userId, @Param("status") OrganizationEntity.Status status, Pageable pageable);

    boolean existsByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    Optional<OrganizationMemberEntity> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    boolean existsByOrganizationIdAndUserIdAndRole(UUID organizationId, UUID userId, OrgRole role);

    Page<OrganizationMemberEntity> findByOrganizationIdOrderByCreatedAtAscIdAsc(UUID organizationId, Pageable pageable);

    long countByOrganizationIdAndRole(UUID organizationId, OrgRole role);

    long countByUserId(UUID userId);

    /** Memberships of the user in organizations with the given status. */
    @Query("select count(m) from OrganizationMemberEntity m, OrganizationEntity o "
            + "where m.organizationId = o.id and m.userId = :userId and o.status = :status")
    long countByUserIdAndOrganizationStatus(
            @Param("userId") UUID userId, @Param("status") OrganizationEntity.Status status);
}
