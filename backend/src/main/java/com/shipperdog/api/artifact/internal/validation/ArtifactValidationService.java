package com.shipperdog.api.artifact.internal.validation;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.application.ApplicationAccess.ApplicationProfile;
import com.shipperdog.api.artifact.ArtifactException;
import com.shipperdog.api.artifact.ArtifactStore;
import com.shipperdog.api.artifact.ArtifactValidatedEvent;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity.Platform;
import com.shipperdog.api.artifact.internal.repository.ArtifactScopedRepository;
import com.shipperdog.api.artifact.internal.validation.ValidationDocument.StageStatus;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.version.VersionAccess;
import com.shipperdog.api.version.VersionAccess.VersionOwnership;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The validation pipeline of FR-006 (issue #24, S5-05, PRD 18/42): the
 * gate between upload and usability. Registration lands an artifact as
 * VALIDATING; the extraction tier settles; and THIS pipeline then runs
 * the seven PRD-ordered stages — FILE, FORMAT, METADATA, CHECKSUM,
 * SIGNING, APPLICATION_MATCH, VERSION_MATCH — recording each one's
 * outcome on the artifact's validation document (V18 jsonb, rewritten
 * per attempt) and moving the row READY or FAILED through {@link
 * ArtifactStateMachine}, the single transition authority. Every stage
 * is idempotent and re-runnable over immutable facts (INV-003: bytes,
 * checksums, and the extraction documents are read, never written), so
 * the retry scope is exactly the {@code FAILED -> VALIDATING}
 * revalidation the story demands.
 *
 * <p>Scheduling: the extraction tier hands off at its settled phase
 * (SUCCESS and FAILED alike — a failed extraction fails the pipeline
 * at FORMAT or METADATA carrying the extraction's own code), and the
 * revalidation surface re-drives blocked or failed rows. A dedicated
 * bounded executor (never the extraction pool: the hand-off would
 * self-deadlock a shared queue) refuses loudly when full — the refusal
 * is recorded synchronously in the caller's already-bound scope as a
 * BLOCKED document (code {@code VALIDATION_QUEUE_FULL}), the artifact
 * stays VALIDATING, and the stall is visible instead of silent.
 *
 * <p>Scope discipline (ADR-0002, the extraction tier's pattern): stage
 * evaluation — storage probes and registry reads — runs on the worker
 * thread outside any scope and transaction; the scoped phase is
 * exactly the row write, audit, and metric, established through the
 * sanctioned {@link TenantScopeExecutor} with the owning dimension
 * captured at schedule time. Storage hiccups retry a bounded number of
 * times and then record BLOCKED ({@code VALIDATION_UNAVAILABLE}) —
 * infrastructure never fails a binary it could not read.
 */
@Service
public class ArtifactValidationService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactValidationService.class);

    /** Documents are plain maps of scalars; one mapper serves them all. */
    private static final ObjectMapper DOCUMENTS = new ObjectMapper();

    /** Validation outcomes recorded on the metric timer. */
    private static final String OUTCOME_SUCCESS = "success";

    private static final String OUTCOME_FAILED = "failed";

    private static final String OUTCOME_BLOCKED = "blocked";

    /**
     * The extraction failure codes that are faults of the CONTAINER —
     * not-a-ZIP, a ZIP64 the walker refuses, a corrupt central
     * directory, or no recognizable Android manifest shape. These are
     * FORMAT-stage failures: no structural verdict can exist. Every
     * other extraction code (a missing or malformed plist/manifest, an
     * oversized or encrypted entry) means the container WAS walked —
     * FORMAT passes with the verdict honestly deferred, and METADATA
     * fails carrying the extraction's own code (the join point of the
     * two async tiers).
     */
    private static final Set<String> CONTAINER_FAULTS =
            Set.of("NOT_A_ZIP", "ZIP64_UNSUPPORTED", "MALFORMED_ZIP", "UNKNOWN_FORMAT");

    private final ArtifactScopedRepository artifacts;

    private final ArtifactStore store;

    private final ApplicationAccess applications;

    private final VersionAccess versions;

    private final TenantScopeExecutor scopeExecutor;

    private final AuditEventPublisher audit;

    private final ApplicationEventPublisher events;

    private final Clock clock;

    private final TraceAttributes trace;

    private final MeterRegistry meters;

    private final ArtifactValidationProperties properties;

    private final ThreadPoolTaskExecutor executor;

    ArtifactValidationService(
            ArtifactScopedRepository artifacts,
            ArtifactStore store,
            ApplicationAccess applications,
            VersionAccess versions,
            TenantScopeExecutor scopeExecutor,
            AuditEventPublisher audit,
            ApplicationEventPublisher events,
            Clock clock,
            TraceAttributes trace,
            MeterRegistry meters,
            ArtifactValidationProperties properties,
            @Qualifier("artifactValidationExecutor") ThreadPoolTaskExecutor executor) {
        this.artifacts = artifacts;
        this.store = store;
        this.applications = applications;
        this.versions = versions;
        this.scopeExecutor = scopeExecutor;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.trace = trace;
        this.meters = meters;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * Schedules a validation attempt, called from a scope that is
     * ALREADY bound (the extraction job's settled phase or the
     * revalidation request): capture is by value — ids and the owning
     * dimension — so the job establishes its own scope for the write
     * phase and never re-reads the artifact before that.
     */
    public void schedule(ArtifactEntity artifact, VersionOwnership ownership) {
        UUID artifactId = artifact.getId();
        UUID uploaderId = artifact.getUploadedBy();
        try {
            executor.execute(() -> validate(artifactId, ownership, uploaderId));
        } catch (org.springframework.core.task.TaskRejectedException e) {
            log.warn(
                    "validation queue is full ({} entries); artifact {} left VALIDATING with VALIDATION_QUEUE_FULL",
                    properties.queueCapacity(),
                    artifactId);
            // The refusal records synchronously in the CALLER's bound
            // scope (request thread or extraction worker's scoped
            // phase): establishing another scope here is the re-scope
            // the seam forbids.
            writeBlockedInCallerScope(
                    artifactId,
                    ownership,
                    uploaderId,
                    ValidationFailureCode.VALIDATION_QUEUE_FULL,
                    "the validation queue is full (" + properties.queueCapacity()
                            + " entries); the artifact stays VALIDATING and validation did not run");
        }
    }

    // -------------------------------------------------------------------------
    // The job
    // -------------------------------------------------------------------------

    /**
     * The catch-all of last resort: an unexpected runtime failure must
     * never kill the worker silently — the artifact id lands in the
     * error log so a VALIDATING row with no validation outcome is
     * traceable, and the artifact is never moved by this path (nothing
     * proved the binary invalid). A divergent re-observation of the
     * write-once format verdict lands here too, loudly: that is a
     * parser regression, never a data fact.
     */
    private void validate(UUID artifactId, VersionOwnership ownership, UUID uploaderId) {
        try {
            validateWithRetries(artifactId, ownership, uploaderId);
        } catch (RuntimeException unexpected) {
            log.error(
                    "validation of artifact {} failed unexpectedly ({}: {}); the artifact keeps its recorded state",
                    artifactId,
                    unexpected.getClass().getSimpleName(),
                    unexpected.getMessage(),
                    unexpected);
        }
    }

    private void validateWithRetries(UUID artifactId, VersionOwnership ownership, UUID uploaderId) {
        int attempts = Math.max(1, properties.storageRetryAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                RowFacts facts = readFacts(artifactId, ownership, uploaderId);
                if (facts == null) {
                    return;
                }
                Attempt outcome = evaluate(artifactId, ownership, facts);
                writeOutcome(artifactId, ownership, uploaderId, outcome);
                return;
            } catch (ArtifactException infra) {
                if (attempt == attempts) {
                    log.warn(
                            "validation of artifact {} could not read storage after {} attempts: {}",
                            artifactId,
                            attempts,
                            infra.getMessage());
                    writeBlocked(
                            artifactId,
                            ownership,
                            uploaderId,
                            ValidationFailureCode.VALIDATION_UNAVAILABLE,
                            "artifact storage could not be read during validation (" + infra.getMessage()
                                    + "); the artifact stays VALIDATING and validation did not complete");
                    return;
                }
                if (!sleepBeforeRetry(attempt)) {
                    writeBlocked(
                            artifactId,
                            ownership,
                            uploaderId,
                            ValidationFailureCode.VALIDATION_UNAVAILABLE,
                            "validation was interrupted before storage recovered; the artifact stays VALIDATING");
                    return;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Stage evaluation: pure over immutable facts, storage probes only
    // -------------------------------------------------------------------------

    /**
     * The scoped fact read (ADR-0002: the isolation seam refuses a row
     * read off the executor thread, exactly as it refuses the
     * extraction job's writes — so the job's FIRST act is a scoped
     * snapshot of the row by value, and every later phase composes
     * over the snapshot).
     */
    private RowFacts readFacts(UUID artifactId, VersionOwnership ownership, UUID uploaderId) {
        return inScope(ownership, uploaderId, () -> {
            ArtifactEntity row = artifacts.findScoped(artifactId).orElse(null);
            if (row == null) {
                log.warn("artifact {} vanished before validation could evaluate", artifactId);
                return null;
            }
            return RowFacts.of(row);
        });
    }

    /**
     * Runs the seven stages in PRD order against the row's captured
     * facts, the registry's recorded expectations, and the stored
     * object. The first FAILED stage stops the run and every later
     * stage records SKIPPED; an unsettled extraction or an unreadable
     * store closes the attempt BLOCKED (the artifact stays
     * VALIDATING).
     */
    private Attempt evaluate(UUID artifactId, VersionOwnership ownership, RowFacts row) {
        ValidationDocument document = ValidationDocument.continueFrom(row.previousValidation(), Instant.now(clock));
        ApplicationProfile application =
                applications.profileOf(ownership.applicationId()).orElse(null);
        VersionAccess.VersionRecord record =
                versions.recordOf(ownership.versionId()).orElse(null);

        // FILE: the stored object exists and its byte count is the
        // registered size — the registry describes the binary storage
        // holds.
        OptionalLong storedSize = store.sizeOf(row.storageKey());
        if (storedSize.isEmpty()) {
            return failed(
                    document,
                    ValidationStage.FILE,
                    ValidationFailureCode.FILE_MISSING,
                    "the stored object of " + "artifact " + artifactId + " is gone");
        }
        if (storedSize.getAsLong() != row.size()) {
            return failed(
                    document,
                    ValidationStage.FILE,
                    ValidationFailureCode.SIZE_MISMATCH,
                    "the stored object holds " + storedSize.getAsLong() + " bytes; the artifact registered "
                            + row.size());
        }
        document.stage(ValidationStage.FILE, StageStatus.PASSED, null, null);

        // FORMAT: the structural container verdict — the S5-03
        // detection, never the file name's claim — checked against the
        // platform and the uploaded extension. A renamed binary (an
        // AAB named .apk) fails here WITH the structural truth
        // recorded, so distribution-time channel rules can never act
        // on the name's lie.
        FormatVerdict verdict = formatVerdict(row);
        switch (verdict.outcome()) {
            case FAILED -> {
                // The verdict RECORDS even on failure: a renamed binary
                // (AAB named .apk) fails the coherence check WITH the
                // structural truth landed in the format column, so the
                // channel-format rule could act on fact even here.
                return failed(document, ValidationStage.FORMAT, verdict.code(), verdict.reason(), verdict.verdict());
            }
            case BLOCKED -> {
                return blocked(document, null, verdict.reason());
            }
            case PASSED -> {
                if (verdict.verdict() != null) {
                    document.observedFormat(verdict.verdict());
                }
                document.stage(ValidationStage.FORMAT, StageStatus.PASSED, null, verdict.reason());
            }
        }

        // METADATA: the extraction outcome must be SUCCESS — the join
        // point of the two async tiers (a payload-fault extraction
        // failed here carrying its own code; a container fault already
        // failed FORMAT above).
        if (!"SUCCESS".equals(row.extractionOutcome())) {
            return failed(
                    document,
                    ValidationStage.METADATA,
                    ValidationFailureCode.METADATA_FAILED,
                    "metadata extraction did not succeed (" + extractionErrorCode(row) + "); the artifact carries "
                            + "no extracted identity to validate against");
        }
        document.stage(ValidationStage.METADATA, StageStatus.PASSED, null, null);

        // CHECKSUM: the stored object's digest re-proves the registered
        // SHA-256 against the bytes as stored.
        ArtifactStore.StoredChecksum checksum = store.hash(row.storageKey()).orElse(null);
        if (checksum == null) {
            return failed(
                    document,
                    ValidationStage.CHECKSUM,
                    ValidationFailureCode.FILE_MISSING,
                    "the stored object of artifact " + artifactId + " vanished between the FILE stage and the digest");
        }
        if (!checksum.sha256Hex().equals(row.sha256())) {
            return failed(
                    document,
                    ValidationStage.CHECKSUM,
                    ValidationFailureCode.CHECKSUM_MISMATCH,
                    "the stored object digests to " + checksum.sha256Hex() + "; the artifact registered "
                            + row.sha256());
        }
        document.stage(ValidationStage.CHECKSUM, StageStatus.PASSED, null, null);

        // SIGNING: M1 presence and self-consistency only (the issue's
        // own scope line — trust-chain depth is M2/M3).
        SigningCheck signing = checkSigning(row, verdict.verdict());
        if (signing.code() != null) {
            return failed(document, ValidationStage.SIGNING, signing.code(), signing.reason());
        }
        document.stage(ValidationStage.SIGNING, StageStatus.PASSED, null, signing.reason());

        // APPLICATION_MATCH: the extracted bundle id / package name
        // equals the registry's registered identifier (S3-01).
        Platform platform = Platform.valueOf(row.platform());
        String expectedIdentifier = platform == Platform.IOS
                ? application == null ? null : application.bundleId()
                : application == null ? null : application.packageName();
        if (expectedIdentifier == null) {
            return failed(
                    document,
                    ValidationStage.APPLICATION_MATCH,
                    ValidationFailureCode.APPLICATION_MISMATCH,
                    "the application registry could not resolve the registered "
                            + identifierKind(platform)
                            + " of this artifact's application");
        }
        if (row.bundleId() == null || !row.bundleId().equals(expectedIdentifier)) {
            return failed(
                    document,
                    ValidationStage.APPLICATION_MATCH,
                    ValidationFailureCode.APPLICATION_MISMATCH,
                    "the uploaded " + identifierKind(platform) + " is "
                            + (row.bundleId() == null ? "absent" : "'" + row.bundleId() + "'")
                            + "; the application registered '" + expectedIdentifier + "'");
        }
        document.stage(ValidationStage.APPLICATION_MATCH, StageStatus.PASSED, null, null);

        // VERSION_MATCH: the extracted marketing version equals the
        // version record's name, and the extracted build (when a plain
        // integer) equals the CI-pinned build when the uploader pinned
        // one. M1 rejects mismatches outright — no auto-amend proposal
        // (the story's own decision).
        if (record == null) {
            return failed(
                    document,
                    ValidationStage.VERSION_MATCH,
                    ValidationFailureCode.VERSION_MISMATCH,
                    "the version record of this artifact could not be resolved");
        }
        if (row.versionName() == null || !row.versionName().equals(record.versionName())) {
            return failed(
                    document,
                    ValidationStage.VERSION_MATCH,
                    ValidationFailureCode.VERSION_MISMATCH,
                    "the extracted version is "
                            + (row.versionName() == null ? "absent" : "'" + row.versionName() + "'")
                            + "; the version record is named '" + record.versionName() + "'");
        }
        Long extractedBuild = plainInteger(documentText(row.extractedMetadata(), "buildNumberRaw"));
        if (extractedBuild != null
                && row.buildNumber() != null
                && !row.buildNumber().equals(extractedBuild)) {
            return failed(
                    document,
                    ValidationStage.VERSION_MATCH,
                    ValidationFailureCode.VERSION_MISMATCH,
                    "the uploader pinned build " + row.buildNumber() + "; the binary carries build " + extractedBuild);
        }
        document.stage(ValidationStage.VERSION_MATCH, StageStatus.PASSED, null, null);

        document.passed(Instant.now(clock));
        return new Attempt(document, true, false, verdict.verdict());
    }

    /**
     * The FORMAT stage's structural verdict: derived from the
     * extraction tier (the S5-03 detection stored in its document —
     * iOS parsing success IS the IPA verdict), checked against the
     * platform and the uploaded extension, and RECORDABLE (the
     * first-class {@code format} column) exactly when a verdict
     * exists. A container-fault extraction fails here; a payload-fault
     * extraction passes with the verdict deferred (METADATA carries
     * that failure); an unsettled extraction blocks the whole attempt.
     */
    private FormatVerdict formatVerdict(RowFacts row) {
        String outcome = row.extractionOutcome();
        if ("PENDING".equals(outcome) || "BLOCKED".equals(outcome)) {
            return FormatVerdict.blocked("extraction has not settled (" + outcome.toLowerCase(Locale.ROOT)
                    + "); validation re-drives once it does");
        }
        if ("FAILED".equals(outcome)) {
            String code = extractionErrorCode(row);
            if (CONTAINER_FAULTS.contains(code)) {
                return FormatVerdict.failed(
                        ValidationFailureCode.FORMAT_MISMATCH,
                        "extraction failed with " + code + "; no structural container verdict can be established");
            }
            return FormatVerdict.passed(
                    null, "container walked but the verdict is deferred: extraction failed in payload (" + code + ")");
        }
        String verdict = "IOS".equals(row.platform()) ? "IPA" : documentText(row.extractedMetadata(), "format");
        if (verdict == null) {
            return FormatVerdict.failed(
                    ValidationFailureCode.FORMAT_MISMATCH, "extraction succeeded without a structural format verdict");
        }
        boolean platformCoherent =
                "IOS".equals(row.platform()) ? "IPA".equals(verdict) : "APK".equals(verdict) || "AAB".equals(verdict);
        if (!platformCoherent) {
            return FormatVerdict.failed(
                    ValidationFailureCode.FORMAT_MISMATCH,
                    "the structural verdict " + verdict + " contradicts platform " + row.platform());
        }
        String claimed = claimedFormat(row.fileName());
        if (claimed != null && !claimed.equals(verdict)) {
            return FormatVerdict.failedOf(
                    verdict,
                    ValidationFailureCode.FORMAT_MISMATCH,
                    "the file name claims " + claimed + " but the binary is structurally " + verdict
                            + "; the structural verdict is recorded and the upload fails the coherence check");
        }
        return FormatVerdict.passed(verdict, null);
    }

    /**
     * The SIGNING stage: iOS requires an embedded provisioning profile
     * that is internally coherent (UUID and expiration present) and
     * unexpired; Android requires an APK signing-scheme block for APKs
     * — an AAB passes with the depth explicitly deferred (its JAR
     * signatures in {@code META-INF} are outside the S5-03 probe's
     * scope; the issue's M1 line is presence + self-consistency of
     * what the platform's marker can see).
     */
    private SigningCheck checkSigning(RowFacts row, String formatVerdict) {
        if ("IOS".equals(row.platform())) {
            JsonNode signing = documentNode(row.extractedMetadata(), "signing");
            if (signing == null || signing.isNull()) {
                return new SigningCheck(
                        ValidationFailureCode.SIGNING_ABSENT, "the iOS build carries no embedded provisioning profile");
            }
            String uuid = textOf(signing.get("profileUuid"));
            JsonNode expiresAt = signing.get("expiresAt");
            if (uuid == null || expiresAt == null || expiresAt.isNull()) {
                return new SigningCheck(
                        ValidationFailureCode.SIGNING_INCOHERENT,
                        "the embedded provisioning profile records no UUID or no expiration date");
            }
            Instant expiration = Instant.parse(expiresAt.asText());
            if (expiration.isBefore(Instant.now(clock))) {
                return new SigningCheck(
                        ValidationFailureCode.SIGNING_PROFILE_EXPIRED,
                        "the embedded provisioning profile expired at " + expiration);
            }
            return new SigningCheck(null, null);
        }
        if (!"APK".equals(formatVerdict)) {
            return new SigningCheck(
                    null,
                    "AAB JAR-signature presence is not probed in M1 (outside the S5-03 marker's scope); "
                            + "the channel-format rule reads the recorded format fact at distribution time");
        }
        JsonNode blocks = documentNode(row.extractedMetadata(), "signingBlocks");
        boolean v2 = blocks != null && booleanOf(blocks.get("v2SignatureBlock"));
        boolean v3 = blocks != null && booleanOf(blocks.get("v3SignatureBlock"));
        if (!v2 && !v3) {
            return new SigningCheck(
                    ValidationFailureCode.SIGNING_ABSENT, "the APK carries no APK Signing Scheme v2 or v3 block");
        }
        return new SigningCheck(null, null);
    }

    // -------------------------------------------------------------------------
    // The terminal writes: scoped row write + audit + metric
    // -------------------------------------------------------------------------

    private void writeOutcome(UUID artifactId, VersionOwnership ownership, UUID uploaderId, Attempt attempt) {
        if (attempt == null) {
            return;
        }
        inScope(ownership, uploaderId, () -> {
            ArtifactEntity row = artifacts.findScoped(artifactId).orElse(null);
            if (row == null) {
                log.warn("artifact {} vanished before validation could write", artifactId);
                return null;
            }
            if (attempt.formatVerdict() != null) {
                row.recordFormat(attempt.formatVerdict());
            }
            if (attempt.blocked()) {
                row.noteValidationBlocked(attempt.document().toJson());
            } else {
                row.applyValidationOutcome(attempt.ready(), attempt.document().toJson());
            }
            artifacts.saveScoped(row);
            trace.artifact(artifactId);
            finish(artifactId, ownership, uploaderId, row, attempt);
            return null;
        });
    }

    private void writeBlocked(
            UUID artifactId, VersionOwnership ownership, UUID uploaderId, ValidationFailureCode code, String reason) {
        inScope(ownership, uploaderId, () -> {
            writeBlockedRow(artifactId, ownership, uploaderId, code, reason);
            return null;
        });
    }

    /** The queue-refusal variant: the caller's scope is already bound. */
    private void writeBlockedInCallerScope(
            UUID artifactId, VersionOwnership ownership, UUID uploaderId, ValidationFailureCode code, String reason) {
        writeBlockedRow(artifactId, ownership, uploaderId, code, reason);
    }

    private void writeBlockedRow(
            UUID artifactId, VersionOwnership ownership, UUID uploaderId, ValidationFailureCode code, String reason) {
        ArtifactEntity row = artifacts.findScoped(artifactId).orElse(null);
        if (row == null) {
            return;
        }
        ValidationDocument document = ValidationDocument.continueFrom(row.getValidation(), Instant.now(clock));
        document.blocked(Instant.now(clock), code, reason);
        row.noteValidationBlocked(document.toJson());
        artifacts.saveScoped(row);
        trace.artifact(artifactId);
        finish(artifactId, ownership, uploaderId, row, new Attempt(document, false, true, null));
    }

    /** The audit + metric + log tail every terminal write shares. */
    private void finish(
            UUID artifactId, VersionOwnership ownership, UUID uploaderId, ArtifactEntity row, Attempt attempt) {
        String platform = row.getPlatform().name();
        String outcome = attempt.blocked() ? OUTCOME_BLOCKED : attempt.ready() ? OUTCOME_SUCCESS : OUTCOME_FAILED;
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("platform", platform);
        detail.put("outcome", attempt.blocked() ? "BLOCKED" : attempt.ready() ? "PASSED" : "FAILED");
        detail.put("attempts", Integer.toString(attempt.document().attempts()));
        detail.put("durationMs", Long.toString(attempt.document().durationMs()));
        if (attempt.formatVerdict() != null || row.getFormat() != null) {
            detail.put("format", attempt.formatVerdict() != null ? attempt.formatVerdict() : row.getFormat());
        }
        ValidationDocument.StageFailure failure = attempt.document().firstFailure();
        if (failure != null) {
            detail.put("stage", failure.stage().name());
            detail.put("errorCode", failure.code().name());
        }
        if (attempt.blocked() && attempt.document().code() != null) {
            detail.put("errorCode", attempt.document().code().name());
        }
        audit.publish(AuditEvent.builder(AuditEventType.ARTIFACT_VALIDATED)
                .occurredAt(Instant.now(clock))
                .userId(uploaderId)
                .orgId(ownership.organizationId())
                .tenantId(ownership.tenantId())
                .applicationId(ownership.applicationId())
                .versionId(ownership.versionId())
                .artifactId(artifactId)
                .success(!attempt.blocked() && attempt.ready())
                .detail(detail)
                .build());
        // The domain fact of the same terminal write (S12-01): PASSED
        // and FAILED are bus events (ARTIFACT_VALIDATED /
        // ARTIFACT_FAILED on the relay); BLOCKED is the operational
        // skip and deliberately publishes nothing.
        String busOutcome = attempt.blocked() ? "BLOCKED" : attempt.ready() ? "PASSED" : "FAILED";
        if (!"BLOCKED".equals(busOutcome)) {
            ValidationDocument.StageFailure firstFailure = attempt.document().firstFailure();
            events.publishEvent(new ArtifactValidatedEvent(
                    artifactId,
                    ownership.versionId(),
                    ownership.applicationId(),
                    ownership.organizationId(),
                    ownership.tenantId(),
                    platform,
                    busOutcome,
                    firstFailure == null ? null : firstFailure.code().name(),
                    uploaderId,
                    Instant.now(clock)));
        }
        timer(platform, outcome)
                .record(java.time.Duration.ofMillis(
                        Math.max(0, attempt.document().durationMs())));
        log.info(
                "validated artifact {} ({}): outcome={} attempts={} status={}",
                artifactId,
                platform,
                outcome,
                attempt.document().attempts(),
                row.getStatus().name());
    }

    // -------------------------------------------------------------------------
    // Documents, metric, scope, sleep, small facts
    // -------------------------------------------------------------------------

    private static String documentText(String document, String field) {
        JsonNode node = documentNode(document, field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Long documentLong(String document, String field) {
        JsonNode node = documentNode(document, field);
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static JsonNode documentNode(String document, String field) {
        if (document == null) {
            return null;
        }
        try {
            return DOCUMENTS.readTree(document).get(field);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String extractionErrorCode(RowFacts row) {
        if (row.extractionError() == null) {
            return "no outcome";
        }
        String code = documentText(row.extractionError(), "code");
        return code == null ? "no code recorded" : code;
    }

    /**
     * The numeric build when the raw string is a plain integer (the
     * parsers' own rule: Apple allows {@code 1.2.3} and letters, and a
     * non-integer raw build has no numeric comparison to make).
     */
    private static Long plainInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The extension's claimed format, or null for an unrecognized name. */
    private static String claimedFormat(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ipa")) {
            return "IPA";
        }
        if (lower.endsWith(".apk")) {
            return "APK";
        }
        if (lower.endsWith(".aab")) {
            return "AAB";
        }
        return null;
    }

    private static String identifierKind(Platform platform) {
        return platform == Platform.IOS ? "bundle id" : "package name";
    }

    private static boolean booleanOf(JsonNode node) {
        return node != null && node.asBoolean(false);
    }

    private static String textOf(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private Attempt failed(
            ValidationDocument document,
            ValidationStage stage,
            ValidationFailureCode code,
            String reason,
            String formatVerdict) {
        document.stage(stage, StageStatus.FAILED, code, reason);
        for (ValidationStage later : ValidationStage.values()) {
            if (later.ordinal() > stage.ordinal()) {
                document.stage(later, StageStatus.SKIPPED, null, null);
            }
        }
        document.failed(Instant.now(clock));
        return new Attempt(document, false, false, formatVerdict);
    }

    private Attempt failed(
            ValidationDocument document, ValidationStage stage, ValidationFailureCode code, String reason) {
        return failed(document, stage, code, reason, null);
    }

    private Attempt blocked(ValidationDocument document, ValidationFailureCode code, String reason) {
        document.blocked(Instant.now(clock), code, reason);
        return new Attempt(document, false, true, null);
    }

    private Timer timer(String platform, String outcome) {
        return Timer.builder(MetricNames.ARTIFACT_VALIDATION_DURATION)
                .tag("platform", platform)
                .tag("outcome", outcome)
                .register(meters);
    }

    /** Establishes the artifact's owning scope the way background jobs must (ADR-0002). */
    private <T> T inScope(VersionOwnership ownership, UUID actorId, java.util.function.Supplier<T> work) {
        return ownership.tenantId() != null
                ? scopeExecutor.inScope(ownership.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(ownership.organizationId(), actorId, work);
    }

    /** Sleeps the bounded backoff; false when the thread was interrupted. */
    private static boolean sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(250L * attempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** One pipeline attempt's terminal shape. */
    private record Attempt(ValidationDocument document, boolean ready, boolean blocked, String formatVerdict) {}

    /**
     * The scoped snapshot of the artifact row the job evaluates
     * against, captured by value under the artifact's owning scope
     * before any stage runs (the isolation seam refuses row reads off
     * the executor thread — the extraction tier's discipline, applied
     * to the read side). Every field is a copy: stage evaluation never
     * touches the entity, and the scoped write phase re-reads the row
     * before mutating it.
     */
    private record RowFacts(
            String storageKey,
            long size,
            String sha256,
            String platform,
            String fileName,
            Long buildNumber,
            String bundleId,
            String versionName,
            String extractedMetadata,
            String extractionError,
            String previousValidation,
            String extractionOutcome) {

        static RowFacts of(ArtifactEntity row) {
            return new RowFacts(
                    row.getStorageKey(),
                    row.getSize(),
                    row.getSha256(),
                    row.getPlatform().name(),
                    row.getFileName(),
                    row.getBuildNumber(),
                    row.getBundleId(),
                    row.getVersionName(),
                    row.getExtractedMetadata(),
                    row.getExtractionError(),
                    row.getValidation(),
                    row.extractionOutcome());
        }
    }

    /**
     * The FORMAT stage's intermediate answer: PASSED (with a recordable
     * verdict, or null when honestly deferred), FAILED (a code), or
     * BLOCKED (extraction has not settled).
     */
    private record FormatVerdict(Outcome outcome, String verdict, ValidationFailureCode code, String reason) {

        enum Outcome {
            PASSED,
            FAILED,
            BLOCKED
        }

        static FormatVerdict passed(String verdict, String reason) {
            return new FormatVerdict(Outcome.PASSED, verdict, null, reason);
        }

        static FormatVerdict failed(ValidationFailureCode code, String reason) {
            return new FormatVerdict(Outcome.FAILED, null, code, reason);
        }

        /** The failed verdict that still OBSERVED a structural format worth recording. */
        static FormatVerdict failedOf(String verdict, ValidationFailureCode code, String reason) {
            return new FormatVerdict(Outcome.FAILED, verdict, code, reason);
        }

        static FormatVerdict blocked(String reason) {
            return new FormatVerdict(Outcome.BLOCKED, null, null, reason);
        }
    }

    /** The SIGNING stage's intermediate answer: null code means passed. */
    private record SigningCheck(ValidationFailureCode code, String reason) {}
}
