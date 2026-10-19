package com.shipperdog.api.otabundle.internal.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One OTA alert firing row (V56 {@code ota_alert_firings}, S16-11,
 * issue #173, ADR-0010 D10): the scheduler's observation of a rule's
 * metric crossing its threshold -- the observed value, the
 * machine-readable window arithmetic, the moment, and the resolution
 * stamp (NULL while the firing stands: the console badge; stamped
 * when the metric recovers inside the rule's window). One standing
 * firing per rule by construction: while a crossing persists, the
 * pass leaves the original firing alone (its {@code fired_at} is the
 * degradation's start), and the next recovery stamps it -- never a
 * firing per tick.
 *
 * <p><strong>No organization column, by design</strong> (the {@code
 * ota_rollout_events} doctrine): a firing row is addressed only
 * through its rule -- every caller resolves the owning rule through
 * the org-scoped seam first and reaches the firings by the guarded
 * rule's id -- so the row needs no scope dimension of its own and a
 * plain (non-seam) repository serves it.
 */
@Entity
@Table(name = "ota_alert_firings")
public class OtaAlertFiringEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID ruleId;

    /** The bundle the firing evaluated (the rule's own scope, stamped per firing). */
    private UUID otaBundleId;

    /** The observed value that crossed the rule's threshold. */
    private double observedValue;

    /** The machine-readable window arithmetic; never secret material. */
    private String detail;

    private Instant firedAt;

    /** NULL while the firing stands; stamped when the metric recovers inside the rule's window. */
    private Instant resolvedAt;

    private Instant createdAt;

    protected OtaAlertFiringEntity() {}

    /** One firing born of a crossing: the observation beside the rule's own scope. */
    public OtaAlertFiringEntity(UUID ruleId, UUID otaBundleId, double observedValue, String detail, Instant firedAt) {
        this.ruleId = ruleId;
        this.otaBundleId = otaBundleId;
        this.observedValue = observedValue;
        this.detail = detail;
        this.firedAt = firedAt;
    }

    /** The recovery's stamp: the metric no longer crosses inside the window. */
    public void resolve(Instant at) {
        this.resolvedAt = at;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public UUID getOtaBundleId() {
        return otaBundleId;
    }

    public double getObservedValue() {
        return observedValue;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getFiredAt() {
        return firedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
