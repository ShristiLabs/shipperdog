package com.shipperdog.api.artifact.internal.download;

import java.util.Map;
import java.util.Optional;

/**
 * The download channel routing table (issue #38, S8-04, FR-012's
 * distribution-surfaces note): platform x channel -> behavior, the
 * one place that decides what a download tap DOES per platform and
 * channel, so the portal renders guidance from a server-resolved fact
 * instead of re-deriving platform rules client-side.
 *
 * <p>V1 ships exactly the two rows the story names (the channels
 * themselves arrive with the M2/M3 distribution model — the table
 * exists now so that arrival extends data, not logic):
 *
 * <ul>
 *   <li><strong>DIRECT / ANDROID</strong> — {@code OPEN_SIGNED_URL}:
 *       the signed URL opens after the one-time "install unknown
 *       apps" consent screen mobile Chrome requires for APK sideloading
 *       (the PRD's distribution-surfaces note: Android installs
 *       directly; AAB is structurally refused at issuance — the S5-05
 *       format rule, APK rows only on the direct channel).</li>
 *   <li><strong>DIRECT / IOS</strong> — {@code OPEN_SIGNED_URL_ADHOC}:
 *       the direct path exists only for ad-hoc/enterprise-signed IPAs
 *       with registered devices (the PRD's own limitation); the
 *       guidance notes that before the URL opens.</li>
 *   <li><strong>TESTFLIGHT / IOS</strong> —
 *       {@code DEEP_LINK_TESTFLIGHT}: the {@code itms-beta://}
 *       placeholder row. Unreachable from V1's API (every V1 download
 *       is DIRECT — there is no channel-selection input until the
 *       distribution model exists); pinned here so the M2 TestFlight
 *       adapter routes through this exact row instead of introducing
 *       a second table.</li>
 * </ul>
 *
 * <p>TESTFLIGHT / ANDROID deliberately has no row: Apple's channel
 * owns iOS installs only, and an unmapped pair routes to nothing —
 * the caller treats it as a configuration fault, never a silent
 * fallback.
 */
public final class DownloadChannelRouting {

    /** The distribution channel vocabulary (the M2/M3 model's seam). */
    public enum Channel {
        DIRECT,
        TESTFLIGHT
    }

    /** What the portal does with the issuance answer. */
    public enum Behavior {
        /** Open the signed URL after the install-unknown-apps consent screen (Android direct). */
        OPEN_SIGNED_URL,
        /** Open the signed URL after the ad-hoc/enterprise signing note (iOS direct). */
        OPEN_SIGNED_URL_ADHOC,
        /** Deep-link into the TestFlight beta channel ({@code itms-beta://}); the V1 placeholder row. */
        DEEP_LINK_TESTFLIGHT
    }

    /** One routed download: the channel it resolved through and the behavior it demands. */
    public record Route(Channel channel, Behavior behavior) {}

    private static final Map<String, Behavior> TABLE = Map.of(
            key("ANDROID", Channel.DIRECT), Behavior.OPEN_SIGNED_URL,
            key("IOS", Channel.DIRECT), Behavior.OPEN_SIGNED_URL_ADHOC,
            key("IOS", Channel.TESTFLIGHT), Behavior.DEEP_LINK_TESTFLIGHT);

    private DownloadChannelRouting() {}

    /**
     * Routes one platform through one channel; empty when the pair has
     * no row (a configuration fault the caller answers loudly, never a
     * silent fallback to another channel).
     */
    public static Optional<Route> route(String platform, Channel channel) {
        return Optional.ofNullable(TABLE.get(key(platform, channel))).map(behavior -> new Route(channel, behavior));
    }

    private static String key(String platform, Channel channel) {
        return platform + "/" + channel.name();
    }
}
