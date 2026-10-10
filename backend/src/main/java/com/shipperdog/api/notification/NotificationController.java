package com.shipperdog.api.notification;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.notification.internal.entity.NotificationDeliveryEntity;
import com.shipperdog.api.notification.internal.service.NotificationSubscriptionService;
import com.shipperdog.api.notification.internal.service.NotificationSubscriptionService.CreateCommand;
import com.shipperdog.api.notification.internal.service.NotificationSubscriptionService.SubscriptionView;
import com.shipperdog.api.notification.internal.service.NotificationSubscriptionService.UpdateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The notification API (issue #51, S12-01, FR-023): the org-scoped
 * subscription CRUD ({@code org:notifications:manage/read} -- the
 * routing table decides who hears about what, a governance surface
 * the same way rings and approval chains are), the delivery log
 * (every attempt visible, failures first-class), and the self-scoped
 * inbox ({@code /api/v1/notification-inbox} -- the caller's own
 * in-app rows, the {@code /auth/me} shape).
 *
 * <p>The channels and the frozen event vocabulary the bodies carry
 * are documented in docs/notifications.md; the webhook secret is
 * write-only (a create or a PATCH supplies it; no view returns it).
 */
@RestController
public class NotificationController {

    private final NotificationSubscriptionService subscriptions;

    NotificationController(NotificationSubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    // -------------------------------------------------------------------------
    // Subscriptions (AC4): list, detail, create, edit, delete
    // -------------------------------------------------------------------------

    /** The organization's subscriptions, newest first. */
    @GetMapping("/api/v1/organizations/{organizationId}/notification-subscriptions")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:notifications:read')")
    PageResponse<SubscriptionView> list(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        List<SubscriptionView> page =
                subscriptions.list(requireUserId(principal), organizationId, (int) query.offset(), query.limit());
        return PageResponse.of(page, page.size() == query.limit(), query.offset() + query.limit());
    }

    /** One subscription; another organization's conceals exactly like an unknown id. */
    @GetMapping("/api/v1/organizations/{organizationId}/notification-subscriptions/{subscriptionId}")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:notifications:read')")
    SubscriptionView get(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID subscriptionId) {
        return subscriptions.get(requireUserId(principal), organizationId, subscriptionId);
    }

    /**
     * Creates a subscription (AC4): event names from the frozen
     * vocabulary, a channel, and the channel-appropriate recipients.
     * Audited (without the webhook secret). Honors {@code Idempotency-Key}.
     */
    @PostMapping("/api/v1/organizations/{organizationId}/notification-subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:notifications:manage')")
    SubscriptionView create(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @RequestBody CreateRequest request) {
        return subscriptions.create(
                requireUserId(principal),
                organizationId,
                new CreateCommand(
                        request.eventTypes(),
                        parseChannel(request.channel()),
                        request.recipientRoles(),
                        request.recipientUserIds(),
                        request.webhookUrl(),
                        request.webhookSecret()));
    }

    /**
     * Edits a subscription: absent fields keep their standing; the
     * channel never moves; a supplied {@code webhookSecret} rotates
     * it. Audited. Honors {@code Idempotency-Key}.
     */
    @PatchMapping("/api/v1/organizations/{organizationId}/notification-subscriptions/{subscriptionId}")
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:notifications:manage')")
    SubscriptionView update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID subscriptionId,
            @RequestBody UpdateRequest request) {
        return subscriptions.update(
                requireUserId(principal),
                organizationId,
                subscriptionId,
                new UpdateCommand(
                        request.eventTypes(),
                        request.recipientRoles(),
                        request.recipientUserIds(),
                        request.webhookUrl(),
                        request.webhookSecret(),
                        request.enabled()));
    }

    /** Deletes a subscription; its deliveries stay in the log (the ledger is history). Audited. */
    @DeleteMapping("/api/v1/organizations/{organizationId}/notification-subscriptions/{subscriptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:notifications:manage')")
    void delete(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID subscriptionId) {
        subscriptions.delete(requireUserId(principal), organizationId, subscriptionId);
    }

    /**
     * Sends the subscription's test message (S12-02, AC3): one
     * synthetic {@code NOTIFICATION_TEST_SENT} event through the real
     * channel pipeline -- the SLACK/TEAMS card a chat client would
     * render, the signed POST a receiver would verify, the ledger row
     * the delivery log would show -- and answers with the synchronous
     * outcome. Audited (without the URL or secret). Honors
     * {@code Idempotency-Key}.
     */
    @PostMapping("/api/v1/organizations/{organizationId}/notification-subscriptions/{subscriptionId}/test")
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:notifications:manage')")
    NotificationSubscriptionService.TestMessageView test(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID subscriptionId) {
        return subscriptions.test(requireUserId(principal), organizationId, subscriptionId);
    }

    // -------------------------------------------------------------------------
    // The delivery log (AC5) and the inbox
    // -------------------------------------------------------------------------

    /**
     * The delivery log (AC5): every attempt, newest first, optionally
     * narrowed to one subscription and one status
     * ({@code SCHEDULED}, {@code DELIVERED}, {@code FAILED}).
     */
    @GetMapping("/api/v1/organizations/{organizationId}/notification-deliveries")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:notifications:read')")
    PageResponse<NotificationSubscriptionService.DeliveryView> deliveries(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @RequestParam(required = false) UUID subscriptionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        NotificationDeliveryEntity.Status parsed = parseStatus(status);
        List<NotificationSubscriptionService.DeliveryView> page = subscriptions.deliveryLog(
                requireUserId(principal), organizationId, subscriptionId, parsed, (int) query.offset(), query.limit());
        return PageResponse.of(page, page.size() == query.limit(), query.offset() + query.limit());
    }

    /**
     * The caller's inbox: their own in-app deliveries, newest first.
     * Self-scoped the {@code /auth/me} way -- no resource id to
     * conceal at, the recipient filter IS the permission.
     */
    @GetMapping("/api/v1/notification-inbox")
    @PreAuthorize("isAuthenticated()")
    PageResponse<NotificationSubscriptionService.DeliveryView> inbox(
            @AuthenticationPrincipal Object principal,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        List<NotificationSubscriptionService.DeliveryView> page =
                subscriptions.inbox(requireUserId(principal), (int) query.offset(), query.limit());
        return PageResponse.of(page, page.size() == query.limit(), query.offset() + query.limit());
    }

    // Bodies and helpers -------------------------------------------------------

    private static NotificationChannel parseChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        try {
            return NotificationChannel.valueOf(channel.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new NotificationException(
                    NotificationException.Code.SUBSCRIPTION_INVALID,
                    "unknown channel",
                    java.util.Map.of("channel", "IN_APP, EMAIL, WEBHOOK, SLACK, or TEAMS"));
        }
    }

    private static NotificationDeliveryEntity.Status parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return NotificationDeliveryEntity.Status.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new NotificationException(
                    NotificationException.Code.INVALID_REQUEST,
                    "unknown delivery status",
                    java.util.Map.of("status", "SCHEDULED, DELIVERED, or FAILED"));
        }
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new NotificationException(NotificationException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The subscription creation body: the event names (frozen
     * vocabulary), the channel, and the channel-appropriate recipients
     * (roles and/or user ids for IN_APP and EMAIL; URL and signing
     * secret for the webhook family -- WEBHOOK, SLACK, TEAMS).
     */
    @Schema(
            description = "The notification subscription creation body: event types, channel, and the "
                    + "channel-appropriate recipients.")
    public record CreateRequest(
            List<String> eventTypes,
            String channel,
            List<String> recipientRoles,
            List<UUID> recipientUserIds,
            String webhookUrl,
            String webhookSecret) {}

    /** The subscription edit body: the mutable facts only; absent fields keep their standing. */
    @Schema(
            description = "The notification subscription edit body: event types, recipients, the webhook pair "
                    + "(supplying the secret rotates it), and the enabled switch; the channel never moves.")
    public record UpdateRequest(
            List<String> eventTypes,
            List<String> recipientRoles,
            List<UUID> recipientUserIds,
            String webhookUrl,
            String webhookSecret,
            Boolean enabled) {}
}
