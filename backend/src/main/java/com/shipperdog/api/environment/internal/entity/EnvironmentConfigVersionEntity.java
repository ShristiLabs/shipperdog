package com.shipperdog.api.environment.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code environment_config_versions} table (V27, issue #58):
 * one append-only row per configuration write, carrying the FULL
 * resolved key-value map as a jsonb document — the snapshot IS the
 * value, so retrieval by version answers from one row and never
 * reconstructs history from fragments. The version is monotonic per
 * environment from 1; a never-configured environment answers version 0
 * with the empty map (the service's honest empty, never an error).
 *
 * <p>Immutable by construction: composite key, no setters, no update
 * or delete path in the repository, no {@code updated_at} (the
 * {@code audit_events}/{@code version_tags} append-only exception to
 * the audit-columns convention). Configuration HISTORY is this ledger
 * plus the audit trail; the environment row never stores the current
 * map — the current snapshot is the highest ledger row, always.
 *
 * <p>A plain child of the organization-grain environment (the V25
 * release_status_history doctrine): no owning-dimension snapshot, no
 * seam repository — every caller resolves the environment first, so
 * the organization check is enforced at the parent and never
 * re-derived here.
 */
@Entity
@Table(name = "environment_config_versions")
@IdClass(EnvironmentConfigVersionId.class)
public class EnvironmentConfigVersionEntity {

    @Id
    private UUID environmentId;

    @Id
    private long version;

    /**
     * The full key-value map at this version as a canonical JSON object
     * (keys sorted at write, so the stored bytes are deterministic and
     * snapshot equality is byte equality).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    private String entries;

    private UUID createdBy;

    private Instant createdAt;

    protected EnvironmentConfigVersionEntity() {}

    /** One snapshot of a config write: the environment, the monotonic version, the full map, the writer. */
    public EnvironmentConfigVersionEntity(UUID environmentId, long version, String canonicalEntries, UUID createdBy) {
        this.environmentId = environmentId;
        this.version = version;
        this.entries = canonicalEntries;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public long getVersion() {
        return version;
    }

    public String getEntries() {
        return entries;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
