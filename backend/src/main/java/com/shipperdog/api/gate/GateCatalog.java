package com.shipperdog.api.gate;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The built-in gate catalog of FR-026 (issue #42, S10-03, PRD 32/33):
 * the seven scan/test kinds the PRD names, each with its
 * expected-result SCHEMA — the shape of result a gate of that type
 * legitimately reports (a binary pass/fail, or a numeric value checked
 * against a threshold/ceiling the definition carries as data). The
 * catalog is CODE, not rows: the types are reference vocabulary every
 * organization shares, and their schemas are stable contracts the
 * definition config and the result submissions are validated against.
 *
 * <p>{@code gate_type} itself stays the V1 open vocabulary (varchar,
 * integration-defined kinds — the deliberate ADR-0001-era decision
 * {@code release_gates} documents): a type outside this catalog is a
 * legitimate definition whose schema defaults to {@link
 * ResultSchema#PASS_FAIL} — binary reporting, no config, no threshold
 * — so an integration's custom gate needs no platform release to
 * exist. The catalog only describes what the platform KNOWS about the
 * kinds it names.
 *
 * <p>FR-026's list also names "Approvals (QA / Product / Security)" —
 * that gate is the approval workflow engine S10-01/S10-02 shipped at
 * the APPROVAL_PENDING crossing, a different mechanism with its own
 * routes and audit family; this catalog deliberately does not
 * duplicate it (documented in docs/releases.md).
 *
 * <p>Canonical type codes are lowercase kebab ({@code unit-tests},
 * {@code coverage-threshold}), the ring/environment naming shape;
 * {@link #normalize} maps user input onto them.
 */
public final class GateCatalog {

    /** The expected-result shape a gate type reports. */
    public enum ResultSchema {

        /** A binary externally-reported outcome: pass or fail, evidence link, no numeric value. */
        PASS_FAIL,
        /** A numeric value that must be {@code >=} the definition's threshold (coverage, crash-free rate). */
        NUMERIC_MIN,
        /** A numeric value that must be {@code <=} the definition's ceiling (open critical bugs, typically 0). */
        COUNT_MAX
    }

    /**
     * The analytics-backed metrics a gate can resolve at evaluation time
     * (the {@link GateMetricSource} vocabulary). {@code
     * BUNDLE_CRASH_FREE_RATE} (S16-05, issue #167) is the OTA
     * discipline's own crash-free standing: the rate over the release's
     * CURRENT BUNDLE's sessions, not the release-attributed pool at
     * large — the promotion decision an OTA ring makes is about the
     * bundle it is widening.
     */
    public enum Metric {
        CRASH_FREE_RATE,
        BUNDLE_CRASH_FREE_RATE,
        OPEN_CRITICAL_BUGS
    }

    /** One catalog entry: the type code, its schema, and its metric binding when analytics owns the value. */
    public record Entry(String type, ResultSchema schema, Metric metric) {

        /** Whether the gate's value resolves from analytics, never from manual submission. */
        public boolean metricBacked() {
            return metric != null;
        }
    }

    /** The FR-026 catalog: unit/integration tests, the three scans, and the three threshold/ceiling gates. */
    private static final Map<String, Entry> CATALOG = buildCatalog();

    private static Map<String, Entry> buildCatalog() {
        Map<String, Entry> catalog = new LinkedHashMap<>();
        catalog.put("unit-tests", new Entry("unit-tests", ResultSchema.PASS_FAIL, null));
        catalog.put("integration-tests", new Entry("integration-tests", ResultSchema.PASS_FAIL, null));
        catalog.put("sast", new Entry("sast", ResultSchema.PASS_FAIL, null));
        catalog.put("dependency-scan", new Entry("dependency-scan", ResultSchema.PASS_FAIL, null));
        catalog.put("mobile-security-scan", new Entry("mobile-security-scan", ResultSchema.PASS_FAIL, null));
        catalog.put("coverage-threshold", new Entry("coverage-threshold", ResultSchema.NUMERIC_MIN, null));
        catalog.put(
                "crash-free-threshold",
                new Entry("crash-free-threshold", ResultSchema.NUMERIC_MIN, Metric.CRASH_FREE_RATE));
        // S16-05, issue #167 (ADR-0010 workstream 3): the OTA rings'
        // "crash-free sessions on current bundle >= X%" — the same
        // NUMERIC_MIN percentage surface, resolved against the bundle
        // the release carries rather than the release's whole attributed
        // pool.
        catalog.put(
                "bundle-crash-free-threshold",
                new Entry("bundle-crash-free-threshold", ResultSchema.NUMERIC_MIN, Metric.BUNDLE_CRASH_FREE_RATE));
        catalog.put(
                "open-critical-bugs",
                new Entry("open-critical-bugs", ResultSchema.COUNT_MAX, Metric.OPEN_CRITICAL_BUGS));
        // Insertion order IS the catalog order (unmodifiable, never
        // Map.copyOf — that copy loses the deterministic iteration the
        // surfaces and tests rely on).
        return java.util.Collections.unmodifiableMap(catalog);
    }

    private GateCatalog() {}

    /** The built-in entries in catalog order (the reference vocabulary, rendered by the definition surfaces). */
    public static List<Entry> builtins() {
        return List.copyOf(CATALOG.values());
    }

    /** The built-in entry of a canonical type code, when the catalog knows the kind. */
    public static Optional<Entry> builtinOf(String type) {
        return Optional.ofNullable(type == null ? null : CATALOG.get(type));
    }

    /** The schema a type reports: the catalog's own, or PASS_FAIL for an integration-defined kind (the open vocabulary default). */
    public static ResultSchema schemaOf(String type) {
        return builtinOf(type).map(Entry::schema).orElse(ResultSchema.PASS_FAIL);
    }

    /** Whether the type's value resolves from analytics at evaluation time (manual submission is refused). */
    public static boolean metricBacked(String type) {
        return builtinOf(type).map(Entry::metricBacked).orElse(false);
    }

    /** Whether the type's results carry a numeric observed value against the definition's config. */
    public static boolean numeric(String type) {
        ResultSchema schema = schemaOf(type);
        return schema == ResultSchema.NUMERIC_MIN || schema == ResultSchema.COUNT_MAX;
    }

    /**
     * Canonicalizes a gate type: trimmed, lowercased — the definition
     * and submission surfaces normalize identically so a release's
     * rows, the definitions, and the catalog can never disagree on
     * which kind they mean (the ring-code precedent).
     */
    public static String normalize(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }

    /** The shape every canonical type must hold (the definitions' constraint, checked here for one vocabulary). */
    public static boolean wellFormed(String normalizedType) {
        return normalizedType != null && normalizedType.matches("^[a-z][a-z0-9-]{0,63}$");
    }

    /** The metric-backed built-ins' type codes (the fail-closed family; rendered for docs and tests). */
    public static Set<String> metricBackedTypes() {
        Set<String> types = new LinkedHashSet<>();
        CATALOG.values().forEach(entry -> {
            if (entry.metricBacked()) {
                types.add(entry.type());
            }
        });
        return types;
    }
}
