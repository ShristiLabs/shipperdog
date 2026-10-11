package com.shipperdog.api.tester;

import java.util.Optional;
import java.util.UUID;

/**
 * The tester module's linked-record lifecycle surface for identity's
 * SCIM automation (S14-03, issue #69): the leaver flow's tester half.
 * When an identity provider deprovisions an account, a linked tester
 * record crosses to DISABLED — the S6-04 cascade that suspends every
 * tester membership and makes the tester's grants inert — and a
 * re-provision re-activates the person record (the memberships stay
 * suspended per the S6-04 manual-restoration doctrine; the IdP's
 * re-provision is the org's authoritative onboarding signal). Implementation
 * lives in the module's {@code .internal.} packages; identity must use
 * only this interface (enforced by ArchUnit).
 */
public interface TesterDeprovisioning {

    /**
     * Disables the tester record linked to the user, when one exists
     * and is not already DISABLED. The mandatory deactivation reason
     * and the membership-suspension cascade ride {@code changeStatus}
     * unchanged. Empty when the user has no tester record; the tester
     * id when a record exists (already DISABLED answers the id too —
     * the state is the fact, not the transition).
     */
    Optional<UUID> deactivateLinked(UUID userId, String reason, UUID actingUserId);

    /**
     * Re-activates the DISABLED tester record linked to the user, when
     * one exists. Empty when there is no linked record or it is not
     * DISABLED (an already-active record needs nothing; a SUSPENDED
     * record belongs to the tester administration routes). The
     * memberships stay SUSPENDED — restoration is a manual per-tenant
     * re-enable, never silent (S6-04).
     */
    Optional<UUID> reactivateLinked(UUID userId, UUID actingUserId);
}
