package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.UserInvitationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserInvitationRepository extends JpaRepository<UserInvitationEntity, UUID> {

    Optional<UserInvitationEntity> findByTokenHash(String tokenHash);

    List<UserInvitationEntity> findByEmailAndStatus(String email, UserInvitationEntity.Status status);
}
