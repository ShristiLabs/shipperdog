package com.shipperdog.api.workflow;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Workflow-module domain exception (issue #40, S10-01, FR-016): the
 * approval engine's failures in the platform's single RFC 7807
 * envelope, rendered by {@code WorkflowExceptionHandler} so services
 * never touch servlet types (issue #8).
 *
 * <p>The code families: the concealed 404s ({@code WORKFLOW_NOT_FOUND}
 * for definitions, {@code RELEASE_NOT_FOUND} for the approval surface —
 * the release module's own public code re-rendered by this surface so
 * a miss is byte-identical through either module, the
 * {@code APPLICATION_NOT_FOUND} pattern); the request-shape 400/422
 * family ({@code INVALID_REQUEST}, {@code WORKFLOW_RING_UNKNOWN}, the
 * reject-reason rule); and the approval-state conflicts — out-of-order
 * decisions and non-restartable rounds answer the precise 409 carrying
 * {@code current} (and {@code attempted} where the request named a
 * step), the {@code RELEASE_TRANSITION_INVALID} shape; a caller who
 * holds no approval authority at all never reaches these: the route's
 * coarse permission gate concealed-denies them first.
 */
public class WorkflowException extends RuntimeException {

    public enum Code {
        NOT_FOUND(HttpStatus.NOT_FOUND, "WORKFLOW_NOT_FOUND", "Resource not found."),
        /**
         * The organization-miss backstop of definition creation: through
         * HTTP the permission check conceals an unknown or foreign
         * organization as this same public code before the service runs
         * (the {@code org:approval-workflows:manage} target), so the
         * backstop keeps the registry's one vocabulary.
         */
        ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "Resource not found."),
        /**
         * The release miss of the approval surface: unknown, out of
         * scope, or of another application than the body addressed —
         * the release module's public code, this surface's own value of
         * it, so the concealed miss is byte-identical through either
         * module.
         */
        RELEASE_NOT_FOUND(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "Resource not found."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        /**
         * The ring attachment does not resolve in the organization's
         * ring vocabulary (the creation surface's own 400, this
         * surface's value of {@code RELEASE_RING_UNKNOWN}'s rule).
         */
        WORKFLOW_RING_UNKNOWN(
                HttpStatus.BAD_REQUEST, "WORKFLOW_RING_UNKNOWN", "The ring is not defined for the organization."),
        /**
         * One name and one ring attachment per organization (V28's two
         * unique guards; the pre-check and the lost-race translation
         * answer identically).
         */
        WORKFLOW_ALREADY_EXISTS(
                HttpStatus.CONFLICT,
                "WORKFLOW_ALREADY_EXISTS",
                "An approval workflow already exists with this name or ring."),
        /**
         * The decision does not address the chain's current step
         * (issue AC2): sequential chains decide step 1, then 2, then on
         * — an attempt past the current step, at an already-decided
         * step, or against a terminal round answers 409 with the
         * {@code errors} member carrying the round's {@code current}
         * position (and {@code attempted} when the request named one).
         */
        APPROVAL_STEP_OUT_OF_ORDER(
                HttpStatus.CONFLICT,
                "APPROVAL_STEP_OUT_OF_ORDER",
                "The approval decision does not address the current step."),
        /**
         * The release is not sitting in APPROVAL_PENDING with a live
         * (PENDING) round: approvals act on the approval moment only —
         * a release elsewhere in PRD 17's graph, or a round already
         * SATISFIED or REJECTED, answers 409 with the {@code errors}
         * member carrying the {@code current} status.
         */
        APPROVAL_NOT_ACTIVE(
                HttpStatus.CONFLICT, "APPROVAL_NOT_ACTIVE", "The release is not awaiting approval decisions."),
        /**
         * A rejected round is the only restartable one (the rework
         * path): restarting a PENDING round is a no-op at best and a
         * history rewrite at worst, and a SATISFIED round already did
         * its work — both answer 409.
         */
        APPROVAL_ROUND_NOT_RESTARTABLE(
                HttpStatus.CONFLICT,
                "APPROVAL_ROUND_NOT_RESTARTABLE",
                "Only a rejected approval round can be restarted."),
        /**
         * The caller holds an approval authority (the route's coarse
         * gate passed) but not THIS step's: only a holder of the step's
         * role may decide it (issue AC2). A precise, non-concealing
         * denial — the release is already visible to the caller; the
         * answer names the required role and permission so the right
         * approver is findable.
         */
        APPROVAL_ROLE_NOT_HELD(
                HttpStatus.FORBIDDEN,
                "APPROVAL_ROLE_NOT_HELD",
                "The caller does not hold the role this step requires."),
        /**
         * The release's creator may not approve their own release
         * (issue AC5, the delegation rule): self-approval is blocked
         * whatever roles the creator holds.
         */
        APPROVAL_SELF_APPROVAL(
                HttpStatus.FORBIDDEN,
                "APPROVAL_SELF_APPROVAL",
                "The release creator may not approve their own release."),
        /**
         * A rejection explains itself (issue AC6, the S4-03 revoke
         * analog): the reason is mandatory on REJECT, optional on
         * APPROVE — 422 naming the {@code reason} field.
         */
        APPROVAL_REJECT_REASON_REQUIRED(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "APPROVAL_REJECT_REASON_REQUIRED",
                "A reason is required to reject an approval step.");

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

    public WorkflowException(Code code, String message) {
        this(code, message, Map.of());
    }

    /** A rejection carrying a field-to-message report for the {@code errors} member (the 422 family). */
    public WorkflowException(Code code, String message, Map<String, String> errors) {
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
