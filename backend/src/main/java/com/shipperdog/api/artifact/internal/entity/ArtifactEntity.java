package com.shipperdog.api.artifact.internal.entity;

import com.shipperdog.api.tenant.OrganizationScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Maps the {@code artifacts} table (V1 baseline + the V15 ownership
 * snapshot). An artifact is a concrete immutable binary (PRD 8.5):
 * bytes in object storage under the server-derived {@code storageKey}
 * (PRD 24 layout), metadata here — never bytes in PostgreSQL (FR-007).
 *
 * <p>The row carries the same owning-dimension snapshot as its version
 * (V15, the V12 shape): {@code organizationId} always, {@code tenantId}
 * exactly when the version hangs under a tenant-owned application.
 * Artifacts join the seam — ADR-0002's ninth amendment — because they
 * break the eighth amendment's plain-child invariant twice: the
 * artifact collection route addresses them directly ({@code POST
 * /api/v1/artifacts}, FR-004), and the same-content detection spans
 * every version of the application.
 *
 * <p>INV-003 at the row level: nothing ever updates the binary fields
 * ({@code storageKey}, {@code sha256}, {@code size}) — the entity has
 * no setters at all; a re-upload is a NEW row with a NEW id and a NEW
 * key. {@code buildNumber} is null until S5-02/03 extract it (or the
 * uploader pinned it); NULLs are distinct in {@code
 * uq_artifacts_version_platform_build}, which is exactly the
 * re-upload-means-new-artifact rule, while a pinned build triple
 * lands once.
 */
@Entity
@Table(name = "artifacts")
public class ArtifactEntity implements OrganizationScope, Persistable<UUID> {

    /**
     * The id is allocated server-side BEFORE any byte moves (the
     * story's stable-path rule), so Spring Data's {@code save} would
     * MERGE an id-carrying row; {@link Persistable} tells it this row
     * is new until persisted — a fresh insert, never a merge onto
     * existing bytes (INV-003's row-level shape).
     */
    @Transient
    private boolean isNew = true;

    /**
     * Lifecycle vocabulary of the V1 baseline {@code artifact_status}
     * (PRD 18). UPLOADING is the pre-registration state of the
     * resumable path's session row; a registered artifact lands as
     * VALIDATING — its bytes are stored and checksummed — and every
     * later transition composes through the PRD 18 table of
     * {@code ArtifactStateMachine} (issue #24, S5-05): the validation
     * pipeline moves it READY or FAILED, a revalidation moves FAILED
     * back to VALIDATING, and DISTRIBUTED/ARCHIVED follow distribution
     * (E9/E13). PRD 18 has no REVOKED state: revocation is the E7
     * authorization lifecycle of INV-007, never an artifact-row state.
     */
    public enum Status {
        UPLOADING,
        VALIDATING,
        READY,
        DISTRIBUTED,
        ARCHIVED,
        FAILED
    }

    /**
     * Assigned at construction, never generated: the id exists BEFORE
     * the storage key and the bytes (the story's stable-path rule), so
     * the database's {@code gen_random_uuid} default never fires — an
     * explicit generator would make Hibernate treat the id-carrying
     * new row as detached.
     */
    @Id
    private UUID id;

    private UUID versionId;

    private UUID organizationId;

    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Platform platform;

    private String fileName;

    private String storageKey;

    /** The V1 column is {@code char(64)}: the fixed-width hex digest. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    private long size;

    private Long buildNumber;

    /**
     * The extraction tier of V16 (issue #21, S5-02, FR-005): the
     * identity fields S5-05's validation matching compares — written
     * ONCE by the extraction job, null until then (and null forever
     * for a FAILED extraction, which records {@code extractionError}
     * instead). {@code buildNumber} above stays the CI-pinned value
     * when the uploader pinned one; extraction fills it only when it
     * was null.
     */
    private String bundleId;

    private String versionName;

    private String minimumOs;

    /** The full extraction document (jsonb): signing, entitlements, timings. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String extractedMetadata;

    /**
     * The build-provenance document (jsonb, V17, issue #23, S5-04,
     * FR-005): the client-asserted git/pipeline/CI-run claims captured
     * at upload time — written ONCE with the registration insert and
     * never updated (INV-003: no setter exists; the column has no
     * writer after insert). Only supplied members are stored; NULL is
     * the honest representation of a manual upload that asserted none.
     * The document keys are the frozen S12-03 contract (see
     * {@code internal.provenance.Provenance}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    private String provenance;

    /** The actionable failure or block record (jsonb, V16; issue #21 AC3). */
    @JdbcTypeCode(SqlTypes.JSON)
    private String extractionError;

