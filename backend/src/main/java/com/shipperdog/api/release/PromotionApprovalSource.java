package com.shipperdog.api.release;

import java.util.Map;
import java.util.UUID;

/**
 * The promotion engine's approval leg (issue #44, S10-05, FR-018): the
 * port through which the engine learns the TARGET ring's approval
 * standing — "approvals (target ring's policy if any)", the issue's
 * precheck scope. The release module owns the promotion decision; the
 * approval model belongs to the workflow module; this interface is the
 * consumer-owned contract between them (the {@link
 * ReleaseApprovalSource} shape: the module that owns the crossing
 * defines what it needs, the owning story's module implements it).
 *
 * <p>The leg is the RING's named policy only — the organization-
 * effective target ring's {@code approval_policy} name (the V32
 * column, resolved through the shadowing contract). The environment
 * and attached-chain legs of the crossing precedence do not apply at
 * promotion: those bound the APPROVAL_PENDING crossing the release
 * already passed, and PRD 8.10's "each ring carries its own approval
 * policy" is the stage rule the widening hop consults. A ring that
 * binds nothing demands no promotion approval (the configurable
 * standing, FR-016); a ring that binds a name that does not resolve
 * FAILS CLOSED (the S10-02 decision: an organization that bound a
 * policy by name never silently runs a different — possibly weaker —
 * chain at exactly the crossing that matters).
 *
 * <p>The round model: a promotion approval is an approval round like
 * any other ({@code release_approval_rounds}), marked with the
 * {@code promotion_ring_id} V33 adds — the ring the round gates the
 * release INTO. {@link #standingOf} is the read (the precheck route);
 * {@link #openRoundFor} is the engine's act: the first promote attempt
 * against a policy-gated ring OPENS the round (the workflow module's
 * own instantiation, audited, one request event per step) and the
 * attempt then answers blocked on the round it just opened — the same
 * first-refusal-stops discipline the CLI's state walk set. Approvers
 * decide through the workflow module's existing decide route; the next
 * promote attempt finds the round SATISFIED and proceeds.
 *
 * <p><strong>Single registrant.</strong> Exactly one implementation
 * exists (the workflow module's {@code ApprovalWorkflowService}, the
 * {@code ReleaseApprovalSource} precedent): the leg is one authority,
 * and a second bean would be a modeling error to surface, never a
 * silent interleaving.
 */
public interface PromotionApprovalSource {

    /**
     * The target ring's approval standing for the release, as a pure
     * read: nothing is written, no round is opened. Unknown releases
     * and rings answer NOT_REQUIRED-shaped emptiness (the caller
     * conceals misses its own way; the leg never guesses).
     */
    Standing standingOf(UUID releaseId, UUID organizationId, UUID ringId);

    /**
     * The engine's consult: the standing, opening the round first when
     * a resolved policy has none yet (the first promote attempt IS the
     * PROMOTION_REQUESTED act of PRD 51's chain — the round it opens
     * is the approval demand). The round commits in the
     * implementation's OWN transaction, before the attempt's
     * evaluation — a refused attempt's 409 rolls back nothing of it:
     * the round is a governed fact of the attempt and must survive it
     * (the next attempt answers pending — then, once decided,
     * satisfied — on the same round), exactly as the blocked promotion
     * record commits in its own transaction beside it.
     */
    Standing openRoundFor(UUID releaseId, UUID organizationId, UUID ringId, UUID actorId);

    /**
     * One leg's answer: whether the target ring demands approval at
     * all, and when it does the policy's name, the round's state, and
     * the human-readable waited-on list the blocker renders.
     */
    record Standing(
            Requirement requirement,
            String policyName,
            Long roundNumber,
            RoundStatus roundStatus,
            Map<String, String> waitedOn) {

        /** Whether the leg blocks the hop. */
        public boolean blocks() {
            return requirement != Requirement.NOT_REQUIRED && requirement != Requirement.SATISFIED;
        }

        /** The leg's standing vocabulary, in the blocker's own terms. */
        public enum Requirement {
            /** The ring binds no policy: the leg demands nothing (the configurable standing). */
            NOT_REQUIRED,
            /** The ring binds a policy that resolved; its round is satisfied — the leg passes. */
            SATISFIED,
            /** The ring binds a policy that resolved; its round is open and waiting (the waitedOn list names what). */
            PENDING,
            /** The ring binds a policy that resolved; its round was rejected — the rework restart is the path. */
            REJECTED,
            /** A policy is bound and resolved, but no round is open yet (the precheck read; the promote act opens it). */
            NOT_OPEN,
            /** A policy is bound by name and does not resolve — fail closed (the S10-02 decision). */
            UNRESOLVED
        }

        /** The round state when a round exists, else null (the stable string vocabulary, never the internal enum). */
        public enum RoundStatus {
            PENDING,
            SATISFIED,
            REJECTED
        }
    }
}
