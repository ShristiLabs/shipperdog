package com.shipperdog.api.release.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.release.internal.entity.ReleaseEntity.Status;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The exhaustive transition-matrix pin of PRD 17 (issue #56 AC6): every
 * one of the 121 pairs of the eleven-state vocabulary is asserted
 * against the diagram verbatim — the eleven legal edges exactly, and
 * nothing else. The table cannot drift from the PRD silently: adding,
 * removing, or redirecting a single edge fails exactly one matrix cell.
 * The reason rule rides the same pin: FAILED and ROLLED_BACK carry the
 * mandatory reason, nothing else.
 */
class ReleaseStateMachineTest {

    /** PRD 17 verbatim: the spine, the ACTIVE fork, and the two failure edges. */
    private static final Map<Status, Set<Status>> PRD_17 = Map.ofEntries(
            Map.entry(Status.DRAFT, Set.of(Status.VALIDATING)),
            Map.entry(Status.VALIDATING, Set.of(Status.READY, Status.FAILED)),
            Map.entry(Status.READY, Set.of(Status.APPROVAL_PENDING)),
            Map.entry(Status.APPROVAL_PENDING, Set.of(Status.APPROVED)),
            Map.entry(Status.APPROVED, Set.of(Status.DEPLOYING)),
            Map.entry(Status.DEPLOYING, Set.of(Status.ACTIVE, Status.FAILED)),
            Map.entry(Status.ACTIVE, Set.of(Status.PROMOTED, Status.ROLLED_BACK)),
            Map.entry(Status.PROMOTED, Set.of(Status.COMPLETED)),
            Map.entry(Status.ROLLED_BACK, Set.<Status>of()),
            Map.entry(Status.COMPLETED, Set.<Status>of()),
            Map.entry(Status.FAILED, Set.<Status>of()));

    @Test
    void theTableMatchesPrd17VerbatimAcrossAll121Pairs() {
        for (Status from : Status.values()) {
            for (Status to : Status.values()) {
                boolean expected = PRD_17.getOrDefault(from, Set.of()).contains(to);
                assertThat(ReleaseStateMachine.legal(from, to))
                        .as("transition %s -> %s must be %s per PRD 17", from, to, expected ? "legal" : "illegal")
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void exactlyElevenEdgesExist() {
        int edges = 0;
        for (Status from : Status.values()) {
            for (Status to : Status.values()) {
                if (ReleaseStateMachine.legal(from, to)) {
                    edges++;
                }
            }
        }
        assertThat(edges).as("PRD 17's diagram carries exactly eleven edges").isEqualTo(11);
    }

    @Test
    void terminalsHaveNoExitsAndNoStateReentersItself() {
        for (Status terminal : Set.of(Status.FAILED, Status.ROLLED_BACK, Status.COMPLETED)) {
            for (Status to : Status.values()) {
                assertThat(ReleaseStateMachine.legal(terminal, to))
                        .as("%s is terminal; nothing exits it", terminal)
                        .isFalse();
            }
        }
        for (Status from : Status.values()) {
            assertThat(ReleaseStateMachine.legal(from, from))
                    .as("no self-crossing exists in PRD 17 (%s -> %s)", from, from)
                    .isFalse();
        }
    }

    @Test
    void reasonsAreMandatoryExactlyForFailureAndRollback() {
        for (Status to : Status.values()) {
            assertThat(ReleaseStateMachine.reasonRequired(to))
                    .as("a reason is required exactly on FAILED and ROLLED_BACK, not %s", to)
                    .isEqualTo(to == Status.FAILED || to == Status.ROLLED_BACK);
        }
    }
}
