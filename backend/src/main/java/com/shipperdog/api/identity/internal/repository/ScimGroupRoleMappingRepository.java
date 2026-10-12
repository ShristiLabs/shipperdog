package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.ScimGroupRoleMappingEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScimGroupRoleMappingRepository extends JpaRepository<ScimGroupRoleMappingEntity, UUID> {

    List<ScimGroupRoleMappingEntity> findByOrganizationId(UUID organizationId);

    Optional<ScimGroupRoleMappingEntity> findByOrganizationIdAndGroupName(UUID organizationId, String groupName);
}
