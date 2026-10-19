/** otaRules mirrors (S16-15): the V55/V56 vocabulary renderings and
 * the pure derivations the OTA screens rely on — the funnel's stage
 * order and shares, the apply-success rate's honest no-data null, the
 * fraction formatters, and the rule/firing expression. */
import { describe, expect, it } from "vitest";
import {
  OTA_BUNDLE_STATUS_LABELS,
  OTA_ROLLOUT_STATUS_LABELS,
  alertRuleExpression,
  applySuccessRate,
  basisPointsPercent,
  formatDeltaPoints,
  formatPercent,
  funnelRows,
  otaModeLabel,
} from "./otaRules";

describe("otaRules vocabulary", () => {
  it("labels every bundle and rollout status", () => {
    expect(OTA_BUNDLE_STATUS_LABELS.SIGNED).toBe("Signed");
    expect(OTA_BUNDLE_STATUS_LABELS.REVOKED).toBe("Revoked");
    expect(OTA_ROLLOUT_STATUS_LABELS.RUNNING).toBe("Running");
    expect(OTA_ROLLOUT_STATUS_LABELS.HALTED).toBe("Halted");
  });

  it("labels the attach modes with their governance meaning", () => {
    expect(otaModeLabel("AUTOMATIC")).toContain("policy-gated");
    expect(otaModeLabel("CHECKPOINT")).toContain("RM approval");
  });

  it("renders basis points as a human percentage", () => {
    expect(basisPointsPercent(10_000)).toBe("100%");
    expect(basisPointsPercent(1_250)).toBe("12.5%");
    expect(basisPointsPercent(5)).toBe("0.05%");
  });
});

describe("funnel derivations", () => {
  const stages = {
    checks: 200,
    seen: 150,
    downloads: 100,
    applied: 90,
    failed: 8,
    rolledBack: 2,
  };

  it("orders the funnel stages with their share of the widest", () => {
    const rows = funnelRows(stages);
    expect(rows.map((row) => row.label)).toEqual([
      "Checks",
      "Devices seen",
      "Downloads",
      "Applied",
    ]);
    expect(rows[0]).toMatchObject({ value: 200, share: 100 });
    expect(rows[1]).toMatchObject({ value: 150, share: 75 });
    expect(rows[3]).toMatchObject({ value: 90, share: 45 });
  });

  it("never divides by zero while nothing has checked", () => {
    const rows = funnelRows({
      checks: 0,
      seen: 0,
      downloads: 0,
      applied: 0,
      failed: 0,
      rolledBack: 0,
    });
    expect(rows.every((row) => row.share === 0)).toBe(true);
  });

  it("derives the apply success rate from attempts, null when none", () => {
    expect(applySuccessRate(stages)).toBeCloseTo(90 / 98);
    expect(
      applySuccessRate({ checks: 5, seen: 5, downloads: 0, applied: 0, failed: 0, rolledBack: 0 }),
    ).toBeNull();
  });
});

describe("fraction formatters", () => {
  it("renders fractions as percentages", () => {
    expect(formatPercent(0.9942)).toBe("99.42%");
    expect(formatPercent(1)).toBe("100.00%");
  });

  it("renders deltas in signed percentage points", () => {
    expect(formatDeltaPoints(0.0031)).toBe("+0.31 pp");
    expect(formatDeltaPoints(-0.005)).toBe("-0.5 pp");
  });
});

describe("alert rule expression", () => {
  it("states the full watch including the window", () => {
    expect(
      alertRuleExpression({
        metric: "OTA_CRASH_FREE_RATE",
        comparator: "LT",
        threshold: 0.995,
        windowMinutes: 15,
      }),
    ).toBe("Crash-free rate (fraction) < 0.995 over 15m");
  });

  it("states a firing's watch without the window it does not carry", () => {
    expect(
      alertRuleExpression({ metric: "OTA_FAILURES", comparator: "GTE", threshold: 10 }),
    ).toBe("Failures (count) >= 10");
  });
});
