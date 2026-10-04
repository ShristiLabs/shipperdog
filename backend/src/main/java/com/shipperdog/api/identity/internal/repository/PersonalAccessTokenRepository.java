package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.PersonalAccessTokenEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessTokenEntity, UUID> {

    Optional<PersonalAccessTokenEntity> findByTokenHash(String tokenHash);

    List<PersonalAccessTokenEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<PersonalAccessTokenEntity> findByIdAndUserId(UUID id, UUID userId);
}
