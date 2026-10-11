package com.shipperdog.api.distribution.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code channel_submission_metadata} table (V45, issue #63,
 * S13-03, AC1): one authored store-listing locale per (application,
 * channel) -- the description, keywords, screenshot references, and
 * release notes a submission sends. Authored through the console
 * BEFORE approval; the release manifest's store-submission block
 * freezes these rows at the APPROVED crossing (PRD 16's immutable copy
 * of what was sent), and the dispatch at the ACTIVE crossing sends
 * THE FROZEN BLOCK -- the rows are the authoring surface, the manifest
 * is the submission's source of truth, so the two can never drift.
 *
 * <p>Channel-generic by design (the V44 group-link precedent): Google
 * Play's listing metadata (#64) authors through the same grain without
 * a second table.
 */
@Entity
@Table(name = "channel_submission_metadata")
public class ChannelSubmissionMetadataEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    private UUID applicationId;

    /** The channel reference name (the registry's vocabulary: {@code appstore}). */
    private String channel;

    /** The store locale the row describes (Apple's BCP-47 vocabulary, e.g. {@code en-US}). */
    private String locale;

    private String description;

    /** The store's search keywords (Apple caps at 100 characters; the mock ASC enforces the provider contract). */
    private String keywords;

    /**
     * The screenshot references the manifest pins (asset ids or URIs);
     * ASC-side assets are uploaded through ASC's own surfaces -- the
     * connector submits text metadata only (the story's minimal field
     * set, documented in docs/distribution.md).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> screenshotRefs = new ArrayList<>();

    /** The locale's what's-new (release notes) the submission sends. */
    private String releaseNotes;

    private Instant createdAt;

    private Instant updatedAt;

    protected ChannelSubmissionMetadataEntity() {}

    /** One authored locale born at the metadata write; a second write to the same locale rotates this row. */
    public ChannelSubmissionMetadataEntity(
            UUID organizationId,
            UUID applicationId,
            String channel,
            String locale,
            String description,
            String keywords,
            List<String> screenshotRefs,
            String releaseNotes) {
        this.organizationId = organizationId;
        this.applicationId = applicationId;
        this.channel = channel;
        this.locale = locale;
        this.description = description;
        this.keywords = keywords;
        this.screenshotRefs = new ArrayList<>(screenshotRefs);
        this.releaseNotes = releaseNotes;
    }

    /** The authoring rotation: the same locale's fields replaced whole (never a partial merge). */
    public void rotate(String description, String keywords, List<String> screenshotRefs, String releaseNotes) {
        this.description = description;
        this.keywords = keywords;
        this.screenshotRefs = new ArrayList<>(screenshotRefs);
        this.releaseNotes = releaseNotes;
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

    public String getDescription() {
        return description;
    }

    public String getKeywords() {
        return keywords;
    }

    public List<String> getScreenshotRefs() {
        return List.copyOf(screenshotRefs);
    }

    public String getReleaseNotes() {
        return releaseNotes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
