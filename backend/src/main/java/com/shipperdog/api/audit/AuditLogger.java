package com.shipperdog.api.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one-line audit emission API of the framework (issue #6, acceptance
 * criterion 1). Wraps {@link AuditEventPublisher} with defaults so a
 * caller can record a full FR-024 event in one line:
 *
 * <pre>{@code
 * audit.record(AuditEvent.builder(AuditEventType.PROMOTE_RELEASE)
 *         .userId(actorId)
 *         .tenantId(tenantId)
 *         .before(beforeJson)
 *         .after(afterJson)
 *         .reason("gates passed")
 *         .success(true)
 *         .build());
 * }</pre>
 *
 * <p>The logger stamps {@code occurredAt} from the shared clock when the
 * caller did not, and delegates to the publisher binding, which attaches
 * request context (correlation id, client IP) and applies
 * {@link AuditRedaction} before the event is logged and persisted.
 */
@Component
public class AuditLogger {

    private final AuditEventPublisher publisher;
    private final Clock clock;

    public AuditLogger(AuditEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Records a fully-built event, defaulting {@code occurredAt} when absent. */
    public void record(AuditEvent event) {
        if (event.occurredAt() == null) {
            publisher.publish(withOccurredAt(event, Instant.now(clock)));
            return;
        }
        publisher.publish(event);
    }

    /** Minimal one-liner: actor, outcome, and detail context. */
    public void record(AuditEventType type, UUID userId, boolean success, Map<String, String> detail) {
        record(AuditEvent.builder(type)
                .userId(userId)
                .success(success)
                .detail(detail)
                .build());
    }

    private static AuditEvent withOccurredAt(AuditEvent event, Instant occurredAt) {
        return new AuditEvent(
                event.type(),
                occurredAt,
                event.userId(),
                event.orgId(),
                event.tenantId(),
                event.success(),
                event.detail(),
                event.ipAddress(),
                event.applicationId(),
                event.versionId(),
                event.artifactId(),
                event.before(),
                event.after(),
                event.reason(),
                event.correlationId());
    }
}
