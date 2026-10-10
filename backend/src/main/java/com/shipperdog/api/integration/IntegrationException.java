package com.shipperdog.api.integration;

import com.shipperdog.api.config.ApiProblems;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Integration-surface domain exception (issue #53, S12-03, FR-022):
 * the inbound webhook receiver's refusals. The handler in {@code
 * com.shipperdog.api.integration.IntegrationExceptionHandler} renders
 * {@link #problem()} -- the RFC 7807 envelope carrying the stable
 * public {@link #code} -- so services never touch servlet types
 * (issue #8).
 *
 * <p>Deliberately one uniform 401 for every signature failure (absent
 * header, malformed header, wrong key, and no secret configured at
 * all): the receiver never reveals WHICH leg refused or whether a
 * secret is configured -- an unauthenticated caller learns nothing
 * but "not accepted", the same posture the download proxy's uniform
 * 404 set (S8-04). A malformed body of an AUTHENTICATED delivery is
 * a 400 (the sender genuinely is the configured webhook and needs
 * the diagnostic), and an oversized body is a 413 before the payload
 * is ever parsed.
 */
public class IntegrationException extends RuntimeException {

    public enum Code {
        /**
         * The delivery is not accepted: no signature header, a
         * malformed signature, a wrong key, or no secret configured.
         * Uniform for every leg -- never a hint. The shared-secret
         * family (GitHub, Jenkins, GitLab) answers this 401.
         */
        SIGNATURE_REJECTED(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_REJECTED", "Webhook delivery not accepted."),
        /**
         * The delivery is not accepted, on a provider whose own
         * contract is the 400 family (S12-04's store receivers, per
         * their acceptance criteria): an invalid or unverifiable
         * provider signature, a stale-timestamp replay, or an absent
         * provider credential. Uniform for every leg -- never a hint
         * which refused.
         */
        DELIVERY_REJECTED(HttpStatus.BAD_REQUEST, "WEBHOOK_DELIVERY_REJECTED", "Webhook delivery not accepted."),
        /** A signature-verified delivery whose body is not readable JSON. */
        PAYLOAD_MALFORMED(
                HttpStatus.BAD_REQUEST, "WEBHOOK_PAYLOAD_MALFORMED", "The webhook payload is not readable JSON."),
        /** A signature-verified delivery beyond the configured body ceiling. */
        PAYLOAD_TOO_LARGE(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "WEBHOOK_PAYLOAD_TOO_LARGE",
                "The webhook payload exceeds the configured maximum.");

        private final HttpStatus httpStatus;
        private final String publicCode;
        private final String defaultDetail;

        Code(HttpStatus httpStatus, String publicCode, String defaultDetail) {
            this.httpStatus = httpStatus;
            this.publicCode = publicCode;
            this.defaultDetail = defaultDetail;
        }

        public HttpStatus httpStatus() {
            return httpStatus;
        }

        public String publicCode() {
            return publicCode;
        }
    }

    private final Code code;

    public IntegrationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    /** The RFC 7807 envelope for this failure (issue #8). */
    public ProblemDetail problem() {
        return ApiProblems.of(code.httpStatus, code.publicCode, code.httpStatus.getReasonPhrase(), code.defaultDetail);
    }
}
