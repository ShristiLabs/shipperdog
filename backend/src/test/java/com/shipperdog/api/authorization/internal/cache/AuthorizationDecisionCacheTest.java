package com.shipperdog.api.authorization.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.authorization.internal.cache.AuthorizationDecisionCache.CachedGrant;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantCatalog;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantEntity;
import com.shipperdog.api.authorization.internal.grant.VersionAccessGrantEntity.AccessType;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The decision-input cache's own semantics (issue #32, S7-03, AC2 and
 * AC3): the cache-aside legs (miss loads-and-refills, hit parses),
 * the fail-closed legs (a Redis failure or a corrupt/future-versioned
 * body answers the LIVE loader and never propagates), the disabled
 * bypass, the TTL applied to every refill, the versioned payload
 * round-trip, and the write-through invalidation discipline — the
 * immediate drop without a transaction, the before-commit drop
 * inside one, and the never-fail-the-mutation contract when Redis is
 * down mid-invalidation.
 */
class AuthorizationDecisionCacheTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID TESTER = UUID.randomUUID();
    private static final UUID APPLICATION = UUID.randomUUID();
    private static final Duration TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final VersionAccessGrantCatalog grants = mock(VersionAccessGrantCatalog.class);
    private final TenantTesterMembershipAccess memberships = mock(TenantTesterMembershipAccess.class);
    private final ApplicationAccess applications = mock(ApplicationAccess.class);
    private final MeterRegistry meters = new SimpleMeterRegistry();

    private AuthorizationDecisionCache cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        cache = new AuthorizationDecisionCache(
                redis,
                new DecisionCacheProperties(true, TTL),
                grants,
                providerOf(memberships),
                providerOf(applications),
                meters);
    }

    private static <T> ObjectProvider<T> providerOf(T backing) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return backing;
            }
        };
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // -----------------------------------------------------------------------
    // Cache-aside: miss loads and refills with the TTL
    // -----------------------------------------------------------------------

    @Test
    void aMissAnswersTheLoaderAndRefillsWithTheConfiguredTtl() {
        when(values.get(anyString())).thenReturn(null);
        when(grants.findByTenantIdAndTesterId(TENANT, TESTER)).thenReturn(List.of(grant(AccessType.APPLICATION)));

        List<CachedGrant> resolved = cache.grantsOf(TENANT, TESTER);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).accessType()).isEqualTo("APPLICATION");
        assertThat(resolved.get(0).tenantId()).isEqualTo(TENANT);
        verify(values).set(startsWith("shipperdog:auth:v1:grants:"), anyString(), eq(TTL));
        assertThat(count("grants", "miss")).isEqualTo(1);
        assertThat(count("grants", "hit")).isZero();
    }

    @Test
    void aHitParsesTheCachedPayloadWithoutTouchingTheLoader() {
        String payload = "{\"v\":1,\"grants\":[{\"grantId\":\""
                + UUID.randomUUID() + "\",\"tenantId\":\"" + TENANT
                + "\",\"applicationId\":\"" + APPLICATION
                + "\",\"accessType\":\"TAG\",\"versionId\":null,\"tag\":\"BETA\","
                + "\"latestN\":null,\"expiresAt\":1893456000000}]}";
        when(values.get(anyString())).thenReturn(payload);

        List<CachedGrant> resolved = cache.grantsOf(TENANT, TESTER);

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).accessType()).isEqualTo("TAG");
        assertThat(resolved.get(0).tag()).isEqualTo("BETA");
        assertThat(resolved.get(0).expiresAt()).isEqualTo(1893456000000L); // epoch micros round-trip
        verify(grants, never()).findByTenantIdAndTesterId(any(), any());
        assertThat(count("grants", "hit")).isEqualTo(1);
    }

    @Test
    void anEmptyGrantSetIsCacheableDenyStaysDeny() {
        when(values.get(anyString())).thenReturn("{\"v\":1,\"grants\":[]}");

        assertThat(cache.grantsOf(TENANT, TESTER)).isEmpty();
        verify(grants, never()).findByTenantIdAndTesterId(any(), any());
    }

    @Test
    void flagFamiliesRoundTripOneAndZero() {
        when(values.get(membershipKey())).thenReturn("1");
        assertThat(cache.effectiveMembership(TENANT, TESTER)).isTrue();

        when(values.get(visibilityKey())).thenReturn("0");
        assertThat(cache.visibleToTenant(APPLICATION, TENANT)).isFalse();
    }

    // -----------------------------------------------------------------------
    // AC3: cache failure degrades to direct evaluation, never to allow
    // -----------------------------------------------------------------------

    @Test
    void aRedisFailureAnswersTheLiveLoaderAndNeverPropagates() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(memberships.isEffectivelyActive(TENANT, TESTER)).thenReturn(true);

        assertThat(cache.effectiveMembership(TENANT, TESTER)).isTrue();
        assertThat(count("membership", "degraded")).isEqualTo(1);
    }

    @Test
    void aFailedRefillStillAnswersTheLoadedValue() {
        when(values.get(anyString())).thenReturn(null);
        when(memberships.isEffectivelyActive(TENANT, TESTER)).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));

        assertThat(cache.effectiveMembership(TENANT, TESTER)).isTrue();
        assertThat(count("membership", "miss")).isEqualTo(1);
        assertThat(count("membership", "degraded")).isEqualTo(1);
    }

    @Test
    void aCorruptPayloadDegradesAndDeletesTheEntry() {
        when(values.get(anyString())).thenReturn("not-json");
        when(grants.findByTenantIdAndTesterId(TENANT, TESTER)).thenReturn(List.of());

        assertThat(cache.grantsOf(TENANT, TESTER)).isEmpty();
        verify(redis).delete(anyString());
        assertThat(count("grants", "degraded")).isEqualTo(1);
    }

    @Test
    void aFuturePayloadVersionIsCorruptByDefinition() {
        when(values.get(anyString())).thenReturn("{\"v\":2,\"grants\":[]}");

        assertThat(cache.grantsOf(TENANT, TESTER)).isEmpty();
        verify(grants).findByTenantIdAndTesterId(TENANT, TESTER);
        assertThat(count("grants", "degraded")).isEqualTo(1);
    }

    @Test
    void theFailFastBreakerSkipsRedisAfterConsecutiveFailures() {
        // Three consecutive failures open the breaker; the fourth
        // lookup answers the loader WITHOUT touching Redis (the
        // per-lookup timeout stack of a dead Redis never repeats).
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        when(memberships.isEffectivelyActive(TENANT, TESTER)).thenReturn(true, true, true, true);

        assertThat(cache.effectiveMembership(TENANT, TESTER)).isTrue();
        assertThat(cache.effectiveMembership(TENANT, TESTER)).isTrue();
        assertThat(cache.effectiveMembership(TENANT, TESTER)).isTrue();
        verify(values, org.mockito.Mockito.times(3)).get(anyString());

        assertThat(cache.effectiveMembership(TENANT, TESTER))
                .as("the open breaker answers the loader without a fourth Redis attempt")
                .isTrue();
        verify(values, org.mockito.Mockito.times(3)).get(anyString());
        assertThat(count("membership", "degraded")).isEqualTo(4);
    }

    @Test
    void aSuccessResetsTheBreakersFailureCount() {
        // Two failures stay below the threshold; the success between
        // them resets the counter, so the NEXT two failures still do
        // not open the breaker — the lookup keeps attempting Redis.
        org.mockito.Mockito.when(values.get(anyString()))
                .thenThrow(new IllegalStateException("redis down"))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn(null)
                .thenThrow(new IllegalStateException("redis down"))
                .thenThrow(new IllegalStateException("redis down"))
                .thenReturn(null);
        when(memberships.isEffectivelyActive(TENANT, TESTER)).thenReturn(true);

        assertThat(cache.effectiveMembership(TENANT, TESTER)).isTrue();
        assertThat(cache.effectiveMembership(TENANT, TESTER)).isTrue();
        assertThat(cache.effectiveMembership(TENANT, TESTER)).isTrue();
        assertThat(cache.effectiveMembership(TENANT, TESTER)).isTrue();
        assertThat(cache.effectiveMembership(TENANT, TESTER)).isTrue();

        verify(values, org.mockito.Mockito.times(5)).get(anyString());
        assertThat(count("membership", "degraded")).isEqualTo(4);
        assertThat(count("membership", "miss")).isEqualTo(1);
    }

    @Test
    void aDisabledCacheAnswersEverythingFromTheLoaders() {
        AuthorizationDecisionCache disabled = new AuthorizationDecisionCache(
                redis,
                new DecisionCacheProperties(false, TTL),
                grants,
                providerOf(memberships),
                providerOf(applications),
                meters);
        when(memberships.tenantIdsOfTester(TESTER)).thenReturn(List.of(TENANT));

        assertThat(disabled.tenantsOfTester(TESTER)).containsExactly(TENANT);
        verify(redis, never()).opsForValue();
        assertThat(count("tenants-of-tester", "bypass")).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // AC2: write-through invalidation
    // -----------------------------------------------------------------------

    @Test
    void invalidationOutsideATransactionDropsImmediately() {
        cache.invalidateGrantSet(TENANT, TESTER);
        verify(redis).delete(List.of(grantsKey()));
        assertThat(count("grants", "invalidate")).isEqualTo(1);
    }

    @Test
    void invalidationInsideATransactionDropsAtBeforeCommit() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger dropped = new AtomicInteger();

        cache.invalidateGrantSet(TENANT, TESTER);
        verify(redis, never()).delete(anyString());

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.beforeCommit(false);
            dropped.incrementAndGet();
        }

        assertThat(dropped.get()).isEqualTo(1);
        verify(redis).delete(List.of(grantsKey()));
    }

    @Test
    void aFailedInvalidationNeverFailsTheMutation() {
        // The drop path deletes the key BATCH (the collection
        // overload), so that is the stub that must explode.
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(redis)
                .delete(org.mockito.ArgumentMatchers.<List<String>>any());

        cache.invalidateMembership(TENANT, TESTER);

        assertThat(count("membership", "degraded")).isEqualTo(1);
        assertThat(count("membership", "invalidate")).isZero();
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static VersionAccessGrantEntity grant(AccessType shape) {
        return new VersionAccessGrantEntity(TENANT, TESTER, APPLICATION, shape, null, null, null, null, null);
    }

    private static String startsWith(String prefix) {
        return org.mockito.ArgumentMatchers.argThat((String key) -> key != null && key.startsWith(prefix));
    }

    private String grantsKey() {
        return "shipperdog:auth:v1:grants:" + TENANT + ":" + TESTER;
    }

    private String membershipKey() {
        return "shipperdog:auth:v1:membership:" + TENANT + ":" + TESTER;
    }

    private String visibilityKey() {
        return "shipperdog:auth:v1:visibility:" + APPLICATION + ":" + TENANT;
    }

    private double count(String family, String outcome) {
        var counter = meters.find(MetricNames.AUTHORIZATION_DECISION_CACHE)
                .tag("family", family)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
    }
}
