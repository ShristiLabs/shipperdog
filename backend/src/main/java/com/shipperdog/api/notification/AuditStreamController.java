package com.shipperdog.api.notification;

import com.shipperdog.api.config.Idempotent;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import com.shipperdog.api.notification.internal.entity.AuditStreamDeliveryEntity;
import com.shipperdog.api.notification.internal.service.AuditStreamExportService;
import com.shipperdog.api.notification.internal.service.AuditStreamExportService.CreateCommand;
import com.shipperdog.api.notification.internal.service.AuditStreamExportService.ExportView;
import com.shipperdog.api.notification.internal.service.AuditStreamExportService.UpdateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SIEM audit-stream API (issue #71, S14-05, PRD 27/38, FR-024):
 * the org-scoped export CRUD -- organization administrators alone
 * ({@code org:audit-stream:read/manage}, the story's AC5: the
 * surface ships security-relevant material off-platform under the
 * organization's own secret references) -- the delivery log with its
 * visible dead-letter, the dead-letter replay, the bucket batch
 * history, and the test action.
 *
 * <p>The exported event schema (the versioned envelope of
 * docs/siem.md), the formats, and the HMAC verification recipe are
 * documented there; the secret references are served as references
 * (they are the configuration) while what a reference resolves to
 * never leaves the delivery path.
 */
@RestController
public class AuditStreamController {

    private final AuditStreamExportService exports;

    AuditStreamController(AuditStreamExportService exports) {
        this.exports = exports;
    }

    // Exports: list, detail, create, edit, delete ---------------------------------

    /** The organization's exports, newest first. */
    @GetMapping("/api/v1/organizations/{organizationId}/audit-stream-exports")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:audit-stream:read')")
    PageResponse<ExportView> list(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        List<ExportView> page =
                exports.list(requireUserId(principal), organizationId, (int) query.offset(), query.limit());
        return PageResponse.of(page, page.size() == query.limit(), query.offset() + query.limit());
    }

    /** One export; another organization's conceals exactly like an unknown id. */
    @GetMapping("/api/v1/organizations/{organizationId}/audit-stream-exports/{exportId}")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:audit-stream:read')")
    ExportView get(
            @AuthenticationPrincipal Object principal, @PathVariable UUID organizationId, @PathVariable UUID exportId) {
        return exports.get(requireUserId(principal), organizationId, exportId);
    }

    /**
     * Creates an export (AC5): the kind, the kind-appropriate
     * destination and format, the event-type filter (empty streams
     * everything), and the secret references. Audited (references
     * only). Honors {@code Idempotency-Key}.
     */
    @PostMapping("/api/v1/organizations/{organizationId}/audit-stream-exports")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:audit-stream:manage')")
    ExportView create(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @RequestBody CreateRequest request) {
        return exports.create(
                requireUserId(principal),
                organizationId,
                new CreateCommand(
                        parseKind(request.kind()),
                        parseFormat(request.format()),
                        request.eventTypes(),
                        request.destinationUrl(),
                        request.bucketUri(),
                        request.signingSecretRef(),
                        request.authHeaderName(),
                        request.authSecretRef(),
                        request.initialWatermark()));
    }

    /**
     * Edits an export: absent fields keep their standing; the kind
     * never moves; a supplied secret reference rotates it. Audited.
     * Honors {@code Idempotency-Key}.
     */
    @PatchMapping("/api/v1/organizations/{organizationId}/audit-stream-exports/{exportId}")
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:audit-stream:manage')")
    ExportView update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID exportId,
            @RequestBody UpdateRequest request) {
        return exports.update(
                requireUserId(principal),
                organizationId,
                exportId,
                new UpdateCommand(
                        parseFormat(request.format()),
                        request.eventTypes(),
                        request.destinationUrl(),
                        request.bucketUri(),
                        request.signingSecretRef(),
                        request.authHeaderName(),
                        request.authSecretRef(),
                        request.enabled()));
    }

    /** Deletes an export; its deliveries and batches stay (the ledgers are history). Audited. */
    @DeleteMapping("/api/v1/organizations/{organizationId}/audit-stream-exports/{exportId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:audit-stream:manage')")
    void delete(
            @AuthenticationPrincipal Object principal, @PathVariable UUID organizationId, @PathVariable UUID exportId) {
        exports.delete(requireUserId(principal), organizationId, exportId);
    }

    // The delivery log, the replay, the batches, the test ----------------------------

    /**
     * The delivery log (AC3): the organization's HTTPS deliveries,
     * newest first, optionally narrowed to one export and one status
     * ({@code SCHEDULED}, {@code DELIVERED}, {@code FAILED} -- the
     * visible dead-letter).
     */
    @GetMapping("/api/v1/organizations/{organizationId}/audit-stream-exports/{exportId}/deliveries")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:audit-stream:read')")
    PageResponse<AuditStreamExportService.DeliveryView> deliveries(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID exportId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        AuditStreamDeliveryEntity.Status parsed = parseStatus(status);
        List<AuditStreamExportService.DeliveryView> page = exports.deliveryLog(
                requireUserId(principal), organizationId, exportId, parsed, (int) query.offset(), query.limit());
        return PageResponse.of(page, page.size() == query.limit(), query.offset() + query.limit());
    }

    /**
     * Replays the export's dead-letter (AC3): every terminally failed
     * delivery returns to the retry schedule with a fresh attempt
     * budget. Audited with the replayed count. Honors {@code
     * Idempotency-Key}.
     */
    @PostMapping("/api/v1/organizations/{organizationId}/audit-stream-exports/{exportId}/replay")
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:audit-stream:manage')")
    ReplayView replay(
            @AuthenticationPrincipal Object principal, @PathVariable UUID organizationId, @PathVariable UUID exportId) {
        int replayed = exports.replay(requireUserId(principal), organizationId, exportId);
        return new ReplayView(exportId, replayed);
    }

    /** The bucket export's batch history (AC2): the completeness ledger, newest sequence first. */
    @GetMapping("/api/v1/organizations/{organizationId}/audit-stream-exports/{exportId}/batches")
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:audit-stream:read')")
    PageResponse<AuditStreamExportService.BatchView> batches(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID organizationId,
            @PathVariable UUID exportId,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        ListQuery query = ListQuery.of(limit, cursor);
        List<AuditStreamExportService.BatchView> page = exports.batches(
                requireUserId(principal), organizationId, exportId, (int) query.offset(), query.limit());
        return PageResponse.of(page, page.size() == query.limit(), query.offset() + query.limit());
    }

    /**
     * Sends the export's test event: one synthetic envelope through
     * the real delivery pipeline, answered synchronously. Audited.
     * Honors {@code Idempotency-Key}.
     */
    @PostMapping("/api/v1/organizations/{organizationId}/audit-stream-exports/{exportId}/test")
    @Idempotent
    @PreAuthorize("hasPermission(#organizationId, 'organization', 'org:audit-stream:manage')")
    AuditStreamExportService.TestEventView test(
            @AuthenticationPrincipal Object principal, @PathVariable UUID organizationId, @PathVariable UUID exportId) {
        return exports.test(requireUserId(principal), organizationId, exportId);
    }

    // Bodies and helpers ------------------------------------------------------------

    /** The replay action's outcome. */
    @Schema(
            description =
                    "The dead-letter replay's outcome: the export and how many deliveries returned to the retry schedule.")
    public record ReplayView(UUID exportId, int replayed) {}

    /**
     * The export creation body: the kind, the kind-appropriate
     * destination and format, the event-type filter (empty streams
     * everything), the secret references (never material), and the
     * bucket leg's optional backfill watermark.
     */
    @Schema(
            description = "The audit-stream export creation body: kind, destination, format, event filter, and "
                    + "secret-manager references.")
    public record CreateRequest(
            String kind,
            String format,
            List<String> eventTypes,
            String destinationUrl,
            String bucketUri,
            String signingSecretRef,
            String authHeaderName,
            String authSecretRef,
            String initialWatermark) {}

    /** The export edit body: the mutable facts only; absent fields keep their standing. */
    @Schema(
            description = "The audit-stream export edit body: format, filter, destination, the secret pair "
                    + "(supplying a reference rotates it), and the enabled switch; the kind never moves.")
    public record UpdateRequest(
            String format,
            List<String> eventTypes,
            String destinationUrl,
            String bucketUri,
            String signingSecretRef,
            String authHeaderName,
            String authSecretRef,
            Boolean enabled) {}

    private static AuditStreamKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        try {
            return AuditStreamKind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AuditStreamException(
                    AuditStreamException.Code.EXPORT_INVALID, "unknown kind", Map.of("kind", "HTTPS or BUCKET"));
        }
    }

    private static AuditStreamFormat parseFormat(String format) {
        if (format == null || format.isBlank()) {
            return null;
        }
        try {
            return AuditStreamFormat.valueOf(format.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AuditStreamException(
                    AuditStreamException.Code.EXPORT_INVALID,
                    "unknown format",
                    Map.of("format", "SHIPPERDOG_JSON or SPLUNK_HEC"));
        }
    }

    private static AuditStreamDeliveryEntity.Status parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AuditStreamDeliveryEntity.Status.valueOf(status.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AuditStreamException(
                    AuditStreamException.Code.INVALID_REQUEST,
                    "unknown delivery status",
                    Map.of("status", "SCHEDULED, DELIVERED, or FAILED"));
        }
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new AuditStreamException(AuditStreamException.Code.INVALID_REQUEST, "no authenticated principal");
    }
}
