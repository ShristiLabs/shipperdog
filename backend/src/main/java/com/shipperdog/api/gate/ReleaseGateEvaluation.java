package com.shipperdog.api.gate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The promotion precheck port (issue #42, S10-03): the evaluation
 * hook S10-05's ring promotion consults — PRD 33's rule that "each
 * ring carries its own ... quality gates" and "promotion to the next
 * ring automatically evaluates the target ring's gates; a failed gate
 * blocks promotion with recorded evidence". The {@code
 * ReleaseTransitionPolicy} shape, applied to facts instead of
 * crossings: the gate module owns the evaluation, the promotion story
 * owns the decision, and this interface is the contract between them
 * (the shared precheck contract the dependency list names).
 *
 * <p>The same evaluation serves the state machine's own gate point:
 * the {@code ReleaseGatePolicy} registrant denies the VALIDATING to
 * READY crossing while the release's CURRENT ring's required gates
 * are not passed (the {@link #evaluate} with no ring override) — one
 * evaluator, two consulters, no duplicated logic.
 *
 * <p>An evaluation is a statement about LIVE state at the moment of
 * the call: submitted results as recorded, metric-backed gates
 * resolved from analytics NOW (fail-closed on missing data), required
 * gates without any result yet reported as PENDING. Overrides are
 * facts the evaluation honors: an overridden FAILED or NO_DATA gate
 * does not block, and stays visible in the per-gate detail so the
 * dashboard renders exactly what was overridden and why.
 */
public interface ReleaseGateEvaluation {

    /**
     * Evaluates the release's gates: the named ring's required set
     * when {@code ringCode} is present (the promotion precheck — the
     * TARGET ring a promotion would enter), the release's current
     * ring otherwise. An unknown release answers empty (the concealed
     * miss the caller renders as its own); an unknown ring answers
     * empty the same way — the caller (the promotion decision or the
     * transition policy) never guesses on the gate module's behalf.
     */
    Optional<Evaluation> evaluate(UUID releaseId, String ringCode);

    /**
     * One evaluation's complete answer: the release and ring
     * evaluated, every gate state in stable display order, and — when
     * the evaluation blocks — the per-gate denial reasons keyed by
     * gate type (the 422 {@code errors} shape the transition policy
     * renders verbatim).
     */
    record Evaluation(
            UUID releaseId,
            String releaseRef,
            String ring,
            List<GateState> gates,
            boolean blocked,
            Map<String, String> denials) {

        /** One gate of the evaluation: the recorded state, never a live row. */
        public record GateState(
                String gateType,
                boolean required,
                String status,
                Double observedValue,
                Double threshold,
                String evidenceUrl,
                String source,
                boolean overridden,
                String overriddenBy,
                String overriddenReason) {}
    }
}
