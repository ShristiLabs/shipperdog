package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity;
import com.shipperdog.api.distribution.internal.repository.ChannelDistributionRepository;
import com.shipperdog.api.integration.StoreStatusEventMapped;
import com.shipperdog.api.release.ReleaseAccess;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The mapped store event's connector leg (issue #63, S13-03, AC2,
 * FR-020/022): the V42 receiver's reserved consumer — a mapped App
 * Store notification is a SYNC TRIGGER for the app's walking store
 * records, not a state interpretation. The event's MEANING still
 * comes from the provider's own answer: the consumer resolves the
 * bundle id to the release's version artifacts, refreshes every
 * non-terminal appstore-channel distribution record of those releases
 * through the same refresh the dashboard's explicit button and the
 * polling reconciliation pass run, and audits the application. The
 * review state that lands on the record IS Apple's own word (the
 * adapter's status observation), never the webhook vocabulary's
 * guess — so a provider vocabulary change can never mis-map a
 * release.
 *
 * <p>The listener runs AFTER_COMMIT (the mapped row is committed, and
 * a consumer fault never rolls back Apple's receipt) and hands the
 * work to its own executor: the refresh dials the store, and Apple's
 * receipt must not wait on store latency. Each record's refresh is
 * contained — one record's provider fault never the event's.
 */
@Component
public class StoreStatusEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(StoreStatusEventConsumer.class);

    private static final String PROVIDER_APPSTORE = "appstore";
    private static final String CHANNEL_APPSTORE = "appstore";
    private static final String PROVIDER_GOOGLE_PLAY = "google-play";
    private static final String CHANNEL_PLAY = "play";

    private final ChannelDistributionRepository records;
    private final ReleaseAccess releases;
    private final DistributionDispatchService dispatch;
    private final AuditLogger audit;
    private final JdbcTemplate jdbc;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();

    public StoreStatusEventConsumer(
            ChannelDistributionRepository records,
            ReleaseAccess releases,
            DistributionDispatchService dispatch,
            AuditLogger audit,
            JdbcTemplate jdbc) {
        this.records = records;
        this.releases = releases;
        this.dispatch = dispatch;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @PreDestroy
    void shutdown() {
        worker.close();
    }

    /** The after-commit handoff: Apple's receipt answers first, the sync runs off the request thread. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onStoreStatusEventMapped(StoreStatusEventMapped event) {
        worker.execute(() -> {
            try {
                apply(event);
            } catch (RuntimeException e) {
                // Containment: the polling reconciliation pass remains
                // the backstop; a consumer fault is evidence, not a
                // lost sync.
                log.warn(
                        "the mapped {} event for bundle {} could not be applied",
                        event.eventType(),
                        event.bundleId(),
                        e);
            }
        });
    }

    /**
     * The application itself (also the deterministic path tests drive
     * directly): resolve the walking appstore records whose release's
     * version carries the event's bundle id, refresh each, audit.
     * Since #64 a mapped google-play event refreshes the walking play
     * records: Google's Real-Time Developer Notifications carry no
     * package identity (the thin reader maps kind and purchase
     * context, never the app), so the play arm refreshes every
     * walking play-channel record within the same page discipline —
     * the sync-trigger-never-interpret doctrine, with the polling
     * reconciliation pass as the backstop.
     */
    public void apply(StoreStatusEventMapped event) {
        if (PROVIDER_GOOGLE_PLAY.equals(event.provider())) {
            applyPlay(event);
            return;
        }
        if (!PROVIDER_APPSTORE.equals(event.provider())
                || event.bundleId() == null
                || event.bundleId().isBlank()) {
            return;
        }
        List<ChannelDistributionEntity> walking = records.findByStatusInAndExternalIdIsNotNull(
                        List.of(
                                ChannelDistributionEntity.Status.PENDING,
                                ChannelDistributionEntity.Status.DISTRIBUTING),
                        org.springframework.data.domain.PageRequest.of(0, 500))
                .getContent();
        // The bundle's applications, resolved through the org-grain
        // registry read (this thread binds no scope; the scoped
        // repository reads of a request thread would refuse here).
        Set<UUID> applicationsOfBundle = Set.copyOf(jdbc.query(
                "SELECT id FROM applications WHERE bundle_id = ?",
                (row, index) -> UUID.fromString(row.getString(1)),
                event.bundleId()));
        Set<UUID> refreshable = new LinkedHashSet<>();
        for (ChannelDistributionEntity row : walking) {
            if (!CHANNEL_APPSTORE.equals(row.getChannel())) {
                continue;
            }
            boolean bundleMatches = releases.distributionFactsOf(row.getReleaseId())
                    .map(facts -> applicationsOfBundle.contains(facts.applicationId()))
                    .orElse(false);
            if (bundleMatches) {
                refreshable.add(row.getId());
            }
        }
        refreshRecords(event, refreshable);
    }

    /** The play arm (S13-04, #64, AC5): refresh every walking play record, audit. */
    private void applyPlay(StoreStatusEventMapped event) {
        Set<UUID> refreshable = new LinkedHashSet<>();
        for (ChannelDistributionEntity row : records.findByStatusInAndExternalIdIsNotNull(
                List.of(ChannelDistributionEntity.Status.PENDING, ChannelDistributionEntity.Status.DISTRIBUTING),
                org.springframework.data.domain.PageRequest.of(0, 500))) {
            if (CHANNEL_PLAY.equals(row.getChannel())) {
                refreshable.add(row.getId());
            }
        }
        refreshRecords(event, refreshable);
    }

    /** The shared leg: refresh each record (contained), audit the event's application. */
    private void refreshRecords(StoreStatusEventMapped event, Set<UUID> refreshable) {
        int refreshed = 0;
        for (UUID recordId : refreshable) {
            ChannelDistributionEntity row = records.findById(recordId).orElse(null);
            if (row == null) {
                continue;
            }
            try {
                dispatch.refresh(row.getOrganizationId(), row.getId(), null);
                refreshed++;
            } catch (RuntimeException e) {
                // One record's provider fault (throttled, credential
                // gap) never the event's: the audit line below carries
                // the count that DID land, the reconciliation pass
                // remains the backstop.
                log.debug("the event-triggered refresh of record {} refused: {}", recordId, e.getMessage());
            }
        }
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("provider", event.provider());
        detail.put("eventType", event.eventType() == null ? "" : event.eventType());
        detail.put("bundleId", event.bundleId() == null ? "" : event.bundleId());
        detail.put("recordsRefreshed", String.valueOf(refreshed));
        audit.record(AuditEvent.builder(AuditEventType.STORE_EVENT_APPLIED)
                .detail(detail)
                .success(true)
                .build());
    }
}
