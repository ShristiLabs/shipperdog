package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.ScimUserEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScimUserRepository extends JpaRepository<ScimUserEntity, UUID> {

    Optional<ScimUserEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The externalId join: the IdP's stable person id within this organization. */
    Optional<ScimUserEntity> findByOrganizationIdAndExternalId(UUID organizationId, String externalId);

    Optional<ScimUserEntity> findByOrganizationIdAndUserName(UUID organizationId, String userName);

    Optional<ScimUserEntity> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    /** The organization's provisioned users, listing order stable (created, id). */
    List<ScimUserEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    long countByOrganizationId(UUID organizationId);
}
