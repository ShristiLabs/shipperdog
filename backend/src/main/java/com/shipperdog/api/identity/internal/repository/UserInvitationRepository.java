package com.shipperdog.api.identity.internal.repository;

import com.shipperdog.api.identity.internal.entity.UserInvitationEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserInvitationRepository extends JpaRepository<UserInvitationEntity, UUID> {

    Optional<UserInvitationEntity> findByTokenHash(String tokenHash);

    List<UserInvitationEntity> findByEmailAndStatus(String email, UserInvitationEntity.Status status);

    /**
     * Pending invitations of the platform and org-member flavors for an
     * address (issue #26): tester-flavored invitations are revoked per
     * tester record, not per address, so the address-wide re-invite path
     * must not touch them.
     */
    List<UserInvitationEntity> findByEmailAndStatusAndTesterIdIsNull(String email, UserInvitationEntity.Status status);

    /** The tester's live invitation, if any (issue #26). */
    Optional<UserInvitationEntity> findByTesterIdAndStatus(UUID testerId, UserInvitationEntity.Status status);
}
