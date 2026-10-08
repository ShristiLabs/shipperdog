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
 * <p>NO CONTRIBUTOR SHIPS WITH THIS STORY — the seam is the hook the
 * issue's scope line names ("policy rules S10-06"). S10-06's engine is
 * the intended registrant, answering at minimum:
 *
 * <ul>
 *   <li><strong>Skip relaxation</strong> — the ring-skip refusal is
 *       the DEFAULT posture this story pins (promotion moves exactly
 *       one ring in the effective order; PRD 17's progression is
 *       governed, never leapt); an organization policy that admits
 *       named skips registers here and widens the walk.</li>
 *   <li><strong>AUTOMATIC promotion</strong> — the ring's
 *       {@code promotionPolicy} AUTOMATIC standing (V32's column) is
 *       reference data this story reads but never acts on: the
 *       scheduler that watches {@link ReleasePromotedEvent} and drives
 *       governed hops without an operator is S10-06's build.</li>
 * </ul>
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
     * The hop under consideration — the release's identity and owning
     * organization, the two rings (the effective codes), and the
     * tenant set the hop would move: a policy answers from facts it
     * may read through public ports, never by reaching into the
     * release module's storage.
     */
    record PromotionCheck(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            UUID organizationId,
            String fromRing,
            String toRing,
            List<UUID> tenants) {}
}