    /**
     * The structural container verdict (V18, issue #24, S5-05): IPA on
     * iOS, APK or AAB on Android — the S5-03 structural detection,
     * never the file name's claim. Written ONCE by the validation
     * pipeline's FORMAT stage (the binary is immutable, so the verdict
     * cannot change; a divergent re-observation is a fault, loudly
     * rejected). FR-006's channel rule (AAB is Play-only, direct
     * download needs APK) is enforced at distribution time against
     * this recorded fact — the upload of an AAB stays valid.
     */
    private String format;

    /**
     * The validation document (jsonb, V18, issue #24): the per-stage
     * record of the LATEST pipeline attempt — outcome, attempts
     * counter, every stage's status/reason in FR-006 order. Unlike the
     * extraction documents this one is REWRITTEN per attempt (the row
     * holds current state, the audit trail holds history).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    private String validation;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private Status status;

    private UUID uploadedBy;

    private Instant createdAt;

    private Instant updatedAt;

    protected ArtifactEntity() {}

    /**
     * A registered artifact (issue #20): the bytes are already in
     * object storage and the checksum is already computed — both
     * BEFORE this row exists, which is why {@code sha256} is NOT NULL
     * by construction (the V1 column, the #2 expand/contract note's
     * "hash before insert").
     *
     * @param id the pre-allocated id — the one the storage key already
     *     embeds, allocated server-side before any byte moved
     * @param buildNumber the uploader's pinned build, or null until
     *     S5-02/03 extract it
     * @param provenanceDocument the validated build-provenance jsonb
     *     document (issue #23), or null when the uploader asserted no
     *     provenance — written here and never after
     */
    public ArtifactEntity(
            UUID id,
            UUID versionId,
            UUID organizationId,
            UUID tenantId,
            Platform platform,
            String fileName,
            String storageKey,
            String sha256,
            long size,
            Long buildNumber,
            Status status,
            UUID uploadedBy,
            String provenanceDocument) {
        this.id = id;
        this.versionId = versionId;
        this.organizationId = organizationId;
        this.tenantId = tenantId;
        this.platform = platform;
        this.fileName = fileName;
        this.storageKey = storageKey;
        this.sha256 = sha256;
        this.size = size;
        this.buildNumber = buildNumber;
        this.status = status;
        this.uploadedBy = uploadedBy;
        this.provenance = provenanceDocument;
    }

    /** The platform vocabulary (V1 {@code platform}); one value per slot. */
    public enum Platform {
        IOS,
        ANDROID
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        isNew = false;
    }

