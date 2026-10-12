package com.shipperdog.api.notification.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.notification.AuditStreamException;
import com.shipperdog.api.notification.AuditStreamFormat;
import com.shipperdog.api.notification.AuditStreamKind;
import com.shipperdog.api.notification.DomainEvent;
import com.shipperdog.api.notification.DomainEventName;
import com.shipperdog.api.notification.internal.entity.AuditStreamDeliveryEntity;
import com.shipperdog.api.notification.internal.entity.AuditStreamExportEntity;
import com.shipperdog.api.notification.internal.entity.NotificationOutboxEntity;
import com.shipperdog.api.notification.internal.repository.AuditStreamBatchRepository;
import com.shipperdog.api.notification.internal.repository.AuditStreamDeliveryRepository;
import com.shipperdog.api.notification.internal.repository.AuditStreamExportRepository;
import com.shipperdog.api.notification.internal.repository.NotificationOutboxRepository;
import com.shipperdog.api.organization.OrganizationAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The audit-stream export surface's core (issue #71, S14-05, AC5):
 * the org-scoped CRUD over the V53 exports table, the validation
 * rules that keep every destination honest (kind-appropriate shape,
 * the SSRF-vetted collector URL or gs:// bucket URI, audit event
 * types from the frozen vocabulary, secret references as
 * references), the delivery log's reads, the dead-letter replay, the
 * bucket batch history, and the test action. The rings pattern
 * throughout: reference data of one organization, concealed 404s
 * across organizations, every write audited -- with the secret
 * references as references only, never resolved material.
 */
@Service
public class AuditStreamExportService {

    /** The per-organization export ceiling: the routing table stays a governed handful, not an unbounded fan-out. */
    static final int MAX_EXPORTS_PER_ORG = 10;

    private static final int SECRET_REF_MAX = 512;
    private static final int AUTH_HEADER_MAX = 64;

    private final AuditStreamExportRepository exports;
    private final AuditStreamDeliveryRepository deliveries;
    private final AuditStreamBatchRepository batches;
    private final NotificationOutboxRepository outbox;
    private final OrganizationAccess organizations;
    private final AuditEventPublisher audit;
    private final WebhookTargetPolicy webhookTargets;
    private final com.shipperdog.api.environment.SecretReferenceResolver secrets;
    private final AuditStreamDispatcher dispatcher;
    private final Clock clock;

