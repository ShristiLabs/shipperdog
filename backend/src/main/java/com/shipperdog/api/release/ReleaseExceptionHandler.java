package com.shipperdog.api.release;

import com.shipperdog.api.version.VersionException;
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
 */
@Order(0)
@RestControllerAdvice(assignableTypes = ReleaseController.class)
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
}
