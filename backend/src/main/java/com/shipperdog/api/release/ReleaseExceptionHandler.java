package com.shipperdog.api.release;

import com.shipperdog.api.version.VersionException;
import com.shipperdog.api.workflow.WorkflowException;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders release-module failures in the platform's single error envelope
 * (issue #8): RFC 7807 problem+json with a stable public code and the
 * request's trace_id. Internal exception messages are logged, never
 * echoed. Serves the release surface of issue #55 (S9-01); permission
 * denials conceal as {@code APPLICATION_NOT_FOUND} through the
 * authorization module's handler (the permission target of every release
 * route is the application), and a genuine release miss renders
 * {@code RELEASE_NOT_FOUND} here.
 *
 * <p>The version module's lifecycle rejections — a release may not
 * target an ARCHIVED or REVOKED version — arrive as that module's own
 * exception; this advice re-renders its problem envelope verbatim, the
 * one-renderer-per-route precedent the artifact module set in S5-01.
 * The artifact miss reuses the artifact read's public code through this
 * module's own value of it (the {@code APPLICATION_ARCHIVED} pattern:
 * one public code, the surface that emits it owns its value).
 *
 * <p>The workflow module's refusal — S10-02's fail-closed resolution:
 * the targeted environment's named approval policy does not resolve —
 * composes into the READY to APPROVAL_PENDING crossing through the
 * transition listener, so it escapes through THIS module's transition
 * route and is re-rendered verbatim here, the same precedent (the
 * workflow surface's own advice serves only its own routes).
 */
@Order(0)
@RestControllerAdvice(assignableTypes = {ReleaseController.class, ReleasePolicyController.class})
public class ReleaseExceptionHandler {

    @ExceptionHandler(ReleaseException.class)
    ResponseEntity<ProblemDetail> handle(ReleaseException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }

    /** The version module's lifecycle rejections, re-rendered verbatim (see class javadoc). */
    @ExceptionHandler(VersionException.class)
    ResponseEntity<ProblemDetail> handle(VersionException exception) {
        return ResponseEntity.status(exception.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }

    /** The workflow module's composed crossing refusal, re-rendered verbatim (see class javadoc). */
    @ExceptionHandler(WorkflowException.class)
    ResponseEntity<ProblemDetail> handle(WorkflowException exception) {
        return ResponseEntity.status(exception.problem().getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
