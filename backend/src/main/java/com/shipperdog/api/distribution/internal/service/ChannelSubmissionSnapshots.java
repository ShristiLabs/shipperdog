package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.distribution.AdapterCapability;
import com.shipperdog.api.distribution.ChannelSubmissionAccess;
import com.shipperdog.api.distribution.internal.entity.ChannelReleaseNotesEntity;
import com.shipperdog.api.distribution.internal.entity.ChannelSubmissionMetadataEntity;
import com.shipperdog.api.distribution.internal.registry.DistributionAdapterRegistry;
import com.shipperdog.api.distribution.internal.repository.ChannelReleaseNotesRepository;
import com.shipperdog.api.distribution.internal.repository.ChannelSubmissionMetadataRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manifest freeze's channel-generic snapshot read (issue #63's
 * port; generalized by #64, S13-04): which authored rows a release's
 * store-submission block freezes, per channel's own authoring
 * surface. The SUBMISSION channels (the App Store) keep the #63
 * contract exactly — the full listing metadata rows, present with an
 * empty locale list when nothing was authored (the honest empty the
 * submission's loud failure acts on). The play channel freezes its
 * release-notes rows when some exist and answers absent when none do
 * (a Play release without authored notes submits none — legitimate,
 * unlike an App Store submission with no listing at all). Channels
 * with no authoring surface (direct, testflight) answer absent.
 */
@Service
public class ChannelSubmissionSnapshots implements ChannelSubmissionAccess {

    private final ChannelSubmissionMetadataRepository metadata;
    private final ChannelReleaseNotesRepository releaseNotes;
    private final DistributionAdapterRegistry registry;

    public ChannelSubmissionSnapshots(
            ChannelSubmissionMetadataRepository metadata,
            ChannelReleaseNotesRepository releaseNotes,
            DistributionAdapterRegistry registry) {
        this.metadata = metadata;
        this.releaseNotes = releaseNotes;
        this.registry = registry;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The per-channel authoring surfaces this read consults: the
     * V45 listing metadata (the App Store's SUBMISSION channels) and
     * the V46 release notes (the play channel).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<SubmissionSnapshot> snapshotOf(UUID applicationId, String channel) {
        boolean registered = registry.byChannelId(channel).isPresent();
        if (!registered) {
            return Optional.empty();
        }
        if (registry.byChannelId(channel)
                .map(adapter -> adapter.capabilities().contains(AdapterCapability.SUBMISSION))
                .orElse(false)) {
            // The #63 contract, unchanged: a SUBMISSION channel's
            // snapshot is present even with an empty locale list.
            List<SubmissionLocale> locales = new ArrayList<>();
            for (ChannelSubmissionMetadataEntity row :
                    metadata.findByApplicationIdAndChannelOrderByLocaleAsc(applicationId, channel)) {
                locales.add(new SubmissionLocale(
                        row.getLocale(),
                        row.getDescription(),
                        row.getKeywords(),
                        row.getScreenshotRefs(),
                        row.getReleaseNotes()));
            }
            return Optional.of(new SubmissionSnapshot(List.copyOf(locales)));
        }
        if ("play".equals(channel)) {
            // The #64 contract: the release-notes rows freeze when some
            // exist; a play release with no authored notes freezes no
            // block (the dispatch then submits no notes — legitimate).
            List<ChannelReleaseNotesEntity> rows =
                    releaseNotes.findByApplicationIdAndChannelOrderByLocaleAsc(applicationId, channel);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            List<SubmissionLocale> locales = new ArrayList<>();
            for (ChannelReleaseNotesEntity row : rows) {
                locales.add(new SubmissionLocale(row.getLocale(), null, null, List.of(), row.getNotes()));
            }
            return Optional.of(new SubmissionSnapshot(List.copyOf(locales)));
        }
        // A registered channel with no authoring surface: no block.
        return Optional.empty();
    }
}
