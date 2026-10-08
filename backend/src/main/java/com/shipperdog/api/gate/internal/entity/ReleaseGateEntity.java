package com.shipperdog.api.gate.internal.entity;

import com.shipperdog.api.gate.internal.entity.ReleaseGateEntity.Status;
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
 * Maps the {@code release_gates} table (V1 sketch, EXPANDED by V31 —
 * issue #42, S10-03, FR-026): the CURRENT state of one gate of one
 * release, keyed by V1's own {@code (release_id, gate_type)} unique
 * constraint — the {@code release_approvals} precedent (V1 modeled
 * the table from PRD 13, no writer ever touched it, S10-03 expands
 * rather than duplicates). The append-only submission history lives
 * beside it in {@code release_gate_results}; this row always mirrors
 * the LATEST result ("latest wins"), plus the override record that
 * alone can make a blocking state stop blocking.
 *
 * <p>A pure child of the seam-served release (the V25 doctrine): no
 * owning-dimension snapshot, served by no seam repository — every
 * caller resolves the release through the release module's scoped port
 * ({@code ReleaseAccess.factsOf}) first, so isolation is enforced at
 * the parent and never re-derived here.
 */
@Entity
@Table(name = "release_gates")
public class ReleaseGateEntity {

    /**
     * The gate state vocabulary (the {@code gate_status} enum, V1,
     * extended by V31): PENDING from instantiation until a result
     * arrives (or for a required gate nothing has reported yet);
     * PASSED/FAILED as externally submitted; NO_DATA as resolved by a
     * metric evaluation that found no analytics data — the fail-closed
     * state, distinct from FAILED on the dashboard, blocking all the
     * same until the data arrives or the gate is overridden.
     */
    public enum Status {
        PENDING,
        PASSED,
        FAILED,
        NO_DATA
    }

    /** Where a recorded result came from: an external report, or the evaluation's own metric resolution. */
    public enum Source {
        SUBMITTED,
        METRIC
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID releaseId;

    private String gateType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private String evidenceUrl;

    private Instant evaluatedAt;

    private Boolean required;

    private Double observedValue;

    private UUID submittedBy;

    @Enumerated(EnumType.STRING)
    private Source source;

    private UUID overriddenBy;

    private String overriddenReason;

    private Instant overriddenAt;

    private Instant createdAt;

    private Instant updatedAt;

    protected ReleaseGateEntity() {}

    /**
     * One expected gate as instantiated at evaluation (S10-03): born
     * PENDING, the required flag frozen from the definition — the
     * dashboard's "awaiting result" row for a gate the ring demands
     * before anything has reported.
     */
    public ReleaseGateEntity(UUID releaseId, String gateType, boolean required) {
        this.releaseId = releaseId;
        this.gateType = gateType;
        this.required = required;
        this.status = Status.PENDING;
    }

    /**
     * Records the latest result ("latest wins"): status, evidence,
     * submitter, source, and the observed numeric value of a
     * threshold/ceiling gate. Never touches the override record — an
     * override is a governance fact about a blocking state, and a
     * later PASS supersedes nothing that was overridden (the override
     * stays rendered beside the history it governed).
     */
    public void record(
            Status status,
            String evidenceUrl,
            Double observedValue,
            UUID submittedBy,
            Source source,
            Instant evaluatedAt) {
        this.status = status;
        this.evidenceUrl = evidenceUrl;
        this.observedValue = observedValue;
        this.submittedBy = submittedBy;
        this.source = source;
        this.evaluatedAt = evaluatedAt;
    }

    /**
     * The override crossing (AC3): records actor, mandatory reason,
     * and moment on a FAILED or NO_DATA gate — the service's guard
     * answers who may call it and whether this row is overridable;
     * the row itself keeps reporting the underlying state.
     */
    public void override(UUID overriddenBy, String reason, Instant overriddenAt) {
        this.overriddenBy = overriddenBy;
        this.overriddenReason = reason;
        this.overriddenAt = overriddenAt;
    }

    /** Whether this gate's state stops blocking: only an override does — PASSED never needed one. */
    public boolean isOverridden() {
        return overriddenBy != null;
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

    public String getGateType() {
        return gateType;
    }

    public Status getStatus() {
        return status;
    }

    public String getEvidenceUrl() {
        return evidenceUrl;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    public Boolean getRequired() {
        return required;
    }

    public Double getObservedValue() {
        return observedValue;
    }

    public UUID getSubmittedBy() {
        return submittedBy;
    }

    public Source getSource() {
        return source;
    }

    public UUID getOverriddenBy() {
        return overriddenBy;
    }

    public String getOverriddenReason() {
        return overriddenReason;
    }

    public Instant getOverriddenAt() {
        return overriddenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
