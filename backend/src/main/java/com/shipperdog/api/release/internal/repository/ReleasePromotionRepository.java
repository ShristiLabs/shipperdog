package com.shipperdog.api.release.internal.repository;

import com.shipperdog.api.release.internal.entity.ReleasePromotionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the promotion timeline rows (V33, issue #44, S10-05). A
 * plain {@link JpaRepository} by design — {@code release_promotions}
 * is the append-only child store of one seam-served release (the
 * {@code release_status_history} doctrine): every caller resolves the
 * release through the scoped parent first, so isolation is enforced
 * there and never re-derived here. Rows are written once (COMPLETED or
 * BLOCKED) and never updated or deleted; the declared operations are
 * the whole vocabulary.
 */
public interface ReleasePromotionRepository extends JpaRepository<ReleasePromotionEntity, UUID> {

    /** One release's promotion timeline, chronological (id ascending as the tiebreak). */
    List<ReleasePromotionEntity> findByReleaseIdOrderByOccurredAtAscIdAsc(UUID releaseId);
}
