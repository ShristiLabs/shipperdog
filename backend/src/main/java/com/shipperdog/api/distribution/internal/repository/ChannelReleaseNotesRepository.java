package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.ChannelReleaseNotesEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the authored store release notes (V46, issue #64,
 * S13-04). A plain {@link JpaRepository}: organization-governed like
 * the V45 metadata rows, addressed by (application, channel, locale)
 * — the authoring grain's own unique key.
 */
public interface ChannelReleaseNotesRepository extends JpaRepository<ChannelReleaseNotesEntity, UUID> {

    /** The organization's authored release notes on one channel, application- then locale-ordered for the stable view. */
    List<ChannelReleaseNotesEntity> findByOrganizationIdAndChannelOrderByApplicationIdAscLocaleAsc(
            UUID organizationId, String channel);

    /** The application's authored release notes on one channel (the manifest freeze's snapshot read). */
    List<ChannelReleaseNotesEntity> findByApplicationIdAndChannelOrderByLocaleAsc(UUID applicationId, String channel);

    /** The exact authored locale (the authoring grain; a second write rotates the row). */
    Optional<ChannelReleaseNotesEntity> findByApplicationIdAndChannelAndLocale(
            UUID applicationId, String channel, String locale);

    /** One release-notes row inside the organization (the delete route's concealed-miss guard). */
    Optional<ChannelReleaseNotesEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
