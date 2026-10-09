package com.shipperdog.api.notification.internal.repository;

import com.shipperdog.api.notification.NotificationChannel;
import com.shipperdog.api.notification.internal.entity.NotificationDeliveryEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The delivery ledger's persistence (V39): the retry runner's drain,
 * the delivery log's scoped reads (specifications compose the
 * organization/subscription/status filters), and the inbox read. The
 * unique key {@code (event_id, subscription_id, recipient_key)} is
 * the at-least-once idempotency grain -- the dispatcher pre-checks
 * through {@link #findByEventId} and the database collapses any race.
 */
public interface NotificationDeliveryRepository
        extends JpaRepository<NotificationDeliveryEntity, UUID>, JpaSpecificationExecutor<NotificationDeliveryEntity> {

    /** The dispatcher's idempotency pre-check: everything already delivered (or scheduled) for the event. */
    List<NotificationDeliveryEntity> findByEventId(UUID eventId);

    /** The retry runner's drain batch: due SCHEDULED rows, oldest attempt first. */
    List<NotificationDeliveryEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            NotificationDeliveryEntity.Status status, Instant now, Pageable pageable);

    /** The inbox read: one user's in-app rows, newest first (the self-scoped route). */
    List<NotificationDeliveryEntity> findByRecipientKeyAndChannelOrderByCreatedAtDesc(
            String recipientKey, NotificationChannel channel, Pageable pageable);

    /** One delivery inside its organization (the log's item read; a foreign row does not answer). */
    Optional<NotificationDeliveryEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
