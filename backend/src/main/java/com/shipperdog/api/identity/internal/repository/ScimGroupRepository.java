package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.ScimGroupEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScimGroupRepository extends JpaRepository<ScimGroupEntity, UUID> {

    Optional<ScimGroupEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<ScimGroupEntity> findByOrganizationIdAndDisplayName(UUID organizationId, String displayName);

    Optional<ScimGroupEntity> findByOrganizationIdAndExternalId(UUID organizationId, String externalId);

    List<ScimGroupEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    long countByOrganizationId(UUID organizationId);
}
