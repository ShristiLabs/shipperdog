package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.UserIdpIdentityEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserIdpIdentityRepository extends JpaRepository<UserIdpIdentityEntity, UUID> {

    Optional<UserIdpIdentityEntity> findByOrganizationIdAndSubject(UUID organizationId, String subject);

    Optional<UserIdpIdentityEntity> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);
}
