package com.shipperdog.api.application;

import java.util.Optional;
import java.util.UUID;

/**
 * The application module's public ownership-resolution surface for other
 * modules (issue #13): the RBAC evaluator resolves {@code app:}
 * permissions through the application's owning tenant/organization, and
 * the tenant module's web layer binds the request's tenant context for
 * application-addressing routes the same way. Implementation lives in
 * the module's {@code .internal.} packages behind the sanctioned
 * cross-tenant registry guard; cross-module callers must use only this
 * interface (enforced by ArchUnit).
 */
public interface ApplicationAccess {

    /**
     * The owning scope of the application, or empty when unknown. A null
     * {@code tenantId} means organization-owned — that ownership mode
     * arrives with S3-02; S3-01 creates tenant-owned rows only.
     */
    Optional<Ownership> ownershipOf(UUID applicationId);

    /** The ownership tuple callers may resolve; never the row itself. */
    record Ownership(UUID applicationId, UUID organizationId, UUID tenantId) {}
}
