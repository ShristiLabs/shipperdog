package com.shipperdog.api.release;

import java.util.UUID;

/**
 * The release module's public surface for a SYSTEM-driven failure
 * crossing (issue #63, S13-03, AC4, PRD 17): the DEPLOYING to FAILED
 * edge a refused production submission lands the release on, with the
 * provider's actionable reason on the timeline row. The S13-03
 * doctrine split: a SUBMISSION channel's permanent submission failure
 * vetoes the ACTIVE crossing (the crossing's transaction rolls back),
 * and this port performs the failure crossing in the fresh
 * transaction that follows — the same CAS, history row, audit, and
 * event ceremony the operator-driven transitions run, with no actor
 * (the store refused; nobody chose this).
 *
 * <p>Implementation lives in the module's {@code .internal.} packages;
 * cross-module callers must use only this interface (enforced by
 * ArchUnit).
 */
public interface ReleaseDistributionFailures {

    /**
     * Carries the release across PRD 17's DEPLOYING to FAILED edge
     * with the given reason (the timeline row carries it verbatim —
     * the actionable answer a Release Manager reads). The actor is the
     * operator whose crossing the refusal answered (null for a
     * system-driven crossing, falling back to the release's creator —
     * the timeline row always names a person). A no-op when the
     * release is not in DEPLOYING: the crossing is refuse-to-lose,
     * never force — a release that moved on concurrently keeps its
     * own state and the refusal is logged.
     */
    void failFromDeploying(UUID releaseId, String reason, UUID actorId);
}
