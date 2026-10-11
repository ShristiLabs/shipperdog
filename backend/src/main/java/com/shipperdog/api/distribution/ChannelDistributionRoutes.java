package com.shipperdog.api.distribution;

import java.util.Optional;
import java.util.UUID;

/**
 * The artifact download tier's channel question (issue #62, S13-02,
 * the S8-04 route-table row activating): through which NON-DIRECT
 * channel was this artifact distributed — the fact that decides
 * whether a download tap opens a signed URL (the platform's own tier)
 * or hands the install to the channel that owns its mechanics
 * (TestFlight's {@code itms-beta://} deep link). The distribution
 * module owns the fact (the {@code channel_distributions} records);
 * the artifact module's issuance asks through this port — public to
 * public, the Direct adapter's own import of the routing table in
 * reverse.
 *
 * <p>The answer is the artifact's LIVE store channel: a
 * DISTRIBUTING/DISTRIBUTED record on a registered store channel. A
 * FAILED store dispatch routes direct (the store never took the
 * build); an artifact no store dispatched answers empty — the
 * default DIRECT tier, unchanged.
 */
public interface ChannelDistributionRoutes {

    /**
     * The artifact's live store channel id ({@code testflight}, {@code
     * play}), or empty when the artifact rides the direct tier only.
     */
    Optional<String> storeChannelOf(UUID artifactId);
}
