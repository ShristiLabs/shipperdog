package com.shipperdog.api.integration.internal.repository;

import com.shipperdog.api.integration.internal.entity.InboundWebhookDeliveryEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The processed-delivery ledger's persistence (V41). The write is a
 * native {@code INSERT ... ON CONFLICT DO NOTHING} INSIDE the
 * processing transaction: the affected-rows verdict is the
 * idempotency answer itself (1 = this transaction owns the delivery,
 * 0 = a concurrent copy already finished it -- the caller answers
 * DUPLICATE), with no exception path to unwind. Deletes and updates
 * do not exist: the ledger is append-only history (the audit
 * doctrine), and its reads are operator queries, not API surface.
 */
public interface InboundWebhookDeliveryRepository extends JpaRepository<InboundWebhookDeliveryEntity, UUID> {

    /**
     * Claims the delivery by writing its final-outcome row, answering
     * whether THIS call won the (provider, delivery_id) idempotency
     * key. The outcome parameter is the enum's name, cast to the V41
     * type; the conflict target is the unique key.
     */
    @Modifying
    @Query(
            value =
                    "insert into inbound_webhook_deliveries (id, provider, delivery_id, event_type, outcome, received_at) "
                            + "values (:id, :provider, :deliveryId, :eventType, cast(:outcome as inbound_webhook_outcome), :receivedAt) "
                            + "on conflict on constraint uq_inbound_webhook_deliveries_provider_delivery do nothing",
            nativeQuery = true)
    int claim(
            @Param("id") UUID id,
            @Param("provider") String provider,
            @Param("deliveryId") String deliveryId,
            @Param("eventType") String eventType,
            @Param("outcome") String outcome,
            @Param("receivedAt") Instant receivedAt);

    /**
     * Promotes the claim's provisional IGNORED outcome to ACCEPTED
     * after processing acted on the delivery. Only the transaction
     * that won the claim calls this (it owns the row), inside the
     * same transaction as the processing it records.
     */
    @Modifying
    @Query(
            value = "update inbound_webhook_deliveries set outcome = 'ACCEPTED' where id = :id and outcome = 'IGNORED'",
            nativeQuery = true)
    int markAccepted(@Param("id") UUID id);
}
