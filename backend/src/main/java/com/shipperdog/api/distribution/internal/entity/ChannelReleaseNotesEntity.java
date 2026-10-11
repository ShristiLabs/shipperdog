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
 * Maps the {@code channel_release_notes} table (V46, issue #64, S13-04,
 * AC2): the per-locale release notes an operator authors BEFORE
 * approval for a channel whose submission carries notes but not full
 * listing metadata — today the Play testing tracks. The row is the
 * AUTHORING surface only: the release manifest's store-submission
 * block freezes these rows at the APPROVED crossing (PRD 16's
 * immutable copy of what was sent), and the dispatch sends THE FROZEN
 * BLOCK, never these rows — the V45 metadata precedent.
 */
@Entity
@Table(name = "channel_release_notes")
public class ChannelReleaseNotesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private UUID applicationId;

    /** The channel reference name (the registry's vocabulary: {@code play}). */
    private String channel;

    /** The store locale the row describes (BCP-47, e.g. en-US). */
    private String locale;

    /** The locale's release-note text (Google's cap is 500 characters). */
    private String notes;

    private Instant createdAt;

    private Instant updatedAt;

    protected ChannelReleaseNotesEntity() {}

    /** One row born at authoring time: the application's channel locale. */
    public ChannelReleaseNotesEntity(
            UUID organizationId, UUID applicationId, String channel, String locale, String notes) {
        this.organizationId = organizationId;
        this.applicationId = applicationId;
        this.channel = channel;
        this.locale = locale;
        this.notes = notes;
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

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getChannel() {
        return channel;
    }

    public String getLocale() {
        return locale;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** The authoring rotation: a second write to the same locale replaces the row whole, never merges. */
    public void rotate(String notes) {
        this.notes = notes;
    }
}
