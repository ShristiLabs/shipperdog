/** The client-side evaluator mirror and the editor's validation
 * (issue #41, AC4/AC5): every mode's evaluation from committed rows —
 * sequential degenerate groups, ALL (parallel), ANY (or, first
 * approval satisfies, skips never do), conditions and vacuous groups,
 * the never-outvoted rejection — and the validation verdicts that keep
 * an unreachable chain unexpressible in the editor. */
import { describe, expect, it } from "vitest";
import type { ApprovalStep, WorkflowStep } from "../api/workflows";
import {
  chainSummary,
  conditionLabel,
  draftToGroups,
  evaluateRound,
  validateDefinition,
} from "./approvalRules";

function step(
  position: number,
  group: number,
  mode: "ALL" | "ANY",
  status: ApprovalStep["status"],
): ApprovalStep {
  return {
    position,
    approvalType: "QA_ENGINEER",
    tenantId: null,
    groupPosition: group,
    groupMode: mode,
    status,
    approverId: null,
    approverRole: null,
    reason: null,
    decidedAt: null,
  };
}

function definitionStep(
  position: number,
  group: number,
  mode: "ALL" | "ANY",
  role: WorkflowStep["requiredRole"],
): WorkflowStep {
  return {
    position,
    requiredRole: role,
    tenantId: null,
    groupPosition: group,
    groupMode: mode,
    condition: null,
  };
}

describe("evaluateRound (the backend evaluator's mirror)", () => {
  it("walks sequential groups of one in order", () => {
    const chain = [step(1, 1, "ALL", "PENDING"), step(2, 2, "ALL", "PENDING")];
    expect(evaluateRound(chain)).toMatchObject({ outcome: "PENDING", activeGroupPosition: 1 });
    expect(evaluateRound(chain).waitingOn.map((pending) => pending.position)).toEqual([1]);

    chain[0]!.status = "APPROVED";
    expect(evaluateRound(chain).activeGroupPosition).toBe(2);

    chain[1]!.status = "APPROVED";
    expect(evaluateRound(chain).outcome).toBe("SATISFIED");
  });

  it("makes every ALL sibling decidable at once and satisfies only when all signed", () => {
    const chain = [
      step(1, 1, "ALL", "APPROVED"),
      step(2, 2, "ALL", "PENDING"),
      step(3, 2, "ALL", "PENDING"),
    ];
    const evaluation = evaluateRound(chain);
    expect(evaluation.activeGroupMode).toBe("ALL");
    expect(evaluation.waitingOn.map((pending) => pending.position)).toEqual([2, 3]);

    chain[2]!.status = "APPROVED";
    expect(evaluateRound(chain).outcome).toBe("PENDING");

    chain[1]!.status = "APPROVED";
    expect(evaluateRound(chain).outcome).toBe("SATISFIED");
  });

  it("satisfies an ANY group on the first approval alone; the sibling skip is just the record", () => {
    const chain = [
      step(1, 2, "ANY", "PENDING"),
      step(2, 2, "ANY", "APPROVED"),
    ];
    const evaluation = evaluateRound(chain);
    expect(evaluation.outcome).toBe("SATISFIED");
    expect(evaluation.waitingOn).toEqual([]);
  });

  it("satisfies a group vacuously when every sibling was condition-excluded", () => {
    const chain = [step(1, 1, "ANY", "SKIPPED"), step(2, 2, "ALL", "PENDING")];
    expect(evaluateRound(chain).activeGroupPosition).toBe(2);
  });

  it("never outvotes a rejection, in either mode", () => {
    for (const mode of ["ALL", "ANY"] as const) {
      const chain = [step(1, 1, mode, "APPROVED"), step(2, 1, mode, "REJECTED")];
      expect(evaluateRound(chain).outcome).toBe("REJECTED");
    }
  });
});

