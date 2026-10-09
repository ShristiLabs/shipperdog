package com.shipperdog.api.notification;

/**
 * The domain-event bus transport port (issue #51, S12-01, PRD 45/51):
 * the single seam a relayed outbox event crosses on its way to
 * consumers. V1.5 ships the in-process implementation -- {@code
 * InProcessDomainEventBus}, a Spring {@code ApplicationEventPublisher}
 * fan-out -- because M1 deliberately deferred messaging infrastructure
 * (no broker in the compose stack; PRD 45's "managed Pub/Sub
 * initially" is an operations decision, not a code dependency).
 *
 * <p>The seam is the documented upgrade path (ADR-0007): when Kafka or
 * Cloud Pub/Sub lands, a new implementation of this interface
 * replaces the in-process one beside the relay and NOTHING else
 * changes -- domain modules keep publishing their typed in-process
 * events, the outbox recorder keeps writing rows in-transaction, and
 * the relay keeps draining; only the publish call's destination moves
 * to the broker. In-process consumers (the notification dispatcher)
 * migrate with it by subscribing through the broker the same way.
 *
 * <p>Implementations carry the delivery policy the issue's third
 * acceptance criterion states: at-least-once publication (a consumer
 * may see an event twice; consumers deduplicate by {@link
 * DomainEvent#eventId()}).
 */
public interface DomainEventBus {

    /**
     * Publishes one relayed outbox event to the bus's consumers.
     * Implementations must not throw for consumer-side failures --
     * the relay's retry bookkeeping is for transport failures, and a
     * misbehaving consumer must never stall the drain.
     *
     * @param event the durable event to publish; never null
     */
    void publish(DomainEvent event);
}
