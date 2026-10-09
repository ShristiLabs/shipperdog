package com.shipperdog.api.analytics.internal.rollup;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The hourly rollup job (issue #49, S11-04, FR-029): each run
 * recomputes the trailing lookback window from source — the
 * late-event healing bound (an event reported late inside the
 * lookback is folded by the next run; older history is repaired by
 * the backfill trigger, docs/analytics.md). The whole pass is the
 * service's own idempotent recompute, so a crashed run costs nothing
 * but the next hour's delay.
 *
 * <p>Scheduling is new to the application with this story — the
 * platform's first background job — so the enablement lives here
 * (module-local wiring, the standard Spring capability; no new
 * dependency). The schedule, the lookback, and the switch are
 * environment-configurable ({@code shipperdog.analytics.rollup-*});
 * tests leave the clock out and drive the computation through the
 * trigger route.
 */
@Component
class MetricRollupScheduler {

    private final MetricRollupService rollups;

    private final AnalyticsProperties properties;

    private final AuditEventPublisher audit;

    private final Clock clock;

    MetricRollupScheduler(
            MetricRollupService rollups, AnalyticsProperties properties, AuditEventPublisher audit, Clock clock) {
        this.rollups = rollups;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /** The hourly pass: lookback recompute over every application, one audit line. */
    @Scheduled(cron = "${shipperdog.analytics.rollup.cron:0 0 * * * *}")
    void runHourly() {
        if (!properties.rollup().isEnabled()) {
            return;
        }
        Instant now = Instant.now(clock);
        int written = rollups.runLookback(now, properties.rollup().lookback());
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("window", properties.rollup().lookback().toString());
        detail.put("rows", Integer.toString(written));
        audit.publish(AuditEvent.builder(AuditEventType.METRIC_ROLLUPS_RECOMPUTED)
                .occurredAt(now)
                .success(true)
                .detail(detail)
                .build());
    }

    /** The module's wiring: the properties record and the scheduler's clock enablement. */
    @Configuration
    @EnableScheduling
    @EnableConfigurationProperties(AnalyticsProperties.class)
    static class RollupConfig {}
}
