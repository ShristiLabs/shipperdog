package com.shipperdog.api.workflow.internal.entity;

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
 * Maps the {@code approval_workflow_steps} table (V28): one step of a
 * definition's ordered chain — position 1..n contiguous, the required
 * role from the {@code approval_step_role} vocabulary (exactly the
 * five PRD 7 roles the RBAC matrix grants an approve permission), and
 * the optional tenant scope that narrows the step to releases
 * TARGETING that tenant (skipped for others, never a failure — the
 * issue's "optional tenant scope").
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

    @Id
    private UUID workflowId;

    @Id
    private Integer position;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Role requiredRole;

    private UUID tenantId;

    private Instant createdAt;

    protected ApprovalWorkflowStepEntity() {}

    /** One step of a new definition (S10-01): the position, the required role, the optional tenant scope. */
    public ApprovalWorkflowStepEntity(UUID workflowId, int position, Role requiredRole, UUID tenantId) {
        this.workflowId = workflowId;
        this.position = position;
        this.requiredRole = requiredRole;
        this.tenantId = tenantId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
