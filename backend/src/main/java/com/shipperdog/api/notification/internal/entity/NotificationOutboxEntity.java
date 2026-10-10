package com.shipperdog.api.notification.internal.entity;

import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.DomainEventName;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code notification_outbox} table (V39): one durable
 * domain event. Rows are appended by the outbox recorder inside the
 * business transaction (the transactional-outbox guarantee) and their
 * relay bookkeeping is the relay's alone to advance -- PENDING rows
 * become RELAYED (or FAILED, at the attempt ceiling) as the drain
 * publishes them. The event's facts are immutable once written; a
 * re-published row is the same event under the same id, which is the
 * consumer idempotency key.
 */
@Entity
@Table(name = "notification_outbox")
public class NotificationOutboxEntity {

    public enum RelayState {
        PENDING,
        RELAYED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private DomainEventName eventName;

    private UUID organizationId;

    private UUID tenantId;

    private UUID applicationId;

    private UUID versionId;

    private UUID artifactId;

    private UUID releaseId;

    private String correlationId;

    /** The event-specific facts, identifiers and stable vocabularies only. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;

    private Instant occurredAt;

    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private RelayState relayState = RelayState.PENDING;

    private int relayAttempts;

    private Instant nextAttemptAt;

    private Instant relayedAt;

    protected NotificationOutboxEntity() {}

    /**
     * One durable event, in the recorder's hand: the caller has
     * already resolved name, context, correlation, payload, and
     * occurrence; the row starts PENDING, due immediately.
     */
    public NotificationOutboxEntity(
            DomainEventName eventName,
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            UUID versionId,
            UUID artifactId,
            UUID releaseId,
            String correlationId,
            Map<String, Object> payload,
            Instant occurredAt,
            Instant now) {
        this.eventName = eventName;
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.applicationId = applicationId;
        this.versionId = versionId;
        this.artifactId = artifactId;
        this.releaseId = releaseId;
        this.correlationId = correlationId;
        this.payload = new LinkedHashMap<>(payload);
        this.occurredAt = occurredAt;
        this.createdAt = now;
        this.relayState = RelayState.PENDING;
        this.relayAttempts = 0;
        this.nextAttemptAt = now;
    }

    /** The relayed view: the row's facts as the bus's {@link DomainEvent} record. */
    public DomainEvent toDomainEvent() {
        return new DomainEvent(
                id,
                eventName,
                organizationId,
                tenantId,
                applicationId,
                versionId,
                artifactId,
                releaseId,
                correlationId,
                payload,
                occurredAt);
    }

    /** Marks the row relayed (the drain's post-publish step). */
    public void markRelayed(Instant at) {
        this.relayState = RelayState.RELAYED;
        this.relayedAt = at;
    }

    /** Schedules the next relay attempt with the given backoff. */
    public void scheduleRetry(Instant nextAttemptAt) {
        this.relayAttempts += 1;
        this.nextAttemptAt = nextAttemptAt;
    }

    /**
     * Marks the row terminally failed at the attempt ceiling. The V39
     * shape constraint stamps {@code relayed_at} on FAILED -- the
     * terminal-exhaustion marker, never a success.
     */
    public void markFailed(Instant at) {
        this.relayState = RelayState.FAILED;
        this.relayedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public DomainEventName getEventName() {
        return eventName;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    /** The event-specific facts (identifiers and stable vocabularies). */
    public Map<String, Object> getPayload() {
        return Map.copyOf(payload);
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public RelayState getRelayState() {
        return relayState;
    }

    public int getRelayAttempts() {
        return relayAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
