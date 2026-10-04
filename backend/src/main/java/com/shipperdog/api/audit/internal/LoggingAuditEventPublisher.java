package com.shipperdog.api.audit.internal;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Initial audit binding: writes a structured line per event and republishes
 * the event in-process so tests (and, from S1-06 on, the durable audit
 * sink) can observe everything emitted. Never logs credentials or tokens;
 * callers must keep {@link AuditEvent#detail()} free of secret material.
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
        log.info(
                "audit event={} user={} org={} tenant={} result={} detail={}",
                event.type(),
                event.userId(),
                event.orgId(),
                event.tenantId(),
                event.success() ? "SUCCESS" : "FAILURE",
                event.detail());
        events.publishEvent(event);
    }
}
