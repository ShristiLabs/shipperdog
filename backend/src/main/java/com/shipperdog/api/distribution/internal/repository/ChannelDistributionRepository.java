package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the adapter-driven distribution records (V43). A plain
 * {@link JpaRepository}: the records hang off one release (the
 * plain-child doctrine -- addressed through the release, never by
 * their own routes), and the adapter's report is the only writer of
 * status, external id, and failure code.
 */
public interface ChannelDistributionRepository extends JpaRepository<ChannelDistributionEntity, UUID> {

    /** One release's records, channel-ordered for the stable view. */
    List<ChannelDistributionEntity> findByReleaseIdOrderByChannelAsc(UUID releaseId);

    /** The exact record of one release artifact through one channel (the dispatch's idempotency grain). */
    Optional<ChannelDistributionEntity> findByReleaseIdAndArtifactIdAndChannel(
            UUID releaseId, UUID artifactId, String channel);

    /** The records of one release inside the organization (the concealed-miss guard of the read route). */
    List<ChannelDistributionEntity> findByReleaseIdAndOrganizationIdOrderByChannelAsc(
            UUID releaseId, UUID organizationId);
}
