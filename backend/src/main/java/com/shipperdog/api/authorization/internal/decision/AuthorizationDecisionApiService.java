package com.shipperdog.api.authorization.internal.decision;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.artifact.ArtifactAccess.IssuanceInputs;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.AuthorizationDecision;
import com.shipperdog.api.authorization.AuthorizationService;
import com.shipperdog.api.authorization.ConcealedResourceAccessDeniedException;
import com.shipperdog.api.authorization.DecisionGuarded;
import com.shipperdog.api.authorization.DenialReason;
import com.shipperdog.api.authorization.internal.cache.AuthorizationDecisionCache.CachedGrant;
import com.shipperdog.api.authorization.internal.cache.DecisionInputs;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantService;
import com.shipperdog.api.config.CursorCodec;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** The registry's platform order the catalog card resolves and serializes in. */
    private static final List<String> PLATFORM_ORDER = List.of("IOS", "ANDROID");

    /**
     * The version-history page's fixed default (issue #37's technical
     * note: testers rarely scroll past 20, but releases accumulate);
     * an explicit {@code limit} still bounds through the platform's
     * [1, 100] rule.
     */
    private static final int HISTORY_PAGE_SIZE = 20;

    private final AuthorizationService authorization;
    private final ApplicationAccess applications;
    private final ArtifactAccess artifacts;
    private final VersionAccess versions;
    private final DecisionInputs decisionInputs;
    private final VersionAccessGrantService grants;
    private final TesterAccess testers;
    private final Clock clock;

    public AuthorizationDecisionApiService(
            AuthorizationService authorization,
            ApplicationAccess applications,
            ArtifactAccess artifacts,
            VersionAccess versions,
            DecisionInputs decisionInputs,
            VersionAccessGrantService grants,
            TesterAccess testers,
            Clock clock) {
        this.authorization = authorization;
        this.applications = applications;
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
     * The caller's authorized version set for one application (FR-013,
     * the S8-03 version-history page's data path): the effective-access
     * entries — newest first by the version sort key — each carrying
     * the entry's TTL (the nearest contributing grant expiry; null when
     * a contributor never expires) and the per-platform installable
     * slots the page renders as platform rows (build number, size,
     * upload date — AC4). The history page's default page size is the
     * story's fixed 20 (testers rarely scroll past, but releases
     * accumulate); {@code next_cursor} follows the platform envelope.
     * {@code application} carries the header facts a deep link cannot
     * know from the catalog card; {@code effectiveAccess} is the
     * two-switches flag.
     */
    public record EligibilityView(
            UUID tenantId,
            UUID applicationId,
            ApplicationCardView application,
            boolean effectiveAccess,
            Instant resolvedAt,
            List<Entry> entries,

            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_cursor")
            String nextCursor) {}

    /**
     * One authorized version of the application, with the platform
     * rows: one slot per platform carrying a distributable artifact —
     * the newest one, which is the artifact a download would serve
     * (S8-04 wires the entry point; the slot here is the metadata the
     * tester compares builds with).
     */
    public record Entry(
            UUID versionId,
            String versionName,
            String status,
            Instant expiresAt,

            @JsonProperty("platforms") List<SlotView> platforms) {}

    /** One platform row of a history entry (AC4). */
    public record SlotView(String platform, UUID artifactId, Long buildNumber, long sizeBytes, Instant uploadedAt) {}

    /**
     * The caller's authorized application catalog (issue #36, S8-02):
     * one card per application the tester can actually reach and
     * install — the set form the engine sanctions, never a client-side
     * filter over a bigger list (PRD 26). {@code effectiveAccess} is
     * true when at least one linked tenant passes the LIST decision
     * (the persona's two-switches rule); the platform list envelope
     * pages the cards.
     */
    public record CatalogView(
            boolean effectiveAccess,
            @JsonProperty("items") List<CatalogCardView> items,

            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("next_cursor")
            String nextCursor) {}

    /**
     * One catalog card (the story's card model, the contract S8-03's
     * history page and S8-04's download flow reuse): the application,
     * the latest authorized version, and the per-platform latest
     * installable resolution. {@code tenantId} is the dimension the
     * card is addressed through — the addressing the download decision
     * needs beside the version id.
     */
    public record CatalogCardView(
            UUID tenantId,
            ApplicationCardView application,
            VersionCardView latestAuthorizedVersion,
            List<PlatformCardView> platforms) {}

    /** The application facts of a card. */
    public record ApplicationCardView(UUID applicationId, String name, String status) {}

    /** The version facts of a card. */
    public record VersionCardView(UUID versionId, String versionName, String status) {}

    /**
     * One platform's install resolution: the newest authorized version
     * of the application carrying a distributable artifact for the
     * platform, with the tenant dimension the download decision for it
     * is addressed through.
     */
    public record PlatformCardView(String platform, UUID tenantId, UUID versionId, String versionName) {}

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
     * computation — the S8-03 history page's data path. Entries carry
     * the per-platform installable slots and page at the story's
     * fixed default of 20 (the technical note) through the platform
     * cursor envelope. An optional {@code versionId} narrows the set
     * to that one version — the portal's deep-link probe (AC5): a
     * version absent from the CALLER's effective set (unknown, a
     * foreign application's, or simply unauthorized) conceals as the
     * version grain's genuine miss, byte-for-byte (§12), so a shared
     * build link leaks nothing to a tester without the grant. A
     * console caller with no linked tester record answers
     * effectiveAccess=false and an empty set (their tester-persona
     * reach is nothing). A suspended tester conceals entirely — the
     * LIST decision's tenant layer denies before this resolution
     * runs, so they learn nothing (stricter than the admin view,
     * which lists retained grants to administrators).
     */
    @Transactional(readOnly = true)
    public EligibilityView eligibility(
            UUID subject, UUID tenantId, UUID applicationId, String limit, String cursor, UUID versionId) {
        AuthorizationDecision list =
                authorization.decideForApi(AuthorizationContext.listApplications(subject, tenantId));
        if (!list.allowed()) {
            throw new ConcealedResourceAccessDeniedException("tenant");
        }
        if (!decisionInputs.visibleToTenant(applicationId, tenantId)) {
            throw new ConcealedResourceAccessDeniedException("application");
        }
        ApplicationAccess.ApplicationSummary summary =
                applications.summaryOf(applicationId).orElse(null);
        if (summary == null) {
            throw new ConcealedResourceAccessDeniedException("application");
        }
        ApplicationCardView application = new ApplicationCardView(applicationId, summary.name(), summary.status());
        UUID testerId = testers.testerIdOfUser(subject).orElse(null);
        if (testerId == null) {
            return new EligibilityView(
                    tenantId, applicationId, application, false, Instant.now(clock), List.of(), null);
        }
        VersionAccessGrantService.EffectiveAccessView effective =
                grants.resolveEffective(tenantId, testerId, applicationId);
        List<VersionAccessGrantService.EffectiveEntryView> authorized = effective.entries();
        if (versionId != null) {
            authorized = authorized.stream()
                    .filter(entry -> entry.versionId().equals(versionId))
                    .toList();
            if (authorized.isEmpty()) {
                // The deep-link probe's miss: not in the caller's set
                // — for ANY reason — is the version grain's genuine
                // miss shape, never a distinguishable denial.
                throw new ConcealedResourceAccessDeniedException("version");
            }
        }
        ListQuery query =
                ListQuery.of(limit != null && !limit.isBlank() ? limit : String.valueOf(HISTORY_PAGE_SIZE), cursor);
        int from = (int) Math.min(query.offset(), authorized.size());
        int to = (int) Math.min(query.offset() + query.limit(), authorized.size());
        List<VersionAccessGrantService.EffectiveEntryView> page = authorized.subList(from, to);
        Map<UUID, List<ArtifactAccess.InstallableSlot>> slots = artifacts.installableSlotsOfVersions(page.stream()
                .map(VersionAccessGrantService.EffectiveEntryView::versionId)
                .toList());
        List<Entry> entries = page.stream()
                .map(entry -> new Entry(
                        entry.versionId(),
                        entry.versionName(),
                        entry.status(),
                        entry.expiresAt(),
                        slotViewsOf(slots.getOrDefault(entry.versionId(), List.of()))))
                .toList();
        boolean hasMore = to < authorized.size();
        return new EligibilityView(
                tenantId,
                applicationId,
                application,
                effective.effectiveAccess(),
                effective.resolvedAt(),
                entries,
                hasMore ? CursorCodec.encode(query.offset() + query.limit()) : null);
    }

    /** Maps the artifact module's slots into the API's serialized form, in the registry's platform order. */
    private List<SlotView> slotViewsOf(List<ArtifactAccess.InstallableSlot> slots) {
        Map<String, ArtifactAccess.InstallableSlot> byPlatform = new LinkedHashMap<>();
        for (ArtifactAccess.InstallableSlot slot : slots) {
            byPlatform.put(slot.platform(), slot);
        }
        return PLATFORM_ORDER.stream()
                .filter(byPlatform::containsKey)
                .map(platform -> {
                    ArtifactAccess.InstallableSlot slot = byPlatform.get(platform);
                    return new SlotView(
                            slot.platform(),
                            slot.artifactId(),
                            slot.buildNumber(),
                            slot.sizeBytes(),
                            slot.uploadedAt());
                })
                .toList();
    }

    /**
     * Resolves the caller's application catalog (S8-02): every
     * application the tester persona holds a live, effective grant
     * through a linked, LIST-sanctioned tenant — the server-computed
     * reachability set the portal renders (never a filtered bigger
     * list, PRD 26). Candidates derive from the tester's own ACTIVE
     * grants (a non-empty effective set REQUIRES a live grant, INV-005
     * deny-by-default, so nothing grantless is probed); a pair the
     * tenant can no longer see (a de-authorized shared application)
     * drops the same way the eligibility route's concealment would.
     * Cards carry the latest authorized version and, per platform, the
     * newest authorized version with a distributable artifact — an
     * application whose authorized versions yield no installable
     * platform has no card (no dead cards, the story's AC3).
     *
     * <p>A caller without a linked tester record (a console persona)
     * answers the honest empty catalog; a suspended tester's tenants
     * all fail the LIST decision, so the answer is empty for them too
     * — the set form conceals by emptiness, with no addressed resource
     * to 404 at on this route.
     */
    @Transactional(readOnly = true)
    public CatalogView catalog(UUID subject, ListQuery query) {
        UUID testerId = testers.testerIdOfUser(subject).orElse(null);
        if (testerId == null) {
            return new CatalogView(false, List.of(), null);
        }
        // Linked tenants in a deterministic order; only tenants the
        // engine's LIST decision sanctions contribute (the eligibility
        // route's own gate, applied per dimension).
        List<UUID> sanctionedTenants = decisionInputs.tenantsOfTester(testerId).stream()
                .sorted()
                .filter(tenantId -> authorization
                        .decideForApi(AuthorizationContext.listApplications(subject, tenantId))
                        .allowed())
                .toList();
        if (sanctionedTenants.isEmpty()) {
            return new CatalogView(false, List.of(), null);
        }

        // One working set per application: the merged newest-first
        // entries with each entry's tenant dimension. Grants dedupe
        // BEFORE the visibility probe (the #36 review's low finding,
        // folded here): the probe is the cached read, and one grant
        // per application is all the reach a dimension contributes.
        Map<UUID, List<DimensionalEntry>> entriesByApplication = new LinkedHashMap<>();
        for (UUID tenantId : sanctionedTenants) {
            List<UUID> applicationIds = decisionInputs.grantsOf(tenantId, testerId).stream()
                    .map(CachedGrant::applicationId)
                    .distinct()
                    .filter(applicationId -> decisionInputs.visibleToTenant(applicationId, tenantId))
                    .sorted()
                    .toList();
            for (UUID applicationId : applicationIds) {
                List<VersionAccessGrantService.EffectiveEntryView> effective = grants.resolveEffective(
                                tenantId, testerId, applicationId)
                        .entries();
                if (effective.isEmpty()) {
                    continue; // only unauthorized or expired versions (AC3)
                }
                List<DimensionalEntry> merged =
                        entriesByApplication.computeIfAbsent(applicationId, key -> new ArrayList<>());
                for (VersionAccessGrantService.EffectiveEntryView entry : effective) {
                    merged.add(new DimensionalEntry(
                            tenantId, entry.versionId(), entry.versionName(), entry.status(), entry.sortKey()));
                }
            }
        }

        List<CatalogCardView> cards = new ArrayList<>();
        for (Map.Entry<UUID, List<DimensionalEntry>> application : entriesByApplication.entrySet()) {
            cardOf(application.getKey(), application.getValue()).ifPresent(cards::add);
        }
        cards.sort(Comparator.comparing(
                        (CatalogCardView card) -> card.application().name().toLowerCase(Locale.ROOT))
                .thenComparing(card -> card.application().applicationId()));

        int from = (int) Math.min(query.offset(), cards.size());
        int to = (int) Math.min(query.offset() + query.limit(), cards.size());
        boolean hasMore = to < cards.size();
        return new CatalogView(
                true,
                List.copyOf(cards.subList(from, to)),
                hasMore ? CursorCodec.encode(query.offset() + query.limit()) : null);
    }

    /**
     * Builds one application's card from its merged newest-first
     * entries across tenant dimensions, or empty when no platform
     * resolves to an installable version (the no-dead-cards rule).
     * The merged entries order GLOBALLY by the version sort key
     * descending (the #36 review's medium finding, folded by #37):
     * with the same shared application granted through two tenants
     * holding divergent version windows, the first dimension's
     * newest is not necessarily the pair's newest, so the latest-
     * authorized pick and the per-platform resolution both walk the
     * globally newest-first order. The stable sort keeps the
     * deterministic insertion order (sanctioned tenants sorted, then
     * applications sorted) as the tiebreak — byte-stable cards.
     */
    private Optional<CatalogCardView> cardOf(UUID applicationId, List<DimensionalEntry> merged) {
        ApplicationAccess.ApplicationSummary summary =
                applications.summaryOf(applicationId).orElse(null);
        if (summary == null) {
            return Optional.empty();
        }
        List<DimensionalEntry> newestFirst = merged.stream()
                .sorted(Comparator.comparing(DimensionalEntry::sortKey).reversed())
                .toList();
        Map<UUID, Set<String>> platformsByVersion = artifacts.distributablePlatformsOfVersions(
                newestFirst.stream().map(DimensionalEntry::versionId).toList());
        List<PlatformCardView> platforms = new ArrayList<>();
        Set<String> resolvedPlatforms = new HashSet<>();
        for (String platform : PLATFORM_ORDER) {
            for (DimensionalEntry entry : newestFirst) {
                if (platformsByVersion.getOrDefault(entry.versionId(), Set.of()).contains(platform)) {
                    platforms.add(
                            new PlatformCardView(platform, entry.tenantId(), entry.versionId(), entry.versionName()));
                    resolvedPlatforms.add(platform);
                    break;
                }
            }
        }
        if (resolvedPlatforms.isEmpty()) {
            return Optional.empty(); // authorized but nothing installable
        }
        DimensionalEntry latest = newestFirst.getFirst();
        return Optional.of(new CatalogCardView(
                latest.tenantId(),
                new ApplicationCardView(applicationId, summary.name(), summary.status()),
                new VersionCardView(latest.versionId(), latest.versionName(), latest.status()),
                List.copyOf(platforms)));
    }

    /** One effective entry with the tenant dimension it resolved through. */
    private record DimensionalEntry(UUID tenantId, UUID versionId, String versionName, String status, String sortKey) {}

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
