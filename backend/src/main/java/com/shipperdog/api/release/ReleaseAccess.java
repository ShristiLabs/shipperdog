package com.shipperdog.api.release;

import java.util.Optional;
import java.util.Set;
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
 * <p>Since S10-01 (issue #40) the port also serves the approval
 * workflow engine's read facts: the release snapshot the workflow
 * module resolves before acting (the seam-served concealed miss
 * included), the ring resolution its definitions attach through, and
 * the target set its tenant-scoped steps test applicability against.
 * Authorization data only — never an endpoint data path.
 *
 * <p>Implementation lives in the module's {@code .internal.} packages;
 * cross-module callers must use only this interface (enforced by
 * ArchUnit).
 */
public interface ReleaseAccess {

    /**
     * Whether the application has any release, in any lifecycle state.
     * True blocks the ownership-scope transition with the 409
     * {@code APPLICATION_RELEASES_PRESENT} (S3-02's reserved
     * zero-distributions precondition, binding since this story).
     */
    boolean hasReleases(UUID applicationId);

    /**
     * The release's snapshot as the approval engine reads it (S10-01):
     * identity, owning dimensions, creator (the self-approval guard),
     * lifecycle status, the resolved starting ring, and the targeted
     * environment — served through the seam, so an id that is unknown,
     * out of the caller's bound scope, or foreign answers empty
     * (indistinguishable; the caller conceals).
     */
    Optional<ReleaseFacts> factsOf(UUID releaseId);

    /**
     * The ring id a code resolves to in the organization's vocabulary
     * (the organization's own ring preferred over the system default —
     * the creation surface's resolution rule, S10-01's definition
     * attachment uses the same one so a definition and a release
     * created later can never disagree). Empty when the code is
     * undefined for the organization.
     */
    Optional<UUID> ringIdOf(UUID organizationId, String ringCode);

    /**
     * The code of a ring id (S10-01's definition views render the
     * attachment as the vocabulary the API accepts). Empty when the id
     * is unknown.
     */
    Optional<String> ringCodeOf(UUID ringId);

    /**
     * The approval-policy NAME the organization-effective ring of this
     * ring id carries (S10-04, issue #43): the ring row resolved
     * organization-preferred (a system ring shadowed by an organization
     * ring of the same code answers the organization ring's policy),
     * and its {@code approval_policy} when it names one. Empty when
     * the ring is unknown or carries no policy — the approval engine
     * falls through to the next leg of its precedence chain.
     */
    Optional<String> ringApprovalPolicyNameOf(UUID organizationId, UUID ringId);

    /**
     * The tenants the release targets (S10-01's tenant-scoped approval
     * steps test applicability against this set). Empty for an unknown
     * or out-of-scope release, like every read here.
     */
    Set<UUID> targetTenantIdsOf(UUID releaseId);

    /**
     * The release facts the approval engine acts on: the owning
     * dimensions and creator beside the lifecycle and attachment
     * points. {@code ringId} is the resolved starting ring (its code
     * carried beside it); {@code environmentId} is null when creation
     * named none.
     */
    record ReleaseFacts(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            UUID organizationId,
            UUID tenantId,
            UUID createdBy,
            String status,
            UUID ringId,
            String ringCode,
            UUID environmentId) {}
}
