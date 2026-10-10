package com.shipperdog.api.integration.internal.service;

import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.integration.internal.entity.InboundWebhookDeliveryEntity;
import com.shipperdog.api.integration.internal.repository.InboundWebhookDeliveryRepository;
import com.shipperdog.api.integration.internal.repository.StoreStatusEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The App Store receiver's processing (issue #54, S12-04, FR-022,
 * PRD 19/51): Apple's App Store Server Notifications arrive as a
 * JWS {@code signedPayload} -- verified against Apple's certificate
 * chain and signature by {@code AppleJwsVerifier} before this
 * service runs -- and are mapped THIN to a normalized internal event
 * row ({@code store_status_events}, V42): provider, Apple's own
 * {@code notificationUUID}, the {@code notificationType} (with
 * {@code subtype} appended when present -- the stable event-type
 * vocabulary), and the {@code data.bundleId}. E13's connectors own
 * what a notification MEANS for a release; this service records that
 * it happened, idempotently.
 *
 * <p><b>Authorization after authenticity</b>: a valid Apple signature
 * proves the notification is Apple's, not that it is about THIS
 * deployment's apps. The bundle-id allowlist is that leg -- a
 * notification for a bundle not on the configured list is
 * acknowledged and IGNORED (Apple retries non-2xx; a foreign app's
 * notification must not churn retries), and an EMPTY list keeps the
 * receiver effectively off (every notification ignores).
 *
 * <p><b>Replay</b>: Apple's own delivery id ({@code
 * notificationUUID}) is the ledger key -- Apple redelivers under the
 * SAME uuid, answered DUPLICATE without reprocessing -- and the
 * payload's {@code signedDate} (epoch milliseconds) is checked
 * against the framework's window by the controller. The mapping's
 * own unique (provider, notificationUUID) key is the second line of
 * defense: a copy that arrives under a fresh transport id converges
 * on the one row.
 *
 * <p><b>Audit posture</b>: STORE_EVENT_MAPPED carries the mapped
 * facts (provider, event, delivery id, bundle id); the raw JWS and
 * every credential are deliberately absent.
 */
@Service
public class AppStoreWebhookService {

    /** The framework's provider key this receiver owns. */
    public static final String PROVIDER = "appstore";

    private static final Logger log = LoggerFactory.getLogger(AppStoreWebhookService.class);

    private static final int EVENT_MAX = 128;
    private static final int DELIVERY_MAX = 255;
    private static final int BUNDLE_MAX = 255;

    private final InboundWebhookDeliveryRepository deliveries;
    private final StoreStatusEventRepository storeEvents;
    private final AuditLogger audit;
    private final Clock clock;
    private final java.util.List<String> bundleAllowlist;

    public AppStoreWebhookService(
            InboundWebhookDeliveryRepository deliveries,
            StoreStatusEventRepository storeEvents,
            AuditLogger audit,
            Clock clock,
            com.shipperdog.api.integration.internal.webhook.InboundWebhookProperties properties) {
        this.deliveries = deliveries;
        this.storeEvents = storeEvents;
        this.audit = audit;
        this.clock = clock;
        this.bundleAllowlist = properties.appstoreBundleIds();
    }

    /**
     * Processes one verified, freshness-checked delivery, answering
     * the receipt the controller returns. The ledger claim comes
     * FIRST -- a duplicate never reprocesses.
     */
    @Transactional
    public GitHubWebhookService.Receipt process(JsonNode payload) {
        String type = normalize(text(payload, "notificationType"));
        String delivery = truncate(normalize(text(payload, "notificationUUID")), DELIVERY_MAX);
        if (type.isBlank() || delivery.isBlank()) {
            // Shape drift inside an Apple-signed payload: an
            // acknowledged no-op, never a failure Apple would retry
            // and never a half-mapped row.
            return new GitHubWebhookService.Receipt("IGNORED", delivery);
        }
        String event = truncate(subtypePrefixed(type, text(payload, "subtype")), EVENT_MAX);
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
        String bundleId = truncate(normalize(text(payload.path("data"), "bundleId")), BUNDLE_MAX);
        if (bundleId.isBlank() || !bundleAllowlist.contains(bundleId)) {
            // A foreign bundle (or a missing bundle id inside shape
            // drift): acknowledged and ignored -- Apple retries
            // non-2xx, and a notification about somebody else's app
            // must not churn retries or map a row.
            audit.record(
                    AuditEventType.INBOUND_WEBHOOK_IGNORED,
                    null,
                    true,
                    Map.of("provider", PROVIDER, "event", event, "deliveryId", delivery));
            return new GitHubWebhookService.Receipt("IGNORED", delivery);
        }
        // 0 affected rows would mean a fresh transport id for an
        // already-mapped notificationUUID: converged on the one row,
        // answered ACCEPTED either way (the domain key's idempotency).
        storeEvents.map(rowId, PROVIDER, delivery, event, bundleId, null, null, Instant.now(clock));
        deliveries.markAccepted(rowId);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("provider", PROVIDER);
        detail.put("event", event);
        detail.put("deliveryId", delivery);
        detail.put("bundleId", bundleId);
        audit.record(AuditEventType.STORE_EVENT_MAPPED, null, true, detail);
        audit.record(
                AuditEventType.INBOUND_WEBHOOK_ACCEPTED,
                null,
                true,
                Map.of("provider", PROVIDER, "event", event, "deliveryId", delivery));
        log.info("mapped an appstore {} notification for bundle {}", event, bundleId);
        return new GitHubWebhookService.Receipt("ACCEPTED", delivery);
    }

    private static String subtypePrefixed(String type, String subtype) {
        String extra = normalize(subtype);
        return extra.isBlank() ? type : type + ":" + extra;
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
