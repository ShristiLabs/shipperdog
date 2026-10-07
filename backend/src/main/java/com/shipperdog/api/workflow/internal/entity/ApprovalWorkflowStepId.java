package com.shipperdog.api.workflow.internal.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The composite identity of {@code approval_workflow_steps} (V28):
 * the workflow and the position within its chain (the
 * {@code ReleaseTargetGroupId} shape).
 */
public class ApprovalWorkflowStepId implements Serializable {

    private UUID workflowId;

    private Integer position;

    public ApprovalWorkflowStepId() {}

    public ApprovalWorkflowStepId(UUID workflowId, Integer position) {
        this.workflowId = workflowId;
        this.position = position;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ApprovalWorkflowStepId stepId)) {
            return false;
        }
        return Objects.equals(workflowId, stepId.workflowId) && Objects.equals(position, stepId.position);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workflowId, position);
    }
}
