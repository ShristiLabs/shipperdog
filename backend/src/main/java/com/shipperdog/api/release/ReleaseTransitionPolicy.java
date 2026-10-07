package com.shipperdog.api.release;

import java.util.Optional;
import java.util.UUID;

/**
 * The transition policy seam (issue #56, S9-02): the extension point
 * where the stories that own a transition's side conditions compose
 * onto the state machine without the machine learning them. Every
 * registered contributor is consulted before a legal crossing commits
 * (after the table's own legality answer — the policy never overrides
 * PRD 17's graph, it only refuses crossings its story would allow to
 * happen yet); an empty {@link #denial} permits.
 *
 * <p>The first registered contributor is E10/S10-01's approval
 * evaluation: the crossing the issue names — "APPROVED requires
 * approvals complete per workflow" — is APPROVAL_PENDING to APPROVED,
 * and that story's evaluator answers it here. Until it registers, the
 * machine permits the crossing to the roles the API authorizes
 * ({@code app:releases:manage}): M2 has no approval model to consult,
 * and a seam that denied by default would block the machine this story
 * exists to build. Distribution-execution preconditions (E13) and gate
 * checks (E11) compose on the same seam when they land.
 *
 * <p>Implementation lives in the module's {@code .internal.} packages;
 * contributors may live anywhere (they see only this interface —
 * tuples, never rows). A denial is a 422 against the crossing, never a
 * rewrite of the machine: the caller fixes the condition and retries.
 */
public interface ReleaseTransitionPolicy {

    /**
     * Whether the crossing may commit: empty permits; a present value
     * is the human-readable refusal rendered in the 422's
     * {@code errors} member keyed by the contributor's name.
     */
    Optional<String> denial(TransitionCheck check);

    /**
     * The crossing under consideration — the release's identity and
     * owning snapshot, the PRD 17 states, and nothing else: a policy
     * answers from facts it may read through public ports, never by
     * reaching into the release module's storage.
     */
    record TransitionCheck(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            UUID organizationId,
            String fromStatus,
            String toStatus) {}
}
