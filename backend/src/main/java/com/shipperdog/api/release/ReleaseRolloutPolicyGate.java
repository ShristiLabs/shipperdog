package com.shipperdog.api.release;

import java.util.Optional;
import java.util.UUID;

/**
 * The release module's public surface for the rollout policy consult
 * (issue #65, S13-05, AC4, PRD 33): the SAME declarative rule engine
 * the promotion precheck runs (S10-06), evaluated for a staged
 * rollout's percentage INCREASE on the ring the rollout serves — the
 * production ring's BLOCK and HOLD rules decide whether the curve may
 * widen while the release's metrics fail their thresholds, exactly
 * the promotion-question's wording applied to the rollout's own
 * promotion-grade act.
 *
 * <p>The consult is attempt-grade: the evaluation logs (one row with
 * its per-rule outcomes, the #45 discipline), so a refused advance
 * leaves the same evidence trail a refused promotion does. The
 * evaluation and hold writes commit in the consult's OWN transaction —
 * a refused advance's 409 rolls the control back but never its
 * evidence. HALT never consults — stopping distribution is always
 * allowed; the gate exists so a bad release stops reaching users,
 * never to keep one going.
 *
 * <p>Implementation lives in the module's {@code .internal.} packages;
 * cross-module callers must use only this interface (enforced by
 * ArchUnit).
 */
public interface ReleaseRolloutPolicyGate {

    /**
     * The policy refusal of a rollout increase on the named ring, if
     * any: the winning BLOCK rule's reference and evidence, or the
     * pending HOLD that no privileged actor has acknowledged — the
     * same composed refusal the promotion 409 renders. Empty when the
     * organization's rules allow the increase (or none are authored).
     * Fail-closed: a release the engine cannot resolve answers the
     * honest refusal, never a silent pass.
     *
     * @param organizationId the release's owning organization (the
     *                       rule scope's grain)
     * @param releaseId      the release whose rollout would widen
     * @param ringCode       the effective-order code of the ring the
     *                       rollout serves (the rules' entry-ring
     *                       scope)
     * @param actorId        the operator advancing the curve (the
     *                       evaluation's actor; null for a system
     *                       consult)
     */
    Optional<String> rolloutDenialOf(UUID organizationId, UUID releaseId, String ringCode, UUID actorId);
}
