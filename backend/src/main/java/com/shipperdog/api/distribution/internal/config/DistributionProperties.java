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
 * @param googlePlay     the Google Play Developer API client wiring (S13-04, #64)
 * @param reconciliation the provider-status refresh pass (S13-02, #62)
 */
@ConfigurationProperties(prefix = "shipperdog.distribution")
public record DistributionProperties(
        @DefaultValue Map<String, AdapterSandbox> adapters,
        @DefaultValue AppleConnector apple,
        @DefaultValue GooglePlayConnector googlePlay,
        @DefaultValue Reconciliation reconciliation,
        @DefaultValue StagedRollout stagedRollout) {

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

    /** The Google Play connector's wiring (the defaults when unconfigured). */
    public GooglePlayConnector googlePlay() {
        return googlePlay == null ? DEFAULT_GOOGLE_PLAY : googlePlay;
    }

    /** The reconciliation pass's knobs (the defaults when unconfigured). */
    public Reconciliation reconciliation() {
        return reconciliation == null ? DEFAULT_RECONCILIATION : reconciliation;
    }

    /** The staged rollout advancement pass's knobs (the defaults when unconfigured). */
    public StagedRollout stagedRollout() {
        return stagedRollout == null ? DEFAULT_STAGED_ROLLOUT : stagedRollout;
    }

    /**
     * The staged TENANT rollout's pass (S13-06, #66, AC2): the
     * unattended cadence of the AUTOMATIC advancement mode — every
     * RUNNING AUTOMATIC plan gets one gated advancement attempt per
     * pass. Off by default (the reconciliation precedent): an
     * unattended pass that widens production rollouts is a
     * deployment's own decision; the ADVANCE action on the staged
     * rollout route serves the same engine everywhere regardless, and
     * a CHECKPOINT plan is never advanced unattended.
     *
     * @param advancement the pass's own knobs
     */
    public record StagedRollout(@DefaultValue Advancement advancement) {

        /**
         * The pass's knobs.
         *
         * @param enabled    whether the scheduled pass runs at all
         * @param fixedDelay the cadence between passes
         */
        public record Advancement(
                @DefaultValue("false") boolean enabled,
                @DefaultValue("PT5M") Duration fixedDelay) {}
    }

    /**
     * The Google Play Developer API client's wiring (S13-04, #64): the
     * Publishing API's base URL and the REST timeouts. The default is
     * Google's production endpoint, and a default-configured stack
     * never dials it: the connector calls only under a dispatch, an
     * explicit group sync or track listing, or an explicit record
     * refresh — and the scheduled reconciliation pass is opt-in. Tests
     * and local stacks point the base URL at a contract-faithful mock
     * Play server. The OAuth2 token endpoint rides the service-account
     * document itself (its {@code token_uri}), so it is not a knob
     * here; the timeouts below govern both legs.
     *
     * @param baseUrl        the Play Developer (Publishing) API root
     * @param connectTimeout the REST connect timeout
     * @param readTimeout    the REST read timeout (the AAB media upload
     *                       rides it — size the timeout to the artifact)
     */
    public record GooglePlayConnector(
            @DefaultValue("https://androidpublisher.googleapis.com/androidpublisher/v3/applications")
            String baseUrl,

            @DefaultValue("PT20S") Duration connectTimeout,
            @DefaultValue("PT60S") Duration readTimeout) {}

    /** The Apple connector's defaults (production Apple). */
    static final AppleConnector DEFAULT_APPLE =
            new AppleConnector("https://api.appstoreconnect.apple.com", Duration.ofSeconds(20), Duration.ofSeconds(20));

    /** The Google Play connector's defaults (production Google). */
    static final GooglePlayConnector DEFAULT_GOOGLE_PLAY = new GooglePlayConnector(
            "https://androidpublisher.googleapis.com/androidpublisher/v3/applications",
            Duration.ofSeconds(20),
            Duration.ofSeconds(60));

    /** The reconciliation pass's default: off until a deployment opts in. */
    static final Reconciliation DEFAULT_RECONCILIATION = new Reconciliation(false, Duration.ofMinutes(15));

    /** The staged rollout advancement pass's default: off until a deployment opts in. */
    static final StagedRollout DEFAULT_STAGED_ROLLOUT =
            new StagedRollout(new StagedRollout.Advancement(false, Duration.ofMinutes(5)));
}
