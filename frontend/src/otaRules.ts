/**
 * Client-side mirrors of the OTA domain's stable vocabulary and the
 * pure derivations its screens render (docs/ota-updates-plan.md
 * workstreams 3-11; V55/V56 words; the API stays the authority): the
 * bundle and rollout status words with display labels and chip colors,
 * the attach-mode pair, the basis-points percentage render, the
 * funnel's stage order and its apply-success rate, the fraction
 * formatters (adoption and crash-free rates are 0..1 server
 * fractions), and the alert-rule vocabulary (metrics, comparators,
 * severities). The backend enforces all of it regardless; these
 * mirrors only decide what renders.
 */
import type {
  OtaAlertComparator,
  OtaAlertMetric,
  OtaAlertSeverity,
  OtaFunnelStages,
} from "./api/ota";

// ---------------------------------------------------------------------------
// Bundle statuses (V55 vocabulary)
// ---------------------------------------------------------------------------

export type OtaBundleStatus = "UPLOADING" | "VALIDATING" | "SIGNED" | "RELEASED" | "REVOKED";

export const OTA_BUNDLE_STATUSES: readonly OtaBundleStatus[] = [
  "UPLOADING",
  "VALIDATING",
  "SIGNED",
  "RELEASED",
  "REVOKED",
];

export const OTA_BUNDLE_STATUS_LABELS: Record<OtaBundleStatus, string> = {
  UPLOADING: "Uploading",
  VALIDATING: "Validating",
  SIGNED: "Signed",
  RELEASED: "Released",
  REVOKED: "Revoked",
};

export const OTA_BUNDLE_STATUS_COLORS: Record<
  OtaBundleStatus,
  "default" | "info" | "success" | "warning" | "error"
> = {
  UPLOADING: "default",
  VALIDATING: "info",
  SIGNED: "success",
  RELEASED: "success",
  REVOKED: "error",
};

// ---------------------------------------------------------------------------
// Rollout standings and modes (V56 vocabulary)
// ---------------------------------------------------------------------------

export type OtaRolloutStatus = "RUNNING" | "PAUSED" | "HALTED" | "COMPLETE";

export const OTA_ROLLOUT_STATUS_LABELS: Record<OtaRolloutStatus, string> = {
  RUNNING: "Running",
  PAUSED: "Paused",
  HALTED: "Halted",
  COMPLETE: "Complete",
};

export const OTA_ROLLOUT_STATUS_COLORS: Record<
  OtaRolloutStatus,
  "default" | "info" | "success" | "warning" | "error"
> = {
  RUNNING: "info",
  PAUSED: "warning",
  HALTED: "error",
  COMPLETE: "success",
};

export const OTA_MODES = ["AUTOMATIC", "CHECKPOINT"] as const;
export type OtaMode = (typeof OTA_MODES)[number];

export function otaModeLabel(mode: OtaMode): string {
  return mode === "AUTOMATIC" ? "automatic (policy-gated)" : "checkpoint (RM approval)";
}

/** 100% = 10000 basis points, the ladder's own vocabulary. */
export const OTA_MAX_BASIS_POINTS = 10_000;

/** Renders a basis-points rung as a human percentage ("12.5%"). */
export function basisPointsPercent(basisPoints: number): string {
  return `${(basisPoints / 100).toLocaleString(undefined, { maximumFractionDigits: 2 })}%`;
}

// ---------------------------------------------------------------------------
// Funnel derivations (S16-10)
// ---------------------------------------------------------------------------

/** The funnel's fixed stage order with display labels. */
export const FUNNEL_STAGE_ORDER = [
  { key: "checks", label: "Checks" },
  { key: "seen", label: "Devices seen" },
  { key: "downloads", label: "Downloads" },
  { key: "applied", label: "Applied" },
] as const;

export type FunnelStageKey = (typeof FUNNEL_STAGE_ORDER)[number]["key"];

/** The funnel rows, widest first, with each stage's share of the
 * checks that entered the funnel (0 while nothing has checked yet). */
export function funnelRows(stages: OtaFunnelStages): Array<{ label: string; value: number; share: number }> {
  const widest = Math.max(stages.checks, 1);
  return FUNNEL_STAGE_ORDER.map(({ key, label }) => {
    const value = stages[key];
    return { label, value, share: Math.round((value / widest) * 100) };
  });
}

/** The apply success rate: applied / (applied + failed) — the share of
 * apply attempts that landed. Null (not 0) while nothing has attempted
 * yet, so "no data" stays honest. */
export function applySuccessRate(stages: OtaFunnelStages): number | null {
  const attempts = stages.applied + stages.failed;
  if (attempts === 0) return null;
  return stages.applied / attempts;
}

/** Renders a 0..1 fraction as a percentage ("99.42%"). */
export function formatPercent(fraction: number): string {
  return `${(fraction * 100).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`;
}

/** Renders a 0..1 fraction difference in percentage points with its
 * sign ("+0.31 pp"); crash-free deltas read in points, not percent
 * of a percent. */
export function formatDeltaPoints(delta: number): string {
  const points = delta * 100;
  return `${points >= 0 ? "+" : ""}${points.toLocaleString(undefined, { maximumFractionDigits: 2 })} pp`;
}

// ---------------------------------------------------------------------------
// Alert-rule vocabulary (S16-11)
// ---------------------------------------------------------------------------

export const OTA_ALERT_METRICS: ReadonlyArray<{ value: OtaAlertMetric; label: string }> = [
  { value: "OTA_CHECKS", label: "Checks (count)" },
  { value: "OTA_SEEN", label: "Devices seen (count)" },
  { value: "OTA_DOWNLOADS", label: "Downloads (count)" },
  { value: "OTA_APPLIED", label: "Applied (count)" },
  { value: "OTA_FAILURES", label: "Failures (count)" },
  { value: "OTA_ROLLBACKS", label: "Rollbacks (count)" },
  { value: "OTA_APPLY_FAILURE_RATE", label: "Apply failure rate (fraction)" },
  { value: "OTA_CRASH_FREE_RATE", label: "Crash-free rate (fraction)" },
];

export const OTA_ALERT_COMPARATORS: ReadonlyArray<{ value: OtaAlertComparator; label: string }> = [
  { value: "GT", label: ">" },
  { value: "GTE", label: ">=" },
  { value: "LT", label: "<" },
  { value: "LTE", label: "<=" },
];

export const OTA_ALERT_SEVERITIES: readonly OtaAlertSeverity[] = ["INFO", "WARNING", "CRITICAL"];

export const OTA_SEVERITY_COLORS: Record<OtaAlertSeverity, "info" | "warning" | "error"> = {
  INFO: "info",
  WARNING: "warning",
  CRITICAL: "error",
};

/** One rule's watch as a single readable statement ("crash-free rate
 * < 0.995 over 15m"); a firing carries the same members minus the
 * window, so windowMinutes is optional. */
export function alertRuleExpression(rule: {
  metric: string;
  comparator: string;
  threshold: number;
  windowMinutes?: number;
}): string {
  const metricLabel =
    OTA_ALERT_METRICS.find((metric) => metric.value === rule.metric)?.label ?? rule.metric;
  const comparatorLabel =
    OTA_ALERT_COMPARATORS.find((comparator) => comparator.value === rule.comparator)?.label ??
    rule.comparator;
  const window = rule.windowMinutes !== undefined ? ` over ${rule.windowMinutes}m` : "";
  return `${metricLabel} ${comparatorLabel} ${rule.threshold}${window}`;
}
