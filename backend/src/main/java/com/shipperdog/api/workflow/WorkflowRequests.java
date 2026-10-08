package com.shipperdog.api.workflow;

import com.shipperdog.api.workflow.WorkflowController.ConditionRequest;
import com.shipperdog.api.workflow.WorkflowController.GroupRequest;
import com.shipperdog.api.workflow.WorkflowController.StepRequest;
import com.shipperdog.api.workflow.internal.service.ApprovalWorkflowService.ConditionCommand;
import com.shipperdog.api.workflow.internal.service.ApprovalWorkflowService.GroupCommand;
import com.shipperdog.api.workflow.internal.service.ApprovalWorkflowService.StepCommand;
import java.util.List;

/**
 * The request-body translation (S10-02): the controller's wire shapes
 * into the service's commands — the flat sequential steps (S10-01's
 * shape, list order IS chain order) and the groups (each its ALL/ANY
 * mode and its own ordered steps, S10-02's shape). A helper class, not
 * controller methods, because the arch rule keeps every
 * controller-declared method off the bare {@code List} return shape
 * (list endpoints answer the paginated envelope) — the translation is
 * request parsing, not a route.
 */
final class WorkflowRequests {

    /** The flat steps (null-safe: an absent member is the empty chain). */
    static List<StepCommand> stepCommandsOf(List<StepRequest> steps) {
        return steps == null
                ? List.of()
                : steps.stream()
                        .map(step -> new StepCommand(step.role(), step.tenantId(), conditionOf(step.condition())))
                        .toList();
    }

    /** The groups (null-safe; each group's mode with its own steps). */
    static List<GroupCommand> groupCommandsOf(List<GroupRequest> groups) {
        return groups == null
                ? List.of()
                : groups.stream()
                        .map(group -> new GroupCommand(group.mode(), stepCommandsOf(group.steps())))
                        .toList();
    }

    private static ConditionCommand conditionOf(ConditionRequest condition) {
        return condition == null ? null : new ConditionCommand(condition.type(), condition.key(), condition.value());
    }

    private WorkflowRequests() {}
}
