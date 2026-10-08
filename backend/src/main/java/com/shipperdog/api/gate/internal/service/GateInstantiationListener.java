package com.shipperdog.api.gate.internal.service;

import com.shipperdog.api.release.ReleaseTransitionedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The gate module's composition hook (issue #42, S10-03): the state
 * machine stays the single writer of release status, and the gate
 * module composes on the S9-02 event seam exactly the way the
 * manifest's freeze listener and the approval engine's round listener
 * do — observing {@link ReleaseTransitionedEvent} synchronously, so
 * the release's expected gate rows instantiate INSIDE the DRAFT to
 * VALIDATING crossing's committing transaction and bound scope: the
 * PENDING rows, the metric-backed fail-closed NO_DATA resolutions,
 * and the crossing commit or roll back together (no VALIDATING
 * release ever exists without the gate records its ring demands),
 * and an instantiation failure answers loudly with the whole
 * transition undone, never a half-validated release.
 *
 * <p>Later crossings publish the same event and this listener stays
 * quiet — the crossing guard inside the service is the single place
 * the VALIDATING edge is named (the freeze listener's shape).
 */
@Component
public class GateInstantiationListener {

    private final GateService gates;

    GateInstantiationListener(GateService gates) {
        this.gates = gates;
    }

    @EventListener
    void onReleaseTransitioned(ReleaseTransitionedEvent event) {
        gates.instantiateFromCrossing(event);
    }
}
