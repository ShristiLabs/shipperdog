package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.ChannelSubmissionMetadataEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the authored store-listing metadata (V45, issue #63,
 * S13-03). A plain {@link JpaRepository}: organization-governed like
 * the credential chain's rows, addressed by (application, channel,
 * locale) -- the authoring grain's own unique key.
 */
public interface ChannelSubmissionMetadataRepository extends JpaRepository<ChannelSubmissionMetadataEntity, UUID> {

    /** The organization's authored metadata on one channel, application- then locale-ordered for the stable view. */
    List<ChannelSubmissionMetadataEntity> findByOrganizationIdAndChannelOrderByApplicationIdAscLocaleAsc(
            UUID organizationId, String channel);

    /** The application's authored metadata on one channel (the manifest freeze's snapshot read). */
    List<ChannelSubmissionMetadataEntity> findByApplicationIdAndChannelOrderByLocaleAsc(
            UUID applicationId, String channel);

    /** The exact authored locale (the authoring grain; a second write rotates the row). */
    Optional<ChannelSubmissionMetadataEntity> findByApplicationIdAndChannelAndLocale(
            UUID applicationId, String channel, String locale);

    /** One metadata row inside the organization (the delete route's concealed-miss guard). */
    Optional<ChannelSubmissionMetadataEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
