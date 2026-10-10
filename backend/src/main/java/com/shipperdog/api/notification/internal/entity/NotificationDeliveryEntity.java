package com.shipperdog.api.notification.internal.entity;

import com.shipperdog.api.notification.NotificationChannel;
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
 * Maps the {@code notification_deliveries} table (V39): one row per
 * (event, subscription, recipient) -- the delivery log the issue's
 * fifth acceptance criterion makes visible, and the at-least-once
 * idempotency grain for the notification service's own consuming
 * (the V39 unique key collapses a replayed outbox row's second
 * dispatch into the first delivery).
 *
 * <p>{@code recipientKey} is the canonical recipient rendering --
 * {@code user:<id>} for IN_APP, {@code email:<address>} for EMAIL,
 * {@code webhook:<url>} for WEBHOOK -- one non-null column so the
 * unique idempotency key behaves identically for every channel.
 *
 * <p>Status semantics (the V39 shape constraint): SCHEDULED rows carry
 * their {@code nextAttemptAt} and retry with exponential backoff;
 * DELIVERED rows are terminal with their delivery stamp; FAILED rows
 * are terminal at the attempt ceiling with the last error recorded.
 * IN_APP rows are born DELIVERED -- the row IS the inbox item.
 */
@Entity
@Table(name = "notification_deliveries")
public class NotificationDeliveryEntity {

    public enum Status {
        SCHEDULED,
        DELIVERED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID eventId;

    private UUID subscriptionId;

    /** The organization grain, denormalized from the subscription for the log's scoped reads. */
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private NotificationChannel channel;

    private String recipientKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private int attempts;

    private Instant nextAttemptAt;

    private String lastError;

    private Instant deliveredAt;

    private Instant createdAt;

    protected NotificationDeliveryEntity() {}

    /**
     * One delivered row: the inbox write (IN_APP -- terminal at
     * birth), or a first attempt that succeeded (EMAIL/WEBHOOK).
     */
    public NotificationDeliveryEntity(
            UUID eventId,
            UUID subscriptionId,
            UUID organizationId,
            NotificationChannel channel,
            String recipientKey,
            Instant now) {
        this.eventId = eventId;
        this.subscriptionId = subscriptionId;
        this.organizationId = organizationId;
        this.channel = channel;
        this.recipientKey = recipientKey;
        this.status = Status.DELIVERED;
        this.attempts = 1;
        this.deliveredAt = now;
        this.createdAt = now;
    }

    /**
     * One row whose first attempt failed and is already scheduled for
     * its first retry: the backoff stamp and the truncated error ride
     * the insert.
     */
    public static NotificationDeliveryEntity scheduledForRetry(
            UUID eventId,
            UUID subscriptionId,
            UUID organizationId,
            NotificationChannel channel,
            String recipientKey,
            Instant nextAttemptAt,
            String error,
            Instant now) {
        NotificationDeliveryEntity entity =
                new NotificationDeliveryEntity(eventId, subscriptionId, organizationId, channel, recipientKey, now);
        entity.status = Status.SCHEDULED;
        entity.attempts = 1;
        entity.deliveredAt = null;
        entity.nextAttemptAt = nextAttemptAt;
        entity.lastError = truncate(error);
        return entity;
    }

    /**
     * One row that failed with no retry coming (a one-attempt
     * ceiling): terminal at birth with its single attempt and error.
     */
    public static NotificationDeliveryEntity failedTerminal(
            UUID eventId,
            UUID subscriptionId,
            UUID organizationId,
            NotificationChannel channel,
            String recipientKey,
            String error,
            Instant now) {
        NotificationDeliveryEntity entity =
                new NotificationDeliveryEntity(eventId, subscriptionId, organizationId, channel, recipientKey, now);
        entity.status = Status.FAILED;
        entity.attempts = 1;
        entity.deliveredAt = null;
        entity.lastError = truncate(error);
        return entity;
    }

    /** The retry ledger's terminal bookkeeping. */
    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getRecipientKey() {
        return recipientKey;
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

    /** The V39 ceiling on the recorded error (the failure's class, not its dump). */
    static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 512 ? error : error.substring(0, 512);
    }
}
