package com.shipperdog.api.otabundle.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The OTA alert evaluation pass (S16-11, issue #173, ADR-0010 D10):
 * each run evaluates every persisted rule against the rollups -- the
 * {@code MetricRollupScheduler} pattern, its job running beside the
 * rollups it reads (the hourly rollup feeds the windows; this pass,
 * five-minute cadence, observes them). A crossing lands the firing
 * row, its bus event, and the console badge; a recovery stamps the
 * standing row. NOTHING here halts a rollout (D10): the pass has no
 * write into the rollout machinery at all -- halting stays the
 * operator's manual, audited act through the S16-06 endpoints.
 *
 * <p>The pass itself is the service's per-rule idempotent evaluate
 * (a crashed run costs nothing but the next pass's delay; a firing
 * that did not land is re-detected), so the scheduler body is exactly
 * the rollup scheduler's: the switch, the clock, the pass, one audit
 * line. Tests leave the clock out and drive the pass through the
 * service directly ({@code shipperdog.ota.alerts.enabled=false}).
 */
@Component
class OtaAlertScheduler {

    private final OtaAlertService alerts;

    private final OtaAlertProperties properties;

    private final AuditEventPublisher audit;

    private final Clock clock;

    OtaAlertScheduler(OtaAlertService alerts, OtaAlertProperties properties, AuditEventPublisher audit, Clock clock) {
        this.alerts = alerts;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /** The recurring pass: evaluate every rule, then the pass's one audit line. */
    @Scheduled(cron = "${shipperdog.ota.alerts.cron:0 */5 * * * *}")
    void runPass() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant now = Instant.now(clock);
        OtaAlertService.EvaluationReport report = alerts.evaluateAll(now);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("rules", Integer.toString(report.rules()));
        detail.put("fired", Integer.toString(report.fired()));
        detail.put("resolved", Integer.toString(report.resolved()));
        audit.publish(AuditEvent.builder(AuditEventType.OTA_ALERTS_EVALUATED)
                .occurredAt(now)
                .success(true)
                .detail(detail)
                .build());
    }

    /** The module's wiring: the properties record (scheduling itself is enabled since S11-04). */
    @Configuration
    @EnableConfigurationProperties(OtaAlertProperties.class)
    static class AlertConfig {}
}
