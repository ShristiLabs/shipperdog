package com.shipperdog.api.release.internal.repository;

import com.shipperdog.api.release.internal.entity.ReleaseTargetEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the release-target rows (issue #55). A plain
 * {@link JpaRepository} by design — {@code release_targets} is not a
 * tenant-scoped surface but the private child store of one: every caller
 * resolves the release through the seam first
 * ({@link ReleaseScopedRepository}, inside the request's bound scope)
 * and then asks about exactly that release's rows, so isolation is
 * enforced at the parent and never re-derived here (the eighth
 * amendment's plain-child doctrine; the entity's javadoc records the
 * same standing). Rows are immutable once written — S9-01 creates the
 * whole set atomically with its release, and no story deletes one.
 */
public interface ReleaseTargetRepository extends JpaRepository<ReleaseTargetEntity, ReleaseTargetId> {

    /** The target rows of the given releases, in bulk: the listing view's single query per page. */
    List<ReleaseTargetEntity> findByReleaseIdIn(Collection<UUID> releaseIds);

    /** The target rows of one release, tenant-ordered for the stable detail view. */
    List<ReleaseTargetEntity> findByReleaseIdOrderByTenantIdAsc(UUID releaseId);
}