    @PostLoad
    void onLoad() {
        isNew = false;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getId() {
        return id;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    @Override
    public UUID organizationId() {
        return organizationId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    @Override
    public UUID tenantId() {
        return tenantId;
    }

    public Platform getPlatform() {
        return platform;
    }

    public String getFileName() {
        return fileName;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getSha256() {
        return sha256;
    }

    public long getSize() {
        return size;
    }

    public Long getBuildNumber() {
        return buildNumber;
    }

    public String getBundleId() {
        return bundleId;
    }

    public String getVersionName() {
        return versionName;
    }

    public String getMinimumOs() {
        return minimumOs;
    }

    public String getExtractedMetadata() {
        return extractedMetadata;
    }

    public String getExtractionError() {
        return extractionError;
    }

    /** The structural container verdict (V18), or null before the first validation attempt. */
    public String getFormat() {
        return format;
    }

    /** The latest validation-attempt document (V18), or null before the first attempt. */
    public String getValidation() {
        return validation;
    }

    /** The frozen-key provenance document, or null when none was asserted. */
    public String getProvenance() {
        return provenance;
    }

    public Status getStatus() {
        return status;
    }

    // -------------------------------------------------------------------------
    // Extraction writes (issue #21, S5-02): once, and only once
    // -------------------------------------------------------------------------

    /** True once any extraction outcome — success, failure, or block — is recorded. */
    public boolean extractionRecorded() {
        return extractedMetadata != null || extractionError != null;
    }

    /**
     * The one successful extraction write (async job): the scalar
     * identity fields, the numeric build ONLY where the uploader did
     * not pin one, and the jsonb document. Write-once by guard — a
     * double-scheduled job is a no-op log line, never a second write
     * over an immutable-history row.
     */
    public void applyExtraction(
            String extractedBundleId,
            String extractedVersionName,
            String extractedMinimumOs,
            Long extractedBuildNumber,
            String metadataDocument) {
        requireExtractionNotRecorded();
        this.bundleId = extractedBundleId;
        this.versionName = extractedVersionName;
        this.minimumOs = extractedMinimumOs;
        if (this.buildNumber == null && extractedBuildNumber != null) {
            this.buildNumber = extractedBuildNumber;
        }
        this.extractedMetadata = metadataDocument;
    }

    /**
     * The malformed-binary outcome (issue #21 AC3): the actionable
     * error records and the status moves VALIDATING to FAILED — the
     * one transition S5-02 owns; every later transition belongs to
     * S5-05's validation pipeline. Bytes stay stored and immutable.
     */
    public void recordExtractionFailure(String errorDocument) {
        requireExtractionNotRecorded();
        this.extractionError = errorDocument;
        if (this.status == Status.VALIDATING) {
            this.status = Status.FAILED;
        }
    }

    /**
     * The infra outcome (queue full, storage unreadable after
     * retries): the error records so the missed extraction is VISIBLE,
     * and the status deliberately stays VALIDATING — the binary did
     * nothing wrong, and S5-05's pipeline (or a re-extraction follow-
     * up) decides its fate.
     */
    public void noteExtractionBlocked(String errorDocument) {
        requireExtractionNotRecorded();
        this.extractionError = errorDocument;
    }

    /**
     * The extraction state views render: PENDING (nothing recorded),
     * SUCCESS (a document), FAILED (an error on a FAILED row), or
     * BLOCKED (an error on a still-VALIDATING row).
     */
    public String extractionOutcome() {
        if (extractedMetadata != null) {
            return "SUCCESS";
        }
        if (extractionError != null) {
            return status == Status.FAILED ? "FAILED" : "BLOCKED";
        }
        return "PENDING";
    }

    private void requireExtractionNotRecorded() {
        if (extractionRecorded()) {
            throw new IllegalStateException("an extraction outcome is already recorded for artifact " + id);
        }
    }

    // -------------------------------------------------------------------------
    // Validation writes (issue #24, S5-05): the state machine's clients
    // -------------------------------------------------------------------------

    /**
     * The FORMAT stage's write-once verdict (V18): the binary is
     * immutable, so the structural format cannot change between
     * attempts — the first verdict stays, and a divergent
     * re-observation is an {@link IllegalStateException} (a parser
     * regression, never a data fact).
     */
    public void recordFormat(String structuralVerdict) {
        if (this.format == null) {
            this.format = structuralVerdict;
            return;
        }
        if (!this.format.equals(structuralVerdict)) {
            throw new IllegalStateException("artifact " + id + " already carries format " + this.format + "; "
                    + structuralVerdict + " contradicts an immutable binary");
        }
    }

    /**
     * The pipeline's terminal write (issue #24): VALIDATING moves READY
     * (every stage passed) or FAILED (a stage failed). A re-fail of an
     * already-FAILED row (the extraction tier failed it first; the
     * pipeline's METADATA stage then records why) is a legal no-op
     * transition — the document is what the write carries. The
     * transition itself composes through {@link
     * com.shipperdog.api.artifact.internal.validation.ArtifactStateMachine},
     * so nothing outside the PRD 18 table can move the status.
     */
    public void applyValidationOutcome(boolean ready, String validationDocument) {
        Status target = ready ? Status.READY : Status.FAILED;
        if (this.status != target) {
            com.shipperdog.api.artifact.internal.validation.ArtifactStateMachine.requireLegal(this.status, target);
            this.status = target;
        }
        this.validation = validationDocument;
    }

    /**
     * The infra outcome (queue full, storage unreadable after retries,
     * extraction still pending): the validation document records the
     * block so the VALIDATING row is never a silent stall, and the
     * status deliberately stays VALIDATING — infrastructure proved
     * nothing about the binary.
     */
    public void noteValidationBlocked(String validationDocument) {
        this.validation = validationDocument;
    }

    /**
     * The revalidation entry transition (issue #24's retry scope):
     * FAILED moves back to VALIDATING so the pipeline can re-run —
     * deterministic stages reproduce their verdict, but the WORLD may
     * have changed (a corrected registry identifier, recovered
     * storage), and the retry is how the corrected fact wins. A row
     * already VALIDATING is a no-op (an in-flight or blocked
     * validation re-drives without a transition).
     */
    public void beginRevalidation() {
        if (this.status == Status.VALIDATING) {
            return;
        }
        com.shipperdog.api.artifact.internal.validation.ArtifactStateMachine.requireLegal(
                this.status, Status.VALIDATING);
        this.status = Status.VALIDATING;
    }

    /**
     * Re-arms extraction after a BLOCKED outcome (issue #24): a BLOCKED
     * record is an infra note, not a binary verdict — the queue-full or
     * storage-unavailable marker clears so the write-once extraction
     * guard can accept a real outcome when the re-drive runs. Legal
     * only on a still-VALIDATING row whose recorded outcome is exactly
     * the blocked shape ({@code extractionError} set, no document);
     * a FAILED extraction outcome is immutable history.
     */
    public void clearExtractionBlock() {
        if (this.status != Status.VALIDATING || this.extractedMetadata != null || this.extractionError == null) {
            throw new IllegalStateException(
                    "artifact " + id + " carries no extraction block to clear (status " + this.status + ")");
        }
        this.extractionError = null;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
