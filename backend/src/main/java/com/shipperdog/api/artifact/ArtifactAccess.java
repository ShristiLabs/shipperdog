package com.shipperdog.api.artifact;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * The version's distributable artifact for the platform, with its
     * storage key reference (issue #32, S7-03, acceptance criterion
     * 4): the signed-URL issuance inputs the decision API hands the
     * portal on an ALLOWED download — the artifact id and the object
     * storage key (S8-04 mints the signed URL from exactly these).
     * Only artifacts whose lifecycle is distributable ({@code READY}
     * or {@code DISTRIBUTED}, PRD 18) resolve; newest first with the
     * id as the deterministic tiebreaker. Authorization data in the
     * same registry-read character as {@link #stateOf}; never an
     * endpoint data path — the engine's own artifact layer stays the
     * availability authority.
     */
    Optional<IssuanceInputs> issuanceInputsOf(UUID versionId, String platform);

    /**
     * The distributable platforms of many versions at once (issue #36,
     * S8-02): the portal catalog's per-platform availability read — for
     * each version id supplied, the set of platforms carrying at least
     * one distributable ({@code READY} or {@code DISTRIBUTED}, PRD 18)
     * artifact. One query over the caller's already-authorized version
     * set, the set form of {@link #issuanceInputsOf}'s availability
     * predicate; the same registry-read character, never an endpoint
     * data path — the catalog composes it with the effective-set
     * resolution the engine sanctioned.
     */
    Map<UUID, Set<String>> distributablePlatformsOfVersions(Collection<UUID> versionIds);

    /**
     * The installable platform slots of many versions at once (issue
     * #37, S8-03): the version-history page's per-platform rows — for
     * each version id supplied, one slot per platform carrying at
     * least one distributable ({@code READY} or {@code DISTRIBUTED},
     * PRD 18) artifact, resolved to the NEWEST such artifact (the one
     * a download would serve) with the build metadata the page shows:
     * the build number from extraction, the byte size, and the upload
     * timestamp. One projected query over the caller's
     * already-authorized version set — the row form of {@link
     * #distributablePlatformsOfVersions}' availability predicate;
     * authorization data in the same registry-read character, never an
     * endpoint data path.
     */
    Map<UUID, List<InstallableSlot>> installableSlotsOfVersions(Collection<UUID> versionIds);

    /**
     * One platform's installable artifact as the history page shows it
     * (issue #37, S8-03): the platform, the artifact a download would
     * serve, and its build metadata. {@code buildNumber} is nullable —
     * extraction metadata is settled best-effort, and a distributable
     * artifact without one still installs.
     */
    record InstallableSlot(String platform, UUID artifactId, Long buildNumber, long sizeBytes, Instant uploadedAt) {}

    /** The artifact tuple callers may resolve; never the row itself. */
    record ArtifactState(UUID artifactId, UUID versionId, String platform, String status) {}

    /**
     * The signed-URL issuance inputs of one distributable artifact
     * (issue #32, S7-03): the artifact id and the PRD 24 storage key
     * reference, plus the resolved platform and lifecycle status for
     * the caller's own reporting.
     */
    record IssuanceInputs(UUID artifactId, UUID versionId, String platform, String status, String storageKey) {}
}
