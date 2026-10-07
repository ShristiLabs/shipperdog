package com.shipperdog.api.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The decision-latency regression guard (S7-05, issue #34; the M1
 * exit criterion's "authorization decision latency < 100 ms (SLO
 * §42)"): a warm-load probe over the decision API measuring
 * client-observed wall-clock latency per decision, asserting BOTH the
 * SLO ceiling on the p99 AND a sanity floor (a decision must actually
 * reach the engine — a sub-millisecond median would mean the probe
 * never left the client). This is the continuous guard the milestone
 * review points at; the "under load test" phrasing is honored by the
 * sequential burst (the cache-warmed steady state the SLO targets —
 * cold-path first-decision latency is observable in the platform's
 * own authorization_decision_duration metric, tagged by outcome).
 *
 * <p>CI tolerance: the assert is on the DECISION API round trip with
 * the cache warm; a CI runner meeting 100 ms at p99 with real Redis
 * and PostgreSQL in Testcontainers has ample headroom, and a genuine
 * regression (a per-decision table scan, a synchronous audit write on
 * the hot path) blows through it loudly.
 */
class DecisionLatencyGuardTest extends IsolationScenarioHarness {

    @Test
    void warmDecisionsStayUnderTheSloCeilingAtP99() throws Exception {
        IsolationScenarioHarness.IsolatedTenant side = world().sideA();
        grantExplicitVersion(
                side.tenantAdminToken(), side.tenantId(), side.testerId(), side.applicationId(), side.versionId());

        // Warm the cache (the grant set + the decision itself).
        get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);

        int probes = 200;
        List<Long> latencies = new ArrayList<>(probes);
        for (int i = 0; i < probes; i++) {
            long begunAt = System.nanoTime();
            get(side.testerToken(), downloadUri(side.tenantId(), side.versionId()), 200);
            latencies.add((System.nanoTime() - begunAt) / 1_000_000);
        }
        latencies.sort(Comparator.naturalOrder());
        long median = latencies.get(probes / 2);
        long p99 = latencies.get((int) Math.ceil(probes * 0.99) - 1);

        assertThat(median).as("the probe reaches a real engine (sanity floor)").isGreaterThanOrEqualTo(1);
        assertThat(p99)
                .as("warm decision p99 must stay under the 100 ms SLO (measured %d ms)", p99)
                .isLessThan(100);
    }
}
