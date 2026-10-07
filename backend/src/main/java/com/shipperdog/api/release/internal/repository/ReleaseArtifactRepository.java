package com.shipperdog.api.release.internal.repository;

import com.shipperdog.api.release.internal.entity.ReleaseArtifactEntity;
import com.shipperdog.api.release.internal.entity.ReleaseArtifactId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the per-platform artifact rows (issue #55). A plain
 * {@link JpaRepository} by design — a plain child of the seam-served
 * release under the eighth amendment: no route addresses an artifact
 * slot by itself, so isolation is enforced at the parent. Rows are
 * immutable once written (INV-003/INV-008's spirit: the artifact set a
 * release was created with is the set it keeps).
 */
public interface ReleaseArtifactRepository extends JpaRepository<ReleaseArtifactEntity, ReleaseArtifactId> {

    /** The artifact rows of the given releases, in bulk: the listing view's single query per page. */
    List<ReleaseArtifactEntity> findByReleaseIdIn(Collection<UUID> releaseIds);

    /** The artifact rows of one release, platform-ordered for the stable detail view. */
    List<ReleaseArtifactEntity> findByReleaseIdOrderByPlatformAscArtifactIdAsc(UUID releaseId);
}
