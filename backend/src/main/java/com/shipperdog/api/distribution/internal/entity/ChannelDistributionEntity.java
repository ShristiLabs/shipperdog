package com.shipperdog.api.distribution.internal.entity;

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
 * Maps the {@code channel_distributions} table (V43, issue #61, AC4,
 * PRD 8.7/13): one adapter-driven distribution record per release x
 * artifact x channel. The record's lifecycle is V1's own {@code
 * distribution_status} vocabulary (PENDING, DISTRIBUTING, DISTRIBUTED,
 * FAILED) and its status, external id, and failure code are the
 * ADAPTER'S REPORT, verbatim -- the framework writes only the attempt
 * bookkeeping around them. External ids are the store-reconciliation
 * key (#54's store_status_events join the #62-#65 connectors perform).
 *
 * <p>The V1 {@code distribution} table stays reserved-unused (the
 * environment_config precedent): its enum-typed channel of the
 * PORTAL/EMAIL sketch predates the reference-name vocabulary V27/V32
 * established, and this table is that vocabulary's record, not a
 * resurrection of the sketch.
 */
@Entity
@Table(name = "channel_distributions")
public class ChannelDistributionEntity {

    /** The record's lifecycle, reused from V1's {@code distribution_status} vocabulary. */
    public enum Status {
        PENDING,
        DISTRIBUTING,
        DISTRIBUTED,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID releaseId;

    private UUID artifactId;

    private UUID organizationId;

    /** The channel reference name the dispatch ran through (the registry's vocabulary). */
    private String channel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    /** The provider-side identifier for store reconciliation; null when the channel has none (Direct). */
    private String externalId;

    /** The failure taxonomy code when status is FAILED (the ADAPTER_* family); null otherwise. */
    private String failureCode;

    /** How many sandboxed attempts the record has consumed (retries increment it). */
    private int attempts;

    private Instant lastAttemptAt;

    private Instant distributedAt;

    private Instant createdAt;

    private Instant updatedAt;

    protected ChannelDistributionEntity() {}

    /** One record born PENDING at dispatch time: the release's artifact, through the channel, under the organization. */
    public ChannelDistributionEntity(UUID releaseId, UUID artifactId, UUID organizationId, String channel) {
        this.releaseId = releaseId;
        this.artifactId = artifactId;
        this.organizationId = organizationId;
        this.channel = channel;
        this.status = Status.PENDING;
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

    public UUID getReleaseId() {
        return releaseId;
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getChannel() {
        return channel;
    }

    public Status getStatus() {
        return status;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getDistributedAt() {
        return distributedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * One adapter report lands: the outcome's facts overwrite, the
     * sandbox's consumed calls accumulate onto the attempt clock, and
     * DISTRIBUTED stamps its moment.
     */
    public void report(AdapterReport report, int sandboxCalls, Instant at) {
        this.status = report.status();
        this.externalId = report.externalId() == null ? this.externalId : report.externalId();
        this.failureCode = report.failureCode();
        this.attempts += sandboxCalls;
        this.lastAttemptAt = at;
        if (this.status == Status.DISTRIBUTED && this.distributedAt == null) {
            this.distributedAt = at;
        }
    }

    /** The adapter report as the record stores it (verbatim; the framework adds only bookkeeping). */
    public record AdapterReport(Status status, String externalId, String failureCode) {

        /** The failed report of an exhausted sandbox (the taxonomy code the record keeps). */
        public static AdapterReport failed(String failureCode) {
            return new AdapterReport(Status.FAILED, null, failureCode);
        }
    }
}
