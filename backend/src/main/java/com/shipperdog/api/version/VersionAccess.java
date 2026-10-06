package com.shipperdog.api.version;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The version module's public surface for other modules (issue #17,
 * extended by issue #20): the application registry's audited
 * ownership-scope transition (issue #14, S3-02) moves an application
 * between owning dimensions, and the application's versions must move
 * with it — a version row left in the previous scope would strand
 * outside the owning scope the isolation seam serves, invisible to
 * every later request (ADR-0002 third amendment doctrine applied to
 * the rows the transition now drags along). Since E5, the same surface
 * resolves a version's owning dimension for the artifact routes, whose
 * body-addressed {@code POST /api/v1/artifacts} carries the version id
 * instead of a path application (issue #20): the RBAC evaluator's
 * {@code version} targets and the artifact module's scope binding
 * resolve through {@link #ownershipOf} exactly the way {@code app:}
 * application targets resolve through the application registry.
 * Implementation lives in the module's {@code .internal.} packages
 * behind the sanctioned cross-tenant guard; cross-module callers must
 * use only this interface (enforced by ArchUnit).
 */
public interface VersionAccess {

    /**
     * Carries every version of the application to the given owning
     * tenant, or to the owning organization when {@code tenantId} is
     * null. Called only from the application module's scope transition
     * after its precondition checks, inside the same transaction, and
     * the move is audited as part of {@code APPLICATION_SCOPE_CHANGED}.
     *
     * @return the rows carried along
     */
    int reassignOwnership(UUID applicationId, UUID tenantId, Instant updatedAt);

    /**
     * The owning scope of the version, or empty when unknown. A null
     * {@code tenantId} means the version hangs under an
     * organization-shared application and resolves at organization
     * scope. Authorization and scope-binding data only — the same
     * character as {@code ApplicationAccess.ownershipOf}; never an
     * endpoint data path.
     */
    Optional<VersionOwnership> ownershipOf(UUID versionId);

    /**
     * The ids of every version of the application (issue #20): the
     * artifact module's same-content detection spans the application's
     * versions — a re-uploaded binary is the same binary whether it
     * landed under this version or a sibling — and composes the
     * version set through this read instead of joining the version
     * tables it must not see. Ids only, never rows.
     */
    List<UUID> versionIdsOfApplication(UUID applicationId);

    /**
     * The version record's own name (issue #24, S5-05): the artifact
     * validation pipeline's VERSION_MATCH stage compares the marketing
     * version extracted from a binary against the name the version
     * record carries — a mismatch fails the artifact (M1 rejects
     * outright; no auto-amend proposal). The name only, never the
     * row; the same registry-read character as {@link #ownershipOf}.
     */
    Optional<VersionRecord> recordOf(UUID versionId);

    /** The ownership tuple callers may resolve; never the row itself. */
    record VersionOwnership(UUID versionId, UUID applicationId, UUID organizationId, UUID tenantId) {}

    /**
     * The version's resolved decision inputs (issue #30, S7-01): the
     * ownership tuple plus the application it belongs to and the
     * lifecycle status — the E7 authorization chain's version-layer
     * facts ({@code DRAFT}, {@code ACTIVE}, {@code ARCHIVED}, or
     * {@code REVOKED}; a REVOKED version blocks new downloads,
     * INV-007). Authorization data only, the same registry-read
     * character as {@link #ownershipOf}; never an endpoint data path.
     */
    record VersionState(UUID versionId, UUID applicationId, UUID organizationId, UUID tenantId, String status) {}

    /**
     * The version's resolved decision inputs, or empty when unknown
     * (issue #30, S7-01) — one point lookup where the engine would
     * otherwise resolve ownership and status separately.
     */
    Optional<VersionState> stateOf(UUID versionId);

    /**
     * Whether the version carries the tag (issue #31, S7-02): the
     * TAG-grant decision probe — a tag grant authorizes exactly the
     * versions carrying its tag at evaluation time (FR-011: newly
     * tagged versions join automatically). One composite-primary-key
     * descent on {@code version_tags}; authorization data only.
     */
    boolean versionHasTag(UUID versionId, String tag);

    /**
     * The versions of the application currently carrying the tag
     * (issue #31, S7-02): the TAG-grant creation validation (a grant
     * may only address a tag the application actually uses, so a
     * typo cannot mint a silently-dead grant) and the effective-set
     * resolution's set form — one query per distinct tag, served by
     * {@code idx_version_tags_tag} composed with the application's
     * versions. Authorization data only.
     */
    List<UUID> versionIdsOfApplicationWithTag(UUID applicationId, String tag);

    /**
     * Whether the version is among the N newest of its application's
     * WORKING SET — the {@code DRAFT} and M2-reserved {@code ACTIVE}
     * window, the listing's default (issue #31, S7-02): the
     * LATEST_N-grant decision probe, resolved by V12's zero-padded
     * {@code sort_key} (never {@code created_at}). Terminal states
     * (ARCHIVED, REVOKED) never occupy a window slot — an archive
     * must not evict a live build from a tester's N. One indexed
     * count over {@code idx_versions_application_sort}; the version's
     * own lifecycle gates still apply at the engine's version layer.
     */
    boolean isWithinLatestWorkingSetN(UUID versionId, int n);

    /**
     * The application's full version catalog for grant-set
     * resolution (issue #31, S7-02): id, name, lifecycle status, and
     * sort key of every version, newest first — the effective-access
     * computation's single read (the portal catalog's backbone, per
     * the story's technical note). Authorization data only, the same
     * registry-read character; never an endpoint data path.
     */
    List<VersionCatalogEntry> catalogOfApplication(UUID applicationId);

    /** The version-record name callers may resolve; never the row itself. */
    record VersionRecord(UUID versionId, String versionName) {}

    /**
     * One application version as the grant resolver sees it (issue
     * #31, S7-02): the human name, the lifecycle status, and the
     * normalized sort key that orders the latest-N window.
     */
    record VersionCatalogEntry(UUID versionId, String versionName, String status, String sortKey) {}
}
