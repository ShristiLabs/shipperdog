package com.shipperdog.api.workflow.internal.entity;

import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepEntity.Role;
import com.shipperdog.api.workflow.internal.entity.ReleaseApprovalEntity.Status;
import jakarta.persistence.Column;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps the {@code release_approvals} table (V1 baseline, expanded by
 * V28, issue #40, S10-01, PRD 13): one per-step record of one round —
 * V1's PRD 13 sketch (never written until this story) expanded with
 * the round linkage, the chain position, the tenant scope, the
 * signature role, and the reject reason. {@code approvalType} is PRD
 * 13's own vocabulary ({@link ApprovalType} — what kind of sign-off
 * this step demanded); the step's required ROLE (who may sign it)
 * maps 1:1 onto it at instantiation, and {@code approverRole} records
 * the role the approver actually signed under at decision time —
 * roles change later, the record does not (the issue's technical
 * note). V1's {@code approver} column name is retained ({@code §13}'s
 * own word).
 *
 * <p>Decided exactly once: PENDING to APPROVED or REJECTED through the
 * repository's compare-and-set; a decided row is terminal (no update,
 * no delete — the CAS is the only writer after instantiation). A pure
 * child of its round, which is a pure child of the seam-served release
 * (the V25 doctrine, applied one level deeper).
 */
@Entity
@Table(name = "release_approvals")
public class ReleaseApprovalEntity {

    /**
     * PRD 13's approval-type vocabulary (V1's {@code approval_type}
     * enum, extended by V28 with COMPLIANCE and RELEASE_MANAGER): what
     * kind of sign-off a step demands. The role-to-type map is total
     * and 1:1 — {@link #of(Role)} is its single definition.
     */
    public enum ApprovalType {
        QA,
        SECURITY,
        PRODUCT,
        COMPLIANCE,
        RELEASE_MANAGER;

        /** The type a step demands of a role-holder (the 1:1 map's single definition). */
        public static ApprovalType of(Role role) {
            return switch (role) {
                case QA_ENGINEER -> QA;
                case SECURITY_REVIEWER -> SECURITY;
                case COMPLIANCE_REVIEWER -> COMPLIANCE;
                case APP_OWNER -> PRODUCT;
                case RELEASE_MANAGER -> RELEASE_MANAGER;
            };
        }
    }

    /**
     * One step instance's vocabulary (V1's {@code approval_status}
     * enum, extended by V29 with SKIPPED): PENDING while the step
     * awaits its signature, APPROVED/REJECTED the signed terminals,
     * and SKIPPED the unsigned terminal — an ANY-group sibling after
     * the group's first approval satisfied it, or a condition-excluded
     * step at instantiation. A SKIPPED row carries its reason and
     * decidedAt but never a signature (nobody signed it); the
     * decision-shape check pins the three-leg shape.
     */
    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        SKIPPED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID releaseId;

    private UUID roundId;

    private Integer position;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private ApprovalType approvalType;

    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    @Column(name = "group_position", nullable = false)
    private Integer groupPosition;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "group_mode", nullable = false)
    private ApprovalWorkflowStepEntity.GroupMode groupMode;

    /** PRD 13's own column name ({@code approver}): who decided (null while PENDING). */
    @Column(name = "approver")
    private UUID approverId;

    /** The role the approver signed under, recorded at decision time (null while PENDING). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Role approverRole;

    private String reason;

    private Instant decidedAt;

    private Instant createdAt;

    private Instant updatedAt;

    protected ReleaseApprovalEntity() {}

    /**
     * One step instance as instantiated PENDING (S10-01's shape, still
     * the sequential default): the position, the approval type (the
     * step's role mapped 1:1) and tenant scope copied from the
     * definition, its group facts (V29) beside.
     */
    public ReleaseApprovalEntity(
            UUID releaseId,
            UUID roundId,
            int position,
            ApprovalType approvalType,
            UUID tenantId,
            int groupPosition,
            ApprovalWorkflowStepEntity.GroupMode groupMode) {
        this.releaseId = releaseId;
        this.roundId = roundId;
        this.position = position;
        this.approvalType = approvalType;
        this.tenantId = tenantId;
        this.groupPosition = groupPosition;
        this.groupMode = groupMode;
        this.status = Status.PENDING;
    }

    /**
     * One step instance as instantiated SKIPPED (S10-02): a step the
     * round never demanded — its condition evaluated false at
     * instantiation. The rationale rides {@code reason} and the moment
     * {@code decidedAt}; no signature exists (the decision-shape
     * check's SKIPPED leg).
     */
    public static ReleaseApprovalEntity skippedAtInstantiation(
            UUID releaseId,
            UUID roundId,
            int position,
            ApprovalType approvalType,
            UUID tenantId,
            int groupPosition,
            ApprovalWorkflowStepEntity.GroupMode groupMode,
            String reason,
            Instant decidedAt) {
        ReleaseApprovalEntity row = new ReleaseApprovalEntity(
                releaseId, roundId, position, approvalType, tenantId, groupPosition, groupMode);
        row.status = Status.SKIPPED;
        row.reason = reason;
        row.decidedAt = decidedAt;
        return row;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /**
     * Binds the row to its round at instantiation (the round row
     * inserts first; the only writer besides the constructors).
     */
    public void assignRound(UUID roundId) {
        this.roundId = roundId;
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

    public UUID getRoundId() {
        return roundId;
    }

    public Integer getPosition() {
        return position;
    }

    public ApprovalType getApprovalType() {
        return approvalType;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Integer getGroupPosition() {
        return groupPosition;
    }

    public ApprovalWorkflowStepEntity.GroupMode getGroupMode() {
        return groupMode;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getApproverId() {
        return approverId;
    }

    public Role getApproverRole() {
        return approverRole;
    }

    public String getReason() {
        return reason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
