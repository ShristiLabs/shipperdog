package com.shipperdog.api.gate.internal.service;

import com.shipperdog.api.gate.ReleaseGateEvaluation;
import com.shipperdog.api.release.ReleaseTransitionPolicy;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The gate-completion policy (issue #42, S10-03, AC3): the second
 * registered {@link ReleaseTransitionPolicy} contributor, the seam's
 * javadoc reserved this exact registration ("gate checks (E11)
 * compose on the same seam when they land"). The crossing it owns is
 * VALIDATING to READY — the state machine's own gate point, where
 * PRD 17's validation phase ends and the release may proceed only
 * with its quality evidence in order: the evaluation of the release's
 * CURRENT ring must find every required gate passed (or overridden —
 * the audited path this story ships). Anything else refuses: PENDING
 * (nothing reported yet), FAILED (with its evidence), or NO_DATA
 * (fail-closed on missing analytics) each render in the 422's
 * {@code errors} member keyed by gate type, so the operator reading
 * the refusal knows exactly what is missing.
 *
 * <p>Gates never force the failure edge: VALIDATING to FAILED is the
 * OPERATOR's explicit crossing with its mandatory reason (S9-02's
 * rule) — gates are evaluated facts, and the issue's technical note
 * keeps the decision where it belongs (the operator reads the
 * refusal; fixing the gates or overriding is their act). Promotion to
 * the NEXT ring is S10-05's crossing to win: that story consults the
 * same evaluation through the {@link ReleaseGateEvaluation} port for
 * the TARGET ring (PRD 33), never through this contributor.
 *
 * <p>Reads and metric resolutions run inside the transition's
 * committing transaction (the seam consults every contributor before
 * the CAS commits), so an organization that defines no gates keeps
 * the ungated machine S9-02 shipped — an empty evaluation blocks
 * nothing, exactly as FR-026's configurability demands.
 */
@Component
public class ReleaseGatePolicy implements ReleaseTransitionPolicy {

    private final GateService gates;

    ReleaseGatePolicy(GateService gates) {
        this.gates = gates;
    }

    /**
     * Permits every crossing except VALIDATING to READY, where the
     * release's current ring must have every required gate passed or
     * overridden. The denial names each blocking gate and why — the
     * human-readable refusal rendered in the 422's {@code errors}
     * member keyed by this contributor's name.
     */
    @Override
    public Optional<String> denial(TransitionCheck check) {
        if (!"READY".equals(check.toStatus()) || !"VALIDATING".equals(check.fromStatus())) {
            return Optional.empty();
        }
        ReleaseGateEvaluation.Evaluation evaluation =
                gates.evaluate(check.releaseId(), null).orElse(null);
        if (evaluation == null || !evaluation.blocked()) {
            return Optional.empty();
        }
        String blockers = evaluation.denials().entrySet().stream()
                .map(denial -> denial.getKey() + ": " + denial.getValue())
                .sorted()
                .collect(Collectors.joining("; "));
        return Optional.of("quality gates refuse this transition [" + blockers + "]");
    }

    /**
     * The per-gate denials as the map the 422 shape prefers (exposed
     * for the tests and S10-05's precheck rendering; the release
     * module's renderer keys the whole refusal by this contributor's
     * name, the gate types live inside the message).
     */
    public Map<String, String> denialsOf(TransitionCheck check) {
        ReleaseGateEvaluation.Evaluation evaluation =
                gates.evaluate(check.releaseId(), null).orElse(null);
        return evaluation == null ? Map.of() : evaluation.denials();
    }
}
