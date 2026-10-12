package com.shipperdog.api.notification.internal.entity;

import com.shipperdog.api.notification.AuditStreamFormat;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code audit_stream_deliveries} table (V53): one row per
 * (event, export) -- the HTTPS leg's delivery ledger of issue #71
 * (S14-05), the same shape semantics as {@code
 * NotificationDeliveryEntity} over its own id grain. The unique key
 * {@code (event, export)} is the at-least-once idempotency collapse
 * (a replayed outbox row's second dispatch finds the first row); the
 * retry drain re-attempts SCHEDULED rows with exponential backoff to
 * the attempt ceiling, and the terminal FAILED row is the visible,
 * replayable dead-letter of AC3 -- the replay action resets it to
 * SCHEDULED, no material is ever dropped.
 */
@Entity
@Table(name = "audit_stream_deliveries")
public class AuditStreamDeliveryEntity {

    public enum Status {
        SCHEDULED,
        DELIVERED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID eventId;

    private UUID exportId;

    /** The organization grain, denormalized from the export for the log's org-scoped reads. */
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private AuditStreamFormat format;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private int attempts;

    private Instant nextAttemptAt;

    private String lastError;

    private Instant deliveredAt;

    private Instant createdAt;

    protected AuditStreamDeliveryEntity() {}

    /**
     * One delivered row: a first attempt that succeeded (terminal at
     * birth).
     */
    public AuditStreamDeliveryEntity(
            UUID eventId, UUID exportId, UUID organizationId, AuditStreamFormat format, Instant now) {
        this.eventId = eventId;
        this.exportId = exportId;
        this.organizationId = organizationId;
        this.format = format;
        this.status = Status.DELIVERED;
        this.attempts = 1;
        this.deliveredAt = now;
        this.createdAt = now;
    }

    /** One row whose first attempt failed and is scheduled for its first retry. */
    public static AuditStreamDeliveryEntity scheduledForRetry(
            UUID eventId,
            UUID exportId,
            UUID organizationId,
            AuditStreamFormat format,
            Instant nextAttemptAt,
            String error,
            Instant now) {
        AuditStreamDeliveryEntity entity =
                new AuditStreamDeliveryEntity(eventId, exportId, organizationId, format, now);
        entity.status = Status.SCHEDULED;
        entity.attempts = 1;
        entity.deliveredAt = null;
        entity.nextAttemptAt = nextAttemptAt;
        entity.lastError = truncate(error);
        return entity;
    }

    /** One row that failed with no retry coming (a one-attempt ceiling): the terminal dead-letter at birth. */
    public static AuditStreamDeliveryEntity failedTerminal(
            UUID eventId, UUID exportId, UUID organizationId, AuditStreamFormat format, String error, Instant now) {
        AuditStreamDeliveryEntity entity =
                new AuditStreamDeliveryEntity(eventId, exportId, organizationId, format, now);
        entity.status = Status.FAILED;
        entity.attempts = 1;
        entity.deliveredAt = null;
        entity.lastError = truncate(error);
        return entity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getExportId() {
        return exportId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public AuditStreamFormat getFormat() {
        return format;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Marks a failed attempt for retry: the backoff stamp and the truncated error. */
    public void scheduleRetry(Instant now, Instant nextAttemptAt, String error) {
        this.attempts += 1;
        this.status = Status.SCHEDULED;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = truncate(error);
    }

    /** Marks the delivery complete. */
    public void markDelivered(Instant now) {
        this.status = Status.DELIVERED;
        this.attempts += 1;
        this.deliveredAt = now;
        this.nextAttemptAt = null;
    }

    /** Marks the delivery terminally failed at the attempt ceiling; the final error is the recorded one. */
    public void markFailed(Instant now, String error) {
        this.status = Status.FAILED;
        this.attempts += 1;
        this.lastError = truncate(error);
        this.nextAttemptAt = null;
    }

    /**
     * The replay action (AC3): a dead-letter row returns to the
     * retry schedule with a fresh attempt budget and the same
     * recorded history of what went before.
     */
    public void markReplayed(Instant now, Instant nextAttemptAt) {
        this.status = Status.SCHEDULED;
        this.attempts = 0;
        this.nextAttemptAt = nextAttemptAt;
    }

    /** The V53 ceiling on the recorded error (the failure's class, not its dump). */
    static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 512 ? error : error.substring(0, 512);
    }
}
