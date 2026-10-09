package com.shipperdog.api.analytics.internal.metrics;

import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity.Result;
import com.shipperdog.api.analytics.internal.entity.AnalyticsEventEntity.Type;
import com.shipperdog.api.analytics.internal.repository.AnalyticsEventRepository;
import com.shipperdog.api.gate.GateCatalog;
import com.shipperdog.api.gate.GateMetricSource;
import com.shipperdog.api.release.PolicyMetric;
import com.shipperdog.api.release.ReleasePolicyMetricSource;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The analytics pipeline's registrants on the two evaluation seams
 * (issue #49, S11-04 — the registration both seams' javadocs name):
 * the device-side and engagement family resolves from the SDK event
 * store and the download trail at evaluation time, exactly the
 * S10-03/S10-06 model.
 *
 * <p><strong>One registrant per metric, per seam.</strong> The
 * feedback module keeps {@code OPEN_CRITICAL_BUGS} (the definitive
 * real-time count — analytics never double-answers it), and nobody
 * owns {@code CRITICAL_VULNERABILITIES} yet (the validation seam it
 * needs is a later story's); both stay empty here. Every metric this
 * class owns answers empty while the release holds none of the
 * events its formula needs — the fail-closed NO_DATA of AC4, never a
 * zero and never a pass: a release whose crash-free rate cannot be
 * evidenced does not cross into READY on the strength of missing
 * data, and a policy rule reasoning about engagement holds for review
 * until engagement is evidenced. The moment S15-01's SDK reports
 * land through the frozen events API, the same answers turn into
 * values without any further wiring.
 */
final class AnalyticsMetricSources {

    private AnalyticsMetricSources() {}

    /**
     * The crash-free arithmetic both seams share: 1 - crash sessions
     * / total sessions over the release's SESSION reports — empty
     * while none exist (the NO_DATA case).
     */
    static Optional<Double> crashFreeRate(AnalyticsEventRepository events, UUID releaseId) {
        return events.sessionArithmeticOf(releaseId, Type.SESSION).stream()
                .findFirst()
                .filter(row -> ((Number) row[0]).longValue() > 0)
                .map(row -> 1.0 - ((Number) row[1]).doubleValue() / ((Number) row[0]).doubleValue());
    }

    /** The gate seam's registrant (S10-03's {@code crash-free-threshold} gate). */
    @Component
    static class Gate implements GateMetricSource {

        private final AnalyticsEventRepository events;

        Gate(AnalyticsEventRepository events) {
            this.events = events;
        }

        @Override
        public Optional<Double> metricOf(UUID releaseId, GateCatalog.Metric metric) {
            if (metric != GateCatalog.Metric.CRASH_FREE_RATE) {
                return Optional.empty();
            }
            // The gate's NUMERIC_MIN surface speaks PERCENTAGES — its
            // threshold is validated 0..100 and its manual submissions
            // and dashboard renders are percent — while the dictionary
            // and the policy seam speak the 0..1 rate. This adapter is
            // the conversion boundary (a raw 0.999 would read as
            // "failed" against a 99.5 threshold).
            return crashFreeRate(events, releaseId).map(rate -> rate * 100.0);
        }
    }

    /** The policy engine's registrant (S10-06's PRD 33 metric vocabulary). */
    @Component
    static class Policy implements ReleasePolicyMetricSource {

        private final AnalyticsEventRepository events;

        private final com.shipperdog.api.analytics.internal.service.AnalyticsQueryService queries;

        Policy(
                AnalyticsEventRepository events,
                com.shipperdog.api.analytics.internal.service.AnalyticsQueryService queries) {
            this.events = events;
            this.queries = queries;
        }

        @Override
        public Optional<Double> metricOf(UUID releaseId, PolicyMetric metric) {
            return switch (metric) {
                case CRASH_FREE_RATE -> crashFreeRate(events, releaseId);
                case ANR_RATE -> anrRate(events, releaseId);
                case UNINSTALL_RATE -> uninstallRate(events, releaseId);
                case INSTALL_SUCCESS_RATE -> installSuccessRate(events, releaseId);
                // INTERNAL from day one: the targeted downloaders /
                // targeted testers rate over the S8-04 trail — empty
                // only when the release targets no testers at all.
                case TESTER_ENGAGEMENT -> queries.testerEngagementOf(releaseId);
                // The frozen SDK contract's verdict reports: APPROVED
                // / (APPROVED + REJECTED) — NO_DATA until any lands.
                case TESTER_APPROVAL_RATE -> testerApprovalRate(events, releaseId);
                // The feedback module owns the definitive count; an
                // empty answer here steps aside for it (the seam's
                // first-definitive-answer rule).
                case OPEN_CRITICAL_BUGS -> Optional.empty();
                // No source speaks for vulnerabilities yet: the
                // fail-closed NO_DATA is the honest answer.
                case CRITICAL_VULNERABILITIES -> Optional.empty();
            };
        }

        private static Optional<Double> anrRate(AnalyticsEventRepository events, UUID releaseId) {
            return events.sessionArithmeticOf(releaseId, Type.SESSION).stream()
                    .findFirst()
                    .filter(row -> ((Number) row[0]).longValue() > 0)
                    .map(row -> ((Number) row[2]).doubleValue() / ((Number) row[0]).doubleValue());
        }

        private static Optional<Double> uninstallRate(AnalyticsEventRepository events, UUID releaseId) {
            long installs = events.countByReleaseIdAndEventType(releaseId, Type.INSTALL);
            if (installs == 0) {
                return Optional.empty();
            }
            long uninstalls = events.countByReleaseIdAndEventType(releaseId, Type.UNINSTALL);
            return Optional.of((double) uninstalls / installs);
        }

        private static Optional<Double> installSuccessRate(AnalyticsEventRepository events, UUID releaseId) {
            long installs = events.countByReleaseIdAndEventType(releaseId, Type.INSTALL);
            if (installs == 0) {
                return Optional.empty();
            }
            long succeeded = events.countByReleaseIdAndEventTypeAndResult(releaseId, Type.INSTALL, Result.SUCCEEDED);
            return Optional.of((double) succeeded / installs);
        }

        private static Optional<Double> testerApprovalRate(AnalyticsEventRepository events, UUID releaseId) {
            long approved =
                    events.countByReleaseIdAndEventTypeAndResult(releaseId, Type.TESTER_VERDICT, Result.APPROVED);
            long rejected =
                    events.countByReleaseIdAndEventTypeAndResult(releaseId, Type.TESTER_VERDICT, Result.REJECTED);
            long verdicts = approved + rejected;
            return verdicts == 0 ? Optional.empty() : Optional.of((double) approved / verdicts);
        }
    }
}
