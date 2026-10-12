package com.shipperdog.api.authorization;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The typed condition tree of one ABAC policy (issue #70, S14-04, PRD
 * 25/33/38): attribute atoms over the decision's subject, resource,
 * action, and environment context — the SAME typed-JSON discipline
 * S10-02's conditional steps and S10-06's release-policy rules use
 * (the shared expression format the issue's technical note demands:
 * one predicate vocabulary shape across every engine, never a string
 * expression language). A condition is one of:
 *
 * <pre>{@code
 * {"allOf": [ <condition>... ]}                       — every child must hold
 * {"anyOf": [ <condition>... ]}                       — at least one child must hold
 * {"subjectOrgRole": "RELEASE_MANAGER"}               — the caller's role in the owning org
 * {"subjectPlatformAdmin": true}                      — the platform wildcard
 * {"subjectLinkedTester": true}                       — an ACTIVE tester person record
 * {"applicationName": "Banking"}                      — the addressed application's name
 * {"applicationPlatform": "ANDROID"}                  — the pinned platform
 * {"environmentName": "Production"}                   — the release's targeted environment
 * {"ringCode": "production"}                          — the ring being entered
 * {"securityStatus": "APPROVED"}                      — the security gates' standing
 * }</pre>
 *
 * <p>The atom vocabulary covers the issue's four attribute classes:
 * subject traits ({@code subject*}), resource attributes
 * ({@code application*}, {@code ringCode}), the action context
 * (carried by the policy's ACTION selector, not a condition atom),
 * and the environment context ({@code environmentName}, {@code
 * securityStatus} — the release-security-state fed from S10-03 gate
 * results). PRD 25's worked example authors exactly: a REQUIRE
 * policy with the PROMOTE action and the Banking/Production
 * selectors over {@code subjectOrgRole} and {@code securityStatus}.
 *
 * <p>{@link #parse} is the single validation authority (the write
 * surface and the console editor's server-side validation both
 * answer from it): every error is collected as a path-keyed message
 * the 400 renders in its {@code errors} member, and a malformed or
 * unknown construct never reaches storage — fail closed at write, so
 * evaluation never meets a condition it cannot answer. At EVALUATION
 * an attribute that cannot be resolved (a security status with no
 * release in the context) is UNRESOLVED, and the engine treats it
 * fail-closed (AC6): a REQUIRE it feeds refuses, and a DENY it feeds
 * refuses too — deny-override never permits on missing data, in
 * either direction.
 */
public sealed interface AbacCondition {

    /** Every child must hold. */
    record AllOf(List<AbacCondition> children) implements AbacCondition {}

    /** At least one child must hold. */
    record AnyOf(List<AbacCondition> children) implements AbacCondition {}

    /** The subject's role in the resource's owning organization (one role per membership). */
    record SubjectOrgRole(String role) implements AbacCondition {}

    /** The platform-administrator wildcard ({@code users.platform_admin}). */
    record SubjectPlatformAdmin(boolean expected) implements AbacCondition {}

    /** The subject holds an ACTIVE tester person record. */
    record SubjectLinkedTester(boolean expected) implements AbacCondition {}

    /** The addressed application's name (exact, case-sensitive — the registry's own string). */
    record ApplicationName(String name) implements AbacCondition {}

    /** The request's pinned platform (IOS or ANDROID). */
    record ApplicationPlatform(String platform) implements AbacCondition {}

    /** The release's targeted environment name. */
    record EnvironmentName(String name) implements AbacCondition {}

    /** The ring the decision addresses (a promotion's TARGET ring). */
    record RingCode(String ring) implements AbacCondition {}

    /**
     * The release-security-state from S10-03 gate results: the
     * standing of the evaluation's security-class gates (APPROVED
     * when every security gate passes or its failure is overridden —
     * the recorded decision honored; FAILED when one fails; PENDING
     * when one awaits; NO_DATA when the evaluation carries none).
     */
    record SecurityStatus(Status status) implements AbacCondition {

        /** The security-state vocabulary (the gate statuses' meaning, renamed for the PRD 25 example). */
        public enum Status {
            APPROVED,
            FAILED,
            PENDING,
            NO_DATA
        }
    }

    /** The tree's depth and size caps: an authored policy is bounded, however composed. */
    int MAX_DEPTH = 6;

    int MAX_NODES = 64;

    /** The condition rendered for audit messages and the inspect detail. */
    default String describe() {
        return switch (this) {
            case AllOf all -> "allOf(" + childrenDescribed(all.children()) + ")";
            case AnyOf any -> "anyOf(" + childrenDescribed(any.children()) + ")";
            case SubjectOrgRole role -> "subject.orgRole = " + role.role();
            case SubjectPlatformAdmin admin -> "subject.platformAdmin is " + admin.expected();
            case SubjectLinkedTester tester -> "subject.linkedTester is " + tester.expected();
            case ApplicationName name -> "application.name = " + name.name();
            case ApplicationPlatform platform -> "application.platform = " + platform.platform();
            case EnvironmentName name -> "environment.name = " + name.name();
            case RingCode ring -> "ring = " + ring.ring();
            case SecurityStatus status -> "security.status = " + status.status().name();
        };
    }

    private static String childrenDescribed(List<AbacCondition> children) {
        StringBuilder rendered = new StringBuilder();
        for (AbacCondition child : children) {
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
     * its vocabulary (org roles, platforms, security states), the
     * combinators non-empty and bounded by {@code MAX_DEPTH}/{@code
     * MAX_NODES}. Errors append to {@code errors} path-keyed
     * ("condition" for the root, "condition.allOf[0].subjectOrgRole"
     * for a child's atom); a node that failed validation is skipped,
     * never guessed at.
     */
    static AbacCondition parse(Object json, String path, List<String> errors) {
        if (!(json instanceof Map<?, ?> node)) {
            errors.add(path + ": a condition must be a JSON object");
            return null;
        }
        Object allOf = node.get("allOf");
        Object anyOf = node.get("anyOf");
        Object subjectOrgRole = node.get("subjectOrgRole");
        Object subjectPlatformAdmin = node.get("subjectPlatformAdmin");
        Object subjectLinkedTester = node.get("subjectLinkedTester");
        Object applicationName = node.get("applicationName");
        Object applicationPlatform = node.get("applicationPlatform");
        Object environmentName = node.get("environmentName");
        Object ringCode = node.get("ringCode");
        Object securityStatus = node.get("securityStatus");
        int named = 0;
        for (Object candidate : new Object[] {
            allOf,
            anyOf,
            subjectOrgRole,
            subjectPlatformAdmin,
            subjectLinkedTester,
            applicationName,
            applicationPlatform,
            environmentName,
            ringCode,
            securityStatus
        }) {
            if (candidate != null) {
                named++;
            }
        }
        if (named != 1) {
            errors.add(path + ": exactly one of allOf, anyOf, subjectOrgRole, subjectPlatformAdmin, "
                    + "subjectLinkedTester, applicationName, applicationPlatform, environmentName, ringCode, "
                    + "securityStatus is required (found " + named + ")");
            return null;
        }
        if (allOf != null) {
            return combinator("allOf", allOf, path, errors, true);
        }
        if (anyOf != null) {
            return combinator("anyOf", anyOf, path, errors, false);
        }
        if (subjectOrgRole != null) {
            if (!(subjectOrgRole instanceof String role) || role.isBlank()) {
                errors.add(path + ".subjectOrgRole: an organization role is required");
                return null;
            }
            String candidate = role.trim().toUpperCase(Locale.ROOT);
            if (!KNOWN_ORG_ROLES.contains(candidate)) {
                errors.add(path + ".subjectOrgRole: unknown organization role '" + role + "'");
                return null;
            }
            return new SubjectOrgRole(candidate);
        }
        if (subjectPlatformAdmin != null) {
            if (!(subjectPlatformAdmin instanceof Boolean expected)) {
                errors.add(path + ".subjectPlatformAdmin: must be true or false");
                return null;
            }
            return new SubjectPlatformAdmin(expected);
        }
        if (subjectLinkedTester != null) {
            if (!(subjectLinkedTester instanceof Boolean expected)) {
                errors.add(path + ".subjectLinkedTester: must be true or false");
                return null;
            }
            return new SubjectLinkedTester(expected);
        }
        if (applicationName != null) {
            if (!(applicationName instanceof String name) || name.isBlank()) {
                errors.add(path + ".applicationName: an application name is required");
                return null;
            }
            return new ApplicationName(name.trim());
        }
        if (applicationPlatform != null) {
            if (!(applicationPlatform instanceof String platform) || platform.isBlank()) {
                errors.add(path + ".applicationPlatform: a platform (IOS or ANDROID) is required");
                return null;
            }
            String candidate = platform.trim().toUpperCase(Locale.ROOT);
            if (!candidate.equals("IOS") && !candidate.equals("ANDROID")) {
                errors.add(path + ".applicationPlatform: unknown platform '" + platform + "' (IOS or ANDROID)");
                return null;
            }
            return new ApplicationPlatform(candidate);
        }
        if (environmentName != null) {
            if (!(environmentName instanceof String name) || name.isBlank()) {
                errors.add(path + ".environmentName: an environment name is required");
                return null;
            }
            return new EnvironmentName(name.trim());
        }
        if (ringCode != null) {
            if (!(ringCode instanceof String ring) || ring.isBlank()) {
                errors.add(path + ".ringCode: a ring code is required");
                return null;
            }
            return new RingCode(ring.trim().toLowerCase(Locale.ROOT));
        }
        if (!(securityStatus instanceof String status) || status.isBlank()) {
            errors.add(path + ".securityStatus: a security status is required");
            return null;
        }
        try {
            return new SecurityStatus(SecurityStatus.Status.valueOf(
                    securityStatus.toString().trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            errors.add(path + ".securityStatus: unknown security status '" + securityStatus
                    + "' (APPROVED, FAILED, PENDING, NO_DATA)");
            return null;
        }
    }

    /** The organization-role vocabulary the write surface validates against (the matrix's own). */
    List<String> KNOWN_ORG_ROLES = List.of(
            "ORG_ADMIN",
            "ORG_MEMBER",
            "APP_OWNER",
            "DEVELOPER",
            "QA_ENGINEER",
            "RELEASE_MANAGER",
            "SECURITY_REVIEWER",
            "COMPLIANCE_REVIEWER",
            "VIEWER");

    private static AbacCondition combinator(
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
        List<AbacCondition> parsed = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            AbacCondition child = parse(list.get(i), path + "." + kind + "[" + i + "]", errors);
            if (child != null) {
                parsed.add(child);
            }
        }
        if (parsed.size() != list.size()) {
            return null;
        }
        return all ? new AllOf(List.copyOf(parsed)) : new AnyOf(List.copyOf(parsed));
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
    static int nodeCountOf(AbacCondition condition) {
        return switch (condition) {
            case AllOf all ->
                1 + all.children().stream().mapToInt(AbacCondition::nodeCountOf).sum();
            case AnyOf any ->
                1 + any.children().stream().mapToInt(AbacCondition::nodeCountOf).sum();
            default -> 1;
        };
    }

    /** Whether a gate type is security-class (the builtin mobile-security-scan and any organization definition naming security). */
    static boolean isSecurityGate(String gateType) {
        return gateType != null && gateType.toLowerCase(Locale.ROOT).contains("security");
    }
}
