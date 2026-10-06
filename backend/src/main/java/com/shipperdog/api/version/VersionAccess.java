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

    /** The ownership tuple callers may resolve; never the row itself. */
    record VersionOwnership(UUID versionId, UUID applicationId, UUID organizationId, UUID tenantId) {}
}
