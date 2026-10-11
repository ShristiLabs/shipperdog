package com.shipperdog.api.distribution.internal.apple;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.ChannelSubmissionAccess;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.internal.entity.ChannelSubmissionMetadataEntity;
import com.shipperdog.api.distribution.internal.registry.DistributionAdapterRegistry;
import com.shipperdog.api.distribution.internal.repository.ChannelSubmissionMetadataRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The App Store submission's authoring surface (issue #63, S13-03,
 * AC1): the per-locale listing metadata an operator authors BEFORE
 * approval — description, keywords, screenshot references, release
 * notes. The rows are the AUTHORING surface only: the release
 * manifest's store-submission block freezes them at the APPROVED
 * crossing (PRD 16's immutable copy of what was sent), and the
 * dispatch sends THE FROZEN BLOCK, never these rows — so an edit
 * after approval cannot silently change what a release submits.
 *
 * <p>This service also answers the {@link ChannelSubmissionAccess}
 * port the manifest freeze reads through, with the registry's
 * SUBMISSION-capability check that decides whether a channel carries
 * a submission block at all.
 */
@Service
public class AppStoreSubmissionMetadataService implements ChannelSubmissionAccess {

    /** The channel this surface authors for (the registry's vocabulary). */
    static final String CHANNEL = "appstore";

    /** Apple's BCP-47 locale shape: a language plus optional region/script segments. */
    private static final java.util.regex.Pattern LOCALE_PATTERN =
            java.util.regex.Pattern.compile("^[a-z]{2}(-[A-Za-z0-9]{2,8})*$");

    private static final int DESCRIPTION_MAX = 4000;
    private static final int KEYWORDS_MAX = 128;
    private static final int RELEASE_NOTES_MAX = 4000;
    private static final int SCREENSHOT_REFS_MAX = 10;
    private static final int SCREENSHOT_REF_MAX = 512;

    private final ChannelSubmissionMetadataRepository metadata;
    private final ApplicationAccess applications;
    private final DistributionAdapterRegistry registry;
    private final AuditLogger audit;
    private final Clock clock;

    public AppStoreSubmissionMetadataService(
            ChannelSubmissionMetadataRepository metadata,
            ApplicationAccess applications,
            DistributionAdapterRegistry registry,
            AuditLogger audit,
            Clock clock) {
        this.metadata = metadata;
        this.applications = applications;
        this.registry = registry;
        this.audit = audit;
        this.clock = clock;
    }

    /** The organization's authored App Store metadata, application- then locale-ordered for the console view. */
    @Transactional(readOnly = true)
    public List<MetadataView> list(UUID organizationId) {
        List<MetadataView> views = new ArrayList<>();
        for (ChannelSubmissionMetadataEntity row :
                metadata.findByOrganizationIdAndChannelOrderByApplicationIdAscLocaleAsc(organizationId, CHANNEL)) {
            views.add(viewOf(row));
        }
        return views;
    }

    /**
     * Authors (or rotates) one locale's listing metadata (AC1): the
     * authoring grain is (application, channel, locale) — a second
     * write to the same locale replaces the row whole, never merges.
     */
    @Transactional
    public MetadataView put(MetadataCommand command, UUID actorId) {
        var ownership =
                applications.ownershipOf(command.applicationId()).orElseThrow(() -> invalid("unknown application"));
        if (!command.organizationId().equals(ownership.organizationId())) {
            throw invalid("unknown application");
        }
        String locale = normalizedLocale(command.locale());
        String description = bounded(command.description(), DESCRIPTION_MAX, "description", false);
        String keywords = bounded(command.keywords(), KEYWORDS_MAX, "keywords", true);
        String releaseNotes = bounded(command.releaseNotes(), RELEASE_NOTES_MAX, "releaseNotes", true);
        List<String> screenshotRefs = screenshotRefsOf(command.screenshotRefs());
        ChannelSubmissionMetadataEntity saved = metadata.findByApplicationIdAndChannelAndLocale(
                        command.applicationId(), CHANNEL, locale)
                .map(existing -> {
                    existing.rotate(description, keywords, screenshotRefs, releaseNotes);
                    return metadata.save(existing);
                })
                .orElseGet(() -> metadata.save(new ChannelSubmissionMetadataEntity(
                        command.organizationId(),
                        command.applicationId(),
                        CHANNEL,
                        locale,
                        description,
                        keywords,
                        screenshotRefs,
                        releaseNotes)));
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("channel", CHANNEL);
        detail.put("applicationId", command.applicationId().toString());
        detail.put("locale", locale);
        audit.record(AuditEvent.builder(AuditEventType.DISTRIBUTION_METADATA_SAVED)
                .orgId(command.organizationId())
                .applicationId(command.applicationId())
                .userId(actorId)
                .detail(detail)
                .success(true)
                .build());
        return viewOf(saved);
    }

    /** Removes one authored locale (the authoring surface only; a frozen manifest block keeps its copy). */
    @Transactional
    public void delete(UUID metadataId, UUID organizationId, UUID actorId) {
        ChannelSubmissionMetadataEntity row = metadata.findByIdAndOrganizationId(metadataId, organizationId)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_METADATA_NOT_FOUND, "unknown submission metadata"));
        metadata.delete(row);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("channel", CHANNEL);
        detail.put("applicationId", row.getApplicationId().toString());
        detail.put("locale", row.getLocale());
        audit.record(AuditEvent.builder(AuditEventType.DISTRIBUTION_METADATA_REMOVED)
                .orgId(organizationId)
                .applicationId(row.getApplicationId())
                .userId(actorId)
                .detail(detail)
                .success(true)
                .build());
    }

    /**
     * The manifest freeze's read (the {@link ChannelSubmissionAccess}
     * port): the application's authored locales on a channel — empty
     * when the channel is not a registered SUBMISSION channel (the
     * manifest's block stays absent for those releases).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<SubmissionSnapshot> snapshotOf(UUID applicationId, String channel) {
        boolean submissionChannel = registry.byChannelId(channel)
                .map(adapter ->
                        adapter.capabilities().contains(com.shipperdog.api.distribution.AdapterCapability.SUBMISSION))
                .orElse(false);
        if (!submissionChannel) {
            return Optional.empty();
        }
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

    private static MetadataView viewOf(ChannelSubmissionMetadataEntity row) {
        return new MetadataView(
                row.getId(),
                row.getOrganizationId(),
                row.getApplicationId(),
                row.getChannel(),
                row.getLocale(),
                row.getDescription(),
                row.getKeywords(),
                row.getScreenshotRefs(),
                row.getReleaseNotes(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private static String normalizedLocale(String raw) {
        String locale = raw == null ? "" : raw.trim();
        if (!LOCALE_PATTERN.matcher(locale).matches()) {
            throw invalid("locale must be a store locale like en-US or de-DE");
        }
        return locale;
    }

    private static String bounded(String value, int max, String field, boolean optional) {
        if (value == null || value.isBlank()) {
            if (optional) {
                return null;
            }
            throw invalid(field + " is required");
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            throw invalid(field + " exceeds its " + max + "-character maximum");
        }
        return trimmed;
    }

    private static List<String> screenshotRefsOf(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return List.of();
        }
        if (refs.size() > SCREENSHOT_REFS_MAX) {
            throw invalid("at most " + SCREENSHOT_REFS_MAX + " screenshot references are pinned per locale");
        }
        List<String> normalized = new ArrayList<>();
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                continue;
            }
            String trimmed = ref.strip();
            if (trimmed.length() > SCREENSHOT_REF_MAX) {
                throw invalid("a screenshot reference exceeds its " + SCREENSHOT_REF_MAX + "-character maximum");
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    private static DistributionException invalid(String message) {
        return new DistributionException(DistributionException.Code.INVALID_REQUEST, message);
    }

    /** The authoring write's body: the owning dimensions and the locale's listing facts. */
    public record MetadataCommand(
            UUID organizationId,
            UUID applicationId,
            String locale,
            String description,
            String keywords,
            List<String> screenshotRefs,
            String releaseNotes) {}

    /** One authored locale's served view. */
    public record MetadataView(
            UUID id,
            UUID organizationId,
            UUID applicationId,
            String channel,
            String locale,
            String description,
            String keywords,
            List<String> screenshotRefs,
            String releaseNotes,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {}
}
