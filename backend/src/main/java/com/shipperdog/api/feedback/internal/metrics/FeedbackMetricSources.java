package com.shipperdog.api.feedback.internal.metrics;

import com.shipperdog.api.feedback.internal.entity.FeedbackEntity;
import com.shipperdog.api.feedback.internal.entity.FeedbackEntity.Severity;
import com.shipperdog.api.feedback.internal.repository.FeedbackRepository;
import com.shipperdog.api.gate.GateCatalog;
import com.shipperdog.api.gate.GateMetricSource;
import com.shipperdog.api.release.PolicyMetric;
import com.shipperdog.api.release.ReleasePolicyMetricSource;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The feedback-backed metric registrants (issue #47, S11-02): the
 * first live answers on the two seams S10-03 and S10-06 shipped
 * fail-closed and empty. The story's issue is explicit — the metric
 * is a real-time query over the feedback table in M2 (volumes are
 * low; S11-04's analytics pipeline can formalize the feed later, and
 * the seams keep reading through the same interfaces either way).
 *
 * <p>Both seams answer exactly the metric feedback owns — {@code
 * OPEN_CRITICAL_BUGS}: the release's count of unresolved CRITICAL
 * reports (severity CRITICAL, status OPEN or TRIAGED; a re-opened
 * report counts again, the AC4 flip). The answer is DEFINITIVE for
 * every release: a real-time count over committed rows is zero when
 * the release has no critical feedback, never "no data" — an empty
 * answer here would fail-closed a clean release's promotions on
 * missing data the table cannot miss. Every other metric (the crash
 * and engagement family) answers empty: S11-04's feeds own those,
 * and the fail-closed posture for them stands.
 */
final class FeedbackMetricSources {

    private FeedbackMetricSources() {}

    /** The one query both seams read: the release's open critical bug count. */
    static double openCriticalBugs(FeedbackRepository feedback, UUID releaseId) {
        return feedback.countByReleaseIdAndSeverityAndStatusIn(releaseId, Severity.CRITICAL, FeedbackEntity.UNRESOLVED);
    }

    /** The gate seam's registrant (S10-03's {@code open-critical-bugs} gate, COUNT_MAX). */
    @Component
    static class Gate implements GateMetricSource {

        private final FeedbackRepository feedback;

        Gate(FeedbackRepository feedback) {
            this.feedback = feedback;
        }

        @Override
        public Optional<Double> metricOf(UUID releaseId, GateCatalog.Metric metric) {
            if (metric != GateCatalog.Metric.OPEN_CRITICAL_BUGS) {
                return Optional.empty();
            }
            return Optional.of(openCriticalBugs(feedback, releaseId));
        }
    }

    /** The policy engine's registrant (S10-06's PRD 33 metric vocabulary). */
    @Component
    static class Policy implements ReleasePolicyMetricSource {

        private final FeedbackRepository feedback;

        Policy(FeedbackRepository feedback) {
            this.feedback = feedback;
        }

        @Override
        public Optional<Double> metricOf(UUID releaseId, PolicyMetric metric) {
            if (metric != PolicyMetric.OPEN_CRITICAL_BUGS) {
                return Optional.empty();
            }
            return Optional.of(openCriticalBugs(feedback, releaseId));
        }
    }
}
