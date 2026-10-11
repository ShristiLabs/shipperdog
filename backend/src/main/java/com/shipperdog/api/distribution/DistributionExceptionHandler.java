package com.shipperdog.api.distribution;

import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders distribution-module failures in the platform's single error
 * envelope (issue #8): RFC 7807 problem+json with a stable public code
 * and the request's trace_id. Serves the channel/credential/record
 * surface of issue #61 (S13-01) and the TestFlight connector's mapping
 * surface (S13-02); permission denials conceal as the target
 * resource's own 404 through the authorization module's handler, and
 * this module's concealed misses render identically here.
 */
@Order(0)
@RestControllerAdvice(assignableTypes = DistributionController.class)
public class DistributionExceptionHandler {

    @ExceptionHandler(DistributionException.class)
    ResponseEntity<ProblemDetail> handle(DistributionException exception) {
        return ResponseEntity.status(exception.code().httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(exception.problem());
    }

    /**
     * A provider call the mapping or sync surface made failed through
     * the adapter taxonomy (S13-02): the same closed codes the
     * distribution records carry, rendered as the problem the console
     * shows — the message never carries credential material (the
     * taxonomy's detail is the channel's own words).
     */
    @ExceptionHandler(AdapterException.class)
    ResponseEntity<ProblemDetail> handle(AdapterException exception) {
        com.shipperdog.api.distribution.DistributionException.Code code = switch (exception.category()) {
            case TIMEOUT -> com.shipperdog.api.distribution.DistributionException.Code.ADAPTER_TIMEOUT;
            case RATE_LIMITED -> com.shipperdog.api.distribution.DistributionException.Code.ADAPTER_RATE_LIMITED;
            case AUTHENTICATION ->
                com.shipperdog.api.distribution.DistributionException.Code.ADAPTER_AUTHENTICATION_FAILED;
            case TRANSIENT, PERMANENT -> com.shipperdog.api.distribution.DistributionException.Code.ADAPTER_FAILED;
        };
        return ResponseEntity.status(code.httpStatus)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(com.shipperdog.api.config.ApiProblems.of(
                        code.httpStatus, code.code, code.httpStatus.getReasonPhrase(), exception.getMessage()));
    }
}
