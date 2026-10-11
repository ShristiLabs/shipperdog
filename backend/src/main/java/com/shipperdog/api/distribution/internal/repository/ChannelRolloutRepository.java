package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.ChannelRolloutEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The rollout state rows (V47, S13-05, #65). Plain repository — the
 * rollout surface is organization-addressed (the record's own
 * organization check is the read's guard, the standing of every
 * store-connector surface).
 */
public interface ChannelRolloutRepository extends JpaRepository<ChannelRolloutEntity, UUID> {

    /** The release's one rollout row on a channel (the grain's unique answer). */
    Optional<ChannelRolloutEntity> findByReleaseIdAndChannel(UUID releaseId, String channel);

    /** The organization-guarded resolution (the concealed miss across organizations). */
    Optional<ChannelRolloutEntity> findByReleaseIdAndChannelAndOrganizationId(
            UUID releaseId, String channel, UUID organizationId);
}
