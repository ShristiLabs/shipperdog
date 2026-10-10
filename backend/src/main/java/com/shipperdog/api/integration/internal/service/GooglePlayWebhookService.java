package com.shipperdog.api.integration.internal.service;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.integration.internal.entity.InboundWebhookDeliveryEntity;
import com.shipperdog.api.integration.internal.repository.InboundWebhookDeliveryRepository;
import com.shipperdog.api.integration.internal.repository.StoreStatusEventRepository;
import com.shipperdog.api.integration.internal.webhook.GooglePlayNotificationReader;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The Google Play receiver's processing (issue #54, S12-04, FR-022,
 * PRD 19/51): Google Play's Real-Time Developer Notifications arrive
 * as Pub/Sub push deliveries -- the {@code Authorization: Bearer}
 * OIDC token verified against Google's JWKS by {@code
 * GoogleJwtVerifier} before this service runs -- carrying the
 * notification base64-encoded (protobuf) in {@code message.data}.
 * The thin reader walks the published {@code notifications.proto}
 * field numbers ({@code GooglePlayNotificationReader}) and the
 * mapping writes ONE normalized internal event row ({@code
 * store_status_events}, V42): provider, the push message's id,
 * {@code kind[/type]} as the stable event-type vocabulary, the
 * purchase token, and the subscription id or sku. E13's connectors
 * own what a notification MEANS; this service records that it
 * happened, idempotently.
 *
 * <p><b>Replay</b>: the push message's own {@code messageId} is the
 * ledger key -- Pub/Sub redelivers under the SAME id until
 * acknowledged, answered DUPLICATE without reprocessing -- and the
 * envelope's {@code publishTime} is checked against the framework's
 * window by the controller (the JWT's issued-at freshness is part of
 * the authenticity leg). The mapping's own unique (provider,
 * messageId) key is the second line of defense.
 *
 * <p><b>Test notifications</b>: Google's {@code TestNotification}
 * maps the same as any other kind -- a row whose event type is
 * {@code test} -- because a configured subscription's first delivery
 * IS the test, and the operator's confirmation that the receiver is
 * live is the row's existence (and the STORE_EVENT_MAPPED audit).
 *
 * <p><b>Audit posture</b>: STORE_EVENT_MAPPED carries the mapped
 * facts (provider, event, delivery id, purchase token); the raw
 * envelope and every credential are deliberately absent.
 */
@Service
public class GooglePlayWebhookService {

    /** The framework's provider key this receiver owns. */
    public static final String PROVIDER = "google-play";

    private static final Logger log = LoggerFactory.getLogger(GooglePlayWebhookService.class);

    private static final int EVENT_MAX = 128;
    private static final int DELIVERY_MAX = 255;
    private static final int TOKEN_MAX = 512;
    private static final int REF_MAX = 255;

    private final InboundWebhookDeliveryRepository deliveries;
    private final StoreStatusEventRepository storeEvents;
    private final AuditLogger audit;
    private final Clock clock;

    public GooglePlayWebhookService(
            InboundWebhookDeliveryRepository deliveries,
            StoreStatusEventRepository storeEvents,
            AuditLogger audit,
            Clock clock) {
        this.deliveries = deliveries;
        this.storeEvents = storeEvents;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Processes one verified, freshness-checked delivery, answering
     * the receipt the controller returns. The ledger claim comes
     * FIRST -- a duplicate never reprocesses.
     */
    @Transactional
    public GitHubWebhookService.Receipt process(JsonNode envelope) {
        JsonNode message = envelope.path("message");
        String delivery = truncate(normalize(text(message, "messageId")), DELIVERY_MAX);
        GooglePlayNotificationReader.Notification notification = decode(message);
        if (delivery.isBlank() || notification == null) {
            // Shape drift inside a Google-signed push: an
            // acknowledged no-op, never a failure Pub/Sub would
            // redeliver and never a half-mapped row.
            return new GitHubWebhookService.Receipt("IGNORED", delivery);
        }
        String event = truncate(eventTypeOf(notification), EVENT_MAX);
        UUID rowId = UUID.randomUUID();
        if (deliveries.claim(
                        rowId,
                        PROVIDER,
                        delivery,
                        event,
                        InboundWebhookDeliveryEntity.Outcome.IGNORED.name(),
                        Instant.now(clock))
                == 0) {
            audit.record(
                    AuditEventType.INBOUND_WEBHOOK_DUPLICATE,
                    null,
                    true,
                    Map.of("provider", PROVIDER, "event", event, "deliveryId", delivery));
            return new GitHubWebhookService.Receipt("DUPLICATE", delivery);
        }
        String purchaseToken = truncate(normalize(notification.purchaseToken()), TOKEN_MAX);
        String productRef = truncate(normalize(notification.productRef()), REF_MAX);
        // 0 affected rows would mean a fresh transport id for an
        // already-mapped messageId: converged on the one row,
        // answered ACCEPTED either way (the domain key's idempotency).
        storeEvents.map(
                rowId,
                PROVIDER,
                delivery,
                event,
                null,
                nullToNull(purchaseToken),
                nullToNull(productRef),
                Instant.now(clock));
        deliveries.markAccepted(rowId);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("provider", PROVIDER);
        detail.put("event", event);
        detail.put("deliveryId", delivery);
        detail.put("purchaseToken", purchaseToken);
        audit.record(AuditEventType.STORE_EVENT_MAPPED, null, true, detail);
        audit.record(
                AuditEventType.INBOUND_WEBHOOK_ACCEPTED,
                null,
                true,
                Map.of("provider", PROVIDER, "event", event, "deliveryId", delivery));
        log.info("mapped a google-play {} notification", event);
        return new GitHubWebhookService.Receipt("ACCEPTED", delivery);
    }

    /** The freshness input for the controller's replay window: the push message's publishTime. */
    public Instant freshnessOf(JsonNode envelope) {
        return parse(text(envelope.path("message"), "publishTime"));
    }

    private static Instant parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The thin decode leg: base64 to protobuf bytes to the mapped facts, or null on any surprise. */
    private static GooglePlayNotificationReader.Notification decode(JsonNode message) {
        String encoded = text(message, "data");
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            return GooglePlayNotificationReader.read(Base64.getDecoder().decode(encoded.trim()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The stable event-type vocabulary: {@code kind} or {@code kind/type}. */
    private static String eventTypeOf(GooglePlayNotificationReader.Notification notification) {
        return notification.type() == null ? notification.kind() : notification.kind() + "/" + notification.type();
    }

    private static String nullToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** The document reader's tolerant text leg: null for absent or null members. */
    private static String text(JsonNode tree, String field) {
        JsonNode value = tree.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
