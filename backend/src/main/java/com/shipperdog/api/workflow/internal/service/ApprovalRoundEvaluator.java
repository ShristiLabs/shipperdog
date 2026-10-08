package com.shipperdog.api.workflow.internal.service;

import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepEntity.GroupMode;
import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalEntity;
import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalEntity.Status;
import java.util.ArrayList;
import java.util.List;

/**
 * The round evaluator (issue #41, S10-02, FR-016): the pure,
 * deterministic function from one round's committed step rows to the
 * round's state — which group is active, which steps are decidable,
 * what the round waits on, and whether it is satisfied or rejected.
 * Everything the decision route, the transition policy, and the
 * dashboard render about mode semantics flows through THIS class, so
 * the semantics have one definition.
 *
 * <p>The model (the issue's scope): a round is an ordered list of
 * GROUPS; groups run sequentially (group N+1 is not decidable until
 * group N is satisfied); within a group the mode rules:
 * <ul>
 *   <li>{@code ALL} — the PARALLEL mode: every step must APPROVE. A
 *       step SKIPPED at instantiation (its condition evaluated false)
 *       demands nothing, so a group whose every step was excluded is
 *       satisfied vacuously — the honest record of "nothing was
 *       required".</li>
 *   <li>{@code ANY} — the OR mode: the FIRST approval satisfies the
 *       group; the remaining PENDING siblings become SKIPPED (the
 *       decision route performs the skip CAS after its own signature
 *       commits, recording WHICH signature satisfied the group —
 *       deterministic and auditable). A skip never satisfies: only a
 *       signature does. A group whose every step was excluded at
 *       instantiation is satisfied vacuously, same as ALL.</li>
 * </ul>
 *
 * <p>A REJECTED step anywhere rejects the round, in EITHER mode — a
 * rejection is never outvoted (the S10-01 sequential precedent kept
 * verbatim: a round is one governance moment; the losing opinion is
 * recorded, the rework path restarts). This makes every approve/reject
 * interleaving converge: two racers on siblings of an ANY group both
 * commit their signatures, both recompute from the committed rows, and
 * both reach the same terminal state — the round CAS serializes the
 * last write and the loser answers from the committed state.
 *
 * <p>Sequential is the degenerate case the issue's technical note
 * names: groups of one, all {@code ALL} — the evaluation of every
 * S10-01 round is unchanged by construction.
 */
final class ApprovalRoundEvaluator {

    /** One step's evaluator facts: identity, group membership, and committed status. */
    record Step(
            int position, int groupPosition, GroupMode groupMode, boolean pending, boolean approved, boolean rejected) {

        static Step of(ReleaseApprovalEntity row) {
            return new Step(
                    row.getPosition(),
                    row.getGroupPosition(),
                    row.getGroupMode(),
                    row.getStatus() == Status.PENDING,
                    row.getStatus() == Status.APPROVED,
                    row.getStatus() == Status.REJECTED);
        }
    }

    /** The round's state as the committed rows determine it. */
    enum Outcome {
        PENDING,
        SATISFIED,
        REJECTED
    }

    /**
     * The evaluation: the round's outcome beside its active group (the
     * first unsatisfied one) and that group's PENDING steps — the
     * decidable set (the only steps a decision may address) and the
     * waiting-on list the policy denial and the dashboard render.
     */
    record Evaluation(Outcome outcome, int activeGroupPosition, GroupMode activeGroupMode, List<Step> waitingOn) {

        /** The decidable positions: the active group's PENDING steps, in chain order. */
        List<Integer> decidablePositions() {
            return waitingOn.stream().map(Step::position).toList();
        }
    }

    /**
     * Evaluates one round's steps (ordered by position — the
     * repository's read order). An empty round is SATISFIED (nothing
     * was required — the born-satisfied shape S10-01 shipped for the
     * all-steps-scoped-away definition).
     */
    static Evaluation evaluate(List<Step> steps) {
        if (steps.isEmpty()) {
            return new Evaluation(Outcome.SATISFIED, 0, GroupMode.ALL, List.of());
        }
        // A rejection anywhere rejects the round (never outvoted).
        if (steps.stream().anyMatch(Step::rejected)) {
            return new Evaluation(Outcome.REJECTED, 0, GroupMode.ALL, List.of());
        }
        int index = 0;
        while (index < steps.size()) {
            int groupPosition = steps.get(index).groupPosition();
            List<Step> group = new ArrayList<>();
            while (index < steps.size() && steps.get(index).groupPosition() == groupPosition) {
                group.add(steps.get(index));
                index++;
            }
            if (!groupSatisfied(group)) {
                // An unsatisfied group always holds PENDING steps (an
                // ALL group is satisfied once none are pending; an ANY
                // group once one is approved or all were skipped).
                return new Evaluation(
                        Outcome.PENDING,
                        groupPosition,
                        group.get(0).groupMode(),
                        group.stream().filter(Step::pending).toList());
            }
        }
        return new Evaluation(Outcome.SATISFIED, 0, GroupMode.ALL, List.of());
    }

    /**
     * One group's satisfaction from its committed rows: ALL demands no
     * PENDING step remain (SKIPPED demanded nothing); ANY demands a
     * signature — one APPROVED step — or the vacuous all-excluded
     * shape (no PENDING and none APPROVED means every step was skipped
     * at instantiation).
     */
    private static boolean groupSatisfied(List<Step> group) {
        boolean anyPending = group.stream().anyMatch(Step::pending);
        boolean anyApproved = group.stream().anyMatch(Step::approved);
        if (group.get(0).groupMode() == GroupMode.ANY) {
            return anyApproved || (!anyPending && !anyApproved);
        }
        return !anyPending;
    }

    private ApprovalRoundEvaluator() {}
}