describe("chain rendering", () => {
  it("summarizes the mixed topology with mode words", () => {
    const steps = [
      definitionStep(1, 1, "ALL", "QA_ENGINEER"),
      definitionStep(2, 2, "ALL", "SECURITY_REVIEWER"),
      definitionStep(3, 2, "ALL", "COMPLIANCE_REVIEWER"),
      definitionStep(4, 3, "ANY", "APP_OWNER"),
    ];
    expect(chainSummary(steps)).toBe("QA → (Security + Compliance) → Product");
  });

  it("renders both condition predicates in prose", () => {
    expect(
      conditionLabel({ type: "ENVIRONMENT_EQUALS", key: null, value: "production" }),
    ).toBe("if environment = production");
    expect(
      conditionLabel({ type: "FEATURE_FLAG_CONTAINS", key: "payments.eu-krn", value: "on" }),
    ).toBe("if flag payments.eu-krn = on");
    expect(conditionLabel({ type: "FEATURE_FLAG_CONTAINS", key: "darkMode", value: null })).toBe(
      "if flag darkMode set",
    );
  });
});

describe("validateDefinition (the editor's unreachable-shape refusal)", () => {
  const unconditional = { role: "QA_ENGINEER" as const, tenantId: null, condition: null };

  it("accepts the minimal valid draft and refuses the empty definition", () => {
    expect(validateDefinition([{ mode: "ALL", steps: [unconditional] }], "valid-name").size).toBe(0);
    expect(validateDefinition([{ mode: "ALL", steps: [] }], "valid-name").has("empty-definition")).toBe(
      true,
    );
  });

  it("refuses an empty group (its position would be meaningless)", () => {
    const verdict = validateDefinition(
      [
        { mode: "ALL", steps: [unconditional] },
        { mode: "ANY", steps: [] },
      ],
      "valid-name",
    );
    expect(verdict.has("empty-group-1")).toBe(true);
  });

  it("refuses a role demanded twice (the second demand is unreachable)", () => {
    const verdict = validateDefinition(
      [
        {
          mode: "ALL",
          steps: [unconditional, { ...unconditional, tenantId: "20000000-0000-4000-8000-000000000001" }],
        },
      ],
      "valid-name",
    );
    expect(verdict.get("duplicate-role")).toContain("QA");
  });

  it("refuses malformed names, missing flag keys, and missing environment codes", () => {
    expect(validateDefinition([{ mode: "ALL", steps: [unconditional] }], "Not Kebab").has("name")).toBe(
      true,
    );
    const flag = validateDefinition(
      [
        {
          mode: "ALL",
          steps: [
            {
              role: "COMPLIANCE_REVIEWER" as const,
              tenantId: null,
              condition: { type: "FEATURE_FLAG_CONTAINS" as const, key: "  ", value: "" },
            },
          ],
        },
      ],
      "valid-name",
    );
    expect([...flag.values()].some((error) => error.includes("flag key"))).toBe(true);

    const env = validateDefinition(
      [
        {
          mode: "ALL",
          steps: [
            {
              role: "COMPLIANCE_REVIEWER" as const,
              tenantId: null,
              condition: { type: "ENVIRONMENT_EQUALS" as const, key: "", value: " " },
            },
          ],
        },
      ],
      "valid-name",
    );
    expect([...env.values()].some((error) => error.includes("environment code"))).toBe(true);
  });
});

describe("draftToGroups (the wire shape)", () => {
  it("trims operands into the create/edit body; empty operands become null", () => {
    expect(
      draftToGroups([
        {
          mode: "ANY",
          steps: [
            {
              role: "SECURITY_REVIEWER",
              tenantId: null,
              condition: { type: "FEATURE_FLAG_CONTAINS", key: " payments.eu-krn ", value: " " },
            },
          ],
        },
      ]),
    ).toEqual([
      {
        mode: "ANY",
        steps: [
          {
            role: "SECURITY_REVIEWER",
            tenantId: null,
            condition: { type: "FEATURE_FLAG_CONTAINS", key: "payments.eu-krn", value: null },
          },
        ],
      },
    ]);
  });
});
