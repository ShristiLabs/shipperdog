package com.shipperdog.api.artifact.internal.repository;

import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.artifact.ArtifactProvenanceLink;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity;
import com.shipperdog.api.tenant.CrossTenantByDesign;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
public interface ArtifactRegistryRepository
        extends JpaRepository<ArtifactEntity, UUID>, ArtifactAccess, ArtifactProvenanceLink {

    /**
     * Registry lookup by id for decision-input resolution — a declared
     * query, deliberately not the inherited (seam-enforced)
     * {@code findById}.
     */
    @Query("select a from ArtifactEntity a where a.id = :id")
    Optional<ArtifactEntity> findArtifactById(@Param("id") UUID id);

    /**
     * The manifest freeze's pin read (issue #57, S9-03): many artifacts
     * by id in one declared query — the same registry-read character as
     * {@link #findArtifactById}, never an endpoint data path.
     */
    @Query("select a from ArtifactEntity a where a.id in :ids")
    List<ArtifactEntity> findArtifactsByIds(@Param("ids") Collection<UUID> ids);

    /**
     * The version's distributable artifact for the platform (issue
     * #32, S7-03): the issuance-inputs lookup behind the decision
     * API's ALLOW path — one indexed descent on the artifact table,
     * newest first with the id tiebreaker, restricted to the
     * distributable lifecycle statuses (PRD 18) so the decision API
     * can never hand the portal an artifact whose bytes are not yet
     * settled, and to the DIRECT channel's format rule (S8-04, the
     * S5-05 recorded fact): an AAB is Play-only and never installs
     * from a download tap, so it is not a distributable download —
     * M1's only channel is the direct one. Authorization data in the
     * same registry-read character as {@link #findArtifactById};
     * never an endpoint data path.
     */
    @Query("select a from ArtifactEntity a"
            + " where a.versionId = :versionId and a.platform = :platform"
            + " and a.status in :distributable and a.format <> 'AAB'"
            + " order by a.createdAt desc, a.id asc")
    List<ArtifactEntity> findDistributableByVersionAndPlatform(
            @Param("versionId") UUID versionId,
            @Param("platform") ArtifactEntity.Platform platform,
            @Param("distributable") Collection<ArtifactEntity.Status> distributable);

    /**
     * The portal catalog's availability read (issue #36, S8-02): the
     * distributable artifacts of many versions in one PROJECTED query —
     * the columns the platform set and the history slots need, never
     * hydrated entities (the #36 review's projection finding, folded
     * with issue #37). The set form of {@link
     * #findDistributableByVersionAndPlatform}'s availability predicate
     * — including the S8-04 direct-channel format rule (no AAB row is
     * ever a slot, a platform, or a download: an AAB cannot install
     * from a tap) — composed with the effective-set resolution the
     * engine already sanctioned; same registry-read character, never
     * an endpoint data path. Newest first with the id tiebreaker, so
     * the first row per (version, platform) is the artifact a
     * download would serve.
     */
    @Query("select a.versionId as versionId, a.platform as platform, a.id as id,"
            + " a.buildNumber as buildNumber, a.size as size, a.createdAt as createdAt"
            + " from ArtifactEntity a"
            + " where a.versionId in :versionIds and a.status in :distributable and a.format <> 'AAB'"
            + " order by a.versionId asc, a.createdAt desc, a.id asc")
    List<InstallableArtifactView> findInstallableByVersionIds(
            @Param("versionIds") Collection<UUID> versionIds,
            @Param("distributable") Collection<ArtifactEntity.Status> distributable);

    /** The projected columns of {@link #findInstallableByVersionIds}; closed projection, no entity hydration. */
    interface InstallableArtifactView {

        UUID getVersionId();

        ArtifactEntity.Platform getPlatform();

        UUID getId();

        Long getBuildNumber();

        long getSize();

        Instant getCreatedAt();
    }

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

    /** The manifest freeze's binary pins; tuples only, never rows. */
    @Override
    default Map<UUID, ArtifactPin> pinsOf(Collection<UUID> artifactIds) {
        if (artifactIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ArtifactPin> pins = new LinkedHashMap<>();
        for (ArtifactEntity artifact : findArtifactsByIds(artifactIds)) {
            pins.put(
                    artifact.getId(),
                    new ArtifactPin(
                            artifact.getId(),
                            artifact.getVersionId(),
                            artifact.getPlatform().name(),
                            artifact.getFileName(),
                            artifact.getBuildNumber(),
                            artifact.getSha256(),
                            artifact.getSize(),
                            artifact.getCreatedAt()));
        }
        return pins;
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

    /** The catalog's per-platform availability read; platform names keyed by version id. */
    @Override
    default Map<UUID, Set<String>> distributablePlatformsOfVersions(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<String>> platformsByVersion = new LinkedHashMap<>();
        for (InstallableArtifactView slot : findInstallableByVersionIds(
                versionIds, EnumSet.of(ArtifactEntity.Status.READY, ArtifactEntity.Status.DISTRIBUTED))) {
            platformsByVersion
                    .computeIfAbsent(slot.getVersionId(), key -> new LinkedHashSet<>())
                    .add(slot.getPlatform().name());
        }
        return platformsByVersion;
    }

    /**
     * The history page's per-platform rows (issue #37, S8-03): one
     * slot per (version, platform) — the newest distributable artifact,
     * which the projection's order makes the first row of the group —
     * with the build metadata the page renders.
     */
    @Override
    default Map<UUID, List<InstallableSlot>> installableSlotsOfVersions(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<InstallableSlot>> slotsByVersion = new LinkedHashMap<>();
        Set<String> platformsSeen = new HashSet<>();
        for (InstallableArtifactView slot : findInstallableByVersionIds(
                versionIds, EnumSet.of(ArtifactEntity.Status.READY, ArtifactEntity.Status.DISTRIBUTED))) {
            String groupKey = slot.getVersionId() + "/" + slot.getPlatform();
            if (!platformsSeen.add(groupKey)) {
                continue; // only the newest artifact per (version, platform)
            }
            slotsByVersion
                    .computeIfAbsent(slot.getVersionId(), key -> new ArrayList<>())
                    .add(new InstallableSlot(
                            slot.getPlatform().name(),
                            slot.getId(),
                            slot.getBuildNumber(),
                            slot.getSize(),
                            slot.getCreatedAt()));
        }
        return slotsByVersion;
    }

    /**
     * The second sanctioned cross-scope operation (issue #53, S12-03,
     * FR-022): verified-source provenance BACKFILL. A signature-
     * verified CI webhook is not request-scoped — the commit match
     * spans every organization by design — so the enrichment reads and
     * writes ride this guard, declared and review-critical exactly
     * like the decision-input reads above. Backfill-only by
     * construction: the update's predicate re-checks that every
     * offered value fills an ABSENT field, so an asserted claim is
     * never replaced and a replayed delivery is a no-op.
     *
     * <p>SETTLED artifacts only (READY/FAILED/DISTRIBUTED/ARCHIVED):
     * while a binary is still UPLOADING or VALIDATING, the async
     * extraction/validation pipeline owns the row and its entity
     * merges would clobber a backfill racing them — an artifact still
     * mid-pipeline answers this lookup empty, and the CI flows that
     * trigger enrichment (the upload action and CLI) poll to
     * settlement before their runs complete anyway. The residual
     * window (a revalidation re-entering the pipeline exactly as a
     * delivery lands) is the documented S12-04 hardening note.
     */
    @Query(
            value = "select id from artifacts"
                    + " where provenance ->> 'gitCommit' = :gitCommit"
                    + " and status in ('READY', 'FAILED', 'DISTRIBUTED', 'ARCHIVED')"
                    + " and ((provenance ->> 'ciRunUrl') is null or (provenance ->> 'pipeline') is null)"
                    + " order by created_at asc, id asc",
            nativeQuery = true)
    List<UUID> findIdsWithBackfillableProvenanceByCommit(@Param("gitCommit") String gitCommit);

    /**
     * The ci_run_url backfill leg: fills the ONE field, only when the
     * caller offers it non-null AND the document lacks it (the
     * predicate re-check makes a concurrent enrichment a no-op, never
     * an overwrite). Per-field, deliberately: a row with its pipeline
     * already asserted still receives its missing run URL -- the
     * backfill is of ABSENT fields, not of whole rows.
     */
    @Modifying
    @Query(
            value = "update artifacts"
                    + " set provenance = provenance || jsonb_build_object('ciRunUrl', cast(:ciRunUrl as text))"
                    + " where id = :id"
                    + " and provenance ->> 'gitCommit' = :gitCommit"
                    + " and cast(:ciRunUrl as text) is not null"
                    + " and (provenance ->> 'ciRunUrl') is null",
            nativeQuery = true)
    int backfillCiRunUrlIfAbsent(
            @Param("id") UUID id, @Param("gitCommit") String gitCommit, @Param("ciRunUrl") String ciRunUrl);

    /**
     * The pipeline backfill leg: the same contract, the workflow-name
     * field. Unknown future keys in the document pass through
     * untouched; an asserted pipeline is never replaced.
     */
    @Modifying
    @Query(
            value = "update artifacts"
                    + " set provenance = provenance || jsonb_build_object('pipeline', cast(:pipeline as text))"
                    + " where id = :id"
                    + " and provenance ->> 'gitCommit' = :gitCommit"
                    + " and cast(:pipeline as text) is not null"
                    + " and (provenance ->> 'pipeline') is null",
            nativeQuery = true)
    int backfillPipelineIfAbsent(
            @Param("id") UUID id, @Param("gitCommit") String gitCommit, @Param("pipeline") String pipeline);

    /** {@link ArtifactProvenanceLink}'s contract: the ids actually enriched, honestly answered. */
    @Override
    default List<UUID> backfillProvenanceByCommit(String gitCommit, String ciRunUrl, String pipeline) {
        List<UUID> enriched = new ArrayList<>();
        for (UUID id : findIdsWithBackfillableProvenanceByCommit(gitCommit)) {
            // Both legs always run: the first fill must not
            // short-circuit the second (an absent field on either
            // side of the row still gets its backfill).
            boolean runUrlFilled = backfillCiRunUrlIfAbsent(id, gitCommit, ciRunUrl) == 1;
            boolean pipelineFilled = backfillPipelineIfAbsent(id, gitCommit, pipeline) == 1;
            if (runUrlFilled || pipelineFilled) {
                enriched.add(id);
            }
        }
        return enriched;
    }
}
