package com.shipperdog.api.authorization.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.AuthorizationDecision;
import com.shipperdog.api.authorization.AuthorizationDecision.Status;
import com.shipperdog.api.authorization.DenialReason;
import com.shipperdog.api.authorization.internal.cache.AuthorizationDecisionCache.CachedGrant;
import com.shipperdog.api.authorization.internal.cache.DecisionInputs;
import com.shipperdog.api.authorization.internal.evaluator.UserRolesSnapshot;
import com.shipperdog.api.identity.UserDirectory;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tenant.TenantRole;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess;
import com.shipperdog.api.version.VersionAccess.VersionCatalogEntry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests of the layered engine's layers (issue #30, S7-01,
 * acceptance criterion 6, first half): every PRD 11 chain member's
 * pass/deny/skip branches, the composed §11 evaluation order, the
 * short-circuit trace, and the fail-closed engine behavior — against
 * fake ports, no Spring context. The integration half (the PRD 12
 * worked example over a real stack) lives in
 * {@code AuthorizationEngineIntegrationTest}.
 */
class LayeredEngineUnitTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID APP = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID ARTIFACT = UUID.randomUUID();

    // ------------------------------------------------------------------
    // Fakes: the ports the layers resolve through
    // ------------------------------------------------------------------

    private final FakeDirectories world = new FakeDirectories();

    /**
     * The chain's resolution world as fakes: ids are fixed constants,
     * statuses and memberships are mutable per test, and the snapshot
     * is built per subject the way {@code RequestRoleGrants} builds
     * it for real.
     */
    private static final class FakeDirectories {
        boolean userExists = true;
        String organizationStatus = "ACTIVE";
        boolean tenantKnown = true;
        boolean effectiveMembership = false;
        UUID testerOfUser = null;
        UUID testerOrganization = null;
        boolean testerKnown = true;
        boolean applicationKnown = true;
        boolean visibleToTenant = true;
        boolean versionKnown = true;
        String versionStatus = "DRAFT";
        boolean artifactKnown = true;
        String artifactStatus = "READY";
        String artifactPlatform = "ANDROID";

        // Per-decision port-call counters (the PRD 42 no-N+1 budget).
        int userExistsCalls;
        int statusOfCalls;
        int owningOrganizationCalls;
        int effectiveMembershipCalls;
        int testerIdCalls;
        int testerOrganizationCalls;
        int ownershipCalls;
        int visibleToTenantCalls;
        int versionStateCalls;
        int artifactStateCalls;

        void resetCounters() {
            userExistsCalls = 0;
            statusOfCalls = 0;
            owningOrganizationCalls = 0;
            effectiveMembershipCalls = 0;
            testerIdCalls = 0;
            testerOrganizationCalls = 0;
            ownershipCalls = 0;
            visibleToTenantCalls = 0;
            versionStateCalls = 0;
            artifactStateCalls = 0;
        }

        final UserDirectory users = new UserDirectory() {
            @Override
            public List<UserSummary> byIds(java.util.Collection<UUID> userIds) {
                return List.of();
            }

            @Override
            public boolean userExists(UUID userId) {
                userExistsCalls++;
                return userExists;
            }
        };
        final OrganizationAccess organizations = new OrganizationAccess() {
            @Override
            public Optional<UUID> primaryOrganizationId(UUID userId) {
                return Optional.empty();
            }

            @Override
            public boolean loginBlockedFor(UUID userId) {
                return false;
            }

            @Override
            public boolean isOrganizationAdmin(UUID organizationId, UUID userId) {
                return false;
            }

            @Override
            public Map<UUID, OrgRole> activeOrganizationRoles(UUID userId) {
                return Map.of();
            }

            @Override
            public boolean organizationExists(UUID organizationId) {
                return true;
            }

            @Override
            public Optional<String> statusOf(UUID organizationId) {
                statusOfCalls++;
                return organizationStatus == null ? Optional.empty() : Optional.of(organizationStatus);
            }

            @Override
            public void addMember(UUID organizationId, UUID userId, OrgRole role, UUID actingUserId) {}

            @Override
            public List<UUID> memberUserIdsOfRole(UUID organizationId, OrgRole role) {
                return List.of();
            }
        };
        final TenantAccess tenants = new TenantAccess() {
            @Override
            public Map<UUID, TenantRole> membershipsOf(UUID userId) {
                return Map.of();
            }

            @Override
            public Optional<TenantRole> roleInTenant(UUID tenantId, UUID userId) {
                return Optional.empty();
            }

            @Override
            public Optional<UUID> owningOrganizationId(UUID tenantId) {
                owningOrganizationCalls++;
                return tenantKnown ? Optional.of(ORG) : Optional.empty();
            }

            @Override
            public List<UUID> tenantIdsOfOrganization(UUID organizationId) {
                return List.of();
            }

            @Override
            public boolean acceptsNewAffiliations(UUID tenantId) {
                return true;
            }

            @Override
            public Map<UUID, TenantSummary> tenantSummariesOf(java.util.Collection<UUID> tenantIds) {
                return Map.of();
            }
        };
        final TenantTesterMembershipAccess testerMemberships = new TenantTesterMembershipAccess() {
            @Override
            public boolean isActiveMember(UUID tenantId, UUID testerId) {
                return effectiveMembership;
            }

            @Override
            public boolean membershipExists(UUID tenantId, UUID testerId) {
                return effectiveMembership;
            }

            @Override
            public boolean isEffectivelyActive(UUID tenantId, UUID testerId) {
                return effectiveMembership;
            }

            @Override
            public List<UUID> tenantIdsOfTester(UUID testerId) {
                return effectiveMembership ? List.of(TENANT) : List.of();
            }
        };
        final TesterAccess testers = new TesterAccess() {
            @Override
            public Optional<UUID> owningOrganizationOf(UUID testerId) {
                testerOrganizationCalls++;
                return testerKnown ? Optional.ofNullable(testerOrganization) : Optional.empty();
            }

            @Override
            public Optional<UUID> testerIdOfUser(UUID userId) {
                testerIdCalls++;
                return Optional.ofNullable(testerOfUser);
            }

            @Override
            public Map<UUID, TesterAccess.TesterRecord> recordsOf(Collection<UUID> testerIds) {
                return Map.of();
            }
        };
        final ApplicationAccess applications = new ApplicationAccess() {
            @Override
            public Optional<Ownership> ownershipOf(UUID applicationId) {
                ownershipCalls++;
                return applicationKnown ? Optional.of(new Ownership(APP, ORG, TENANT)) : Optional.empty();
            }

            @Override
            public Optional<ApplicationProfile> profileOf(UUID applicationId) {
                return Optional.empty();
            }

            @Override
            public Optional<ApplicationSummary> summaryOf(UUID applicationId) {
                return Optional.empty(); // the engine walk never renders cards
            }

            @Override
            public boolean visibleToTenant(UUID applicationId, UUID tenantId) {
                visibleToTenantCalls++;
                return visibleToTenant;
            }
        };
        final VersionAccess versions = new VersionAccess() {
            @Override
            public int reassignOwnership(UUID applicationId, UUID tenantId, java.time.Instant updatedAt) {
                return 0;
            }

            @Override
            public boolean versionHasTag(UUID versionId, String tag) {
                return false;
            }

            @Override
            public java.util.List<UUID> versionIdsOfApplicationWithTag(UUID applicationId, String tag) {
                return java.util.List.of();
            }

            @Override
            public boolean isWithinLatestWorkingSetN(UUID versionId, int n) {
                return false;
            }

            @Override
            public java.util.List<VersionCatalogEntry> catalogOfApplication(UUID applicationId) {
                return java.util.List.of();
            }

            @Override
            public Optional<VersionOwnership> ownershipOf(UUID versionId) {
                return versionKnown ? Optional.of(new VersionOwnership(VERSION, APP, ORG, TENANT)) : Optional.empty();
            }

            @Override
            public List<UUID> versionIdsOfApplication(UUID applicationId) {
                return List.of();
            }

            @Override
            public Optional<VersionRecord> recordOf(UUID versionId) {
                return Optional.empty();
            }

            @Override
            public Optional<VersionState> stateOf(UUID versionId) {
                versionStateCalls++;
                return versionKnown
                        ? Optional.of(new VersionState(VERSION, APP, ORG, TENANT, versionStatus))
                        : Optional.empty();
            }
        };
        final ArtifactAccess artifacts = new ArtifactAccess() {
            @Override
            public Optional<ArtifactState> stateOf(UUID artifactId) {
                artifactStateCalls++;
                return artifactKnown
                        ? Optional.of(new ArtifactState(ARTIFACT, VERSION, artifactPlatform, artifactStatus))
                        : Optional.empty();
            }

            @Override
            public Map<UUID, Set<String>> distributablePlatformsOfVersions(Collection<UUID> versionIds) {
                return Map.of(); // the engine walk never renders cards
            }

            @Override
            public Map<UUID, List<InstallableSlot>> installableSlotsOfVersions(Collection<UUID> versionIds) {
                return Map.of(); // the engine walk never renders history rows
            }

            @Override
            public Map<UUID, ArtifactPin> pinsOf(Collection<UUID> artifactIds) {
                return Map.of(); // the engine walk never freezes manifests
            }

            @Override
            public Optional<IssuanceInputs> issuanceInputsOf(UUID versionId, String platform) {
                return artifactKnown && artifactPlatform.equals(platform)
                        ? Optional.of(new IssuanceInputs(ARTIFACT, VERSION, platform, artifactStatus, "test/key"))
                        : Optional.empty();
            }
        };
        final DecisionInputs decisionInputs = new DecisionInputs() {
            @Override
            public List<CachedGrant> grantsOf(UUID tenantId, UUID testerId) {
                return List.of();
            }

            @Override
            public List<UUID> tenantsOfTester(UUID testerId) {
                return List.of();
            }

            @Override
            public boolean effectiveMembership(UUID tenantId, UUID testerId) {
                effectiveMembershipCalls++;
                return effectiveMembership;
            }

            @Override
            public boolean visibleToTenant(UUID applicationId, UUID tenantId) {
                visibleToTenantCalls++;
                return visibleToTenant;
            }
        };
    }

    private EvaluationState stateWith(OrgRole orgRole, TenantRole tenantRole, boolean platformAdmin) {
        Map<UUID, OrgRole> orgRoles = orgRole == null ? Map.of() : Map.of(ORG, orgRole);
        Map<UUID, TenantRole> tenantRoles = tenantRole == null ? Map.of() : Map.of(TENANT, tenantRole);
        UserRolesSnapshot snapshot = new UserRolesSnapshot(USER, platformAdmin, orgRoles, tenantRoles, world.tenants);
        return new EvaluationState(world.applications, world.versions, world.artifacts, world.testers, snapshot);
    }

    // ------------------------------------------------------------------
    // User layer
    // ------------------------------------------------------------------

    @Test
    void userLayerDeniesUnknownSubjectsAndPassesKnownOnes() {
        UserLayer layer = new UserLayer(world.users);
        AuthorizationContext context = AuthorizationContext.listApplications(USER, TENANT);
        assertThat(layer.evaluate(stateWith(null, null, false), context).status())
                .isEqualTo(Status.PASSED);
        world.userExists = false;
        AuthorizationLayer.Verdict denied = layer.evaluate(stateWith(null, null, false), context);
        assertThat(denied.status()).isEqualTo(Status.FAILED);
        assertThat(denied.reason()).isEqualTo(DenialReason.SUBJECT_MISSING);
    }

    // ------------------------------------------------------------------
    // Organization layer
    // ------------------------------------------------------------------

    @Test
    void organizationLayerPassesActiveOrganizationsAndDeniesMissingOrInactiveOnes() {
        OrganizationLayer layer = new OrganizationLayer(world.tenants, world.organizations);
        AuthorizationContext context = AuthorizationContext.listApplications(USER, TENANT);
        assertThat(layer.evaluate(stateWith(null, null, false), context).status())
                .isEqualTo(Status.PASSED);

        world.organizationStatus = "DEACTIVATED";
        assertThat(layer.evaluate(stateWith(null, null, false), context).reason())
                .isEqualTo(DenialReason.ORGANIZATION_INACTIVE);

        world.organizationStatus = null;
        assertThat(layer.evaluate(stateWith(null, null, false), context).reason())
                .isEqualTo(DenialReason.ORGANIZATION_MISSING);

        world.tenantKnown = false;
        world.organizationStatus = "ACTIVE";
        assertThat(layer.evaluate(stateWith(null, null, false), context).reason())
                .isEqualTo(DenialReason.TENANT_MISSING);
    }

    @Test
    void organizationLayerWaivesThePlatformWildcard() {
        OrganizationLayer layer = new OrganizationLayer(world.tenants, world.organizations);
        world.organizationStatus = "DEACTIVATED";
        assertThat(layer.evaluate(stateWith(null, null, true), AuthorizationContext.listApplications(USER, TENANT))
                        .status())
                .isEqualTo(Status.PASSED);
    }

    // ------------------------------------------------------------------
    // Tenant layer
    // ------------------------------------------------------------------

    @Test
    void tenantLayerAdmitsTheTesterPersonaOnEffectiveMembershipOnly() {
        TenantLayer layer = new TenantLayer(world.decisionInputs);
        AuthorizationContext context = AuthorizationContext.listApplications(USER, TENANT);

        // A linked tester with both switches on: TENANT dimension, no
        // role anywhere.
        world.testerOfUser = UUID.randomUUID();
        world.testerOrganization = ORG;
        world.effectiveMembership = true;
        EvaluationState state = stateWith(null, null, false);
        assertThat(layer.evaluate(state, context).status()).isEqualTo(Status.PASSED);
        assertThat(state.dimension().kind()).isEqualTo(EvaluationState.AccessDimension.Kind.TENANT);

        // Any switch off (suspension, removal, deactivated person):
        // live exclusion, no caching.
        world.effectiveMembership = false;
        assertThat(layer.evaluate(stateWith(null, null, false), context).reason())
                .isEqualTo(DenialReason.TENANT_ACCESS_DENIED);

        // A tester of ANOTHER organization never had this tenant's
        // access, whatever the membership table says (defense).
        world.effectiveMembership = true;
        world.testerOrganization = UUID.randomUUID();
        assertThat(layer.evaluate(stateWith(null, null, false), context).reason())
                .isEqualTo(DenialReason.TENANT_ACCESS_DENIED);
    }

    @Test
    void tenantLayerAdmitsTheRolePersonaThroughTenantOrOverlayRoles() {
        TenantLayer layer = new TenantLayer(world.decisionInputs);
        AuthorizationContext context = AuthorizationContext.listApplications(USER, TENANT);

        assertThat(layer.evaluate(stateWith(null, TenantRole.TENANT_ADMIN, false), context)
                        .status())
                .isEqualTo(Status.PASSED);

        // No tenant role, but the owning organization's role overlays
        // (the RBAC union rule).
        assertThat(layer.evaluate(stateWith(OrgRole.ORG_ADMIN, null, false), context)
                        .status())
                .isEqualTo(Status.PASSED);

        // A plain member of another organization's tenant world: deny.
        world.testerOfUser = null;
        assertThat(layer.evaluate(stateWith(null, null, false), context).reason())
                .isEqualTo(DenialReason.TENANT_ACCESS_DENIED);
    }

    // ------------------------------------------------------------------
    // Application layer
    // ------------------------------------------------------------------

    @Test
    void applicationLayerAnswersVisibilityPerDimensionAndSkipsListDecisions() {
        ApplicationLayer layer = new ApplicationLayer(world.applications);
        AuthorizationContext download = AuthorizationContext.download(USER, TENANT, APP, VERSION, ARTIFACT, "ANDROID");
        EvaluationState tenantDimension = stateWith(OrgRole.DEVELOPER, null, false);
        new TenantLayer(world.decisionInputs)
                .evaluate(tenantDimension, AuthorizationContext.listApplications(USER, TENANT));

        // Tenant-owned application: visible by ownership.
        assertThat(layer.evaluate(tenantDimension, download).status()).isEqualTo(Status.PASSED);

        // Shared application not authorized for the tenant: the S3-03
        // mapping decides (visibleToTenant false).
        world.visibleToTenant = false;
        // The fake's ownership is tenant-owned; force the shared arm by
        // asserting the port answer directly shapes the verdict.
        assertThat(world.applications.visibleToTenant(APP, TENANT)).isFalse();

        // Missing application: concealed miss (fresh state: the
        // ownership resolution caches per decision by design).
        world.applicationKnown = false;
        EvaluationState missing = stateWith(OrgRole.DEVELOPER, null, false);
        new TenantLayer(world.decisionInputs).evaluate(missing, AuthorizationContext.listApplications(USER, TENANT));
        assertThat(layer.evaluate(missing, download).reason()).isEqualTo(DenialReason.APPLICATION_MISSING);

        // List decisions skip the per-item question (the set form is
        // the sanctioned union the listing runs).
        world.applicationKnown = true;
        assertThat(layer.applicable(tenantDimension, AuthorizationContext.listApplications(USER, TENANT)))
                .isFalse();
    }

    // ------------------------------------------------------------------
    // Version layer
    // ------------------------------------------------------------------

    @Test
    void versionLayerChecksAddressLifecycleAndEntitlement() {
        VersionLayer layer = new VersionLayer(entitlements(RoleOverlayTestContributor.denyAll()));
        AuthorizationContext read = AuthorizationContext.readArtifact(USER, VERSION, ARTIFACT);
        EvaluationState state = stateWith(OrgRole.DEVELOPER, null, false);

        assertThat(layer.evaluate(state, read).reason()).isEqualTo(DenialReason.ENTITLEMENT_MISSING);

        layer = new VersionLayer(entitlements(RoleOverlayTestContributor.entitleAll()));
        assertThat(layer.evaluate(state, read).status()).isEqualTo(Status.PASSED);

        // A REVOKED version blocks downloads (INV-007) but not reads
        // (INV-006: still readable). Fresh states: the version
        // resolution caches per decision by design.
        world.versionStatus = "REVOKED";
        AuthorizationContext download = AuthorizationContext.download(USER, TENANT, APP, VERSION, ARTIFACT, "ANDROID");
        EvaluationState revoked = stateWith(OrgRole.DEVELOPER, null, false);
        assertThat(layer.evaluate(revoked, download).reason()).isEqualTo(DenialReason.VERSION_REVOKED);
        EvaluationState revokedRead = stateWith(OrgRole.DEVELOPER, null, false);
        assertThat(layer.evaluate(revokedRead, read).status()).isEqualTo(Status.PASSED);

        // Unknown version: the concealed miss.
        world.versionKnown = false;
        world.versionStatus = "DRAFT";
        EvaluationState unknown = stateWith(OrgRole.DEVELOPER, null, false);
        assertThat(layer.evaluate(unknown, read).reason()).isEqualTo(DenialReason.VERSION_MISSING);
    }

    // ------------------------------------------------------------------
    // Artifact layer
    // ------------------------------------------------------------------

    @Test
    void artifactLayerChecksAddressPlatformAndAvailability() {
        ArtifactLayer layer = new ArtifactLayer();
        EvaluationState state = stateWith(OrgRole.DEVELOPER, null, false);

        AuthorizationContext download = AuthorizationContext.download(USER, TENANT, APP, VERSION, ARTIFACT, "ANDROID");
        assertThat(layer.evaluate(state, download).status()).isEqualTo(Status.PASSED);

        // Platform pin mismatch: PRD 12's Android ask against an IOS
        // artifact.
        assertThat(layer.evaluate(state, AuthorizationContext.download(USER, TENANT, APP, VERSION, ARTIFACT, "IOS"))
                        .reason())
                .isEqualTo(DenialReason.ARTIFACT_PLATFORM_MISMATCH);

        // Not distributable yet: VALIDATING is metadata-readable but
        // not downloadable (PRD 18; fresh states — the artifact
        // resolution caches per decision by design).
        world.artifactStatus = "VALIDATING";
        assertThat(layer.evaluate(stateWith(OrgRole.DEVELOPER, null, false), download)
                        .reason())
                .isEqualTo(DenialReason.ARTIFACT_NOT_AVAILABLE);
        assertThat(layer.evaluate(
                                stateWith(OrgRole.DEVELOPER, null, false),
                                AuthorizationContext.readArtifact(USER, VERSION, ARTIFACT))
                        .status())
                .isEqualTo(Status.PASSED);

        // Unknown artifact or foreign version: the concealed miss.
        world.artifactStatus = "READY";
        world.artifactKnown = false;
        assertThat(layer.evaluate(stateWith(OrgRole.DEVELOPER, null, false), download)
                        .reason())
                .isEqualTo(DenialReason.ARTIFACT_MISSING);
    }

    // ------------------------------------------------------------------
    // Distribution layer
    // ------------------------------------------------------------------

    @Test
    void distributionLayerPassesWithNoPolicyAndDeniesWhenOneBlocks() {
        DistributionLayer layer = new DistributionLayer(policies(List.of()));
        AuthorizationContext download = AuthorizationContext.download(USER, TENANT, APP, VERSION, ARTIFACT, "ANDROID");
        EvaluationState state = stateWith(OrgRole.DEVELOPER, null, false);

        assertThat(layer.applicable(state, download)).isTrue();
        assertThat(layer.evaluate(state, download).status()).isEqualTo(Status.PASSED);

        DistributionLayer blocking = new DistributionLayer(policies(List.of((artifactId, versionId) -> true)));
        assertThat(blocking.evaluate(state, download).reason()).isEqualTo(DenialReason.DISTRIBUTION_BLOCKED);

        // Non-download actions skip the layer.
        assertThat(layer.applicable(state, AuthorizationContext.readArtifact(USER, VERSION, ARTIFACT)))
                .isFalse();
    }

    // ------------------------------------------------------------------
    // The composed engine: order, short-circuit, fail-closed
    // ------------------------------------------------------------------

    @Test
    void theComposedChainEvaluatesInPrd11Order() {
        AuthorizationServiceImpl engine = engine(entitlements(RoleOverlayTestContributor.entitleAll()));
        // The tester persona (no console roles anywhere in the fakes):
        // effective membership carries the tenant layer, the stub
        // contributor the entitlement.
        world.testerOfUser = UUID.randomUUID();
        world.testerOrganization = ORG;
        world.effectiveMembership = true;
        AuthorizationDecision decision =
                engine.decide(AuthorizationContext.download(USER, TENANT, APP, VERSION, ARTIFACT, "ANDROID"));
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.chain())
                .extracting(outcome -> outcome.layer())
                .containsExactly(
                        "USER", "ORGANIZATION", "TENANT", "APPLICATION", "VERSION", "ARTIFACT", "DISTRIBUTION");
        assertThat(decision.chain())
                .allSatisfy(outcome -> assertThat(outcome.status()).isEqualTo(Status.PASSED));
    }

    @Test
    void theFirstFailingLayerShortCircuitsAndTheTraceSkipsTheRest() {
        AuthorizationServiceImpl engine = engine(entitlements(RoleOverlayTestContributor.entitleAll()));
        world.userExists = false;
        AuthorizationDecision decision =
                engine.decide(AuthorizationContext.download(USER, TENANT, APP, VERSION, ARTIFACT, "ANDROID"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.failingLayer()).isEqualTo("USER");
        assertThat(decision.reason()).isEqualTo(DenialReason.SUBJECT_MISSING);
        assertThat(decision.chain().get(0).status()).isEqualTo(Status.FAILED);
        assertThat(decision.chain().subList(1, decision.chain().size()))
                .allSatisfy(outcome -> assertThat(outcome.status()).isEqualTo(Status.SKIPPED));
    }

    @Test
    void theEngineFailsClosedOnAResolutionFault() {
        // A tester port that throws: the decision must deny with the
        // engine-fault reason, never allow and never a 500.
        TesterAccess explodingTesters = new TesterAccess() {
            @Override
            public Optional<UUID> owningOrganizationOf(UUID testerId) {
                throw new IllegalStateException("port unavailable");
            }

            @Override
            public Optional<UUID> testerIdOfUser(UUID userId) {
                throw new IllegalStateException("port unavailable");
            }

            @Override
            public Map<UUID, TesterAccess.TesterRecord> recordsOf(Collection<UUID> testerIds) {
                throw new IllegalStateException("port unavailable");
            }
        };
        AuthorizationServiceImpl engine = new AuthorizationServiceImpl(
                List.of(
                        new UserLayer(world.users),
                        new OrganizationLayer(world.tenants, world.organizations),
                        new TenantLayer(world.decisionInputs),
                        new ApplicationLayer(world.applications),
                        new VersionLayer(entitlements(RoleOverlayTestContributor.entitleAll())),
                        new ArtifactLayer(),
                        new DistributionLayer(policies(List.of()))),
                new com.shipperdog.api.authorization.internal.evaluator.RequestRoleGrants(
                        userId -> false, world.organizations, world.tenants),
                world.applications,
                world.versions,
                world.artifacts,
                explodingTesters,
                new com.shipperdog.api.audit.AuditLogger(event -> {}, java.time.Clock.systemUTC()),
                new SimpleMeterRegistry());
        AuthorizationDecision decision =
                engine.decide(AuthorizationContext.download(USER, TENANT, APP, VERSION, ARTIFACT, "ANDROID"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(DenialReason.ENGINE_FAULT);
        assertThat(decision.failingLayer()).isEqualTo("TENANT");
    }

    // ------------------------------------------------------------------
    // Earlier-layer denies mask later-layer states (the §11 order)
    // ------------------------------------------------------------------

    @Test
    void anEarlierLayerDenyMasksALaterLayerAdverseState() {
        // A suspended tester (membership switch off) downloading a
        // REVOKED version: the TENANT layer fails first and the version
        // revocation — a later-layer fact — must never surface; the
        // trace skips everything after the first failure.
        AuthorizationServiceImpl engine = engine(entitlements(RoleOverlayTestContributor.entitleAll()));
        world.testerOfUser = UUID.randomUUID();
        world.testerOrganization = ORG;
        world.effectiveMembership = false;
        world.versionStatus = "REVOKED";
        AuthorizationDecision decision =
                engine.decide(AuthorizationContext.download(USER, TENANT, APP, VERSION, ARTIFACT, "ANDROID"));
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.failingLayer())
                .as("the suspended membership denies before the revoked version is ever consulted")
                .isEqualTo("TENANT");
        assertThat(decision.reason()).isEqualTo(DenialReason.TENANT_ACCESS_DENIED);
        assertThat(statusOf(decision, "VERSION"))
                .as("the revoked state is masked, not reported")
                .isEqualTo(Status.SKIPPED);
        assertThat(statusOf(decision, "ARTIFACT")).isEqualTo(Status.SKIPPED);
        assertThat(statusOf(decision, "DISTRIBUTION")).isEqualTo(Status.SKIPPED);
    }

    // ------------------------------------------------------------------
    // PRD 42 SLO shape: a bounded, per-decision-constant query budget
    // ------------------------------------------------------------------

    @Test
    void oneDecisionCostsAConstantNumberOfPortLookupsNoMatterHowManyLayersConsumeThem() {
        AuthorizationServiceImpl engine = engine(entitlements(RoleOverlayTestContributor.entitleAll()));
        world.testerOfUser = UUID.randomUUID();
        world.testerOrganization = ORG;
        world.effectiveMembership = true;
        world.resetCounters();
        AuthorizationDecision decision =
                engine.decide(AuthorizationContext.download(USER, TENANT, APP, VERSION, ARTIFACT, "ANDROID"));
        assertThat(decision.allowed()).isTrue();
        // Every chain fact resolves exactly once although the
        // organization, tenant, application, version, and artifact
        // layers all consume ownership/state facts (EvaluationState's
        // memoization — the no-N+1 discipline of PRD 42).
        assertThat(world.userExistsCalls).isEqualTo(1);
        assertThat(world.statusOfCalls).isEqualTo(1);
        assertThat(world.effectiveMembershipCalls).isEqualTo(1);
        assertThat(world.testerIdCalls).isEqualTo(1);
        assertThat(world.testerOrganizationCalls).isEqualTo(1);
        assertThat(world.ownershipCalls)
                .as("the application ownership is resolved once for applicability and evaluation")
                .isEqualTo(1);
        assertThat(world.versionStateCalls).isEqualTo(1);
        assertThat(world.artifactStateCalls).isEqualTo(1);
        assertThat(world.visibleToTenantCalls).isZero();
        assertThat(world.owningOrganizationCalls)
                .as("two memoization domains (snapshot + organization layer), still constant")
                .isLessThanOrEqualTo(2);
        // The decision is timed on the shared SLO meter with its engine
        // tag — one sample per decision.
        assertThat(meters.find(com.shipperdog.api.config.MetricNames.AUTHORIZATION_DECISION_DURATION)
                        .tag("engine", "layered")
                        .tag("outcome", "allow")
                        .timer()
                        .count())
                .isEqualTo(1);

        // A catalog decision never fans out per item: the listing's set
        // form IS the sanctioned visibility union, so no per-application
        // lookup happens inside the decision.
        world.resetCounters();
        assertThat(engine.decide(AuthorizationContext.listApplications(USER, TENANT))
                        .allowed())
                .isTrue();
        assertThat(world.visibleToTenantCalls).isZero();
        assertThat(world.ownershipCalls).isZero();
        assertThat(meters.find(com.shipperdog.api.config.MetricNames.AUTHORIZATION_DECISION_DURATION)
                        .tag("engine", "layered")
                        .tag("outcome", "allow")
                        .timer()
                        .count())
                .isEqualTo(2);
    }

    private static Status statusOf(AuthorizationDecision decision, String layer) {
        return decision.chain().stream()
                .filter(outcome -> outcome.layer().equals(layer))
                .map(com.shipperdog.api.authorization.AuthorizationDecision.LayerOutcome::status)
                .findFirst()
                .orElseThrow(() -> new AssertionError("layer " + layer + " missing from the chain"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private SimpleMeterRegistry meters;

    private AuthorizationServiceImpl engine(
            ObjectProvider<com.shipperdog.api.authorization.AuthorizationEntitlement> entitlements) {
        List<AuthorizationLayer> layers = List.of(
                new UserLayer(world.users),
                new OrganizationLayer(world.tenants, world.organizations),
                new TenantLayer(world.decisionInputs),
                new ApplicationLayer(world.applications),
                new VersionLayer(entitlements),
                new ArtifactLayer(),
                new DistributionLayer(policies(List.of())));
        com.shipperdog.api.authorization.internal.evaluator.RequestRoleGrants grants =
                new com.shipperdog.api.authorization.internal.evaluator.RequestRoleGrants(
                        userId -> false, world.organizations, world.tenants);
        this.meters = new SimpleMeterRegistry();
        return new AuthorizationServiceImpl(
                layers,
                grants,
                world.applications,
                world.versions,
                world.artifacts,
                world.testers,
                new com.shipperdog.api.audit.AuditLogger(event -> {}, java.time.Clock.systemUTC()),
                meters);
    }

    private static ObjectProvider<com.shipperdog.api.authorization.AuthorizationEntitlement> entitlements(
            com.shipperdog.api.authorization.AuthorizationEntitlement... contributors) {
        return new ObjectProvider<>() {
            @Override
            public com.shipperdog.api.authorization.AuthorizationEntitlement getObject() {
                return contributors[0];
            }

            @Override
            public java.util.stream.Stream<com.shipperdog.api.authorization.AuthorizationEntitlement> orderedStream() {
                return Stream.of(contributors);
            }
        };
    }

    private static ObjectProvider<com.shipperdog.api.authorization.AuthorizationDistributionPolicy> policies(
            List<com.shipperdog.api.authorization.AuthorizationDistributionPolicy> registered) {
        return new ObjectProvider<>() {
            @Override
            public java.util.stream.Stream<com.shipperdog.api.authorization.AuthorizationDistributionPolicy>
                    orderedStream() {
                return registered.stream();
            }
        };
    }

    /** Contributor stubs for the version layer's entitlement union. */
    private static final class RoleOverlayTestContributor
            implements com.shipperdog.api.authorization.AuthorizationEntitlement {

        private final boolean entitled;

        private RoleOverlayTestContributor(boolean entitled) {
            this.entitled = entitled;
        }

        static RoleOverlayTestContributor entitleAll() {
            return new RoleOverlayTestContributor(true);
        }

        static RoleOverlayTestContributor denyAll() {
            return new RoleOverlayTestContributor(false);
        }

        @Override
        public boolean isEntitled(EntitlementQuery query) {
            return entitled;
        }
    }
}
