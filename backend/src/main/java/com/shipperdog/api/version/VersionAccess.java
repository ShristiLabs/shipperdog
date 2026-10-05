package com.shipperdog.api.version;

import java.time.Instant;
import java.util.UUID;

/**
 * The version module's public surface for other modules (issue #17):
 * the application registry's audited ownership-scope transition (issue
 * #14, S3-02) moves an application between owning dimensions, and the
 * application's versions must move with it — a version row left in the
 * previous scope would strand outside the owning scope the isolation
 * seam serves, invisible to every later request (ADR-0002 third
 * amendment doctrine applied to the rows the transition now drags
 * along). Implementation lives in the module's {@code .internal.}
 * packages behind the sanctioned cross-tenant guard; cross-module
 * callers must use only this interface (enforced by ArchUnit).
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
}
