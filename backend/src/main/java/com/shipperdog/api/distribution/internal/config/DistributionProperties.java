package com.shipperdog.api.distribution.internal.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The adapter sandbox's knobs (issue #61, S13-01, AC5): the per-channel
 * timeout, bounded retry count, and backoff every adapter call runs
 * under -- store latency is a top debugging question, so a hung
 * provider call can never hang a dispatch. Configured per adapter by
 * channel id under {@code shipperdog.distribution.adapters.<channel>.*}
 * ({@code DISTRIBUTION_ADAPTERS__<CHANNEL>__TIMEOUT} and friends in
 * the deployment env); any channel without an entry runs the
 * defaults, and unknown keys are ignored (a configured channel that
 * has no adapter is a typo the catalog would catch, not a startup
 * fault).
 *
 * @param adapters per-channel sandbox settings, keyed by channel id
 */
@ConfigurationProperties(prefix = "shipperdog.distribution")
public record DistributionProperties(@DefaultValue Map<String, AdapterSandbox> adapters) {

    /** The sandbox every channel runs under when none is configured for it. */
    public static final AdapterSandbox DEFAULT_SANDBOX =
            new AdapterSandbox(Duration.ofSeconds(30), 2, Duration.ofSeconds(1));

    /**
     * One channel's sandbox.
     *
     * @param timeout        the wall every adapter call is cut at (the
     *                       cut surfaces as the TIMEOUT failure
     *                       category and earns the retries below)
     * @param retryAttempts  how many RETRIES a retryable failure
     *                       (TRANSIENT, RATE_LIMITED, TIMEOUT) earns
     *                       after the first attempt
     * @param retryBackoff   the fixed backoff between attempts
     */
    public record AdapterSandbox(
            @DefaultValue("PT30S") Duration timeout,
            @DefaultValue("2") int retryAttempts,
            @DefaultValue("PT1S") Duration retryBackoff) {

        /** The total attempt ceiling: the first attempt plus the retries. */
        public int totalAttempts() {
            return 1 + Math.max(0, retryAttempts);
        }
    }

    /** The sandbox in force for one channel (the default when none is configured). */
    public AdapterSandbox sandboxOf(String channelId) {
        return adapters.getOrDefault(channelId, DEFAULT_SANDBOX);
    }
}
