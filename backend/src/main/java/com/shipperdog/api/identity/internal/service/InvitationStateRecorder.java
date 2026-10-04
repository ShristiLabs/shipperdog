package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.identity.internal.entity.UserInvitationEntity;
import com.shipperdog.api.identity.internal.repository.UserInvitationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists invitation state transitions in its own transaction. The
 * registration flow rejects an expired invitation by throwing, which
 * rolls its transaction back — so the EXPIRED marking must happen in a
 * separate transaction that survives that rollback.
 */
@Service
public class InvitationStateRecorder {

    private final UserInvitationRepository invitations;

    InvitationStateRecorder(UserInvitationRepository invitations) {
        this.invitations = invitations;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExpired(UUID invitationId) {
        invitations.findById(invitationId).ifPresent(invitation -> {
            invitation.setStatus(UserInvitationEntity.Status.EXPIRED);
            invitations.save(invitation);
        });
    }
}
