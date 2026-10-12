package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.ScimTokenEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScimTokenRepository extends JpaRepository<ScimTokenEntity, UUID> {

    /**
     * The live token of the organization (one row per organization by
     * the V51 UNIQUE constraint; generating a new one replaces it).
     */
    Optional<ScimTokenEntity> findByOrganizationId(UUID organizationId);

    /** The token row a presented bearer credential hashes to. */
    Optional<ScimTokenEntity> findByTokenHash(String tokenHash);
}
