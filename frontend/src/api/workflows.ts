/** Approval workflow surface client (S10-01/S10-02 backend,
 * docs/releases.md): the organization-grain definition routes (list,
 * read, create, edit) and the release-addressed approval routes (the
 * audit-read state, one decision, the rework restart). Field shapes
 * mirror the workflow module's served views exactly — the definition's
 * chain carries its group facts (groupPosition/groupMode, S10-02) and
 * each step's optional trigger condition; the round's steps carry the
 * frozen copy of the definition they ran plus the recorded signature,
 * skip rationale, or pending demand. */
import { apiFetch, pageFetch, type Page } from "./client";

/** The five-role approval vocabulary (PRD 8.10): the role whose holder
 * may sign a step. Each role appears at most once per definition. */
export type ApprovalRole =
  | "QA_ENGINEER"
  | "SECURITY_REVIEWER"
  | "COMPLIANCE_REVIEWER"
  | "APP_OWNER"
  | "RELEASE_MANAGER";

/** A group's satisfaction rule (S10-02): ALL — the PARALLEL mode, every
 * step must approve — or ANY — the OR mode, the first approval
 * satisfies and the remaining siblings become optional-skipped. */
export type GroupMode = "ALL" | "ANY";

/** The condition-predicate stub vocabulary (S10-02; S10-06's expression
 * evaluator migrates it): ENVIRONMENT_EQUALS compares the targeted
 * environment code to `value`; FEATURE_FLAG_CONTAINS asks the
 * environment's resolved configuration for `key` (optionally exactly
 * `value`). */
export type ConditionType = "ENVIRONMENT_EQUALS" | "FEATURE_FLAG_CONTAINS";

export interface Condition {
  type: ConditionType;
  key: string | null;
  value: string | null;
}

/** One step of a definition: the required role, the optional tenant
 * scope (the release must target the tenant for the step to demand its
 * signature), the trigger condition, and the group facts. */
export interface WorkflowStep {
  position: number;
  requiredRole: ApprovalRole;
  tenantId: string | null;
  groupPosition: number;
  groupMode: GroupMode;
  condition: Condition | null;
}

/** The definition view: the binding name (the vocabulary an
 * environment's approval_policy references — not editable), the
 * optional starting-ring attachment, and the ordered chain. */
export interface ApprovalWorkflow {
  id: string;
  organizationId: string;
  name: string;
  ring: string | null;
  steps: WorkflowStep[];
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

/** One step instance of a round: PENDING (the demand), APPROVED /
 * REJECTED (the signature — approver, role, when, reason), or SKIPPED
 * (no signature, always a reason: which signature satisfied the ANY
 * group, or the condition's evaluation rationale). */
export interface ApprovalStep {
  position: number;
  approvalType: ApprovalRole;
  tenantId: string | null;
  groupPosition: number;
  groupMode: GroupMode;
  status: "PENDING" | "APPROVED" | "REJECTED" | "SKIPPED";
  approverId: string | null;
  approverRole: string | null;
  reason: string | null;
  decidedAt: string | null;
}

/** One round: the frozen definition it ran (the round keeps its
 * per-step copies even after the definition is edited) and every
 * recorded decision. */
export interface ApprovalRound {
  roundNumber: number;
  status: "PENDING" | "SATISFIED" | "REJECTED";
  workflowId: string;
  workflowName: string;
  createdBy: string;
  createdAt: string;
  steps: ApprovalStep[];
}

/** The release's whole approval record: every round — rejected rounds
 * with their reasons beside the live or satisfied one — and the
 * release's lifecycle status for context. */
export interface ApprovalState {
  releaseId: string;
  releaseRef: string;
  applicationId: string;
  releaseStatus: string;
  currentRoundNumber: number;
  currentRoundStatus: "PENDING" | "SATISFIED" | "REJECTED";
  rounds: ApprovalRound[];
}

/** A condition as the create/edit bodies carry it; null means an
 * unconditional step. */
export interface ConditionInput {
  type: ConditionType;
  key: string | null;
  value: string | null;
}

export interface StepInput {
  role: ApprovalRole;
  tenantId: string | null;
  condition: ConditionInput | null;
}

export interface GroupInput {
  mode: GroupMode;
  steps: StepInput[];
}

export function listApprovalWorkflows(
  params: { organizationId: string; limit?: number; cursor?: string },
): Promise<Page<ApprovalWorkflow>> {
  return pageFetch<ApprovalWorkflow>("/approval-workflows", { ...params });
}

export function getApprovalWorkflow(
  organizationId: string,
  workflowId: string,
): Promise<ApprovalWorkflow> {
  return apiFetch<ApprovalWorkflow>(
    `/approval-workflows/${workflowId}?organizationId=${encodeURIComponent(organizationId)}`,
  );
}

export function createApprovalWorkflow(input: {
  organizationId: string;
  name: string;
  ring: string | null;
  /** Exactly one chain shape rides the body — flat steps or groups. */
  steps?: StepInput[] | null;
  groups?: GroupInput[] | null;
}): Promise<ApprovalWorkflow> {
  return apiFetch<ApprovalWorkflow>("/approval-workflows", { method: "POST", body: input });
}

/** The definition edit (S10-02 AC4): the chain replaces (flat steps or
 * groups, exactly the creation shapes) and the ring re-attaches — an
 * absent ring DETACHES. The name never moves. */
export function updateApprovalWorkflow(
  organizationId: string,
  workflowId: string,
  input: { ring: string | null; steps?: StepInput[] | null; groups?: GroupInput[] | null },
): Promise<ApprovalWorkflow> {
  return apiFetch<ApprovalWorkflow>(
    `/approval-workflows/${workflowId}?organizationId=${encodeURIComponent(organizationId)}`,
    { method: "PUT", body: input },
  );
}

/** The audit read: every round with its steps and signatures. Answers
 * the precise 404 when the release never bound a policy. */
export function getReleaseApprovals(applicationId: string, releaseId: string): Promise<ApprovalState> {
  return apiFetch<ApprovalState>(
    `/releases/${releaseId}/approvals?applicationId=${encodeURIComponent(applicationId)}`,
  );
}

/** One approval decision on the current round: APPROVE or REJECT (the
 * reason is mandatory on REJECT), optionally asserting the step
 * position being decided (an out-of-order assertion answers 409). */
export function decideReleaseApproval(
  applicationId: string,
  releaseId: string,
  input: { decision: "APPROVE" | "REJECT"; step?: number; reason?: string },
): Promise<ApprovalState> {
  const trimmed = input.reason?.trim();
  return apiFetch<ApprovalState>(`/releases/${releaseId}/approvals`, {
    method: "POST",
    body: {
      applicationId,
      decision: input.decision,
      ...(input.step !== undefined ? { step: input.step } : {}),
      ...(trimmed ? { reason: trimmed } : {}),
    },
  });
}

/** The rework restart: a rejected round's chain re-opens as a NEW round
 * of the same frozen definition (release management, not a signature). */
export function restartApprovalRound(
  applicationId: string,
  releaseId: string,
  reason?: string,
): Promise<ApprovalState> {
  const trimmed = reason?.trim();
  return apiFetch<ApprovalState>(`/releases/${releaseId}/approvals/rounds`, {
    method: "POST",
    body: { applicationId, ...(trimmed ? { reason: trimmed } : {}) },
  });
}
