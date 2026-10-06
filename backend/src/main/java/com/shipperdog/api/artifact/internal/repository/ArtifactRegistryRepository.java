package com.shipperdog.api.artifact.internal.repository;

import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity;
import com.shipperdog.api.tenant.CrossTenantByDesign;
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
}
