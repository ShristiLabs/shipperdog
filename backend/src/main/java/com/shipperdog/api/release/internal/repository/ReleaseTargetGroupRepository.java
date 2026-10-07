package com.shipperdog.api.release.internal.repository;

import com.shipperdog.api.release.internal.entity.ReleaseTargetGroupEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetGroupId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the per-target tester-group rows (issue #55). A plain
 * {@link JpaRepository} by design — the via-parent rule: every row is
 * reachable only through the release target it hangs off (the composite
 * foreign key), which itself hangs off the seam-served release. Rows are
 * immutable once written.
 */
public interface ReleaseTargetGroupRepository extends JpaRepository<ReleaseTargetGroupEntity, ReleaseTargetGroupId> {

    /** The group rows of the given releases, in bulk: the detail view's single query. */
    List<ReleaseTargetGroupEntity> findByReleaseIdIn(Collection<UUID> releaseIds);

    /** The group rows of one release, deterministically ordered for the detail view. */
    List<ReleaseTargetGroupEntity> findByReleaseIdOrderByTenantIdAscGroupIdAsc(UUID releaseId);
}
