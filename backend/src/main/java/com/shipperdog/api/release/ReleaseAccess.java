package com.shipperdog.api.release;

import java.util.UUID;

/**
 * The release module's public surface for other modules (issue #55,
 * S9-01): the application registry's audited ownership-scope transition
 * (issue #14, S3-02) reserved its zero-distributions precondition for
 * exactly this landing — "M2's release targets do not exist in M1; that
 * story binds its check here." A release anchors the application to its
 * owning dimension: converting ownership would strand release rows (and
 * their targets, which point at tenants of the organization) in a scope
 * the seam no longer binds for the application's routes. The
 * precondition refuses in BOTH directions for the same reason the
 * ACTIVE-authorization precondition does: a release of a shared
 * application targeted tenants that a conversion to tenant ownership
 * would cut off, and a release of a tenant-owned application anchored it
 * to its tenant. History blocks — a completed, rolled-back, or failed
 * release is still a distribution record (INV-006's character), and no
 * release row is ever deleted.
 *
 * <p>Implementation lives in the module's {@code .internal.} packages;
 * cross-module callers must use only this interface (enforced by
 * ArchUnit). Authorization data only — never an endpoint data path.
 */
public interface ReleaseAccess {

    /**
     * Whether the application has any release, in any lifecycle state.
     * True blocks the ownership-scope transition with the 409
     * {@code APPLICATION_RELEASES_PRESENT} (S3-02's reserved
     * zero-distributions precondition, binding since this story).
     */
    boolean hasReleases(UUID applicationId);
}
