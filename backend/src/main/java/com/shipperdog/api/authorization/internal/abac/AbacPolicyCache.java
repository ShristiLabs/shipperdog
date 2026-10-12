package com.shipperdog.api.authorization.internal.abac;

import com.shipperdog.api.authorization.AbacCondition;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.Effect;
import com.shipperdog.api.authorization.internal.abac.AbacPolicyEntity.GoverningAction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The in-memory compiled-policy cache (issue #70, S14-04, the issue's
 * "precompile policies" note): one bounded load per organization per
 * TTL window, holding the parsed {@link AbacCondition} trees beside
 * the raw selectors — evaluation never re-parses, never re-queries.
 *
 * <p>Invalidation is write-through from the SAME JVM's policy
 * service (the modular monolith: the mutating service drops the
 * organization's entry at commit, so a saved policy evaluates on the
 * very next decision), with the TTL as the cross-instance backstop —
 * the documented bound on staleness for deployments beyond one node
 * (the grant-cache precedent bounds its own window the same way).
 * The cache holds INPUTS only (the compiled rule store), never
 * decisions — the S7-03 discipline.
 */
@Component
class AbacPolicyCache {

    /** One compiled policy: the parsed tree beside its selectors, or a null tree marking storage drift. */
    record CompiledPolicy(
            UUID id,
            String name,
            int version,
            Effect effect,
            GoverningAction action,
            UUID applicationId,
            UUID environmentId,
            String ring,
            AbacCondition condition,
            int position) {}

    private record Entry(List<CompiledPolicy> policies, Instant loadedAt) {}

    /**
     * The cross-instance staleness backstop (single-node immediacy is
     * the write-through drop): {@code shipperdog.abac.policy-cache-ttl},
     * 60s by default.
     */
    private final Duration ttl;

    private final AbacPolicyRepository policies;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Entry> byOrganization = new ConcurrentHashMap<>();

    AbacPolicyCache(
            AbacPolicyRepository policies,
            Clock clock,
            @org.springframework.beans.factory.annotation.Value("${shipperdog.abac.policy-cache-ttl:60s}")
                    Duration ttl) {
        this.policies = policies;
        this.clock = clock;
        this.ttl = ttl;
    }

    /** The organization's enabled policies, compiled, in the defined evaluation order. */
    List<CompiledPolicy> enabledOf(UUID organizationId) {
        return entryOf(organizationId).policies();
    }

    /** Whether the organization holds any enabled policy (the layer's zero-cost fast-path probe). */
    boolean anyEnabled(UUID organizationId) {
        return !entryOf(organizationId).policies().isEmpty();
    }

    /** The write-through invalidation: the mutating service's drop at commit. */
    void invalidate(UUID organizationId) {
        byOrganization.remove(organizationId);
    }

    private Entry entryOf(UUID organizationId) {
        Instant now = Instant.now(clock);
        Entry entry = byOrganization.get(organizationId);
        if (entry != null && entry.loadedAt().plus(ttl).isAfter(now)) {
            return entry;
        }
        List<CompiledPolicy> compiled = policies.findByOrganizationIdOrderByPositionAscNameAsc(organizationId).stream()
                .filter(AbacPolicyEntity::isEnabled)
                .map(policy -> new CompiledPolicy(
                        policy.getId(),
                        policy.getName(),
                        policy.getVersion(),
                        policy.getEffect(),
                        policy.getAction(),
                        policy.getApplicationId(),
                        policy.getEnvironmentId(),
                        policy.getRing(),
                        compile(policy),
                        policy.getPosition()))
                .toList();
        Entry fresh = new Entry(compiled, now);
        byOrganization.put(organizationId, fresh);
        return fresh;
    }

    /** Parses the stored tree; null marks storage drift (impossible through the write surface's validation). */
    private static AbacCondition compile(AbacPolicyEntity policy) {
        Map<String, Object> raw = policy.getCondition();
        if (raw == null) {
            return null;
        }
        return AbacCondition.parse(raw, "condition", new java.util.ArrayList<>());
    }
}
