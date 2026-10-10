package com.shipperdog.api.distribution.internal.direct;

import com.shipperdog.api.artifact.DownloadChannelRouting;
import com.shipperdog.api.artifact.DownloadChannelRouting.Channel;
import com.shipperdog.api.distribution.AdapterCapability;
import com.shipperdog.api.distribution.AdapterOutcome;
import com.shipperdog.api.distribution.DistributionChannelAdapter;
import com.shipperdog.api.distribution.DistributionCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The Direct channel's adapter (issue #61, S13-01, AC1): the S8-04
 * download path -- ShipperDog's own signed-URL issuance tier --
 * migrated onto the adapter SPI with ZERO portal-visible behavior
 * change. The install mechanics were never behind an adapter because
 * they were always the platform's own: the authorization engine's
 * DOWNLOAD decision and the issuance route are untouched, and this
 * adapter's job at dispatch time is exactly the two facts the channel
 * owes the framework -- which artifact slots the channel can serve
 * (the format rule: AAB is Play-only, the direct channel serves APK
 * and ad-hoc/enterprise IPA, FR-006/PRD 19) and the per-platform
 * behavior the portal renders (resolved through {@link
 * DownloadChannelRouting}, the ONE routing table, exactly as its V1
 * javadoc pinned this arrival).
 *
 * <p>The direct channel needs no store account (no {@code
 * credentialProviders()}), has no external system (external ids stay
 * null -- there is nothing to reconcile), and its status answer is
 * the standing DISTRIBUTED it reported at dispatch: availability's
 * own state machine is the download tier's rollback cutoff (S9-02's
 * {@code RolledBackCutoffPolicy}), not a provider to poll.
 */
@Component
public class DirectChannelAdapter implements DistributionChannelAdapter {

    /** The AAB format verdict the direct channel structurally refuses (S5-05's vocabulary). */
    static final String AAB_FORMAT = "AAB";

    /** The record's failure code for a slot the channel cannot serve (format or platform). */
    static final String DIRECT_FORMAT_REFUSED = "DIRECT_FORMAT_REFUSED";

    @Override
    public String channelId() {
        return "direct";
    }

    @Override
    public String displayName() {
        return "Direct Download";
    }

    @Override
    public String description() {
        return "ShipperDog's own signed-URL download tier: Android APK and ad-hoc/enterprise iOS IPA,"
                + " fully self-serve, no store account required (the platform default).";
    }

    @Override
    public Set<AdapterCapability> capabilities() {
        return Set.of(AdapterCapability.DIRECT_ISSUANCE);
    }

    @Override
    public List<AdapterOutcome> distribute(DistributionCommand command) {
        List<AdapterOutcome> outcomes = new ArrayList<>();
        for (DistributionCommand.ArtifactSlot slot : command.artifacts()) {
            boolean routable = DownloadChannelRouting.route(slot.platform(), Channel.DIRECT)
                    .isPresent();
            boolean served = routable && !AAB_FORMAT.equals(slot.format());
            outcomes.add(
                    served
                            ? new AdapterOutcome(slot.artifactId(), AdapterOutcome.DISTRIBUTED, null, null, null)
                            : new AdapterOutcome(
                                    slot.artifactId(),
                                    AdapterOutcome.FAILED,
                                    null,
                                    DIRECT_FORMAT_REFUSED,
                                    "the direct channel serves APK and ad-hoc/enterprise IPA; AAB is"
                                            + " Google Play-only (FR-006)"));
        }
        return outcomes;
    }

    @Override
    public AdapterOutcome status(UUID artifactId, String channel, String externalId) {
        // No provider to poll: the standing DISTRIBUTED the adapter
        // reported is the truth (the download tier's own cutoffs govern
        // live availability).
        return new AdapterOutcome(artifactId, AdapterOutcome.DISTRIBUTED, null, null, null);
    }
}
