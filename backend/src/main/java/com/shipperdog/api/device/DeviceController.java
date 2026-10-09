package com.shipperdog.api.device;

import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.device.internal.service.DeviceService;
import com.shipperdog.api.device.internal.service.DeviceService.CsvExport;
import com.shipperdog.api.device.internal.service.DeviceService.DeviceView;
import com.shipperdog.api.device.internal.service.DeviceService.RegisterCommand;
import com.shipperdog.api.device.internal.service.DeviceService.UpdateCommand;
import com.shipperdog.api.identity.AuthenticatedPrincipal;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The device API (issue #48, S11-03, FR-028, PRD 8.12): registration,
 * the console's listing and management actions, and the per-tenant
 * provisioning CSV export — the M2 manual-entry answer to store-side
 * provisioning (the issue's technical note: a UDID cannot be captured
 * from a web page; the mobileconfig profile is a documented
 * enhancement).
 *
 * <p>Every route rides the tenant path ({@code
 * /api/v1/tenants/{id}/devices}), where the tenant module's route
 * interceptor binds the tenant context the isolation seam enforces for
 * every device read and write — the membership surface's own shape.
 * Permissions: {@code tenant:devices:read} for the reads,
 * {@code tenant:devices:manage} for registration and the management
 * actions, and {@code tenant:devices:export} for the CSV download
 * (AC3's download-restricted-to-admins — a distinct authority so the
 * identifier's mass disclosure can be governed apart from routine
 * management), all held by TENANT_ADMIN and, through the
 * owning-organization overlay, ORG_ADMIN; the identifiers are
 * semi-sensitive, so every surface here is admin-only by construction
 * (the issue's privacy note). Resource-scoped denials render as the
 * concealed 404 a genuine miss produces (PRD 12). The listing pages
 * with the platform cursor convention.
 */
@RestController
public class DeviceController {

    private final DeviceService devices;

    DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    /**
     * Registers one device (AC1): the tester it belongs to, the
     * platform, the optional model and OS version, and the identifier
     * (UDID/serial) — unique within the tenant; a duplicate answers
     * 409 {@code DEVICE_IDENTIFIER_TAKEN}. The row lands ACTIVE.
     */
    @PostMapping("/api/v1/tenants/{id}/devices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:devices:manage')")
    DeviceView register(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestBody DeviceRegistrationRequest request) {
        return devices.register(
                requireUserId(principal),
                id,
                new RegisterCommand(
                        request.testerId(),
                        request.platform(),
                        request.model(),
                        request.osVersion(),
                        request.identifier()));
    }

    /**
     * The tenant's devices (AC2): conjunctive filters by tester,
     * platform, and status, newest first, standard limit/cursor
     * pagination, each row with its owner's contact record.
     */
    @GetMapping("/api/v1/tenants/{id}/devices")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:devices:read')")
    PageResponse<DeviceView> list(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestParam(required = false) UUID testerId,
            @RequestParam(required = false) String platform,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor) {
        return devices.list(id, testerId, platform, status, ListQuery.of(limit, cursor));
    }

    /**
     * One device (AC2): the management detail under the tenant seam —
     * an unknown or foreign id is the genuine miss.
     */
    @GetMapping("/api/v1/tenants/{id}/devices/{deviceId}")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:devices:read')")
    DeviceView get(@AuthenticationPrincipal Object principal, @PathVariable UUID id, @PathVariable UUID deviceId) {
        return devices.get(id, deviceId);
    }

    /**
     * Updates the descriptive fields (AC2): the model and OS version —
     * null clears; the identifier and platform are the device's
     * identity and never move.
     */
    @PutMapping("/api/v1/tenants/{id}/devices/{deviceId}")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:devices:manage')")
    DeviceView update(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @PathVariable UUID deviceId,
            @RequestBody DeviceUpdateRequest request) {
        return devices.update(
                requireUserId(principal), id, deviceId, new UpdateCommand(request.model(), request.osVersion()));
    }

    /**
     * The reversible status switch (AC2/AC5): DEACTIVATED parks the
     * device — its feedback links and the tenant's history stay, and
     * new feedback no longer attaches it; ACTIVE releases it.
     */
    @PutMapping("/api/v1/tenants/{id}/devices/{deviceId}/status")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:devices:manage')")
    DeviceView changeStatus(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @PathVariable UUID deviceId,
            @RequestBody DeviceStatusChangeRequest request) {
        return devices.changeStatus(requireUserId(principal), id, deviceId, request.status());
    }

    /**
     * The provisioning export (AC3): the tenant's device list as CSV —
     * identifier, platform, model, OS version, owner email — the
     * store-side provisioning columns. The default answer is the
     * ACTIVE fleet; {@code status=DEACTIVATED} exports the parked set
     * and {@code status=ALL} the full registry. The download is its
     * own authority ({@code tenant:devices:export}) and the disclosure
     * is audited with its row count.
     */
    @GetMapping("/api/v1/tenants/{id}/devices/export")
    @PreAuthorize("hasPermission(#id, 'tenant', 'tenant:devices:export')")
    ResponseEntity<byte[]> export(
            @AuthenticationPrincipal Object principal,
            @PathVariable UUID id,
            @RequestParam(required = false) String status) {
        CsvExport export = devices.exportCsv(requireUserId(principal), id, status);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("devices-" + id + ".csv")
                .build());
        return ResponseEntity.ok().headers(headers).body(export.csv());
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new DeviceException(DeviceException.Code.INVALID_REQUEST, "no authenticated principal");
    }

    /**
     * The registration body (AC1): the tester-typed fields only. The
     * simple name is device-distinct on purpose: springdoc keys
     * request schemas by simple name, and a bare {@code
     * RegisterRequest} would collide with the auth module's body and
     * evict its fields from the published contract (the OpenAPI
     * re-baseline caught exactly that).
     */
    public record DeviceRegistrationRequest(
            UUID testerId, String platform, String model, String osVersion, String identifier) {}

    /** The descriptive update body: both fields optional, null clears (device-distinct schema name). */
    public record DeviceUpdateRequest(String model, String osVersion) {}

    /** The status switch body: ACTIVE or DEACTIVATED (device-distinct schema name). */
    public record DeviceStatusChangeRequest(String status) {}
}
