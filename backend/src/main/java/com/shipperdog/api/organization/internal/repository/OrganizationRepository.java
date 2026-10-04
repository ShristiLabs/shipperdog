package com.shipperdog.api.organization.internal.repository;

import com.shipperdog.api.organization.internal.entity.OrganizationEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {

    boolean existsByExternalId(String externalId);

    /** Organizations the user belongs to, any role, oldest first. */
    @Query("select o from OrganizationEntity o, OrganizationMemberEntity m "
            + "where o.id = m.organizationId and m.userId = :userId")
    List<OrganizationEntity> findAllForMember(@Param("userId") UUID userId, Sort sort);
}
