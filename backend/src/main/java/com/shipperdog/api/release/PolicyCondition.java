package com.shipperdog.api.release;

import com.shipperdog.api.gate.GateCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The typed condition tree of a policy rule (issue #45, S10-06, the
 * issue's technical note): the SAME format S10-02's conditional
 * approval steps use — a small typed JSON predicate vocabulary,
 * validated at write time and evaluated by code, NEVER a string
 * expression language. A condition is one of:
 *
 * <pre>{@code
 * {"allOf": [ <condition>... ]}                        — every child must hold
 * {"anyOf": [ <condition>... ]}                        — at least one child must hold
 * {"metric": "CRASH_FREE_RATE", "operator": "<", "value": 0.985}
 * {"gate": "dependency-scan", "status": "FAILED"}
 * {"approvalBlocked": true}
 * {"artifactPlatform": "ios"}
 * }</pre>
 *
 * <p>The atom vocabulary covers the four decision-input classes the
 * issue's scope names: gate results ({@code gate} — the TARGET ring's
 * live evaluation, overrides honored: an overridden gate never fires
 * a failure condition), approval state ({@code approvalBlocked} — the
 * hop's approval standing), analytics metrics ({@code metric} — PRD
 * 33's list, resolved through {@link ReleasePolicyMetricSource},
 * fail-closed on missing data), and artifact metadata ({@code
 * artifactPlatform} — the platforms the release ships). S14-04's ABAC
 * reuses THIS shape (the evaluator's typed-tree discipline), never
 * the rule store this parses for.
 *
 * <p>{@link #parse} is the single validation authority (the write
 * surfaces and the console editor's server-side validation both
 * answer from it): every error is collected as a path-keyed message
 * the 400 renders in its {@code errors} member, and a malformed or
 * unknown construct never reaches storage — fail closed at write, so
 * evaluation never meets a condition it cannot answer.
 */
public sealed interface PolicyCondition {

    /** The comparison vocabulary of a metric atom (PRD 33's comparisons, nothing more). */
    enum Operator {
        LT("<"),
        LTE("<="),
        GT(">"),
        GTE(">="),
        EQ("="),
        NEQ("!=");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        /** The rendered comparison symbol. */
        public String symbol() {
            return symbol;
        }

        public boolean matches(double left, double right) {
            return switch (this) {
                case LT -> left < right;
                case LTE -> left <= right;
                case GT -> left > right;
                case GTE -> left >= right;
                case EQ -> left == right;
                case NEQ -> left != right;
            };
        }

        static Operator of(String rendered) {
            for (Operator operator : values()) {
                if (operator.symbol().equals(rendered) || operator.name().equals(rendered)) {
                    return operator;
                }
            }
            return null;
        }
    }

    /** The live gate statuses a gate atom may test (the gate module's own vocabulary). */
    enum GateStatus {
        PASSED,
        FAILED,
        PENDING,
        NO_DATA
    }

    /** Every child must hold (PRD 33's "tester approval > threshold AND open critical bugs = 0"). */
    record AllOf(List<PolicyCondition> children) implements PolicyCondition {}

    /** At least one child must hold (PRD 33's "crash-free < threshold OR ANR rate > threshold"). */
    record AnyOf(List<PolicyCondition> children) implements PolicyCondition {}

    /**
     * One analytics comparison: the metric's live value against the
     * rule's threshold. A metric with no data does NOT answer false —
     * the atom carries the fail-closed no-data standing the tree
     * propagates (AC4).
     */
    record MetricCondition(PolicyMetric metric, Operator operator, double value) implements PolicyCondition {}

    /**
     * One gate-status test against the TARGET ring's live evaluation.
     * An OVERRIDDEN gate never fires a FAILED/NO_DATA condition: the
     * override is the audited decision the gate module already
     * recorded (S10-03 AC3), and this engine does not re-litigate it.
     * A gate absent from the ring's evaluation is a condition whose
     * subject does not exist — not met, with the absence named.
     */
    record GateCondition(String gateType, GateStatus status) implements PolicyCondition {}

    /** The hop's approval standing: fires when it blocks (or not, when {@code expected} is false). */
    record ApprovalCondition(boolean expected) implements PolicyCondition {}

    /** The release's artifact platforms: fires when the release ships the named platform. */
    record ArtifactCondition(String platform) implements PolicyCondition {}

    /** The tree's depth and size caps: an authored rule is bounded, however composed. */
    int MAX_DEPTH = 6;

    int MAX_NODES = 64;

    /** The condition rendered for audit messages (the same text the evaluation log's detail carries). */
    default String describe() {
        return switch (this) {
            case AllOf all -> "allOf(" + childrenDescribed(all.children()) + ")";
            case AnyOf any -> "anyOf(" + childrenDescribed(any.children()) + ")";
            case MetricCondition metric ->
                "metric " + metric.metric().name() + " " + metric.operator().symbol() + " " + metric.value();
            case GateCondition gate ->
                "gate " + gate.gateType() + " is " + gate.status().name();
            case ApprovalCondition approval -> "approval blocked is " + approval.expected();
            case ArtifactCondition artifact -> "artifact platform " + artifact.platform();
        };
    }

    private static String childrenDescribed(List<PolicyCondition> children) {
        StringBuilder rendered = new StringBuilder();
        for (PolicyCondition child : children) {
            if (!rendered.isEmpty()) {
                rendered.append(", ");
            }
            rendered.append(child.describe());
        }
        return rendered.toString();
    }

    /**
     * Parses and validates one condition node from its JSON shape:
     * exactly one construct per node, every reference resolved against
     * its vocabulary (metric names, operators, gate statuses), the
     * combinators non-empty and bounded by {@code MAX_DEPTH}/{@code
     * MAX_NODES}. Errors append to {@code errors} path-keyed ("condition"
     * for the root, "condition.allOf[0].metric" for a child's field);
     * a node that failed validation is skipped, never guessed at.
     */
    static PolicyCondition parse(Object json, String path, List<String> errors) {
        if (!(json instanceof Map<?, ?> node)) {
            errors.add(path + ": a condition must be a JSON object");
            return null;
        }
        Object allOf = node.get("allOf");
        Object anyOf = node.get("anyOf");
        Object metric = node.get("metric");
        Object gate = node.get("gate");
        Object approval = node.get("approvalBlocked");
        Object artifact = node.get("artifactPlatform");
        int named = 0;
        for (Object candidate : new Object[] {allOf, anyOf, metric, gate, approval, artifact}) {
            if (candidate != null) {
                named++;
            }
        }
        if (named != 1) {
            errors.add(path + ": exactly one of allOf, anyOf, metric, gate, approvalBlocked, artifactPlatform"
                    + " is required (found " + named + ")");
            return null;
        }
        if (allOf != null) {
            return combinator("allOf", allOf, path, errors, true);
        }
        if (anyOf != null) {
            return combinator("anyOf", anyOf, path, errors, false);
        }
        if (metric != null) {
            return metricCondition(node, path, errors);
        }
        if (gate != null) {
            return gateCondition(node, path, errors);
        }
        if (approval != null) {
            if (!(approval instanceof Boolean expected)) {
                errors.add(path + ".approvalBlocked: must be true or false");
                return null;
            }
            return new ApprovalCondition(expected);
        }
        if (!(artifact instanceof String platform) || platform.isBlank()) {
            errors.add(path + ".artifactPlatform: a platform name is required");
            return null;
        }
        return new ArtifactCondition(platform.trim().toLowerCase(Locale.ROOT));
    }

    private static PolicyCondition combinator(
            String kind, Object children, String path, List<String> errors, boolean all) {
        if (!(children instanceof List<?> list) || list.isEmpty()) {
            errors.add(path + "." + kind + ": at least one child condition is required");
            return null;
        }
        if (list.size() > 16) {
            errors.add(path + "." + kind + ": at most 16 child conditions are allowed");
            return null;
        }
        if (depthOf(path) >= MAX_DEPTH) {
            errors.add(path + ": conditions nest at most " + MAX_DEPTH + " levels deep");
            return null;
        }
        List<PolicyCondition> parsed = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            PolicyCondition child = parse(list.get(i), path + "." + kind + "[" + i + "]", errors);
            if (child != null) {
                parsed.add(child);
            }
        }
        if (parsed.size() != list.size()) {
            return null;
        }
        return all ? new AllOf(List.copyOf(parsed)) : new AnyOf(List.copyOf(parsed));
    }

    private static PolicyCondition metricCondition(Map<?, ?> node, String path, List<String> errors) {
        Object name = node.get("metric");
        Object operator = node.get("operator");
        Object value = node.get("value");
        if (!(name instanceof String metricName)) {
            errors.add(path + ".metric: a metric name is required");
            return null;
        }
        PolicyMetric metric;
        try {
            metric = PolicyMetric.valueOf(metricName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            errors.add(path + ".metric: unknown metric '" + metricName + "'");
            return null;
        }
        if (!(operator instanceof String operatorText)) {
            errors.add(path + ".operator: an operator (<, <=, >, >=, =, !=) is required");
            return null;
        }
        Operator parsedOperator = Operator.of(operatorText.trim());
        if (parsedOperator == null) {
            errors.add(path + ".operator: unknown operator '" + operatorText + "'");
            return null;
        }
        if (!(value instanceof Number number)) {
            errors.add(path + ".value: a numeric threshold is required");
            return null;
        }
        return new MetricCondition(metric, parsedOperator, number.doubleValue());
    }

    private static PolicyCondition gateCondition(Map<?, ?> node, String path, List<String> errors) {
        Object type = node.get("gate");
        Object status = node.get("status");
        if (!(type instanceof String gateType) || gateType.isBlank()) {
            errors.add(path + ".gate: a gate type is required");
            return null;
        }
        if (!(status instanceof String statusText)) {
            errors.add(path + ".status: a gate status (PASSED, FAILED, PENDING, NO_DATA) is required");
            return null;
        }
        GateStatus parsedStatus;
        try {
            parsedStatus = GateStatus.valueOf(statusText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            errors.add(path + ".status: unknown gate status '" + statusText + "'");
            return null;
        }
        return new GateCondition(GateCatalog.normalize(gateType), parsedStatus);
    }

    /** The nesting depth of a rendered path (the combinator recursion bound's arithmetic). */
    private static int depthOf(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '[') {
                depth++;
            }
        }
        return depth;
    }

    /** The node count of a parsed tree (the MAX_NODES bound the write surface enforces). */
    static int nodeCountOf(PolicyCondition condition) {
        return switch (condition) {
            case AllOf all ->
                1
                        + all.children().stream()
                                .mapToInt(PolicyCondition::nodeCountOf)
                                .sum();
            case AnyOf any ->
                1
                        + any.children().stream()
                                .mapToInt(PolicyCondition::nodeCountOf)
                                .sum();
            default -> 1;
        };
    }
}
