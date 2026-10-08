package com.shipperdog.api.release;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The promotion policy seam (issue #44, S10-05): the extension point
 * where S10-06's policy engine composes onto the promotion engine —
 * the {@link ReleaseTransitionPolicy} shape applied to ring hops. Every
 * registered contributor is consulted by the promotion precheck before
 * a hop commits; an empty {@link #denial} permits, a present value is
 * one blocker of the complete list the 409 answers (keyed by the
 * contributor's name, the transition seam's rendering).
 *
 * <p>The FIRST registrants are S10-06's (issue #45): the declarative
 * policy engine (BLOCK/HOLD rules over the issue's decision inputs,
 * the evaluation logged per attempt) and the skip-relaxation knob it
 * carries (an organization's named skip allowances widen the walk).
 * The ring's {@code promotionPolicy} AUTOMATIC standing (V32's
 * column) stays reference data no registrant acts on: the scheduler
 * that would watch {@link ReleasePromotedEvent} and drive governed
 * hops without an operator is a later story's build (V3's automated
 * promotion), not the declarative v1 this story ships.
 *
 * <p>Contributors see only this interface — tuples, never rows (the
 * ArchUnit boundary; the check carries the hop's complete self-
 * contained facts).
 */
public interface ReleasePromotionPolicy {

    /**
     * Whether the hop may commit: empty permits; a present value is
     * the human-readable refusal rendered in the promotion 409's
     * {@code errors} member keyed by the contributor's name.
     */
    Optional<String> denial(PromotionCheck check);

    /**
     * Whether this contributor admits the NAMED SKIP the request
     * carries: a {@code to} ring BEYOND the effective order's next
     * (never backward — rollback is the transitions route's own
     * crossing). The default REFUSES: one-ring-at-a-time is the #44
     * posture, and it stands for every organization unless every
     * registered contributor admits the wider hop (fail-closed
     * composition — one refusing contributor keeps the refusal). The
     * skip consult addresses the NAMED ring as {@code toRing}.
     */
    default boolean admitsSkip(PromotionCheck check) {
        return false;
    }

    /**
     * Why the engine is asking (S10-06): a contributor with governed
     * side effects — the policy engine's evaluation log and hold
     * ledger — writes ONLY on the promote ATTEMPT; the precheck read
     * and the hop's in-transaction revalidation answer the same
     * evaluation as pure reads (one log row per attempt, exactly).
     */
    enum Purpose {
        /** The precheck read (or a dry-run): the same evaluation, nothing written. */
        PRECHECK,
        /** The promote act: governed writes (the evaluation log, the hold ledger) are sanctioned. */
        ATTEMPT,
        /** The hop's in-transaction revalidation: a pure read (the ATTEMPT phase already logged). */
        RE_COMMIT
    }

    /**
     * The hop under consideration — the release's identity and owning
     * organization, the two rings (the effective codes), the tenant
     * set the hop would move, the actor, and the asking surface: a
     * policy answers from facts it may read through public ports,
     * never by reaching into the release module's storage.
     */
    record PromotionCheck(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            UUID organizationId,
            String fromRing,
            String toRing,
            List<UUID> tenants,
            UUID actorId,
            Purpose purpose) {}
}
