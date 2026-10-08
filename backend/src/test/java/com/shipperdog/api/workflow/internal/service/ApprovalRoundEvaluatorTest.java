package com.shipperdog.api.workflow.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepEntity.GroupMode;
import com.shipperdog.api.workflow.internal.service.ApprovalRoundEvaluator.Evaluation;
import com.shipperdog.api.workflow.internal.service.ApprovalRoundEvaluator.Outcome;
import com.shipperdog.api.workflow.internal.service.ApprovalRoundEvaluator.Step;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The evaluator's exhaustive mode matrix (issue #41, S10-02, AC6):
 * every mode — sequential (the degenerate groups of one), ALL (the
 * PARALLEL mode), ANY (the OR mode), and the mixed topologies AC3
 * names — crossed with every approve/reject ordering that reaches a
 * distinct evaluation: who is decidable at each moment, what the
 * round waits on, and which terminal the committed rows determine.
 * The evaluator is pure (its Step record IS the committed-row
 * projection), so the matrix runs as a unit test; the integration
 * tests walk the marquee flows end to end.
 */
class ApprovalRoundEvaluatorTest {

    /** One committed row: the position, its group and mode, the terminal status (PENDING by default). */
    private static Step step(int position, int group, GroupMode mode, String status) {
        return new Step(
                position, group, mode, "PENDING".equals(status), "APPROVED".equals(status), "REJECTED".equals(status));
    }

    /** The CAS's effect on the committed rows, replayed in-memory. */
    private static void decided(List<Step> chain, int position, String to) {
        Step row = chain.stream()
                .filter(candidate -> candidate.position() == position)
                .findFirst()
                .orElseThrow();
        chain.set(chain.indexOf(row), step(row.position(), row.groupPosition(), row.groupMode(), to));
    }

    // A three-group fixture: [QA] -> [Security AND Compliance] -> [Product].
    private static List<Step> mixedTopology() {
        return new ArrayList<>(List.of(
                step(1, 1, GroupMode.ALL, "PENDING"),
                step(2, 2, GroupMode.ALL, "PENDING"),
                step(3, 2, GroupMode.ALL, "PENDING"),
                step(4, 3, GroupMode.ALL, "PENDING")));
    }

    // The ANY fixture: [QA] -> [Security OR Compliance] -> [Product].
    private static List<Step> anyTopology() {
        return new ArrayList<>(List.of(
                step(1, 1, GroupMode.ALL, "PENDING"),
                step(2, 2, GroupMode.ANY, "PENDING"),
                step(3, 2, GroupMode.ANY, "PENDING"),
                step(4, 3, GroupMode.ALL, "PENDING")));
    }

    private static Evaluation evaluate(List<Step> chain) {
        return ApprovalRoundEvaluator.evaluate(chain);
    }

    // -------------------------------------------------------------------
    // Sequential (the degenerate case): S10-01 semantics verbatim
    // -------------------------------------------------------------------

    @Test
    void sequentialChainDecidesInOrderAndSatisfiesAtTheLastStep() {
        List<Step> chain = new ArrayList<>(List.of(
                step(1, 1, GroupMode.ALL, "PENDING"),
                step(2, 2, GroupMode.ALL, "PENDING"),
                step(3, 3, GroupMode.ALL, "PENDING")));

        Evaluation start = evaluate(chain);
        assertThat(start.outcome()).isEqualTo(Outcome.PENDING);
        assertThat(start.decidablePositions()).containsExactly(1);

        decided(chain, 1, "APPROVED");
        assertThat(evaluate(chain).outcome()).isEqualTo(Outcome.PENDING);
        assertThat(evaluate(chain).decidablePositions()).containsExactly(2);

        decided(chain, 2, "APPROVED");
        assertThat(evaluate(chain).decidablePositions()).containsExactly(3);
        assertThat(evaluate(chain).outcome()).isEqualTo(Outcome.PENDING);

        decided(chain, 3, "APPROVED");
        assertThat(evaluate(chain).outcome()).isEqualTo(Outcome.SATISFIED);
    }

