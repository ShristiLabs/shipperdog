package com.shipperdog.api.integration.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code store_status_events} table (V42): the store
 * receivers' normalized internal events (issue #54, S12-04, FR-022,
 * PRD 51) -- one row per VERIFIED store notification that was mapped.
 * This is E13's state-sync foundation: the mapping is deliberately
 * thin (who sent it, the provider's own event id, a stable event-type
 * vocabulary, and the store-side identifiers), because E13's
 * connectors own what a notification MEANS for a release; no business
 * interpretation is persisted here.
 *
 * <p>Idempotency is the table's own unique key on (provider,
 * provider_event_id), INDEPENDENT of the transport ledger's
 * (provider, delivery_id): a redelivery under a new transport id that
 * carries an already-mapped notification converges on the same single
 * row -- the claim insert answers 0 affected rows and the delivery is
 * acknowledged, never re-mapped. Rows are append-only history (the
 * audit doctrine); no update or delete path exists.
 *
 * <p>NULLability is provider semantics, not optionality: Apple
 * notifications carry a bundle id but no purchase token; Google Play
 * RTDN envelopes carry a purchase token but NO package name (the
 * subscription's app is resolved by E13 from the token).
 */
@Entity
@Table(name = "store_status_events")
public class StoreStatusEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The framework's provider key (appstore / google-play). */
    private String provider;

    /** The provider's own event id -- the mapping idempotency grain with {@code provider}. */
    private String providerEventId;

    /** The stable event-type vocabulary value (Apple notificationType; Google kind/type). */
    private String eventType;

    /** The app's store identifier (Apple bundleId; Google RTDN carries none). */
    private String bundleId;

    /** The purchase the notification is about (Google purchaseToken; Apple transactions carry none here). */
    private String purchaseToken;

    /** The product reference (subscription id or sku) when the notification has one. */
    private String externalRef;

    private Instant receivedAt;

    protected StoreStatusEventEntity() {}

    public StoreStatusEventEntity(
            String provider,
            String providerEventId,
            String eventType,
            String bundleId,
            String purchaseToken,
            String externalRef,
            Instant receivedAt) {
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.eventType = eventType;
        this.bundleId = bundleId;
        this.purchaseToken = purchaseToken;
        this.externalRef = externalRef;
        this.receivedAt = receivedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getBundleId() {
        return bundleId;
    }

    public String getPurchaseToken() {
        return purchaseToken;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
