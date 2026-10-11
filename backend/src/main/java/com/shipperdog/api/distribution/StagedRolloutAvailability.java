package com.shipperdog.api.distribution;

import java.util.UUID;

/**
 * The distribution module's public surface for the staged rollout's
 * DIRECT arm (issue #66, S13-06, AC3): whether a staged tenant rollout
 * narrows the downloads of a version's artifacts for the asking
 * tester. A release running a staged plan serves its DIRECT-channel
 * downloads to the REACHED cohort only — a tester whose every tenant
 * membership sits outside the reached set is cut exactly like the
 * ROLLED_BACK cutoff cuts everyone (the same concealed-miss shape),
 * until a wider stage reaches them.
 *
 * <p>The download path consults this seam AFTER the authorization
 * engine's ALLOW — the staged cohort narrows a granted download, it
 * never widens one (the engine owns authorization; the staged rollout
 * owns availability, PRD 19's constraint). A read-only point lookup
 * that never throws: an unavailable source answers not-blocking (this
 * layer restricts; it does not authorize).
 *
 * <p>Implementation lives in the module's {@code .internal.} packages;
 * cross-module callers must use only this interface (enforced by
 * ArchUnit).
 */
public interface StagedRolloutAvailability {

    /**
     * Whether a staged rollout of the version's release blocks the
     * download for the asking user: true only when a RUNNING plan
     * exists for the version, the user resolves to a tester, and NONE
     * of the tester's tenant memberships sits in the plan's reached
     * cohort. False for every other standing — no plan, a PLANNED
     * plan (the release is not distributing yet; the engine's own
     * layers govern), a COMPLETE plan (everyone reached), a ROLLED_BACK
     * plan (the release-level cutoff policy owns the cut).
     *
     * @param versionId the version the download addresses
     * @param userId    the authenticated subject asking for the download
     */
    boolean blocksDownload(UUID versionId, UUID userId);
}
