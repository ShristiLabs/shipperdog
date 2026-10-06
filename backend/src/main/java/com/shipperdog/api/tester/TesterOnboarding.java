package com.shipperdog.api.tester;

import java.util.UUID;

/**
 * The tester module's onboarding callback for identity (issue #26).
 * The accept flow REUSES the platform registration endpoint
 * ({@code POST /api/v1/auth/register}): when the presented invitation
 * carries a tester scope, identity creates the ACTIVE user account and
 * then activates the tester record through this surface — linking the
 * user, flipping {@code INVITED -> ACTIVE}, and auditing
 * {@code TESTER_ACTIVATED}. Implementation lives in the module's
 * {@code .internal.} packages; identity must use only this interface
 * (enforced by ArchUnit).
 */
public interface TesterOnboarding {

    /**
     * Links the accepted account to the tester and activates it.
     *
     * @param testerId  the invitation's tester record
     * @param userId    the just-created user account
     * @param invitedBy the invitation's issuer (audit trail)
     * @throws NotOnboardingException when the tester record no longer
     *                                exists or is not INVITED (the
     *                                registration must then fail exactly
     *                                like any dead invitation)
     */
    void onInvitationAccepted(UUID testerId, UUID userId, UUID invitedBy);

    /** Raised when the tester record cannot accept an invitation. */
    class NotOnboardingException extends RuntimeException {

        public NotOnboardingException(String message) {
            super(message);
        }
    }
}
