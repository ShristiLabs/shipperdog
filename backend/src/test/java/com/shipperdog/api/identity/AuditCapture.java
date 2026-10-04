package com.shipperdog.api.identity;

import com.shipperdog.api.audit.AuditEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;

/**
 * Test-only listener that records every {@link AuditEvent} republished by
 * the audit module, so integration tests can assert emission without
 * parsing log output. Import with {@code @Import(AuditCapture.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuditCapture {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @EventListener
    void capture(AuditEvent event) {
        events.add(event);
    }

    public List<AuditEvent> events() {
        return events;
    }

    public void reset() {
        events.clear();
    }

    public List<AuditEvent> eventsOfType(com.shipperdog.api.audit.AuditEventType type) {
        return events.stream().filter(event -> event.type() == type).toList();
    }
}
