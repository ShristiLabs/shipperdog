package com.shipperdog.api.notification.internal.repository;

import com.shipperdog.api.notification.internal.entity.AuditStreamDeliveryEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The HTTPS leg's ledger persistence (V53): the retry drain, the
 * delivery log's scoped reads (specifications compose the
 * organization/export/status filters), and the dead-letter's replay
 * read. The unique key {@code (event_id, export_id)} is the
 * at-least-once idempotency grain -- the dispatcher pre-checks
 * through {@link #findByEventId} and the database collapses any
 * race.
 */
public interface AuditStreamDeliveryRepository
        extends JpaRepository<AuditStreamDeliveryEntity, UUID>, JpaSpecificationExecutor<AuditStreamDeliveryEntity> {

    /** The dispatcher's idempotency pre-check: everything already delivered (or scheduled) for the event. */
    List<AuditStreamDeliveryEntity> findByEventId(UUID eventId);

    /** The retry drain's batch: due SCHEDULED rows, oldest attempt first. */
    List<AuditStreamDeliveryEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            AuditStreamDeliveryEntity.Status status, Instant now, Pageable pageable);

    /** One delivery inside its organization (the log's item read; a foreign row does not answer). */
    Optional<AuditStreamDeliveryEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The replay action's read: the export's terminally failed rows (the visible dead-letter). */
    List<AuditStreamDeliveryEntity> findByExportIdAndStatusOrderByCreatedAtAsc(
            UUID exportId, AuditStreamDeliveryEntity.Status status);
}
