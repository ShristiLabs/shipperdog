package com.shipperdog.api.otabundle.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.application.ApplicationAccess.ApplicationProfile;
import com.shipperdog.api.artifact.OtaBundleArtifacts;
import com.shipperdog.api.artifact.OtaBundleArtifacts.BundleRegistration;
import com.shipperdog.api.artifact.OtaBundleArtifacts.SessionGrant;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.otabundle.OtaBundleException;
import com.shipperdog.api.otabundle.OtaBundleSignedEvent;
import com.shipperdog.api.otabundle.internal.entity.OtaBundleEntity;
import com.shipperdog.api.otabundle.internal.entity.OtaSigningKeyEntity;
import com.shipperdog.api.otabundle.internal.repository.OtaBundleScopedRepository;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.version.BundleVersionCreation;
import com.shipperdog.api.version.VersionAccess;
import com.shipperdog.api.version.VersionAccess.VersionOwnership;
import com.shipperdog.api.version.VersionException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The bundle authoring orchestration (S16-03, issue #165, ADR-0010
 * workstream 2): create grants the artifact module's bundle upload
 * session under the bundle's own freshly created versions row (D2);
 * the client streams bytes direct to storage; finalize drives the
 * artifact registration, computes nothing the store has not already
 * hashed (the manifest IS the registration's checksum and size plus
 * the bundle's platform tag), signs under the app's Ed25519 key
 * (D8), lands the SIGNED bundle row, and publishes {@code
 * OtaBundleSignedEvent} on the outbox-backed bus (ADR-0007) — one
 * transaction, one coherent set of facts.
 *
 * <p>The two-phase shape the V55 schema forces (documented judgment):
 * {@code ota_bundles.artifact_id} is NOT NULL with an immediate
 * foreign key and {@code artifacts.sha256} is NOT NULL, so no bundle
 * row can exist before its bytes are fully stored and hashed. The
 * UPLOADING window therefore lives in the artifact session the create
 * response hands back; the bundle row lands at finalize. The bundle's
 * id IS the session's pre-allocated artifact id (D5: the bytes are
 * the bundle), so the client addresses finalize and every later read
 * with the id create answered, and a finalize retry replays the same
 * row.
 *
 * <p>The claims discipline mirrors the resumable artifact path's
 * provenance capture: the create's authoring claims (bundle version,
 * platform tag, forced, min app version) ride the session row and are
 * replayed at finalize — the finalize that lands hours later needs
 * nothing re-asserted by the client.
 */
@Service
public class OtaBundleService {

    private static final ObjectMapper CLAIMS = new ObjectMapper();