    AuditStreamExportService(
            AuditStreamExportRepository exports,
            AuditStreamDeliveryRepository deliveries,
            AuditStreamBatchRepository batches,
            NotificationOutboxRepository outbox,
            OrganizationAccess organizations,
            AuditEventPublisher audit,
            WebhookTargetPolicy webhookTargets,
            com.shipperdog.api.environment.SecretReferenceResolver secrets,
            AuditStreamDispatcher dispatcher,
            Clock clock) {
        this.exports = exports;
        this.deliveries = deliveries;
        this.batches = batches;
        this.outbox = outbox;
        this.organizations = organizations;
        this.audit = audit;
        this.webhookTargets = webhookTargets;
        this.secrets = secrets;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    // The views ------------------------------------------------------------------

    /**
     * The served export view: everything EXCEPT resolved secret
     * material (the references themselves are served -- they are the
     * configuration; what a reference resolves to never leaves the
     * delivery path).
     */
    public record ExportView(
            UUID id,
            UUID organizationId,
            AuditStreamKind kind,
            AuditStreamFormat format,
            List<String> eventTypes,
            String destinationUrl,
            String bucketUri,
            String signingSecretRef,
            String authHeaderName,
            String authSecretRef,
            boolean enabled,
            Instant watermark,
            long batchSeq,
            long batchesWritten,
            long eventsExported,
            Instant createdAt,
            Instant updatedAt) {

        static ExportView of(AuditStreamExportEntity row, long batchesWritten, long eventsExported) {
            return new ExportView(
                    row.getId(),
                    row.getOrganizationId(),
                    row.getKind(),
                    row.getFormat(),
                    row.getEventTypes(),
                    row.getDestinationUrl(),
                    row.getBucketUri(),
                    row.getSigningSecretRef(),
                    row.getAuthHeaderName(),
                    row.getAuthSecretRef(),
                    row.isEnabled(),
                    row.getWatermark(),
                    row.getBatchSeq(),
                    batchesWritten,
                    eventsExported,
                    row.getCreatedAt(),
                    row.getUpdatedAt());
        }
    }

    /** One delivery-log row: the ledger as the console and the API read it. */
    public record DeliveryView(
            UUID id,
            UUID eventId,
            UUID exportId,
            AuditStreamFormat format,
            AuditStreamDeliveryEntity.Status status,
            int attempts,
            String lastError,
            Instant deliveredAt,
            Instant createdAt) {}

    /** One batch-history row: the completeness ledger the bucket leg serves. */
    public record BatchView(
            UUID id,
            long seq,
            Instant watermarkFrom,
            Instant watermarkTo,
            int eventCount,
            String batchSha256,
            String objectUri,
            String manifestUri,
            Instant createdAt) {}

    /** The create command: every export fact, validated below. */
    public record CreateCommand(
            AuditStreamKind kind,
            AuditStreamFormat format,
            List<String> eventTypes,
            String destinationUrl,
            String bucketUri,
            String signingSecretRef,
            String authHeaderName,
            String authSecretRef,
            String initialWatermark) {}

    /** The edit command: absent fields keep their standing; the kind never moves. */
    public record UpdateCommand(
            AuditStreamFormat format,
            List<String> eventTypes,
            String destinationUrl,
            String bucketUri,
            String signingSecretRef,
            String authHeaderName,
            String authSecretRef,
            Boolean enabled) {}

    // The reads --------------------------------------------------------------------

    /** The organization's exports, newest first, with their batch rollups. */
    public List<ExportView> list(UUID actorId, UUID organizationId, int offset, int limit) {
        requireOrganization(organizationId);
        return exports
                .findByOrganizationIdOrderByCreatedAtDesc(organizationId, PageRequest.of(offset / limit, limit))
                .stream()
                .map(row -> ExportView.of(row, countBatches(row.getId()), sumExported(row.getId())))
                .toList();
    }

    /** One export inside its organization; another organization's conceals as the miss. */
    public ExportView get(UUID actorId, UUID organizationId, UUID exportId) {
        requireOrganization(organizationId);
        return ExportView.of(requireExport(organizationId, exportId), countBatches(exportId), sumExported(exportId));
    }

    /**
     * The delivery log (AC3): the organization's HTTPS deliveries,
     * newest first, optionally narrowed to one export and one status
     * ({@code SCHEDULED}, {@code DELIVERED}, {@code FAILED} -- the
     * last being the visible dead-letter). A foreign export id
     * narrows nothing -- it conceals exactly like an unknown one.
     */
    public List<DeliveryView> deliveryLog(
            UUID actorId,
            UUID organizationId,
            UUID exportId,
            AuditStreamDeliveryEntity.Status status,
            int offset,
            int limit) {
        requireOrganization(organizationId);
        if (exportId != null
                && exports.findByIdAndOrganizationId(exportId, organizationId).isEmpty()) {
            return List.of();
        }
        return deliveries
                .findAll(
                        orgScoped(organizationId, exportId, status),
                        PageRequest.of(offset / limit, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
                .stream()
                .map(this::viewOf)
                .toList();
    }

    /** The bucket export's batch history, newest sequence first (the completeness ledger). */
    public List<BatchView> batches(UUID actorId, UUID organizationId, UUID exportId, int offset, int limit) {
        requireOrganization(organizationId);
        AuditStreamExportEntity export = requireExport(organizationId, exportId);
        return batches.findByExportIdOrderBySeqDesc(export.getId(), PageRequest.of(offset / limit, limit)).stream()
                .map(row -> new BatchView(
                        row.getId(),
                        row.getSeq(),
                        row.getWatermarkFrom(),
                        row.getWatermarkTo(),
                        row.getEventCount(),
                        row.getBatchSha256(),
                        row.getObjectUri(),
                        row.getManifestUri(),
                        row.getCreatedAt()))
                .toList();
    }

    private DeliveryView viewOf(AuditStreamDeliveryEntity row) {
        return new DeliveryView(
                row.getId(),
                row.getEventId(),
                row.getExportId(),
                row.getFormat(),
                row.getStatus(),
                row.getAttempts(),
                row.getLastError(),
                row.getDeliveredAt(),
                row.getCreatedAt());
    }

    private long countBatches(UUID exportId) {
        return batches.countByExportId(exportId);
    }

    private long sumExported(UUID exportId) {
        return batches.sumEventCountByExportId(exportId);
    }

    // The writes ----------------------------------------------------------------------

    /** Creates an export (AC5): validated end to end, audited with references only. */
    @Transactional
    public ExportView create(UUID actorId, UUID organizationId, CreateCommand command) {
        requireOrganization(organizationId);
        if (command.kind() == null) {
            throw invalid("the kind is required", Map.of("kind", "HTTPS or BUCKET"));
        }
        if (exports.countByOrganizationId(organizationId) >= MAX_EXPORTS_PER_ORG) {
            throw invalid(
                    "the organization holds at most " + MAX_EXPORTS_PER_ORG + " exports",
                    Map.of("organization", "at most " + MAX_EXPORTS_PER_ORG + " exports"));
        }
        Validated validated = validate(command.kind(), null, mergeCreate(command));
        Instant initialWatermark = parseWatermark(command.initialWatermark(), command.kind());
        AuditStreamExportEntity saved = exports.save(new AuditStreamExportEntity(
                organizationId,
                command.kind(),
                validated.format(),
                validated.eventTypes(),
                validated.destinationUrl(),
                validated.bucketUri(),
                validated.signingSecretRef(),
                validated.authHeaderName(),
                validated.authSecretRef(),
                initialWatermark,
                actorId,
                Instant.now(clock)));
        auditConfig(actorId, organizationId, AuditEventType.AUDIT_STREAM_EXPORT_CREATED, saved, "created");
        return ExportView.of(saved, 0, 0);
    }

    /**
     * Edits an export: absent fields keep their standing; the kind
     * never moves (an HTTPS export does not become a bucket export;
     * the ledgers differ). A concurrent edit of the same row refuses
     * with the public 409 (the optimistic {@code version} column).
     */
    @Transactional
    public ExportView update(UUID actorId, UUID organizationId, UUID exportId, UpdateCommand command) {
        requireOrganization(organizationId);
        AuditStreamExportEntity row = requireExport(organizationId, exportId);
        Validated validated = validate(row.getKind(), row, mergeUpdate(row, command));
        boolean enabled = command.enabled() == null ? row.isEnabled() : command.enabled();
        row.reconfigure(
                validated.format(),
                validated.eventTypes(),
                validated.destinationUrl(),
                validated.bucketUri(),
                validated.signingSecretRef(),
                validated.authHeaderName(),
                validated.authSecretRef(),
                enabled,
                Instant.now(clock));
        AuditStreamExportEntity saved;
        try {
            // Flush inside the method so the version check's refusal
            // is translated here, not after the boundary.
            saved = exports.saveAndFlush(row);
        } catch (OptimisticLockingFailureException e) {
            throw new AuditStreamException(
                    AuditStreamException.Code.CONFLICT, "the export changed concurrently; reload and retry");
        }
        auditConfig(actorId, organizationId, AuditEventType.AUDIT_STREAM_EXPORT_UPDATED, saved, "updated");
        return ExportView.of(saved, countBatches(exportId), sumExported(exportId));
    }

    /** Deletes an export; its deliveries and batches stay (the ledgers are history, not state). */
    @Transactional
    public void delete(UUID actorId, UUID organizationId, UUID exportId) {
        requireOrganization(organizationId);
        AuditStreamExportEntity row = requireExport(organizationId, exportId);
        exports.delete(row);
        Map<String, String> detail = new LinkedHashMap<>(configDetail(row));
        detail.put("deleted", "true");
        audit.publish(AuditEvent.builder(AuditEventType.AUDIT_STREAM_EXPORT_DELETED)
                .userId(actorId)
                .orgId(organizationId)
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(detail)
                .build());
    }

    // The test action and the replay ------------------------------------------------

    /** The test action's outcome as the API serves it: the synchronous attempt, honestly reported. */
    public record TestEventView(boolean delivered, AuditStreamKind kind, String error, Instant sentAt) {}

    /**
     * Sends the export's test event: one synthetic audit-shaped
     * envelope through the REAL delivery pipeline (the notification
     * test precedent) -- the same secret resolution, the same format
     * rendering, the same signed POST -- so a passing test is
     * end-to-end proof the collector works and a failing one names
     * why. The synthetic outbox row is born RELAYED (the relay never
     * re-fans a test); it is recorded in the outbox as honest history
     * but never in the delivery ledger (a test is not a trail fact).
     * For a BUCKET export the test answers the secret reference's
     * resolvability -- the batch leg's own precondition.
     */
    @Transactional
    public TestEventView test(UUID actorId, UUID organizationId, UUID exportId) {
        requireOrganization(organizationId);
        AuditStreamExportEntity export = requireExport(organizationId, exportId);
        Instant now = Instant.now(clock);
        if (export.getKind() == AuditStreamKind.BUCKET) {
            boolean resolved = export.getSigningSecretRef() != null
                    && secrets.resolve(export.getSigningSecretRef()).isPresent();
            String error = resolved ? null : "the signing secret reference did not resolve";
            auditTest(actorId, organizationId, export, resolved, error, now);
            return new TestEventView(resolved, export.getKind(), error, now);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", SiemEventFormats.SCHEMA_VERSION);
        payload.put("event_type", "AUDIT_STREAM_TEST");
        payload.put("occurred_at", now.toString());
        payload.put("organization_id", organizationId.toString());
        payload.put("tenant_id", null);
        payload.put("user_id", actorId == null ? null : actorId.toString());
        payload.put("application_id", null);
        payload.put("version_id", null);
        payload.put("artifact_id", null);
        payload.put("result", "SUCCESS");
        payload.put("reason", "synthetic SIEM export test event");
        payload.put("ip_address", null);
        payload.put("correlation_id", null);
        payload.put("detail", Map.of("synthetic", "true", "exportId", exportId.toString()));
        payload.put("before", null);
        payload.put("after", null);
        NotificationOutboxEntity row = outbox.save(new NotificationOutboxEntity(
                DomainEventName.AUDIT_EVENT, organizationId, null, null, null, null, null, null, payload, now, now));
        // Born RELAYED: the synchronous attempt below IS the
        // publication; the relay never re-fans a test out.
        row.markRelayed(now);
        outbox.save(row);
        DomainEvent synthetic = row.toDomainEvent();
        WebhookDeliveryClient.Attempt attempt = dispatcher.dispatchTest(synthetic, export);
        auditTest(actorId, organizationId, export, attempt.delivered(), attempt.error(), now);
        return new TestEventView(attempt.delivered(), export.getKind(), attempt.error(), now);
    }

    /**
     * The replay action (AC3): the export's terminally failed
     * deliveries return to the retry schedule with a fresh attempt
     * budget. Audited with the count.
     */
    @Transactional
    public int replay(UUID actorId, UUID organizationId, UUID exportId) {
        requireOrganization(organizationId);
        AuditStreamExportEntity export = requireExport(organizationId, exportId);
        Instant now = Instant.now(clock);
        int replayed = dispatcher.replayFailed(export.getId());
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("exportId", export.getId().toString());
        detail.put("kind", export.getKind().name());
        detail.put("replayed", Integer.toString(replayed));
        audit.publish(AuditEvent.builder(AuditEventType.AUDIT_STREAM_REPLAYED)
                .userId(actorId)
                .orgId(organizationId)
                .occurredAt(now)
                .success(true)
                .detail(detail)
                .build());
        return replayed;
    }

    // Validation -----------------------------------------------------------------------

    /** The export facts as the validator sees them. */
    private record Facts(
            AuditStreamFormat format,
            List<String> eventTypes,
            String destinationUrl,
            String bucketUri,
            String signingSecretRef,
            String authHeaderName,
            String authSecretRef) {}

    private Facts mergeCreate(CreateCommand command) {
        return new Facts(
                command.format(),
                command.eventTypes() == null ? List.of() : command.eventTypes(),
                command.destinationUrl(),
                command.bucketUri(),
                command.signingSecretRef(),
                command.authHeaderName(),
                command.authSecretRef());
    }

    private Facts mergeUpdate(AuditStreamExportEntity row, UpdateCommand command) {
        return new Facts(
                command.format() == null ? row.getFormat() : command.format(),
                command.eventTypes() == null ? row.getEventTypes() : command.eventTypes(),
                command.destinationUrl() == null ? row.getDestinationUrl() : command.destinationUrl(),
                command.bucketUri() == null ? row.getBucketUri() : command.bucketUri(),
                command.signingSecretRef() == null ? row.getSigningSecretRef() : command.signingSecretRef(),
                command.authHeaderName() == null ? row.getAuthHeaderName() : command.authHeaderName(),
                command.authSecretRef() == null ? row.getAuthSecretRef() : command.authSecretRef());
    }

    /** One validated export's facts. */
    private record Validated(
            AuditStreamFormat format,
            List<String> eventTypes,
            String destinationUrl,
            String bucketUri,
            String signingSecretRef,
            String authHeaderName,
            String authSecretRef) {}

    /**
     * One validation pass: the kind's shape (format and URL for
     * HTTPS; bucket URI for BUCKET), the event-type filter against
     * the frozen {@code AuditEventType} vocabulary (deduplicated,
     * order-stable; an empty filter streams everything), the secret
     * references' format, and the auth-header pair's shape. One 422
     * carries every offense.
     */
    private Validated validate(AuditStreamKind kind, AuditStreamExportEntity standing, Facts facts) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<String> eventTypes = validateEventTypes(facts.eventTypes(), errors);
        String signingSecretRef = validateSecretRef("signingSecretRef", facts.signingSecretRef(), errors);
        String authHeaderName = null;
        String authSecretRef = null;
        boolean authShaped = (facts.authHeaderName() == null) == (facts.authSecretRef() == null);
        if (!authShaped) {
            errors.put("authHeaderName", "the auth header and its secret reference are supplied together");
            errors.put("authSecretRef", "the auth header and its secret reference are supplied together");
        } else if (facts.authHeaderName() != null) {
            if (facts.authHeaderName().isBlank()
                    || facts.authHeaderName().length() > AUTH_HEADER_MAX
                    || !facts.authHeaderName().matches("[A-Za-z0-9-]+")) {
                errors.put("authHeaderName", "1.." + AUTH_HEADER_MAX + " header-name characters (A-Z, a-z, 0-9, -)");
            } else {
                authHeaderName = facts.authHeaderName();
            }
            String resolvedAuthRef = validateSecretRef("authSecretRef", facts.authSecretRef(), errors);
            if (resolvedAuthRef != null) {
                authSecretRef = resolvedAuthRef;
            }
        }
        if (!errors.isEmpty()) {
            throw invalid("the export facts do not validate", errors);
        }
        return switch (kind) {
            case HTTPS -> {
                if (facts.format() == null) {
                    throw invalid(
                            "an HTTPS export carries a format", Map.of("format", "SHIPPERDOG_JSON or SPLUNK_HEC"));
                }
                if (facts.bucketUri() != null) {
                    // An edit supplying the other kind's destination
                    // refuses rather than silently ignoring it: the
                    // kind never moves, and a request that thinks it
                    // does is a client bug worth naming.
                    throw invalid(
                            "an HTTPS export carries no bucket URI", Map.of("bucketUri", "absent for HTTPS exports"));
                }
                String url = facts.destinationUrl();
                boolean urlBlank = url == null || url.isBlank();
                if (urlBlank || !url.trim().matches("^https?://.+")) {
                    throw invalid(
                            "an HTTPS export carries a well-formed collector URL",
                            Map.of("destinationUrl", urlBlank ? "a http(s) URL is required" : "must be a http(s) URL"));
                }
                // The same SSRF policy the webhook channel rides: a
                // publicly routable http(s) host, re-vetted on every
                // edit of the standing URL.
                webhookTargets.requireRoutable(url.trim());
                yield new Validated(
                        facts.format(), eventTypes, url.trim(), null, signingSecretRef, authHeaderName, authSecretRef);
            }
            case BUCKET -> {
                if (facts.destinationUrl() != null) {
                    throw invalid(
                            "a BUCKET export carries no collector URL",
                            Map.of("destinationUrl", "absent for BUCKET exports"));
                }
                String uri = facts.bucketUri();
                boolean uriBlank = uri == null || uri.isBlank();
                if (uriBlank || !uri.trim().matches("^gs://[^/\\s]+.*$")) {
                    throw invalid(
                            "a BUCKET export carries a gs:// bucket URI (the s3:// connector is the documented follow-up)",
                            Map.of(
                                    "bucketUri",
                                    uriBlank ? "a gs://bucket/prefix URI is required" : "must be a gs://bucket URI"));
                }
                if (facts.format() != null) {
                    throw invalid("a BUCKET export carries no format", Map.of("format", "absent for BUCKET exports"));
                }
                yield new Validated(null, eventTypes, null, uri.trim(), signingSecretRef, null, null);
            }
        };
    }

    private List<String> validateEventTypes(List<String> requested, Map<String, String> errors) {
        if (requested == null || requested.isEmpty()) {
            // An empty filter is the SIEM default: everything streams.
            return List.of();
        }
        Set<String> known = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String name : requested) {
            try {
                known.add(AuditEventType.valueOf(name).name());
            } catch (IllegalArgumentException e) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            errors.put("eventTypes", "unknown audit event types: " + unknown);
        }
        return List.copyOf(known);
    }

    private String validateSecretRef(String member, String reference, Map<String, String> errors) {
        if (reference == null || reference.isBlank()) {
            errors.put(member, "a secret-manager reference is required");
            return null;
        }
        if (reference.length() > SECRET_REF_MAX) {
            errors.put(member, "at most " + SECRET_REF_MAX + " characters");
            return null;
        }
        return reference.trim();
    }

    private Instant parseWatermark(String initialWatermark, AuditStreamKind kind) {
        if (initialWatermark == null || initialWatermark.isBlank()) {
            // Forward-only by default: the stream starts at creation;
            // historical coverage is the bucket leg's explicit backfill.
            return kind == AuditStreamKind.BUCKET ? Instant.now(clock) : null;
        }
        try {
            return Instant.parse(initialWatermark.trim());
        } catch (DateTimeParseException e) {
            throw invalid(
                    "the initial watermark is an ISO-8601 instant",
                    Map.of("initialWatermark", "an ISO-8601 instant, or absent to start at now"));
        }
    }

    // Guards -----------------------------------------------------------------------------

    private void requireOrganization(UUID organizationId) {
        if (!organizations.organizationExists(organizationId)) {
            throw new AuditStreamException(AuditStreamException.Code.ORGANIZATION_NOT_FOUND, "organization not found");
        }
    }

    private AuditStreamExportEntity requireExport(UUID organizationId, UUID exportId) {
        return exports.findByIdAndOrganizationId(exportId, organizationId)
                .orElseThrow(() -> new AuditStreamException(AuditStreamException.Code.NOT_FOUND, "export not found"));
    }

    private void auditConfig(
            UUID actorId, UUID organizationId, AuditEventType type, AuditStreamExportEntity row, String action) {
        Map<String, String> detail = new LinkedHashMap<>(configDetail(row));
        detail.put("action", action);
        audit.publish(AuditEvent.builder(type)
                .userId(actorId)
                .orgId(organizationId)
                .occurredAt(Instant.now(clock))
                .success(true)
                .detail(detail)
                .build());
    }

    /** The audit line's facts: configuration as references -- destinations and filters, never resolved material. */
    private Map<String, String> configDetail(AuditStreamExportEntity row) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("exportId", row.getId().toString());
        detail.put("kind", row.getKind().name());
        if (row.getFormat() != null) {
            detail.put("format", row.getFormat().name());
        }
        detail.put("eventTypes", row.getEventTypes().isEmpty() ? "ALL" : String.join(",", row.getEventTypes()));
        if (row.getDestinationUrl() != null) {
            detail.put("destinationUrl", row.getDestinationUrl());
        }
        if (row.getBucketUri() != null) {
            detail.put("bucketUri", row.getBucketUri());
        }
        detail.put("signingSecretRef", row.getSigningSecretRef());
        if (row.getAuthHeaderName() != null) {
            detail.put("authHeaderName", row.getAuthHeaderName());
        }
        detail.put("enabled", Boolean.toString(row.isEnabled()));
        return detail;
    }

    private void auditTest(
            UUID actorId,
            UUID organizationId,
            AuditStreamExportEntity export,
            boolean delivered,
            String error,
            Instant now) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("exportId", export.getId().toString());
        detail.put("kind", export.getKind().name());
        detail.put("delivered", Boolean.toString(delivered));
        detail.put("action", "tested");
        if (error != null) {
            detail.put("error", error);
        }
        audit.publish(AuditEvent.builder(AuditEventType.AUDIT_STREAM_EXPORT_TESTED)
                .userId(actorId)
                .orgId(organizationId)
                .occurredAt(now)
                .success(true)
                .detail(detail)
                .build());
    }

    private static AuditStreamException invalid(String message, Map<String, String> errors) {
        return new AuditStreamException(AuditStreamException.Code.EXPORT_INVALID, message, errors);
    }

    /** The log's filters, composed: the organization always, the export and status when given. */
    private static Specification<AuditStreamDeliveryEntity> orgScoped(
            UUID organizationId, UUID exportId, AuditStreamDeliveryEntity.Status status) {
        Specification<AuditStreamDeliveryEntity> spec =
                (root, query, criteria) -> criteria.equal(root.get("organizationId"), organizationId);
        if (exportId != null) {
            spec = spec.and((root, query, criteria) -> criteria.equal(root.get("exportId"), exportId));
        }
        if (status != null) {
            spec = spec.and((root, query, criteria) -> criteria.equal(root.get("status"), status));
        }
        return spec;
    }
}
