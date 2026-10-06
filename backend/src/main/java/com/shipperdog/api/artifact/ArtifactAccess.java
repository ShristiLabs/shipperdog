package com.shipperdog.api.artifact;

import java.util.Optional;
import java.util.UUID;

/**
 * The artifact module's public surface for the authorization engine
 * (issue #30, S7-01): the E7 chain's artifact-layer input — "Is the
 * artifact available?" (PRD 12) needs the artifact's version,
 * platform, and lifecycle status resolved BEFORE any request scope is
 * bound (the engine decides before the route serves), exactly the
 * authorization-data character of the application and version
 * registries' ownership reads. Implementation lives in the module's
 * {@code .internal.} packages behind the sanctioned cross-tenant
 * guard; cross-module callers must use only this interface (enforced
 * by ArchUnit). Tuples only, never rows, never an endpoint data path.
 */
public interface ArtifactAccess {

    /**
     * The artifact's resolved decision inputs, or empty when unknown:
     * the owning version, the platform, and the lifecycle status
     * (PRD 18's {@code UPLOADING | VALIDATING | READY | FAILED |
     * DISTRIBUTED | ARCHIVED}).
     */
    Optional<ArtifactState> stateOf(UUID artifactId);

    /** The artifact tuple callers may resolve; never the row itself. */
    record ArtifactState(UUID artifactId, UUID versionId, String platform, String status) {}
}
