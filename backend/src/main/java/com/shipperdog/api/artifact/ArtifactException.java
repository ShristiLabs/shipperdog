package com.shipperdog.api.artifact;

import com.shipperdog.api.config.ApiProblems;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Artifact-module domain exception (issue #20, S5-01). The handler in
 * {@code com.shipperdog.api.artifact.ArtifactExceptionHandler} renders
 * {@link #problem()} — the RFC 7807 envelope carrying the stable public
 * {@link #code} — so services never touch servlet types (issue #8).
 *
 * <p>Lifecycle rejections are deliberately NOT translated here: the
 * upload path calls the version module's
 * {@code VersionDistributionGuard} and lets its {@code VERSION_IMMUTABLE}
 * / {@code VERSION_NOT_FOUND} rejections surface through the version
 * module's own handler — the error registry documents exactly that
 * handoff ("the VersionDistributionGuard rejection E5's upload path
 * surfaces"). {@code APPLICATION_ARCHIVED} is the code the registry
 * reserved in issue #13 for artifact creation under an archived
 * application — this module emits it since S5-01.
 */
public class ArtifactException extends RuntimeException {

    public enum Code {
        ARTIFACT_NOT_FOUND(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND", "Resource not found."),
        UPLOAD_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "UPLOAD_SESSION_NOT_FOUND", "Resource not found."),
        DOWNLOAD_NOT_FOUND(HttpStatus.NOT_FOUND, "DOWNLOAD_NOT_FOUND", "Resource not found."),
        INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid."),
        ARTIFACT_FILE_TYPE_REJECTED(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "ARTIFACT_FILE_TYPE_REJECTED",
                "The artifact file type is not supported; expected .ipa, .apk, or .aab matching the platform."),
        ARTIFACT_TOO_LARGE(
                HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_TOO_LARGE", "The artifact exceeds the maximum upload size."),
        ARTIFACT_BUILD_TAKEN(
                HttpStatus.CONFLICT,
                "ARTIFACT_BUILD_TAKEN",
                "The build number is already in use for this version and platform."),
        ARTIFACT_NOT_VALIDATABLE(
                HttpStatus.CONFLICT,
                "ARTIFACT_NOT_VALIDATABLE",
                "Only a FAILED or still-VALIDATING artifact can be revalidated."),
        UPLOAD_SESSION_EXPIRED(
                HttpStatus.GONE, "UPLOAD_SESSION_EXPIRED", "The upload session has expired; create a new one."),
        APPLICATION_ARCHIVED(
                HttpStatus.CONFLICT,
                "APPLICATION_ARCHIVED",
                "The application is archived; new artifacts cannot be added."),
        STORAGE_UNAVAILABLE(
                HttpStatus.SERVICE_UNAVAILABLE,
                "STORAGE_UNAVAILABLE",
                "Artifact storage is unavailable; retry the upload.");

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

    /**
     * The per-field validation report of a rejection (issue #23,
     * S5-04's field-errors criterion): keys are the frozen wire names
     * ({@code git_commit}, {@code ci_run_url}, ...), values are
     * caller-safe messages. Empty unless the failure is a shape
     * rejection that names its fields.
     */
    private final Map<String, String> fieldErrors;

    public ArtifactException(Code code, String message) {
        this(code, message, Map.of());
    }

    public ArtifactException(Code code, String message, Map<String, String> fieldErrors) {
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

    /** The RFC 7807 envelope for this failure (issue #8). */
    public ProblemDetail problem() {
        return ApiProblems.withErrors(
                ApiProblems.of(code.httpStatus, code.code, code.httpStatus.getReasonPhrase(), code.detail),
                fieldErrors);
    }
}
