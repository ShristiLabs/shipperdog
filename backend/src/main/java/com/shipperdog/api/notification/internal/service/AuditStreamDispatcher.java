package com.shipperdog.api.notification.internal.service;

import com.shipperdog.api.environment.SecretReferenceResolver;
import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.DomainEventName;
import com.shipperdog.api.notification.internal.bus.NotificationProperties;
import com.shipperdog.api.notification.internal.entity.AuditStreamDeliveryEntity;
import com.shipperdog.api.notification.internal.entity.AuditStreamExportEntity;
import com.shipperdog.api.notification.internal.entity.NotificationOutboxEntity;
import com.shipperdog.api.notification.internal.repository.AuditStreamDeliveryRepository;
import com.shipperdog.api.notification.internal.repository.AuditStreamExportRepository;
import com.shipperdog.api.notification.internal.repository.NotificationOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit stream's HTTPS fan-out and retry drain (issue #71,
 * S14-05, AC1/AC3): the bus consumer that turns one relayed {@code
 * AUDIT_EVENT} into signed POSTs -- the organization's enabled HTTPS
 * exports whose event-type filter selects the event, each attempted
 * through the same {@link WebhookDeliveryClient} wire discipline the
 * webhook channel rides (HMAC over the exact body bytes, no
 * redirects, any 2xx is success), each recorded in the delivery
 * ledger. Consumer downtime is the ledger's problem and it is
 * durable: failed attempts schedule with exponential backoff
 * (the standing {@link NotificationProperties} policy), the due rows
 * re-attempt on the shared cadence, and the attempt ceiling's
 * terminal FAILED row is the visible dead-letter the replay action
 * re-enqueues -- buffering without loss, exactly AC3.
 *
 * <p>Fully off the business path (the S12-01 structural guarantee):
 * the dispatcher runs inside the relay's drain, never inside a
 * business transaction, and every failure -- secret resolution,
 * signing, POST -- is caught, recorded, and scheduled; nothing a
 * delivery does can refuse, delay, or roll back the transition that
 * produced the event. Secrets resolve per attempt through the
 * environment module's {@link SecretReferenceResolver} port
 * (AC5): a rotated reference takes effect on the next attempt, and
 * an unresolvable one is an honest failed attempt ("the signing
 * secret reference did not resolve"), never a dropped event.
 *
 * <p>Idempotent by (event, export): the ledger's unique key and the
 * pre-check collapse a replayed outbox row's second dispatch into
 * the first delivery row -- the at-least-once relay is invisible to
 * the collector, which deduplicates on the envelope's {@code
 * event_id} regardless.
 */
