package com.shipperdog.api.release;

import java.util.List;
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
     * The release's CURRENT effective ring (S13-05, #65): the earliest
     * ring in the organization's effective order that still holds any
     * target — the release's standing on the ring walk, which starts
     * at the starting ring and widens with every promotion hop. The
     * distribution dispatch maps this onto its channel's ring-graded
     * surfaces (the Play ring-to-track map), so a dispatch at the
     * PROMOTED crossing addresses the ring the walk actually sits on
     * (the final ring), never the ring the release was born on.
     * Empty for an unknown or out-of-scope release, like every read
     * here.
     */
    Optional<String> effectiveRingCodeOf(UUID releaseId);

    /**
     * The distribution-served twin of the effective ring (S13-06,
     * issue #66): the SAME resolution over the registry probe — the
     * staged rollout's policy consult reads the ring on org-grain
     * routes and background threads that bind no release-owning
     * scope, exactly the standing of every {@code distribution*Of}
     * read on this port.
     */
    Optional<String> distributionEffectiveRingCodeOf(UUID releaseId);

    /**
     * The distribution-served target set (S13-06, issue #66): the
     * staged rollout's cohort resolution reads the release's frozen
     * target tenant set — registry-probed, the caller's own standing
     * the guard (the staged surfaces verify the plan's organization
     * before any of this reads). Frozen at creation, so the answer is
     * stable for a release's whole life.
     */
    Set<UUID> distributionTargetTenantIdsOf(UUID releaseId);

    /**
     * The distribution-served targeted groups (S13-06, issue #66):
     * every tester group every target selected, tenant-addressed —
     * the staged arms' group mapping walks this set, registry-probed
     * like the target set.
     */
    List<ReleaseTargetGroup> distributionTargetGroupsOf(UUID releaseId);

    /**
     * The release's frozen artifact selection (S13-01, issue #61): one
     * slot per platform, the id and platform the distribution
     * dispatcher addresses its records by. Empty for an unknown
     * release, like every read here; the slots are frozen at creation
     * (INV-004's artifact-set freeze), so the answer is stable for a
     * release's whole life.
     */
    List<ReleaseArtifactSlot> artifactSlotsOf(UUID releaseId);

    /**
     * The release's targeted tester groups (S13-02, issue #62): every
     * group every target selected, tenant-addressed — the store
     * connectors' group mapping walks this set at dispatch. Empty for
     * an unknown release or a release that targeted tenants at large,
     * like every read here; frozen at creation like the artifact set.
     */
    List<ReleaseTargetGroup> targetGroupsOf(UUID releaseId);

    /**
     * The release facts the store connectors' credential resolution
     * reads (S13-02, issue #62): the owning dimensions and the
     * targeted environment of a release the distribution record already
     * names. Deliberately NOT seam-served, exactly like {@link
     * #submissionFactsOf}: the refresh and reconciliation paths run on
     * organization-grain console routes and background threads whose
     * bound scope is by construction not the release's owning scope,
     * and the caller has already established its own standing over the
     * record (the organization check) before this read. Authorization
     * data only — never an endpoint data path.
     */
    Optional<ReleaseFacts> distributionFactsOf(UUID releaseId);

    /**
     * The release a feedback submission evaluates (S11-01, issue #46,
     * PRD 8.13): the one release of the application and version
     * (V1's {@code uq_releases_application_version}), resolved ONLY
     * when the named submitter tenant is one of the release's targets
     * -- the release link is the submission's entire standing, so the
     * target-set check rides the resolution and a release that does
     * not serve the tenant answers empty (indistinguishable from an
     * unknown application/version pair).
     *
     * <p>Deliberately NOT seam-served, exactly like the registry
     * probe the download cutoff uses: the submitting tester's bound
     * tenant context is by construction not the release's owning
     * scope (a shared application's release rows serve the
     * organization's scope while the tester sits in a target
     * tenant's). Authorization data in the port's own character --
     * the facts never leave the caller's own act (the tester's
     * membership in the named tenant is validated beside this, by
     * the feedback module through the tester module's port). The
     * S15-01 in-app SDK rides this same resolution: one submission
     * contract, two clients.
     */
    Optional<ReleaseFacts> submissionFactsOf(UUID applicationId, UUID versionId, UUID submitterTenantId);

    /**
     * The release a device telemetry report evaluates (S11-04, issue
     * #49, FR-029): the addressed release resolved ONLY when the
     * reporting tenant is one of the release's targets — the SDK event
     * contract's own standing (a device of a tenant the release does
     * not serve has no business reporting against it, and the miss is
     * indistinguishable from an unknown id, the concealment of every
     * release-addressed resolution). Non-seam-served exactly like
     * {@link #submissionFactsOf}: the reporter's tenant context is by
     * construction not the release's owning scope.
     */
    Optional<ReleaseFacts> telemetryFactsOf(UUID releaseId, UUID reporterTenantId);

    /**
     * Carries an ACTIVE release across PRD 17's ROLLED_BACK edge
     * (S13-06, issue #66, AC4 — the orchestrated rollback's
     * release-side half): the same transition ceremony the operator
     * route runs — the CAS, the mandatory reason, the timeline row,
     * the {@code RELEASE_TRANSITIONED} audit and event — invoked by
     * the distribution module's one-action rollback so the release
     * state, the channel records, and the staged plan land as one
     * governed act. The direct-download cutoff follows the state (the
     * {@code RolledBackCutoffPolicy} seam, S9-02); artifacts are
     * never touched (INV-008).
     *
     * <p>Refuses exactly like the route: a release not sitting on
     * ACTIVE answers the precise illegal-crossing conflict (a
     * PROMOTED or terminal release keeps its own state), an unknown
     * or out-of-scope release the concealed miss.
     *
     * @param organizationId the organization the caller established
     *                       standing over (the release's own guard)
     * @param releaseId      the release the rollback pulls
     * @param actorId        the operator running the rollback (the
     *                       timeline row names them)
     * @param reason         the mandatory rollback reason (FR-024 —
     *                       the pulled release says why)
     */
    void rollbackActiveRelease(UUID organizationId, UUID releaseId, UUID actorId, String reason);

    /**
     * The release facts the approval engine acts on: the owning
     * dimensions and creator beside the lifecycle and attachment
     * points. {@code ringId} is the resolved starting ring (its code
     * carried beside it); {@code environmentId} is null when creation
     * named none. {@code versionId} (the release's version, PRD 8.13's
     * feedback context since S11-01) rides beside the application: the
     * feedback submission's context snapshot keys on it.
     */
    record ReleaseFacts(
            UUID releaseId,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            UUID organizationId,
            UUID tenantId,
            UUID createdBy,
            String status,
            UUID ringId,
            String ringCode,
            UUID environmentId) {}

    /** One frozen artifact slot as the distribution dispatcher addresses it (S13-01). */
    record ReleaseArtifactSlot(UUID artifactId, String platform) {}

    /**
     * One targeted tester group as the store connectors address it
     * (S13-02): the group and the tenant whose target selected it.
     */
    record ReleaseTargetGroup(UUID tenantId, UUID groupId) {}
}
