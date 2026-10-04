package com.shipperdog.api.organization.internal.repository;

import com.shipperdog.api.organization.internal.entity.OrganizationIdpConfigEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationIdpConfigRepository extends JpaRepository<OrganizationIdpConfigEntity, UUID> {

    Optional<OrganizationIdpConfigEntity> findByOrganizationId(UUID organizationId);
}
