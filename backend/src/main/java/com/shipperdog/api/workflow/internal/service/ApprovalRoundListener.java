package com.shipperdog.api.workflow.internal.service;

import com.shipperdog.api.release.ReleaseTransitionedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The approval engine's composition hook (issue #40, S10-01): the
 * state machine stays the single writer of release status, and the
 * workflow module composes on the S9-02 event seam exactly the way
 * the manifest's freeze listener does — observing
 * {@link ReleaseTransitionedEvent} synchronously, so round 1
 * instantiates INSIDE the READY to APPROVAL_PENDING crossing's
 * committing transaction and bound scope: the round, its per-step
 * records, and the crossing commit or roll back together (no
 * APPROVAL_PENDING release ever exists without the approval records
 * its resolved definition demanded), and an instantiation failure
 * answers loudly with the whole transition undone, never a
 * half-committed approval.
 *
 * <p>Later crossings publish the same event and this listener stays
 * quiet — the crossing guard inside the service is the single place
 * the APPROVAL_PENDING edge is named (the freeze listener's shape).
 */
@Component
public class ApprovalRoundListener {

    private final ApprovalWorkflowService approvals;

    ApprovalRoundListener(ApprovalWorkflowService approvals) {
        this.approvals = approvals;
    }

    @EventListener
    void onReleaseTransitioned(ReleaseTransitionedEvent event) {
        approvals.instantiateFromCrossing(event);
    }
}
