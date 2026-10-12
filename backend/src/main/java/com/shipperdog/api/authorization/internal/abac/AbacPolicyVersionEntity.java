package com.shipperdog.api.authorization.internal.abac;

import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.Effect;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.GoverningAction;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code abac_policy_versions} table (V52, issue #70,
 * S14-04): the IMMUTABLE version history AC4 demands — every create
 * and replace appends one row carrying the complete authored facts,
 * the author, and the moment. Rows are never edited and never
 * deleted: the policy row's deletion survives them ({@code policy_id}
 * is deliberately LOOSE — the V34 evaluation-log precedent — so
 * "which rule version denied last month's promotion" stays answerable
 * from the data).
 */
@Entity
@Table(name = "abac_policy_versions")
public class AbacPolicyVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** LOOSE by design: history survives the policy row's deletion. */
    private UUID policyId;

    private UUID organizationId;

    /** The policy's name at authoring time (a rename is a new version). */
    private String name;

    private int version;

    @Enumerated(EnumType.STRING)
    private Effect effect;

    @Enumerated(EnumType.STRING)
    private GoverningAction action;

    private UUID applicationId;

    private UUID environmentId;

    private String ring;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> condition;

    private int position;

    private boolean enabled;

    private UUID authoredBy;

    private Instant createdAt;

    protected AbacPolicyVersionEntity() {}

    /** One immutable history row: the policy's complete facts as authored. */
    public AbacPolicyVersionEntity(
            UUID policyId,
            UUID organizationId,
            String name,
            int version,
            Effect effect,
            GoverningAction action,
            UUID applicationId,
            UUID environmentId,
            String ring,
            Map<String, Object> condition,
            int position,
            boolean enabled,
            UUID authoredBy) {
        this.policyId = policyId;
        this.organizationId = organizationId;
        this.name = name;
        this.version = version;
        this.effect = effect;
        this.action = action;
        this.applicationId = applicationId;
        this.environmentId = environmentId;
        this.ring = ring;
        this.condition = condition;
        this.position = position;
        this.enabled = enabled;
        this.authoredBy = authoredBy;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public int getVersion() {
        return version;
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

    public UUID getAuthoredBy() {
        return authoredBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
