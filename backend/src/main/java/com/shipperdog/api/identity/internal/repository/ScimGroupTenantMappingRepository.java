package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.ScimGroupTenantMappingEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScimGroupTenantMappingRepository extends JpaRepository<ScimGroupTenantMappingEntity, UUID> {

    List<ScimGroupTenantMappingEntity> findByOrganizationId(UUID organizationId);

    List<ScimGroupTenantMappingEntity> findByOrganizationIdAndGroupName(UUID organizationId, String groupName);
}
