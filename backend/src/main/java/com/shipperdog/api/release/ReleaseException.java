package com.shipperdog.api.release;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Release-module domain exception (issue #55, S9-01). The handler in
 * {@code com.shipperdog.api.release.ReleaseExceptionHandler} renders
 * {@link #problem()} — the RFC 7807 envelope carrying the stable public
 * {@link #code} — so services never touch servlet types (issue #8).
 *
 * <p>{@code RELEASE_TARGETS_INVALID} is the platform's first 422: target
 * validation is a semantic failure of an otherwise well-formed request
 * (issue AC2's per-tenant errors), not a syntax problem (400) and not a
 * conflict with stored state (409) — the field-to-message {@code errors}
 * member carries one entry per offending target so a Release Manager
 * fixes the whole target set in one pass. {@code VERSION_ARCHIVED} and
 * {@code VERSION_REVOKED} arrive as the version module's own exception
 * and are re-rendered verbatim by the handler (the artifact module's
 * S5-01 precedent for one renderer per route); {@code ARTIFACT_NOT_FOUND}
 * is this module's own value of the artifact read's public code (the
 * {@code APPLICATION_ARCHIVED} pattern: one public code, the surface
 * that emits it owns its value).
 */
public class ReleaseException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "Resource not found."),
        /**
         * The application-miss backstop of {@code profileOf}: through HTTP
         * the permission check conceals an unknown or foreign application
         * as this same public code before the service runs, so the
         * backstop keeps the registry's one vocabulary.
         */
        APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Resource not found."),
        /** The version-miss of creation: unknown, foreign, or not the addressed application's version. */
        VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "VERSION_NOT_FOUND", "Resource not found."),
        /**
         * The environment miss of creation (S9-04, issue #58 AC4): unknown,
         * foreign, or of another organization than the application's — this
         * surface's own value of the environment module's public code (the
         * {@code APPLICATION_NOT_FOUND} pattern), so the concealed miss is
         * byte-identical through either module.
         */
        ENVIRONMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND", "Resource not found."),
        /**
         * The named environment is DISABLED (S9-04): a parked environment
         * may not be named by a new release — reactivation is the one
         * crossing out.
         */
        ENVIRONMENT_DISABLED(HttpStatus.CONFLICT, "ENVIRONMENT_DISABLED", "The environment is disabled."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        /** The artifact miss of creation: unknown, foreign, or not of the release's version (concealed — the artifact read's own shape). */
        ARTIFACT_NOT_FOUND(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND", "Resource not found."),
        APPLICATION_ARCHIVED(
                HttpStatus.CONFLICT,
                "APPLICATION_ARCHIVED",
                "The application is archived; new releases cannot be created."),
        /**
         * One release per application and version (V1's
         * {@code uq_releases_application_version} is the authority; the
         * pre-check and the lost-race translation answer it identically).
         */
        RELEASE_ALREADY_EXISTS(
                HttpStatus.CONFLICT,
                "RELEASE_ALREADY_EXISTS",
                "A release already exists for this application and version."),
        /**
         * The ring reference does not resolve in the organization's ring
         * vocabulary (issue AC4): unknown code, or creation attempted
         * before the ring definitions exist.
         */
        RELEASE_RING_UNKNOWN(
                HttpStatus.BAD_REQUEST, "RELEASE_RING_UNKNOWN", "The ring is not defined for the organization."),
        /**
         * A target tenant does not hold the application, or a target
         * tester group does not belong to its target tenant (issue AC2/
         * AC3): one 422 carrying every offending target, keyed by tenant
         * or group id in the {@code errors} member.
         */
        RELEASE_TARGETS_INVALID(
                HttpStatus.UNPROCESSABLE_ENTITY, "RELEASE_TARGETS_INVALID", "One or more release targets are invalid."),
        /**
         * A selected artifact is not READY (issue AC1: the artifact must
         * be READY per S5-05's vocabulary) — one 422 carrying every
         * offending platform in the {@code errors} member.
         */
        RELEASE_ARTIFACT_NOT_READY(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "RELEASE_ARTIFACT_NOT_READY",
                "One or more selected artifacts are not READY."),
        /**
         * The OTA-channel bundle rule of S16-05 (issue #167, ADR-0010
         * D1): a release on an {@code ota}-channel ring carries exactly
         * one SIGNED OTA bundle as its one artifact slot, its version
         * the bundle's own versions row (D2) — zero bundles, more than
         * one bundle, a not-SIGNED bundle, or a bundle's bytes on a
         * store-channel ring are all this one 422, the {@code errors}
         * member carrying the offending facts.
         */
        RELEASE_OTA_BUNDLE_INVALID(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "RELEASE_OTA_BUNDLE_INVALID",
                "An OTA-channel release requires exactly one SIGNED bundle; bundle bytes belong on no other release."),
        /**
         * The publish crossing lost its bundle (S16-05): the bundle was
         * SIGNED at validation but is not at the linkage write — a
         * concurrent revoke or second publish won the race. The precise
         * 409; the create attempt resolves fresh on retry.
         */
        RELEASE_OTA_BUNDLE_UNAVAILABLE(
                HttpStatus.CONFLICT,
                "RELEASE_OTA_BUNDLE_UNAVAILABLE",
                "The bundle is no longer available to publish; re-validate and retry."),
        /**
         * The crossing is not one of PRD 17's edges (issue #56 AC1): a
         * conflict with the stored state, answered 409 with the
         * {@code errors} member carrying {@code current} and
         * {@code attempted} — including the self-crossing (re-sending a
         * state the release already holds) and every exit from a
         * terminal (FAILED, ROLLED_BACK, COMPLETED are final).
         */
        RELEASE_TRANSITION_INVALID(
                HttpStatus.CONFLICT,
                "RELEASE_TRANSITION_INVALID",
                "The release cannot transition to the requested state."),
        /**
         * A failure or rollback crossing without its mandatory reason
         * (issue #56 AC3, the S4-03 revoke analog): a semantic failure
         * of an otherwise well-formed request — 422, the {@code errors}
         * member naming the {@code reason} field.
         */
        RELEASE_TRANSITION_REASON_REQUIRED(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "RELEASE_TRANSITION_REASON_REQUIRED",
                "A reason is required for failure and rollback transitions."),
        /**
         * A registered {@code ReleaseTransitionPolicy} refused the
         * crossing (issue #56's guard seam): 422 keyed by the refusing
         * contributor's name in the {@code errors} member. No
         * contributor ships in M2 (the approval evaluator is S10-01);
         * the code lands with the seam so a denial is never a 500.
         */
        RELEASE_TRANSITION_BLOCKED(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "RELEASE_TRANSITION_BLOCKED",
                "A transition policy refuses this transition."),
        /**
         * The addressed release resolved (same application, in scope)
         * but has no frozen manifest — it has not crossed into
         * APPROVED yet, or predates the manifest surface (S9-03). An
         * honest state-dependent miss, never a concealment: the
         * release itself is readable, only the manifest is absent.
         */
        RELEASE_MANIFEST_NOT_FOUND(
                HttpStatus.NOT_FOUND, "RELEASE_MANIFEST_NOT_FOUND", "No manifest is frozen for this release."),
        /**
         * A manifest already exists for the release (S9-03 AC1): the
         * document is write-once — V26's one-per-release constraint is
         * the authority, and the forward-only state machine cannot
         * re-enter APPROVED, so a second freeze is doubly impossible.
         * Any surface that ever reaches this code is a bug answering
         * loudly, never a silent overwrite.
         */
        RELEASE_MANIFEST_IMMUTABLE(
                HttpStatus.CONFLICT, "RELEASE_MANIFEST_IMMUTABLE", "The release manifest is immutable."),
        /**
         * The stored manifest failed read-time verification (S9-03):
         * the re-canonicalized content does not hash to the stored
         * digest — storage corruption or tampering. The platform
         * detects drift loudly and never repairs or serves the
         * document (INV-003/004 discipline applied to the manifest
         * itself); an operator investigates, the row is never
         * silently rewritten.
         */
        RELEASE_MANIFEST_CORRUPT(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "RELEASE_MANIFEST_CORRUPT",
                "The stored release manifest failed integrity verification."),
        /**
         * The promotion precheck refused the hop (issue #44 AC1, PRD
         * 33): the target ring's gates, approvals, eligibility, or a
         * registered promotion policy is unmet — 409 with the COMPLETE
         * blocker list in the {@code errors} member, every blocker
         * named with its evidence (the gate type and its evidence URL,
         * the approval round and the steps it waits on, the tenant the
         * ring's eligibility excludes). The refused attempt is itself
         * recorded: a BLOCKED row on the append-only promotions
         * timeline with this same list.
         */
        RELEASE_PROMOTION_BLOCKED(
                HttpStatus.CONFLICT,
                "RELEASE_PROMOTION_BLOCKED",
                "The promotion is blocked; see the errors member for the complete blocker list."),
        /**
         * Promotion serves live distribution only (issue #44): the
         * release's status is not ACTIVE — 409 naming the current
         * status in the {@code errors} member. A release reaches its
         * audience through PRD 17's machine (the transitions route);
         * ring hops widen a distribution that is already running.
         */
        RELEASE_PROMOTION_INVALID_STATE(
                HttpStatus.CONFLICT,
                "RELEASE_PROMOTION_INVALID_STATE",
                "Only an ACTIVE release can be promoted ring-to-ring."),
        /**
         * The move set sits on more than one ring (issue #44): staggered
         * targets (an earlier selective promotion) cannot hop as one —
         * 409 naming each tenant's current ring; the operator promotes
         * each ring group (or names the tenants explicitly).
         */
        RELEASE_PROMOTION_RING_SPLIT(
                HttpStatus.CONFLICT,
                "RELEASE_PROMOTION_RING_SPLIT",
                "The promotion's targets sit on more than one ring; promote them per ring."),
        /**
         * There is no next ring (issue #44): the move set sits on the
         * final ring of the organization's effective vocabulary — the
         * progression is complete. A release whose every target sits
         * here has already crossed to PROMOTED (PRD 17).
         */
        RELEASE_PROMOTION_RING_FINAL(
                HttpStatus.CONFLICT, "RELEASE_PROMOTION_RING_FINAL", "The targets already sit on the final ring."),
        /**
         * The named target ring is beyond the next ring (issue #44 AC6):
         * skipping rings is DISALLOWED by default — the effective
         * order's position+1 is the only hop. The relaxation knob is a
         * promotion policy (the S10-06 seam); none ships, so the
         * default stands for every organization until one registers.
         */
        RELEASE_PROMOTION_RING_SKIP(
                HttpStatus.CONFLICT,
                "RELEASE_PROMOTION_RING_SKIP",
                "Skipping rings is disallowed; promotion moves one ring at a time."),
        /**
         * The hop lost its compare-and-set (issue #44, the V14 CAS
         * discipline applied to ring moves): a concurrent promotion
         * moved a target row first. The caller re-reads and retries —
         * the precise conflict, never a silent partial move.
         */
        RELEASE_PROMOTION_CONFLICT(
                HttpStatus.CONFLICT,
                "RELEASE_PROMOTION_CONFLICT",
                "A concurrent promotion moved the targets first; re-read and retry."),
        /**
         * One rule name per organization (issue #45, S10-06; V34's
         * {@code uq_release_policy_rules_org_name} is the authority;
         * the pre-check and the lost-race translation answer it
         * identically, the ring-code precedent).
         */
        POLICY_RULE_NAME_TAKEN(
                HttpStatus.CONFLICT,
                "POLICY_RULE_NAME_TAKEN",
                "A policy rule with this name already exists for the organization."),
        /**
         * A rule the hold ledger references cannot be deleted (issue
         * #45): the ledger is audit evidence — the rule's holds record
         * governed decisions that must keep their reference. Disable
         * the rule instead ({@code enabled = false}); the evaluation
         * log's name snapshots survive either way.
         */
        POLICY_RULE_IN_USE(
                HttpStatus.CONFLICT,
                "POLICY_RULE_IN_USE",
                "The rule is referenced by recorded policy holds; disable it instead of deleting it."),
        /**
         * A hold can be acknowledged once (issue #45, S10-06 AC2):
         * the recorded decision STANDS for later hops of the same
         * release into the same ring — re-acknowledging is not a
         * thing, and a second actor's intent is a new hop's question.
         */
        POLICY_HOLD_ALREADY_ACKNOWLEDGED(
                HttpStatus.CONFLICT,
                "POLICY_HOLD_ALREADY_ACKNOWLEDGED",
                "The hold is already acknowledged; its decision stands.");

        final HttpStatus httpStatus;
        final String code;
        final String detail;

        Code(HttpStatus httpStatus, String code, String detail) {
            this.httpStatus = httpStatus;
            this.code = code;
            this.detail = detail;
        }
    }

    private final Code code;

    private final Map<String, String> errors;

    public ReleaseException(Code code, String message) {
        this(code, message, Map.of());
    }

    /** A rejection carrying a field-to-message report for the {@code errors} member (the 422 family). */
    public ReleaseException(Code code, String message, Map<String, String> errors) {
        super(message);
        this.code = code;
        this.errors = Map.copyOf(errors);
    }

    public Code code() {
        return code;
    }

    /** The RFC 7807 envelope for this failure (issue #8), with the {@code errors} member when present. */
    public ProblemDetail problem() {
        ProblemDetail problem =
                ApiProblems.of(code.httpStatus, code.code, code.httpStatus.getReasonPhrase(), code.detail);
        return ApiProblems.withErrors(problem, errors);
    }
}
