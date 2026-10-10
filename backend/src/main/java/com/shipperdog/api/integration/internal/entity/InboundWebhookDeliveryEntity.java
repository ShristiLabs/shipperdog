package com.shipperdog.api.integration.internal.entity;

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
 * Maps the {@code inbound_webhook_deliveries} table (V41): the
 * processed-delivery ledger of the generic inbound webhook framework
 * (issue #53, S12-03, FR-022). One row per VERIFIED delivery that was
 * processed to a terminal outcome -- the idempotency grain the
 * framework's receivers answer duplicates from (the V41 unique key on
 * (provider, delivery_id)) and the operator's "what did we consume"
 * view.
 *
 * <p>The claim discipline is transactional: a delivery's row is
 * written in the SAME transaction as its processing, carrying the
 * final outcome directly. A crash mid-processing leaves nothing
 * persisted, so a redelivery (GitHub redelivers with the SAME
 * {@code X-GitHub-Delivery} id) processes fresh; two concurrent
 * copies of one delivery both process (processing is idempotent by
 * construction -- enrichment only backfills absent fields) and the
 * second finisher answers the caller DUPLICATE on the unique-key
 * conflict. Rejected deliveries (failed signature) never reach this
 * ledger: they are audit events only, so a rejected attempt never
 * blocks a later legitimate redelivery.
 *
 * <p>{@code provider} is a plain varchar, deliberately not a PG enum:
 * the framework's provider set is open-ended by design (S12-04 adds
 * Jenkins/GitLab/App Store/Google Play, E13 the store connectors),
 * and an open vocabulary is a row value, not a schema migration.
 * {@code outcome} is the closed enum -- the framework's two terminal
 * processing outcomes.
 */
@Entity
@Table(name = "inbound_webhook_deliveries")
public class InboundWebhookDeliveryEntity {

    /** The framework's terminal processing outcomes (the V41 shape constraint). */
    public enum Outcome {
        /** Verified, consumed, and acted on. */
        ACCEPTED,
        /** Verified, consumed, and deliberately not acted on (an event type this receiver does not process). */
        IGNORED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The framework's provider key (github today; the S12-04/E13 receivers join it). */
    private String provider;

    /** The provider's delivery id -- the idempotency grain with {@code provider}. */
    private String deliveryId;

    /** The provider's event type header value (GitHub: {@code X-GitHub-Event}). */
    private String eventType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Outcome outcome;

    private Instant receivedAt;

    protected InboundWebhookDeliveryEntity() {}

    public InboundWebhookDeliveryEntity(
            String provider, String deliveryId, String eventType, Outcome outcome, Instant receivedAt) {
        this.provider = provider;
        this.deliveryId = deliveryId;
        this.eventType = eventType;
        this.outcome = outcome;
        this.receivedAt = receivedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public String getEventType() {
        return eventType;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
