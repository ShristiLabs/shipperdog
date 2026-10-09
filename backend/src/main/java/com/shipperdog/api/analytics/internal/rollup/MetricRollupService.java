package com.shipperdog.api.analytics.internal.rollup;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The aggregation engine (issue #49, S11-04, FR-029): recomputes the
 * dictionary's metric rollups from the source facts — the S8-04
 * download trail, the S10-01 approval decisions, the promotion
 * timeline's ring occupancy, and the SDK event store — into the
 * {@code metric_hourly}/{@code metric_daily} tables, one bounded
 * window at a time.
 *
 * <p><strong>Idempotent by recompute.</strong> Every statement
 * re-derives a bucket's whole value from the facts and upserts it
 * (ON CONFLICT overwrites): a re-run writes the same numbers, and a
 * late fact inside the window is folded by the next pass over that
 * window. Counts aggregate from their events; rates and durations
 * RECOMPUTE at each grain (rates never sum). Sources are append-only
 * or monotone — audit rows never change, decisions only land,
 * promotions only commit — so upsert-only recompute is safe; nothing
 * that once fed a bucket can vanish beneath it. The one mutable
 * input, the target set (a membership row can be removed), only
 * shrinks a denominator the recompute re-derives.
 *
 * <p><strong>Bounded windows only.</strong> Every statement filters
 * its fact scan to {@code [from, to)}; the hourly job runs the
 * trailing lookback, the backfill trigger runs an explicit historical
 * window, and no path ever scans unbounded history.
 *
 * <p><strong>Ring attribution.</strong> An event's ring is the ring
 * the release occupied when it occurred — the starting ring until
 * the first COMPLETED promotion, then that hop's to-ring (the
 * timeline's own semantics; docs/analytics.md documents the grain).
 * Metrics that carry no ring dimension store the nil uuid sentinel.
 */
@Service
public class MetricRollupService {

    /** The nil-uuid sentinel of V38: a metric row with no ring dimension. */
    static final String NIL_RING = "00000000-0000-0000-0000-000000000000";

    /** The ring-attribution lateral every event-shaped statement shares. */
    private static final String RING_AT_EVENT = """
            LEFT JOIN LATERAL (
                SELECT rp.to_ring_id FROM release_promotions rp
                WHERE rp.release_id = r.id AND rp.status = 'COMPLETED' AND rp.occurred_at <= e.occurred_at
                ORDER BY rp.occurred_at DESC LIMIT 1
            ) hop ON true
            """;

    private final NamedParameterJdbcTemplate jdbc;

    MetricRollupService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Recomputes every dictionary metric for one application over
     * {@code [from, to)}, at both grains. The backfill trigger and
     * the hourly job both land here — the same computation, different
     * windows. Returns the number of rollup rows written or updated.
     */
    @Transactional
    public int recompute(UUID applicationId, Instant from, Instant to) {
        return recomputeGrain("hour", applicationId, from, to) + recomputeGrain("day", applicationId, from, to);
    }

    /** The hourly job's pass (the scheduler's call): the lookback window over every application. */
    @Transactional
    public int runLookback(Instant now, Duration lookback) {
        Instant from = now.minus(lookback);
        List<UUID> applications = jdbc.getJdbcTemplate()
                .queryForList("SELECT DISTINCT application_id FROM releases ORDER BY application_id", UUID.class);
        int written = 0;
        for (UUID applicationId : applications) {
            written += recompute(applicationId, from, now);
        }
        return written;
    }

    private int recomputeGrain(String grain, UUID applicationId, Instant from, Instant to) {
        // Timestamps bind through the JDBC bridge type: pgjdbc cannot
        // infer a SQL type for a raw Instant. The grain is INLINED from
        // the fixed whitelist ("hour"/"day"), never bound: two bound
        // placeholders inside date_trunc are distinct parameters to
        // PostgreSQL, and the SELECT/GROUP BY pair would stop matching.
        Map<String, Object> bound =
                Map.of("applicationId", applicationId, "from", Timestamp.from(from), "to", Timestamp.from(to));
        MapSqlParameterSource parameters = new MapSqlParameterSource(bound);
        String table = tableOf(grain);
        int written = 0;
        written += grainUpdate(jdbc, grain, parameters, downloads(table));
        written += grainUpdate(jdbc, grain, parameters, eventCount(table, "INSTALLATIONS", "'INSTALL'"));
        written += grainUpdate(jdbc, grain, parameters, installSuccessRate(table));
        written += grainUpdate(jdbc, grain, parameters, crashFreeSessions(table));
        written += grainUpdate(jdbc, grain, parameters, anrRate(table));
        written += grainUpdate(jdbc, grain, parameters, uninstallRate(table));
        written += grainUpdate(jdbc, grain, parameters, testerEngagement(table));
        written += grainUpdate(jdbc, grain, parameters, approvalDuration(table));
        written += grainUpdate(jdbc, grain, parameters, timeInRing(table));
        return written;
    }

    /** Substitutes the inlined grain into a statement's {GRAIN} markers and runs it. */
    private static int grainUpdate(
            NamedParameterJdbcTemplate jdbc, String grain, MapSqlParameterSource bound, String sql) {
        return jdbc.update(sql.replace("{GRAIN}", grain), bound);
    }

    private static String tableOf(String grain) {
        return "hour".equals(grain) ? "metric_hourly" : "metric_daily";
    }

    /** The shared insert head: the rollup row's columns in the platform's order. */
    private static String insertInto(String table) {
        return "INSERT INTO "
                + table
                + " (organization_id, tenant_id, application_id, release_id, ring_id, metric, bucket_start, value, computed_at) ";
    }

    private static String upsert() {
        return """
                ON CONFLICT (application_id, metric, bucket_start, release_id, ring_id)
                    DO UPDATE SET value = EXCLUDED.value, computed_at = now()
                """;
    }

    // -------------------------------------------------------------------------
    // The dictionary's statements — every one bounded, grouped at the
    // grain, and upserted over the bucket's whole recomputed value.
    // -------------------------------------------------------------------------

    /** DOWNLOADS: the release's artifacts' allowed download issuances (the S8-04 trail). */
    private static String downloads(String table) {
        return insertInto(table) + """
                SELECT r.organization_id, r.tenant_id, r.application_id, r.id,
                       COALESCE(hop.to_ring_id, r.starting_ring_id),
                       'DOWNLOADS', date_trunc('{GRAIN}', a.occurred_at), count(*)::float8, now()
                FROM audit_events a
                JOIN release_artifacts ra ON ra.artifact_id = a.artifact_id
                JOIN releases r ON r.id = ra.release_id AND r.application_id = :applicationId
                LEFT JOIN LATERAL (
                    SELECT rp.to_ring_id FROM release_promotions rp
                    WHERE rp.release_id = r.id AND rp.status = 'COMPLETED' AND rp.occurred_at <= a.occurred_at
                    ORDER BY rp.occurred_at DESC LIMIT 1
                ) hop ON true
                WHERE a.event_type = 'DOWNLOAD_ISSUED' AND a.success
                  AND a.occurred_at >= :from AND a.occurred_at < :to
                GROUP BY r.organization_id, r.tenant_id, r.application_id, r.id,
                         COALESCE(hop.to_ring_id, r.starting_ring_id), date_trunc('{GRAIN}', a.occurred_at)
                """ + upsert();
    }

    /** INSTALLATIONS (and the shared shape of every single-type event count): the reports that landed in the bucket. */
    private static String eventCount(String table, String metric, String typeLiteral) {
        return insertInto(table) + """
                SELECT r.organization_id, r.tenant_id, r.application_id, r.id,
                       COALESCE(hop.to_ring_id, r.starting_ring_id),
                       '""" + metric + """
                ', date_trunc('{GRAIN}', e.occurred_at), count(*)::float8, now()
                FROM analytics_events e
                JOIN releases r ON r.id = e.release_id AND r.application_id = :applicationId
                """ + RING_AT_EVENT + """
                WHERE e.event_type = """ + typeLiteral + """
                  AND e.occurred_at >= :from AND e.occurred_at < :to
                GROUP BY r.organization_id, r.tenant_id, r.application_id, r.id,
                         COALESCE(hop.to_ring_id, r.starting_ring_id), date_trunc('{GRAIN}', e.occurred_at)
                """ + upsert();
    }

    /** INSTALL_SUCCESS_RATE: succeeded / reported installs, recomputed at the grain. */
    private static String installSuccessRate(String table) {
        return insertInto(table) + """
                SELECT r.organization_id, r.tenant_id, r.application_id, r.id,
                       COALESCE(hop.to_ring_id, r.starting_ring_id),
                       'INSTALL_SUCCESS_RATE', date_trunc('{GRAIN}', e.occurred_at),
                       count(*) FILTER (WHERE e.result = 'SUCCEEDED')::float8 / count(*)::float8, now()
                FROM analytics_events e
                JOIN releases r ON r.id = e.release_id AND r.application_id = :applicationId
                """ + RING_AT_EVENT + """
                WHERE e.event_type = 'INSTALL'
                  AND e.occurred_at >= :from AND e.occurred_at < :to
                GROUP BY r.organization_id, r.tenant_id, r.application_id, r.id,
                         COALESCE(hop.to_ring_id, r.starting_ring_id), date_trunc('{GRAIN}', e.occurred_at)
                """ + upsert();
    }

    /** CRASH_FREE_SESSIONS: 1 - crash sessions / total sessions over the bucket's SESSION reports. */
    private static String crashFreeSessions(String table) {
        return insertInto(table) + """
                SELECT r.organization_id, r.tenant_id, r.application_id, r.id,
                       COALESCE(hop.to_ring_id, r.starting_ring_id),
                       'CRASH_FREE_SESSIONS', date_trunc('{GRAIN}', e.occurred_at),
                       1.0 - sum(e.crash_sessions)::float8 / sum(e.sessions)::float8, now()
                FROM analytics_events e
                JOIN releases r ON r.id = e.release_id AND r.application_id = :applicationId
                """ + RING_AT_EVENT + """
                WHERE e.event_type = 'SESSION'
                  AND e.occurred_at >= :from AND e.occurred_at < :to
                GROUP BY r.organization_id, r.tenant_id, r.application_id, r.id,
                         COALESCE(hop.to_ring_id, r.starting_ring_id), date_trunc('{GRAIN}', e.occurred_at)
                """ + upsert();
    }

    /** ANR_RATE: ANR sessions / total sessions over the bucket's SESSION reports. */
    private static String anrRate(String table) {
        return insertInto(table) + """
                SELECT r.organization_id, r.tenant_id, r.application_id, r.id,
                       COALESCE(hop.to_ring_id, r.starting_ring_id),
                       'ANR_RATE', date_trunc('{GRAIN}', e.occurred_at),
                       sum(e.anr_sessions)::float8 / sum(e.sessions)::float8, now()
                FROM analytics_events e
                JOIN releases r ON r.id = e.release_id AND r.application_id = :applicationId
                """ + RING_AT_EVENT + """
                WHERE e.event_type = 'SESSION'
                  AND e.occurred_at >= :from AND e.occurred_at < :to
                GROUP BY r.organization_id, r.tenant_id, r.application_id, r.id,
                         COALESCE(hop.to_ring_id, r.starting_ring_id), date_trunc('{GRAIN}', e.occurred_at)
                """ + upsert();
    }

    /** UNINSTALL_RATE: uninstall reports / install reports (no installs in a bucket -> no row: NO_DATA at read). */
    private static String uninstallRate(String table) {
        return insertInto(table) + """
                SELECT r.organization_id, r.tenant_id, r.application_id, r.id,
                       COALESCE(hop.to_ring_id, r.starting_ring_id),
                       'UNINSTALL_RATE', date_trunc('{GRAIN}', e.occurred_at),
                       count(*) FILTER (WHERE e.event_type = 'UNINSTALL')::float8
                           / count(*) FILTER (WHERE e.event_type = 'INSTALL')::float8, now()
                FROM analytics_events e
                JOIN releases r ON r.id = e.release_id AND r.application_id = :applicationId
                """ + RING_AT_EVENT + """
                WHERE e.event_type IN ('INSTALL', 'UNINSTALL')
                  AND e.occurred_at >= :from AND e.occurred_at < :to
                GROUP BY r.organization_id, r.tenant_id, r.application_id, r.id,
                         COALESCE(hop.to_ring_id, r.starting_ring_id), date_trunc('{GRAIN}', e.occurred_at)
                HAVING count(*) FILTER (WHERE e.event_type = 'INSTALL') > 0
                """ + upsert();
    }

    /** TESTER_ENGAGEMENT: the bucket's distinct targeted downloaders / the release's distinct targeted testers. */
    private static String testerEngagement(String table) {
        return """
                WITH targeted AS (
                    SELECT t.release_id, m.tester_id
                    FROM release_targets t
                    JOIN releases r ON r.id = t.release_id AND r.application_id = :applicationId
                    JOIN tenant_tester_memberships m ON m.tenant_id = t.tenant_id
                    WHERE NOT EXISTS (
                        SELECT 1 FROM release_target_groups g
                        WHERE g.release_id = t.release_id AND g.tenant_id = t.tenant_id)
                    UNION
                    SELECT t.release_id, gm.tester_id
                    FROM release_targets t
                    JOIN releases r ON r.id = t.release_id AND r.application_id = :applicationId
                    JOIN release_target_groups g ON g.release_id = t.release_id AND g.tenant_id = t.tenant_id
                    JOIN tester_group_members gm ON gm.group_id = g.group_id
                ),
                denominators AS (
                    SELECT release_id, count(DISTINCT tester_id) AS targeted FROM targeted GROUP BY release_id
                ),
                downloaders AS (
                    SELECT ra.release_id, ts.id AS tester_id, date_trunc('{GRAIN}', a.occurred_at) AS bucket
                    FROM audit_events a
                    JOIN release_artifacts ra ON ra.artifact_id = a.artifact_id
                    JOIN releases r ON r.id = ra.release_id AND r.application_id = :applicationId
                    JOIN testers ts ON ts.user_id = a.user_id
                    WHERE a.event_type = 'DOWNLOAD_ISSUED' AND a.success AND a.user_id IS NOT NULL
                      AND a.occurred_at >= :from AND a.occurred_at < :to
                )
                """ + insertInto(table) + """
                SELECT r.organization_id, r.tenant_id, r.application_id, d.release_id, '""" + NIL_RING + """
                '::uuid,
                       'TESTER_ENGAGEMENT', d.bucket,
                       count(DISTINCT d.tester_id)::float8 / den.targeted, now()
                FROM downloaders d
                JOIN targeted tt ON tt.release_id = d.release_id AND tt.tester_id = d.tester_id
                JOIN denominators den ON den.release_id = d.release_id
                JOIN releases r ON r.id = d.release_id
                GROUP BY r.organization_id, r.tenant_id, r.application_id, d.release_id, d.bucket, den.targeted
                """ + upsert();
    }

    /** APPROVAL_DURATION: average minutes from round start to final decision, attributed to the decision's bucket. */
    private static String approvalDuration(String table) {
        return insertInto(table) + """
                SELECT r.organization_id, r.tenant_id, r.application_id, r.id, '""" + NIL_RING + """
                '::uuid,
                       'APPROVAL_DURATION', date_trunc('{GRAIN}', decided.last_decision),
                       avg(extract(epoch FROM (decided.last_decision - rr.created_at)) / 60.0), now()
                FROM release_approval_rounds rr
                JOIN releases r ON r.id = rr.release_id AND r.application_id = :applicationId
                JOIN LATERAL (
                    SELECT max(ra.decided_at) AS last_decision
                    FROM release_approvals ra
                    WHERE ra.round_id = rr.id AND ra.decided_at IS NOT NULL
                ) decided ON decided.last_decision IS NOT NULL
                WHERE decided.last_decision >= :from AND decided.last_decision < :to
                GROUP BY r.organization_id, r.tenant_id, r.application_id, r.id,
                         date_trunc('{GRAIN}', decided.last_decision)
                """ + upsert();
    }

    /** TIME_IN_RING: average minutes of the ring occupancies the bucket closed (open spans are the live read's). */
    private static String timeInRing(String table) {
        return """
                WITH entries AS (
                    SELECT r.id AS release_id, r.organization_id, r.tenant_id, r.application_id,
                           r.starting_ring_id AS ring_id, r.created_at AS entry_at
                    FROM releases r WHERE r.application_id = :applicationId
                    UNION ALL
                    SELECT r.id, r.organization_id, r.tenant_id, r.application_id,
                           rp.to_ring_id, rp.occurred_at
                    FROM releases r
                    JOIN release_promotions rp ON rp.release_id = r.id AND rp.status = 'COMPLETED'
                    WHERE r.application_id = :applicationId AND rp.to_ring_id IS NOT NULL
                ),
                closed AS (
                    SELECT e.*,
                           (SELECT min(rp2.occurred_at) FROM release_promotions rp2
                            WHERE rp2.release_id = e.release_id AND rp2.status = 'COMPLETED'
                              AND rp2.occurred_at > e.entry_at) AS exit_at
                    FROM entries e
                )
                """ + insertInto(table) + """
                SELECT organization_id, tenant_id, application_id, release_id, ring_id,
                       'TIME_IN_RING', date_trunc('{GRAIN}', exit_at),
                       avg(extract(epoch FROM (exit_at - entry_at)) / 60.0), now()
                FROM closed
                WHERE exit_at IS NOT NULL AND exit_at >= :from AND exit_at < :to
                GROUP BY organization_id, tenant_id, application_id, release_id, ring_id,
                         date_trunc('{GRAIN}', exit_at)
                """ + upsert();
    }
}
