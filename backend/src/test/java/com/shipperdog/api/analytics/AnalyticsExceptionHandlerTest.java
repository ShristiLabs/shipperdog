package com.shipperdog.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * The handler's retry hint rendering (issue #72, S15-01 review round
 * 1): the ingest's write-contention mapping is 503 PLUS the
 * {@code Retry-After} header its exception carries — and every other
 * analytics failure renders exactly as before, with no hint.
 */
class AnalyticsExceptionHandlerTest {

    private final AnalyticsExceptionHandler handler = new AnalyticsExceptionHandler();

    @Test
    void writeContentionAnswers503WithTheRetryAfterHint() {
        ResponseEntity<ProblemDetail> response =
                handler.handle(new AnalyticsException(AnalyticsException.Code.INGEST_WRITE_CONTENTION, "race", 5));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties().get("code")).isEqualTo("INGEST_WRITE_CONTENTION");
    }

    @Test
    void ordinaryFailuresCarryNoRetryAfterHint() {
        ResponseEntity<ProblemDetail> response = handler.handle(
                new AnalyticsException(AnalyticsException.Code.INVALID_REQUEST, "shape", Map.of("events", "offender")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties().get("errors")).isEqualTo(Map.of("events", "offender"));
    }
}
