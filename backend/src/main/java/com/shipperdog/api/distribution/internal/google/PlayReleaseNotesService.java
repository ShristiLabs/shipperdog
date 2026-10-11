package com.shipperdog.api.distribution.internal.google;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.internal.entity.ChannelReleaseNotesEntity;
import com.shipperdog.api.distribution.internal.repository.ChannelReleaseNotesRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Play submission's release-notes authoring surface (issue #64,
 * S13-04, AC2): the per-locale what's-new an operator authors BEFORE
 * approval — the ONLY listing fact the testing tracks' track-release
 * write carries (full listing metadata belongs to later stories).
 * The rows are the AUTHORING surface only: the release manifest's
 * store-submission block freezes them at the APPROVED crossing (PRD
 * 16's immutable copy of what was sent, the V45 metadata precedent),
 * and the dispatch sends THE FROZEN BLOCK, never these rows.
 *
 * <p>The field set is Google's own: one locale + text pair per
 * language, text capped at 500 characters (the provider's cap; the
 * authoring surface enforces it so a frozen block can never carry a
 * value the provider would refuse).
 */
@Service
public class PlayReleaseNotesService {

    /** The channel this surface authors for (the registry's vocabulary). */
    static final String CHANNEL = "play";

    /** Google's own release-note cap: 500 characters. */
    static final int NOTES_MAX = 500;

    /** The store locale shape: a language plus optional region/script segments (BCP-47). */
    private static final java.util.regex.Pattern LOCALE_PATTERN =
            java.util.regex.Pattern.compile("^[a-z]{2}(-[A-Za-z0-9]{2,8})*$");

    private final ChannelReleaseNotesRepository notes;
    private final ApplicationAccess applications;
    private final AuditLogger audit;

    public PlayReleaseNotesService(
            ChannelReleaseNotesRepository notes, ApplicationAccess applications, AuditLogger audit) {
        this.notes = notes;
        this.applications = applications;
        this.audit = audit;
    }

    /** The organization's authored Play release notes, application- then locale-ordered for the console view. */
    @Transactional(readOnly = true)
    public List<NotesView> list(UUID organizationId) {
        List<NotesView> views = new ArrayList<>();
        for (ChannelReleaseNotesEntity row :
                notes.findByOrganizationIdAndChannelOrderByApplicationIdAscLocaleAsc(organizationId, CHANNEL)) {
            views.add(viewOf(row));
        }
        return views;
    }

    /**
     * Authors (or rotates) one locale's release notes (AC2): the
     * authoring grain is (application, channel, locale) — a second
     * write to the same locale replaces the row whole, never merges.
     */
    @Transactional
    public NotesView put(NotesCommand command, UUID actorId) {
        var ownership =
                applications.ownershipOf(command.applicationId()).orElseThrow(() -> invalid("unknown application"));
        if (!command.organizationId().equals(ownership.organizationId())) {
            throw invalid("unknown application");
        }
        String locale = normalizedLocale(command.locale());
        String text = bounded(command.notes());
        ChannelReleaseNotesEntity saved = notes.findByApplicationIdAndChannelAndLocale(
                        command.applicationId(), CHANNEL, locale)
                .map(existing -> {
                    existing.rotate(text);
                    return notes.save(existing);
                })
                .orElseGet(() -> notes.save(new ChannelReleaseNotesEntity(
                        command.organizationId(), command.applicationId(), CHANNEL, locale, text)));
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
    public void delete(UUID notesId, UUID organizationId, UUID actorId) {
        ChannelReleaseNotesEntity row = notes.findByIdAndOrganizationId(notesId, organizationId)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_METADATA_NOT_FOUND, "unknown release notes"));
        notes.delete(row);
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

    private static NotesView viewOf(ChannelReleaseNotesEntity row) {
        return new NotesView(
                row.getId(),
                row.getOrganizationId(),
                row.getApplicationId(),
                row.getChannel(),
                row.getLocale(),
                row.getNotes(),
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

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            throw invalid("notes are required");
        }
        String trimmed = value.strip();
        if (trimmed.length() > NOTES_MAX) {
            throw invalid("release notes exceed Google's " + NOTES_MAX + "-character maximum");
        }
        return trimmed;
    }

    private static DistributionException invalid(String message) {
        return new DistributionException(DistributionException.Code.INVALID_REQUEST, message);
    }

    /** The authoring write's body: the owning dimensions and the locale's what's-new. */
    public record NotesCommand(UUID organizationId, UUID applicationId, String locale, String notes) {}

    /** One authored locale's served view. */
    public record NotesView(
            UUID id,
            UUID organizationId,
            UUID applicationId,
            String channel,
            String locale,
            String notes,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {}
}
