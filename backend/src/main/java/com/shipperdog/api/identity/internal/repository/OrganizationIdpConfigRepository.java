package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.OrganizationIdpConfigEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationIdpConfigRepository extends JpaRepository<OrganizationIdpConfigEntity, UUID> {

    Optional<OrganizationIdpConfigEntity> findByOrganizationIdAndProviderType(UUID organizationId, String providerType);

    Optional<OrganizationIdpConfigEntity> findFirstByIssuerUrlAndProviderTypeAndEnabledTrue(
            String issuerUrl, String providerType);
}
