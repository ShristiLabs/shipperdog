package com.shipperdog.api.notification.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.UserDirectory;
import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.DomainEventName;
import com.shipperdog.api.notification.NotificationChannel;
import com.shipperdog.api.notification.NotificationException;
import com.shipperdog.api.notification.internal.entity.NotificationDeliveryEntity;
import com.shipperdog.api.notification.internal.entity.NotificationOutboxEntity;
import com.shipperdog.api.notification.internal.entity.NotificationSubscriptionEntity;
import com.shipperdog.api.notification.internal.repository.NotificationDeliveryRepository;
import com.shipperdog.api.notification.internal.repository.NotificationOutboxRepository;
import com.shipperdog.api.notification.internal.repository.NotificationSubscriptionRepository;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The subscription surface's core (issue #51, S12-01, AC4): the
 * org-scoped CRUD over the V39 subscriptions table, the validation
 * rules that keep every routing fact honest (event names from the
 * frozen vocabulary, roles the RBAC knows, users the directory
 * knows, the channel-appropriate recipient shape), and the delivery
 * log's reads. The rings pattern throughout: reference data of one
 * organization, concealed 404s across organizations, every write
 * audited (without the webhook secret -- it is write-only material).
 */
@Service
public class NotificationSubscriptionService {

    private static final int WEBHOOK_SECRET_MIN = 16;

    private final NotificationSubscriptionRepository subscriptions;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationOutboxRepository outbox;
    private final OrganizationAccess organizations;
    private final UserDirectory directory;
    private final AuditEventPublisher audit;
    private final WebhookTargetPolicy webhookTargets;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;

