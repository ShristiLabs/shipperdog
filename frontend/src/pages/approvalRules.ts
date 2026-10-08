/** Approval mode semantics for the console (issue #41, S10-02, AC4/AC5)
 * — the client-side mirror of the backend's ApprovalRoundEvaluator,
 * never a second opinion: the same function from a round's committed
 * step rows to the round's state (which group is active, what the
 * round waits on, whether it is satisfied or rejected) so the release
 * dashboard renders the waiting-on list the evaluator would name, and
 * the editor's preview shows the definition's shape. The server
 * re-derives everything on every decision; this module only renders
 * what the committed rows already say.
 *
 * Also the definition editor's client-side validation (AC4): the
 * structural rules that make an unreachable or unrunnable chain
 * unexpressible — the group-list model itself has no orphan edges
 * (groups run in order, each step belongs to exactly one group), so
 * validation refuses the empty shapes a group editor could otherwise
 * produce: an empty definition, an empty group, a role demanded twice
 * (the second demand is unreachable — the role's holder already
 * signed), the ten-step ceiling, and a malformed condition. The
 * server re-validates everything (the console is never the gate). */
import type {
  ApprovalRole,
  ApprovalStep,
  ConditionType,
  GroupInput,
  GroupMode,
  WorkflowStep,
} from "../api/workflows";

export interface RoundEvaluation {
  outcome: "PENDING" | "SATISFIED" | "REJECTED";
  /** The first unsatisfied group's position (0 when terminal/empty). */
  activeGroupPosition: number;
  activeGroupMode: GroupMode;
  /** The active group's PENDING steps — the decidable set and the
   * waiting-on list the dashboard renders. */
  waitingOn: ApprovalStep[];
}

/** Evaluate one round's steps (ordered by position — the served
 * order). Semantics mirror the backend evaluator exactly: groups run
 * sequentially; ALL satisfies when no step is PENDING; ANY satisfies
 * on the first APPROVED signature or vacuously when every sibling was
 * SKIPPED at instantiation; a REJECTED step anywhere rejects the round
 * in either mode — a no is never outvoted. */
export function evaluateRound(steps: ApprovalStep[]): RoundEvaluation {
  if (steps.length === 0) {
    return { outcome: "SATISFIED", activeGroupPosition: 0, activeGroupMode: "ALL", waitingOn: [] };
  }
  if (steps.some((step) => step.status === "REJECTED")) {
    return { outcome: "REJECTED", activeGroupPosition: 0, activeGroupMode: "ALL", waitingOn: [] };
  }
  const groups = new Map<number, ApprovalStep[]>();
  for (const step of steps) {
    const group = groups.get(step.groupPosition) ?? [];
    group.push(step);
    groups.set(step.groupPosition, group);
  }
  for (const [groupPosition, group] of groups) {
    const anyPending = group.some((step) => step.status === "PENDING");
    const anyApproved = group.some((step) => step.status === "APPROVED");
    const satisfied =
      group[0]!.groupMode === "ANY" ? anyApproved || (!anyPending && !anyApproved) : !anyPending;
    if (!satisfied) {
      return {
        outcome: "PENDING",
        activeGroupPosition: groupPosition,
        activeGroupMode: group[0]!.groupMode,
        waitingOn: group.filter((step) => step.status === "PENDING"),
      };
    }
  }
  return { outcome: "SATISFIED", activeGroupPosition: 0, activeGroupMode: "ALL", waitingOn: [] };
}

/** The groups of a definition's chain, in order: [(mode, steps)]. */
export function groupsOf(steps: WorkflowStep[]): Array<{ mode: GroupMode; steps: WorkflowStep[] }> {
  const groups = new Map<number, { mode: GroupMode; steps: WorkflowStep[] }>();
  for (const step of steps) {
    const group = groups.get(step.groupPosition) ?? { mode: step.groupMode, steps: [] };
    group.steps.push(step);
    groups.set(step.groupPosition, group);
  }
  return [...groups.entries()].sort(([a], [b]) => a - b).map(([, group]) => group);
}

/** The human summary of a definition for list rows: "QA → (Security +
 * Compliance) → Product" — parenthesized multi-step groups carry the
 * mode word ("+"/"or"). */
export function chainSummary(steps: WorkflowStep[]): string {
  return groupsOf(steps)
    .map((group) => {
      const names = group.steps.map((step) => roleLabel(step.requiredRole));
      return group.steps.length === 1 ? names[0]! : `(${names.join(group.mode === "ANY" ? " or " : " + ")})`;
    })
    .join(" → ");
}

