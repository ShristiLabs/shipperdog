package com.shipperdog.api.artifact.internal.extraction;

import com.shipperdog.api.artifact.ArtifactBinary;
import com.shipperdog.api.artifact.ArtifactException;
import com.shipperdog.api.artifact.ArtifactMetadataParser;
import com.shipperdog.api.artifact.ArtifactStore;
import com.shipperdog.api.artifact.ExtractedMetadata;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity;
import com.shipperdog.api.artifact.internal.repository.ArtifactScopedRepository;
import com.shipperdog.api.artifact.internal.validation.ArtifactValidationService;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.version.VersionAccess.VersionOwnership;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The async extraction orchestrator (issue #21, S5-02): registration
 * hands every stored artifact here, and a bounded executor parses it
 * off the request path — the story's "extraction runs async
 * post-upload". The failure vocabulary is the issue's AC3 split:
 *
 * <ul>
 *   <li>{@link ExtractionFailure} — the BINARY is malformed (not a
 *       ZIP, no Info.plist, unreadable plist): the error records with
 *       its code and the artifact moves VALIDATING to FAILED. The
 *       bytes stay stored and immutable (INV-003); the upload was not
 *       a mistake, the artifact is.
 *   <li>{@code STORAGE_UNAVAILABLE} — infra: retried up to the
 *       configured attempts, then recorded as EXTRACTION_UNAVAILABLE
 *       while the artifact STAYS VALIDATING (never FAILED for the
 *       store's sake). The record makes the gap visible instead of
 *       silent.
 *   <li>Queue saturation — the bounded executor refuses at schedule
 *       time; the refusal is recorded synchronously as
 *       EXTRACTION_QUEUE_FULL (still inside the request's scope).
 * </ul>
 *
 * <p>Scope discipline (ADR-0002): the job runs on an executor thread
 * with no request, so it establishes the artifact's owning scope
 * through the sanctioned {@link TenantScopeExecutor} — the background-
 * job entry point the executor documents — using the owning dimension
 * captured at schedule time. Parsing happens OUTSIDE any scope and
 * outside any transaction: it touches only object storage; the scoped
 * phase is exactly the row update, audit, and metric. Idempotency is
 * structural: the entity applies an extraction result only once
 * (write-once guard), so a double schedule is harmless.
 */
@Service
public class ArtifactExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactExtractionService.class);

    /** Documents are plain maps of scalars; one mapper serves them all. */
    private static final ObjectMapper DOCUMENTS = new ObjectMapper();

    /** Extraction outcomes recorded on the metric timer. */
    private static final String OUTCOME_SUCCESS = "success";

    private static final String OUTCOME_FAILED_PARSE = "failed-parse";

    private static final String OUTCOME_FAILED_INFRA = "failed-infra";

    private final ArtifactScopedRepository artifacts;

    private final ArtifactStore store;

    private final List<ArtifactMetadataParser> parsers;

    private final ArtifactValidationService validation;

    private final TenantScopeExecutor scopeExecutor;

    private final AuditEventPublisher audit;

    private final Clock clock;

    private final TraceAttributes trace;

    private final MeterRegistry meters;

    private final ArtifactExtractionProperties properties;

    private final ThreadPoolTaskExecutor executor;

    ArtifactExtractionService(
            ArtifactScopedRepository artifacts,
            ArtifactStore store,
            List<ArtifactMetadataParser> parsers,
            ArtifactValidationService validation,
            TenantScopeExecutor scopeExecutor,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace,
            MeterRegistry meters,
            ArtifactExtractionProperties properties,
            @Qualifier("artifactExtractionExecutor") ThreadPoolTaskExecutor executor) {
        this.artifacts = artifacts;
        this.store = store;
        this.parsers = List.copyOf(parsers);
        this.validation = validation;
        this.scopeExecutor = scopeExecutor;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
        this.meters = meters;
        this.properties = properties;
        this.executor = executor;
    }

    /**
     * Schedules the artifact's extraction, called from the
     * registration path (the request thread, scope bound, transaction
     * done): capture is by value — the row's owning dimension,
     * uploader, storage key, and platform — so the job never re-reads
     * the artifact before establishing its own scope.
     *
     * <p>Platforms without a registered parser (none today: IOS and
     * ANDROID both carry one since S5-03) schedule nothing: there is
     * no code to run, and pretending otherwise would record failures
     * against artifacts nothing can parse.
     */
    public void schedule(ArtifactEntity artifact, VersionOwnership ownership) {
        Optional<ArtifactMetadataParser> parser = parserFor(artifact.getPlatform());
        if (parser.isEmpty()) {
            log.debug("no metadata parser registered for platform {}; extraction skipped", artifact.getPlatform());
            return;
        }
        UUID artifactId = artifact.getId();
        UUID uploaderId = artifact.getUploadedBy();
        String storageKey = artifact.getStorageKey();
        String platform = artifact.getPlatform().name();
        try {
            executor.execute(() -> extract(artifactId, ownership, uploaderId, storageKey, platform, parser.get()));
        } catch (org.springframework.core.task.TaskRejectedException e) {
            log.warn(
                    "extraction queue is full ({} entries); artifact {} left VALIDATING with EXTRACTION_QUEUE_FULL",
                    properties.queueCapacity(),
                    artifactId);
            // The refusal records synchronously on the REQUEST thread,
            // inside the already-bound scope: establishing another
            // scope here is the re-scope the seam forbids, so this is
            // the one outcome written WITHOUT the scope-establishing
            // wrapper.
            blockedInCurrentScope(
                    artifactId,
                    ownership,
                    uploaderId,
                    platform,
                    "EXTRACTION_QUEUE_FULL",
                    "the extraction queue is full (" + properties.queueCapacity()
                            + " entries); the artifact stays VALIDATING and extraction did not run");
        }
    }

    // -------------------------------------------------------------------------
    // The job
    // -------------------------------------------------------------------------

    /**
     * The catch-all of last resort: an unexpected runtime failure (a
     * parser bug, a repository hiccup outside the infra envelope)
     * must never kill the worker silently — the artifact id lands in
     * the error log so a VALIDATING row with no extraction outcome is
     * traceable. The artifact is never FAILED by this path: nothing
     * proved the binary malformed.
     */
    private void extract(
            UUID artifactId,
            VersionOwnership ownership,
            UUID uploaderId,
            String storageKey,
            String platform,
            ArtifactMetadataParser parser) {
        try {
            extractWithRetries(artifactId, ownership, uploaderId, storageKey, platform, parser);
        } catch (RuntimeException unexpected) {
            log.error(
                    "extraction of artifact {} failed unexpectedly ({}: {}); the artifact stays VALIDATING with no recorded outcome",
                    artifactId,
                    unexpected.getClass().getSimpleName(),
                    unexpected.getMessage(),
                    unexpected);
        }
    }

    private void extractWithRetries(
            UUID artifactId,
            VersionOwnership ownership,
            UUID uploaderId,
            String storageKey,
            String platform,
            ArtifactMetadataParser parser) {
        int attempts = Math.max(1, properties.storageRetryAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ExtractedMetadata metadata = parse(artifactId, storageKey, parser);
                succeed(artifactId, ownership, uploaderId, platform, metadata);
                return;
            } catch (ExtractionFailure failure) {
                failParse(artifactId, ownership, uploaderId, platform, failure);
                return;
            } catch (ArtifactException infra) {
                if (attempt == attempts) {
                    log.warn(
                            "extraction of artifact {} could not read storage after {} attempts: {}",
                            artifactId,
                            attempts,
                            infra.getMessage());
                    blocked(
                            artifactId,
                            ownership,
                            uploaderId,
                            platform,
                            "EXTRACTION_UNAVAILABLE",
                            "artifact storage could not be read during extraction (" + infra.getMessage()
                                    + "); the artifact stays VALIDATING and extraction did not complete");
                    return;
                }
                if (!sleepBeforeRetry(attempt)) {
                    blocked(
                            artifactId,
                            ownership,
                            uploaderId,
                            platform,
                            "EXTRACTION_UNAVAILABLE",
                            "extraction was interrupted before storage recovered; the artifact stays VALIDATING");
                    return;
                }
            }
        }
    }

    /** Parses outside any scope and transaction: storage reads only. */
    private ExtractedMetadata parse(UUID artifactId, String storageKey, ArtifactMetadataParser parser) {
        OptionalLong size = store.sizeOf(storageKey);
        if (size.isEmpty()) {
            throw new ArtifactException(
                    ArtifactException.Code.STORAGE_UNAVAILABLE,
                    "the stored object of artifact " + artifactId + " is gone; cannot extract");
        }
        return parser.parse(
                new ArtifactBinary(size.getAsLong(), (offset, length) -> store.read(storageKey, offset, length)));
    }

    // -------------------------------------------------------------------------
    // The three terminal outcomes, each a scoped row write + audit + metric
    // -------------------------------------------------------------------------

    private void succeed(
            UUID artifactId, VersionOwnership ownership, UUID uploaderId, String platform, ExtractedMetadata metadata) {
        inScope(ownership, uploaderId, () -> {
            ArtifactEntity row = artifacts.findScoped(artifactId).orElse(null);
            if (row == null) {
                log.warn("artifact {} vanished before extraction could write", artifactId);
                return null;
            }
            if (row.extractionRecorded()) {
                log.debug("artifact {} already carries an extraction outcome; double schedule ignored", artifactId);
                return null;
            }
            row.applyExtraction(
                    metadata.bundleId(),
                    metadata.versionName(),
                    metadata.minimumOs(),
                    metadata.buildNumber(),
                    metadataDocument(metadata));
            artifacts.saveScoped(row);
            trace.artifact(artifactId);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("platform", platform);
            detail.put("bundleId", metadata.bundleId());
            detail.put("versionName", metadata.versionName() == null ? "-" : metadata.versionName());
            detail.put("buildNumber", metadata.buildNumber() == null ? "-" : Long.toString(metadata.buildNumber()));
            detail.put("minimumOs", metadata.minimumOs() == null ? "-" : metadata.minimumOs());
            if (metadata.format() != null) {
                detail.put("format", metadata.format());
            }
            detail.put("parser", metadata.parser());
            detail.put("durationMs", Long.toString(metadata.durationMs()));
            audit(artifactId, ownership, uploaderId, true, detail);
            timer(platform, OUTCOME_SUCCESS).record(java.time.Duration.ofMillis(Math.max(0, metadata.durationMs())));
            log.info(
                    "extracted artifact {} ({} {}): bundleId={} build={}",
                    artifactId,
                    platform,
                    metadata.versionName(),
                    metadata.bundleId(),
                    metadata.buildNumberRaw());
            // The validation hand-off (issue #24, S5-05): the settled
            // extraction is the pipeline's scheduling point, inside the
            // same bound scope the write used.
            validation.schedule(row, ownership);
            return null;
        });
    }

    private void failParse(
            UUID artifactId, VersionOwnership ownership, UUID uploaderId, String platform, ExtractionFailure failure) {
        log.warn("extraction of artifact {} failed: {} ({})", artifactId, failure.code(), failure.getMessage());
        inScope(ownership, uploaderId, () -> {
            ArtifactEntity row = artifacts.findScoped(artifactId).orElse(null);
            if (row == null || row.extractionRecorded()) {
                return null;
            }
            row.recordExtractionFailure(errorDocument(failure.code().name(), failure.getMessage()));
            artifacts.saveScoped(row);
            trace.artifact(artifactId);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("platform", platform);
            detail.put("errorCode", failure.code().name());
            detail.put("error", failure.getMessage());
            audit(artifactId, ownership, uploaderId, false, detail);
            timer(platform, OUTCOME_FAILED_PARSE).record(java.time.Duration.ZERO);
            // The failed leg hands off too (issue #24): the pipeline's
            // FORMAT or METADATA stage records WHY on the validation
            // document — the row is already FAILED by the extraction
            // tier, and applyValidationOutcome's same-target write is
            // the legal no-op that carries the document.
            validation.schedule(row, ownership);
            return null;
        });
    }

    private void blocked(
            UUID artifactId,
            VersionOwnership ownership,
            UUID uploaderId,
            String platform,
            String errorCode,
            String message) {
        inScope(ownership, uploaderId, () -> {
            writeBlocked(artifactId, ownership, uploaderId, platform, errorCode, message);
            return null;
        });
    }

    /** The queue-refusal variant: the caller's scope is already bound (request thread). */
    private void blockedInCurrentScope(
            UUID artifactId,
            VersionOwnership ownership,
            UUID uploaderId,
            String platform,
            String errorCode,
            String message) {
        writeBlocked(artifactId, ownership, uploaderId, platform, errorCode, message);
    }

    private void writeBlocked(
            UUID artifactId,
            VersionOwnership ownership,
            UUID uploaderId,
            String platform,
            String errorCode,
            String message) {
        ArtifactEntity row = artifacts.findScoped(artifactId).orElse(null);
        if (row == null || row.extractionRecorded()) {
            return;
        }
        row.noteExtractionBlocked(errorDocument(errorCode, message));
        artifacts.saveScoped(row);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("platform", platform);
        detail.put("errorCode", errorCode);
        detail.put("error", message);
        audit(artifactId, ownership, uploaderId, false, detail);
        timer(platform, OUTCOME_FAILED_INFRA).record(java.time.Duration.ZERO);
    }

    // -------------------------------------------------------------------------
    // Documents, audit, metric, scope, sleep
    // -------------------------------------------------------------------------

    /**
     * The jsonb document of a successful extraction (V16 {@code
     * extracted_metadata}). The S5-03 Android keys (targetOs, format,
     * signingBlocks) appear only when a parser produced them, so an
     * iOS document is byte-shaped exactly as S5-02 wrote it — the
     * additive-only rule of the shared record.
     */
    private static String metadataDocument(ExtractedMetadata metadata) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("bundleId", metadata.bundleId());
        document.put("versionName", metadata.versionName());
        document.put("buildNumberRaw", metadata.buildNumberRaw());
        document.put("minimumOs", metadata.minimumOs());
        document.put("displayName", metadata.displayName());
        if (metadata.targetOs() != null) {
            document.put("targetOs", metadata.targetOs());
        }
        if (metadata.format() != null) {
            document.put("format", metadata.format());
        }
        if (metadata.signing() != null) {
            Map<String, Object> signing = new LinkedHashMap<>();
            signing.put("profileName", metadata.signing().profileName());
            signing.put("profileUuid", metadata.signing().profileUuid());
            signing.put("teamIdentifiers", metadata.signing().teamIdentifiers());
            signing.put(
                    "expiresAt",
                    metadata.signing().expiresAt() == null
                            ? null
                            : metadata.signing().expiresAt().toString());
            signing.put("entitlements", metadata.signing().entitlements());
            document.put("signing", signing);
        } else {
            document.put("signing", null);
        }
        if (metadata.signatureBlocks() != null) {
            Map<String, Object> blocks = new LinkedHashMap<>();
            blocks.put("v2SignatureBlock", metadata.signatureBlocks().v2SignatureBlock());
            blocks.put("v3SignatureBlock", metadata.signatureBlocks().v3SignatureBlock());
            document.put("signingBlocks", blocks);
        }
        document.put("extractedAt", metadata.extractedAt().toString());
        document.put("durationMs", metadata.durationMs());
        document.put("parser", metadata.parser());
        return DOCUMENTS.writeValueAsString(document);
    }

    /** The jsonb document of a failed or blocked extraction (V16 {@code extraction_error}). */
    private String errorDocument(String code, String message) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("code", code);
        document.put("message", message);
        document.put("occurredAt", Instant.now(clock).toString());
        return DOCUMENTS.writeValueAsString(document);
    }

    private void audit(
            UUID artifactId, VersionOwnership ownership, UUID actorId, boolean success, Map<String, String> detail) {
        audit.publish(AuditEvent.builder(AuditEventType.ARTIFACT_METADATA_EXTRACTED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(ownership.organizationId())
                .tenantId(ownership.tenantId())
                .applicationId(ownership.applicationId())
                .versionId(ownership.versionId())
                .artifactId(artifactId)
                .success(success)
                .detail(detail)
                .build());
    }

    private Timer timer(String platform, String outcome) {
        return Timer.builder(MetricNames.ARTIFACT_EXTRACTION_DURATION)
                .tag("platform", platform)
                .tag("outcome", outcome)
                .register(meters);
    }

    /** Establishes the artifact's owning scope the way background jobs must (ADR-0002). */
    private void inScope(VersionOwnership ownership, UUID actorId, java.util.function.Supplier<Object> work) {
        if (ownership.tenantId() != null) {
            scopeExecutor.inScope(ownership.tenantId(), actorId, work::get);
        } else {
            scopeExecutor.inOrganizationScope(ownership.organizationId(), actorId, work::get);
        }
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

    private Optional<ArtifactMetadataParser> parserFor(ArtifactEntity.Platform platform) {
        return parsers.stream()
                .filter(parser -> parser.platform().equals(platform.name()))
                .findFirst();
    }
}
