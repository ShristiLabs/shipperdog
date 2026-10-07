package com.shipperdog.api.release.internal.service;

import com.shipperdog.api.release.internal.entity.ReleaseEntity.Status;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * PRD 17's release state machine as one exhaustive table (issue #56,
 * S9-02): the legal crossings, verbatim. The diagram's spine is the
 * happy path DRAFT to COMPLETED through VALIDATING, READY,
 * APPROVAL_PENDING, APPROVED, DEPLOYING, and ACTIVE; ACTIVE forks to
 * PROMOTED (which alone continues to COMPLETED) or ROLLED_BACK; the
 * two failure edges land VALIDATING and DEPLOYING on FAILED. Everything
 * else — every self-crossing, every skip, every exit from a terminal —
 * is illegal, and the exhaustive transition-matrix test pins all 121
 * pairs of the eleven-state vocabulary so the table cannot drift from
 * the PRD silently.
 *
 * <p>Enum + guard table over a workflow engine is the story's own
 * technical note ("enum + guard functions beat a full workflow engine
 * for M2's needs"); PRD 23's Temporal evaluation revisits if the graph
 * grows approval-driven branching. The table deliberately carries no
 * behavior: guards with side conditions (E10's approval completion,
 * S12-01's bus) compose around it through the policy seam and the
 * transition event, never inside it.
 */
final class ReleaseStateMachine {

    /**
     * The legal crossings of PRD 17, verbatim — eleven edges. The
     * terminal states (FAILED, ROLLED_BACK, COMPLETED) map to empty
     * sets: nothing exits a terminal, and no state re-opens artifact
     * mutation (the freeze is absolute; a FAILED release's artifacts
     * stay pinned, the fix path is a new version and a new release).
     */
    private static final Map<Status, Set<Status>> LEGAL_TRANSITIONS = buildLegalTransitions();

    private static Map<Status, Set<Status>> buildLegalTransitions() {
        Map<Status, Set<Status>> table = new EnumMap<>(Status.class);
        table.put(Status.DRAFT, Set.of(Status.VALIDATING));
        table.put(Status.VALIDATING, Set.of(Status.READY, Status.FAILED));
        table.put(Status.READY, Set.of(Status.APPROVAL_PENDING));
        table.put(Status.APPROVAL_PENDING, Set.of(Status.APPROVED));
        table.put(Status.APPROVED, Set.of(Status.DEPLOYING));
        table.put(Status.DEPLOYING, Set.of(Status.ACTIVE, Status.FAILED));
        table.put(Status.ACTIVE, Set.of(Status.PROMOTED, Status.ROLLED_BACK));
        table.put(Status.PROMOTED, Set.of(Status.COMPLETED));
        table.put(Status.ROLLED_BACK, Set.of());
        table.put(Status.COMPLETED, Set.of());
        table.put(Status.FAILED, Set.of());
        return Map.copyOf(table);
    }

    private ReleaseStateMachine() {}

    /**
     * Whether the crossing is one of PRD 17's edges: the single
     * authority behind the transition route's legality answer (the
     * 409), the CAS retry loop's re-check, and the matrix test.
     */
    static boolean legal(Status from, Status to) {
        return LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Whether a crossing onto this target carries a mandatory reason:
     * the failure edges (a release that died says why) and the
     * direct-channel rollback (a pulled release says why) — the S4-03
     * revoke analog, feeding the audit event and the timeline row.
     */
    static boolean reasonRequired(Status to) {
        return to == Status.FAILED || to == Status.ROLLED_BACK;
    }
}
