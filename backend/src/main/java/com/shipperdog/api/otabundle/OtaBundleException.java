package com.shipperdog.api.otabundle;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Otabundle-module domain exception (S16-03, issue #165). The handler
 * in {@code com.shipperdog.api.otabundle.OtaBundleExceptionHandler}
 * renders {@link #problem()} — the RFC 7807 envelope carrying the
 * stable public {@link #code} — so services never touch servlet types
 * (issue #8's single-envelope rule).
 *
 * <p>Neighboring modules' rejections surface through their own codes:
 * the version module's creation guards (the semver name vocabulary,
 * the unique-label race) answer as {@code VersionException}, and the
 * artifact module's session machinery (expiry, size ceilings, storage)
 * answers as {@code ArtifactException} — the handler re-renders both
 * verbatim, the same one-renderer-per-route discipline the artifact
 * module's advice established.
 */
public class OtaBundleException extends RuntimeException {

    public enum Code {
        BUNDLE_NOT_FOUND(HttpStatus.NOT_FOUND, "BUNDLE_NOT_FOUND", "Resource not found."),
        APPLICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Resource not found."),
        SIGNING_KEY_NOT_FOUND(HttpStatus.NOT_FOUND, "SIGNING_KEY_NOT_FOUND", "Resource not found."),
        TOKEN_NOT_FOUND(HttpStatus.NOT_FOUND, "TOKEN_NOT_FOUND", "Resource not found."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        APPLICATION_NOT_OTA_ENABLED(
                HttpStatus.CONFLICT,
                "APPLICATION_NOT_OTA_ENABLED",
                "The application has not enabled OTA bundle updates; enable OTA before authoring bundles."),
        BUNDLE_NOT_REVOKABLE(HttpStatus.CONFLICT, "BUNDLE_NOT_REVOKABLE", "Only a SIGNED bundle can be revoked."),
        ROLLOUT_NOT_FOUND(HttpStatus.NOT_FOUND, "ROLLOUT_NOT_FOUND", "Resource not found."),
        ROLLOUT_CONFLICT(
                HttpStatus.CONFLICT,
                "ROLLOUT_CONFLICT",
                "The rollout plan already exists or changed concurrently; reload and retry."),
        ROLLOUT_ACTION_INVALID(
                HttpStatus.CONFLICT, "ROLLOUT_ACTION_INVALID", "The action is not valid for the plan's standing."),
        ROLLOUT_BLOCKED(
                HttpStatus.CONFLICT, "ROLLOUT_BLOCKED", "The advancement is blocked: resolve the policy rule first."),
        ROLLOUT_RELEASE_NOT_LIVE(
                HttpStatus.CONFLICT,
                "ROLLOUT_RELEASE_NOT_LIVE",
                "The release is not distributing; a rollout plan commences on a live (ACTIVE or PROMOTED) release."),
        ROLLOUT_RELEASE_NOT_OTA(
                HttpStatus.CONFLICT,
                "ROLLOUT_RELEASE_NOT_OTA",
                "The release carries no OTA bundle; rollout plans belong to OTA releases."),
        SIGNING_UNAVAILABLE(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SIGNING_UNAVAILABLE",
                "Bundle signing is unavailable; retry the finalize."),
        ALERT_RULE_NOT_FOUND(HttpStatus.NOT_FOUND, "ALERT_RULE_NOT_FOUND", "Resource not found."),
        ALERT_RULE_HAS_FIRINGS(
                HttpStatus.CONFLICT,
                "ALERT_RULE_HAS_FIRINGS",
                "The rule has firing history; keep the rule (update it) so the trail stays addressable.");

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

    private final Map<String, String> fieldErrors;

    public OtaBundleException(Code code, String message) {
        this(code, message, Map.of());
    }

    public OtaBundleException(Code code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Code code() {
        return code;
    }

    /** The field-level report, empty when the failure names no field. */
    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }

    /**
     * The RFC 7807 envelope for this failure (issue #8). The detail is
     * the exception's own message when one was authored -- this
     * module's messages are deliberate safe-for-callers prose carrying
     * the refusal's specifics (a policy rule's evidence, a stage's
     * constraint), the {@code DistributionException} discipline -- and
     * the code's stable static detail otherwise.
     */
    public ProblemDetail problem() {
        return ApiProblems.withErrors(
                ApiProblems.of(
                        code.httpStatus,
                        code.code,
                        code.httpStatus.getReasonPhrase(),
                        getMessage() == null || getMessage().isBlank() ? code.detail : getMessage()),
                fieldErrors);
    }
}
