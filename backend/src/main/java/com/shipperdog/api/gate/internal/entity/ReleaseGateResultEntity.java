package com.shipperdog.api.gate.internal.entity;

import com.shipperdog.api.gate.internal.entity.ReleaseGateEntity.Source;
import com.shipperdog.api.gate.internal.entity.ReleaseGateEntity.Status;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code release_gate_results} table (V31, issue #42,
 * S10-03): one row per RECORDED result — an external submission or a
 * metric evaluation — the append-only history behind the current
 * state's "latest wins" (PRD P5: a re-run gate never rewrites what was
 * reported before; the audit trail answers what a release's quality
 * state was at any moment). A pure child of the seam-served release
 * (the V25 doctrine), never mutated after insert.
 */
@Entity
@Table(name = "release_gate_results")
public class ReleaseGateResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID releaseId;

    private String gateType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status outcome;

    private Double observedValue;

    private String evidenceUrl;

    private UUID submittedBy;

    @Enumerated(EnumType.STRING)
    private Source source;

    private Instant recordedAt;

    protected ReleaseGateResultEntity() {}

    /** One result as recorded: the outcome, the evidence, the value of a numeric gate, and who/what reported it. */
    public ReleaseGateResultEntity(
            UUID releaseId,
            String gateType,
            Status outcome,
            String evidenceUrl,
            Double observedValue,
            UUID submittedBy,
            Source source,
            Instant recordedAt) {
        this.releaseId = releaseId;
        this.gateType = gateType;
        this.outcome = outcome;
        this.evidenceUrl = evidenceUrl;
        this.observedValue = observedValue;
        this.submittedBy = submittedBy;
        this.source = source;
        this.recordedAt = recordedAt;
    }

    @PrePersist
    void onCreate() {
        if (recordedAt == null) {
            recordedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getReleaseId() {
        return releaseId;
    }

    public String getGateType() {
        return gateType;
    }

    public Status getOutcome() {
        return outcome;
    }

    public Double getObservedValue() {
        return observedValue;
    }

    public String getEvidenceUrl() {
        return evidenceUrl;
    }

    public UUID getSubmittedBy() {
        return submittedBy;
    }

    public Source getSource() {
        return source;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