    NotificationSubscriptionService(
            NotificationSubscriptionRepository subscriptions,
            NotificationDeliveryRepository deliveries,
            NotificationOutboxRepository outbox,
            OrganizationAccess organizations,
            UserDirectory directory,
            AuditEventPublisher audit,
            WebhookTargetPolicy webhookTargets,
            NotificationDispatcher dispatcher,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.outbox = outbox;
        this.organizations = organizations;
        this.directory = directory;
        this.audit = audit;
        this.webhookTargets = webhookTargets;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    // The views ----------------------------------------------------------------

    /**
     * The served subscription view: everything EXCEPT the webhook
     * secret (write-only material; a PATCH rotates it, nothing reads
     * it back).
     */
    public record SubscriptionView(
            UUID id,
            UUID organizationId,
            List<String> eventTypes,
            NotificationChannel channel,
            List<String> recipientRoles,
            List<UUID> recipientUserIds,
            String webhookUrl,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {

        static SubscriptionView of(NotificationSubscriptionEntity row) {
            return new SubscriptionView(
                    row.getId(),
                    row.getOrganizationId(),
                    row.getEventTypes(),
                    row.getChannel(),
                    row.getRecipientRoles(),
                    row.getRecipientUserIds(),
                    row.getWebhookUrl(),
                    row.isEnabled(),
                    row.getCreatedAt(),
                    row.getUpdatedAt());
        }
    }

    /** One delivery-log row: the ledger as the console and the API read it. */
    public record DeliveryView(
            UUID id,
            UUID eventId,
            String eventName,
            UUID subscriptionId,
            NotificationChannel channel,
            String recipientKey,
            NotificationDeliveryEntity.Status status,
            int attempts,
            String lastError,
            Instant deliveredAt,
            Instant createdAt) {}

    /** The create command: every routing fact, validated below. */
    public record CreateCommand(
            List<String> eventTypes,
            NotificationChannel channel,
            List<String> recipientRoles,
            List<UUID> recipientUserIds,
            String webhookUrl,
            String webhookSecret) {}

    /** The edit command: absent fields keep their standing; an empty event list refuses (a subscription routes something). */
    public record UpdateCommand(
            List<String> eventTypes,
            List<String> recipientRoles,
            List<UUID> recipientUserIds,
            String webhookUrl,
            String webhookSecret,
            Boolean enabled) {}

    // The reads ------------------------------------------------------------------

    /** The organization's subscriptions, newest first. */
    public List<SubscriptionView> list(UUID actorId, UUID organizationId, int offset, int limit) {
        requireOrganization(organizationId);
        return subscriptions
                .findByOrganizationIdOrderByCreatedAtDesc(organizationId, PageRequest.of(offset / limit, limit))
                .stream()
                .map(SubscriptionView::of)
                .toList();
    }

    /** One subscription inside its organization; another organization's conceals as the miss. */
    public SubscriptionView get(UUID actorId, UUID organizationId, UUID subscriptionId) {
        requireOrganization(organizationId);
        return SubscriptionView.of(requireSubscription(organizationId, subscriptionId));
    }

    /**
     * The delivery log (AC5): the organization's deliveries, newest
     * first, optionally narrowed to one subscription and one status.
     * A foreign subscription id narrows nothing -- it conceals
     * exactly like an unknown one (the log never confirms or denies
     * another organization's routing).
     */
    public List<DeliveryView> deliveryLog(
            UUID actorId,
            UUID organizationId,
            UUID subscriptionId,
            NotificationDeliveryEntity.Status status,
            int offset,
            int limit) {
        requireOrganization(organizationId);
        if (subscriptionId != null
                && subscriptions
                        .findByIdAndOrganizationId(subscriptionId, organizationId)
                        .isEmpty()) {
            return List.of();
        }
        return deliveries
                .findAll(
                        orgScoped(organizationId, subscriptionId, status),
                        PageRequest.of(offset / limit, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(this::viewOf)
                .toList();
    }

    /** The log's filters, composed: the organization always, the subscription and status when given. */
    private static Specification<NotificationDeliveryEntity> orgScoped(
            UUID organizationId, UUID subscriptionId, NotificationDeliveryEntity.Status status) {
        Specification<NotificationDeliveryEntity> spec =
                (root, query, criteria) -> criteria.equal(root.get("organizationId"), organizationId);
        if (subscriptionId != null) {
            spec = spec.and((root, query, criteria) -> criteria.equal(root.get("subscriptionId"), subscriptionId));
        }
        if (status != null) {
            spec = spec.and((root, query, criteria) -> criteria.equal(root.get("status"), status));
        }
        return spec;
    }

    /** The caller's inbox: their in-app rows, newest first (the self-scoped route's model). */
    public List<DeliveryView> inbox(UUID userId, int offset, int limit) {
        return deliveries
                .findByRecipientKeyAndChannelOrderByCreatedAtDesc(
                        "user:" + userId, NotificationChannel.IN_APP, PageRequest.of(offset / limit, limit))
                .stream()
                .map(this::viewOf)
                .toList();
    }

    private DeliveryView viewOf(NotificationDeliveryEntity row) {
        return new DeliveryView(
                row.getId(),
                row.getEventId(),
                eventNameOf(row.getEventId()),
                row.getSubscriptionId(),
                row.getChannel(),
                row.getRecipientKey(),
                row.getStatus(),
                row.getAttempts(),
                row.getLastError(),
                row.getDeliveredAt(),
                row.getCreatedAt());
    }

    /** The ledger's name rendering: the frozen vocabulary of the outbox row, resolved per view. */
    private String eventNameOf(UUID eventId) {
        return outbox.findById(eventId).map(row -> row.getEventName().name()).orElse("UNKNOWN");
    }

    // The writes -------------------------------------------------------------------

    /** Creates a subscription (AC4): validated end to end, audited without the secret. */
    @Transactional
    public SubscriptionView create(UUID actorId, UUID organizationId, CreateCommand command) {
        requireOrganization(organizationId);
        if (command.channel() == null) {
            throw invalid("the channel is required", Map.of("channel", "IN_APP, EMAIL, WEBHOOK, SLACK, or TEAMS"));
        }
        ValidatedRouting routing = validate(command.eventTypes(), command.channel(), new RoutingFacts(command));
        NotificationSubscriptionEntity saved = subscriptions.save(new NotificationSubscriptionEntity(
                organizationId,
                routing.eventTypes(),
                command.channel(),
                routing.roles(),
                routing.userIds(),
                routing.webhookUrl(),
                routing.webhookSecret(),
                actorId,
                Instant.now(clock)));
        audit(actorId, organizationId, AuditEventType.NOTIFICATION_SUBSCRIPTION_CREATED, saved, "created");
        return SubscriptionView.of(saved);
    }

    /**
     * Edits a subscription: absent fields keep their standing; the
     * channel never moves. A concurrent edit of the same row refuses
     * with the public 409 (the optimistic {@code version} column) --
     * a stale writer can never silently resurrect a rotated secret or
     * a replaced URL from its out-of-date read.
     */
    @Transactional
    public SubscriptionView update(UUID actorId, UUID organizationId, UUID subscriptionId, UpdateCommand command) {
        requireOrganization(organizationId);
        NotificationSubscriptionEntity row = requireSubscription(organizationId, subscriptionId);
        RoutingFacts merged = new RoutingFacts(
                command.eventTypes() == null ? row.getEventTypes() : command.eventTypes(),
                command.recipientRoles() == null ? row.getRecipientRoles() : command.recipientRoles(),
                command.recipientUserIds() == null ? row.getRecipientUserIds() : command.recipientUserIds(),
                // An edit omits the webhook pair to keep the standing
                // values; supplying them rotates (the secret's only
                // read path is delivery's signing call).
                command.webhookUrl() == null ? row.getWebhookUrl() : command.webhookUrl(),
                command.webhookSecret() == null ? row.getWebhookSecret() : command.webhookSecret());
        ValidatedRouting routing = validate(merged.eventTypes(), row.getChannel(), merged);
        boolean enabled = command.enabled() == null ? row.isEnabled() : command.enabled();
        row.reroute(
                routing.eventTypes(),
                routing.roles(),
                routing.userIds(),
                routing.webhookUrl(),
                routing.webhookSecret(),
                enabled,
                Instant.now(clock));
        NotificationSubscriptionEntity saved;
        try {
            // Flush inside the method so the version check's refusal
            // is translated here, not after the boundary.
            saved = subscriptions.saveAndFlush(row);
        } catch (OptimisticLockingFailureException e) {
            throw new NotificationException(
                    NotificationException.Code.CONFLICT, "the subscription changed concurrently; reload and retry");
        }
        audit(actorId, organizationId, AuditEventType.NOTIFICATION_SUBSCRIPTION_UPDATED, saved, "updated");
        return SubscriptionView.of(saved);
    }

    /** Deletes a subscription; its deliveries stay in the log (the ledger is history, not state). */
    @Transactional
    public void delete(UUID actorId, UUID organizationId, UUID subscriptionId) {
        requireOrganization(organizationId);
        NotificationSubscriptionEntity row = requireSubscription(organizationId, subscriptionId);
        subscriptions.delete(row);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("subscriptionId", subscriptionId.toString());
        detail.put("channel", row.getChannel().name());
        detail.put("eventTypes", row.getEventTypes().size() + " names");
        audit.publish(AuditEvent.builder(AuditEventType.NOTIFICATION_SUBSCRIPTION_DELETED)
                .userId(actorId)
                .orgId(organizationId)
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(detail)
                .build());
    }

    // The test message (S12-02, AC3) ------------------------------------------------

    /** The test action's outcome as the API serves it: the synchronous attempt, honestly reported. */
    public record TestMessageView(boolean delivered, String channel, String error, Instant sentAt) {}

    /**
     * Sends the subscription's test message (issue #52, S12-02, AC3):
     * one synthetic {@code NOTIFICATION_TEST_SENT} event through the
     * REAL per-channel pipeline -- the same validation the
     * subscription already passed, the same formatters, the same
     * signed POST, the same ledger -- so a passing test is end-to-end
     * proof the channel works and a failing one names why, in the
     * response AND in the delivery log (a refused test is a SCHEDULED
     * row the retry drain keeps working, exactly like a real
     * delivery).
     *
     * <p>The synthetic event is recorded in the outbox born RELAYED:
     * this method's synchronous dispatch IS its publication -- the
     * relay must never re-fan a test out, or every test would deliver
     * twice. It stays in the outbox as honest history (the ledger's
     * foreign key needs the row; the org's event timeline shows the
     * test happened).
     */
    @Transactional
    public TestMessageView test(UUID actorId, UUID organizationId, UUID subscriptionId) {
        requireOrganization(organizationId);
        NotificationSubscriptionEntity subscription = requireSubscription(organizationId, subscriptionId);
        Instant now = Instant.now(clock);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", subscription.getChannel().name());
        payload.put("subscriptionId", subscriptionId.toString());
        NotificationOutboxEntity row = outbox.save(new NotificationOutboxEntity(
                DomainEventName.NOTIFICATION_TEST_SENT,
                organizationId,
                null,
                null,
                null,
                null,
                null,
                null,
                payload,
                now,
                now));
        // Born RELAYED (see the method javadoc): the synchronous
        // dispatch below IS the publication, under the row's real
        // generated id -- the ledger's foreign key and the event's
        // idempotency key both bind to it.
        row.markRelayed(now);
        outbox.save(row);
        DomainEvent event = row.toDomainEvent();
        WebhookDeliveryClient.Attempt attempt = dispatcher.dispatchTest(event, subscription);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("subscriptionId", subscriptionId.toString());
        detail.put("channel", subscription.getChannel().name());
        detail.put("delivered", Boolean.toString(attempt.delivered()));
        detail.put("action", "tested");
        // The URL and secret never ride an audit line (write-only
        // material, the credential-reference posture).
        audit.publish(AuditEvent.builder(AuditEventType.NOTIFICATION_SUBSCRIPTION_TESTED)
                .userId(actorId)
                .orgId(organizationId)
                .occurredAt(now)
                .success(true)
                .detail(detail)
                .build());
        return new TestMessageView(
                attempt.delivered(), subscription.getChannel().name(), attempt.error(), now);
    }

    // Validation ---------------------------------------------------------------------

    /** The routing facts as the validator sees them: the request's own (create) or merged with the standing (update). */
    private record RoutingFacts(
            List<String> eventTypes,
            List<String> recipientRoles,
            List<UUID> recipientUserIds,
            String webhookUrl,
            String webhookSecret) {

        RoutingFacts(CreateCommand command) {
            this(
                    command.eventTypes(),
                    command.recipientRoles(),
                    command.recipientUserIds(),
                    command.webhookUrl(),
                    command.webhookSecret());
        }
    }

    /** The channel-resolved routing facts a validated write produces. */
    private record ValidatedRouting(
            List<String> eventTypes, List<String> roles, List<UUID> userIds, String webhookUrl, String webhookSecret) {}

    /**
     * One validation pass over the routing facts: event names from
     * the frozen vocabulary (deduplicated, order-stable), roles the
     * RBAC knows, users the directory knows, and the channel's
     * recipient shape (recipients for IN_APP/EMAIL; URL and secret
     * for the webhook family -- WEBHOOK and S12-02's SLACK and TEAMS,
     * where an edit omits them to keep the standing values). One 422
     * carries every offense.
     */
    private ValidatedRouting validate(List<String> eventTypes, NotificationChannel channel, RoutingFacts facts) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<String> names = validateEventTypes(eventTypes, errors);
        List<String> roles = validateRoles(facts.recipientRoles(), errors);
        if (!errors.isEmpty()) {
            throw invalid("the subscription routing facts do not validate", errors);
        }
        List<UUID> userIds = facts.recipientUserIds() == null ? List.of() : facts.recipientUserIds();
        switch (channel) {
            case WEBHOOK, SLACK, TEAMS -> {
                String url = facts.webhookUrl();
                String secret = facts.webhookSecret();
                boolean urlBlank = url == null || url.isBlank();
                boolean secretBlank = secret == null || secret.isBlank();
                if (urlBlank || secretBlank || !urlBlank && !url.matches("^https?://.+")) {
                    throw invalid(
                            "a webhook subscription carries a well-formed URL and a signing secret",
                            Map.of(
                                    "webhookUrl", urlBlank ? "a http(s) URL is required" : "must be a http(s) URL",
                                    "webhookSecret", secretBlank ? "a signing secret is required" : "required"));
                }
                if (secret.length() < WEBHOOK_SECRET_MIN) {
                    throw invalid(
                            "the webhook signing secret is too short",
                            Map.of("webhookSecret", "at least " + WEBHOOK_SECRET_MIN + " characters"));
                }
                // The SSRF policy (the review's blocker): the target
                // must be a publicly routable http(s) host -- the
                // length, scheme, credentials, and range checks all
                // refuse through this same 422.
                webhookTargets.requireRoutable(url.trim());
                return new ValidatedRouting(names, roles, userIds, url.trim(), secret);
            }
            case IN_APP, EMAIL -> {
                if (roles.isEmpty() && userIds.isEmpty()) {
                    throw invalid(
                            "an in-app or email subscription carries recipients",
                            Map.of("recipientRoles", "at least one role or user is required"));
                }
                if (!userIds.isEmpty() && directory.byIds(userIds).size() != userIds.size()) {
                    throw invalid(
                            "a recipient user is unknown to the directory",
                            Map.of("recipientUserIds", "every id must resolve to a user"));
                }
                return new ValidatedRouting(names, roles, userIds, null, null);
            }
            default -> throw invalid("unknown channel", Map.of("channel", "unknown"));
        }
    }

    private List<String> validateEventTypes(List<String> eventTypes, Map<String, String> errors) {
        if (eventTypes == null || eventTypes.isEmpty()) {
            errors.put("eventTypes", "at least one event name is required");
            return List.of();
        }
        Set<String> known = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String name : eventTypes) {
            try {
                known.add(DomainEventName.valueOf(name).name());
            } catch (IllegalArgumentException e) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            errors.put("eventTypes", "unknown event names: " + unknown);
        }
        return List.copyOf(known);
    }

    private List<String> validateRoles(List<String> requested, Map<String, String> errors) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Set<String> known = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String role : requested) {
            try {
                known.add(OrgRole.valueOf(role).name());
            } catch (IllegalArgumentException e) {
                unknown.add(role);
            }
        }
        if (!unknown.isEmpty()) {
            errors.put("recipientRoles", "unknown roles: " + unknown);
        }
        return List.copyOf(known);
    }