export const APPROVAL_ROLES: ApprovalRole[] = [
  "QA_ENGINEER",
  "SECURITY_REVIEWER",
  "COMPLIANCE_REVIEWER",
  "APP_OWNER",
  "RELEASE_MANAGER",
];

export function roleLabel(role: ApprovalRole): string {
  switch (role) {
    case "QA_ENGINEER":
      return "QA";
    case "SECURITY_REVIEWER":
      return "Security";
    case "COMPLIANCE_REVIEWER":
      return "Compliance";
    case "APP_OWNER":
      return "Product";
    case "RELEASE_MANAGER":
      return "Release Mgr";
  }
}

export const CONDITION_TYPES: ConditionType[] = ["ENVIRONMENT_EQUALS", "FEATURE_FLAG_CONTAINS"];

export function conditionLabel(condition: {
  type: ConditionType;
  key: string | null;
  value: string | null;
}): string {
  if (condition.type === "ENVIRONMENT_EQUALS") {
    return `if environment = ${condition.value ?? "?"}`;
  }
  return condition.value === null || condition.value === ""
    ? `if flag ${condition.key ?? "?"} set`
    : `if flag ${condition.key ?? "?"} = ${condition.value}`;
}

/** The editor's draft: groups with mode toggles and their steps. */
export interface DraftStep {
  role: ApprovalRole;
  tenantId: string | null;
  condition: { type: ConditionType; key: string; value: string } | null;
}

export interface DraftGroup {
  mode: GroupMode;
  steps: DraftStep[];
}

/** The editor's validation verdict: an empty `errors` map means the
 * draft may be submitted. Every structural rule that would make a
 * step unreachable or the chain unrunnable refuses here (AC4):
 *
 * - `name` — the binding name (kebab-case, required, create only);
 * - `empty-definition` / `empty-group` — a chain with no steps, or a
 *   group that demands nothing (its siblings' satisfaction would make
 *   its own position meaningless — the unreachable shape);
 * - `duplicate-role` — a role demanded twice across the definition:
 *   the second demand can never be signed (the role's holder already
 *   signed once; the server's each-role-once rule);
 * - `too-many-steps` — the ten-step ceiling;
 * - `condition-*` — the predicate's shape (a flag condition names its
 *   key; operands fit their 256-char column).
 */
export function validateDefinition(
  groups: DraftGroup[],
  name: string | null,
): Map<string, string> {
  const errors = new Map<string, string>();
  if (name !== null) {
    const trimmed = name.trim();
    if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(trimmed)) {
      errors.set("name", "The binding name is lowercase kebab-case (letters, digits, single dashes).");
    }
  }
  const allSteps = groups.flatMap((group) => group.steps);
  if (allSteps.length === 0) {
    errors.set("empty-definition", "The chain needs at least one step.");
  }
  if (allSteps.length > 10) {
    errors.set("too-many-steps", "A chain carries at most ten steps.");
  }
  groups.forEach((group, index) => {
    if (group.steps.length === 0) {
      errors.set(`empty-group-${index}`, "Every group needs at least one step — an empty group is unreachable.");
    }
  });
  const seen = new Set<ApprovalRole>();
  for (const step of allSteps) {
    if (seen.has(step.role)) {
      errors.set("duplicate-role", `Each role signs once — ${roleLabel(step.role)} is demanded twice.`);
      break;
    }
    seen.add(step.role);
  }
  allSteps.forEach((step, index) => {
    if (step.condition === null) return;
    const { type, key, value } = step.condition;
    if (type === "FEATURE_FLAG_CONTAINS" && key.trim() === "") {
      errors.set(`condition-${index}`, "A flag condition names its flag key.");
    }
    if (key.length > 256 || value.length > 256) {
      errors.set(`condition-${index}`, "Condition operands carry at most 256 characters.");
    }
    if (type === "ENVIRONMENT_EQUALS" && value.trim() === "") {
      errors.set(`condition-${index}`, "An environment condition names the environment code.");
    }
  });
  return errors;
}

/** A draft into the create/edit body's groups shape: unconditional
 * steps carry condition null; empty operand strings become null. */
export function draftToGroups(groups: DraftGroup[]): GroupInput[] {
  return groups.map((group) => ({
    mode: group.mode,
    steps: group.steps.map((step) => ({
      role: step.role,
      tenantId: step.tenantId,
      condition:
        step.condition === null
          ? null
          : {
              type: step.condition.type,
              key: step.condition.key.trim() === "" ? null : step.condition.key.trim(),
              value: step.condition.value.trim() === "" ? null : step.condition.value.trim(),
            },
    })),
  }));
}
