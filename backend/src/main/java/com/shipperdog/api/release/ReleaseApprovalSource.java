package com.shipperdog.api.release;

import java.util.List;
import java.util.UUID;

/**
 * The manifest's approval feed (issue #40, S10-01, PRD 16): the seam
 * through which the freeze at the APPROVED crossing learns WHICH
 * approvals authorized the release — the {@code approval.required}
 * block PRD 16 demands and the S9-03 document reserved ("the
 * required-approvers list stays empty until S10-01's approval
 * policies"). The release module owns the manifest; the approval model
 * belongs to the workflow module; this interface is the consumer-owned
 * contract between them — the {@link ReleaseTransitionPolicy} shape,
 * where the module that owns the crossing defines what it needs and
 * the owning story's module implements it.
 *
 * <p>The answer is a statement about a FROZEN moment, not live state:
 * the list the satisfied round demanded, in chain order, as the
 * crossing's policy already verified it — a release no workflow gated
 * answers empty (approvals are configurable, FR-016, and their absence
 * is a fact the manifest records like any other). Implementations
 * resolve by release id alone and never widen scope: the freeze runs
 * inside the crossing's committed transaction and bound scope.
 */
public interface ReleaseApprovalSource {

    /**
     * The approval types (the role vocabulary of the satisfied chain,
     * in order) that authorized the release's APPROVED crossing, or
     * empty when no workflow gated the release.
     */
    List<String> approvalTypesOf(UUID releaseId);
}
