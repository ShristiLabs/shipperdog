package com.shipperdog.api.integration.internal.repository;

import com.shipperdog.api.integration.internal.entity.StoreStatusEventEntity;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The normalized store events' persistence (V42). Like the V41
 * ledger, the write is a native {@code INSERT ... ON CONFLICT DO
 * NOTHING} INSIDE the delivery's processing transaction: the
 * affected-rows verdict is the mapping's idempotency answer itself (1
 * = this transaction mapped the event, 0 = a concurrent or replayed
 * copy already had -- the caller acknowledges without re-mapping),
 * with no exception path to unwind. The unique key this claim races
 * on is the DOMAIN grain (provider, provider_event_id), independent
 * of the transport ledger's (provider, delivery_id). Deletes and
 * updates do not exist: the table is append-only history (the audit
 * doctrine), and its reads are E13's connectors, not API surface.
 */
public interface StoreStatusEventRepository extends JpaRepository<StoreStatusEventEntity, UUID> {

    /**
     * Maps one event by writing its row, answering whether THIS call
     * won the (provider, provider_event_id) idempotency key. The
     * NULLable columns are passed as parameters so the caller's
     * provider semantics decide them, not the repository.
     */
    @Modifying
    @Query(
            value = "insert into store_status_events "
                    + "(id, provider, provider_event_id, event_type, bundle_id, purchase_token, external_ref, received_at) "
                    + "values (:id, :provider, :providerEventId, :eventType, :bundleId, :purchaseToken, :externalRef, :receivedAt) "
                    + "on conflict on constraint uq_store_status_events_provider_event do nothing",
            nativeQuery = true)
    int map(
            @Param("id") UUID id,
            @Param("provider") String provider,
            @Param("providerEventId") String providerEventId,
            @Param("eventType") String eventType,
            @Param("bundleId") String bundleId,
            @Param("purchaseToken") String purchaseToken,
            @Param("externalRef") String externalRef,
            @Param("receivedAt") Instant receivedAt);
}
