package com.shipperdog.api.config.ratelimit;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The in-app rate limiter's configuration (issue #39, S8-05): which
 * rules exist, their bucket shape, and how the client identity behind
 * a proxy chain is resolved. Every knob is environment-overridable —
 * the compose stack ships generous local limits (one operator IP
 * carries every browser, e2e run, and CLI session) while production
 * (Cloud Run) and the gateway (Dwara, fronting the platform later)
 * dial them to their own posture through the same variables.
 *
 * <p>Fail-open by construction: a limiter outage or misconfiguration
 * must never take the API down — the story's authority for abuse
 * control in production is the gateway, and this tier is the
 * always-present baseline beneath it (PRD P8: security at every
 * layer, and the S8-05 scope note "app-level; Dwara fronts in prod").
 *
 * @param enabled master switch (the test profile disables the limiter
 *      so pre-existing suites never share one 127.0.0.1 bucket)
 * @param trustedProxyCount how many proxies in front of the API
 *      append to {@code X-Forwarded-For} and are therefore trusted;
 *      the client IP is the entry that many positions from the right
 *      (compose: the nginx service; Cloud Run: the platform proxy)
 * @param maxTrackedClients bound on the per-(rule, client) bucket
 *      cache — an LRU ceiling so a spoofed-header flood cannot grow
 *      the map unboundedly
 * @param rules per-rule bucket shapes, keyed by the rule names the
 *      filter matches ({@code login}, {@code downloads})
 */
@ConfigurationProperties("shipperdog.ratelimit")
public record RateLimitProperties(
        boolean enabled, int trustedProxyCount, long maxTrackedClients, Map<String, Rule> rules) {

    /** One token bucket: {@code capacity} burst, then one token per {@code refill}. */
    public record Rule(int capacity, Duration refill) {}
}
