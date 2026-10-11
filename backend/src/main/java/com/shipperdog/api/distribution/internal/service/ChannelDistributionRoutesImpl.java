package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.distribution.ChannelDistributionRoutes;
import com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity;
import com.shipperdog.api.distribution.internal.registry.DistributionAdapterRegistry;
import com.shipperdog.api.distribution.internal.repository.ChannelDistributionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The download channel question over the records (issue #62,
 * S13-02): the artifact's live store channel — its newest
 * DISTRIBUTING/DISTRIBUTED record on a channel that is not the
 * direct tier. Direct rows never answer (they are the default the
 * caller already assumes); a FAILED store dispatch never answers
 * either (the store never took the build, so the direct tier keeps
 * serving it — the authorization engine's availability, not the
 * store's).
 */
@Component
class ChannelDistributionRoutesImpl implements ChannelDistributionRoutes {

    private final ChannelDistributionRepository records;

    ChannelDistributionRoutesImpl(ChannelDistributionRepository records) {
        this.records = records;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> storeChannelOf(UUID artifactId) {
        return records.findByArtifactIdOrderByUpdatedAtDesc(artifactId).stream()
                .filter(row -> row.getStatus() == ChannelDistributionEntity.Status.DISTRIBUTED
                        || row.getStatus() == ChannelDistributionEntity.Status.DISTRIBUTING)
                .map(ChannelDistributionEntity::getChannel)
                .filter(channel -> !DistributionAdapterRegistry.DIRECT_CHANNEL.equals(channel))
                .findFirst();
    }
}
