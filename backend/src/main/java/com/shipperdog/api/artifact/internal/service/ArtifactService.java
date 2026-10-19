package com.shipperdog.api.artifact.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.artifact.ArtifactException;
import com.shipperdog.api.artifact.ArtifactStore;
import com.shipperdog.api.artifact.ArtifactUploadedEvent;
import com.shipperdog.api.artifact.OtaBundleArtifacts;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity.Platform;
import com.shipperdog.api.artifact.internal.entity.ArtifactUploadSessionEntity;
import com.shipperdog.api.artifact.internal.extraction.ArtifactExtractionService;
import com.shipperdog.api.artifact.internal.provenance.Provenance;
import com.shipperdog.api.artifact.internal.repository.ArtifactScopedRepository;
import com.shipperdog.api.artifact.internal.repository.ArtifactUploadSessionScopedRepository;
import com.shipperdog.api.artifact.internal.storage.ArtifactStorageProperties;
import com.shipperdog.api.artifact.internal.storage.ArtifactUploadProperties;
import com.shipperdog.api.artifact.internal.validation.ArtifactValidationService;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.AuthorizationService;
import com.shipperdog.api.authorization.ConcealedResourceAccessDeniedException;
import com.shipperdog.api.authorization.DecisionGuarded;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.version.VersionAccess;
import com.shipperdog.api.version.VersionAccess.VersionOwnership;
import com.shipperdog.api.version.VersionArtifacts;
import com.shipperdog.api.version.VersionDistributionGuard;
import com.shipperdog.api.version.VersionException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * The artifact layer (issue #20, S5-01, FR-004/FR-007, PRD 8.5/24,
 * INV-003): binaries stream into object storage under the PRD 24
 * layout — the server allocates the artifact id BEFORE any byte moves,
 * so the key is stable and never derives from user input — while
 * PostgreSQL keeps the metadata row only (never bytes, FR-007). The
 * SHA-256 is computed DURING the stream and the row is inserted after,
 * which keeps {@code artifacts.sha256} NOT NULL by construction (the
 * V1 column, the #2 expand/contract note's "hash before insert").
 *
 * <p>INV-003 — an uploaded artifact cannot be replaced — is structural:
 * the {@link ArtifactStore} port has no overwrite and no delete; the
 * store write is preconditioned write-once; the entity has no setters;
 * and a re-upload of the same file is a NEW row with a NEW id and key
 * (issue #20 AC3), with the identical checksum surfaced informationally
 * as {@code duplicateOf} (AC4) — detected across every version of the
 * application, because a re-uploaded binary is the same binary whether
 * it landed under this version or a sibling.
 *
 * <p>Two paths share one set of guards (issue #20): the server-streamed
 * multipart route for artifacts at or below the resumable threshold,
 * and the direct-to-storage resumable session for larger ones (AC5,
 * the >100 MB rule — enforced, not advisory: a multipart body above
 * the threshold answers the 413 that points at the session path). The
 * session handoff happens only after the same validation, the
 * {@link VersionDistributionGuard} freeze check (issue #19's INV-004
 * wiring: no artifacts onto a non-DRAFT-frozen version), and the
 * archived-application guard ({@code APPLICATION_ARCHIVED}, the code
 * issue #13 reserved for exactly this surface); finalize re-checks the
 * freeze — a version can be revoked during the upload window — hashes
 * the stored object (the server never saw the client's direct bytes),
 * registers the row, and deletes the session. Session creation is
 * audited ({@code ARTIFACT_UPLOAD_SESSION_CREATED}), and every
 * registered artifact is audited as {@code ARTIFACT_UPLOADED} with
 * actor, version, platform, size, and checksum (issue #20 AC6) and
 * timed as {@code shipperdog.artifact.upload.duration} (AC7, PRD 28).
 *
 * <p>Tenant isolation (INV-001, ADR-0002): the artifact routes are
 * body-addressed ({@code POST /api/v1/artifacts} carries the version
 * id in the form, FR-004), so no route interceptor can bind the scope
 * — the sanctioned {@link TenantScopeExecutor} establishes it once the
 * version's owning dimension resolves through {@link VersionAccess},
 * exactly the establishment the executor was built for (issue #13's
 * creation routes). Every subsequent read and write composes through
 * the seam inside that scope; an unknown or foreign version id is the
 * standard concealed miss, indistinguishable. This service also
 * implements {@link VersionArtifacts} — the version module's read-side
 * seam — answering inside the request's already-bound scope for the
 * version views and the history read.
 *
 * <p>Transactions: each repository call carries its own (the seam's
 * write guard included); no transaction stays open across a storage
 * stream — a multi-hundred-MB upload never pins a connection
 * (streaming sits between the scoped pre-check phase and the scoped
 * registration phase, each establishing its own scope binding; the
 * executor is establish-only per execution, not per thread). A crash
 * between the artifact insert and the session delete is healed by the
 * finalize replay branch: session present, artifact present, answer
 * the registered artifact and drop the stale session.
 */
@Service
@DecisionGuarded
public class ArtifactService implements VersionArtifacts, OtaBundleArtifacts {

    /** The upload allowlist (issue #20 AC1): extension to platform coherence. */
    private static final Map<String, Platform> ALLOWED_EXTENSIONS =
            Map.of(".ipa", Platform.IOS, ".apk", Platform.ANDROID, ".aab", Platform.ANDROID);

    /** Artifacts store an opaque binary; no client media type is trusted. */
    private static final String CONTENT_TYPE = "application/octet-stream";

    /** Chronological order of every artifact read (upload time, then id). */
    private static final Sort CHRONOLOGICAL =
            Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id"));

    /** Page width of the paged-by-loop artifact reads; never truncates. */
    private static final int READ_PAGE = 500;

    private final ArtifactScopedRepository artifacts;
    private final ArtifactUploadSessionScopedRepository sessions;
    private final ArtifactStore store;
    private final ApplicationAccess applications;
    private final VersionAccess versions;
    private final VersionDistributionGuard distributionGuard;
    private final TenantScopeExecutor scopeExecutor;
    private final ArtifactExtractionService extraction;
    private final ArtifactValidationService validation;
    private final AuditEventPublisher audit;
    private final ApplicationEventPublisher events;
    /**
     * Lazy on purpose (issue #30): the layered authorization engine's
     * artifact layer resolves artifact state through this module's
     * public {@code ArtifactAccess} port, so a direct constructor
     * dependency on {@code AuthorizationService} would close a bean
     * cycle — the ObjectProvider precedent this codebase uses for
     * every inversion of that shape.
     */
    private final ObjectProvider<AuthorizationService> authorization;

    private final Clock clock;
    private final TraceAttributes trace;
    private final MeterRegistry meters;
    private final ArtifactStorageProperties properties;
    private final ArtifactUploadProperties uploadLimits;

    ArtifactService(
            ArtifactScopedRepository artifacts,
            ArtifactUploadSessionScopedRepository sessions,
            ArtifactStore store,
            ApplicationAccess applications,
            VersionAccess versions,
            VersionDistributionGuard distributionGuard,
            TenantScopeExecutor scopeExecutor,
            ArtifactExtractionService extraction,
            ArtifactValidationService validation,
            AuditEventPublisher audit,
            ApplicationEventPublisher events,
            ObjectProvider<AuthorizationService> authorization,
            Clock clock,
            TraceAttributes trace,
            MeterRegistry meters,
            ArtifactStorageProperties properties,
            ArtifactUploadProperties uploadLimits) {
        this.artifacts = artifacts;
        this.sessions = sessions;
        this.store = store;
        this.applications = applications;
        this.versions = versions;
        this.distributionGuard = distributionGuard;
        this.scopeExecutor = scopeExecutor;
        this.extraction = extraction;
        this.validation = validation;
        this.audit = audit;
        this.events = events;
        this.authorization = authorization;
        this.clock = clock;
        this.trace = trace;
        this.meters = meters;
        this.properties = properties;
        this.uploadLimits = uploadLimits;
    }

    // -------------------------------------------------------------------------
    // The multipart path (AC1-AC4, AC6, AC7)
    // -------------------------------------------------------------------------

    /**
     * Uploads an artifact through the server-streamed multipart route:
     * everything validates before the first byte is stored — platform
     * parse, file-name shape and extension allowlist (AC1: unknown
     * extensions never reach storage), the build-provenance claims of
     * issue #23 (optional, shaped when present, rejected with every
     * field's error at once), platform registered for the
     * application, size ceiling, the >threshold rule that points at
     * the session path (AC5), a pinned build's availability, the
     * archived-application guard, and INV-004's freeze — then the bytes
     * stream to storage through a SHA-256 digest, and the metadata row
     * registers. Identical content elsewhere in the application
     * surfaces as {@code duplicateOf} (AC4), informational only.
     */
    public ArtifactView upload(
            UUID actorId,
            UUID versionId,
            String platformText,
            String fileName,
            Long buildNumber,
            Provenance.Fields provenanceFields,
            MultipartFile file) {
        Platform platform = parsePlatform(platformText);
        String validatedName = requireArtifactFileName(fileName, platform);
        requireBuildNumber(buildNumber);
        Provenance provenance = Provenance.of(provenanceFields);
        if (file == null || file.isEmpty()) {
            throw invalid("file is required and must not be empty");
        }
        if (file.getSize() > uploadLimits.maxBytes()) {
            throw tooLarge();
        }
        if (file.getSize() > uploadLimits.resumableThresholdBytes()) {
            throw new ArtifactException(
                    ArtifactException.Code.ARTIFACT_TOO_LARGE,
                    "artifacts larger than " + uploadLimits.resumableThresholdBytes()
                            + " bytes upload through a resumable session (POST /api/v1/artifacts/upload-sessions)");
        }
        VersionOwnership ownership = ownershipOf(versionId);
        ArtifactView registered = inScope(ownership, actorId, () -> {
            requireUploadableApplication(ownership, platform);
            distributionGuard.requireArtifactsMutable(ownership.applicationId(), versionId);
            if (buildNumber != null && artifacts.existsScoped(pinnedBuild(versionId, platform, buildNumber))) {
                throw buildTaken();
            }
            UUID artifactId = UUID.randomUUID();
            String storageKey = keyOf(ownership, artifactId);
            trace.application(ownership.applicationId());
            trace.version(versionId);
            trace.artifact(artifactId);
            Timer.Sample sample = Timer.start(meters);
            String sha256;
            try (InputStream raw = file.getInputStream()) {
                MessageDigest digest = sha256();
                DigestInputStream digested = new DigestInputStream(raw, digest);
                store.put(storageKey, digested, CONTENT_TYPE);
                sha256 = HexFormat.of().formatHex(digest.digest());
                sample.stop(timer("multipart", "success"));
            } catch (IOException e) {
                sample.stop(timer("multipart", "failure"));
                throw new ArtifactException(ArtifactException.Code.STORAGE_UNAVAILABLE, "cannot read the upload body");
            } catch (RuntimeException e) {
                sample.stop(timer("multipart", "failure"));
                throw e;
            }
            ArtifactEntity saved = register(
                    artifactId,
                    ownership,
                    platform,
                    validatedName,
                    storageKey,
                    sha256,
                    file.getSize(),
                    buildNumber,
                    provenance == null ? null : provenance.toJson(),
                    actorId,
                    ArtifactEntity.Status.VALIDATING);
            auditUploaded(actorId, ownership, "multipart", saved);
            extraction.schedule(saved, ownership);
            return viewOf(saved, duplicatesOf(sha256, ownership.applicationId(), saved.getId()));
        });
        return registered;
    }

    // -------------------------------------------------------------------------
    // The resumable path (AC5): session create, direct bytes, finalize
    // -------------------------------------------------------------------------

    /**
     * Creates the short-lived direct-to-storage session (AC5): the
     * same guards as the multipart path run first — the session is a
     * handoff to an already-validated upload, not a bypass — including
     * the issue #23 provenance validation: this creation moment IS the
     * upload-time capture point of the resumable surface, so the
     * session row carries the claims and finalize replays them into
     * the registered artifact. Then the artifact id and storage key
     * allocate, the store grants the resumable session URL, and the
     * session row records the handoff with its expiry. Audited as
     * {@code ARTIFACT_UPLOAD_SESSION_CREATED}.
     */
    public UploadSessionView createSession(
            UUID actorId,
            UUID versionId,
            String platformText,
            String fileName,
            Long buildNumber,
            Provenance.Fields provenanceFields,
            Long sizeHint) {
        Platform platform = parsePlatform(platformText);
        String validatedName = requireArtifactFileName(fileName, platform);
        requireBuildNumber(buildNumber);
        Provenance provenance = Provenance.of(provenanceFields);
        if (sizeHint != null) {
            if (sizeHint <= 0) {
                throw invalid("sizeHint must be positive when present");
            }
            if (sizeHint > uploadLimits.maxBytes()) {
                throw tooLarge();
            }
        }
        VersionOwnership ownership = ownershipOf(versionId);
        return inScope(ownership, actorId, () -> {
            requireUploadableApplication(ownership, platform);
            distributionGuard.requireArtifactsMutable(ownership.applicationId(), versionId);
            if (buildNumber != null && artifacts.existsScoped(pinnedBuild(versionId, platform, buildNumber))) {
                throw buildTaken();
            }
            UUID artifactId = UUID.randomUUID();
            String storageKey = keyOf(ownership, artifactId);
            String sessionUrl = store.createResumableSession(storageKey, CONTENT_TYPE);
            Instant expiresAt = Instant.now(clock).plus(uploadLimits.sessionTtl());
            ArtifactUploadSessionEntity session = sessions.saveScoped(new ArtifactUploadSessionEntity(
                    ownership.organizationId(),
                    ownership.tenantId(),
                    versionId,
                    artifactId,
                    platform,
                    validatedName,
                    buildNumber,
                    provenance == null ? null : provenance.toJson(),
                    storageKey,
                    sessionUrl,
                    expiresAt,
                    actorId));
            trace.application(ownership.applicationId());
            trace.version(versionId);
            trace.artifact(artifactId);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("platform", platform.name());
            detail.put("fileName", validatedName);
            detail.put("expiresAt", expiresAt.toString());
            audit(AuditEventType.ARTIFACT_UPLOAD_SESSION_CREATED, actorId, ownership, artifactId, detail);
            return new UploadSessionView(session.getId(), artifactId, versionId, sessionUrl, expiresAt);
        });
    }

    /**
     * Finalizes the session (AC5): the stored object is streamed back
     * through a SHA-256 digest — the server never saw the client's
     * direct bytes, so the checksum comes from the storage itself —
     * then the artifact row registers with the provenance the session
     * captured at its creation (issue #23: the resumable surface's
     * upload-time capture point; finalize takes no provenance of its
     * own — hours may separate the two calls, and the claims belong to
     * the client that made them), the session row disappears, and the
     * outcome replays: a finalize against an already-registered
     * artifact (retry, or the heal of a crash between insert and
     * session delete) answers the registered view, 200 not 201. The
     * freeze is re-checked (a version can be revoked mid-upload), the
     * expiry enforced (410), and an unused session (no bytes) is a
     * broken request, not a stored one.
     */
    public FinalizeView finalizeSession(UUID actorId, UUID artifactId, UUID versionId) {
        VersionOwnership ownership = ownershipOf(versionId);
        return inScope(ownership, actorId, () -> {
            ArtifactUploadSessionEntity session =
                    sessions.findOneScoped(sessionOf(artifactId)).orElse(null);
            if (session == null) {
                ArtifactEntity existing =
                        artifacts.findScoped(artifactId).orElseThrow(ArtifactService::artifactMissing);
                requireOwnVersion(existing, versionId);
                return new FinalizeView(false, viewOf(existing, new DuplicateScan(List.of(), false)));
            }
            requireOwnVersion(session, versionId);
            distributionGuard.requireArtifactsMutable(ownership.applicationId(), versionId);
            if (Instant.now(clock).isAfter(session.getExpiresAt())) {
                throw new ArtifactException(
                        ArtifactException.Code.UPLOAD_SESSION_EXPIRED, "the upload session has expired");
            }
            ArtifactStore.StoredChecksum stored = store.hash(session.getStorageKey())
                    .orElseThrow(() -> invalid("no bytes were uploaded to this session yet"));
            if (stored.size() == 0) {
                throw invalid("the uploaded artifact is empty");
            }
            if (stored.size() > uploadLimits.maxBytes()) {
                throw tooLarge();
            }
            ArtifactEntity healed = artifacts.findScoped(artifactId).orElse(null);
            if (healed != null) {
                sessions.delete(session);
                return new FinalizeView(
                        false,
                        viewOf(healed, duplicatesOf(healed.getSha256(), ownership.applicationId(), healed.getId())));
            }
            // The registration point of the resumable path: same
            // guards, same audit, same async extraction handoff.
            Timer.Sample sample = Timer.start(meters);
            ArtifactEntity saved;
            try {
                saved = register(
                        artifactId,
                        ownership,
                        session.getPlatform(),
                        session.getFileName(),
                        session.getStorageKey(),
                        stored.sha256Hex(),
                        stored.size(),
                        session.getBuildNumber(),
                        session.getProvenance(),
                        actorId,
                        ArtifactEntity.Status.VALIDATING);
                sample.stop(timer("resumable", "success"));
            } catch (RuntimeException e) {
                sample.stop(timer("resumable", "failure"));
                throw e;
            }
            sessions.delete(session);
            auditUploaded(actorId, ownership, "resumable", saved);
            extraction.schedule(saved, ownership);
            return new FinalizeView(
                    true, viewOf(saved, duplicatesOf(stored.sha256Hex(), ownership.applicationId(), saved.getId())));
        });
    }

    // -------------------------------------------------------------------------
    // The OTA bundle port (S16-03, ADR-0010 D5): bundle binaries ride
    // the same storage, sessions, and hash-back finalize — without the
    // platform-package gates or the extraction pipeline they feed.
    // -------------------------------------------------------------------------

    /**
     * The bundle session grant ({@code OtaBundleArtifacts} port): the
     * same handoff the resumable artifact path grants — pre-allocated
     * artifact id, PRD 24 storage key, store-minted session URL, TTL'd
     * session row — minus the package gates: the file name is any bare
     * name (a generic bundle is a platform-agnostic archive; the
     * extension allowlist stays an installable-package rule), the
     * platform is the caller-derived enum value the closed V1 storage
     * vocabulary forces, and the session carries the caller's opaque
     * claims document in the provenance slot (the same capture-point
     * discipline: claims belong to the creating client, finalize
     * replays them). Audited as {@code ARTIFACT_UPLOAD_SESSION_CREATED}
     * with the OTA kind in the detail.
     */
    @Override
    public SessionGrant createBundleSession(
            UUID actorId, UUID versionId, String fileName, String platformText, Long sizeHint, String claimsDocument) {
        Platform platform = parsePlatform(platformText);
        String validatedName = requireBundleFileName(fileName);
        if (claimsDocument == null || claimsDocument.isBlank()) {
            throw invalid("the bundle session requires its claims document");
        }
        if (sizeHint != null) {
            if (sizeHint <= 0) {
                throw invalid("sizeHint must be positive when present");
            }
            if (sizeHint > uploadLimits.maxBytes()) {
                throw tooLarge();
            }
        }
        VersionOwnership ownership = ownershipOf(versionId);
        // The port's scope contract: the CALLER (the otabundle module)
        // has established the version's owning scope; the seam enforces
        // it on every row this path touches, and the executor would
        // refuse the re-scope an inner binding attempt is.
        requireUploadableApplication(ownership, platform);
        distributionGuard.requireArtifactsMutable(ownership.applicationId(), versionId);
        UUID artifactId = UUID.randomUUID();
        String storageKey = keyOf(ownership, artifactId);
        String sessionUrl = store.createResumableSession(storageKey, CONTENT_TYPE);
        Instant expiresAt = Instant.now(clock).plus(uploadLimits.sessionTtl());
        ArtifactUploadSessionEntity session = sessions.saveScoped(new ArtifactUploadSessionEntity(
                ownership.organizationId(),
                ownership.tenantId(),
                versionId,
                artifactId,
                platform,
                validatedName,
                null,
                claimsDocument,
                storageKey,
                sessionUrl,
                expiresAt,
                actorId));
        trace.application(ownership.applicationId());
        trace.version(versionId);
        trace.artifact(artifactId);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("kind", "ota-bundle");
        detail.put("platform", platform.name());
        detail.put("fileName", validatedName);
        detail.put("expiresAt", expiresAt.toString());
        audit(AuditEventType.ARTIFACT_UPLOAD_SESSION_CREATED, actorId, ownership, artifactId, detail);
        return new SessionGrant(session.getId(), artifactId, versionId, sessionUrl, expiresAt);
    }

    /**
     * The bundle session finalize ({@code OtaBundleArtifacts} port):
     * the same hash-back, expiry, and replay semantics as the artifact
     * finalize — the artifact row registers with the checksum the
     * store itself computed, and an already-registered artifact
     * replays ({@code created = false}). Two deliberate differences:
     * the row lands {@code READY} (nothing structural to validate on a
     * platform-agnostic bundle, so no extraction or validation is ever
     * scheduled for it), and the registration is audited as
     * {@code ARTIFACT_UPLOADED} without the bus event — the otabundle
     * module's {@code OtaBundleSignedEvent} is this binary's one bus
     * fact, published with the bundle row in the same transaction the
     * caller holds.
     */
    @Override
    public BundleRegistration finalizeBundleSession(UUID actorId, UUID artifactId, UUID versionId) {
        VersionOwnership ownership = ownershipOf(versionId);
        // The port's scope contract: the caller's established context
        // governs (the seam enforces it); this path never re-binds.
        ArtifactEntity healed = artifacts.findScoped(artifactId).orElse(null);
        if (healed != null) {
            requireOwnVersion(healed, versionId);
            sessions.findOneScoped(sessionOf(artifactId)).ifPresent(sessions::delete);
            return new BundleRegistration(
                    false,
                    healed.getId(),
                    versionId,
                    healed.getFileName(),
                    healed.getSha256(),
                    healed.getSize(),
                    healed.getProvenance());
        }
        // The serialization point of concurrent finalizes of one bundle
        // (#181, S16-05): every racing finalize must delete this one
        // session row, so the locked read holds the loser until the
        // winner's transaction commits — its delete leaves the locked
        // read empty and the re-read below finds the winner's
        // registered artifact, answering the replay instead of
        // colliding on the artifact primary key inside a transaction
        // the collision would poison. An absent row with no registered
        // artifact is the honest miss it always was.
        ArtifactUploadSessionEntity session =
                sessions.findOneScopedForUpdate(sessionOf(artifactId)).orElse(null);
        if (session == null) {
            ArtifactEntity raced = artifacts.findScoped(artifactId).orElseThrow(ArtifactService::artifactMissing);
            requireOwnVersion(raced, versionId);
            return new BundleRegistration(
                    false,
                    raced.getId(),
                    versionId,
                    raced.getFileName(),
                    raced.getSha256(),
                    raced.getSize(),
                    raced.getProvenance());
        }
        requireOwnVersion(session, versionId);
        distributionGuard.requireArtifactsMutable(ownership.applicationId(), versionId);
        if (Instant.now(clock).isAfter(session.getExpiresAt())) {
            throw new ArtifactException(
                    ArtifactException.Code.UPLOAD_SESSION_EXPIRED, "the upload session has expired");
        }
        ArtifactStore.StoredChecksum stored = store.hash(session.getStorageKey())
                .orElseThrow(() -> invalid("no bytes were uploaded to this session yet"));
        if (stored.size() == 0) {
            throw invalid("the uploaded bundle is empty");
        }
        if (stored.size() > uploadLimits.maxBytes()) {
            throw tooLarge();
        }
        ArtifactEntity saved = register(
                artifactId,
                ownership,
                session.getPlatform(),
                session.getFileName(),
                session.getStorageKey(),
                stored.sha256Hex(),
                stored.size(),
                null,
                session.getProvenance(),
                actorId,
                ArtifactEntity.Status.READY);
        sessions.delete(session);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("kind", "ota-bundle");
        detail.put("platform", saved.getPlatform().name());
        detail.put("fileName", saved.getFileName());
        detail.put("size", Long.toString(saved.getSize()));
        detail.put("sha256", saved.getSha256());
        detail.put("path", "ota-resumable");
        audit(AuditEventType.ARTIFACT_UPLOADED, actorId, ownership, saved.getId(), detail);
        return new BundleRegistration(
                true,
                saved.getId(),
                versionId,
                saved.getFileName(),
                saved.getSha256(),
                saved.getSize(),
                saved.getProvenance());
    }

    /**
     * The bundle file-name guard: the same bare-name rule as the
     * package path minus the extension allowlist — at most 255
     * characters, no path separators, no NUL. The storage key never
     * derives from it (the name is display metadata only).
     */
    private static String requireBundleFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw invalid("file name is required");
        }
        String trimmed = fileName.trim();
        if (trimmed.length() > 255 || trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("\0")) {
            throw invalid("file name is a bare name of at most 255 characters");
        }
        return trimmed;
    }

    // -------------------------------------------------------------------------
    // The artifact read (S5-02): where extraction lands
    // -------------------------------------------------------------------------

    /**
     * Reads one artifact (issue #21, S5-02): the read-back surface of
     * the extraction tier — an upload response can only ever show
     * {@code extraction.status = PENDING} (parsing is async by the
     * story's scope), so this route is where a client watches the
     * outcome settle, SUCCESS or the actionable FAILED/BLOCKED error.
     * The {@code version_id} query parameter is the permission target
     * (the same body-addressed shape as the upload routes: the
     * artifact collection has no nesting to bind a route interceptor);
     * a foreign or mismatched version is the standard concealed miss.
     *
     * <p>Since S7-01 (issue #30) the read is decision-guarded: before
     * the scoped data read, the layered authorization engine decides
     * the subject's PRD 11 chain over the version and artifact; a
     * denial renders as the route's concealed version miss and the
     * decision is audited with the correlation id. The engine is
     * injected lazily because its artifact layer resolves artifact
     * state through this module's public {@code ArtifactAccess} port —
     * the same dependency inversion the {@code VersionArtifacts} seam
     * this service implements uses, so neither constructor closes a
     * cycle.
     */
    public ArtifactView get(UUID actorId, UUID artifactId, UUID versionId) {
        // The engine is a required collaborator since S7-01: a
        // missing engine bean must fail LOUD here (500), never
        // silently skip the decision guard — the S7-01 review
        // advisory this replaces (getIfAvailable's null arm was a
        // pre-engine compatibility shim whose fallthrough served an
        // unguarded read). Resolution at request time keeps the
        // constructor cycle broken.
        AuthorizationService engine = authorization.getObject();
        // The conceal grain follows the route's pre-engine
        // contract, not the failing layer: an artifact-layer miss
        // (unknown artifact, foreign artifact) renders the
        // artifact-grain miss ARTIFACT_NOT_FOUND; every other
        // denial conceals as the version-grain miss — exactly the
        // two shapes the route has always produced.
        com.shipperdog.api.authorization.AuthorizationDecision decision =
                engine.decide(AuthorizationContext.readArtifact(actorId, versionId, artifactId));
        if (!decision.allowed()) {
            boolean artifactGrain = decision.reason() == com.shipperdog.api.authorization.DenialReason.ARTIFACT_MISSING
                    || decision.reason() == com.shipperdog.api.authorization.DenialReason.ARTIFACT_PLATFORM_MISMATCH
                    || decision.reason() == com.shipperdog.api.authorization.DenialReason.ARTIFACT_NOT_AVAILABLE;
            throw new ConcealedResourceAccessDeniedException(artifactGrain ? "artifact" : "version");
        }
        VersionOwnership ownership = ownershipOf(versionId);
        return inScope(ownership, actorId, () -> {
            ArtifactEntity artifact = artifacts.findScoped(artifactId).orElseThrow(ArtifactService::artifactMissing);
            requireOwnVersion(artifact, versionId);
            trace.application(ownership.applicationId());
            trace.version(versionId);
            trace.artifact(artifactId);
            return viewOf(artifact, new DuplicateScan(List.of(), false));
        });
    }

    // -------------------------------------------------------------------------
    // Revalidation (S5-05): the retry entry
    // -------------------------------------------------------------------------

    /**
     * Re-drives validation (issue #24, S5-05 — the story's retry
     * scope): a FAILED artifact moves back to VALIDATING through the
     * state machine and the pipeline re-runs, a still-VALIDATING
     * artifact with a BLOCKED validation re-drives where it stands,
     * and a still-VALIDATING artifact whose EXTRACTION was blocked
     * re-arms the extraction tier instead (the block note clears, the
     * parser re-runs, and its settled phase schedules validation — the
     * two async tiers re-enter in order). Deterministic stages
     * reproduce their verdict, but the WORLD may have changed — a
     * corrected registry identifier, recovered storage — and the
     * retry is how the corrected fact wins. Anything else (an
     * UPLOADING session row, a READY or later artifact) answers
     * {@code ARTIFACT_NOT_VALIDATABLE}: PRD 18 has no path from those
     * states back to VALIDATING, and the machine is the authority.
     */
    public ArtifactView revalidate(UUID actorId, UUID artifactId, UUID versionId) {
        VersionOwnership ownership = ownershipOf(versionId);
        return inScope(ownership, actorId, () -> {
            ArtifactEntity artifact = artifacts.findScoped(artifactId).orElseThrow(ArtifactService::artifactMissing);
            requireOwnVersion(artifact, versionId);
            trace.application(ownership.applicationId());
            trace.version(versionId);
            trace.artifact(artifactId);
            switch (artifact.getStatus()) {
                case FAILED -> {
                    artifact.beginRevalidation();
                    ArtifactEntity saved = artifacts.saveScoped(artifact);
                    auditRevalidation(
                            actorId, ownership, artifactId, saved.getStatus().name());
                    validation.schedule(saved, ownership);
                    return viewOf(saved, new DuplicateScan(List.of(), false));
                }
                case VALIDATING -> {
                    if ("BLOCKED".equals(artifact.extractionOutcome())) {
                        artifact.clearExtractionBlock();
                        ArtifactEntity saved = artifacts.saveScoped(artifact);
                        auditRevalidation(
                                actorId,
                                ownership,
                                artifactId,
                                saved.getStatus().name());
                        extraction.schedule(saved, ownership);
                        return viewOf(saved, new DuplicateScan(List.of(), false));
                    }
                    auditRevalidation(
                            actorId, ownership, artifactId, artifact.getStatus().name());
                    validation.schedule(artifact, ownership);
                    return viewOf(artifact, new DuplicateScan(List.of(), false));
                }
                default ->
                    throw new ArtifactException(
                            ArtifactException.Code.ARTIFACT_NOT_VALIDATABLE,
                            "the artifact is " + artifact.getStatus().name()
                                    + "; only a FAILED or still-VALIDATING artifact can be revalidated");
            }
        });
    }

    private void auditRevalidation(UUID actorId, VersionOwnership ownership, UUID artifactId, String status) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("status", status);
        audit(AuditEventType.ARTIFACT_REVALIDATION_REQUESTED, actorId, ownership, artifactId, detail);
    }

    // -------------------------------------------------------------------------
    // VersionArtifacts: the version views' slot read
    // -------------------------------------------------------------------------

    /**
     * The artifacts of the requested versions inside the request's
     * bound scope, chronological (issue #20 filling the #17/#19
     * promises): the version detail's per-platform slots and the
     * history read's artifact list render this verbatim. Pages loop to
     * completion — the read never silently truncates (the #16 lesson).
     */
    @Override
    public Map<UUID, List<VersionArtifactSlot>> byVersionIds(Collection<UUID> versionIds) {
        Map<UUID, List<VersionArtifactSlot>> byVersion = new LinkedHashMap<>();
        if (versionIds == null || versionIds.isEmpty()) {
            return byVersion;
        }
        List<ArtifactEntity> rows = new ArrayList<>();
        long offset = 0;
        while (true) {
            List<ArtifactEntity> page = artifacts
                    .findAllScoped(ofVersions(versionIds), new CursorPageable(offset, READ_PAGE, CHRONOLOGICAL))
                    .getContent();
            rows.addAll(page);
            if (page.size() < READ_PAGE) {
                break;
            }
            offset += READ_PAGE;
        }
        for (ArtifactEntity row : rows) {
            byVersion
                    .computeIfAbsent(row.getVersionId(), id -> new ArrayList<>())
                    .add(new VersionArtifactSlot(
                            row.getId(),
                            row.getPlatform().name(),
                            row.getFileName(),
                            row.getSize(),
                            row.getSha256(),
                            row.getStatus().name(),
                            row.getCreatedAt(),
                            row.getBuildNumber(),
                            row.getBundleId(),
                            row.getVersionName(),
                            row.getMinimumOs(),
                            row.extractionOutcome(),
                            provenanceSlot(row.getProvenance()),
                            row.getFormat(),
                            ValidationView.outcomeOf(row.getValidation()),
                            row.getStorageKey()));
        }
        return byVersion;
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /**
     * The artifact view (PRD 8.5 through S5-05): identity, platform,
     * the stored binary's name, size, and SHA-256, the pinned-or-
     * extracted build number, the lifecycle status, the uploader, the
     * server-derived storage key, and — since S5-04 (issue #23) — the
     * build-provenance block the uploader asserted at capture time
     * ({@code null} when a manual upload asserted none; never
     * fabricated). The extraction tier (S5-02) rides along: the
     * identity fields and the outcome — {@code extraction.status} is
     * PENDING in an upload response by construction (parsing is
     * async), which is exactly why the field exists: read the artifact
     * back to see where extraction landed. Since S5-05 (issue #24) the
     * view also carries the STRUCTURAL {@code format} verdict (the
     * channel-format fact of FR-006's AAB/APK rule; {@code null} until
     * a verdict exists) and the {@code validation} block — outcome,
     * attempts, duration, and every stage's status/code/reason in
     * pipeline order, the per-stage failure detail a FAILED artifact
     * must surface. {@code duplicateOf} carries the same-content
     * detection (AC4 of #20): informational, never a rejection, and
     * {@code duplicateOfTruncated} flags the 50-hit rendering cap so a
     * long duplicate list is never mistaken for the complete one.
     */
    public record ArtifactView(
            UUID id,
            UUID versionId,
            String platform,
            String fileName,
            long size,
            String sha256,
            Long buildNumber,
            String bundleId,
            String versionName,
            String minimumOs,
            String status,
            UUID uploadedBy,
            String storageKey,
            Instant uploadedAt,
            ProvenanceView provenance,
            ExtractionView extraction,
            String format,
            ValidationView validation,
            List<DuplicateOf> duplicateOf,
            boolean duplicateOfTruncated) {

        static ArtifactView from(ArtifactEntity artifact, List<DuplicateOf> duplicates, boolean truncated) {
            return new ArtifactView(
                    artifact.getId(),
                    artifact.getVersionId(),
                    artifact.getPlatform().name(),
                    artifact.getFileName(),
                    artifact.getSize(),
                    artifact.getSha256(),
                    artifact.getBuildNumber(),
                    artifact.getBundleId(),
                    artifact.getVersionName(),
                    artifact.getMinimumOs(),
                    artifact.getStatus().name(),
                    artifact.getUploadedBy(),
                    artifact.getStorageKey(),
                    artifact.getCreatedAt(),
                    ProvenanceView.from(artifact.getProvenance()),
                    ExtractionView.from(artifact),
                    artifact.getFormat(),
                    ValidationView.from(artifact.getValidation()),
                    List.copyOf(duplicates),
                    truncated);
        }
    }

    /**
     * The provenance block of an artifact view (issue #23, S5-04,
     * FR-005, PRD 8.5): the five frozen, client-asserted build claims
     * — {@code null} members for claims the uploader did not assert,
     * and a {@code null} block when none were. The names are the M2
     * CI-integration contract; shape-validated claims, never verified
     * facts (see docs/artifacts.md for the trust model).
     */
    public record ProvenanceView(
            String gitCommit, String gitBranch, String pipeline, String ciRunUrl, String buildTimestamp) {

        static ProvenanceView from(String document) {
            Provenance provenance = Provenance.from(document);
            return provenance == null
                    ? null
                    : new ProvenanceView(
                            provenance.gitCommit(),
                            provenance.gitBranch(),
                            provenance.pipeline(),
                            provenance.ciRunUrl(),
                            provenance.buildTimestamp());
        }
    }

    /**
     * The extraction state of one artifact, as views render it: the
     * outcome ({@code PENDING|SUCCESS|FAILED|BLOCKED}) plus the error
     * code and message when an outcome was recorded. Never the raw
     * jsonb documents — the view is typed, the document is storage.
     */
    public record ExtractionView(String status, String errorCode, String errorMessage) {

        /** Error documents are tiny; one mapper reads them all. */
        private static final tools.jackson.databind.ObjectMapper DOCUMENTS = new tools.jackson.databind.ObjectMapper();

        static ExtractionView from(ArtifactEntity artifact) {
            return new ExtractionView(
                    artifact.extractionOutcome(),
                    artifact.getExtractionError() == null ? null : documentField(artifact.getExtractionError(), "code"),
                    artifact.getExtractionError() == null
                            ? null
                            : documentField(artifact.getExtractionError(), "message"));
        }

        private static String documentField(String document, String field) {
            try {
                tools.jackson.databind.JsonNode value =
                        DOCUMENTS.readTree(document).get(field);
                return value == null || value.isNull() ? null : value.asText();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** One same-content hit: where else this exact binary is registered. */
    public record DuplicateOf(UUID artifactId, UUID versionId) {}

    /**
     * The validation state of one artifact (issue #24, S5-05, FR-006):
     * the LATEST attempt's outcome ({@code PENDING} before the first
     * attempt, then {@code PASSED|FAILED|BLOCKED}), the attempts
     * counter (revalidations are attempt N+1), the duration, and every
     * stage's status/code/reason in pipeline order — the per-stage
     * failure detail a FAILED artifact must surface, and the visible
     * BLOCKED record that keeps a stalled VALIDATING row honest. Typed
     * views, never the raw jsonb document.
     */
    public record ValidationView(String status, Integer attempts, Long durationMs, List<ValidationStageView> stages) {

        /** Documents are tiny; one mapper reads them all. */
        private static final tools.jackson.databind.ObjectMapper DOCUMENTS = new tools.jackson.databind.ObjectMapper();

        static ValidationView from(String document) {
            if (document == null) {
                return new ValidationView("PENDING", null, null, List.of());
            }
            try {
                tools.jackson.databind.JsonNode node = DOCUMENTS.readTree(document);
                List<ValidationStageView> stages = new java.util.ArrayList<>();
                for (tools.jackson.databind.JsonNode stage : node.path("stages")) {
                    stages.add(new ValidationStageView(
                            textOf(stage.get("name")),
                            textOf(stage.get("status")),
                            textOf(stage.get("code")),
                            textOf(stage.get("reason"))));
                }
                return new ValidationView(
                        textOf(node.get("outcome")),
                        node.hasNonNull("attempts") ? node.get("attempts").asInt() : null,
                        node.hasNonNull("durationMs") ? node.get("durationMs").asLong() : null,
                        List.copyOf(stages));
            } catch (RuntimeException e) {
                return new ValidationView("PENDING", null, null, List.of());
            }
        }

        /** The slot-rendering shortcut: the outcome string alone, or PENDING. */
        static String outcomeOf(String document) {
            return from(document).status();
        }

        private static String textOf(tools.jackson.databind.JsonNode node) {
            return node == null || node.isNull() ? null : node.asText();
        }
    }

    /** One pipeline stage's recorded outcome: name, status, and the code/reason a failure carries. */
    public record ValidationStageView(String name, String status, String code, String reason) {}

    /** The upload-session handoff (AC5): where to stream, and until when. */
    public record UploadSessionView(
            UUID sessionId, UUID artifactId, UUID versionId, String uploadUrl, Instant expiresAt) {}

    /** The finalize outcome: fresh registration (201) or state replay (200). */
    public record FinalizeView(boolean created, ArtifactView artifact) {}

    // -------------------------------------------------------------------------
    // Helpers: guards, keys, registration, duplicates, audit, metric
    // -------------------------------------------------------------------------

    /** Establishes the version's owning scope and runs the work inside it (issue #13's executor). */
    private <T> T inScope(VersionOwnership ownership, UUID actorId, java.util.function.Supplier<T> work) {
        return ownership.tenantId() != null
                ? scopeExecutor.inScope(ownership.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(ownership.organizationId(), actorId, work);
    }

    /** The version's owning dimension, or the concealed miss. */
    private VersionOwnership ownershipOf(UUID versionId) {
        return versions.ownershipOf(versionId).orElseThrow(() -> versionMissing());
    }

    /**
     * The application guards every upload shares: the application must
     * exist (the concealed miss), must not be archived
     * ({@code APPLICATION_ARCHIVED}, reserved for exactly this surface
     * since issue #13), and must have registered the target platform —
     * an ANDROID artifact of an iOS-only application is a broken
     * request, not a storage problem.
     */
    private ApplicationAccess.ApplicationProfile requireUploadableApplication(
            VersionOwnership ownership, Platform platform) {
        ApplicationAccess.ApplicationProfile application = applications
                .profileOf(ownership.applicationId())
                .orElseThrow(() ->
                        new VersionException(VersionException.Code.APPLICATION_NOT_FOUND, "application not found"));
        if ("ARCHIVED".equals(application.status())) {
            throw new ArtifactException(
                    ArtifactException.Code.APPLICATION_ARCHIVED, "application is archived; no new artifacts");
        }
        if (!application.platforms().contains(platform.name())) {
            throw invalid(platform.name() + " is not a registered platform of this application");
        }
        return application;
    }

    /**
     * The PRD 24 storage key: /{organizationId}/{tenantId}/{applicationId}/
     * {versionId}/{artifactId}/artifact — every segment a server-known
     * id (the artifact id allocated before any byte moved), the tenant
     * slot {@code _shared} for organization-owned versions. Addressing
     * only, never authorization (PRD 24's own note).
     */
    private static String keyOf(VersionOwnership ownership, UUID artifactId) {
        String tenantSegment =
                ownership.tenantId() != null ? ownership.tenantId().toString() : "_shared";
        return "%s/%s/%s/%s/%s/artifact"
                .formatted(
                        ownership.organizationId(),
                        tenantSegment,
                        ownership.applicationId(),
                        ownership.versionId(),
                        artifactId);
    }

    /**
     * Inserts the metadata row; the pinned-build race answers the precise 409.
     * The provenance document is written HERE and never after (issue
     * #23: upload-time capture, INV-003's immutable-after-insert). The
     * initial status is the caller's: {@code VALIDATING} for the
     * installable-package paths (the pipeline moves it on), {@code
     * READY} for OTA bundle binaries (no package structure exists to
     * validate, S16-03).
     */
    private ArtifactEntity register(
            UUID artifactId,
            VersionOwnership ownership,
            Platform platform,
            String fileName,
            String storageKey,
            String sha256,
            long size,
            Long buildNumber,
            String provenanceDocument,
            UUID actorId,
            ArtifactEntity.Status initialStatus) {
        ArtifactEntity entity = new ArtifactEntity(
                artifactId,
                ownership.versionId(),
                ownership.organizationId(),
                ownership.tenantId(),
                platform,
                fileName,
                storageKey,
                sha256,
                size,
                buildNumber,
                initialStatus,
                actorId,
                provenanceDocument);
        try {
            return artifacts.saveScoped(entity);
        } catch (DataIntegrityViolationException e) {
            if (buildNumber != null) {
                throw buildTaken();
            }
            throw e;
        }
    }

    /**
     * The same-content detection (AC4 of #20): every other
     * registration of this checksum in the application, capped at 50
     * rendered hits with the cap SIGNALED — the probe reads 51 so
     * {@code truncated} is true only when there really is more (the
     * #20 review's silent-truncation advisory, folded here at its
     * touchpoint).
     */
    private DuplicateScan duplicatesOf(String sha256, UUID applicationId, UUID excludeArtifactId) {
        Set<UUID> versionIds = new LinkedHashSet<>(versions.versionIdsOfApplication(applicationId));
        if (versionIds.isEmpty()) {
            return new DuplicateScan(List.of(), false);
        }
        List<ArtifactEntity> hits = artifacts
                .findAllScoped(
                        sameContent(sha256, versionIds, excludeArtifactId),
                        org.springframework.data.domain.PageRequest.of(0, DUPLICATE_RENDER_CAP + 1))
                .stream()
                .toList();
        boolean truncated = hits.size() > DUPLICATE_RENDER_CAP;
        List<DuplicateOf> duplicates = hits.stream()
                .limit(DUPLICATE_RENDER_CAP)
                .map(row -> new DuplicateOf(row.getId(), row.getVersionId()))
                .toList();
        return new DuplicateScan(duplicates, truncated);
    }

    /** The rendered-duplicate ceiling of every artifact view. */
    private static final int DUPLICATE_RENDER_CAP = 50;

    /** The duplicate probe's answer: the hits plus the cap signal. */
    private record DuplicateScan(List<DuplicateOf> duplicates, boolean truncated) {}

    private void auditUploaded(UUID actorId, VersionOwnership ownership, String path, ArtifactEntity artifact) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("platform", artifact.getPlatform().name());
        detail.put("fileName", artifact.getFileName());
        detail.put("size", Long.toString(artifact.getSize()));
        detail.put("sha256", artifact.getSha256());
        detail.put("path", path);
        // The incident-answer pair of issue #23: which commit, which
        // pipeline — present in the trail only when the uploader
        // asserted them (never fabricated).
        Provenance provenance = Provenance.from(artifact.getProvenance());
        if (provenance != null) {
            if (provenance.gitCommit() != null) {
                detail.put("gitCommit", provenance.gitCommit());
            }
            if (provenance.pipeline() != null) {
                detail.put("pipeline", provenance.pipeline());
            }
        }
        audit(AuditEventType.ARTIFACT_UPLOADED, actorId, ownership, artifact.getId(), detail);
        // The domain fact of the same moment (S12-01): the bus event
        // the outbox recorder makes durable in this transaction.
        events.publishEvent(new ArtifactUploadedEvent(
                artifact.getId(),
                ownership.versionId(),
                ownership.applicationId(),
                ownership.organizationId(),
                ownership.tenantId(),
                artifact.getPlatform().name(),
                artifact.getFileName(),
                artifact.getSize(),
                artifact.getSha256(),
                actorId,
                Instant.now(clock)));
    }

    /** The version-slot rendering of the provenance block (issue #23). */
    private static VersionArtifacts.ArtifactProvenance provenanceSlot(String document) {
        Provenance provenance = Provenance.from(document);
        return provenance == null
                ? null
                : new VersionArtifacts.ArtifactProvenance(
                        provenance.gitCommit(),
                        provenance.gitBranch(),
                        provenance.pipeline(),
                        provenance.ciRunUrl(),
                        provenance.buildTimestamp());
    }

    private void audit(
            AuditEventType type,
            UUID actorId,
            VersionOwnership ownership,
            UUID artifactId,
            Map<String, String> detail) {
        audit.publish(AuditEvent.builder(type)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(ownership.organizationId())
                .tenantId(ownership.tenantId())
                .applicationId(ownership.applicationId())
                .versionId(ownership.versionId())
                .artifactId(artifactId)
                .success(true)
                .detail(detail)
                .build());
    }

    private Timer timer(String path, String outcome) {
        return Timer.builder(MetricNames.ARTIFACT_UPLOAD_DURATION)
                .tag("path", path)
                .tag("outcome", outcome)
                .register(meters);
    }

    private ArtifactView viewOf(ArtifactEntity artifact, DuplicateScan scan) {
        return ArtifactView.from(artifact, scan.duplicates(), scan.truncated());
    }

    private static Platform parsePlatform(String platformText) {
        if (platformText == null || platformText.isBlank()) {
            throw invalid("platform is required (IOS or ANDROID)");
        }
        try {
            return Platform.valueOf(platformText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("platform is IOS or ANDROID");
        }
    }

    /**
     * The client file name (AC1's pre-storage gate): a bare name of
     * at most 255 characters whose extension is on the allowlist AND
     * coherent with the platform — an {@code .ipa} of an ANDROID
     * upload (or an unknown extension) is rejected before any byte is
     * stored. The name is metadata only; the storage key never derives
     * from it.
     */
    private static String requireArtifactFileName(String fileName, Platform platform) {
        if (fileName == null || fileName.isBlank()) {
            throw invalid("file name is required");
        }
        String trimmed = fileName.trim();
        if (trimmed.length() > 255 || trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("\0")) {
            throw invalid("file name is a bare name of at most 255 characters");
        }
        int dot = trimmed.lastIndexOf('.');
        String extension = dot >= 0 ? trimmed.substring(dot).toLowerCase(Locale.ROOT) : "";
        Platform implied = ALLOWED_EXTENSIONS.get(extension);
        if (implied == null) {
            throw new ArtifactException(
                    ArtifactException.Code.ARTIFACT_FILE_TYPE_REJECTED,
                    "the file type is not supported; expected .ipa, .apk, or .aab");
        }
        if (implied != platform) {
            throw new ArtifactException(
                    ArtifactException.Code.ARTIFACT_FILE_TYPE_REJECTED,
                    trimmed + " does not match platform " + platform.name());
        }
        return trimmed;
    }

    private static void requireBuildNumber(Long buildNumber) {
        if (buildNumber != null && buildNumber < 0) {
            throw invalid("build number must not be negative");
        }
    }

    private static Specification<ArtifactEntity> pinnedBuild(UUID versionId, Platform platform, long buildNumber) {
        return (root, query, builder) -> builder.and(
                builder.equal(root.get("versionId"), versionId),
                builder.equal(root.get("platform"), platform),
                builder.equal(root.get("buildNumber"), buildNumber));
    }

    private static Specification<ArtifactEntity> ofVersions(Collection<UUID> versionIds) {
        return (root, query, builder) -> root.get("versionId").in(versionIds);
    }

    private static Specification<ArtifactEntity> sameContent(
            String sha256, Collection<UUID> versionIds, UUID excludeId) {
        return (root, query, builder) -> builder.and(
                builder.equal(root.get("sha256"), sha256),
                root.get("versionId").in(versionIds),
                builder.notEqual(root.get("id"), excludeId));
    }

    private static Specification<ArtifactUploadSessionEntity> sessionOf(UUID artifactId) {
        return (root, query, builder) -> builder.equal(root.get("artifactId"), artifactId);
    }

    /** A session or artifact of a different version is the concealed miss. */
    private static void requireOwnVersion(ArtifactUploadSessionEntity session, UUID versionId) {
        if (!session.getVersionId().equals(versionId)) {
            throw artifactMissing();
        }
    }

    private static void requireOwnVersion(ArtifactEntity artifact, UUID versionId) {
        if (!artifact.getVersionId().equals(versionId)) {
            throw artifactMissing();
        }
    }

    private static ArtifactException artifactMissing() {
        return new ArtifactException(ArtifactException.Code.ARTIFACT_NOT_FOUND, "artifact not found");
    }

    private static VersionException versionMissing() {
        return new VersionException(VersionException.Code.NOT_FOUND, "version not found");
    }

    private static ArtifactException buildTaken() {
        return new ArtifactException(
                ArtifactException.Code.ARTIFACT_BUILD_TAKEN,
                "the build number is already in use for this version and platform");
    }

    private static ArtifactException tooLarge() {
        return new ArtifactException(
                ArtifactException.Code.ARTIFACT_TOO_LARGE, "the artifact exceeds the maximum upload size");
    }

    private static ArtifactException invalid(String message) {
        return new ArtifactException(ArtifactException.Code.INVALID_REQUEST, message);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
