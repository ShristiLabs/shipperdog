package com.shipperdog.api.analytics;

/**
 * The FR-029 metric definitions registry (issue #49, S11-04): every
 * per-release metric the platform computes, with its formula, unit,
 * dimensions, and source — the code-side metrics dictionary
 * (docs/analytics.md mirrors this table and carries the worked
 * examples). The query API serves exactly this vocabulary, and the
 * two evaluation seams ({@code GateMetricSource} of S10-03 and
 * {@code ReleasePolicyMetricSource} of S10-06) resolve their
 * metric-backed questions through the same computation.
 *
 * <p><strong>Sources.</strong> INTERNAL metrics populate from day one
 * — downloads from the S8-04 audit trail, approvals from the S10-01
 * decision records, ring occupancy from the release's own timeline —
 * with the migration-time backfill seeding the rollups from history.
 * DEVICE_SDK metrics need the S15-01 in-app SDK's reports (frozen
 * contract: {@code POST /api/v1/tenants/{id}/analytics/events}); until
 * a release holds the events a formula needs, the metric answers the
 * explicit NO_DATA and every gate or policy that consumes it fails
 * closed (the S10-03 AC4 posture — never a zero, never a pass).
 *
 * <p><strong>Dimensions.</strong> Every metric is computed per
 * release; the rollups additionally key the ring the release occupied
 * when the underlying event occurred (the starting ring until the
 * first COMPLETED promotion, then the last COMPLETED promotion's
 * to-ring), so release, application, tenant (the owning dimension),
 * ring, and time-window views all aggregate from the same rows.
 */
public final class AnalyticsCatalog {

    private AnalyticsCatalog() {}

    /** The metric vocabulary of FR-029 — the metrics dictionary's keys. */
    public enum Metric {
        /**
         * Count of allowed download issuances of the release's
         * artifacts (S8-04's {@code DOWNLOAD_ISSUED} trail). Count,
         * INTERNAL, populated from day one with backfill.
         */
        DOWNLOADS(Source.INTERNAL, Unit.COUNT),
        /**
         * Count of device install reports ({@code INSTALL} events).
         * Count, DEVICE_SDK — NO_DATA until events arrive.
         */
        INSTALLATIONS(Source.DEVICE_SDK, Unit.COUNT),
        /**
         * Succeeded installs / reported installs, 0..1
         * ({@code INSTALL} events by result). Rate, DEVICE_SDK.
         */
        INSTALL_SUCCESS_RATE(Source.DEVICE_SDK, Unit.RATE),
        /**
         * 1 - crash sessions / total sessions, 0..1 ({@code SESSION}
         * event counts). Rate, DEVICE_SDK. The gate seam's
         * {@code crash-free-threshold} metric.
         */
        CRASH_FREE_SESSIONS(Source.DEVICE_SDK, Unit.RATE),
        /** ANR sessions / total sessions, 0..1 ({@code SESSION} counts). Rate, DEVICE_SDK. */
        ANR_RATE(Source.DEVICE_SDK, Unit.RATE),
        /** Uninstall reports / install reports, 0..1. Rate, DEVICE_SDK. */
        UNINSTALL_RATE(Source.DEVICE_SDK, Unit.RATE),
        /**
         * Targeted testers who downloaded / distinct targeted testers,
         * 0..1 (the target set is the creation targets expanded: a
         * tenant target's retained members, narrowed by its named
         * tester groups when present). Rate, INTERNAL — NO_DATA only
         * when the release targets no testers at all.
         */
        TESTER_ENGAGEMENT(Source.INTERNAL, Unit.RATE),
        /**
         * Average minutes from an approval round's start to its final
         * required decision (S10-01's round records), attributed to
         * the final decision's bucket. Duration, INTERNAL.
         */
        APPROVAL_DURATION(Source.INTERNAL, Unit.MINUTES),
        /**
         * Minutes the release occupied a ring (entry from creation's
         * starting ring or a COMPLETED promotion; exit at the next
         * COMPLETED promotion — the open span while it holds). The
         * current value serves the live span; rollups attribute each
         * closed span to its exit bucket. Duration, INTERNAL.
         */
        TIME_IN_RING(Source.INTERNAL, Unit.MINUTES);

        private final Source source;
        private final Unit unit;

        Metric(Source source, Unit unit) {
            this.source = source;
            this.unit = unit;
        }

        /** Where the formula's facts come from. */
        public Source source() {
            return source;
        }

        /** The value's unit (the dictionary's formula column). */
        public Unit unit() {
            return unit;
        }
    }

    /** The two fact families the dictionary's formulas read. */
    public enum Source {
        /** Internal facts already recorded (S8-04 downloads, S10-01 approvals, the release timeline). */
        INTERNAL,
        /** Device-side reports of the S15-01 SDK (the frozen events API contract). */
        DEVICE_SDK
    }

    /** The unit a metric's value carries. */
    public enum Unit {
        COUNT,
        RATE,
        MINUTES
    }

    /** The metrics that roll into the precomputed tables (all of them — the dictionary IS the registry). */
    public static java.util.Set<Metric> all() {
        return java.util.Set.of(Metric.values());
    }
}