    // Guards ---------------------------------------------------------------------------

    private void requireOrganization(UUID organizationId) {
        if (!organizations.organizationExists(organizationId)) {
            throw new NotificationException(
                    NotificationException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
    }

    private NotificationSubscriptionEntity requireSubscription(UUID organizationId, UUID subscriptionId) {
        return subscriptions
                .findByIdAndOrganizationId(subscriptionId, organizationId)
                .orElseThrow(() ->
                        new NotificationException(NotificationException.Code.NOT_FOUND, "subscription not found"));
    }

    private void audit(
            UUID actorId, UUID organizationId, AuditEventType type, NotificationSubscriptionEntity row, String action) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("subscriptionId", row.getId().toString());
        detail.put("channel", row.getChannel().name());
        detail.put("eventTypes", String.join(",", row.getEventTypes()));
        detail.put("roles", String.join(",", row.getRecipientRoles()));
        detail.put("userRecipients", row.getRecipientUserIds().size() + " users");
        detail.put("enabled", Boolean.toString(row.isEnabled()));
        detail.put("action", action);
        // The webhook secret never rides an audit line (write-only
        // material, the credential-reference posture).
        audit.publish(AuditEvent.builder(type)
                .userId(actorId)
                .orgId(organizationId)
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(detail)
                .build());
    }

    private static NotificationException invalid(String message, Map<String, String> errors) {
        return new NotificationException(NotificationException.Code.SUBSCRIPTION_INVALID, message, errors);
    }
}
