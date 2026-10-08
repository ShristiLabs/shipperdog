package com.shipperdog.api.release;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders ring-surface failures in the platform's single error envelope
 * (issue #8): RFC 7807 problem+json with a stable public code and the
 * request's trace_id. Serves the ring routes of issue #43 (S10-04);
 * permission denials conceal as {@code ORGANIZATION_NOT_FOUND} through
 * the authorization module's handler (the permission target of every
 * organization-grain ring route is the organization), and a genuine
 * ring miss renders {@code RING_NOT_FOUND} here. The release-addressed
 * eligibility route escapes through THIS advice too (the controller
 * pair below shares the module), so its release misses keep the
 * release surface's own concealed shape through this module's codes.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = {RingController.class})
public class RingExceptionHandler {

    @ExceptionHandler(RingException.class)
    ResponseEntity<ProblemDetail> handle(RingException exception) {
        return ResponseEntity.status(exception.code().httpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }

    /** The release module's lifecycle rejections re-rendered verbatim (the one-renderer-per-route precedent). */
    @ExceptionHandler(ReleaseException.class)
    ResponseEntity<ProblemDetail> handle(ReleaseException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
