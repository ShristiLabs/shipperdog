package com.shipperdog.api.distribution;

import com.shipperdog.api.config.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Distribution-module domain exception (issue #61, S13-01). The
 * handler in {@code com.shipperdog.api.distribution.DistributionExceptionHandler}
 * renders {@link #problem()} -- the RFC 7807 envelope carrying the
 * stable public {@link #code} -- so services never touch servlet
 * types (issue #8). The adapter-taxonomy codes ({@code ADAPTER_*})
 * map {@link AdapterException} categories per the taxonomy (AC5):
 * they ride distribution records as failure codes, and render as
 * problem codes should a dispatch surface ever expose them over HTTP
 * (the dispatch itself is event-driven -- a store failure fails the
 * record, never the release's ACTIVE crossing).
 */
public class DistributionException extends RuntimeException {

    public enum Code {
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        /** The permission target's concealed miss (the organization the binding surface addresses). */
        ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Resource not found."),
        /** The app-level binding's application miss: unknown, foreign, or of another organization (concealed). */
        APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Resource not found."),
        /** The environment-level binding's environment miss: unknown, foreign, or of another organization (concealed). */
        ENVIRONMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "ENVIRONMENT_NOT_FOUND", "Resource not found."),
        /** The addressed credential binding's miss (concealed across organizations). */
        DISTRIBUTION_CREDENTIAL_NOT_FOUND(
                HttpStatus.NOT_FOUND, "DISTRIBUTION_CREDENTIAL_NOT_FOUND", "Resource not found."),
        /** The addressed distribution record's miss (concealed across organizations). */
        DISTRIBUTION_RECORD_NOT_FOUND(HttpStatus.NOT_FOUND, "DISTRIBUTION_RECORD_NOT_FOUND", "Resource not found."),
        /**
         * The environment-level binding names an environment the
         * application does not target in the chain's shape (an
         * environment binding must name its application; PRD 25's
         * levels are org, app, app+env).
         */
        DISTRIBUTION_CREDENTIAL_LEVEL_INVALID(
                HttpStatus.BAD_REQUEST, "DISTRIBUTION_CREDENTIAL_LEVEL_INVALID", "The credential level is invalid."),
        /**
         * The adapter failure taxonomy's terminal answer for
         * {@code TRANSIENT}/{@code PERMANENT} categories (AC5): the
         * per-adapter retries are exhausted or the fault was final.
         * Rides the distribution record's {@code failure_code}; a
         * problem code for any future dispatch surface.
         */
        ADAPTER_FAILED(HttpStatus.BAD_GATEWAY, "ADAPTER_FAILED", "The distribution channel failed."),
        /**
         * The taxonomy's terminal answer for {@code RATE_LIMITED}:
         * the provider throttled every attempt.
         */
        ADAPTER_RATE_LIMITED(
                HttpStatus.SERVICE_UNAVAILABLE, "ADAPTER_RATE_LIMITED", "The distribution channel is rate limited."),
        /**
         * The taxonomy's terminal answer for {@code TIMEOUT}: the
         * per-adapter timeout cut every attempt.
         */
        ADAPTER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "ADAPTER_TIMEOUT", "The distribution channel timed out."),
        /**
         * The taxonomy's answer for {@code AUTHENTICATION}: never
         * retried (retrying a bad credential is a lockout generator);
         * the detail never carries material.
         */
        ADAPTER_AUTHENTICATION_FAILED(
                HttpStatus.BAD_GATEWAY, "ADAPTER_AUTHENTICATION_FAILED", "The channel credentials were rejected."),
        /** The dispatch reached an unregistered channel reference (a configuration fault, recorded loudly). */
        CHANNEL_UNKNOWN(HttpStatus.BAD_REQUEST, "CHANNEL_UNKNOWN", "The distribution channel is not registered."),
        /**
         * The group-mapping write's shape or target fault (S13-02):
         * an unknown application, tester group, or bundle id; a
         * provider group that does not exist and was not asked to be
         * created. The message names the fault's fact, never a
         * foreign row (concealment).
         */
        DISTRIBUTION_GROUP_LINK_INVALID(
                HttpStatus.BAD_REQUEST, "DISTRIBUTION_GROUP_LINK_INVALID", "The group link request is invalid."),
        /** The addressed group link's miss (concealed across organizations, S13-02). */
        DISTRIBUTION_GROUP_LINK_NOT_FOUND(
                HttpStatus.NOT_FOUND, "DISTRIBUTION_GROUP_LINK_NOT_FOUND", "Resource not found."),
        /** The addressed submission-metadata row's miss (concealed across organizations, S13-03). */
        DISTRIBUTION_METADATA_NOT_FOUND(HttpStatus.NOT_FOUND, "DISTRIBUTION_METADATA_NOT_FOUND", "Resource not found."),
        /**
         * The addressed release carries no phased release on the store
         * side (S13-03, AC3): the release's channel never enabled one,
         * or the curve has none to read — a read answers this honestly
         * rather than an invented curve, a control answers it rather
         * than a fabricated provider call.
         */
        DISTRIBUTION_PHASED_RELEASE_NOT_FOUND(
                HttpStatus.NOT_FOUND, "DISTRIBUTION_PHASED_RELEASE_NOT_FOUND", "Resource not found."),
        /**
         * The phased-release control's shape fault (S13-03, AC3): an
         * action outside the pause/resume/halt vocabulary, or an
         * action the curve's current state does not accept.
         */
        DISTRIBUTION_PHASED_RELEASE_ACTION_INVALID(
                HttpStatus.BAD_REQUEST,
                "DISTRIBUTION_PHASED_RELEASE_ACTION_INVALID",
                "The phased release action is invalid."),
        /**
         * The addressed release carries no staged rollout on the
         * channel (S13-05, AC1/AC2): the release's production
         * dispatch never landed a rollout (a testing-track release,
         * a full-list release, or a foreign release — concealed). A
         * read answers this honestly rather than an invented curve.
         */
        DISTRIBUTION_ROLLOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "DISTRIBUTION_ROLLOUT_NOT_FOUND", "Resource not found."),
        /**
         * The rollout control's shape fault (S13-05, AC1/AC2): an
         * action outside the advance/halt/resume/reapply vocabulary,
         * a percentage outside 1..100 or not strictly greater than
         * the curve's current intent, or an action the rollout's
         * current state does not accept.
         */
        DISTRIBUTION_ROLLOUT_ACTION_INVALID(
                HttpStatus.BAD_REQUEST, "DISTRIBUTION_ROLLOUT_ACTION_INVALID", "The rollout action is invalid."),
        /**
         * The policy engine's refusal of a rollout increase (S13-05,
         * AC4): the production ring's BLOCK/HOLD rules evaluated
         * against the release refuse to widen the curve while the
         * release's metrics fail their thresholds — the promotion
         * question's own wording, applied to the rollout's
         * promotion-grade act. The message carries the rule's
         * reference and evidence.
         */
        DISTRIBUTION_ROLLOUT_BLOCKED(
                HttpStatus.CONFLICT, "DISTRIBUTION_ROLLOUT_BLOCKED", "The rollout increase is blocked by policy."),
        /**
         * The rollout control's concurrent-edit refusal (S13-05): the
         * curve row changed under the action (the optimistic {@code
         * version} column, the #51 subscription precedent) — the stale
         * action answers the public 409 instead of last-write-winning,
         * so two concurrent ADVANCEs can never lower the curve or
         * double-apply a step. The operator reloads and retries.
         */
        DISTRIBUTION_ROLLOUT_CONFLICT(
                HttpStatus.CONFLICT,
                "DISTRIBUTION_ROLLOUT_CONFLICT",
                "The rollout changed concurrently; reload and retry."),
        /**
         * The addressed release carries no staged tenant rollout plan
         * (S13-06, #66): the release never authored one, or a foreign
         * release — concealed. A read answers this honestly rather
         * than an invented plan.
         */
        DISTRIBUTION_STAGED_ROLLOUT_NOT_FOUND(
                HttpStatus.NOT_FOUND, "DISTRIBUTION_STAGED_ROLLOUT_NOT_FOUND", "Resource not found."),
        /**
         * The staged plan authoring's shape or target fault (S13-06,
         * #66, AC1): an unknown mode, a stage list without a
         * full-coverage final stage, a percentage outside 1..100, an
         * explicit list naming a non-target tenant or shrinking the
         * previous cohort, or authoring against a release already
         * distributing. The message names the fault's fact, never a
         * foreign row (concealment).
         */
        DISTRIBUTION_STAGED_ROLLOUT_PLAN_INVALID(
                HttpStatus.BAD_REQUEST,
                "DISTRIBUTION_STAGED_ROLLOUT_PLAN_INVALID",
                "The staged rollout plan is" + " invalid."),
        /**
         * The staged rollout action's shape fault (S13-06, #66): an
         * action outside the advance/rollback vocabulary, an advance
         * the plan's current state does not accept (not started, the
         * final stage reached), or a rollback without its mandatory
         * reason.
         */
        DISTRIBUTION_STAGED_ROLLOUT_ACTION_INVALID(
                HttpStatus.BAD_REQUEST,
                "DISTRIBUTION_STAGED_ROLLOUT_ACTION_INVALID",
                "The staged rollout action is invalid."),
        /**
         * A staged advancement refused (S13-06, #66, AC2): the
         * policy engine's BLOCK/HOLD denial of the widening (the same
         * composed refusal the promotion 409 renders), or — the
         * dispatch-failure rollback ownership doctrine since #61 —
         * FAILED dispatch records on the release's channels that own
         * the refusal until they are resolved. The message carries
         * the rule's reference and evidence or the failure codes.
         */
        DISTRIBUTION_STAGED_ROLLOUT_BLOCKED(
                HttpStatus.CONFLICT,
                "DISTRIBUTION_STAGED_ROLLOUT_BLOCKED",
                "The staged rollout advancement is blocked."),
        /**
         * The staged plan's concurrent-edit or duplicate-authoring
         * refusal (S13-06, #66): the plan row changed under the
         * action (the optimistic {@code version} stamp) or the
         * release already carries its one plan — the stale or second
         * authoring answers the public 409 instead of
         * last-write-winning.
         */
        DISTRIBUTION_STAGED_ROLLOUT_CONFLICT(
                HttpStatus.CONFLICT,
                "DISTRIBUTION_STAGED_ROLLOUT_CONFLICT",
                "The staged rollout changed concurrently or is already planned; reload and retry."),
        FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied.");

        final HttpStatus httpStatus;
        final String code;
        final String detail;

        Code(HttpStatus httpStatus, String code, String detail) {
            this.httpStatus = httpStatus;
            this.code = code;
            this.detail = detail;
        }

        /** The stable public code string (cross-package readers: the record's failure codes, the taxonomy mapping). */
        public String codeValue() {
            return code;
        }

        /** The HTTP status the problem envelope renders. */
        public HttpStatus status() {
            return httpStatus;
        }
    }

    private final Code code;

    public DistributionException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    /**
     * The RFC 7807 envelope for this failure (issue #8). The detail is
     * the exception's own message when it carries one — the provider's
     * actionable words a refused submission must surface (S13-03,
     * AC4) — and the code's stable vocabulary otherwise.
     */
    public ProblemDetail problem() {
        return ApiProblems.of(
                code.httpStatus,
                code.code,
                code.httpStatus.getReasonPhrase(),
                getMessage() == null || getMessage().isBlank() ? code.detail : getMessage());
    }
}
