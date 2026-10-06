package com.shipperdog.api.authorization.internal.decision;

import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.artifact.ArtifactAccess.IssuanceInputs;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.AuthorizationDecision;
import com.shipperdog.api.authorization.AuthorizationService;
import com.shipperdog.api.authorization.ConcealedResourceAccessDeniedException;
import com.shipperdog.api.authorization.DecisionGuarded;
import com.shipperdog.api.authorization.DenialReason;
import com.shipperdog.api.authorization.internal.cache.DecisionInputs;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantService;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first-party decision API (issue #32, S7-03): the stable
 * internal contract the tester portal (E8) and the download path
 * consume — the CALLER is the decided subject, unlike the
 * platform-administrator inspection surface. Two questions:
 *
 * <ul>
 *   <li><strong>Download decision</strong> — "can I fetch version V
 *       on platform P through tenant T?" The layered engine decides
 *       the full PRD 11/12 chain (audited with
 *       {@code surface=decision-api}); an ALLOW carries the signed-
 *       URL issuance inputs — the artifact id and its object-storage
 *       key reference (acceptance criterion 4) — which S8-04 mints
 *       the download URL from. NOT a public third-party authorize
 *       endpoint (out of scope by the story) and not a URL minter:
 *       the decision is the API, the signing is S8-04's.</li>
 *   <li><strong>Eligibility listing</strong> — "which versions of
 *       application A can I see?" (FR-013's version-history page).
 *       The set form the engine sanctions: the LIST_APPLICATIONS
 *       decision over the tenant, then the application's visibility
 *       to the tenant, then the effective-set resolution — the same
 *       computation the administrator's effective-access view runs,
 *       self-scoped and stripped of administrative detail (no
 *       source-grant attribution, no expired-grant accounting).</li>
 * </ul>
 *
 * <p><strong>Concealment (PRD 12):</strong> denials never leak which
 * layer failed or whether the resource exists. The download route
 * conceals at the grains it addresses, exactly the artifact read's
 * two-grain rule: an artifact-grain miss (unknown or foreign
 * artifact, platform without a distributable artifact) renders
 * ARTIFACT_NOT_FOUND; every other denial — including the entitlement
 * denial — renders VERSION_NOT_FOUND. The eligibility route conceals
 * its tenant-grain denial as TENANT_NOT_FOUND and an application the
 * tenant cannot see as APPLICATION_NOT_FOUND; a tester with no
 * applicable grants legitimately sees an EMPTY entry list (the set
 * form answers what they may reach, and the answer can be nothing).
 *
 * <p><strong>Performance (PRD 42):</strong> the decision path
 * resolves its repeatable inputs through the decision-input cache
 * (the grant set, membership effectiveness, visibility); the engine's
 * layer walk itself is in-process. Authentication is the security
 * chain's; the ENGINE is the route's entire authority — a pure tester
 * holds no RBAC role by design (S6-03), so a permission gate here
 * would exclude exactly the persona the portal serves.
 */
@Service
@DecisionGuarded
public class AuthorizationDecisionApiService {

    /** The platform vocabulary of the registry (S3-01). */
    private static final Set<String> PLATFORMS = Set.of("IOS", "ANDROID");

    private final AuthorizationService authorization;
    private final ArtifactAccess artifacts;
    private final VersionAccess versions;
    private final DecisionInputs decisionInputs;
    private final VersionAccessGrantService grants;
    private final TesterAccess testers;
    private final Clock clock;

    public AuthorizationDecisionApiService(
            AuthorizationService authorization,
            ArtifactAccess artifacts,
            VersionAccess versions,
            DecisionInputs decisionInputs,
            VersionAccessGrantService grants,
            TesterAccess testers,
            Clock clock) {
        this.authorization = authorization;
        this.artifacts = artifacts;
        this.versions = versions;
        this.decisionInputs = decisionInputs;
        this.grants = grants;
        this.testers = testers;
        this.clock = clock;
    }

    /**
     * The ALLOWED download decision's payload: the issuance inputs
     * S8-04 signs — the artifact id and the storage key reference —
     * beside the addressing the caller supplied (the application the
     * engine derived, the version, the platform).
     */
    public record DownloadDecisionView(
            UUID applicationId, UUID versionId, UUID artifactId, String platform, String storageKey) {}

    /**
     * The caller's authorized version set for one application: the
     * effective-access entries the portal's version-history page
     * renders (FR-013) — newest first, each carrying the entry's TTL
     * (the nearest contributing grant expiry; null when a contributor
     * never expires). {@code effectiveAccess} is the two-switches
     * flag; a suspended caller keeps their entries listed but the
     * flag false (the engine denies their downloads regardless).
     */
    public record EligibilityView(
            UUID tenantId, UUID applicationId, boolean effectiveAccess, Instant resolvedAt, List<Entry> entries) {}

    /** One authorized version of the application. */
    public record Entry(UUID versionId, String versionName, String status, Instant expiresAt) {}

    /**
     * Decides the caller's download (PRD 12's worked example, the
     * strictest chain): every layer, INV-007's revoked-version block,
     * PRD 18's distributable-artifact availability, the FR-011
     * entitlement. On ALLOW, hands back the signed-URL issuance
     * inputs; on deny, the route's concealed miss (the two-grain
     * rule above — never a reason code, never a layer name).
     */
    @Transactional(readOnly = true)
    public DownloadDecisionView download(UUID subject, UUID tenantId, UUID versionId, String platform) {
        String pinned = requirePlatform(platform);
        Optional<IssuanceInputs> issuance = artifacts.issuanceInputsOf(versionId, pinned);
        AuthorizationDecision decision = authorization.decideForApi(AuthorizationContext.download(
                subject,
                tenantId,
                null,
                versionId,
                issuance.map(IssuanceInputs::artifactId).orElse(null),
                pinned));
        if (!decision.allowed()) {
            throw new ConcealedResourceAccessDeniedException(artifactGrain(decision) ? "artifact" : "version");
        }
        // The engine allowed the version-grain chain (the artifact
        // layer is skipped when no artifact id was addressable): with
        // no distributable artifact for the platform the miss is the
        // artifact grain — the same shape a genuine foreign-artifact
        // miss renders.
        return issuance.map(inputs -> new DownloadDecisionView(
                        versions.stateOf(versionId)
                                .map(VersionAccess.VersionState::applicationId)
                                .orElse(null),
                        versionId,
                        inputs.artifactId(),
                        pinned,
                        inputs.storageKey()))
                .orElseThrow(() -> new ConcealedResourceAccessDeniedException("artifact"));
    }

    /**
     * Resolves the caller's authorized version set for the
     * application: the LIST decision over the tenant (the set form's
     * sanctioned skip of the per-item application layer), the
     * application's visibility to the tenant, then the effective-set
     * computation. A console caller with no linked tester record
     * answers effectiveAccess=false and an empty set (their
     * tester-persona reach is nothing). A suspended tester conceals
     * entirely — the LIST decision's tenant layer denies before this
     * resolution runs, so they learn nothing (stricter than the admin
     * view, which lists retained grants to administrators).
     */
    @Transactional(readOnly = true)
    public EligibilityView eligibility(UUID subject, UUID tenantId, UUID applicationId) {
        AuthorizationDecision list =
                authorization.decideForApi(AuthorizationContext.listApplications(subject, tenantId));
        if (!list.allowed()) {
            throw new ConcealedResourceAccessDeniedException("tenant");
        }
        if (!decisionInputs.visibleToTenant(applicationId, tenantId)) {
            throw new ConcealedResourceAccessDeniedException("application");
        }
        UUID testerId = testers.testerIdOfUser(subject).orElse(null);
        if (testerId == null) {
            return new EligibilityView(tenantId, applicationId, false, Instant.now(clock), List.of());
        }
        VersionAccessGrantService.EffectiveAccessView effective =
                grants.resolveEffective(tenantId, testerId, applicationId);
        List<Entry> entries = effective.entries().stream()
                .map(entry -> new Entry(entry.versionId(), entry.versionName(), entry.status(), entry.expiresAt()))
                .toList();
        return new EligibilityView(
                tenantId, applicationId, effective.effectiveAccess(), effective.resolvedAt(), entries);
    }

    /** The artifact-grain denial reasons (the artifact read route's own set). */
    private static boolean artifactGrain(AuthorizationDecision decision) {
        DenialReason reason = decision.reason();
        return reason == DenialReason.ARTIFACT_MISSING
                || reason == DenialReason.ARTIFACT_PLATFORM_MISMATCH
                || reason == DenialReason.ARTIFACT_NOT_AVAILABLE;
    }

    /** The platform vocabulary is closed (the registry's own rule). */
    private static String requirePlatform(String platform) {
        if (platform == null) {
            throw new AuthorizationDecisionApiException("platform is required (IOS or ANDROID)");
        }
        String normalized = platform.trim().toUpperCase(Locale.ROOT);
        if (!PLATFORMS.contains(normalized)) {
            throw new AuthorizationDecisionApiException("platform must be IOS or ANDROID");
        }
        return normalized;
    }

    /** A malformed decision-API request (400 INVALID_REQUEST). */
    public static final class AuthorizationDecisionApiException extends RuntimeException {

        public AuthorizationDecisionApiException(String message) {
            super(message);
        }
    }
}
