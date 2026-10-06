package com.shipperdog.api.artifact.internal.repository;

import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity;
import com.shipperdog.api.tenant.CrossTenantByDesign;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The cross-scope guard over {@code artifacts} (issue #30, S7-01):
 * deliberately cross-tenant, explicitly marked
 * {@link CrossTenantByDesign} per ADR-0002 (the registry-guard pattern
 * of the application and version modules; see the ADR's thirteenth
 * amendment).
 *
 * <p>Exactly one sanctioned operation spans scopes, declared here,
 * review-critical and in one place: <strong>decision-input
 * resolution</strong> — the E7 authorization chain's artifact layer
 * ("Is the artifact available?", PRD 11/12) must resolve the
 * artifact's version, platform, and lifecycle status BEFORE any
 * request scope exists, because the engine decides before the route
 * serves. This is authorization data in the same sense as the
 * registries' ownership resolution ({@code findApplicationById},
 * {@code findVersionById}): a point lookup by id that answers a
 * tuple, never a row, never an endpoint data path.
 *
 * <p>Like every {@link CrossTenantByDesign} repository, this
 * interface uses only its own declared queries — the inherited
 * {@code JpaRepository} surface stays seam-enforced for
 * {@code OrganizationScope} entities (ADR-0002 amendment) and would
 * refuse without a bound context. Reads and writes of artifact rows
 * for endpoint data go through {@link ArtifactScopedRepository}, the
 * tenant-isolation seam.
 */
@CrossTenantByDesign
public interface ArtifactRegistryRepository extends JpaRepository<ArtifactEntity, UUID>, ArtifactAccess {

    /**
     * Registry lookup by id for decision-input resolution — a declared
     * query, deliberately not the inherited (seam-enforced)
     * {@code findById}.
     */
    @Query("select a from ArtifactEntity a where a.id = :id")
    Optional<ArtifactEntity> findArtifactById(@Param("id") UUID id);

    /**
     * The version's distributable artifact for the platform (issue
     * #32, S7-03): the issuance-inputs lookup behind the decision
     * API's ALLOW path — one indexed descent on the artifact table,
     * newest first with the id tiebreaker, restricted to the
     * distributable lifecycle statuses (PRD 18) so the decision API
     * can never hand the portal a storage key whose bytes are not yet
     * settled. Authorization data in the same registry-read character
     * as {@link #findArtifactById}; never an endpoint data path.
     */
    @Query("select a from ArtifactEntity a"
            + " where a.versionId = :versionId and a.platform = :platform"
            + " and a.status in :distributable"
            + " order by a.createdAt desc, a.id asc")
    List<ArtifactEntity> findDistributableByVersionAndPlatform(
            @Param("versionId") UUID versionId,
            @Param("platform") ArtifactEntity.Platform platform,
            @Param("distributable") Collection<ArtifactEntity.Status> distributable);

    /** Decision-input resolution for the E7 authorization chain; tuples only, never rows. */
    @Override
    default Optional<ArtifactState> stateOf(UUID artifactId) {
        return findArtifactById(artifactId)
                .map(artifact -> new ArtifactState(
                        artifact.getId(),
                        artifact.getVersionId(),
                        artifact.getPlatform().name(),
                        artifact.getStatus().name()));
    }

    /** The issuance-inputs resolution of the decision API; tuples only, never rows. */
    @Override
    default Optional<IssuanceInputs> issuanceInputsOf(UUID versionId, String platform) {
        ArtifactEntity.Platform pinned;
        try {
            pinned = ArtifactEntity.Platform.valueOf(platform.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return findDistributableByVersionAndPlatform(
                        versionId, pinned, EnumSet.of(ArtifactEntity.Status.READY, ArtifactEntity.Status.DISTRIBUTED))
                .stream()
                .findFirst()
                .map(artifact -> new IssuanceInputs(
                        artifact.getId(),
                        artifact.getVersionId(),
                        artifact.getPlatform().name(),
                        artifact.getStatus().name(),
                        artifact.getStorageKey()));
    }
}
