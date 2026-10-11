package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.ChannelGroupLinkEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the tester-group to provider-group links (V44, issue
 * #62, S13-02). A plain {@link JpaRepository}: organization-governed
 * like the credential chain's rows (the mapping surface's evaluator
 * hop), addressed by (channel, group) — the mapping grain's own unique
 * key.
 */
public interface ChannelGroupLinkRepository extends JpaRepository<ChannelGroupLinkEntity, UUID> {

    /** The organization's links on one channel, group-ordered for the stable view. */
    List<ChannelGroupLinkEntity> findByOrganizationIdAndChannelOrderByGroupIdAsc(UUID organizationId, String channel);

    /** The exact link of one tester group on one channel (the mapping grain). */
    Optional<ChannelGroupLinkEntity> findByChannelAndGroupId(String channel, UUID groupId);

    /** The bulk resolution of a dispatch's target groups (one query, channel-filtered). */
    List<ChannelGroupLinkEntity> findByChannelAndGroupIdIn(String channel, Collection<UUID> groupIds);

    /** One link inside the organization (the read route's concealed-miss guard). */
    Optional<ChannelGroupLinkEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
