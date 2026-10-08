package com.shipperdog.api.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The built-in catalog's contract (issue #42, S10-03, AC1, FR-026):
 * the seven scan/test kinds the PRD names with their expected-result
 * schemas, the open-vocabulary default for integration-defined kinds,
 * and the canonicalization every surface shares.
 */
class GateCatalogTest {

    @Test
    void fr026CatalogCarriesExpectedResultSchemas() {
        List<String> types =
                GateCatalog.builtins().stream().map(GateCatalog.Entry::type).toList();
        assertThat(types)
                .containsExactly(
                        "unit-tests",
                        "integration-tests",
                        "sast",
                        "dependency-scan",
                        "mobile-security-scan",
                        "coverage-threshold",
                        "crash-free-threshold",
                        "open-critical-bugs");

        assertThat(GateCatalog.schemaOf("unit-tests")).isEqualTo(GateCatalog.ResultSchema.PASS_FAIL);
        assertThat(GateCatalog.schemaOf("sast")).isEqualTo(GateCatalog.ResultSchema.PASS_FAIL);
        assertThat(GateCatalog.schemaOf("coverage-threshold")).isEqualTo(GateCatalog.ResultSchema.NUMERIC_MIN);
        assertThat(GateCatalog.schemaOf("crash-free-threshold")).isEqualTo(GateCatalog.ResultSchema.NUMERIC_MIN);
        assertThat(GateCatalog.schemaOf("open-critical-bugs")).isEqualTo(GateCatalog.ResultSchema.COUNT_MAX);

        // FR-026's list names Approvals too — that gate is the
        // approval workflow engine (S10-01/02), never this catalog's.
        assertThat(types).doesNotContain("approvals");
    }

    @Test
    void openVocabularyDefaultsToBinaryReporting() {
        assertThat(GateCatalog.schemaOf("custom-scan")).isEqualTo(GateCatalog.ResultSchema.PASS_FAIL);
        assertThat(GateCatalog.metricBacked("custom-scan")).isFalse();
        assertThat(GateCatalog.numeric("custom-scan")).isFalse();
        assertThat(GateCatalog.builtinOf("custom-scan")).isEmpty();
    }

    @Test
    void metricBackedKindsAreExactlyTheAnalyticsPair() {
        assertThat(GateCatalog.metricBackedTypes()).containsExactly("crash-free-threshold", "open-critical-bugs");
        assertThat(GateCatalog.builtinOf("crash-free-threshold").orElseThrow().metric())
                .isEqualTo(GateCatalog.Metric.CRASH_FREE_RATE);
        assertThat(GateCatalog.builtinOf("open-critical-bugs").orElseThrow().metric())
                .isEqualTo(GateCatalog.Metric.OPEN_CRITICAL_BUGS);
        // Coverage is numeric but externally reported (CI submits it).
        assertThat(GateCatalog.metricBacked("coverage-threshold")).isFalse();
    }

    @Test
    void normalizationAndShapeShareOneVocabulary() {
        assertThat(GateCatalog.normalize("  SAST ")).isEqualTo("sast");
        assertThat(GateCatalog.wellFormed("unit-tests")).isTrue();
        assertThat(GateCatalog.wellFormed("a")).isTrue();
        assertThat(GateCatalog.wellFormed("Not-A-Type")).isFalse();
        assertThat(GateCatalog.wellFormed("-leading")).isFalse();
        assertThat(GateCatalog.wellFormed("")).isFalse();
        assertThat(GateCatalog.wellFormed("trailing-")).isTrue();
    }
}
