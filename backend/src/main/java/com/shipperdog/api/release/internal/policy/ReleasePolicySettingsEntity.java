package com.shipperdog.api.release.internal.policy;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code release_policy_settings} table (V34, issue #45):
 * the organization's policy-engine settings — the skip-relaxation
 * knob the {@code ReleasePromotionPolicy} seam's javadoc reserved
 * ("an organization policy that admits named skips registers here
 * and widens the walk"). One row per organization, created lazily on
 * first edit: ABSENCE IS THE EMPTY SET, so every organization keeps
 * the #44 default posture (one ring at a time) until it deliberately
 * widens it.
 */
@Entity
@Table(name = "release_policy_settings")
public class ReleasePolicySettingsEntity {

    @Id
    private UUID organizationId;

    /** The ring codes a promotion may name BEYOND the next ring (validated against the vocabulary on write). */
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> skipAllowances;

    private Instant updatedAt;

    protected ReleasePolicySettingsEntity() {}

    /** The organization's settings row (created with the first edit — absence is the empty set). */
    public ReleasePolicySettingsEntity(UUID organizationId, List<String> skipAllowances) {
        this.organizationId = organizationId;
        this.skipAllowances = skipAllowances;
    }

    /** Replaces the skip allowances (the PUT surface's whole-set semantics). */
    public void editSkipAllowances(List<String> skipAllowances) {
        this.skipAllowances = skipAllowances;
    }

    @PrePersist
    void onCreate() {
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public List<String> getSkipAllowances() {
        return skipAllowances;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
