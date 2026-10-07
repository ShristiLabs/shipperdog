package com.shipperdog.api.workflow;

import com.shipperdog.api.release.ReleaseException;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders workflow-module failures in the platform's single error
 * envelope (issue #8, #40): RFC 7807 problem+json with a stable public
 * code and the request's trace_id. Internal exception messages are
 * logged, never echoed. Serves the approval surface; permission
 * denials conceal through the authorization module's handler (the
 * approval action's permission target is the application, the
 * definition routes' the organization).
 *
 * <p>The release module's rejections — the application-miss backstop
 * of the approval surface's profile resolution — arrive as that
 * module's own exception; this advice re-renders its problem envelope
 * verbatim (the one-renderer-per-route precedent, the release
 * handler's VersionException shape), so a miss is byte-identical
 * through either module.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = WorkflowController.class)
public class WorkflowExceptionHandler {

    @ExceptionHandler(WorkflowException.class)
    ResponseEntity<ProblemDetail> handle(WorkflowException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }

    /** The release module's application-miss backstop, re-rendered verbatim (see class javadoc). */
    @ExceptionHandler(ReleaseException.class)
    ResponseEntity<ProblemDetail> handle(ReleaseException exception) {
        return ResponseEntity.status(exception.problem().getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
