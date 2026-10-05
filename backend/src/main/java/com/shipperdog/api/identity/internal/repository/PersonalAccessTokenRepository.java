package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.PersonalAccessTokenEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessTokenEntity, UUID> {

    Optional<PersonalAccessTokenEntity> findByTokenHash(String tokenHash);

    Page<PersonalAccessTokenEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<PersonalAccessTokenEntity> findByIdAndUserId(UUID id, UUID userId);
}
