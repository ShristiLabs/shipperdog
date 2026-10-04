package com.shipperdog.api.audit.internal;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditRedaction;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The audit module's binding since S1-03, extended with the S1-06
 * pipeline: every published event is enriched with request context
 * (correlation id, client IP — only where the event did not carry one),
 * scrubbed by {@link AuditRedaction}, written as a structured log line,
 * and republished as a Spring application event. The durable sink
 * ({@code PersistentAuditEventSink}) persists those application events
 * in the same transaction as the business change; in-process listeners
 * (tests) observe the identical enriched event. No caller of
 * {@link AuditEventPublisher} changes shape.
 */
@Component
class LoggingAuditEventPublisher implements AuditEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuditEventPublisher.class);

    private final ApplicationEventPublisher events;

    LoggingAuditEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void publish(AuditEvent event) {
        AuditEvent enriched = enrich(event);
        AuditEvent redacted = redact(enriched);
        log.info(
                "audit event={} user={} org={} tenant={} result={} correlation={} ip={} detail={}",
                redacted.type(),
                redacted.userId(),
                redacted.orgId(),
                redacted.tenantId(),
                redacted.success() ? "SUCCESS" : "FAILURE",
                redacted.correlationId(),
                redacted.ipAddress(),
                redacted.detail());
        events.publishEvent(redacted);
    }

    private static AuditEvent enrich(AuditEvent event) {
        String correlationId =
                event.correlationId() == null ? AuditRequestContext.correlationId() : event.correlationId();
        String ipAddress = event.ipAddress() == null ? AuditRequestContext.clientIpAddress() : event.ipAddress();
        if (correlationId == null && ipAddress == null) {
            return event;
        }
        return new AuditEvent(
                event.type(),
                event.occurredAt(),
                event.userId(),
                event.orgId(),
                event.tenantId(),
                event.success(),
                event.detail(),
                ipAddress,
                event.applicationId(),
                event.versionId(),
                event.artifactId(),
                event.before(),
                event.after(),
                event.reason(),
                correlationId);
    }

    private static AuditEvent redact(AuditEvent event) {
        Map<String, String> detail = AuditRedaction.redactDetail(event.detail());
        String before = AuditRedaction.redactSnapshot(event.before());
        String after = AuditRedaction.redactSnapshot(event.after());
        if (detail.equals(event.detail())
                && Objects.equals(before, event.before())
                && Objects.equals(after, event.after())) {
            return event;
        }
        return new AuditEvent(
                event.type(),
                event.occurredAt(),
                event.userId(),
                event.orgId(),
                event.tenantId(),
                event.success(),
                detail,
                event.ipAddress(),
                event.applicationId(),
                event.versionId(),
                event.artifactId(),
                before,
                after,
                event.reason(),
                event.correlationId());
    }
}
