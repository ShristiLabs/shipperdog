package com.shipperdog.api.notification.internal.bus;

import com.shipperdog.api.artifact.ArtifactDownloadIssuedEvent;
import com.shipperdog.api.artifact.ArtifactUploadedEvent;
import com.shipperdog.api.artifact.ArtifactValidatedEvent;
import com.shipperdog.api.config.CorrelationIdFilter;
import com.shipperdog.api.feedback.FeedbackMarkedCriticalEvent;
import com.shipperdog.api.feedback.FeedbackReceivedEvent;
import com.shipperdog.api.notification.DomainEventName;
import com.shipperdog.api.notification.internal.entity.NotificationOutboxEntity;
import com.shipperdog.api.notification.internal.repository.NotificationOutboxRepository;
import com.shipperdog.api.release.ReleaseCreatedEvent;
import com.shipperdog.api.release.ReleasePromotedEvent;
import com.shipperdog.api.release.ReleaseTransitionedEvent;
import com.shipperdog.api.tester.TesterInvitedEvent;
import com.shipperdog.api.workflow.ApprovalRequestedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The transactional-outbox recorder (issue #51, S12-01, AC1/AC2): one
 * listener per wired typed domain event, each appending its {@code
 * notification_outbox} row. The {@link TransactionalEventListener}
 * with {@code BEFORE_COMMIT} makes the insert part of the publishing
 * transaction -- the {@code PersistentAuditEventSink} pattern since
 * S1-06 -- so a business change and its bus event commit atomically:
 * a rollback cancels the event, a commit guarantees it, and no crash
 * can ever separate the two (the guarantee the third acceptance
 * criterion's relay then turns into at-least-once publication). When
 * no transaction is active ({@code fallbackExecution}) the row lands
 * immediately -- an event off a transactional boundary is still a
 * fact worth delivering.
 *
 * <p>The mapping table (docs/notifications.md is the public face):
 * <ul>
 *   <li>{@code ArtifactUploadedEvent} -> ARTIFACT_UPLOADED</li>
 *   <li>{@code ArtifactValidatedEvent} -> ARTIFACT_VALIDATED (PASSED)
 *       or ARTIFACT_FAILED (FAILED); BLOCKED publishes nothing (the
 *       operational skip)</li>
 *   <li>{@code ArtifactDownloadIssuedEvent} -> TESTER_DOWNLOADED</li>
 *   <li>{@code ReleaseCreatedEvent} -> RELEASE_CREATED</li>
 *   <li>{@code ReleaseTransitionedEvent} -> RELEASE_APPROVED (the
 *       APPROVED crossing), RELEASE_PROMOTED is suppressed in favor of
 *       the hop's own event, every other crossing RELEASE_TRANSITIONED</li>
 *   <li>{@code ApprovalRequestedEvent} -> APPROVAL_REQUESTED</li>
 *   <li>{@code ReleasePromotedEvent} -> RELEASE_PROMOTED (one per hop)</li>
 *   <li>{@code TesterInvitedEvent} -> TESTER_INVITED</li>
 *   <li>{@code FeedbackReceivedEvent} -> FEEDBACK_RECEIVED</li>
 *   <li>{@code FeedbackMarkedCriticalEvent} -> FEEDBACK_MARKED_CRITICAL</li>
 * </ul>
 *
 * <p>Every row carries the PRD 51 attribute set, including the
 * originating request's correlation id read from the MDC the {@code
 * CorrelationIdFilter} established ({@code fallbackExecution} paths
 * and worker threads carry none -- the row is honestly
 * context-free, never placeholder-filled).
 */
@Component
class OutboxRecorder {

    private static final Logger log = LoggerFactory.getLogger(OutboxRecorder.class);

    private final NotificationOutboxRepository outbox;
    private final Clock clock;

    OutboxRecorder(NotificationOutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    // The artifact stream ---------------------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(ArtifactUploadedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", event.platform());
        payload.put("fileName", event.fileName());
        payload.put("size", event.size());
        payload.put("sha256", event.sha256());
        record(
                DomainEventName.ARTIFACT_UPLOADED,
                event.organizationId(),
                event.tenantId(),
                event.applicationId(),
                event.versionId(),
                event.artifactId(),
                null,
                payload,
                event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(ArtifactValidatedEvent event) {
        if ("BLOCKED".equals(event.outcome())) {
            return;
        }
        DomainEventName name =
                "PASSED".equals(event.outcome()) ? DomainEventName.ARTIFACT_VALIDATED : DomainEventName.ARTIFACT_FAILED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", event.platform());
        payload.put("outcome", event.outcome());
        if (event.failureCode() != null) {
            payload.put("failureCode", event.failureCode());
        }
        record(
                name,
                event.organizationId(),
                event.tenantId(),
                event.applicationId(),
                event.versionId(),
                event.artifactId(),
                null,
                payload,
                event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(ArtifactDownloadIssuedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", event.platform());
        payload.put("testerUserId", event.actorId().toString());
        record(
                DomainEventName.TESTER_DOWNLOADED,
                event.organizationId(),
                event.tenantId(),
                event.applicationId(),
                event.versionId(),
                event.artifactId(),
                null,
                payload,
                event.occurredAt());
    }

    // The release stream ----------------------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(ReleaseCreatedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseRef", event.releaseRef());
        payload.put("ring", event.ring());
        record(
                DomainEventName.RELEASE_CREATED,
                event.organizationId(),
                event.tenantId(),
                event.applicationId(),
                event.versionId(),
                null,
                event.releaseId(),
                payload,
                event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(ReleaseTransitionedEvent event) {
        if ("PROMOTED".equals(event.toStatus())) {
            // The final hop publishes the promotion event with the
            // crossing's own facts; a second bus event for the same
            // moment would double-deliver (the mapping table's one
            // documented suppression).
            return;
        }
        DomainEventName name = "APPROVED".equals(event.toStatus())
                ? DomainEventName.RELEASE_APPROVED
                : DomainEventName.RELEASE_TRANSITIONED;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseRef", event.releaseRef());
        payload.put("fromStatus", event.fromStatus());
        payload.put("toStatus", event.toStatus());
        if (event.reason() != null) {
            payload.put("reason", event.reason());
        }
        record(
                name,
                event.organizationId(),
                event.tenantId(),
                event.applicationId(),
                event.versionId(),
                null,
                event.releaseId(),
                payload,
                event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(ApprovalRequestedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseRef", event.releaseRef());
        payload.put("roundNumber", event.roundNumber());
        payload.put("position", event.position());
        payload.put("approvalType", event.approvalType());
        record(
                DomainEventName.APPROVAL_REQUESTED,
                event.organizationId(),
                event.tenantId(),
                event.applicationId(),
                null,
                null,
                event.releaseId(),
                payload,
                event.requestedAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(ReleasePromotedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseRef", event.releaseRef());
        payload.put("fromRing", event.fromRing());
        payload.put("toRing", event.toRing());
        payload.put("tenants", event.tenants().stream().map(UUID::toString).toList());
        payload.put("crossedToPromoted", event.crossedToPromoted());
        record(
                DomainEventName.RELEASE_PROMOTED,
                event.organizationId(),
                event.tenantId(),
                event.applicationId(),
                event.versionId(),
                null,
                event.releaseId(),
                payload,
                event.occurredAt());
    }

    // The onboarding and feedback streams ------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(TesterInvitedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reinvite", event.reinvite());
        record(
                DomainEventName.TESTER_INVITED,
                event.organizationId(),
                null,
                null,
                null,
                null,
                null,
                payload,
                event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(FeedbackReceivedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseRef", event.releaseRef());
        payload.put("feedbackType", event.type());
        payload.put("attachmentCount", event.attachmentCount());
        record(
                DomainEventName.FEEDBACK_RECEIVED,
                event.organizationId(),
                event.submitterTenantId(),
                event.applicationId(),
                event.versionId(),
                event.artifactId(),
                event.releaseId(),
                payload,
                event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    void on(FeedbackMarkedCriticalEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseRef", event.releaseRef());
        payload.put("feedbackType", event.type());
        record(
                DomainEventName.FEEDBACK_MARKED_CRITICAL,
                event.organizationId(),
                event.submitterTenantId(),
                event.applicationId(),
                null,
                null,
                event.releaseId(),
                payload,
                event.occurredAt());
    }

    // The append --------------------------------------------------------------

    private void record(
            DomainEventName name,
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            UUID versionId,
            UUID artifactId,
            UUID releaseId,
            Map<String, Object> payload,
            Instant occurredAt) {
        Instant now = Instant.now(clock);
        outbox.save(new NotificationOutboxEntity(
                name,
                organizationId,
                tenantId,
                applicationId,
                versionId,
                artifactId,
                releaseId,
                correlationId(),
                payload,
                occurredAt,
                now));
        log.debug(
                "outbox event={} organization={} release={} correlation={}",
                name,
                organizationId,
                releaseId,
                correlationId());
    }

    /**
     * The originating request's correlation id from the MDC the
     * {@code CorrelationIdFilter} established, or null off-request
     * (worker threads, schedulers) -- PRD 51's note that every event
     * carries the originating request's trace, honestly empty when
     * there was no request.
     */
    private static String correlationId() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        return value == null || value.isBlank() ? null : value;
    }
}
