package com.shipperdog.api.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.artifact.DownloadChannelRouting.Behavior;
import com.shipperdog.api.artifact.DownloadChannelRouting.Channel;
import org.junit.jupiter.api.Test;

/**
 * The channel routing table's contract (issue #38, S8-04, the story's
 * routing-table AC): the V1 rows exist exactly as named — DIRECT for
 * both platforms (Android the plain signed URL, iOS the ad-hoc/
 * enterprise note first) and the TESTFLIGHT placeholder for iOS — and
 * an unmapped pair routes to nothing (a configuration fault the
 * caller answers loudly, never a silent fallback).
 */
class DownloadChannelRoutingTest {

    @Test
    void directAndroidOpensTheSignedUrlAfterTheUnknownAppsConsent() {
        assertThat(DownloadChannelRouting.route("ANDROID", Channel.DIRECT))
                .contains(new DownloadChannelRouting.Route(Channel.DIRECT, Behavior.OPEN_SIGNED_URL));
    }

    @Test
    void directIosCarriesTheAdhocSigningNoteBeforeTheUrl() {
        assertThat(DownloadChannelRouting.route("IOS", Channel.DIRECT))
                .contains(new DownloadChannelRouting.Route(Channel.DIRECT, Behavior.OPEN_SIGNED_URL_ADHOC));
    }

    @Test
    void testflightIosIsThePlaceholderDeepLinkRow() {
        // Unreachable from V1's API (every V1 download is DIRECT; the
        // channel-selection input arrives with the M2 distribution
        // model) — pinned so the TestFlight adapter routes through
        // THIS row instead of growing a second table.
        assertThat(DownloadChannelRouting.route("IOS", Channel.TESTFLIGHT))
                .contains(new DownloadChannelRouting.Route(Channel.TESTFLIGHT, Behavior.DEEP_LINK_TESTFLIGHT));
    }

    @Test
    void testflightAndroidHasNoRowAndRoutesToNothing() {
        assertThat(DownloadChannelRouting.route("ANDROID", Channel.TESTFLIGHT)).isEmpty();
    }
}
