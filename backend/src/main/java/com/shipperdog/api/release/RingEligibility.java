package com.shipperdog.api.release;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The ring-eligibility seam (issue #43, S10-04, FR-017, PRD 8.10/33):
 * the admission arithmetic every consumer of "who may see this release
 * at its current ring" resolves through — the distribution engine's
 * targeting set, the promotion engine's widening check (S10-05), and
 * the portal's tester visibility (the S8-02/S8-03 catalog composition:
 * the ring admits the RELEASE side, the E7 grants still admit the
 * TESTER side; both must pass, the story's technical note).
 *
 * <p>The formula (the issue's second acceptance criterion): at ring X,
 * distribution targets = release targets INTERSECT ring eligibility —
 * the tenant axis first (a target tenant not in the ring's tenant rule
 * is excluded), then the group axis (the ring's group rule narrows a
 * target's audience; a target whose group selection and the ring's
 * rule share no group is excluded with no overlap). A ring with no
 * eligibility rule admits every release target (the seeded standing:
 * rings widen by configuration, never by default).
 *
 * <p>An evaluation is a statement about LIVE state: the release's
 * CURRENT per-target rings (all targets share the starting ring until
 * S10-05's promotion moves them) resolved organization-preferred (the
 * V24 shadowing contract), the ring rules as stored. Unknown and
 * out-of-scope releases answer empty — the concealed miss the caller
 * renders as its own.
 */
public interface RingEligibility {

    /**
     * The release's per-target admission at each target's current ring:
     * every release target with its resolved ring, whether the ring's
     * eligibility admits it, and when admitted the effective audience —
     * the tester groups distribution narrows to (the target's own
     * selection intersected with the ring's group rule; empty means the
     * tenant at large narrowed by nothing, i.e. every effectively-active
     * member).
     */
    Optional<Admission> admissionOf(UUID releaseId);

    /**
     * Whether the current rings admit one tester (the visibility rule):
     * the tester's tenant must be an admitted target tenant, the tester
     * must hold an ACTIVE membership there (the two-switches rule, the
     * same live check the authorization engine honors), and the
     * effective audience must be the tenant at large or contain one of
     * the tester's groups. Empty when the release is unknown or out of
     * the caller's bound scope — the concealed miss; a tester no ring
     * admits answers FALSE (an unknown tester is admitted by nothing,
     * not an error).
     */
    Optional<Boolean> admitsTester(UUID releaseId, UUID testerId);

    /** One evaluation's answer: the release, and one row per target. */
    record Admission(UUID releaseId, String releaseRef, String ring, List<TargetAdmission> targets) {

        /** One target's admission: the tenant, its resolved ring, the verdict, the audience, the reasons. */
        public record TargetAdmission(
                UUID tenantId,
                String ring,
                boolean admitted,
                List<UUID> audienceGroups,
                boolean tenantAtLarge,
                List<String> reasons) {}
    }
}
