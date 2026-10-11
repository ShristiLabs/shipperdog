package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.ScimConfigEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScimConfigRepository extends JpaRepository<ScimConfigEntity, UUID> {

    Optional<ScimConfigEntity> findByOrganizationId(UUID organizationId);
}
