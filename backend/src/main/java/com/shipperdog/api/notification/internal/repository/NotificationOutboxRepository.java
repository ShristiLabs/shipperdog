package com.shipperdog.api.notification.internal.repository;

import com.shipperdog.api.notification.internal.entity.NotificationOutboxEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The outbox's persistence (V39): append and drain only -- the
 * recorder appends, the relay drains due PENDING rows and advances
 * their bookkeeping. No delete: the outbox is the durable event
 * history beside the delivery ledger.
 */
public interface NotificationOutboxRepository extends JpaRepository<NotificationOutboxEntity, UUID> {

    /**
     * The relay's drain batch: due PENDING rows in occurrence order.
     * The batch is claimed by update-at-completion (not row locks
     * across the batch), so two relay instances may publish the same
     * row -- the documented at-least-once posture, safe because
     * consumers deduplicate by event id.
     */
    List<NotificationOutboxEntity> findByRelayStateAndNextAttemptAtLessThanEqualOrderByOccurredAtAsc(
            NotificationOutboxEntity.RelayState relayState, Instant now, Pageable pageable);
}
