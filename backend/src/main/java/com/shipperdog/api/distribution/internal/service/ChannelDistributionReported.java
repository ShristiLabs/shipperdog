package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.distribution.AdapterOutcome;
import java.util.List;
import java.util.UUID;

/**
 * The dispatch's framework-generic completion fact (S13-05, #65):
 * published inside the crossing's transaction after a dispatch's
 * outcomes landed on the records — the fact a channel service that
 * keys off a LANDED dispatch listens for. The dispatch itself stays
 * channel-blind; this event carries only what the framework already
 * held: the release's owning dimensions, the channel the dispatch ran
 * through, the ring the walk stood on, and the adapter's per-artifact
 * outcomes verbatim (external ids and provider words included).
 *
 * <p>Not published on a dispatch's record-level failure path: the
 * fact exists exactly when a provider conversation landed, which is
 * the precondition of every listener's own state.
 */
public record ChannelDistributionReported(
        UUID releaseId,
        UUID organizationId,
        UUID applicationId,
        String channel,
        String ringCode,
        List<AdapterOutcome> outcomes) {

    public ChannelDistributionReported {
        outcomes = List.copyOf(outcomes);
    }
}