    /** The listing order: newest bundle first, the id breaking ties. */
    private static final org.springframework.data.domain.Sort NEWEST_FIRST = org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Order.desc("createdAt"),
            org.springframework.data.domain.Sort.Order.desc("id"));

    private final OtaBundleScopedRepository bundles;
    private final OtaSigningKeyService signingKeys;
    private final OtaBundleArtifacts bundleArtifacts;
    private final ApplicationAccess applications;
    private final VersionAccess versions;
    private final BundleVersionCreation versionCreation;
    private final TenantScopeExecutor scopeExecutor;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OtaBundleService(
            OtaBundleScopedRepository bundles,
            OtaSigningKeyService signingKeys,
            OtaBundleArtifacts bundleArtifacts,
            ApplicationAccess applications,
            VersionAccess versions,
            BundleVersionCreation versionCreation,
            TenantScopeExecutor scopeExecutor,
            ApplicationEventPublisher events,
            Clock clock) {
        this.bundles = bundles;
        this.signingKeys = signingKeys;
        this.bundleArtifacts = bundleArtifacts;
        this.applications = applications;
        this.versions = versions;
        this.versionCreation = versionCreation;
        this.scopeExecutor = scopeExecutor;
        this.events = events;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // Create: guards + the dedicated versions row + the session grant
    // -------------------------------------------------------------------------

    /**
     * Creates the bundle draft: validates the authoring surface
     * (OTA-enabled, non-archived application; semver-triple bundle
     * version — the D2 versions row's label vocabulary; shaped
     * platform tag, file name, min app version), creates the bundle's
     * dedicated versions row through the version module's own guarded
     * path, and grants the artifact module's bundle upload session
     * carrying the authoring claims. The answer is everything the
     * client needs to stream bytes and later finalize: the bundle id
     * (the pre-allocated artifact id), the version id, and the
     * session URL with its expiry.
     */
    @Transactional
    public DraftView create(UUID actorId, CreateCommand command) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        String bundleVersion = requireSemverLabel(command.bundleVersion(), fieldErrors);
        String platformTag = requirePlatformTag(command.platformTag(), fieldErrors);
        String fileName = requireFileName(command.fileName(), fieldErrors);
        String minAppVersion = requireMinAppVersion(command.minAppVersion(), fieldErrors);
        if (!fieldErrors.isEmpty()) {
            throw invalid("the bundle request is invalid", fieldErrors);
        }
        ApplicationProfile application =
                applications.profileOf(command.applicationId()).orElseThrow(OtaBundleService::applicationMissing);
        requireOtaAuthorable(application);
        return inScope(application, actorId, () -> {
            var version = versionCreation.createOtaBundleVersion(actorId, application.applicationId(), bundleVersion);
            String derivedPlatform = deriveStoragePlatform(platformTag, application);
            String claims = claimsDocument(bundleVersion, platformTag, command.forced(), minAppVersion);
            SessionGrant grant = bundleArtifacts.createBundleSession(
                    actorId, version.versionId(), fileName, derivedPlatform, command.sizeHint(), claims);
            return new DraftView(
                    grant.artifactId(),
                    application.applicationId(),
                    version.versionId(),
                    bundleVersion,
                    platformTag,
                    command.forced(),
                    minAppVersion,
                    OtaBundleEntity.Status.UPLOADING.name(),
                    new UploadGrantView(grant.artifactId(), grant.uploadUrl(), grant.expiresAt()));
        });
    }

    // -------------------------------------------------------------------------
    // Finalize: artifact registration + manifest + signature + event
    // -------------------------------------------------------------------------

    /**
     * Finalizes and signs: registers the bundle's binary through the
     * artifact port (the store's own hash-back checksum — finalize
     * computes nothing the storage has not already hashed), lands the
     * manifest (sha256, sizeBytes, platformTag), signs the checksum
     * under the application's active Ed25519 key (generating the key
     * on first use through the secret-store port, D8), inserts the
     * SIGNED bundle row, and publishes {@code OtaBundleSignedEvent}
     * in the same transaction the outbox recorder appends its row
     * under (ADR-0007). Replay-safe by construction: the bundle id is
     * the artifact id, so a retry either replays the landed row
     * ({@code created = false}) or re-enters at the artifact port's
     * own replay point.
     */
    @Transactional
    public FinalizeView finalizeBundle(UUID actorId, UUID bundleId, UUID versionId) {
        VersionOwnership ownership = versions.ownershipOf(versionId).orElseThrow(OtaBundleService::versionMissing);
        return inScope(ownership, actorId, () -> {
            OtaBundleEntity existing = bundles.findScoped(bundleId).orElse(null);
            if (existing != null) {
                // The replay answer: the row landed (this caller or a
                // concurrent one), every later transition it may since
                // have crossed included.
                requireOwnVersion(existing, versionId);
                return new FinalizeView(false, viewOf(existing));
            }
            BundleRegistration registration = bundleArtifacts.finalizeBundleSession(actorId, bundleId, versionId);
            JsonNode claims = parseClaims(registration.claimsDocument());
            ApplicationProfile application =
                    applications.profileOf(ownership.applicationId()).orElseThrow(OtaBundleService::applicationMissing);
            String bundleVersion = claimText(claims, "bundleVersion");
            String platformTag = claimText(claims, "platformTag");
            OtaSigningKeyEntity key = signingKeys.activeKeyOf(application, actorId);
            String signature = signingKeys.sign(key, registration.sha256Hex());
            OtaBundleEntity entity = new OtaBundleEntity(
                    bundleId,
                    ownership.organizationId(),
                    ownership.tenantId(),
                    ownership.applicationId(),
                    versionId,
                    registration.artifactId(),
                    bundleVersion,
                    platformTag,
                    registration.sha256Hex(),
                    registration.sizeBytes(),
                    claims.path("forced").asBoolean(false),
                    claimText(claims, "minAppVersion"),
                    OtaBundleEntity.Status.SIGNED,
                    actorId);
            entity.landSignature(signature, key.getKeyId());
            OtaBundleEntity saved;
            try {
                saved = bundles.saveScoped(entity);
            } catch (DataIntegrityViolationException e) {
                // A concurrent finalize of the same bundle landed first;
                // the artifact row registered exactly once and this caller
                // replays the winner.
                OtaBundleEntity winner = bundles.findScoped(bundleId).orElseThrow(() -> e);
                return new FinalizeView(false, viewOf(winner));
            }
            events.publishEvent(new OtaBundleSignedEvent(
                    saved.getId(),
                    saved.getVersionId(),
                    saved.getArtifactId(),
                    saved.getApplicationId(),
                    saved.getOrganizationId(),
                    saved.getTenantId(),
                    saved.getBundleVersion(),
                    saved.getPlatformTag(),
                    saved.getSha256(),
                    saved.getSizeBytes(),
                    saved.getKeyId(),
                    saved.getSignature(),
                    actorId,
                    Instant.now(clock)));
            return new FinalizeView(registration.created(), viewOf(saved));
        });
    }

    // -------------------------------------------------------------------------
    // Reads: the item, the listing, the signing key
    // -------------------------------------------------------------------------

    /** Reads one bundle; a foreign or unknown id is the concealed miss. */
    @Transactional(readOnly = true)
    public BundleView get(UUID actorId, UUID bundleId, UUID applicationId) {
        ApplicationProfile application =
                applications.profileOf(applicationId).orElseThrow(OtaBundleService::applicationMissing);
        return inScope(application, actorId, () -> {
            OtaBundleEntity bundle = bundles.findScoped(bundleId).orElseThrow(OtaBundleService::bundleMissing);
            requireOwnApplication(bundle, applicationId);
            return viewOf(bundle);
        });
    }

    /**
     * Lists the application's bundles, newest first, standard
     * limit/cursor pagination through {@code CursorCodec}; the
     * optional status filter narrows by the V55 vocabulary.
     */
    @Transactional(readOnly = true)
    public PageResponse<BundleView> list(UUID actorId, UUID applicationId, String status, int limit, long offset) {
        ApplicationProfile application =
                applications.profileOf(applicationId).orElseThrow(OtaBundleService::applicationMissing);
        return inScope(application, actorId, () -> {
            Specification<OtaBundleEntity> filter = ofApplication(applicationId);
            if (status != null && !status.isBlank()) {
                OtaBundleEntity.Status wanted = parseStatus(status.trim().toUpperCase(Locale.ROOT));
                filter = filter.and((root, query, builder) -> builder.equal(root.get("status"), wanted));
            }
            Page<OtaBundleEntity> page = bundles.findAllScoped(filter, new CursorPageable(offset, limit, NEWEST_FIRST));
            List<BundleView> items =
                    page.getContent().stream().map(OtaBundleService::viewOf).toList();
            return PageResponse.of(items, page.hasNext(), offset + limit);
        });
    }

    /**
     * The application's ACTIVE signing key for console/SDK embedding
     * (D8: the public half plus its key id, copyable before the SDK
     * ships). Before the first bundle there is no key — generation is
     * a finalize-time act, never a read side effect — and the read
     * answers the honest miss.
     */
    @Transactional(readOnly = true)
    public SigningKeyView signingKeyOf(UUID actorId, UUID applicationId) {
        ApplicationProfile application =
                applications.profileOf(applicationId).orElseThrow(OtaBundleService::applicationMissing);
        return inScope(application, actorId, () -> {
            OtaSigningKeyEntity key = signingKeys
                    .findActiveKeyOf(applicationId)
                    .orElseThrow(() -> new OtaBundleException(
                            OtaBundleException.Code.SIGNING_KEY_NOT_FOUND,
                            "no active signing key; finalize a bundle to generate one"));
            return new SigningKeyView(
                    applicationId, key.getKeyId(), key.getPublicKey(), key.getAlgorithm(), key.getCreatedAt());
        });
    }

    // -------------------------------------------------------------------------
    // Revoke: the author's withdrawal of a SIGNED bundle
    // -------------------------------------------------------------------------

    /**
     * Revokes a SIGNED bundle: the author's withdrawal (V55's REVOKED;
     * the device-side refusal lands with the check resolution, S16-07,
     * keyed on this status). RELEASED and already-REVOKED bundles are
     * not revocable through this surface (the S16-05 release-side
     * lifecycle owns them).
     */
    @Transactional
    public BundleView revoke(UUID actorId, UUID bundleId, UUID applicationId) {
        ApplicationProfile application =
                applications.profileOf(applicationId).orElseThrow(OtaBundleService::applicationMissing);
        return inScope(application, actorId, () -> {
            OtaBundleEntity bundle = bundles.findScoped(bundleId).orElseThrow(OtaBundleService::bundleMissing);
            requireOwnApplication(bundle, applicationId);
            if (bundle.getStatus() != OtaBundleEntity.Status.SIGNED) {
                throw new OtaBundleException(
                        OtaBundleException.Code.BUNDLE_NOT_REVOKABLE,
                        "only a SIGNED bundle can be revoked through this surface");
            }
            bundle.revoke();
            return viewOf(bundles.saveScoped(bundle));
        });
    }

    // -------------------------------------------------------------------------
    // Views and guards
    // -------------------------------------------------------------------------

    /** The create request as the controller parsed it. */
    public record CreateCommand(
            UUID applicationId,
            String bundleVersion,
            String platformTag,
            String fileName,
            boolean forced,
            String minAppVersion,
            Long sizeHint) {}

    /** The create answer: the draft's identity and its upload grant. */
    public record DraftView(
            UUID id,
            UUID applicationId,
            UUID versionId,
            String bundleVersion,
            String platformTag,
            boolean forced,
            String minAppVersion,
            String status,
            UploadGrantView upload) {}

    /** The direct-to-storage grant the client streams bytes with. */
    public record UploadGrantView(UUID artifactId, String uploadUrl, Instant expiresAt) {}

    /** The finalize answer: whether this call landed the row, plus the bundle. */
    public record FinalizeView(boolean created, BundleView bundle) {}

    /** One bundle's rendered state. */
    public record BundleView(
            UUID id,
            UUID applicationId,
            UUID versionId,
            UUID artifactId,
            UUID releaseId,
            String bundleVersion,
            String platformTag,
            String sha256,
            Long sizeBytes,
            String signature,
            String keyId,
            boolean forced,
            String minAppVersion,
            String status,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {}

    /** The ACTIVE signing key's public facts. */
    public record SigningKeyView(
            UUID applicationId, String keyId, String publicKey, String algorithm, Instant createdAt) {}

    private static BundleView viewOf(OtaBundleEntity bundle) {
        return new BundleView(
                bundle.getId(),
                bundle.getApplicationId(),
                bundle.getVersionId(),
                bundle.getArtifactId(),
                bundle.getReleaseId(),
                bundle.getBundleVersion(),
                bundle.getPlatformTag(),
                bundle.getSha256(),
                bundle.getSizeBytes(),
                bundle.getSignature(),
                bundle.getKeyId(),
                bundle.isForced(),
                bundle.getMinAppVersion(),
                bundle.getStatus().name(),
                bundle.getCreatedBy(),
                bundle.getCreatedAt(),
                bundle.getUpdatedAt());
    }

    /** Establishes the application's owning dimension and runs inside it. */
    private <T> T inScope(ApplicationProfile application, UUID actorId, java.util.function.Supplier<T> work) {
        return application.tenantId() != null
                ? scopeExecutor.inScope(application.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    private <T> T inScope(VersionOwnership ownership, UUID actorId, java.util.function.Supplier<T> work) {
        return ownership.tenantId() != null
                ? scopeExecutor.inScope(ownership.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(ownership.organizationId(), actorId, work);
    }

    /**
     * The OTA authoring guard: the application must exist (the
     * concealed miss), must not be archived, and must have opted in
     * to OTA bundle updates (V55's flag — the console toggle, S16-15).
     */
    private static void requireOtaAuthorable(ApplicationProfile application) {
        if ("ARCHIVED".equals(application.status())) {
            throw new OtaBundleException(
                    OtaBundleException.Code.APPLICATION_NOT_FOUND, "application is archived; no new bundles");
        }
        if (!application.otaEnabled()) {
            throw new OtaBundleException(
                    OtaBundleException.Code.APPLICATION_NOT_OTA_ENABLED,
                    "the application has not enabled OTA bundle updates");
        }
    }

    /**
     * The storage-row platform the closed V1 artifact vocabulary
     * forces: a mobile tag maps honestly; every other tag of V55's
     * OPEN vocabulary rides the application's first registered
     * platform — inert metadata for OTA flows, which resolve binaries
     * through {@code ota_bundles.artifact_id}, never through the
     * platform slots (the S16-03 documented judgment; the extended
     * platform vocabulary is the schema change that retires it).
     */
    private static String deriveStoragePlatform(String platformTag, ApplicationProfile application) {
        return switch (platformTag.toLowerCase(Locale.ROOT)) {
            case "ios" -> "IOS";
            case "android" -> "ANDROID";
            default -> application.platforms().get(0);
        };
    }

    private static String claimsDocument(
            String bundleVersion, String platformTag, boolean forced, String minAppVersion) {
        ObjectNode claims = JsonNodeFactory.instance.objectNode();
        claims.put("kind", "ota-bundle");
        claims.put("bundleVersion", bundleVersion);
        claims.put("platformTag", platformTag);
        claims.put("forced", forced);
        if (minAppVersion == null) {
            claims.putNull("minAppVersion");
        } else {
            claims.put("minAppVersion", minAppVersion);
        }
        return claims.toString();
    }

    private static JsonNode parseClaims(String document) {
        try {
            return CLAIMS.readTree(document);
        } catch (RuntimeException e) {
            throw new OtaBundleException(
                    OtaBundleException.Code.INVALID_REQUEST, "the bundle session's claims are unreadable");
        }
    }

    /** A claims member's text, null for absent or null (never "null" strings). */
    private static String claimText(JsonNode claims, String field) {
        JsonNode value = claims.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /**
     * The bundle version label: the D2 versions row carries it, so it
     * must satisfy the platform's version-name vocabulary — the semver
     * triple the version module's own creation guard enforces. The
     * shape is checked here for the field-error report; the unique
     * race the version module's constraint translates.
     */
    private static String requireSemverLabel(String value, Map<String, String> errors) {
        if (value == null || !value.matches("^(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})$")) {
            errors.put("bundleVersion", "bundleVersion must be a semantic version triple (e.g. 1.4.2)");
            return null;
        }
        return value;
    }

    /** V55's shape: 1..32 characters once trimmed, an OPEN vocabulary. */
    private static String requirePlatformTag(String value, Map<String, String> errors) {
        if (value == null) {
            errors.put("platformTag", "platformTag is required");
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 32) {
            errors.put("platformTag", "platformTag is 1 to 32 characters");
            return null;
        }
        return trimmed;
    }

    /** A bare name of at most 255 characters; any extension (a generic archive). */
    private static String requireFileName(String value, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            errors.put("fileName", "fileName is required");
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 255 || trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("\0")) {
            errors.put("fileName", "fileName is a bare name of at most 255 characters");
            return null;
        }
        return trimmed;
    }

    /** V55's shape: 1..100 characters once trimmed, optional. */
    private static String requireMinAppVersion(String value, Map<String, String> errors) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 100) {
            errors.put("minAppVersion", "minAppVersion is at most 100 characters");
            return null;
        }
        return trimmed;
    }

    private static OtaBundleEntity.Status parseStatus(String value) {
        try {
            return OtaBundleEntity.Status.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw invalid("status must be one of UPLOADING, VALIDATING, SIGNED, RELEASED, REVOKED");
        }
    }

    private static void requireOwnVersion(OtaBundleEntity bundle, UUID versionId) {
        if (!bundle.getVersionId().equals(versionId)) {
            throw bundleMissing();
        }
    }

    private static void requireOwnApplication(OtaBundleEntity bundle, UUID applicationId) {
        if (!bundle.getApplicationId().equals(applicationId)) {
            throw bundleMissing();
        }
    }

    private static Specification<OtaBundleEntity> ofApplication(UUID applicationId) {
        return (root, query, builder) -> builder.equal(root.get("applicationId"), applicationId);
    }

    private static OtaBundleException applicationMissing() {
        return new OtaBundleException(OtaBundleException.Code.APPLICATION_NOT_FOUND, "application not found");
    }

    /** The version module's own concealed miss, re-rendered by the handler. */
    private static VersionException versionMissing() {
        return new VersionException(VersionException.Code.NOT_FOUND, "version not found");
    }

    private static OtaBundleException bundleMissing() {
        return new OtaBundleException(OtaBundleException.Code.BUNDLE_NOT_FOUND, "bundle not found");
    }

    private static OtaBundleException invalid(String message) {
        return new OtaBundleException(OtaBundleException.Code.INVALID_REQUEST, message);
    }

    private static OtaBundleException invalid(String message, Map<String, String> fieldErrors) {
        return new OtaBundleException(OtaBundleException.Code.INVALID_REQUEST, message, fieldErrors);
    }
}
