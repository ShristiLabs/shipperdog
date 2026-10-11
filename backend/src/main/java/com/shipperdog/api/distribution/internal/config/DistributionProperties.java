package com.shipperdog.api.distribution.internal.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The adapter framework's knobs (issue #61, S13-01, AC5; the store
 * connectors' additions since #62): the per-channel sandbox every
 * adapter call runs under -- store latency is a top debugging
 * question, so a hung provider call can never hang a dispatch -- the
 * Apple connector's client wiring (S13-02), and the reconciliation
 * pass's cadence. Configured per adapter by channel id under
 * {@code shipperdog.distribution.adapters.<channel>.*}
 * ({@code DISTRIBUTION_ADAPTERS__<CHANNEL>__TIMEOUT} and friends in
 * the deployment env); any channel without an entry runs the
 * defaults, and unknown keys are ignored (a configured channel that
 * has no adapter is a typo the catalog would catch, not a startup
 * fault).
 *
 * @param adapters       per-channel sandbox settings, keyed by channel id
 * @param apple          the App Store Connect client wiring (S13-02, #62)
 * @param reconciliation the provider-status refresh pass (S13-02, #62)
 */
@ConfigurationProperties(prefix = "shipperdog.distribution")
public record DistributionProperties(
        @DefaultValue Map<String, AdapterSandbox> adapters,
        @DefaultValue AppleConnector apple,
        @DefaultValue Reconciliation reconciliation) {

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

    /**
     * The App Store Connect client's wiring (S13-02, #62): the API's
     * base URL and the REST timeouts. The default is Apple's
     * production endpoint, and a default-configured stack never dials
     * it: the connector calls only under a dispatch, an explicit group
     * sync, or an explicit record refresh -- and the scheduled
     * reconciliation pass is opt-in. Tests and local stacks point the
     * base URL at a contract-faithful mock ASC server.
     *
     * @param baseUrl        the App Store Connect API root
     * @param connectTimeout the REST connect timeout
     * @param readTimeout    the REST read timeout
     */
    public record AppleConnector(
            @DefaultValue("https://api.appstoreconnect.apple.com")
            String baseUrl,

            @DefaultValue("PT20S") Duration connectTimeout,
            @DefaultValue("PT20S") Duration readTimeout) {}

    /**
     * The provider-status refresh pass (S13-02, AC4/AC5): the
     * scheduled half of store reconciliation, polling non-direct
     * channels' records through the adapters' status answers. Off by
     * default -- an OSS stack must never wake up dialing Apple -- with
     * the production opt-in on deployment-record (the explicit
     * refresh route serves the release dashboard either way).
     *
     * @param enabled    whether the scheduled pass runs at all
     * @param fixedDelay the cadence between passes
     */
    public record Reconciliation(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("PT15M") Duration fixedDelay) {}

    /** The sandbox in force for one channel (the default when none is configured). */
    public AdapterSandbox sandboxOf(String channelId) {
        return adapters.getOrDefault(channelId, DEFAULT_SANDBOX);
    }

    /** The Apple connector's wiring (the defaults when unconfigured). */
    public AppleConnector apple() {
        return apple == null ? DEFAULT_APPLE : apple;
    }

    /** The reconciliation pass's knobs (the defaults when unconfigured). */
    public Reconciliation reconciliation() {
        return reconciliation == null ? DEFAULT_RECONCILIATION : reconciliation;
    }

    /** The Apple connector's defaults (production Apple). */
    static final AppleConnector DEFAULT_APPLE =
            new AppleConnector("https://api.appstoreconnect.apple.com", Duration.ofSeconds(20), Duration.ofSeconds(20));

    /** The reconciliation pass's default: off until a deployment opts in. */
    static final Reconciliation DEFAULT_RECONCILIATION = new Reconciliation(false, Duration.ofMinutes(15));
}
