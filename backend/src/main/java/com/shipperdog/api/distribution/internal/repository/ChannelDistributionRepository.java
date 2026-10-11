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

    /**
     * One release's records through one channel (S13-03, #63): the
     * phased-release surface's resolution of the release's store-side
     * submission record inside the organization.
     */
    List<ChannelDistributionEntity> findByReleaseIdAndOrganizationIdAndChannelOrderByCreatedAtAsc(
            UUID releaseId, UUID organizationId, String channel);

    /** The exact record of one release artifact through one channel (the dispatch's idempotency grain). */
    Optional<ChannelDistributionEntity> findByReleaseIdAndArtifactIdAndChannel(
            UUID releaseId, UUID artifactId, String channel);

    /** The records of one release inside the organization (the concealed-miss guard of the read route). */
    List<ChannelDistributionEntity> findByReleaseIdAndOrganizationIdOrderByChannelAsc(
            UUID releaseId, UUID organizationId);

    /**
     * The reconciliation pass's input (S13-02, #62): store-channel
     * records with an external id still walking toward their provider
     * answer — PENDING or DISTRIBUTING, capped per pass.
     */
    org.springframework.data.domain.Page<ChannelDistributionEntity> findByStatusInAndExternalIdIsNotNull(
            java.util.Collection<ChannelDistributionEntity.Status> statuses,
            org.springframework.data.domain.Pageable pageable);

    /** The organization's records on one channel (the group sync's build-drift walk). */
    List<ChannelDistributionEntity> findByOrganizationIdAndChannelOrderByCreatedAtAsc(
            UUID organizationId, String channel);

    /** The artifact's records newest-first (the download channel's live-store question). */
    List<ChannelDistributionEntity> findByArtifactIdOrderByUpdatedAtDesc(UUID artifactId);
}
