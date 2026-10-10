package com.shipperdog.api.notification.internal.bus;

import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.DomainEventBus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The V1.5 {@link DomainEventBus} transport (issue #51, S12-01,
 * ADR-0007): Spring's {@code ApplicationEventPublisher} fan-out -- one
 * in-process publish, every listener a Spring {@code @EventListener}
 * bean. No broker, by the M1 decision to defer messaging
 * infrastructure: the compose stack carries no Kafka or Pub/Sub, and
 * PRD 45's "managed Pub/Sub initially" is an operations choice the
 * port exists to absorb, not a dependency this story takes.
 *
 * <p>The upgrade path: a Kafka- or Pub/Sub-backed implementation of
 * the same interface replaces this bean beside the relay -- domain
 * modules, the outbox recorder, and the relay stay untouched, and the
 * notification dispatcher becomes a broker consumer exactly the way
 * it is a Spring listener today. Until then the in-process publish
 * carries the delivery policy alone: at-least-once with the event id
 * as the consumer's idempotency key (the relay may replay a row whose
 * mark did not commit).
 *
 * <p>Consumer-side failures never propagate: a listener that throws
 * is logged by Spring's error handler and the publish returns -- the
 * relay's bookkeeping is for transport failures, and a misbehaving
 * consumer must never stall the drain (the issue's sixth acceptance
 * criterion, structural).
 */
@Component
class InProcessDomainEventBus implements DomainEventBus {

    private final ApplicationEventPublisher events;

    InProcessDomainEventBus(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void publish(DomainEvent event) {
        events.publishEvent(event);
    }
}
