package com.shipperdog.api.release.internal.repository;

import com.shipperdog.api.release.internal.entity.ReleaseManifestEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the per-release frozen manifest (issue #57, S9-03, PRD
 * 16). A plain {@link JpaRepository} by design —
 * {@code release_manifests} is not a tenant-scoped surface but the
 * immutable child record of one: every caller resolves the release
 * through the seam first ({@code ReleaseScopedRepository}, inside the
 * request's bound scope) and then reads exactly that release's row,
 * so isolation is enforced at the parent and never re-derived here
 * (the V25 doctrine, applied to the manifest).
 *
 * <p>Rows are write-once history (PRD P5 + V26's
 * {@code uq_release_manifests_release}): the freeze inserts, the
 * routes read, and nothing ever updates or deletes a row — the
 * declared operations are the whole vocabulary, and the entity carries
 * no setters a caller could abuse.
 */
public interface ReleaseManifestRepository extends JpaRepository<ReleaseManifestEntity, UUID> {

    /** The release's frozen manifest; empty until the release first crossed into APPROVED. */
    Optional<ReleaseManifestEntity> findByReleaseId(UUID releaseId);
}
