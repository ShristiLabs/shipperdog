package com.shipperdog.api.workflow.internal.repository;

import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepEntity;
import com.shipperdog.api.workflow.internal.entity.ApprovalWorkflowStepId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for a definition's ordered chain (V28, issue #40, S10-01).
 * A plain child store of the definition (the via-parent rule):
 * callers resolve the workflow first, then read its steps.
 */
public interface ApprovalWorkflowStepRepository
        extends JpaRepository<ApprovalWorkflowStepEntity, ApprovalWorkflowStepId> {

    /** One definition's chain, in order (position ascending). */
    List<ApprovalWorkflowStepEntity> findByWorkflowIdOrderByPositionAsc(UUID workflowId);
}
