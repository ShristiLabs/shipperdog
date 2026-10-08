package com.shipperdog.api.release.internal.policy;

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
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code release_policy_holds} table (V34, issue #45,
 * S10-06 AC2): the HOLD_FOR_REVIEW ledger. A fired HOLD rule records
 * one PENDING hold per (release, rule, ring) — the ring the hop
 * would enter — and the explicit privileged acknowledge flips it to
 * ACKNOWLEDGED with actor, mandatory reason, and timestamp (audited
 * beside). The acknowledged decision STANDS for later hops of the
 * same release into the same ring: the acknowledge is a human
 * decision about the rule's condition, not a one-shot token, and the
 * evaluation log names every suppressed firing with the
 * ACKNOWLEDGED outcome.
 *
 * <p>A pure child of the seam-served release (the V25 doctrine): no
 * owning-dimension snapshot — every caller resolves the release
 * through the release module's scoped port first. {@code ruleId} is
 * a HARD foreign key: the delete guard refuses a rule with holds
 * (the ledger is audit evidence), the opposite of the evaluation
 * log's loose snapshots.
 */
@Entity
@Table(name = "release_policy_holds")
public class ReleasePolicyHoldEntity {

    /** The hold's standing: awaiting the explicit acknowledge, or recorded by one. */
    public enum Status {
        PENDING,
        ACKNOWLEDGED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID releaseId;

    private UUID ruleId;

    private UUID organizationId;

    /** The ring the blocked hop would have entered (the hold guards entry). */
    private String ring;

    @Enumerated(EnumType.STRING)
    private Status status;

    /** The fired condition's evidence snapshot: what the rule saw when it fired. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> evidence;

    private UUID acknowledgedBy;

    private Instant acknowledgedAt;

    private String acknowledgeReason;

    private Instant createdAt;

    private Instant updatedAt;

    protected ReleasePolicyHoldEntity() {}

    /** One PENDING hold (S10-06 AC2): the fired rule's recorded demand for an explicit acknowledge. */
    public ReleasePolicyHoldEntity(
            UUID releaseId, UUID ruleId, UUID organizationId, String ring, Map<String, Object> evidence) {
        this.releaseId = releaseId;
        this.ruleId = ruleId;
        this.organizationId = organizationId;
        this.ring = ring;
        this.status = Status.PENDING;
        this.evidence = evidence;
    }

    /** The explicit privileged acknowledge (AC2): the actor, the mandatory reason, the moment — all three at once. */
    public void acknowledge(UUID acknowledgedBy, String acknowledgeReason, Instant acknowledgedAt) {
        this.status = Status.ACKNOWLEDGED;
        this.acknowledgedBy = acknowledgedBy;
        this.acknowledgeReason = acknowledgeReason;
        this.acknowledgedAt = acknowledgedAt;
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

    public UUID getRuleId() {
        return ruleId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getRing() {
        return ring;
    }

    public Status getStatus() {
        return status;
    }

    public Map<String, Object> getEvidence() {
        return evidence;
    }

    public UUID getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public String getAcknowledgeReason() {
        return acknowledgeReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
