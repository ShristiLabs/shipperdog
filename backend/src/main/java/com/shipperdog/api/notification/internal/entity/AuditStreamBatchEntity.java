package com.shipperdog.api.notification.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps the {@code audit_stream_batches} table (V53): one row per
 * written bucket batch of issue #71 (S14-05, AC2) -- the batch
 * history whose cumulative {@code event_count} sum is the
 * completeness answer against the audit table's count for the same
 * window (verifiable completeness). Immutable history: rows are
 * written by the batcher's committed pass and never updated.
 */
@Entity
@Table(name = "audit_stream_batches")
public class AuditStreamBatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID exportId;

    private UUID organizationId;

    private long seq;

    private Instant watermarkFrom;

    private Instant watermarkTo;

    private int eventCount;

    private String batchSha256;

    private String objectUri;

    private String manifestUri;

    private Instant createdAt;

    protected AuditStreamBatchEntity() {}

    /**
     * One committed batch: the export and its organization, the
     * batch's sequence, its watermark window, its row count, its
     * content digest, and both object URIs (the batch and its
     * signed manifest).
     */
    public AuditStreamBatchEntity(
            UUID exportId,
            UUID organizationId,
            long seq,
            Instant watermarkFrom,
            Instant watermarkTo,
            int eventCount,
            String batchSha256,
            String objectUri,
            String manifestUri,
            Instant now) {
        this.exportId = exportId;
        this.organizationId = organizationId;
        this.seq = seq;
        this.watermarkFrom = watermarkFrom;
        this.watermarkTo = watermarkTo;
        this.eventCount = eventCount;
        this.batchSha256 = batchSha256;
        this.objectUri = objectUri;
        this.manifestUri = manifestUri;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getExportId() {
        return exportId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public long getSeq() {
        return seq;
    }

    public Instant getWatermarkFrom() {
        return watermarkFrom;
    }

    public Instant getWatermarkTo() {
        return watermarkTo;
    }

    public int getEventCount() {
        return eventCount;
    }

    public String getBatchSha256() {
        return batchSha256;
    }

    public String getObjectUri() {
        return objectUri;
    }

    public String getManifestUri() {
        return manifestUri;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
