package com.shipperdog.api.distribution.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code channel_group_links} table (V44, issue #62, S13-02,
 * AC2): the tester-group to provider-group mapping — one row per
 * (channel, tester group), carrying the provider-side group id the
 * link's sync resolved (an ASC beta group id for {@code testflight}).
 * The row is a MAPPING, never a membership mirror: provider-side
 * membership is reconciled on sync and reported as drift, never stored
 * here (AC5).
 */
@Entity
@Table(name = "channel_group_links")
public class ChannelGroupLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private UUID tenantId;

    private UUID applicationId;

    private UUID groupId;

    /** The channel reference name (the registry's vocabulary: {@code testflight}). */
    private String channel;

    /** The provider-side group id (an ASC beta group id); stable for the link's life. */
    private String externalGroupId;

    /** The provider-side group name at link time (the operator's recognition key). */
    private String externalGroupName;

    private Instant lastSyncedAt;

    private Instant createdAt;

    private Instant updatedAt;

    protected ChannelGroupLinkEntity() {}

    /** One link born at mapping time; the sync stamps {@link #markSynced} later. */
    public ChannelGroupLinkEntity(
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            UUID groupId,
            String channel,
            String externalGroupId,
            String externalGroupName) {
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.applicationId = applicationId;
        this.groupId = groupId;
        this.channel = channel;
        this.externalGroupId = externalGroupId;
        this.externalGroupName = externalGroupName;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public String getChannel() {
        return channel;
    }

    public String getExternalGroupId() {
        return externalGroupId;
    }

    public String getExternalGroupName() {
        return externalGroupName;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** The sync's stamp: the link's membership was pushed this moment. */
    public void markSynced(Instant at) {
        this.lastSyncedAt = at;
    }

    /** Re-points the link at a different provider group (re-link under a standing row: the group id is stable, the provider side moves). */
    public void rotate(String externalGroupId, String externalGroupName) {
        this.externalGroupId = externalGroupId;
        this.externalGroupName = externalGroupName;
    }
}
