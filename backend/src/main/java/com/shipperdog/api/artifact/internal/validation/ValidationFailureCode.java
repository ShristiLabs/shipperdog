package com.shipperdog.api.artifact.internal.validation;

/**
 * The stable failure vocabulary of the validation pipeline (issue #24,
 * S5-05): one code per deterministic stage failure, recorded on the
 * artifact's validation document and rendered by the API so a FAILED
 * artifact always answers "which stage, which code, what reason" —
 * the story's actionable-failure criterion. Codes are stored, not
 * HTTP: the HTTP surface stays problem+json with the artifact's state;
 * these are the machine-readable detail underneath.
 *
 * <p>Codes group by stage in {@link ValidationStage} order. The two
 * infra conditions ({@code VALIDATION_QUEUE_FULL},
 * {@code VALIDATION_UNAVAILABLE}) are NOT stage failures: the artifact
 * stays VALIDATING and the block records in the validation document so
 * a missed validation is visible rather than a silent stall.
 */
public enum ValidationFailureCode {
    /** FILE: the stored object is gone or its size differs from the registered size. */
    FILE_MISSING,
    SIZE_MISMATCH,
    /** FORMAT: the structural container verdict contradicts the platform or extension. */
    FORMAT_MISMATCH,
    /** METADATA: extraction failed (the extraction's own code rides in the reason); this alias names the stage view. */
    METADATA_FAILED,
    /** CHECKSUM: the stored object's SHA-256 differs from the registered checksum. */
    CHECKSUM_MISMATCH,
    /** SIGNING (M1 presence/self-consistency tier): no signing material, an incoherent profile, or an expired one. */
    SIGNING_ABSENT,
    SIGNING_INCOHERENT,
    SIGNING_PROFILE_EXPIRED,
    /** APPLICATION_MATCH: extracted bundle/package differs from the registry's identifier (or extraction produced none). */
    APPLICATION_MISMATCH,
    /** VERSION_MATCH: extracted version/build differs from the version record (M1 rejects; no auto-amend). */
    VERSION_MISMATCH,
    /** Infra: the bounded validation queue refused the run (artifact stays VALIDATING). */
    VALIDATION_QUEUE_FULL,
    /** Infra: object storage was unreadable after retries (artifact stays VALIDATING). */
    VALIDATION_UNAVAILABLE
}
