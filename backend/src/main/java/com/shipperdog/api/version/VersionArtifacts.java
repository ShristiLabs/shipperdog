package com.shipperdog.api.version;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The version module's read-side seam for artifact slots (issue #20,
 * S5-01): the per-platform artifact lists of the version views and the
 * chronological artifact section of the history read. The version
 * module owns the shape and consumes it through an {@code
 * ObjectProvider} of this interface; the artifact module registers the
 * implementation — the exact dependency inversion of {@link
 * VersionDistributionContributor}, so the version module never learns
 * the artifact schema and the artifact module never duplicates the
 * version views.
 *
 * <p>Implementations run inside the request's already-bound scope (the
 * route interceptor bound it for application-addressing reads) and
 * answer with tuples only, never entities. The chronological order
 * (upload time, then id) is the contract: the history read renders the
 * list verbatim, and the per-platform slots group a per-platform
 * subsequence of the same order.
 */
public interface VersionArtifacts {

    /**
     * The artifacts of each requested version, chronological (upload
     * time ascending, id ascending as the tiebreak). Versions with no
     * artifacts are absent from the map (never a null value); an empty
     * request returns an empty map. The single bulk read backs both the
     * listing page and the detail/history reads — no per-version
     * fan-out.
     */
    Map<UUID, List<VersionArtifactSlot>> byVersionIds(Collection<UUID> versionIds);

    /**
     * One artifact of a version, as the version views render it:
     * identity, platform, the client file name, the stored size and
     * SHA-256 checksum, the lifecycle status (PRD 18), and the upload
     * timestamp — plus, since S5-02 (issue #21), the extraction tier
     * of PRD 8.5: the build number (CI-pinned at upload or extracted
     * from the binary), the extracted identity fields S5-05's
     * validation matching compares, and the extraction outcome
     * ({@code PENDING|SUCCESS|FAILED|BLOCKED}) so a slot never hides a
     * failed or blocked extraction behind VALIDATING; since S5-04
     * (issue #23), the build-provenance block the uploader asserted at
     * capture time (FR-005; {@code null} when none was asserted); and
     * since S5-05 (issue #24), the STRUCTURAL format verdict the
     * validation pipeline recorded (the channel-format fact of FR-006's
     * AAB/APK rule; {@code null} until a verdict exists) and the
     * validation outcome ({@code PENDING|PASSED|FAILED|BLOCKED}) so a
     * slot never hides a failed or blocked validation behind its
     * status either. Since S13-04 (#64) the slot also carries the
     * server-derived storage key — the byte source the upload-capable
     * distribution channels (Google Play) stream the stored binary
     * from; the artifact view's own key, now on the port tuple too.
     */
    record VersionArtifactSlot(
            UUID id,
            String platform,
            String fileName,
            long size,
            String sha256,
            String status,
            Instant uploadedAt,
            Long buildNumber,
            String bundleId,
            String versionName,
            String minimumOs,
            String extraction,
            ArtifactProvenance provenance,
            String format,
            String validation,
            String storageKey) {

        /** The S13-03 shape (no storage key on the tuple): the key answers null. */
        public VersionArtifactSlot(
                UUID id,
                String platform,
                String fileName,
                long size,
                String sha256,
                String status,
                Instant uploadedAt,
                Long buildNumber,
                String bundleId,
                String versionName,
                String minimumOs,
                String extraction,
                ArtifactProvenance provenance,
                String format,
                String validation) {
            this(
                    id,
                    platform,
                    fileName,
                    size,
                    sha256,
                    status,
                    uploadedAt,
                    buildNumber,
                    bundleId,
                    versionName,
                    minimumOs,
                    extraction,
                    provenance,
                    format,
                    validation,
                    null);
        }
    }

    /**
     * The provenance block of a version-view artifact slot (issue
     * #23, S5-04, FR-005): the five frozen, client-asserted build
     * claims — {@code null} members for claims not asserted. A
     * shape-only contract: the artifact module validates and stores
     * the values; the version module renders them.
     */
    record ArtifactProvenance(
            String gitCommit, String gitBranch, String pipeline, String ciRunUrl, String buildTimestamp) {}
}
