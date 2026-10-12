package com.shipperdog.api.analytics.internal.repository;

import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity;
import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity.Result;
import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity.Type;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Storage for the SDK event rows (V38). A plain {@link JpaRepository}
 * — the release's events span every targeted tenant's reporters, so
 * the per-release reads are cross-tenant by design (the entity's own
 * doctrine) and the ingest write path is the one route, validated
 * before any row lands. No delete or update path exists; corrections
 * are new events.
 */
public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEventEntity, UUID> {

    /** The release's events of one type, oldest first — the bounded scan every device-side formula folds. */
    List<AnalyticsEventEntity> findByReleaseIdAndEventTypeOrderByOccurredAtAsc(UUID releaseId, Type eventType);

    /** The release's INSTALL reports by result — the installations count and the success rate's halves. */
    long countByReleaseIdAndEventTypeAndResult(UUID releaseId, Type eventType, Result result);

    /** The release's INSTALL reports — the denominator of the uninstall rate. */
    long countByReleaseIdAndEventType(UUID releaseId, Type eventType);

    /**
     * The release's session arithmetic in one pass: total sessions,
     * crash sessions, ANR sessions over every SESSION report — the
     * crash-free and ANR rates' numerator and denominator (the seam
     * registrants' own query; an empty answer means no sessions yet,
     * the NO_DATA case).
     */
    @Query("select coalesce(sum(e.sessions), 0), coalesce(sum(e.crashSessions), 0), coalesce(sum(e.anrSessions), 0) "
            + "from AnalyticsEventEntity e "
            + "where e.releaseId = :releaseId and e.eventType = :eventType")
    List<Object[]> sessionArithmeticOf(@Param("releaseId") UUID releaseId, @Param("eventType") Type eventType);

    /**
     * The (device, event id) pairs already landed for the release's
     * batch (S15-01, V54) — the dedup probe the ingest re-filters a
     * batch through before any write, so at-least-once delivery lands
     * at most once per (device, release, event id). A device-less
     * event dedups against the null device (the unique index's
     * sentinel semantics, mirrored here).
     */
    @Query("select e.deviceId, e.eventId from AnalyticsEventEntity e "
            + "where e.releaseId = :releaseId and e.eventId in :eventIds")
    List<Object[]> landedClientIds(@Param("releaseId") UUID releaseId, @Param("eventIds") Collection<UUID> eventIds);
}
