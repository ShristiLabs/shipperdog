package com.shipperdog.api.otabundle.internal.entity;

import com.shipperdog.api.tenant.OrganizationScope;
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

/**
 * One OTA alert rule row (V56 {@code ota_alert_rules} with V61's
 * org-grain column, S16-11, issue #173, ADR-0010 D10): a persisted
 * watch over the application's OTA metrics -- the dictionary metric,
 * the comparator, the threshold, the lookback window, the severity a
 * crossing raises. The evaluation scheduler reads every rule on its
 * pass and lands {@link OtaAlertFiringEntity} rows for the crossings;
 * NOTHING a rule detects ever halts a rollout (D10) -- the firing
 * surfaces, the halt stays an operator's act through the S16-06
 * endpoints.
 *
 * <p><strong>Org-grain by design</strong> (the rollout plans' own
 * doctrine, V60's words): a rule watches an application's whole OTA
 * funnel -- the device population's grain, the owning organization's
 * -- so {@link #tenantId()} answers null (V61's column, kept NULL by
 * its org-grain guard) and access rides the seam bound in
 * ORGANIZATION scope.
 *
 * <p><strong>The metric vocabulary</strong> is the S16-09 dictionary's
 * OTA names plus the two derived rates (an open row-value set in the
 * schema, a closed dictionary in the service -- the CRUD validates
 * against it, the evaluator computes each member). {@code
 * ota_bundle_id} narrows a rule to one bundle; NULL watches the
 * application's funnel as a whole.
 */
@Entity
@Table(name = "ota_alert_rules")
public class OtaAlertRuleEntity implements OrganizationScope {

    /** The comparison of V56 {@code ck_ota_alert_rules_comparator}. */
    public enum Comparator {
        GT,
        GTE,
        LT,
        LTE
    }

    /** The crossing's severity of V56 {@code ck_ota_alert_rules_severity}. */
    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    /**
     * Always NULL (V61's {@code ck_ota_alert_rules_org_grain}): the
     * rule is the organization's own row -- a rule watches the
     * application's whole device population, never one tenant's --
     * but the MAPPED column itself is what lets the row ride the
     * tenant-isolation seam, whose organization-scope predicate
     * composes {@code tenantId IS NULL AND organizationId = :context}
     * on exactly this attribute (the {@code OrganizationScope}
     * doctrine every org-owned row follows).
     */
    private UUID tenantId;

    private UUID applicationId;

    /** The bundle the rule watches; NULL = the application's funnel as a whole. */
    private UUID otaBundleId;

    /** The dictionary metric the rule evaluates (the S16-09 OTA names plus the derived rates). */
    private String metric;

    @Enumerated(EnumType.STRING)
    private Comparator comparator;

    private double threshold;

    /** The evaluation lookback window, in minutes (V56's 1..10080). */
    private int windowMinutes;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    private UUID createdBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected OtaAlertRuleEntity() {}

    /**
     * One rule born of a validated create: the application's own
     * dimensions beside the watch's whole shape.
     */
    public OtaAlertRuleEntity(
            UUID organizationId,
            UUID applicationId,
            UUID otaBundleId,
            String metric,
            Comparator comparator,
            double threshold,
            int windowMinutes,
            Severity severity,
            UUID createdBy) {
        this.organizationId = organizationId;
        this.applicationId = applicationId;
        this.otaBundleId = otaBundleId;
        this.metric = metric;
        this.comparator = comparator;
        this.threshold = threshold;
        this.windowMinutes = windowMinutes;
        this.severity = severity;
        this.createdBy = createdBy;
    }

    /** The update's whole shape: every watch member replaced at once (the CRUD's full-replace PUT). */
    public void reshape(
            UUID otaBundleId,
            String metric,
            Comparator comparator,
            double threshold,
            int windowMinutes,
            Severity severity) {
        this.otaBundleId = otaBundleId;
        this.metric = metric;
        this.comparator = comparator;
        this.threshold = threshold;
        this.windowMinutes = windowMinutes;
        this.severity = severity;
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

    @Override
    public UUID organizationId() {
        return organizationId;
    }

    /** Always null: the row is org-owned (V61's org-grain guard over the mapped column). */
    @Override
    public UUID tenantId() {
        return tenantId;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getOtaBundleId() {
        return otaBundleId;
    }

    public String getMetric() {
        return metric;
    }

    public Comparator getComparator() {
        return comparator;
    }

    public double getThreshold() {
        return threshold;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public Severity getSeverity() {
        return severity;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
