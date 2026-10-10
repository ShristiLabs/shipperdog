package com.shipperdog.api.artifact.internal.download;

import com.shipperdog.api.artifact.ArtifactDownloadIssuedEvent;
import com.shipperdog.api.artifact.ArtifactException;
import com.shipperdog.api.artifact.ArtifactStore;
import com.shipperdog.api.artifact.DownloadChannelRouting;
import com.shipperdog.api.artifact.DownloadChannelRouting.Channel;
import com.shipperdog.api.artifact.DownloadChannelRouting.Route;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity;
import com.shipperdog.api.artifact.internal.repository.ArtifactScopedRepository;
import com.shipperdog.api.artifact.internal.storage.ArtifactStorageProperties;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.AuthorizationDecision;
import com.shipperdog.api.authorization.AuthorizationService;
import com.shipperdog.api.authorization.ConcealedResourceAccessDeniedException;
import com.shipperdog.api.authorization.DecisionGuarded;
import com.shipperdog.api.authorization.DenialReason;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.version.VersionAccess;
import com.shipperdog.api.version.VersionAccess.VersionOwnership;
import com.shipperdog.api.version.VersionException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The download issuance service (issue #38, S8-04, FR-014, PRD 24/42):
 * the M1 kernel's climax — Authorize, Portal, Download — closes here.
 * One artifact-addressed call runs the layered engine's DOWNLOAD
 * decision for the CALLER (the same {@code surface=decision-api}
 * decision the portal's other surfaces ride), and on ALLOW mints the
 * short-lived signed URL that is the binary's ONLY egress (the storage
 * key never appears in any client-visible payload; INV-005 means no
 * URL is ever minted on deny).
 *
 * <p><strong>The relaxation (#37's recorded follow-up):</strong> this
 * route carries no console-grade {@code app:artifacts:read} RBAC gate.
 * The engine is the download authority, exactly as
 * {@code ExplicitAuthorizationBypassTest} pins — a pure tester holds
 * no RBAC role by design (S6-03), so a permission gate here would
 * exclude precisely the persona the download serves; the console
 * personas still pass through the engine's role-overlay entitlement.
 *
 * <p><strong>The URL mechanism (ADR-0003's download-tier
 * amendment):</strong> production deployments mint GCS V4-signed URLs
 * through the store; emulator deployments (local/CI) mint an
 * HMAC-signed backend proxy URL — fake-gcs-server does not verify V4
 * signatures, and the proxy carries the identical semantics (opaque
 * URL, server-owned TTL, expiry enforced at fetch). One contract, two
 * mechanisms, the seam documented in the ADR. INV-003 is untouched:
 * the port gains reads only, never overwrite or delete.
 *
 * <p><strong>Revocation immediacy (S7-04's rule):</strong> the URL TTL
 * is bounded at startup — at or under the decision-input cache TTL
 * (write-through invalidation makes a revocation effective on the next
 * decision in the healthy path; the cache TTL is the degraded-mode
 * backstop a minted URL must never outlive) and at or under the hard
 * 15-minute ceiling the story's acceptance criteria name.
 *
 * <p><strong>Evidence:</strong> every issuance audited as
 * {@code DOWNLOAD_ISSUED} (who/what/artifact/result, beside the
 * engine's own decision event), the per-artifact counter incremented
 * (V23, the S11-04 aggregate), the URL-generation timer recorded for
 * PRD 42's under-500 ms SLO, and the download counter metric
 * ({@code shipperdog.download.count}, the reserved PRD 28 name)
 * incremented per platform.
 */
@Service
@DecisionGuarded
public class ArtifactDownloadService {

    /** Artifacts egress as opaque binaries; the download disposition names the file. */
    private static final String CONTENT_TYPE = "application/octet-stream";

    /** The S5-05 format rule: AAB is Play-only, the direct channel serves APK. */
    private static final String AAB_FORMAT = "AAB";

    private final ArtifactScopedRepository artifacts;
    private final ArtifactStore store;
    private final ArtifactStorageProperties storageProperties;
    private final ArtifactDownloadProperties downloadProperties;
    private final DownloadTokenCodec tokens;
    private final ObjectProvider<AuthorizationService> authorization;
    private final TenantScopeExecutor scopeExecutor;
    private final VersionAccess versions;
    private final AuditEventPublisher audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TraceAttributes trace;
    private final MeterRegistry meters;

    public ArtifactDownloadService(
            ArtifactScopedRepository artifacts,
            ArtifactStore store,
            ArtifactStorageProperties storageProperties,
            ArtifactDownloadProperties downloadProperties,
            DownloadTokenCodec tokens,
            ObjectProvider<AuthorizationService> authorization,
            TenantScopeExecutor scopeExecutor,
            VersionAccess versions,
            AuditEventPublisher audit,
            ApplicationEventPublisher events,
            Clock clock,
            TraceAttributes trace,
            MeterRegistry meters,
            Environment environment) {
        this.artifacts = artifacts;
        this.store = store;
        this.storageProperties = storageProperties;
        this.downloadProperties = downloadProperties;
        this.tokens = tokens;
        this.authorization = authorization;
        this.scopeExecutor = scopeExecutor;
        this.versions = versions;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.trace = trace;
        this.meters = meters;
        requireTtlWithinBounds(environment);
    }

    /**
     * The ALLOWED download issuance answer: the addressing the caller
     * supplied, the routed channel and behavior the portal renders
     * guidance from, and the signed URL with its expiry — never the
     * storage key, never a reason code on deny.
     */
    public record DownloadIssuanceView(
            UUID artifactId,
            UUID versionId,
            String platform,
            String channel,
            String behavior,
            String url,
            Instant expiresAt,
            String fileName,
            long sizeBytes) {}

    /**
     * Issues one tester download (FR-014's worked example end to end):
     * the engine decides the full PRD 11/12 chain for the caller
     * (INV-007's revoked-version block, PRD 18's distributable
     * availability, the FR-011 entitlement), then — on ALLOW only —
     * the scoped read resolves the artifact, the direct channel's
     * format rule refuses an AAB as the artifact-grain miss (S5-05:
     * AAB is Play-only), the channel routing table resolves the
     * behavior, the URL mints, the counter increments, and the
     * issuance is audited. A denial renders the route's concealed
     * miss shapes, byte-identical to the genuine ones: an
     * artifact-grain miss (unknown/foreign artifact, platform without
     * a distributable artifact, an AAB on the direct channel) as
     * ARTIFACT_NOT_FOUND; every other denial as VERSION_NOT_FOUND —
     * the artifact read's own two-grain rule, never a layer name.
     */
    @Transactional
    public DownloadIssuanceView issue(UUID actorId, UUID artifactId, UUID versionId) {
        Timer.Sample sample = Timer.start(meters);
        AuthorizationDecision decision;
        try {
            decision = authorization
                    .getObject()
                    .decideForApi(AuthorizationContext.download(actorId, null, null, versionId, artifactId, null));
            if (!decision.allowed()) {
                throw concealed(decision);
            }
            DownloadIssuanceView issued = issueAllowed(actorId, artifactId, versionId);
            sample.stop(generationTimer("issued"));
            return issued;
        } catch (ConcealedResourceAccessDeniedException | ArtifactException | VersionException e) {
            sample.stop(generationTimer("denied"));
            throw e;
        }
    }

    private DownloadIssuanceView issueAllowed(UUID actorId, UUID artifactId, UUID versionId) {
        VersionOwnership ownership = versions.ownershipOf(versionId).orElseThrow(() -> versionMissing());
        return inScope(ownership, actorId, () -> {
            ArtifactEntity artifact =
                    artifacts.findScoped(artifactId).orElseThrow(ArtifactDownloadService::artifactMissing);
            if (!artifact.getVersionId().equals(versionId)) {
                throw artifactMissing();
            }
            // The direct channel's format rule (S5-05): an AAB cannot
            // install on a device, so the direct channel never serves
            // one — the miss is the artifact grain, byte-identical to
            // a version without any distributable Android artifact.
            if (artifact.getPlatform() == ArtifactEntity.Platform.ANDROID && AAB_FORMAT.equals(artifact.getFormat())) {
                throw new ConcealedResourceAccessDeniedException("artifact");
            }
            Route route = DownloadChannelRouting.route(artifact.getPlatform().name(), Channel.DIRECT)
                    .orElseThrow(() -> new IllegalStateException(
                            "no download route for " + artifact.getPlatform() + " on channel " + Channel.DIRECT));
            Duration ttl = downloadProperties.urlTtl();
            Instant expiresAt = Instant.now(clock).plus(ttl);
            String url = storageProperties.emulatorMode()
                    ? "/api/v1/downloads/"
                            + tokens.mint(new DownloadTokenCodec.Grant(
                                    artifactId,
                                    artifact.getStorageKey(),
                                    artifact.getFileName(),
                                    artifact.getSize(),
                                    expiresAt))
                    : store.signV4DownloadUrl(artifact.getStorageKey(), artifact.getFileName(), CONTENT_TYPE, ttl);

            artifact.recordDownloadIssued();
            artifacts.saveScoped(artifact);
            trace.application(ownership.applicationId());
            trace.version(versionId);
            trace.artifact(artifactId);
            meters.counter(
                            MetricNames.DOWNLOAD_COUNT,
                            "platform",
                            artifact.getPlatform().name())
                    .increment();
            auditIssued(actorId, ownership, artifact, route, ttl);
            // The domain fact of the same moment (S12-01, PRD 51's
            // TESTER_DOWNLOADED): the platform-side download event,
            // durable through the outbox in this transaction.
            events.publishEvent(new ArtifactDownloadIssuedEvent(
                    artifact.getId(),
                    ownership.versionId(),
                    ownership.applicationId(),
                    ownership.organizationId(),
                    ownership.tenantId(),
                    artifact.getPlatform().name(),
                    actorId,
                    Instant.now(clock)));

            return new DownloadIssuanceView(
                    artifactId,
                    versionId,
                    artifact.getPlatform().name(),
                    route.channel().name(),
                    route.behavior().name(),
                    url,
                    expiresAt,
                    artifact.getFileName(),
                    artifact.getSize());
        });
    }

    /** Every download issuance leaves who/what/artifact/result in the trail (AC3). */
    private void auditIssued(
            UUID actorId, VersionOwnership ownership, ArtifactEntity artifact, Route route, Duration ttl) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("platform", artifact.getPlatform().name());
        detail.put("channel", route.channel().name());
        detail.put("behavior", route.behavior().name());
        detail.put("fileName", artifact.getFileName());
        detail.put("size", Long.toString(artifact.getSize()));
        detail.put("urlTtlSeconds", Long.toString(ttl.toSeconds()));
        detail.put("mechanism", storageProperties.emulatorMode() ? "backend-proxy" : "gcs-v4");
        audit.publish(AuditEvent.builder(AuditEventType.DOWNLOAD_ISSUED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(ownership.organizationId())
                .tenantId(ownership.tenantId())
                .applicationId(ownership.applicationId())
                .versionId(ownership.versionId())
                .artifactId(artifact.getId())
                .success(true)
                .detail(detail)
                .build());
    }

    /** The conceal grain follows the route's addressing, never the failing layer (the artifact read's rule). */
    private static ConcealedResourceAccessDeniedException concealed(AuthorizationDecision decision) {
        DenialReason reason = decision.reason();
        boolean artifactGrain = reason == DenialReason.ARTIFACT_MISSING
                || reason == DenialReason.ARTIFACT_PLATFORM_MISMATCH
                || reason == DenialReason.ARTIFACT_NOT_AVAILABLE;
        return new ConcealedResourceAccessDeniedException(artifactGrain ? "artifact" : "version");
    }

    private <T> T inScope(VersionOwnership ownership, UUID actorId, java.util.function.Supplier<T> work) {
        return ownership.tenantId() != null
                ? scopeExecutor.inScope(ownership.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(ownership.organizationId(), actorId, work);
    }

    private Timer generationTimer(String outcome) {
        return Timer.builder(MetricNames.DOWNLOAD_URL_GENERATION_DURATION)
                .tag("outcome", outcome)
                .register(meters);
    }

    /**
     * The S7-04 bound, fail-fast at startup: the URL TTL must sit at
     * or under the decision-input cache TTL (the degraded-mode
     * staleness backstop a minted URL must never outlive) and at or
     * under the 15-minute ceiling FR-014's acceptance criteria name.
     * A misconfiguration is a startup fault, never a silent clamp —
     * the operator sees it before the first URL exists.
     */
    private void requireTtlWithinBounds(Environment environment) {
        requireTtlWithinBounds(
                downloadProperties.urlTtl(),
                environment.getProperty("shipperdog.authorization-cache.ttl", Duration.class, Duration.ofMinutes(15)));
    }

    /** The startup guard, static so its bounds pin without a context. */
    static void requireTtlWithinBounds(Duration urlTtl, Duration cacheTtl) {
        if (urlTtl.compareTo(ArtifactDownloadProperties.MAX_URL_TTL) > 0) {
            throw new IllegalStateException("shipperdog.artifact.download.url-ttl (" + urlTtl
                    + ") exceeds the FR-014 ceiling of " + ArtifactDownloadProperties.MAX_URL_TTL);
        }
        if (urlTtl.compareTo(cacheTtl) > 0) {
            throw new IllegalStateException("shipperdog.artifact.download.url-ttl (" + urlTtl
                    + ") exceeds the decision-input cache TTL (" + cacheTtl
                    + "); a signed URL must never outlive the revocation backstop");
        }
    }

    private static ArtifactException artifactMissing() {
        return new ArtifactException(ArtifactException.Code.ARTIFACT_NOT_FOUND, "artifact not found");
    }

    private static VersionException versionMissing() {
        return new VersionException(VersionException.Code.NOT_FOUND, "version not found");
    }
}
