package com.shipperdog.api.authorization.internal.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The decision-input cache's settings (issue #32, S7-03):
 * {@code shipperdog.authorization-cache.*}. The TTL is the staleness
 * backstop and stays at or under the signed-URL TTL (both 15 min
 * default, the story's technical note): write-through invalidation is
 * the freshness mechanism, and the TTL only bounds what an
 * invalidation miss (Redis down during a mutation, or a refill race
 * around a commit) can leave behind.
 *
 * @param enabled whether the cache resolves at all — false answers
 *        every lookup from the live loaders (outcome
 *        {@code bypass}), the operational lever that turns S7-03 off
 *        without a redeploy
 * @param ttl the per-entry expiry applied to every put
 */
@ConfigurationProperties(prefix = "shipperdog.authorization-cache")
public record DecisionCacheProperties(boolean enabled, Duration ttl) {}
