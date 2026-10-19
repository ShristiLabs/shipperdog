package com.shipperdog.api.analytics.internal.service;

import com.shipperdog.api.analytics.AnalyticsCatalog.Metric;
import com.shipperdog.api.analytics.AnalyticsException;
import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity.Result;
import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity.Type;
import com.shipperdog.api.analytics.internal.repository.AnalyticsEventRepository;
import com.shipperdog.api.analytics.internal.rollup.AnalyticsProperties;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The metrics read side (issue #49, S11-04, FR-029): the per-release
 * CURRENT values (the on-demand computation over the release's own
 * bounded, index-backed facts — the dictionary's formulas applied to
 * the live rows) and the cross-release TIME SERIES (the cached
 * rollups the hourly job and the backfill keep warm — the p95 &lt; 1s
 * reads the AC names, one indexed window scan, never an on-the-fly
 * aggregation over history).
 *
 * <p>Every metric with no data for its formula answers the explicit
 * NO_DATA ({@code value: null}) — never a zero. The device-side
 * family reads the SDK event store; the internal family reads the
 * S8-04 download trail, the S10-01 decisions, and the promotion
 * timeline. The two evaluation seams (gate, policy) consume the same
 * computations through the module's registrants — one formula, three
 * readers.
 */
@Service
public class AnalyticsQueryService {

    private final NamedParameterJdbcTemplate jdbc;

    private final AnalyticsEventRepository events;

    private final AnalyticsProperties properties;

    private final Clock clock;

    /** The six OTA event types the funnel counts (the entity's Type vocabulary as SQL row values). */
    private static final List<String> OTA_FUNNEL_TYPES =
            List.of("OTA_CHECK", "OTA_SEEN", "OTA_DOWNLOAD", "OTA_APPLIED", "OTA_FAILED", "OTA_ROLLED_BACK");

    AnalyticsQueryService(
            NamedParameterJdbcTemplate jdbc,
            AnalyticsEventRepository events,
            AnalyticsProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // The per-release current values (the dictionary, on demand)
    // -------------------------------------------------------------------------

    /**
     * Every dictionary metric for one release (AC1/AC2): the current
     * value or the explicit NO_DATA, each with its unit and source
     * family. The release must belong to the addressed application —
     * unknown, foreign, and mismatched are the one concealed miss.
     */
    @Transactional(readOnly = true)
    public ReleaseMetricsView currentValues(UUID applicationId, UUID releaseId) {
        ReleaseRow release = releaseOf(applicationId, releaseId);

        List<MetricValueView> metrics = new ArrayList<>();
        // DOWNLOADS is a definitive internal count: zero is a real
        // value, never NO_DATA (the source cannot miss it).
        metrics.add(value(Metric.DOWNLOADS, (double) downloadsCurrent(releaseId)));

        long installs = events.countByReleaseIdAndEventType(releaseId, Type.INSTALL);
        metrics.add(value(Metric.INSTALLATIONS, installs == 0 ? null : (double) installs));
        long succeeded = events.countByReleaseIdAndEventTypeAndResult(releaseId, Type.INSTALL, Result.SUCCEEDED.name());
        metrics.add(value(Metric.INSTALL_SUCCESS_RATE, installs == 0 ? null : (double) succeeded / installs));

        long[] sessions = sessionArithmetic(releaseId);
        metrics.add(
                value(Metric.CRASH_FREE_SESSIONS, sessions[0] == 0 ? null : 1.0 - (double) sessions[1] / sessions[0]));
        metrics.add(value(Metric.ANR_RATE, sessions[0] == 0 ? null : (double) sessions[2] / sessions[0]));

        long uninstalls = events.countByReleaseIdAndEventType(releaseId, Type.UNINSTALL);
        metrics.add(value(Metric.UNINSTALL_RATE, installs == 0 ? null : (double) uninstalls / installs));

        double[] engagement = engagementCurrent(releaseId);
        metrics.add(value(Metric.TESTER_ENGAGEMENT, engagement[0] == 0 ? null : engagement[1] / engagement[0]));

        // The policy registrant's answer, shared with the view above
        // (one formula, three readers — view, seam, rollup).

        Double approvalMinutes = approvalDurationCurrent(releaseId);
        metrics.add(value(Metric.APPROVAL_DURATION, approvalMinutes));

        metrics.add(value(Metric.TIME_IN_RING, timeInRingCurrent(releaseId)));
        return new ReleaseMetricsView(
                releaseId, release.releaseRef(), release.currentRingCode(), release.status(), metrics);
    }

    private MetricValueView value(Metric metric, Double value) {
        return new MetricValueView(
                metric.name(), value, metric.unit().name(), metric.source().name());
    }

    /**
     * The bundle-scoped crash-free rate (S16-05, issue #167, ADR-0010
     * workstream 3): 1 - crash sessions / total sessions over the
     * SESSION reports V57's {@code ota_bundle_id} attributes to the
     * bundle — the formula the {@code bundle-crash-free-threshold} gate
     * resolves at evaluation time. Empty while the bundle holds no
     * attributed sessions (the NO_DATA case the gate's documented
     * release-keyed fallback covers until the S16-08 funnel attribution
     * lands).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Double> crashFreeRateOfBundle(UUID bundleId) {
        return events.sessionArithmeticOfBundle(bundleId, Type.SESSION).stream()
                .findFirst()
                .filter(row -> ((Number) row[0]).longValue() > 0)
                .map(row -> 1.0 - ((Number) row[1]).doubleValue() / ((Number) row[0]).doubleValue());
    }

    // -------------------------------------------------------------------------
    // The OTA bundle funnel (S16-09, issue #171 — the live read over the
    // bundle's own bounded facts; the rollups carry the per-stage counts)
    // -------------------------------------------------------------------------

    /**
     * The OTA bundle funnel (AC3): the stage counts of the device funnel
     * — check -> seen -> download -> applied, beside the failure and
     * rollback legs — with the failure-reason breakdown ({@code
     * OTA_FAILED}'s open row-value vocabulary) and the adoption curve
     * over time: the cumulative distinct devices that applied the
     * bundle over the distinct devices that observed its manifest, at
     * each day the bundle drew a report (a monotone curve, recomputed
     * from the facts at each day exactly as the rollups recompute
     * rates; rows without the payload's device identity count toward
     * the stage counts but never toward adoption — adoption needs
     * identity).
     *
     * <p>The bundle must belong to an application the addressed tenant
     * can see (tenant-owned or ACTIVE-authorized — the dashboard
     * catalog union): unknown, foreign, and unvisible are the one
     * concealed {@code BUNDLE_NOT_FOUND}. Unfiltered, every leg reads
     * the bundle's own event history directly — attribution is
     * history, so a rolled-back (detached) bundle keeps its whole
     * funnel. The optional ring filter narrows EVERY leg — the stage
     * counts, the failure breakdown, and the curve — through the
     * bundle's own release's timeline (the rollups' grain, never the
     * reporting release): the reports that occurred while the bundle's
     * release occupied the named ring. A ring-filtered read is
     * release-anchored by construction — a detached bundle occupies
     * no ring, so its funnel answers zero under a filter.
     */
    @Transactional(readOnly = true)
    public OtaFunnelView otaFunnel(UUID tenantId, UUID bundleId, String ring) {
        requireTenant(tenantId);
        OtaBundleRow bundle = visibleBundleOf(tenantId, bundleId);
        String ringCode = normalizedRing(ring, bundle.organizationId());
        return new OtaFunnelView(
                bundleId,
                bundle.bundleVersion(),
                bundle.applicationId(),
                bundle.releaseId(),
                bundle.releaseRef(),
                funnelStagesOf(bundleId, ringCode),
                funnelFailuresOf(bundleId, ringCode),
                funnelAdoptionOf(bundleId, ringCode));
    }

    /**
     * The funnel's stage counts: one aggregate over the bundle's six
     * event types. The type list is INLINED, never bound — the store's
     * {@code analytics_event_type} is a Postgres enum a bound varchar
     * cannot compare against (the rollup service's own grain
     * discipline), and the vocabulary is the platform's own fixed
     * constant, never caller input.
     */
    private OtaStagesView funnelStagesOf(UUID bundleId, String ringCode) {
        MapSqlParameterSource bound = new MapSqlParameterSource("bundleId", bundleId);
        StringBuilder sql = new StringBuilder("""
                SELECT e.event_type, count(*) AS n
                FROM analytics_events e
                """)
                .append(ringAtEvent(ringCode, bound))
                .append(" WHERE e.ota_bundle_id = :bundleId AND e.event_type IN ('")
                .append(String.join("','", OTA_FUNNEL_TYPES))
                .append("')")
                .append(ringCode == null ? "" : " AND rg.code = :ring")
                .append(" GROUP BY e.event_type");
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql.toString(), bound)) {
            counts.put((String) row.get("event_type"), ((Number) row.get("n")).longValue());
        }
        return new OtaStagesView(
                counts.getOrDefault("OTA_CHECK", 0L),
                counts.getOrDefault("OTA_SEEN", 0L),
                counts.getOrDefault("OTA_DOWNLOAD", 0L),
                counts.getOrDefault("OTA_APPLIED", 0L),
                counts.getOrDefault("OTA_FAILED", 0L),
                counts.getOrDefault("OTA_ROLLED_BACK", 0L));
    }

    /**
     * The failure-reason breakdown: the bundle's OTA_FAILED reports
     * grouped by their open row value — ring-filtered exactly like the
     * stage counts when a filter narrows the funnel.
     */
    private List<OtaFailureView> funnelFailuresOf(UUID bundleId, String ringCode) {
        MapSqlParameterSource bound = new MapSqlParameterSource("bundleId", bundleId);
        StringBuilder sql = new StringBuilder("""
                SELECT e.result, count(*) AS n
                FROM analytics_events e
                """)
                .append(ringAtEvent(ringCode, bound))
                .append(" WHERE e.ota_bundle_id = :bundleId AND e.event_type = 'OTA_FAILED'")
                .append(ringCode == null ? "" : " AND rg.code = :ring")
                .append(" GROUP BY e.result ORDER BY count(*) DESC, e.result");
        return jdbc.query(
                sql.toString(), bound, (rs, i) -> new OtaFailureView(rs.getString("result"), rs.getLong("n")));
    }

    /**
     * The adoption curve: for each day the bundle drew a SEEN or
     * APPLIED report, the cumulative distinct identified devices that
     * applied over the cumulative distinct that observed — the S16-10
     * dashboard's adoption-over-time series from the facts. Honest
     * arithmetic, never clamped: a device whose OTA_APPLIED landed
     * without any OTA_SEEN (an offline restore that lost the seen leg)
     * can push a day's rate past 1.0 — the curve reports the partial
     * reporting as it stands.
     */
    private List<OtaAdoptionPointView> funnelAdoptionOf(UUID bundleId, String ringCode) {
        MapSqlParameterSource bound = new MapSqlParameterSource("bundleId", bundleId);
        StringBuilder sql = new StringBuilder("""
                WITH marks AS (
                    SELECT date_trunc('day', e.occurred_at) AS day,
                           e.ota_payload ->> 'device_id' AS device,
                           e.event_type
                    FROM analytics_events e
                """)
                .append(ringAtEvent(ringCode, bound))
                .append("""
                      WHERE e.ota_bundle_id = :bundleId
                        AND e.event_type IN ('OTA_SEEN', 'OTA_APPLIED')
                        AND e.ota_payload ->> 'device_id' IS NOT NULL""")
                .append(ringCode == null ? "" : "\n                      AND rg.code = :ring")
                .append("""
                ),
                days AS (SELECT DISTINCT day FROM marks),
                curve AS (
                    SELECT d.day,
                           (SELECT count(DISTINCT m.device) FROM marks m
                             WHERE m.event_type = 'OTA_APPLIED' AND m.day <= d.day) AS applied_devices,
                           (SELECT count(DISTINCT m.device) FROM marks m
                             WHERE m.event_type = 'OTA_SEEN' AND m.day <= d.day) AS seen_devices
                    FROM days d
                )
                SELECT day, seen_devices, applied_devices,
                       CASE WHEN seen_devices > 0
                            THEN applied_devices::float8 / seen_devices END AS adoption
                FROM curve
                ORDER BY day
                """);
        return jdbc.query(sql.toString(), bound, (rs, i) -> {
            double adoption = rs.getDouble("adoption");
            return new OtaAdoptionPointView(
                    rs.getTimestamp("day").toInstant().toString(),
                    rs.getLong("seen_devices"),
                    rs.getLong("applied_devices"),
                    rs.wasNull() ? null : adoption);
        });
    }

    /**
     * The bundle-release ring-attribution fragment the funnel's
     * statements share when a ring filter narrows them: the bundle's
     * own release (the rollups' grain — never the reporting release)
     * joined with its promotion lateral and the rings dimension, so
     * the caller's {@code rg.code = :ring} leg attributes each event
     * to the ring the bundle's release occupied when it occurred. The
     * WHOLE chain rides the filter: the unfiltered legs read the event
     * store alone, keeping a detached bundle's history (a rolled-back
     * release returns its bundle to the pool — attribution is
     * history), while a ring-filtered read is release-anchored by
     * construction (a detached bundle occupies no ring). Appends
     * nothing (and binds nothing) without a filter.
     */
    private static String ringAtEvent(String ringCode, MapSqlParameterSource bound) {
        if (ringCode == null) {
            return "";
        }
        bound.addValue("ring", ringCode);
        return """
                JOIN ota_bundles ob ON ob.id = e.ota_bundle_id
                JOIN releases r ON r.id = ob.release_id
                LEFT JOIN LATERAL (
                    SELECT rp.to_ring_id FROM release_promotions rp
                    WHERE rp.release_id = r.id AND rp.status = 'COMPLETED' AND rp.occurred_at <= e.occurred_at
                    ORDER BY rp.occurred_at DESC LIMIT 1
                ) hop ON true
                JOIN rings rg ON rg.id = COALESCE(hop.to_ring_id, r.starting_ring_id)
                """;
    }

    /** The addressed tenant's existence (the dashboard's own miss, mirrored). */
    private void requireTenant(UUID tenantId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tenants WHERE id = :id)",
                new MapSqlParameterSource("id", tenantId),
                Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new AnalyticsException(AnalyticsException.Code.TENANT_NOT_FOUND, "unknown tenant");
        }
    }

    /**
     * The bundle the tenant can see: the dashboard catalog union
     * (tenant-owned application or ACTIVE authorization) resolving the
     * bundle's release context — the one concealed miss for every leg.
     */
    private OtaBundleRow visibleBundleOf(UUID tenantId, UUID bundleId) {
        List<OtaBundleRow> rows = jdbc.query(
                """
                SELECT b.application_id, b.organization_id, b.release_id, b.bundle_version,
                       'R-' || r.release_number AS release_ref
                FROM ota_bundles b
                JOIN applications a ON a.id = b.application_id
                LEFT JOIN releases r ON r.id = b.release_id
                WHERE b.id = :bundleId
                  AND (a.tenant_id = :tenantId
                       OR EXISTS (SELECT 1 FROM tenant_application_authorizations aa
                                   WHERE aa.tenant_id = :tenantId AND aa.application_id = a.id
                                     AND aa.status = 'ACTIVE'))
                """,
                new MapSqlParameterSource(Map.of("bundleId", bundleId, "tenantId", tenantId)),
                (rs, i) -> new OtaBundleRow(
                        rs.getObject("application_id", UUID.class),
                        rs.getObject("organization_id", UUID.class),
                        rs.getObject("release_id", UUID.class),
                        rs.getString("release_ref"),
                        rs.getString("bundle_version")));
        if (rows.isEmpty()) {
            throw new AnalyticsException(
                    AnalyticsException.Code.BUNDLE_NOT_FOUND, "the addressed bundle did not resolve");
        }
        return rows.get(0);
    }

    /**
     * The ring filter's own normalization: the series route's token
     * discipline (trimmed, lowercased) over the bundle's organization's
     * effective ring vocabulary — unknown codes are refused, never
     * silently empty.
     */
    private String normalizedRing(String ring, UUID organizationId) {
        if (ring == null || ring.isBlank()) {
            return null;
        }
        String code = ring.trim().toLowerCase(java.util.Locale.ROOT);
        Boolean exists = jdbc.queryForObject(
                """
                SELECT EXISTS(SELECT 1 FROM rings
                              WHERE code = :code AND (organization_id = :org OR organization_id IS NULL))
                """, new MapSqlParameterSource(Map.of("code", code, "org", organizationId)), Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new AnalyticsException(
                    AnalyticsException.Code.INVALID_REQUEST,
                    "unknown ring " + ring,
                    Map.of("ring", "unknown ring code"));
        }
        return code;
    }

    /** The release's own row: the concealed miss for unknown, foreign, and mismatched ids alike. */
    private ReleaseRow releaseOf(UUID applicationId, UUID releaseId) {
        List<ReleaseRow> rows = jdbc.query(
                """
                SELECT 'R-' || r.release_number AS release_ref, r.status::text AS status,
                       COALESCE(last_ring.code, starting_ring.code, '') AS ring_code
                FROM releases r
                JOIN rings starting_ring ON starting_ring.id = r.starting_ring_id
                LEFT JOIN LATERAL (
                    SELECT rg.code FROM release_promotions rp
                    JOIN rings rg ON rg.id = rp.to_ring_id
                    WHERE rp.release_id = r.id AND rp.status = 'COMPLETED'
                    ORDER BY rp.occurred_at DESC LIMIT 1
                ) last_ring ON true
                WHERE r.id = :releaseId AND r.application_id = :applicationId
                """,
                new MapSqlParameterSource(Map.of("releaseId", releaseId, "applicationId", applicationId)),
                (rs, i) ->
                        new ReleaseRow(rs.getString("release_ref"), rs.getString("status"), rs.getString("ring_code")));
        if (rows.isEmpty()) {
            throw new AnalyticsException(
                    AnalyticsException.Code.RELEASE_NOT_FOUND, "the addressed release did not resolve");
        }
        return rows.get(0);
    }

    /** DOWNLOADS, current: the release's artifacts' allowed download issuances (all time, the release's own bound). */
    private long downloadsCurrent(UUID releaseId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM audit_events a
                JOIN release_artifacts ra ON ra.artifact_id = a.artifact_id
                WHERE ra.release_id = :releaseId AND a.event_type = 'DOWNLOAD_ISSUED' AND a.success
                """, new MapSqlParameterSource("releaseId", releaseId), Long.class);
        return count == null ? 0 : count;
    }

    /** The release's session arithmetic: total, crash, ANR sessions over every SESSION report. */
    private long[] sessionArithmetic(UUID releaseId) {
        List<Object[]> rows = events.sessionArithmeticOf(releaseId, Type.SESSION);
        if (rows.isEmpty()) {
            return new long[] {0, 0, 0};
        }
        Object[] row = rows.get(0);
        return new long[] {((Number) row[0]).longValue(), ((Number) row[1]).longValue(), ((Number) row[2]).longValue()};
    }

    /**
     * TESTER_ENGAGEMENT as the policy seam's registrant answers it:
     * empty while the release targets no testers at all (the NO_DATA
     * case), the engaged/targeted rate otherwise.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Double> testerEngagementOf(UUID releaseId) {
        double[] engagement = engagementCurrent(releaseId);
        return engagement[0] == 0 ? java.util.Optional.empty() : java.util.Optional.of(engagement[1] / engagement[0]);
    }

    /** TESTER_ENGAGEMENT, current: the targeted count and the distinct targeted downloaders. */
    private double[] engagementCurrent(UUID releaseId) {
        Map<String, Object> row = jdbc.queryForMap("""
                WITH targeted AS (
                    SELECT t.release_id, m.tester_id
                    FROM release_targets t
                    JOIN tenant_tester_memberships m ON m.tenant_id = t.tenant_id
                    WHERE t.release_id = :releaseId
                      AND NOT EXISTS (
                        SELECT 1 FROM release_target_groups g
                        WHERE g.release_id = t.release_id AND g.tenant_id = t.tenant_id)
                    UNION
                    SELECT t.release_id, gm.tester_id
                    FROM release_targets t
                    JOIN release_target_groups g ON g.release_id = t.release_id AND g.tenant_id = t.tenant_id
                    JOIN tester_group_members gm ON gm.group_id = g.group_id
                    WHERE t.release_id = :releaseId
                )
                SELECT
                    (SELECT count(DISTINCT tester_id) FROM targeted) AS targeted,
                    (SELECT count(DISTINCT d.tester_id)
                     FROM (
                        SELECT ts.id AS tester_id FROM audit_events a
                        JOIN release_artifacts ra ON ra.artifact_id = a.artifact_id
                        JOIN testers ts ON ts.user_id = a.user_id
                        WHERE ra.release_id = :releaseId
                          AND a.event_type = 'DOWNLOAD_ISSUED' AND a.success AND a.user_id IS NOT NULL
                     ) d
                     JOIN targeted tt ON tt.tester_id = d.tester_id) AS engaged
                """, new MapSqlParameterSource("releaseId", releaseId));
        return new double[] {((Number) row.get("targeted")).doubleValue(), ((Number) row.get("engaged")).doubleValue()};
    }

    /** APPROVAL_DURATION, current: the average decided round's minutes, or null when no decision has landed. */
    private Double approvalDurationCurrent(UUID releaseId) {
        List<Double> rows = jdbc.query("""
                SELECT avg(extract(epoch FROM (decided.last_decision - rr.created_at)) / 60.0) AS minutes
                FROM release_approval_rounds rr
                JOIN LATERAL (
                    SELECT max(ra.decided_at) AS last_decision
                    FROM release_approvals ra
                    WHERE ra.round_id = rr.id AND ra.decided_at IS NOT NULL
                ) decided ON decided.last_decision IS NOT NULL
                WHERE rr.release_id = :releaseId
                """, new MapSqlParameterSource("releaseId", releaseId), (rs, i) -> {
            double minutes = rs.getDouble("minutes");
            return rs.wasNull() ? null : minutes;
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** TIME_IN_RING, current: minutes since the release entered the ring it holds. */
    private Double timeInRingCurrent(UUID releaseId) {
        List<Double> rows = jdbc.query("""
                SELECT extract(epoch FROM (now() - GREATEST(
                    r.created_at,
                    COALESCE((SELECT max(rp.occurred_at) FROM release_promotions rp
                              WHERE rp.release_id = r.id AND rp.status = 'COMPLETED'), r.created_at)
                ))) / 60.0 AS minutes
                FROM releases r WHERE r.id = :releaseId
                """, new MapSqlParameterSource("releaseId", releaseId), (rs, i) -> {
            double minutes = rs.getDouble("minutes");
            return rs.wasNull() ? null : minutes;
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    // -------------------------------------------------------------------------
    // The time series (the cached rollups, bounded windows)
    // -------------------------------------------------------------------------

    /**
     * One metric's series over a bounded window (AC4): application or
     * tenant grain, optional ring and release filters, hourly or
     * daily buckets from the rollup store, newest-last and paged the
     * platform's way. The window is validated against the bounds —
     * refused, never clamped.
     */
    @Transactional(readOnly = true)
    public PageResponse<MetricPointView> series(SeriesQuery query, ListQuery page) {
        Metric metric = metricOf(query.metric());
        Instant from = instantOf("from", query.from());
        Instant to = instantOf("to", query.to());
        if (!from.isBefore(to)) {
            throw refused(Map.of("from", "from must precede to"));
        }
        if (to.isAfter(Instant.now(clock).plusSeconds(300))) {
            throw refused(Map.of("to", "to is in the future"));
        }
        if (Duration.ofMillis(to.toEpochMilli() - from.toEpochMilli()).compareTo(properties.maxSeriesWindow()) > 0) {
            throw refused(
                    Map.of("from", "the window exceeds " + properties.maxSeriesWindow() + " (refused, never clamped)"));
        }
        String grain = query.grain() == null || query.grain().isBlank()
                ? "hour"
                : query.grain().trim().toLowerCase(java.util.Locale.ROOT);
        if (!grain.equals("hour") && !grain.equals("day")) {
            throw new AnalyticsException(
                    AnalyticsException.Code.INVALID_REQUEST,
                    "unknown grain " + query.grain(),
                    Map.of("grain", "grain is HOUR or DAY"));
        }
        if (grain.equals("hour")
                && Duration.ofMillis(to.toEpochMilli() - from.toEpochMilli()).compareTo(properties.maxHourlySpan())
                        > 0) {
            throw refused(Map.of(
                    "grain", "the hourly grain serves windows up to " + properties.maxHourlySpan() + "; use DAY"));
        }
        validateScope(query);

        StringBuilder sql = new StringBuilder("""
                SELECT m.bucket_start, m.value, m.release_id, 'R-' || r.release_number AS release_ref, rg.code AS ring_code
                FROM metric_""")
                .append(grain.equals("hour") ? "hourly" : "daily")
                .append("""
                         m
                JOIN releases r ON r.id = m.release_id
                LEFT JOIN rings rg ON rg.id = m.ring_id
                WHERE m.metric = :metric AND m.bucket_start >= :from AND m.bucket_start < :to
                 """);
        Map<String, Object> bound = new LinkedHashMap<>();
        bound.put("metric", metric.name());
        bound.put("from", Timestamp.from(from));
        bound.put("to", Timestamp.from(to));
        if (query.applicationId() != null) {
            sql.append(" AND m.application_id = :applicationId");
            bound.put("applicationId", query.applicationId());
        }
        if (query.tenantId() != null) {
            sql.append(" AND m.tenant_id = :tenantId");
            bound.put("tenantId", query.tenantId());
        }
        if (query.releaseId() != null) {
            sql.append(" AND m.release_id = :releaseId");
            bound.put("releaseId", query.releaseId());
        }
        if (query.ring() != null && !query.ring().isBlank()) {
            sql.append(" AND rg.code = :ring");
            bound.put("ring", query.ring().trim().toLowerCase(java.util.Locale.ROOT));
        }
        sql.append(" ORDER BY m.bucket_start ASC, m.release_id ASC LIMIT :limit OFFSET :offset");
        bound.put("limit", page.limit() + 1);
        bound.put("offset", page.offset());

        List<MetricPointView> points = jdbc.query(
                sql.toString(),
                new MapSqlParameterSource(bound),
                (rs, i) -> new MetricPointView(
                        rs.getTimestamp("bucket_start").toInstant().toString(),
                        rs.getDouble("value"),
                        rs.getString("release_ref"),
                        rs.getString("ring_code")));
        boolean hasMore = points.size() > page.limit();
        List<MetricPointView> visible = hasMore ? points.subList(0, page.limit()) : points;
        return PageResponse.of(visible, hasMore, page.offset() + page.limit());
    }

    private void validateScope(SeriesQuery query) {
        if (query.applicationId() == null && query.tenantId() == null) {
            throw new AnalyticsException(
                    AnalyticsException.Code.INVALID_REQUEST,
                    "a series needs its application or tenant scope",
                    Map.of("scope", "applicationId or tenantId is required"));
        }
        if (query.applicationId() != null) {
            Boolean exists = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM applications WHERE id = :id)",
                    new MapSqlParameterSource("id", query.applicationId()),
                    Boolean.class);
            if (!Boolean.TRUE.equals(exists)) {
                throw new AnalyticsException(
                        AnalyticsException.Code.APPLICATION_NOT_FOUND, "the addressed application did not resolve");
            }
        }
        if (query.releaseId() != null && query.applicationId() != null) {
            Boolean belongs = jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM releases WHERE id = :releaseId AND application_id = :applicationId)",
                    new MapSqlParameterSource(
                            Map.of("releaseId", query.releaseId(), "applicationId", query.applicationId())),
                    Boolean.class);
            if (!Boolean.TRUE.equals(belongs)) {
                throw new AnalyticsException(
                        AnalyticsException.Code.RELEASE_NOT_FOUND, "the addressed release did not resolve");
            }
        }
    }

    private static Metric metricOf(String token) {
        if (token == null || token.isBlank()) {
            throw new AnalyticsException(
                    AnalyticsException.Code.INVALID_REQUEST,
                    "the series metric is required",
                    Map.of("metric", "metric is required"));
        }
        try {
            return Metric.valueOf(token.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AnalyticsException(
                    AnalyticsException.Code.INVALID_REQUEST,
                    "unknown metric " + token,
                    Map.of("metric", "unknown metric; the dictionary is docs/analytics.md"));
        }
    }

    private static Instant instantOf(String field, String text) {
        if (text == null || text.isBlank()) {
            throw new AnalyticsException(
                    AnalyticsException.Code.INVALID_REQUEST,
                    field + " is required",
                    Map.of(field, field + " is required"));
        }
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new AnalyticsException(
                    AnalyticsException.Code.INVALID_REQUEST,
                    field + " is not an ISO-8601 instant",
                    Map.of(field, "ISO-8601 UTC instant expected (2026-10-05T12:00:00Z)"));
        }
    }

    private static AnalyticsException refused(Map<String, String> errors) {
        return new AnalyticsException(
                AnalyticsException.Code.ANALYTICS_REQUEST_REFUSED, "the series window is refused", errors);
    }

    /** The release's own facts the view carries beside its metrics. */
    private record ReleaseRow(String releaseRef, String status, String currentRingCode) {}

    /** One dictionary metric's current answer: the value, or null — the explicit NO_DATA. */
    public record MetricValueView(String metric, Double value, String unit, String source) {}

    /** The per-release current-values document (the dictionary's whole list, in catalog order). */
    public record ReleaseMetricsView(
            UUID releaseId,
            String releaseRef,
            String currentRing,
            String releaseStatus,
            List<MetricValueView> metrics) {}

    /**
     * The OTA bundle funnel document (S16-09): the bundle's identity
     * and release context beside the stage counts, the failure-reason
     * breakdown, and the adoption curve.
     */
    public record OtaFunnelView(
            UUID bundleId,
            String bundleVersion,
            UUID applicationId,
            UUID releaseId,
            String releaseRef,
            OtaStagesView stages,
            List<OtaFailureView> failures,
            List<OtaAdoptionPointView> adoption) {}

    /** The funnel's stage counts: the six OTA event types' report counts. */
    public record OtaStagesView(long checks, long seen, long downloads, long applied, long failed, long rolledBack) {}

    /** One failure reason and the OTA_FAILED reports that carried it. */
    public record OtaFailureView(String reason, long count) {}

    /**
     * One adoption point: the day, the cumulative distinct devices that
     * observed the manifest and that applied, and the adoption rate
     * (null while no identified device has observed — the NO_DATA
     * doctrine, never a zero).
     */
    public record OtaAdoptionPointView(String day, long seenDevices, long appliedDevices, Double adoptionRate) {}

    /** One visible bundle's resolution context (internal; the views above are the served shape). */
    private record OtaBundleRow(
            UUID applicationId, UUID organizationId, UUID releaseId, String releaseRef, String bundleVersion) {}

    /** One point of a series: the bucket, the value, and the dimensions the row carries. */
    public record MetricPointView(String bucketStart, double value, String releaseRef, String ringCode) {}

    /** A series request as the routes assemble it. */
    public record SeriesQuery(
            UUID applicationId,
            UUID tenantId,
            UUID releaseId,
            String metric,
            String ring,
            String grain,
            String from,
            String to) {}
}
