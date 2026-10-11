package com.shipperdog.api.integration;

import java.time.Instant;

/**
 * One mapped store status event, published for the platform's
 * connectors (issue #63, S13-03, AC2; the V42 receiver's reserved
 * meaning leg): the S12-04 receivers map a VERIFIED store
 * notification to its normalized row ({@code store_status_events})
 * and publish this event so the connector modules — which own what a
 * notification MEANS for a release — can act without the integration
 * module ever learning release semantics. The row stays the thin
 * ledger; this event is the pointer to it.
 *
 * <p>Published after the mapping transaction COMMITS (an
 * AFTER_COMMIT listener on the consumer side), so a consumer never
 * reads an uncommitted row and a consumer fault never rolls back the
 * receipt Apple or Google was acknowledged with.
 *
 * @param provider    the framework's provider key (appstore, google-play)
 * @param eventType   the stable event-type vocabulary value (Apple notificationType[:subtype])
 * @param bundleId    the app's store identifier (Apple carries one; Google RTDN carries none — null)
 * @param receivedAt  the moment the receiver mapped the row
 */
public record StoreStatusEventMapped(String provider, String eventType, String bundleId, Instant receivedAt) {}
