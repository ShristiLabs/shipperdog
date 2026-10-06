package com.shipperdog.api.authorization;

import java.util.List;
import java.util.Optional;

/**
 * The outcome of one layered decision (issue #30, S7-01): ALLOW or
 * DENY, the {@link DenialReason} and failing layer when denied, and the
 * evaluated chain — one {@link LayerOutcome} per PRD 11 layer, in
 * evaluation order, carrying each layer's verdict. Layers after the
 * first failure report {@link Status#SKIPPED}: the engine
 * short-circuits (the first failing layer is the reason), but the
 * trace keeps the whole chain visible for debugging.
 *
 * <p>The trace is internal by construction: it reaches the audit trail
 * (every decision emits one, allow and deny alike, with the request's
 * correlation id — PRD 27) and the admin-only decision inspection API.
 * HTTP responses carry only the route's concealed not-found shape.
 *
 * @param allowed      whether the decision is ALLOW
 * @param reason       why the decision denied, or null when allowed
 * @param failingLayer the layer that denied, or null when allowed
 * @param chain        every layer's outcome, in PRD 11 order
 */
public record AuthorizationDecision(
        boolean allowed, DenialReason reason, String failingLayer, List<LayerOutcome> chain) {

    /** One layer's verdict within the evaluated chain. */
    public record LayerOutcome(String layer, Status status, DenialReason reason, String detail) {

        public LayerOutcome {
            layer = layer == null ? "" : layer;
            detail = detail == null ? "" : detail;
        }
    }

    /** The verdict of one layer for one decision. */
    public enum Status {
        PASSED,
        FAILED,
        SKIPPED
    }

    public AuthorizationDecision {
        chain = List.copyOf(chain);
    }

    /** The denial reason, when the decision denied. */
    public Optional<DenialReason> denialReason() {
        return Optional.ofNullable(reason);
    }
}
