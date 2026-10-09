package com.shipperdog.api.analytics;

import com.shipperdog.api.analytics.internal.rollup.AnalyticsProperties;
import com.shipperdog.api.analytics.internal.rollup.MetricRollupService;
import com.shipperdog.api.analytics.internal.service.AnalyticsIngestService;
import com.shipperdog.api.analytics.internal.service.AnalyticsIngestService.EventCommand;
import com.shipperdog.api.analytics.internal.service.AnalyticsQueryService;
import com.shipperdog.api.analytics.internal.service.AnalyticsQueryService.MetricPointView;
import com.shipperdog.api.analytics.internal.service.AnalyticsQueryService.ReleaseMetricsView;
import com.shipperdog.api.analytics.internal.service.AnalyticsQueryService.SeriesQuery;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The release analytics API (issue #49, S11-04, FR-029, PRD 28/29/33/41):
 * the per-release current values, the cross-release time series over
 * the cached rollups, the frozen S15-01 events-ingest contract, and
 * the rollup recompute trigger (backfill and late-history repair).
 * Dashboards (S11-05) RENDER this surface; the computation lives
 * here.
 *
 * <p>Permissions: {@code app:analytics:read} for the application- and
 * release-grain reads, {@code tenant:analytics:read} for the
 * tenant-grain series, {@code tenant:analytics:ingest} for the events
 * API, and {@code app:analytics:manage} for the recompute trigger —
 * four authorities in the {@code Permission} catalog, held by the
 * established personas (APP_OWNER and RELEASE_MANAGER for the app
 * scope, TENANT_ADMIN for the tenant scope, the ORG_ADMIN overlay
 * above both). The ingest authority is tenant administrators for now:
 * M2 has no SDK emitters, CI and operations exercise the pipeline
 * through it, and S15-01's device authentication evolves the
 * authority without touching the contract. Resource-scoped denials
 * render as the concealed 404 a genuine miss produces (PRD 12); the
 * series pages with the platform's limit/cursor convention.
 */
@RestController
public class AnalyticsController {

    private final AnalyticsQueryService queries;

    private final AnalyticsIngestService ingest;

    private final MetricRollupService rollups;

    private final AnalyticsProperties properties;

    AnalyticsController(
            AnalyticsQueryService queries,
            AnalyticsIngestService ingest,
            MetricRollupService rollups,
            AnalyticsProperties properties) {
        this.queries = queries;
        this.ingest = ingest;
        this.rollups = rollups;
        this.properties = properties;
    }

    /**
     * The release's current metric set (AC1/AC2): every dictionary
     * metric with its live value or the explicit NO_DATA — the
     * document gates, policies, and the release dashboard all read.
     */
    @GetMapping("/api/v1/applications/{applicationId}/releases/{releaseId}/metrics")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:analytics:read')")
    ReleaseMetricsView releaseMetrics(
            @AuthenticationPrincipal Object principal, @PathVariable UUID applicationId, @PathVariable UUID releaseId) {
        return queries.currentValues(applicationId, releaseId);
    }

    /**
     * The application's metric series (AC4): one dictionary metric
     * over a bounded window, hourly or daily buckets from the cached
     * rollups, optional ring and release filters, newest last and
     * paged — the p95 &lt; 1s read of the acceptance criteria.
     */
    @GetMapping("/api/v1/applications/{applicationId}/metrics")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:analytics:read')")
    PageResponse<MetricPointView> applicationSeries(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID applicationId,
            @RequestParam String metric,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String grain,
            @RequestParam(required = false) String ring,
            @RequestParam(required = false) UUID releaseId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        return queries.series(
                new SeriesQuery(applicationId, null, releaseId, metric, ring, grain, from, to),
                ListQuery.of(limit, cursor));
    }

    /**
     * The tenant-grain series (AC4): the same shape over the tenant's
     * owning dimension (the releases of the applications the tenant
     * owns — the isolation-consistent grain; targeted-tenant reach
     * stays app/release-grain with ring filters, docs/analytics.md).
     */
    @GetMapping("/api/v1/tenants/{tenantId}/metrics")
    @PreAuthorize("hasPermission(#tenantId, 'tenant', 'tenant:analytics:read')")
    PageResponse<MetricPointView> tenantSeries(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID tenantId,
            @RequestParam String metric,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String grain,
            @RequestParam(required = false) String ring,
            @RequestParam(required = false) UUID releaseId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        return queries.series(
                new SeriesQuery(null, tenantId, releaseId, metric, ring, grain, from, to), ListQuery.of(limit, cursor));
    }

    /**
     * The frozen SDK events contract (S15-01 implements THIS): one
     * batch, one release the reporting tenant is targeted by, each
     * event one of INSTALL / SESSION / UNINSTALL / TESTER_VERDICT
     * with its type's shape (docs/analytics.md carries the worked
     * bodies). Rows are append-only; late {@code occurredAt} heals
     * through the job's lookback or the recompute trigger.
     */
    @PostMapping("/api/v1/tenants/{tenantId}/analytics/events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(#tenantId, 'tenant', 'tenant:analytics:ingest')")
    AnalyticsIngestService.IngestReport ingestEvents(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID tenantId,
            @RequestBody AnalyticsEventBatchRequest request) {
        List<EventCommand> batch = request.events() == null
                ? List.of()
                : request.events().stream()
                        .map(event -> new EventCommand(
                                event.type(),
                                event.result(),
                                event.releaseId(),
                                event.deviceId(),
                                parseInstant("occurred_at", event.occurredAt()),
                                event.sessions(),
                                event.crashSessions(),
                                event.anrSessions()))
                        .toList();
        return ingest.ingest(requireUserId(principal), tenantId, batch);
    }

    /**
     * The rollup recompute trigger (AC3/AC5): one bounded window, one
     * application — the backfill for pre-existing history (the
     * migration seeds downloads once; this repairs and extends any
     * window on demand) and the late-event repair beyond the hourly
     * job's lookback. Idempotent by construction: the same window
     * recomputes the same numbers.
     */
    @PostMapping("/api/v1/applications/{applicationId}/metrics/rollups")
    @PreAuthorize("hasPermission(#applicationId, 'application', 'app:analytics:manage')")
    RollupReport recomputed(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID applicationId,
            @RequestBody RollupRecomputeRequest request) {
        Instant from = parseInstant("from", request.from());
        Instant to = parseInstant("to", request.to());
        if (!from.isBefore(to)) {
            throw new AnalyticsException(
                    AnalyticsException.Code.INVALID_REQUEST,
                    "from must precede to",
                    Map.of("from", "from must precede to"));
        }
        if (Duration.between(from, to).compareTo(properties.maxSeriesWindow()) > 0) {
            throw new AnalyticsException(
                    AnalyticsException.Code.ANALYTICS_REQUEST_REFUSED,
                    "the window exceeds " + properties.maxSeriesWindow(),
                    Map.of("from", "the window exceeds " + properties.maxSeriesWindow() + " (refused, never clamped)"));
        }
        int rows = rollups.recompute(applicationId, from, to);
        return new RollupReport(rows, from.toString(), to.toString());
    }

    private static Instant parseInstant(String field, String text) {
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

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new AnalyticsException(AnalyticsException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /** The ingest body: a bounded batch (analytics-distinct schema name — springdoc keys by simple name). */
    public record AnalyticsEventBatchRequest(List<AnalyticsEventRequest> events) {}

    /** One SDK event as the frozen contract receives it (the type's shape is validated whole-batch). */
    public record AnalyticsEventRequest(
            String type,
            String result,
            UUID releaseId,
            UUID deviceId,
            String occurredAt,
            Integer sessions,
            Integer crashSessions,
            Integer anrSessions) {}

    /** The recompute body: one bounded historical window. */
    public record RollupRecomputeRequest(String from, String to) {}

    /** The recompute receipt: rows written or refreshed over the window. */
    public record RollupReport(int rows, String from, String to) {}
}
