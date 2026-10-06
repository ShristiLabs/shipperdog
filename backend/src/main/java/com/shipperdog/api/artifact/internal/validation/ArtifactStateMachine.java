package com.shipperdog.api.artifact.internal.validation;

import com.shipperdog.api.artifact.internal.entity.ArtifactEntity.Status;
import java.util.Map;
import java.util.Set;

/**
 * The artifact lifecycle authority (issue #24, S5-05, PRD 18): one
 * explicit transition table every artifact status change composes
 * through. Before this story each writer moved the status inline
 * (extraction's VALIDATING-to-FAILED being the only one); the formal
 * state machine centralizes the rule so an invalid transition is
 * rejected wherever it is attempted, not merely where it was expected.
 *
 * <p>The table is PRD 18 exactly:
 *
 * <pre>
 * UPLOADING   -> VALIDATING                  (registration: bytes stored, checksum known)
 * VALIDATING  -> READY | FAILED              (this story's pipeline; extraction's malformed-binary leg)
 * FAILED      -> VALIDATING                  (revalidation: the retry the story's scope demands)
 * READY       -> DISTRIBUTED | ARCHIVED      (distribution epics own these; reserved here)
 * DISTRIBUTED -> ARCHIVED                    (terminal rest)
 * </pre>
 *
 * <p>PRD 18 has no REVOKED state — and neither does the V1 {@code
 * artifact_status} vocabulary. REVOKED (FR-006's loose "possible
 * states" list) is the AUTHORIZATION lifecycle of INV-007
 * ({@code authorization can be revoked without deleting the artifact})
 * and therefore an E7 concept over authorization rows, not an
 * artifact-row state; this machine deliberately does not model it, and
 * the artifact vocabulary stays the six values of V1. ARCHIVED is
 * reachable from READY and DISTRIBUTED per the diagram; nothing in M1
 * drives it yet (E9/E13 will).
 *
 * <p>All transitions preserve INV-003 by construction: the machine
 * moves only the status field. Bytes, checksums, provenance, and the
 * extraction document are never touched by a transition — the entity's
 * write-once guards own that, and the pipeline's stage reads consult
 * them without ever writing them.
 */
public final class ArtifactStateMachine {

    private static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.UPLOADING, Set.of(Status.VALIDATING),
            Status.VALIDATING, Set.of(Status.READY, Status.FAILED),
            Status.FAILED, Set.of(Status.VALIDATING),
            Status.READY, Set.of(Status.DISTRIBUTED, Status.ARCHIVED),
            Status.DISTRIBUTED, Set.of(Status.ARCHIVED),
            Status.ARCHIVED, Set.of());

    private ArtifactStateMachine() {}

    /** Whether the transition exists in the PRD 18 table. */
    public static boolean isLegal(Status from, Status to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Accepts the transition or rejects it loudly: every status-mutating
     * path of the artifact module composes through this guard, so a
     * transition outside the table is an {@link IllegalStateException}
     * at the attempt, never a silently corrupted lifecycle.
     *
     * @param from the status the row currently carries
     * @param to the status the writer wants
     */
    public static void requireLegal(Status from, Status to) {
        if (!isLegal(from, to)) {
            throw new IllegalStateException("illegal artifact transition " + from + " -> " + to);
        }
    }
}
