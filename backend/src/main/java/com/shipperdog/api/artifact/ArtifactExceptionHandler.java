package com.shipperdog.api.artifact;

import com.shipperdog.api.version.VersionException;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders artifact-module failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Internal exception messages are logged,
 * never echoed. Serves the artifact surface of issue #20 (S5-01);
 * permission denials conceal as the 404-shaped envelope through the
 * authorization module's handler (the permission target of every
 * artifact route is the version, resolved application-ward by the
 * evaluator).
 *
 * <p>Lifecycle rejections — {@code VERSION_IMMUTABLE} under a frozen
 * version, the precise 404 of an unknown version — arrive as the
 * version module's own exception (the upload path calls the
 * {@code VersionDistributionGuard}, whose rejections are that module's
 * contract); the version module's advice is bound to its own
 * controllers, so this advice re-renders the exception's own problem
 * envelope here — same code, same shape, one renderer per route.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = {ArtifactController.class})
public class ArtifactExceptionHandler {

    @ExceptionHandler(ArtifactException.class)
    ResponseEntity<ProblemDetail> handle(ArtifactException exception) {
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
}
