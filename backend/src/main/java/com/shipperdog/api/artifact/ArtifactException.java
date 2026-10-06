package com.shipperdog.api.artifact;

import com.shipperdog.api.config.ApiProblems;
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

    public ArtifactException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    /** The RFC 7807 envelope for this failure (issue #8). */
    public ProblemDetail problem() {
        return ApiProblems.of(code.httpStatus, code.code, code.httpStatus.getReasonPhrase(), code.detail);
    }
}
