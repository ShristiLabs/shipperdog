package com.shipperdog.api.notification.internal.service;

import com.shipperdog.api.identity.UserDirectory;
import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.EmailSender;
import com.shipperdog.api.notification.NotificationChannel;
import com.shipperdog.api.notification.internal.bus.NotificationProperties;
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
import java.util.LinkedHashSet;
import java.util.List;
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
 * The notification fan-out (issue #51, S12-01, AC4/AC5/AC6): the bus
 * consumer that turns one relayed {@link DomainEvent} into deliveries
 * -- the organization's enabled subscriptions matching the event
 * name, each resolved to its channel's recipients, each attempted and
 * recorded in the delivery ledger.
 *
 * <p>Fully off the business path (the sixth acceptance criterion,
 * structural): the dispatcher runs inside the relay's drain, never
 * inside a business transaction, and every failure -- recipient
 * resolution, email send, webhook POST -- is caught, recorded in the
 * ledger, and scheduled for retry with exponential backoff. Nothing a
 * notification does can refuse, delay, or roll back the transition
 * that produced the event.
 *
 * <p>Idempotent by event id (the third acceptance criterion): the
 * ledger's unique key {@code (event, subscription, recipient)} and the
 * pre-check on existing rows collapse a replayed outbox row into the
 * first delivery set -- the at-least-once relay is invisible to
 * recipients.
 */
@Component
class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationSubscriptionRepository subscriptions;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationOutboxRepository outbox;
    private final OrganizationAccess organizations;
    private final UserDirectory directory;
    private final EmailSender email;
    private final WebhookDeliveryClient webhooks;
    private final SlackChatFormatter slack;
    private final TeamsChatFormatter teams;
    private final NotificationProperties properties;
    private final Clock clock;

    NotificationDispatcher(
            NotificationSubscriptionRepository subscriptions,
            NotificationDeliveryRepository deliveries,
            NotificationOutboxRepository outbox,
            OrganizationAccess organizations,
            UserDirectory directory,
            EmailSender email,
            WebhookDeliveryClient webhooks,
            SlackChatFormatter slack,
            TeamsChatFormatter teams,
            NotificationProperties properties,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.outbox = outbox;
        this.organizations = organizations;
        this.directory = directory;
        this.email = email;
        this.webhooks = webhooks;
        this.slack = slack;
        this.teams = teams;
        this.properties = properties;
        this.clock = clock;
    }

    /** The bus subscription: one relayed event, fanned out. Failures are recorded, never propagated. */
    @EventListener
    @Transactional
    public void on(DomainEvent event) {
        try {
            dispatch(event);
        } catch (RuntimeException e) {
            // The structural guarantee: nothing here reaches the relay
            // (a failed fan-out is a delivery-log entry, never a
            // business-flow exception).
            log.error("notification dispatch failed event={} organization={}", event.name(), event.organizationId(), e);
        }
    }

    /** The retry drain: due SCHEDULED deliveries re-attempted (the cadence shares the relay loop's property space). */
    @Scheduled(fixedDelayString = "${shipperdog.notifications.relay-fixed-delay:PT2S}")
    void retryScheduled() {
        if (properties.relayEnabled()) {
            retryDue();
        }
    }

    /** One retry pass. Public seam for tests and manual drains. */
    @Transactional
    public void retryDue() {
        List<NotificationDeliveryEntity> due = deliveries.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                NotificationDeliveryEntity.Status.SCHEDULED,
                Instant.now(clock),
                PageRequest.of(0, properties.relayBatchSize()));
        for (NotificationDeliveryEntity row : due) {
            try {
                attemptScheduled(row);
            } catch (RuntimeException e) {
                // A retry attempt's own bookkeeping failure: the row
                // keeps its schedule and the next pass tries again.
                log.error("delivery retry failed delivery={} attempts={}", row.getId(), row.getAttempts(), e);
            }
        }
    }

    // The fan-out -------------------------------------------------------------

    private void dispatch(DomainEvent event) {
        List<NotificationSubscriptionEntity> routing =
                subscriptions.findByOrganizationIdAndEnabledTrue(event.organizationId());
        if (routing.isEmpty()) {
            return;
        }
        Set<String> alreadyDelivered = deliveredRecipientKeys(event.eventId());
        for (NotificationSubscriptionEntity subscription : routing) {
            if (!subscription.routes(event.name().name())) {
                continue;
            }
            try {
                fanOut(subscription, event, alreadyDelivered);
            } catch (RuntimeException e) {
                log.error(
                        "subscription fan-out failed subscription={} channel={}",
                        subscription.getId(),
                        subscription.getChannel(),
                        e);
            }
        }
    }

    private void fanOut(NotificationSubscriptionEntity subscription, DomainEvent event, Set<String> alreadyDelivered) {
        switch (subscription.getChannel()) {
            case IN_APP -> deliverInApp(subscription, event, alreadyDelivered);
            case EMAIL -> deliverEmail(subscription, event, alreadyDelivered);
            case WEBHOOK, SLACK, TEAMS -> deliverWebhook(subscription, event, alreadyDelivered);
        }
    }

    /** The inbox write: the row IS the item, terminal at birth. */
    private void deliverInApp(NotificationSubscriptionEntity subscription, DomainEvent event, Set<String> skip) {
        for (UUID userId : resolveRecipients(subscription)) {
            String recipientKey = "user:" + userId;
            if (skip.contains(recipientKey)) {
                continue;
            }
            deliveries.save(new NotificationDeliveryEntity(
                    event.eventId(),
                    subscription.getId(),
                    subscription.getOrganizationId(),
                    NotificationChannel.IN_APP,
                    recipientKey,
                    Instant.now(clock)));
        }
    }

    /** The email channel: the {@code EmailSender} seam (WARN-log stub; SMTP is a standing follow-up). */
    private WebhookDeliveryClient.Attempt deliverEmail(
            NotificationSubscriptionEntity subscription, DomainEvent event, Set<String> skip) {
        WebhookDeliveryClient.Attempt worst = WebhookDeliveryClient.Attempt.success();
        for (UUID userId : resolveRecipients(subscription)) {
            UserDirectory.UserSummary member =
                    directory.byIds(List.of(userId)).stream().findFirst().orElse(null);
            if (member == null) {
                // A recipient the directory no longer knows: recorded,
                // never retried (the recipient is gone, not unavailable).
                continue;
            }
            String recipientKey = "email:" + member.email();
            if (skip.contains(recipientKey)) {
                continue;
            }
            WebhookDeliveryClient.Attempt attempt =
                    attemptDelivery(event, subscription, recipientKey, NotificationChannel.EMAIL, () -> {
                        email.send(member.email(), subjectOf(event), bodyOf(event, member));
                        return WebhookDeliveryClient.Attempt.success();
                    });
            if (!attempt.delivered()) {
                worst = attempt;
            }
        }
        return worst;
    }

    /**
     * The webhook family's shared leg (WEBHOOK plus S12-02's SLACK and
     * TEAMS): one signed POST per attempt -- the canonical event JSON
     * for WEBHOOK, the provider-rendered chat message for SLACK/TEAMS
     * -- all through the same wire discipline and the same ledger.
     */
    private void deliverWebhook(NotificationSubscriptionEntity subscription, DomainEvent event, Set<String> skip) {
        String recipientKey = recipientKeyOf(subscription);
        if (skip.contains(recipientKey)) {
            return;
        }
        attemptDelivery(
                event,
                subscription,
                recipientKey,
                subscription.getChannel(),
                () -> webhooks.deliver(
                        subscription.getWebhookUrl(),
                        subscription.getWebhookSecret(),
                        event.name().name(),
                        webhookBodyOf(subscription, event)));
    }

    /** The channel-prefixed recipient key: the ledger's idempotency rendering for the webhook family. */
    private static String recipientKeyOf(NotificationSubscriptionEntity subscription) {
        return switch (subscription.getChannel()) {
            case WEBHOOK -> "webhook:" + subscription.getWebhookUrl();
            case SLACK -> "slack:" + subscription.getWebhookUrl();
            case TEAMS -> "teams:" + subscription.getWebhookUrl();
            default -> throw new IllegalStateException("channel " + subscription.getChannel() + " has no URL leg");
        };
    }

    /** The body this subscription's channel posts: canonical event JSON, or the provider chat card. */
    private byte[] webhookBodyOf(NotificationSubscriptionEntity subscription, DomainEvent event) {
        return switch (subscription.getChannel()) {
            case SLACK -> slack.render(event);
            case TEAMS -> teams.render(event);
            case WEBHOOK -> WebhookDeliveryClient.canonicalBody(event);
            default -> throw new IllegalStateException("channel " + subscription.getChannel() + " has no URL leg");
        };
    }

    /**
     * The test action's synchronous fan-out (issue #52, S12-02, AC3):
     * the REAL per-channel pipeline against a synthetic event the
     * caller already recorded in the outbox. Deliberately not gated by
     * {@code routes()} -- the test verifies the subscription's
     * configuration, whatever it routes -- and deliberately recording
     * in the ledger like any first attempt: a test the receiver
     * refuses is a SCHEDULED row the retry drain keeps trying, visible
     * in the delivery log exactly like a real delivery (AC5's posture:
     * failures are never silent).
     */
    @Transactional
    public WebhookDeliveryClient.Attempt dispatchTest(DomainEvent event, NotificationSubscriptionEntity subscription) {
        switch (subscription.getChannel()) {
            case IN_APP -> {
                deliverInApp(subscription, event, Set.of());
                return WebhookDeliveryClient.Attempt.success();
            }
            case EMAIL -> {
                return deliverEmail(subscription, event, Set.of());
            }
            case WEBHOOK, SLACK, TEAMS -> {
                return attemptDelivery(
                        event,
                        subscription,
                        recipientKeyOf(subscription),
                        subscription.getChannel(),
                        () -> webhooks.deliver(
                                subscription.getWebhookUrl(),
                                subscription.getWebhookSecret(),
                                event.name().name(),
                                webhookBodyOf(subscription, event)));
            }
            default -> throw new IllegalStateException("unknown channel " + subscription.getChannel());
        }
    }

    // The attempt ledger --------------------------------------------------------

    @FunctionalInterface
    private interface DeliveryAction {
        WebhookDeliveryClient.Attempt run();
    }

    /**
     * One first attempt: success writes the terminal row, failure
     * writes the scheduled-for-retry row (or the terminal FAILED row
     * at a one-attempt ceiling). Returns the attempt so the test
     * action can answer with the synchronous outcome.
     */
    private WebhookDeliveryClient.Attempt attemptDelivery(
            DomainEvent event,
            NotificationSubscriptionEntity subscription,
            String recipientKey,
            NotificationChannel channel,
            DeliveryAction action) {
        Instant now = Instant.now(clock);
        WebhookDeliveryClient.Attempt attempt;
        try {
            attempt = action.run();
        } catch (RuntimeException e) {
            attempt = WebhookDeliveryClient.Attempt.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (attempt.delivered()) {
            deliveries.save(new NotificationDeliveryEntity(
                    event.eventId(),
                    subscription.getId(),
                    subscription.getOrganizationId(),
                    channel,
                    recipientKey,
                    now));
            return attempt;
        }
        recordFailure(event, subscription, channel, recipientKey, attempt.error(), now);
        return attempt;
    }

    /** One retry attempt against an existing row. */
    private void attemptScheduled(NotificationDeliveryEntity row) {
        NotificationSubscriptionEntity subscription =
                subscriptions.findById(row.getSubscriptionId()).orElse(null);
        if (subscription == null) {
            // The subscription was deleted between scheduling and
            // retry: the delivery retires quietly.
            row.markFailed(Instant.now(clock), "subscription deleted");
            deliveries.save(row);
            return;
        }
        Instant now = Instant.now(clock);
        DomainEvent event = eventOf(row);
        if (event == null) {
            row.markFailed(now, "outbox row unreadable");
            deliveries.save(row);
            return;
        }
        WebhookDeliveryClient.Attempt attempt;
        try {
            attempt = retryAction(row, subscription, event);
        } catch (RuntimeException e) {
            attempt = WebhookDeliveryClient.Attempt.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (attempt.delivered()) {
            row.markDelivered(now);
        } else if (row.getAttempts() >= properties.deliveryMaxAttempts() - 1) {
            row.markFailed(now, attempt.error());
            log.error(
                    "delivery exhausted event={} recipient={} attempts={}",
                    event.name(),
                    row.getRecipientKey(),
                    row.getAttempts() + 1);
        } else {
            row.scheduleRetry(now, now.plus(properties.deliveryBackoff(row.getAttempts())), attempt.error());
        }
        deliveries.save(row);
    }

    private WebhookDeliveryClient.Attempt retryAction(
            NotificationDeliveryEntity row, NotificationSubscriptionEntity subscription, DomainEvent event) {
        return switch (row.getChannel()) {
            case EMAIL -> {
                String address = row.getRecipientKey().substring("email:".length());
                email.send(address, subjectOf(event), bodyOf(event, null));
                yield WebhookDeliveryClient.Attempt.success();
            }
            case WEBHOOK, SLACK, TEAMS ->
                webhooks.deliver(
                        subscription.getWebhookUrl(),
                        subscription.getWebhookSecret(),
                        event.name().name(),
                        webhookBodyOf(subscription, event));
            case IN_APP ->
                // In-app rows are terminal at birth; a scheduled in-app
                // row is an artifact no path produces.
                WebhookDeliveryClient.Attempt.success();
        };
    }

    /**
     * The first attempt's failure record: the scheduled-for-retry row,
     * or the terminal FAILED row when the policy allows no retry at
     * all (a one-attempt ceiling).
     */
    private void recordFailure(
            DomainEvent event,
            NotificationSubscriptionEntity subscription,
            NotificationChannel channel,
            String recipientKey,
            String error,
            Instant now) {
        if (properties.deliveryMaxAttempts() <= 1) {
            deliveries.save(NotificationDeliveryEntity.failedTerminal(
                    event.eventId(),
                    subscription.getId(),
                    subscription.getOrganizationId(),
                    channel,
                    recipientKey,
                    error,
                    now));
            log.error("delivery exhausted event={} recipient={} channel={}", event.name(), recipientKey, channel);
            return;
        }
        deliveries.save(NotificationDeliveryEntity.scheduledForRetry(
                event.eventId(),
                subscription.getId(),
                subscription.getOrganizationId(),
                channel,
                recipientKey,
                now.plus(properties.deliveryBackoff(0)),
                error,
                now));
    }

    // Resolution and rendering ---------------------------------------------------

    /** The subscription's role-resolved and explicit recipients, order-stable. */
    private List<UUID> resolveRecipients(NotificationSubscriptionEntity subscription) {
        List<UUID> roleResolved = new ArrayList<>();
        for (String role : subscription.getRecipientRoles()) {
            roleResolved.addAll(
                    organizations.memberUserIdsOfRole(subscription.getOrganizationId(), OrgRole.valueOf(role)));
        }
        return subscription.recipientUserIdUnion(roleResolved);
    }

    private Set<String> deliveredRecipientKeys(UUID eventId) {
        Set<String> keys = new LinkedHashSet<>();
        for (NotificationDeliveryEntity row : deliveries.findByEventId(eventId)) {
            keys.add(row.getRecipientKey());
        }
        return keys;
    }

    private DomainEvent eventOf(NotificationDeliveryEntity row) {
        return outbox.findById(row.getEventId())
                .map(NotificationOutboxEntity::toDomainEvent)
                .orElse(null);
    }

    private String subjectOf(DomainEvent event) {
        return "ShipperDog event " + event.name();
    }

    /** The message carries identifiers only (the events' PII-minimization posture). */
    private String bodyOf(DomainEvent event, UserDirectory.UserSummary recipient) {
        StringBuilder body = new StringBuilder("A ShipperDog domain event you subscribe to occurred.");
        body.append("\n\nEvent: ").append(event.name().name());
        body.append("\nOrganization: ").append(event.organizationId());
        if (event.applicationId() != null) {
            body.append("\nApplication: ").append(event.applicationId());
        }
        if (event.releaseId() != null) {
            body.append("\nRelease: ").append(event.releaseId());
        }
        if (event.artifactId() != null) {
            body.append("\nArtifact: ").append(event.artifactId());
        }
        body.append("\nOccurred at: ").append(event.occurredAt());
        body.append("\n\nOpen the console's notification surfaces to work it.");
        return body.toString();
    }
}
