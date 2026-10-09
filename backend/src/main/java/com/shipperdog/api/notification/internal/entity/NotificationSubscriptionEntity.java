package com.shipperdog.api.notification.internal.entity;

import com.shipperdog.api.notification.NotificationChannel;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code notification_subscriptions} table (V39): one
 * org-scoped routing rule of FR-023 -- a set of event names, a
 * channel, and the channel's recipients (organization roles and/or
 * explicit user ids for IN_APP and EMAIL; a URL and its HMAC signing
 * secret for WEBHOOK). Organization-grain reference data (the rings
 * pattern): the organization dimension only, a plain repository with
 * organization-scoped queries.
 *
 * <p>The webhook secret is write-only by construction: it exists on
 * the row (delivery must sign with it) but no served view returns it
 * and no log line carries it -- the same posture the environment
 * module gives credential material.
 */
@Entity
@Table(name = "notification_subscriptions")
public class NotificationSubscriptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    /** The subscribed event names (the frozen {@code DomainEventName} vocabulary), stored as a jsonb array. */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> eventTypes = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private NotificationChannel channel;

    /** The subscribed organization roles ({@code OrgRole} names), stored as a jsonb array. */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> recipientRoles = new ArrayList<>();

    /** The subscribed user ids, stored as a jsonb array. */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<UUID> recipientUserIds = new ArrayList<>();

    private String webhookUrl;

    private String webhookSecret;

    private boolean enabled = true;

    /**
     * The optimistic-lock stamp (the review's race finding): a stale
     * concurrent edit refuses with the public 409 instead of silently
     * resurrecting a rotated secret or replaced URL.
     */
    @Version
    private long version;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected NotificationSubscriptionEntity() {}

    /**
     * One subscription (validated by the service before any write):
     * the owning organization, the event names, the channel, the
     * channel-appropriate recipients, and the author.
     */
    public NotificationSubscriptionEntity(
            UUID organizationId,
            List<String> eventTypes,
            NotificationChannel channel,
            List<String> recipientRoles,
            List<UUID> recipientUserIds,
            String webhookUrl,
            String webhookSecret,
            UUID createdBy,
            Instant now) {
        this.organizationId = organizationId;
        this.eventTypes = new ArrayList<>(eventTypes);
        this.channel = channel;
        this.recipientRoles = new ArrayList<>(recipientRoles);
        this.recipientUserIds = new ArrayList<>(recipientUserIds);
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
        this.enabled = true;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Replaces the mutable routing facts (the channel never moves; recipients re-resolve per edit). */
    public void reroute(
            List<String> eventTypes,
            List<String> recipientRoles,
            List<UUID> recipientUserIds,
            String webhookUrl,
            String webhookSecret,
            boolean enabled,
            Instant now) {
        this.eventTypes = new ArrayList<>(eventTypes);
        this.recipientRoles = new ArrayList<>(recipientRoles);
        this.recipientUserIds = new ArrayList<>(recipientUserIds);
        if (webhookUrl != null) {
            this.webhookUrl = webhookUrl;
        }
        if (webhookSecret != null) {
            this.webhookSecret = webhookSecret;
        }
        this.enabled = enabled;
        this.updatedAt = now;
    }

    /** Whether this subscription routes the named event. */
    public boolean routes(String eventName) {
        return enabled && eventTypes.contains(eventName);
    }

    /** The union of role-resolved and explicit recipients, order-stable, duplicates collapsed. */
    public List<UUID> recipientUserIdUnion(List<UUID> roleResolvedUserIds) {
        LinkedHashSet<UUID> union = new LinkedHashSet<>(roleResolvedUserIds);
        union.addAll(recipientUserIds);
        return List.copyOf(union);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public List<String> getEventTypes() {
        return List.copyOf(eventTypes);
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public List<String> getRecipientRoles() {
        return List.copyOf(recipientRoles);
    }

    public List<UUID> getRecipientUserIds() {
        return List.copyOf(recipientUserIds);
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    /** The HMAC signing key. Delivery-side only; never render in a view, never log. */
    public String getWebhookSecret() {
        return webhookSecret;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
