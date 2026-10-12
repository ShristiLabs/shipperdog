package com.shipperdog.api.authorization.internal.abac;

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
 * Maps the {@code abac_policies} table (V52, issue #70, S14-04, PRD
 * 25/38): one attribute-based access policy — applicability selectors
 * plus a typed condition tree plus an effect — as organization-grain
 * reference data (the V34 {@code release_policy_rules} pattern). The
 * condition tree is stored as the VALIDATED jsonb the write surface
 * parsed (one authority: {@link com.shipperdog.api.authorization.AbacCondition#parse}
 * refuses before any write, so the stored shape is always a tree the
 * evaluator can answer).
 *
 * <p>Both effects only ever NARROW access (the deny-override
 * composition over the RBAC base layer): a {@code DENY} policy
 * refuses when its condition holds; a {@code REQUIRE} policy refuses
 * when its condition does NOT hold for a request its selectors
 * match. Neither can turn a base-layer denial into an allow — that
 * direction does not exist in the vocabulary.
 *
 * <p>The {@code version} counter is the CURRENT version number: every
 * replace increments it and appends one immutable {@code
 * AbacPolicyVersionEntity} row (AC4). Selector references follow the
 * V34 precedents: {@code applicationId} and {@code environmentId}
 * are hard foreign keys (reference data), while {@code ring} is a
 * NAME-ONLY code the organization's vocabulary may redefine (an
 * unresolved code at evaluation simply does not match).
 */
@Entity
@Table(name = "abac_policies")
public class AbacPolicyEntity {

    /** The narrowing semantics: refuse when the condition holds, or refuse when it does not. */
    public enum Effect {
        DENY,
        REQUIRE
    }

    /** The decision the policy governs: the layered engine's actions plus the promotion consult. */
    public enum GoverningAction {
        LIST_APPLICATIONS,
        READ_ARTIFACT,
        DOWNLOAD,
        PROMOTE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    /** The policy's stable, human-facing name: {@code banking-production-promote} (unique per organization). */
    private String name;

    @Enumerated(EnumType.STRING)
    private Effect effect;

    @Enumerated(EnumType.STRING)
    private GoverningAction action;

    /** The application selector (null = any). */
    private UUID applicationId;

    /** The environment selector (null = any). */
    private UUID environmentId;

    /** The ring selector (null = any; a promotion's TARGET ring). */
    private String ring;

    /** The validated condition tree, stored exactly as parsed (never a string expression). */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> condition;

    /** The evaluation order: position ascending, then name ascending. */
    private int position;

    private boolean enabled;

    /** The current version number (v1 at create; +1 per replace). */
    private int version;

    private Instant createdAt;

    private Instant updatedAt;

    protected AbacPolicyEntity() {}

    /**
     * One policy (S14-04): the owning organization, the stable name,
     * the effect, the governing action, the optional selectors, the
     * VALIDATED condition tree, the evaluation position, and the
     * enabled standing — born at version 1.
     */
    public AbacPolicyEntity(
            UUID organizationId,
            String name,
            Effect effect,
            GoverningAction action,
            UUID applicationId,
            UUID environmentId,
            String ring,
            Map<String, Object> condition,
            int position,
            boolean enabled) {
        this.organizationId = organizationId;
        this.name = name;
        this.effect = effect;
        this.action = action;
        this.applicationId = applicationId;
        this.environmentId = environmentId;
        this.ring = ring;
        this.condition = condition;
        this.position = position;
        this.enabled = enabled;
        this.version = 1;
    }

    /** Replaces every mutable fact and bumps the version (the update surface's whole write). */
    public void edit(
            String name,
            Effect effect,
            GoverningAction action,
            UUID applicationId,
            UUID environmentId,
            String ring,
            Map<String, Object> condition,
            int position,
            boolean enabled) {
        this.name = name;
        this.effect = effect;
        this.action = action;
        this.applicationId = applicationId;
        this.environmentId = environmentId;
        this.ring = ring;
        this.condition = condition;
        this.position = position;
        this.enabled = enabled;
        this.version++;
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

    public String getName() {
        return name;
    }

    public Effect getEffect() {
        return effect;
    }

    public GoverningAction getAction() {
        return action;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public String getRing() {
        return ring;
    }

    public Map<String, Object> getCondition() {
        return condition;
    }

    public int getPosition() {
        return position;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
