package com.shipperdog.api.workflow.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code approval_workflow_steps} table (V28, expanded by
 * V29): one step of a definition's ordered chain — position 1..n
 * contiguous across the WHOLE definition, the required role from the
 * {@code approval_step_role} vocabulary (exactly the five PRD 7 roles
 * the RBAC matrix grants an approve permission), and the optional
 * tenant scope that narrows the step to releases TARGETING that tenant
 * (skipped for others, never a failure — the issue's "optional tenant
 * scope").
 *
 * <p>Since S10-02 (issue #41) a definition is an ordered list of
 * GROUPS: {@code groupPosition} (1..m contiguous) orders them and
 * {@code groupMode} is the group's satisfaction rule — {@code ALL}
 * (the PARALLEL mode: every step must approve) or {@code ANY} (the OR
 * mode: the first approval satisfies, remaining siblings skip).
 * Sequential is the degenerate case: groups of one, all {@code ALL} —
 * exactly the shape every V28 definition carries. The optional
 * {@link StepCondition} is a step's trigger predicate, evaluated at
 * round instantiation against the release's frozen environment facts:
 * an unmet condition instantiates the step SKIPPED with its rationale
 * recorded, never a silent omission.
 *
 * <p>A plain child of the definition (the via-parent rule): reachable
 * only through the workflow it belongs to; no snapshot, no seam, no
 * route of its own.
 */
@Entity
@Table(name = "approval_workflow_steps")
@IdClass(ApprovalWorkflowStepId.class)
public class ApprovalWorkflowStepEntity {

    /** The step roles (the {@code approval_step_role} vocabulary, V28): stored as the native enum. */
    public enum Role {
        QA_ENGINEER,
        SECURITY_REVIEWER,
        COMPLIANCE_REVIEWER,
        APP_OWNER,
        RELEASE_MANAGER
    }

    /**
     * A group's satisfaction rule (the {@code approval_group_mode}
     * vocabulary, V29): ALL is the PARALLEL mode (every step must
     * approve), ANY is the OR mode (the first approval satisfies the
     * group and the remaining PENDING siblings become SKIPPED). A
     * rejection terminates its round in EITHER mode — a no is never
     * outvoted.
     */
    public enum GroupMode {
        ALL,
        ANY
    }

    /**
     * The condition-predicate stub vocabulary (the
     * {@code approval_step_condition_type} enum, V29): S10-06's
     * policy-engine expression evaluator is the migration target —
     * these two predicates are the deterministic subset S10-02 ships.
     */
    public enum ConditionType {
        /** The release's targeted environment code equals {@code value}. */
        ENVIRONMENT_EQUALS,
        /** The environment's resolved configuration contains {@code key} (optionally with exactly {@code value}). */
        FEATURE_FLAG_CONTAINS
    }

    /**
     * A step's trigger predicate (S10-02): the type plus its operand
     * — the environment code for {@code ENVIRONMENT_EQUALS}, the
     * flag key (and optional exact value) for
     * {@code FEATURE_FLAG_CONTAINS}. Evaluated only at round
     * instantiation against facts frozen with the release; the
     * evaluation's outcome and rationale are recorded on the step's
     * round row.
     */
    public record StepCondition(ConditionType type, String key, String value) {

        /** The predicate's compact rendering (the audit rationale's shape). */
        public String describe() {
            return switch (type) {
                case ENVIRONMENT_EQUALS -> "ENVIRONMENT_EQUALS(" + value + ")";
                case FEATURE_FLAG_CONTAINS ->
                    key == null
                            ? "FEATURE_FLAG_CONTAINS(?)"
                            : value == null
                                    ? "FEATURE_FLAG_CONTAINS(" + key + ")"
                                    : "FEATURE_FLAG_CONTAINS(" + key + "=" + value + ")";
            };
        }
    }

    @Id
    private UUID workflowId;

    @Id
    private Integer position;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Role requiredRole;

    private UUID tenantId;

    @Column(name = "group_position", nullable = false)
    private Integer groupPosition;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "group_mode", nullable = false)
    private GroupMode groupMode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "condition_type")
    private ConditionType conditionType;

    @Column(name = "condition_key")
    private String conditionKey;

    @Column(name = "condition_value")
    private String conditionValue;

    private Instant createdAt;

    protected ApprovalWorkflowStepEntity() {}

    /**
     * One unconditional step of a sequential group of one (S10-01's
     * shape, still the degenerate default): the position, the required
     * role, the optional tenant scope.
     */
    public ApprovalWorkflowStepEntity(UUID workflowId, int position, Role requiredRole, UUID tenantId) {
        this(workflowId, position, requiredRole, tenantId, position, GroupMode.ALL, null);
    }

    /**
     * One step of a group (S10-02): the definition-wide position and
     * the required role beside the group's position and mode, the
     * optional tenant scope, and the optional trigger condition.
     */
    public ApprovalWorkflowStepEntity(
            UUID workflowId,
            int position,
            Role requiredRole,
            UUID tenantId,
            int groupPosition,
            GroupMode groupMode,
            StepCondition condition) {
        this.workflowId = workflowId;
        this.position = position;
        this.requiredRole = requiredRole;
        this.tenantId = tenantId;
        this.groupPosition = groupPosition;
        this.groupMode = groupMode;
        if (condition != null) {
            this.conditionType = condition.type();
            this.conditionKey = condition.key();
            this.conditionValue = condition.value();
        }
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public Integer getPosition() {
        return position;
    }

    public Role getRequiredRole() {
        return requiredRole;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Integer getGroupPosition() {
        return groupPosition;
    }

    public GroupMode getGroupMode() {
        return groupMode;
    }

    /** The step's trigger predicate, or null when the step is unconditional. */
    public StepCondition condition() {
        return conditionType == null ? null : new StepCondition(conditionType, conditionKey, conditionValue);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
