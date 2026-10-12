package com.shipperdog.api.authorization.internal.abac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shipperdog.api.authorization.internal.abac.AbacPolicyCache.CompiledPolicy;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.Effect;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.GoverningAction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The compiled-policy cache's window contract (issue #70, S14-04, the
 * issue's "precompile policies" note): one bounded load per
 * organization per TTL window (the latency budget's query bound), the
 * write-through drop the mutating service registers at commit (a
 * saved policy evaluates on the very NEXT decision — no TTL wait),
 * and the storage-drift marking the engine denies on (a condition
 * that cannot parse compiles to null, never to a permissive guess).
 */
class AbacPolicyCacheTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();

    /** A clock the test advances by hand: the TTL window's time source. */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        void advance(Duration by) {
            now.updateAndGet(current -> current.plus(by));
        }
    }

    @Test
    void oneLoadPerTtlWindowWithTheEnabledFilterApplied() {
        AbacPolicyRepository repository = mock(AbacPolicyRepository.class);
        MutableClock clock = new MutableClock();
        when(repository.findByOrganizationIdOrderByPositionAscNameAsc(ORGANIZATION))
                .thenReturn(List.of(
                        policy("alpha", true, Map.of("subjectOrgRole", "VIEWER")),
                        policy("beta", false, Map.of("subjectOrgRole", "VIEWER"))));
        AbacPolicyCache cache = new AbacPolicyCache(repository, clock, Duration.ofSeconds(60));

        List<CompiledPolicy> first = cache.enabledOf(ORGANIZATION);
        List<CompiledPolicy> second = cache.enabledOf(ORGANIZATION);

        // The disabled policy never compiles into the window, and the
        // window serves the SAME compiled list without a second load.
        assertThat(first).hasSize(1);
        assertThat(first.get(0).name()).isEqualTo("alpha");
        assertThat(first.get(0).condition()).isNotNull();
        assertThat(second).isSameAs(first);
        verify(repository, times(1)).findByOrganizationIdOrderByPositionAscNameAsc(ORGANIZATION);

        // The window passes: the next probe reloads from the store.
        clock.advance(Duration.ofSeconds(61));
        assertThat(cache.enabledOf(ORGANIZATION)).hasSize(1);
        verify(repository, times(2)).findByOrganizationIdOrderByPositionAscNameAsc(ORGANIZATION);
    }

    @Test
    void theFastPathProbeAnswersFromTheWindowWithoutItsOwnLoad() {
        AbacPolicyRepository repository = mock(AbacPolicyRepository.class);
        when(repository.findByOrganizationIdOrderByPositionAscNameAsc(ORGANIZATION))
                .thenReturn(List.of());
        AbacPolicyCache cache = new AbacPolicyCache(repository, new MutableClock(), Duration.ofSeconds(60));

        assertThat(cache.anyEnabled(ORGANIZATION)).isFalse();
        assertThat(cache.anyEnabled(ORGANIZATION)).isFalse();
        verify(repository, times(1)).findByOrganizationIdOrderByPositionAscNameAsc(ORGANIZATION);
    }

    @Test
    void writeThroughInvalidationReloadsImmediatelyWithoutWaitingForTheWindow() {
        AbacPolicyRepository repository = mock(AbacPolicyRepository.class);
        MutableClock clock = new MutableClock();
        AtomicReference<List<AbacPolicyEntity>> stored = new AtomicReference<>(List.of());
        when(repository.findByOrganizationIdOrderByPositionAscNameAsc(ORGANIZATION))
                .thenAnswer(invocation -> stored.get());
        AbacPolicyCache cache = new AbacPolicyCache(repository, clock, Duration.ofSeconds(60));

        // The empty window is served warm...
        assertThat(cache.anyEnabled(ORGANIZATION)).isFalse();
        verify(repository, times(1)).findByOrganizationIdOrderByPositionAscNameAsc(ORGANIZATION);

        // ...the policy lands in storage, and the service's drop makes
        // the very next probe reload — no clock advance, no TTL wait.
        stored.set(List.of(policy("published", true, Map.of("subjectOrgRole", "VIEWER"))));
        cache.invalidate(ORGANIZATION);
        assertThat(cache.anyEnabled(ORGANIZATION)).isTrue();
        verify(repository, times(2)).findByOrganizationIdOrderByPositionAscNameAsc(ORGANIZATION);
    }

    @Test
    void storageDriftCompilesToTheNullConditionTheEngineDeniesOn() {
        AbacPolicyRepository repository = mock(AbacPolicyRepository.class);
        when(repository.findByOrganizationIdOrderByPositionAscNameAsc(ORGANIZATION))
                .thenReturn(List.of(policy("drifted", true, Map.of("noSuchAtom", true))));
        AbacPolicyCache cache = new AbacPolicyCache(repository, new MutableClock(), Duration.ofSeconds(60));

        List<CompiledPolicy> compiled = cache.enabledOf(ORGANIZATION);

        // The write surface's validation makes this impossible; when
        // storage drifts anyway, the compile marks it (null) rather
        // than guessing a tree — the engine's fail-closed contract.
        assertThat(compiled).hasSize(1);
        assertThat(compiled.get(0).condition()).isNull();
    }

    private static AbacPolicyEntity policy(String name, boolean enabled, Map<String, Object> condition) {
        return new AbacPolicyEntity(
                ORGANIZATION, name, Effect.DENY, GoverningAction.DOWNLOAD, null, null, null, condition, 10, enabled);
    }
}
