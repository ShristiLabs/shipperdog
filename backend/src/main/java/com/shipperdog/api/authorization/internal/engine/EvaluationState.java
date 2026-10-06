package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.authorization.internal.evaluator.UserRolesSnapshot;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The running resolution state of one decision (issue #30, S7-01):
 * the derived chain facts layers resolve from the context — the
 * ownership tuples of the addressed application, version, and
 * artifact, the subject's role snapshot, the linked tester person
 * record — memoized so each fact resolves at most once per decision
 * (the {@code UserRolesSnapshot} discipline: indexed point lookups,
 * never per-layer re-reads; the PRD 42 under-100 ms SLO). Layers read
 * resolved facts through the typed caches; no layer writes another's
 * conclusions.
 */
final class EvaluationState {

    private final ApplicationAccess applications;
    private final VersionAccess versions;
    private final ArtifactAccess artifacts;
    private final TesterAccess testers;
    private final UserRolesSnapshot roles;

    private final ConcurrentHashMap<UUID, Optional<ApplicationAccess.Ownership>> applicationOwnerships =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Optional<VersionAccess.VersionState>> versionStates =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Optional<ArtifactAccess.ArtifactState>> artifactStates =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Optional<UUID>> testerIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Optional<UUID>> testerOrganizations = new ConcurrentHashMap<>();

    EvaluationState(
            ApplicationAccess applications,
            VersionAccess versions,
            ArtifactAccess artifacts,
            TesterAccess testers,
            UserRolesSnapshot roles) {
        this.applications = applications;
        this.versions = versions;
        this.artifacts = artifacts;
        this.testers = testers;
        this.roles = roles;
    }

    private AccessDimension dimension;

    UserRolesSnapshot roles() {
        return roles;
    }

    /**
     * The access dimension the tenant layer resolved for the subject —
     * how the subject reaches the chain's resources — recorded once by
     * that layer and consumed by the later layers (the application
     * layer's visibility question is dimension-dependent).
     */
    record AccessDimension(Kind kind, UUID organizationId, UUID tenantId) {

        enum Kind {
            PLATFORM,
            ORGANIZATION,
            TENANT
        }

        static AccessDimension platform() {
            return new AccessDimension(Kind.PLATFORM, null, null);
        }
    }

    /** The dimension recorded by the tenant layer, or null when it has not run/passed yet. */
    AccessDimension dimension() {
        return dimension;
    }

    /** Records the resolved access dimension (the tenant layer's conclusion). */
    void recordDimension(AccessDimension resolved) {
        this.dimension = resolved;
    }

    /**
     * The owning dimensions of the application, deriving through the
     * version's own snapshot when only a version is addressed (the
     * engine walks the chain downward exactly once).
     */
    Optional<ApplicationAccess.Ownership> applicationOwnershipOf(UUID applicationId, UUID versionId) {
        if (applicationId != null) {
            return applicationOwnerships.computeIfAbsent(applicationId, applications::ownershipOf);
        }
        if (versionId != null) {
            return versionStateOf(versionId)
                    .map(state -> new ApplicationAccess.Ownership(
                            state.applicationId(), state.organizationId(), state.tenantId()));
        }
        return Optional.empty();
    }

    /** The version's resolved state (ownership dimensions plus lifecycle status). */
    Optional<VersionAccess.VersionState> versionStateOf(UUID versionId) {
        return versionStates.computeIfAbsent(versionId, versions::stateOf);
    }

    /** The artifact's resolved state (version, platform, lifecycle status). */
    Optional<ArtifactAccess.ArtifactState> artifactStateOf(UUID artifactId) {
        return artifactStates.computeIfAbsent(artifactId, artifacts::stateOf);
    }

    /** The linked tester person record id of the subject, when the subject is a tester. */
    Optional<UUID> testerIdOfSubject(UUID userId) {
        return testerIds.computeIfAbsent(userId, testers::testerIdOfUser);
    }

    /** The tester person record's owning organization, or empty when unknown. */
    Optional<UUID> organizationOfTester(UUID testerId) {
        return testerOrganizations.computeIfAbsent(testerId, testers::owningOrganizationOf);
    }
}
