package com.shipperdog.api.feedback.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One uploaded attachment of a feedback report (issue #46, S11-01,
 * V35): the metadata row — the bytes live in object storage under the
 * tenant-scoped prefix the store derives ({@code {organizationId}/
 * {submitterTenantId}/feedback/{feedbackId}/{attachmentId}}, AC3's
 * tenant-scoped object prefix; never a client path).
 *
 * <p>A plain child of the feedback row (the V25 doctrine): reads
 * resolve the parent first. The category is the EXTENSION-resolved
 * kind (SCREENSHOT/VIDEO/LOG — PRD 8.13's attachment classes), the
 * checksum the SHA-256 streamed during the write, and the file name
 * the tester's own, unique within one report (V35's constraint; a
 * duplicate name in one submission is refused before any write).
 * Rows are immutable once written — attachments cannot be replaced.
 */
@Entity
@Table(name = "feedback_attachments")
public class FeedbackAttachmentEntity {

    /** The extension-resolved attachment kinds (PRD 8.13's three classes). */
    public enum Category {
        SCREENSHOT,
        VIDEO,
        LOG
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID feedbackId;

    private String fileName;

    @Enumerated(EnumType.STRING)
    private Category category;

    private String contentType;

    private long sizeBytes;

    /** The lowercase hex SHA-256 of the stored bytes (char(64) in V35, the artifact checksum's mapping). */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "checksum", length = 64, nullable = false)
    private String checksum;

    private String storageKey;

    private Instant createdAt;

    private Instant updatedAt;

    protected FeedbackAttachmentEntity() {}

    /** A stored attachment: the id feeds the object key, so the row IS the allocation. */
    public FeedbackAttachmentEntity(
            UUID feedbackId,
            String fileName,
            Category category,
            String contentType,
            long sizeBytes,
            String checksum,
            String storageKey) {
        this.feedbackId = feedbackId;
        this.fileName = fileName;
        this.category = category;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.storageKey = storageKey;
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

    public UUID getFeedbackId() {
        return feedbackId;
    }

    public String getFileName() {
        return fileName;
    }

    public Category getCategory() {
        return category;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
