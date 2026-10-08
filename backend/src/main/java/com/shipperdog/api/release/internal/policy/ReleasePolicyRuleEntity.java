package com.shipperdog.api.release.internal.policy;

import com.shipperdog.api.release.PolicyCondition;
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
 * Maps the {@code release_policy_rules} table (V34, issue #45,
 * S10-06, PRD 33): one declarative rule — IF condition THEN action —
 * as organization-grain reference data (the rings pattern: the
 * organization dimension only, no tenant dimension, a plain
 * repository with explicit organization-scoped queries). The
 * condition tree is stored as the VALIDATED jsonb the write surface
 * parsed (one authority: {@link PolicyCondition#parse} refuses
 * before any write, so the stored shape is always a tree the
 * evaluator can answer).
 *
 * <p>The scope references follow the module's standing precedents:
 * {@code applicationId} and {@code environmentId} are hard foreign
 * keys (their rows are reference data), while {@code ring} is a
 * NAME-ONLY code (the V27/V32 precedent — the ring vocabulary is the
 * organization's to redefine, so the code is validated resolvable at
 * write and an unresolved code at evaluation simply does not match).
 */
@Entity
@Table(name = "release_policy_rules")
public class ReleasePolicyRuleEntity {

    /** The scoping dimension: which promotions the rule evaluates for. */
    public enum Scope {
        ORGANIZATION,
        APPLICATION,
        RING,
        ENVIRONMENT
    }

    /** PRD 33's three actions: refuse, hold for an explicit acknowledge, or record an allow. */
    public enum Action {
        BLOCK,
        HOLD_FOR_REVIEW,
        ALLOW
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID organizationId;

    /** The rule's stable, human-facing name: {@code no-critical-vulns} (lowercase kebab, unique per organization). */
    private String name;

    @Enumerated(EnumType.STRING)
    private Scope scope;

    /** The release's application a scoped rule matches (scope APPLICATION). */
    private UUID applicationId;

    /** The hop's TARGET ring code a scoped rule matches (scope RING — governance for ENTERING the ring). */
    private String ring;

    /** The release's targeted environment a scoped rule matches (scope ENVIRONMENT). */
    private UUID environmentId;

    @Enumerated(EnumType.STRING)
    private Action action;

    /** The validated condition tree, stored exactly as parsed (never a string expression). */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> condition;

    /** The evaluation order (AC5): position ascending, then name ascending — the org's defined order. */
    private int position;

    private boolean enabled;

    private Instant createdAt;

    private Instant updatedAt;

    protected ReleasePolicyRuleEntity() {}

    /**
     * One rule (S10-06 AC1): the owning organization, the stable
     * name, the scope and its reference, the action, the VALIDATED
     * condition tree, the evaluation position, and the enabled
     * standing.
     */
    public ReleasePolicyRuleEntity(
            UUID organizationId,
            String name,
            Scope scope,
            UUID applicationId,
            String ring,
            UUID environmentId,
            Action action,
            Map<String, Object> condition,
            int position,
            boolean enabled) {
        this.organizationId = organizationId;
        this.name = name;
        this.scope = scope;
        this.applicationId = applicationId;
        this.ring = ring;
        this.environmentId = environmentId;
        this.action = action;
        this.condition = condition;
        this.position = position;
        this.enabled = enabled;
    }

    /** Edits every mutable fact (the PUT surface): the rule's meaning end to end, revalidated as a whole. */
    public void edit(
            String name,
            Scope scope,
            UUID applicationId,
            String ring,
            UUID environmentId,
            Action action,
            Map<String, Object> condition,
            int position,
            boolean enabled) {
        this.name = name;
        this.scope = scope;
        this.applicationId = applicationId;
        this.ring = ring;
        this.environmentId = environmentId;
        this.action = action;
        this.condition = condition;
        this.position = position;
        this.enabled = enabled;
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

    public Scope getScope() {
        return scope;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public String getRing() {
        return ring;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public Action getAction() {
        return action;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