    @Test
    void sequentialChainRejectsFromTheFirstNoOn() {
        for (int rejectAt : List.of(1, 2, 3)) {
            List<Step> chain = new ArrayList<>(List.of(
                    step(1, 1, GroupMode.ALL, "PENDING"),
                    step(2, 2, GroupMode.ALL, "PENDING"),
                    step(3, 3, GroupMode.ALL, "PENDING")));
            for (int position = 1; position < rejectAt; position++) {
                decided(chain, position, "APPROVED");
            }
            decided(chain, rejectAt, "REJECTED");
            Evaluation outcome = evaluate(chain);
            assertThat(outcome.outcome()).as("reject at %d", rejectAt).isEqualTo(Outcome.REJECTED);
            assertThat(outcome.decidablePositions())
                    .as("reject at %d", rejectAt)
                    .isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // ALL (the PARALLEL mode): every step, any order
    // -------------------------------------------------------------------

    @Test
    void allGroupMakesEverySiblingDecidableAndSatisfiesOnlyWhenEveryStepSigned() {
        List<Step> chain = mixedTopology();

        // Group 1 gates the parallel pair: neither sibling is decidable yet.
        Evaluation gated = evaluate(chain);
        assertThat(gated.decidablePositions()).containsExactly(1);
        assertThat(gated.activeGroupPosition()).isEqualTo(1);

        decided(chain, 1, "APPROVED");
        Evaluation parallel = evaluate(chain);
        assertThat(parallel.activeGroupPosition()).isEqualTo(2);
        assertThat(parallel.activeGroupMode()).isEqualTo(GroupMode.ALL);
        // PARALLEL: both siblings are decidable AT ONCE.
        assertThat(parallel.decidablePositions()).containsExactlyInAnyOrder(2, 3);
        assertThat(parallel.outcome()).isEqualTo(Outcome.PENDING);

        // Either order satisfies: Compliance first here.
        decided(chain, 3, "APPROVED");
        assertThat(evaluate(chain).outcome()).isEqualTo(Outcome.PENDING);
        assertThat(evaluate(chain).decidablePositions()).containsExactly(2);

        decided(chain, 2, "APPROVED");
        Evaluation between = evaluate(chain);
        assertThat(between.activeGroupPosition()).isEqualTo(3);
        assertThat(between.decidablePositions()).containsExactly(4);

        decided(chain, 4, "APPROVED");
        assertThat(evaluate(chain).outcome()).isEqualTo(Outcome.SATISFIED);
    }

    @Test
    void allGroupRejectsFromAnySiblingNo() {
        for (int rejectAt : List.of(2, 3)) {
            List<Step> chain = mixedTopology();
            decided(chain, 1, "APPROVED");
            decided(chain, rejectAt, "REJECTED");
            assertThat(evaluate(chain).outcome())
                    .as("parallel reject at %d", rejectAt)
                    .isEqualTo(Outcome.REJECTED);
        }
    }

    @Test
    void theMixedTopologyCompletesOnlyPerItsSemantics() {
        // (QA) -> (Security AND Compliance) -> (Product), the PRD FR-016
        // example: every prefix of every legal ordering stays PENDING
        // and only the full set SATISFIES.
        List<List<Integer>> legalOrderings = List.of(List.of(1, 2, 3, 4), List.of(1, 3, 2, 4));
        for (List<Integer> ordering : legalOrderings) {
            List<Step> chain = mixedTopology();
            for (int index = 0; index < ordering.size(); index++) {
                decided(chain, ordering.get(index), "APPROVED");
                Outcome outcome = evaluate(chain).outcome();
                if (index < ordering.size() - 1) {
                    assertThat(outcome)
                            .as("ordering %s at prefix %d", ordering, index)
                            .isEqualTo(Outcome.PENDING);
                } else {
                    assertThat(outcome).as("ordering %s complete", ordering).isEqualTo(Outcome.SATISFIED);
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // ANY (the OR mode): first approval satisfies; skips never do
    // -------------------------------------------------------------------

    @Test
    void anyGroupSatisfiesOnTheFirstApprovalWhicheverSiblingItIs() {
        for (int satisfier : List.of(2, 3)) {
            List<Step> chain = anyTopology();
            decided(chain, 1, "APPROVED");
            assertThat(evaluate(chain).decidablePositions())
                    .as("ANY decidable before the first approval (satisfier %d)", satisfier)
                    .containsExactlyInAnyOrder(2, 3);

            decided(chain, satisfier, "APPROVED");
            // The skip the decision route performs beside the signature.
            decided(chain, satisfier == 2 ? 3 : 2, "SKIPPED");
            Evaluation after = evaluate(chain);
            assertThat(after.outcome()).as("satisfier %d", satisfier).isEqualTo(Outcome.PENDING);
            assertThat(after.activeGroupPosition()).isEqualTo(3);
            assertThat(after.decidablePositions()).containsExactly(4);

            decided(chain, 4, "APPROVED");
            assertThat(evaluate(chain).outcome()).isEqualTo(Outcome.SATISFIED);
        }
    }

    @Test
    void anyGroupIsSatisfiedByTheApprovalAloneTheSiblingSkipIsJustTheRecord() {
        // One APPROVED sibling, the other still PENDING (the skip CAS
        // has not landed): the FIRST approval is the satisfaction —
        // the evaluation does not wait for the skip.
        List<Step> chain = anyTopology();
        decided(chain, 1, "APPROVED");
        decided(chain, 2, "APPROVED");
        Evaluation afterFirstApproval = evaluate(chain);
        assertThat(afterFirstApproval.activeGroupPosition()).isEqualTo(3);
        assertThat(afterFirstApproval.decidablePositions()).containsExactly(4);
    }

    @Test
    void anyGroupRejectsEvenWithOneSiblingAlreadyApprovedANoIsNeverOutvoted() {
        List<Step> chain = anyTopology();
        decided(chain, 1, "APPROVED");
        decided(chain, 2, "APPROVED");
        decided(chain, 3, "REJECTED");
        assertThat(evaluate(chain).outcome()).isEqualTo(Outcome.REJECTED);
    }

    // -------------------------------------------------------------------
    // Conditions and vacuous groups
    // -------------------------------------------------------------------

    @Test
    void aConditionExcludedStepDemandsNothingItsGroupSatisfiesWithoutIt() {
        // [QA AND (Compliance if environment=production)] -> [Product],
        // on a non-production release: Compliance instantiates SKIPPED.
        List<Step> chain = new ArrayList<>(List.of(
                step(1, 1, GroupMode.ALL, "PENDING"),
                step(2, 1, GroupMode.ALL, "SKIPPED"),
                step(3, 2, GroupMode.ALL, "PENDING")));
        Evaluation start = evaluate(chain);
        assertThat(start.activeGroupPosition()).isEqualTo(1);
        assertThat(start.decidablePositions()).containsExactly(1);

        decided(chain, 1, "APPROVED");
        // The group satisfied without the excluded step; Product is next.
        assertThat(evaluate(chain).activeGroupPosition()).isEqualTo(2);
        assertThat(evaluate(chain).decidablePositions()).containsExactly(3);

        decided(chain, 3, "APPROVED");
        assertThat(evaluate(chain).outcome()).isEqualTo(Outcome.SATISFIED);
    }

    @Test
    void anAnyGroupWhoseEveryStepWasExcludedIsSatisfiedVacuously() {
        List<Step> chain = new ArrayList<>(List.of(
                step(1, 1, GroupMode.ALL, "PENDING"),
                step(2, 2, GroupMode.ANY, "SKIPPED"),
                step(3, 2, GroupMode.ANY, "SKIPPED")));
        decided(chain, 1, "APPROVED");
        assertThat(evaluate(chain).outcome()).isEqualTo(Outcome.SATISFIED);
    }

    @Test
    void anAllGroupWhoseEveryStepWasExcludedIsSatisfiedVacuously() {
        List<Step> chain =
                new ArrayList<>(List.of(step(1, 1, GroupMode.ALL, "SKIPPED"), step(2, 2, GroupMode.ALL, "PENDING")));
        assertThat(evaluate(chain).activeGroupPosition()).isEqualTo(2);
        decided(chain, 2, "APPROVED");
        assertThat(evaluate(chain).outcome()).isEqualTo(Outcome.SATISFIED);
    }

    @Test
    void anEmptyRoundIsSatisfied() {
        assertThat(ApprovalRoundEvaluator.evaluate(List.<Step>of()).outcome()).isEqualTo(Outcome.SATISFIED);
    }

    @Test
    void aLaterGroupsApprovalDoesNotSatisfyAnEarlierPendingGroup() {
        // Out-of-order signatures cannot exist through the decision
        // route (only the active group's steps are decidable); the
        // evaluator stays honest about them anyway — an earlier
        // PENDING group keeps the round PENDING wherever later rows
        // stand.
        List<Step> chain = new ArrayList<>(List.of(
                step(1, 1, GroupMode.ALL, "PENDING"),
                step(2, 2, GroupMode.ALL, "APPROVED"),
                step(3, 3, GroupMode.ALL, "APPROVED")));
        Evaluation evaluation = evaluate(chain);
        assertThat(evaluation.outcome()).isEqualTo(Outcome.PENDING);
        assertThat(evaluation.activeGroupPosition()).isEqualTo(1);
        assertThat(evaluation.decidablePositions()).containsExactly(1);
    }
}
