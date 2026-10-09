package com.shipperdog.api.notification.internal.bus;

import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.DomainEventBus;
import com.shipperdog.api.notification.internal.entity.NotificationOutboxEntity;
import com.shipperdog.api.notification.internal.repository.NotificationOutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The outbox relay (issue #51, S12-01, AC3): the scheduled drain that
 * turns committed outbox rows into bus publications. Each due PENDING
 * row is published to the {@link DomainEventBus} and then marked
 * RELAYED -- publish BEFORE mark, which is exactly what makes the
 * guarantee at-least-once rather than at-most-once: a crash between
 * the two replays the row, and consumers deduplicate by event id (the
 * delivery ledger's unique key does it for this service; webhook
 * receivers verify the {@code eventId} the same way, documented in
 * docs/notifications.md).
 *
 * <p>A publish failure (transport) retries with exponential backoff up
 * to the attempt ceiling, then the row stays FAILED in the table --
 * visible beside the delivery log, never silently dropped. Consumer
 * failures never reach this loop: the bus contract keeps listener
 * failures out of the publish call.
 *
 * <p>The relay is also where the S12+ broker lands: swapping the bus
 * bean for a Kafka or Cloud Pub/Sub implementation moves every drain
 * publication to the broker with this class untouched (ADR-0007, the
 * upgrade seam).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final NotificationOutboxRepository outbox;
    private final DomainEventBus bus;
    private final NotificationProperties properties;
    private final Clock clock;

    OutboxRelay(
            NotificationOutboxRepository outbox, DomainEventBus bus, NotificationProperties properties, Clock clock) {
        this.outbox = outbox;
        this.bus = bus;
        this.properties = properties;
        this.clock = clock;
    }

    /** The scheduled drain (the cadence and switch are environment-configurable). */
    @Scheduled(fixedDelayString = "${shipperdog.notifications.relay-fixed-delay:PT2S}")
    void drainScheduled() {
        if (properties.relayEnabled()) {
            drainOnce();
        }
    }

    /**
     * One drain pass: due PENDING rows, each published then marked.
     * Public seam for tests and manual drains (the trigger-route
     * precedent of the analytics rollup).
     */
    @Transactional
    public void drainOnce() {
        List<NotificationOutboxEntity> due = outbox.findByRelayStateAndNextAttemptAtLessThanEqualOrderByOccurredAtAsc(
                NotificationOutboxEntity.RelayState.PENDING,
                Instant.now(clock),
                PageRequest.of(0, properties.relayBatchSize()));
        for (NotificationOutboxEntity row : due) {
            Instant now = Instant.now(clock);
            try {
                DomainEvent event = row.toDomainEvent();
                bus.publish(event);
                row.markRelayed(now);
            } catch (RuntimeException e) {
                // Transport failure only (consumer failures do not
                // propagate out of the bus): backoff, or the terminal
                // FAILED stamp at the ceiling.
                if (row.getRelayAttempts() + 1 >= properties.relayMaxAttempts()) {
                    row.markFailed(now);
                    log.error(
                            "outbox relay exhausted event={} attempts={}",
                            row.getEventName(),
                            row.getRelayAttempts() + 1,
                            e);
                } else {
                    row.scheduleRetry(now.plus(properties.relayBackoff(row.getRelayAttempts())));
                    log.warn(
                            "outbox relay retry scheduled event={} attempts={} next={}",
                            row.getEventName(),
                            row.getRelayAttempts() + 1,
                            row.getNextAttemptAt());
                }
            }
        }
        if (!due.isEmpty()) {
            outbox.saveAll(due);
        }
    }

    /** The module's wiring: the properties record (scheduling itself is on the application). */
    @Configuration
    @EnableScheduling
    @EnableConfigurationProperties(NotificationProperties.class)
    static class BusConfig {}
}
