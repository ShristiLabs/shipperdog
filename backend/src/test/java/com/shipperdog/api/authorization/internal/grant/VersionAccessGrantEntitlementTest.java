package com.shipperdog.api.authorization.internal.grant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shipperdog.api.authorization.AuthorizationAction;
import com.shipperdog.api.authorization.AuthorizationEntitlement.EntitlementQuery;
import com.shipperdog.api.authorization.internal.cache.AuthorizationDecisionCache.CachedGrant;
import com.shipperdog.api.authorization.internal.cache.DecisionInputs;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantEntity.AccessType;
import com.shipperdog.api.tester.TesterAccess;
import com.shipperdog.api.version.VersionAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The grant contributor's per-shape semantics in isolation (issue
 * #31, re-based onto the S7-03 decision-input seam): each FR-011
 * shape's coverage over the addressed version, both owning dimensions
 * (tenant-owned and the S3-03-mapped shared case), the expiry filter,
 * the subject-to-tester resolution, the per-tenant set-read
 * discipline (the cached unit of issue #32's technical note), and the
 * fail-closed contract (an input that cannot run answers
 * not-entitled, never an exception and never an allow).
 */
class VersionAccessGrantEntitlementTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final UUID SUBJECT = UUID.randomUUID();
    private static final UUID TESTER = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID APPLICATION = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID OTHER_VERSION = UUID.randomUUID();

    private final DecisionInputs decisionInputs = mock(DecisionInputs.class);
    private final TesterAccess testers = mock(TesterAccess.class);
    private final VersionAccess versions = mock(VersionAccess.class);
    private final VersionAccessGrantEntitlement contributor =
            new VersionAccessGrantEntitlement(decisionInputs, testers, versions, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void explicitGrantCoversExactlyTheAddressedVersion() {
        given(grant(TENANT, AccessType.EXPLICIT_VERSION, VERSION, null, null, null));
        assertThat(entitled(query(VERSION))).isTrue();
        assertThat(entitled(query(OTHER_VERSION))).isFalse();
    }

    @Test
    void applicationWideGrantCoversAnyVersion() {
        given(grant(TENANT, AccessType.APPLICATION, null, null, null, null));
        assertThat(entitled(query(OTHER_VERSION))).isTrue();
    }

    @Test
    void tagGrantCoversVersionsCarryingTheTagAtEvaluationTime() {
        given(grant(TENANT, AccessType.TAG, null, "BETA", null, null));
        when(versions.versionHasTag(VERSION, "BETA")).thenReturn(true);
        when(versions.versionHasTag(OTHER_VERSION, "BETA")).thenReturn(false);
        assertThat(entitled(query(VERSION))).isTrue();
        assertThat(entitled(query(OTHER_VERSION))).isFalse();
    }

    @Test
    void latestNGrantAsksTheWorkingSetWindow() {
        given(grant(TENANT, AccessType.LATEST_N, null, null, 3, null));
        when(versions.isWithinLatestWorkingSetN(VERSION, 3)).thenReturn(true);
        when(versions.isWithinLatestWorkingSetN(OTHER_VERSION, 3)).thenReturn(false);
        assertThat(entitled(query(VERSION))).isTrue();
        assertThat(entitled(query(OTHER_VERSION))).isFalse();
    }

    @Test
    void expiredGrantsNeverAuthorize() {
        given(grant(TENANT, AccessType.APPLICATION, null, null, null, NOW.minusSeconds(1)));
        assertThat(entitled(query(VERSION))).isFalse();
    }

    @Test
    void aTenantOwnedApplicationMatchesOnlyItsOwningTenant() {
        // The engine's query carries the owning tenant; a grant made
        // through a DIFFERENT tenant (impossible to create for a
        // tenant-owned application, but structurally excluded here
        // regardless) never matches.
        given(grant(OTHER_TENANT, AccessType.APPLICATION, null, null, null, null));
        assertThat(entitled(query(VERSION))).isFalse();
    }

    @Test
    void aSharedApplicationMatchesTheGrantsTenantLive() {
        // Organization-shared application (owning tenant null): the
        // candidate tenants resolve through the linked-tenant set, and
        // each grant's own tenant is matched live — effective
        // membership plus the S3-03 mapping.
        when(testers.testerIdOfUser(SUBJECT)).thenReturn(Optional.of(TESTER));
        when(decisionInputs.tenantsOfTester(TESTER)).thenReturn(List.of(OTHER_TENANT));
        when(decisionInputs.grantsOf(OTHER_TENANT, TESTER))
                .thenReturn(
                        List.of(CachedGrant.of(grant(OTHER_TENANT, AccessType.APPLICATION, null, null, null, null))));
        when(decisionInputs.effectiveMembership(OTHER_TENANT, TESTER)).thenReturn(true);
        when(decisionInputs.visibleToTenant(APPLICATION, OTHER_TENANT)).thenReturn(true);
        assertThat(entitled(sharedQuery(VERSION))).isTrue();

        // Membership suspended (a switch off): denied on this
        // evaluation; re-enabling restores without any re-grant.
        when(decisionInputs.effectiveMembership(OTHER_TENANT, TESTER)).thenReturn(false);
        assertThat(entitled(sharedQuery(VERSION))).isFalse();

        // Mapping revoked (S3-03 de-authorization): denied live.
        when(decisionInputs.effectiveMembership(OTHER_TENANT, TESTER)).thenReturn(true);
        when(decisionInputs.visibleToTenant(APPLICATION, OTHER_TENANT)).thenReturn(false);
        assertThat(entitled(sharedQuery(VERSION))).isFalse();
    }

    @Test
    void aSubjectWithoutATesterRecordIsNotEntitled() {
        when(decisionInputs.grantsOf(any(), any())).thenReturn(List.of());
        when(testers.testerIdOfUser(SUBJECT)).thenReturn(Optional.empty());
        assertThat(entitled(query(VERSION))).isFalse();
    }

    @Test
    void aFaultAnswersNotEntitledNeverAnException() {
        when(testers.testerIdOfUser(SUBJECT)).thenReturn(Optional.of(TESTER));
        when(decisionInputs.grantsOf(TENANT, TESTER)).thenThrow(new IllegalStateException("probe unavailable"));
        assertThat(entitled(query(VERSION)))
                .as("fail-closed: an input that cannot run does not authorize")
                .isFalse();
    }

    @Test
    void theProbeIsOneSetReadPerTenantRegardlessOfGrantCount() {
        // The cached-unit discipline (issue #32's technical note):
        // however many grants the tester holds through the tenant, the
        // tenant-owned decision resolves ONE per-tenant set read and
        // filters in memory — the cache entry a grant mutation
        // invalidates; the shared-application path adds exactly one
        // linked-tenant set read plus one set read per linked tenant.
        when(testers.testerIdOfUser(SUBJECT)).thenReturn(Optional.of(TESTER));
        when(decisionInputs.grantsOf(TENANT, TESTER))
                .thenReturn(List.of(
                        CachedGrant.of(grant(TENANT, AccessType.EXPLICIT_VERSION, VERSION, null, null, null)),
                        CachedGrant.of(grant(TENANT, AccessType.APPLICATION, null, null, null, null)),
                        CachedGrant.of(grant(TENANT, AccessType.LATEST_N, null, null, 2, null))));
        assertThat(entitled(query(VERSION))).isTrue();
        verify(decisionInputs, times(1)).grantsOf(TENANT, TESTER);
        verify(decisionInputs, times(0)).tenantsOfTester(TESTER);
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static EntitlementQuery query(UUID versionId) {
        return new EntitlementQuery(
                SUBJECT, UUID.randomUUID(), TENANT, APPLICATION, versionId, AuthorizationAction.DOWNLOAD);
    }

    private static EntitlementQuery sharedQuery(UUID versionId) {
        return new EntitlementQuery(
                SUBJECT, UUID.randomUUID(), null, APPLICATION, versionId, AuthorizationAction.DOWNLOAD);
    }

    private static VersionAccessGrantEntity grant(
            UUID tenantId, AccessType shape, UUID versionId, String tag, Integer latestN, Instant expiresAt) {
        return new VersionAccessGrantEntity(
                tenantId, TESTER, APPLICATION, shape, versionId, tag, latestN, expiresAt, SUBJECT);
    }

    private void given(VersionAccessGrantEntity grant) {
        when(testers.testerIdOfUser(SUBJECT)).thenReturn(Optional.of(TESTER));
        when(decisionInputs.grantsOf(TENANT, TESTER)).thenReturn(List.of(CachedGrant.of(grant)));
    }

    private boolean entitled(EntitlementQuery query) {
        return contributor.isEntitled(query);
    }
}
