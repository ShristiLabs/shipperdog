package com.shipperdog.api.version.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code version_status_history} table (V14): one row per real
 * lifecycle transition — creation writes the DRAFT row, archive and
 * revoke-distribution append theirs — forming the chronological status
 * timeline the history query returns for audit (issue #19 AC5, FR-024's
 * Compliance Reviewer persona). The revoke action's mandatory reason is
 * carried on its row so the timeline is self-contained; the audit log
 * keeps the full who/where record this table deliberately does not
 * duplicate.
 *
 * <p>The table is a pure child of the seam-served version (ADR-0002
 * seventh amendment doctrine, formalized by the eighth): it carries no
 * owning-dimension snapshot and is served by no seam repository — every
 * path resolves the version through {@code VersionScopedRepository}
 * inside the request's bound scope first, then reads only that
 * version's history rows. Isolation stays enforced at the parent.
 */
@Entity
@Table(name = "version_status_history")
public class VersionStatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID versionId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private VersionEntity.Status status;

    private String reason;

    private Instant occurredAt;

    protected VersionStatusHistoryEntity() {}

    /**
     * One timeline entry (S4-03): the status the version took, when,
     * and — for the revoke transition only — the actor's mandatory
     * reason (FR-024).
     */
    public VersionStatusHistoryEntity(UUID versionId, VersionEntity.Status status, String reason, Instant occurredAt) {
        this.versionId = versionId;
        this.status = status;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public VersionEntity.Status getStatus() {
        return status;
    }

    /** The revoke reason; null on every other transition. */
    public String getReason() {
        return reason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