@Component
public class AuditStreamDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AuditStreamDispatcher.class);

    private final AuditStreamExportRepository exports;
    private final AuditStreamDeliveryRepository deliveries;
    private final NotificationOutboxRepository outbox;
    private final WebhookDeliveryClient webhooks;
    private final SecretReferenceResolver secrets;
    private final NotificationProperties properties;
    private final Clock clock;

    AuditStreamDispatcher(
            AuditStreamExportRepository exports,
            AuditStreamDeliveryRepository deliveries,
            NotificationOutboxRepository outbox,
            WebhookDeliveryClient webhooks,
            SecretReferenceResolver secrets,
            NotificationProperties properties,
            Clock clock) {
        this.exports = exports;
        this.deliveries = deliveries;
        this.outbox = outbox;
        this.webhooks = webhooks;
        this.secrets = secrets;
        this.properties = properties;
        this.clock = clock;
    }

    /** The bus subscription: one relayed event, fanned out. Failures are recorded, never propagated. */
    @EventListener
    @Transactional
    public void on(DomainEvent event) {
        if (event.name() != DomainEventName.AUDIT_EVENT) {
            return;
        }
        try {
            dispatch(event);
        } catch (RuntimeException e) {
            // The structural guarantee: nothing here reaches the relay
            // (a failed fan-out is a ledger entry, never a
            // business-flow exception).
            log.error("audit stream dispatch failed event={} organization={}", event.name(), event.organizationId(), e);
        }
    }

    /** The retry drain: due SCHEDULED deliveries re-attempted on the relay's cadence and switch. */
    @Scheduled(fixedDelayString = "${shipperdog.notifications.relay-fixed-delay:PT2S}")
    void retryScheduled() {
        if (properties.relayEnabled()) {
            retryDue();
        }
    }

    /** One retry pass. Public seam for tests and manual drains. */
    @Transactional
    public void retryDue() {
        List<AuditStreamDeliveryEntity> due = deliveries.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                AuditStreamDeliveryEntity.Status.SCHEDULED,
                Instant.now(clock),
                PageRequest.of(0, properties.relayBatchSize()));
        for (AuditStreamDeliveryEntity row : due) {
            try {
                attemptScheduled(row);
            } catch (RuntimeException e) {
                // A retry attempt's own bookkeeping failure: the row
                // keeps its schedule and the next pass tries again.
                log.error("audit stream retry failed delivery={} attempts={}", row.getId(), row.getAttempts(), e);
            }
        }
    }

    // The fan-out -------------------------------------------------------------

    private void dispatch(DomainEvent event) {
        List<AuditStreamExportEntity> routing = exports.findByOrganizationIdAndEnabledTrue(event.organizationId());
        if (routing.isEmpty()) {
            return;
        }
        String auditEventType = String.valueOf(event.payload().get("event_type"));
        Set<UUID> alreadyDelivered = deliveredExports(event.eventId());
        for (AuditStreamExportEntity export : routing) {
            if (export.getKind() != com.shipperdog.api.notification.AuditStreamKind.HTTPS
                    || !export.streams(auditEventType)
                    || alreadyDelivered.contains(export.getId())) {
                continue;
            }
            attemptDelivery(event, export);
        }
    }

    /** One first attempt against one export: success writes the terminal row, failure the scheduled (or terminal) one. */
    private void attemptDelivery(DomainEvent event, AuditStreamExportEntity export) {
        Instant now = Instant.now(clock);
        WebhookDeliveryClient.Attempt attempt = attempt(event, export);
        if (attempt.delivered()) {
            deliveries.save(new AuditStreamDeliveryEntity(
                    event.eventId(), export.getId(), export.getOrganizationId(), export.getFormat(), now));
            return;
        }
        if (properties.deliveryMaxAttempts() <= 1) {
            deliveries.save(AuditStreamDeliveryEntity.failedTerminal(
                    event.eventId(),
                    export.getId(),
                    export.getOrganizationId(),
                    export.getFormat(),
                    attempt.error(),
                    now));
            log.error("audit stream exhausted event={} export={} attempts=1", event.name(), export.getId());
            return;
        }
        deliveries.save(AuditStreamDeliveryEntity.scheduledForRetry(
                event.eventId(),
                export.getId(),
                export.getOrganizationId(),
                export.getFormat(),
                now.plus(properties.deliveryBackoff(0)),
                attempt.error(),
                now));
    }

    /** The attempt itself: resolve the secrets, render the format's body, sign, POST. Never throws. */
    private WebhookDeliveryClient.Attempt attempt(DomainEvent event, AuditStreamExportEntity export) {
        String secret = secrets.resolve(export.getSigningSecretRef()).orElse(null);
        if (secret == null) {
            return WebhookDeliveryClient.Attempt.failure("the signing secret reference did not resolve");
        }
        Map<String, String> extraHeaders = new HashMap<>();
        if (export.getAuthHeaderName() != null) {
            String authValue = secrets.resolve(export.getAuthSecretRef()).orElse(null);
            if (authValue == null) {
                return WebhookDeliveryClient.Attempt.failure("the auth secret reference did not resolve");
            }
            extraHeaders.put(export.getAuthHeaderName(), authValue);
        }
        byte[] body = SiemEventFormats.bodyOf(export.getFormat(), event.eventId(), event.payload(), event.occurredAt());
        return webhooks.deliver(export.getDestinationUrl(), secret, event.name().name(), body, extraHeaders);
    }

    /** One retry attempt against an existing row (the export may have been reconfigured or deleted meanwhile). */
    private void attemptScheduled(AuditStreamDeliveryEntity row) {
        AuditStreamExportEntity export = exports.findById(row.getExportId()).orElse(null);
        if (export == null) {
            // The export was deleted between scheduling and retry:
            // the delivery retires quietly (the ledger row stays as
            // history, the destination is gone, not unavailable).
            row.markFailed(Instant.now(clock), "export deleted");
            deliveries.save(row);
            return;
        }
        Instant now = Instant.now(clock);
        DomainEvent event = eventOf(row.getEventId());
        if (event == null) {
            row.markFailed(now, "outbox row unreadable");
            deliveries.save(row);
            return;
        }
        WebhookDeliveryClient.Attempt attempt;
        try {
            attempt = attempt(event, export);
        } catch (RuntimeException e) {
            attempt = WebhookDeliveryClient.Attempt.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (attempt.delivered()) {
            row.markDelivered(now);
        } else if (row.getAttempts() >= properties.deliveryMaxAttempts() - 1) {
            row.markFailed(now, attempt.error());
            log.error(
                    "audit stream exhausted event={} export={} attempts={}",
                    event.name(),
                    row.getExportId(),
                    row.getAttempts() + 1);
        } else {
            row.scheduleRetry(now, now.plus(properties.deliveryBackoff(row.getAttempts())), attempt.error());
        }
        deliveries.save(row);
    }

    /**
     * The test action's synchronous attempt (the notification test
     * precedent): the REAL delivery pipeline -- same resolution, same
     * rendering, same signed POST -- against the caller's synthetic
     * event, honestly reported. Not recorded in the ledger: the test
     * event is not a trail fact, and the outbox row the service
     * records for it is born RELAYED so the relay never re-fans it.
     */
    public WebhookDeliveryClient.Attempt dispatchTest(DomainEvent synthetic, AuditStreamExportEntity export) {
        return attempt(synthetic, export);
    }

    // The replay action (AC3) ---------------------------------------------------

    /**
     * Re-enqueues the export's terminally failed deliveries: every
     * dead-letter row returns to the retry schedule with a fresh
     * attempt budget. Answers the replayed count for the audit line
     * and the response.
     */
    @Transactional
    public int replayFailed(UUID exportId) {
        List<AuditStreamDeliveryEntity> deadLetters = deliveries.findByExportIdAndStatusOrderByCreatedAtAsc(
                exportId, AuditStreamDeliveryEntity.Status.FAILED);
        Instant now = Instant.now(clock);
        for (AuditStreamDeliveryEntity row : deadLetters) {
            row.markReplayed(now, now);
            deliveries.save(row);
        }
        return deadLetters.size();
    }

    // Helpers ---------------------------------------------------------------------

    private Set<UUID> deliveredExports(UUID eventId) {
        Set<UUID> ids = new HashSet<>();
        for (AuditStreamDeliveryEntity row : deliveries.findByEventId(eventId)) {
            ids.add(row.getExportId());
        }
        return ids;
    }

    private DomainEvent eventOf(UUID eventId) {
        return outbox.findById(eventId)
                .map(NotificationOutboxEntity::toDomainEvent)
                .orElse(null);
    }
}
