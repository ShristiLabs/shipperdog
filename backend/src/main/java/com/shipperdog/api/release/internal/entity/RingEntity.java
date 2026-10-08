package com.shipperdog.api.release.internal.entity;

import jakarta.persistence.Entity;
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
 * Maps the {@code rings} reference table (V24, issue #55; behavior since
 * V32, issue #43, S10-04): the ring data model with its PRD 8.10
 * properties — eligibility rules, the approval-policy reference, the
 * promotion policy, and the distribution-channel reference. FR-017's
 * five system defaults were seeded by V24 with {@code organization_id}
 * NULL (visible to every organization); V32 materializes the same five
 * defaults organization-grain for every existing organization (and the
 * API's creation hook seeds every new one), so each organization can
 * rename, reorder, and rule its own rings. A ring is reference data,
 * not tenant-scoped data: it carries an optional organization dimension
 * and no tenant dimension, so it uses a plain repository with explicit
 * organization-scoped queries — the tenth amendment's organization-grain
 * pattern — and joins no seam.
 */
@Entity
@Table(name = "rings")
public class RingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** NULL for a system default ring; the owning organization for an organization ring. */
    private UUID organizationId;

    /** The stable, human-facing code: {@code tenant-pilot} (lowercase kebab). */
    private String code;

    /** The display name: {@code Tenant Pilot}. */
    private String name;

    /** The progressive-distribution order (PRD 8.10: Ring 0 Engineering ... Ring 4 Production). */
    private int position;

    /**
     * PRD 8.10's eligibility rules (S10-04): {@code {"tenants": [...]}}
     * and/or {@code {"groups": [...]}}, an absent axis unconstrained.
     * NULL admits every release target (the seeded standing). The
     * service validates the shape and the organization membership of
     * every listed id before any write; the JSON column stores what was
     * validated (the V31 gate-config precedent — one authority).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> eligibility;

    /**
     * The NAME-ONLY approval-policy reference (PRD 8.10): an approval
     * workflow name of the owning organization, the V27 environments
     * precedent. Not a foreign key by design — renaming a workflow must
     * not strand ring rows; an unresolved name fails closed at
     * resolution (the S10-02 decision).
     */
    private String approvalPolicy;

    /** PRD 8.10's manual-or-automatic standing; AUTOMATIC acts with S10-06's engine. */
    private String promotionPolicy;

    /** The NAME-ONLY distribution-channel reference; store channels land as destinations in E13. */
    private String distributionChannel;

    private Instant createdAt;

    private Instant updatedAt;

    protected RingEntity() {}

    /**
     * One seeded or created ring (S10-04): the owning scope (NULL for a
     * system default), the stable code, the display name, the order, and
     * the four PRD 8.10 properties.
     */
    public RingEntity(
            UUID id,
            UUID organizationId,
            String code,
            String name,
            int position,
            Map<String, Object> eligibility,
            String approvalPolicy,
            String promotionPolicy,
            String distributionChannel) {
        this.id = id;
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.position = position;
        this.eligibility = eligibility;
        this.approvalPolicy = approvalPolicy;
        this.promotionPolicy = promotionPolicy;
        this.distributionChannel = distributionChannel;
    }

    /**
     * Edits the mutable facts (the PATCH surface): the display name, the
     * order, and the four properties. The code is the identity releases
     * and definitions key on; it never moves.
     */
    public void edit(
            String name,
            int position,
            Map<String, Object> eligibility,
            String approvalPolicy,
            String promotionPolicy,
            String distributionChannel) {
        this.name = name;
        this.position = position;
        this.eligibility = eligibility;
        this.approvalPolicy = approvalPolicy;
        this.promotionPolicy = promotionPolicy;
        this.distributionChannel = distributionChannel;
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

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getPosition() {
        return position;
    }

    public Map<String, Object> getEligibility() {
        return eligibility;
    }

    public String getApprovalPolicy() {
        return approvalPolicy;
    }

    public String getPromotionPolicy() {
        return promotionPolicy;
    }

    public String getDistributionChannel() {
        return distributionChannel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
