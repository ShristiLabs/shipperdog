package com.shipperdog.api.otabundle;

import com.shipperdog.api.artifact.ArtifactException;
import com.shipperdog.api.version.VersionException;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders otabundle-module failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. The module's whole vocabulary — bundle,
 * signing-key, and app-token codes — renders here, and the
 * neighboring modules' rejections the bundle flow provokes — the
 * version module's creation guards (the semver name vocabulary, the
 * unique-label race) and the artifact module's session machinery
 * (expiry, size ceilings, storage) — are re-rendered verbatim under
 * their own codes, the same one-renderer-per-route discipline the
 * artifact module's advice established.
 */
@Order(0)
@RestControllerAdvice(
        assignableTypes = {
            OtaBundleController.class,
            OtaAppTokenController.class,
            OtaRolloutController.class,
            OtaCheckController.class,
            OtaAlertController.class
        })
public class OtaBundleExceptionHandler {

    @ExceptionHandler(OtaBundleException.class)
    ResponseEntity<ProblemDetail> handle(OtaBundleException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }

    /** The version module's creation rejections, re-rendered verbatim. */
    @ExceptionHandler(VersionException.class)
    ResponseEntity<ProblemDetail> handle(VersionException exception) {
        return ResponseEntity.status(exception.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }

    /** The artifact module's session rejections, re-rendered verbatim. */
    @ExceptionHandler(ArtifactException.class)
    ResponseEntity<ProblemDetail> handle(ArtifactException exception) {
        return ResponseEntity.status(exception.problem().getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }
}
