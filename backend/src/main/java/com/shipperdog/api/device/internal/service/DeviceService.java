package com.shipperdog.api.device.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.ListQuery;
import com.shipperdog.api.config.PageResponse;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.device.DeviceAccess;
import com.shipperdog.api.device.DeviceException;
import com.shipperdog.api.device.internal.entity.DeviceEntity;
import com.shipperdog.api.device.internal.entity.DeviceEntity.Platform;
import com.shipperdog.api.device.internal.entity.DeviceEntity.Status;
import com.shipperdog.api.device.internal.repository.DeviceCatalog;
import com.shipperdog.api.device.internal.repository.DeviceRepository;
import com.shipperdog.api.tenant.TenantAccess;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import com.shipperdog.api.tester.TesterAccess;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The device surface's service (issue #48, S11-03, FR-028, PRD 8.12):
 * the registration act, the console's listing and management actions,
 * the per-tenant provisioning CSV export, and the feedback link facts
 * — all over the tenant seam (ADR-0002): the route interceptor binds
 * the request's tenant context for every {@code /tenants/{id}/devices}
 * route, and the scoped repository filters every read and write to it,
 * so a foreign tenant's device is indistinguishable from a missing
 * one.
 *
 * <p>Registration is the tenant administrator's manual entry (the
 * issue's technical note: iOS UDIDs cannot be captured from a web
 * page; the manual-entry + CSV path is the M2 answer, the
 * mobileconfig profile a documented enhancement). The guard stack:
 * the tester must exist in the tenant's owning organization, hold a
 * RETAINED membership row in the tenant (any status — the
 * two-switches rule governs effectiveness at decision time, not
 * registration), the tenant must accept new affiliations, and the
 * identifier must be new to the tenant (AC1's duplicate guard, V1's
 * {@code uq_devices_tenant_identifier} surfaced as 409).
 *
 * <p>Rows are never deleted (the INV-007 history posture, AC5):
 * deactivating a tester keeps every device record (no cascade exists
 * and none is added), and deactivating a device parks it reversibly —
 * its feedback links and the tenant's history stay. The identifier
 * never appears in audit detail (semi-sensitive, the issue's privacy
 * note); the identifier's mass disclosure the export performs is
 * itself the audited act.
 */
@Service
public class DeviceService implements DeviceAccess {

    /** Newest first: creation descending, id descending as the deterministic tiebreak. */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    /** The identifier's length bound (V1's column width) — a UDID or serial, never a sentence. */
    private static final int MAX_IDENTIFIER_LENGTH = 255;

    /** The model's length bound (V1's column width). */
    private static final int MAX_MODEL_LENGTH = 255;

    /** The OS version's length bound (V1's column width). */
    private static final int MAX_OS_VERSION_LENGTH = 64;

    /** The platform vocabulary the validation errors quote. */
    private static final String PLATFORM_VOCABULARY = "IOS, ANDROID";

    /** The status vocabulary the validation errors quote. */
    private static final String STATUS_VOCABULARY = "ACTIVE, DEACTIVATED";

    private final DeviceRepository devices;

    private final DeviceCatalog catalog;

    private final TenantAccess tenants;

    private final TenantTesterMembershipAccess memberships;

    private final TesterAccess testers;

    private final AuditEventPublisher audit;

    private final Clock clock;

    private final TraceAttributes trace;

    DeviceService(
            DeviceRepository devices,
            DeviceCatalog catalog,
            TenantAccess tenants,
            TenantTesterMembershipAccess memberships,
            TesterAccess testers,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace) {
        this.devices = devices;
        this.catalog = catalog;
        this.tenants = tenants;
        this.memberships = memberships;
        this.testers = testers;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // Registration and management (AC1, AC2, AC5)
    // -------------------------------------------------------------------------

    /**
     * Registers one device (AC1): the guard stack refuses before any
     * write — the platform vocabulary, the bounded free text, the
     * tester's existence and organization, the retained membership
     * row, the tenant's affiliation posture, and the identifier's
     * freshness — then the row lands ACTIVE with its audit. A lost
     * duplicate race (two registers of one identifier) answers the
     * same 409 the probe does.
     */
    @Transactional
    public DeviceView register(UUID actorId, UUID tenantId, RegisterCommand command) {
        Map<String, String> errors = new LinkedHashMap<>();
        Platform platform = validatePlatform(command.platform(), errors);
        String identifier = validateRequiredText("identifier", command.identifier(), MAX_IDENTIFIER_LENGTH, errors);
        String model = validateOptionalText("model", command.model(), MAX_MODEL_LENGTH, errors);
        String osVersion = validateOptionalText("os_version", command.osVersion(), MAX_OS_VERSION_LENGTH, errors);
        if (command.testerId() == null) {
            errors.put("tester_id", "tester_id is required");
        }
        if (!errors.isEmpty()) {
            throw new DeviceException(
                    DeviceException.Code.INVALID_REQUEST, "the registration has invalid fields", errors);
        }

        // The owner: a tester of the tenant's own organization (a
        // foreign tester is the same miss as a missing one), linked to
        // the tenant by a retained membership row (any status —
        // registration is not effectiveness, the two-switches rule).
        UUID tenantOrganization = tenants.owningOrganizationId(tenantId).orElseThrow(DeviceService::tenantMiss);
        if (!testers.owningOrganizationOf(command.testerId())
                .map(testerOrganization -> testerOrganization.equals(tenantOrganization))
                .orElse(false)) {
            throw new DeviceException(
                    DeviceException.Code.TESTER_NOT_FOUND, "the tester did not resolve for this tenant");
        }
        if (!memberships.membershipExists(tenantId, command.testerId())) {
            throw new DeviceException(
                    DeviceException.Code.DEVICE_REQUEST_REFUSED,
                    "the tester is not linked to the tenant",
                    Map.of("tester_id", "no retained membership row in this tenant"));
        }
        if (!tenants.acceptsNewAffiliations(tenantId)) {
            throw new DeviceException(
                    DeviceException.Code.DEVICE_REQUEST_REFUSED,
                    "the tenant does not accept new registrations",
                    Map.of("tenant", "the tenant is deactivated"));
        }
        if (devices.existsScoped(identifierIs(identifier))) {
            throw duplicate();
        }

        DeviceEntity saved;
        try {
            saved = devices.saveScoped(
                    new DeviceEntity(command.testerId(), tenantId, platform, model, osVersion, identifier));
        } catch (DataIntegrityViolationException e) {
            // The lost half of a duplicate race: the constraint is the
            // same guard the probe applied.
            throw duplicate();
        }

        trace.tenant(tenantId);
        audit.publish(AuditEvent.builder(AuditEventType.DEVICE_REGISTERED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(tenantOrganization)
                .tenantId(tenantId)
                .success(true)
                .detail(deviceDetail(saved, null, null))
                .build());
        return viewOf(saved, ownerRecordOf(saved.getTesterId()));
    }

    /**
     * The tenant's devices (AC2): conjunctive filters by tester,
     * platform, and status over the tenant-scoped seam, newest first,
     * standard limit/cursor pagination, each row joined with its
     * owner's contact record.
     */
    @Transactional(readOnly = true)
    public PageResponse<DeviceView> list(
            UUID tenantId, UUID testerId, String platform, String status, ListQuery query) {
        Platform platformFilter = platform == null || platform.isBlank() ? null : parsePlatform(platform);
        Status statusFilter = status == null || status.isBlank() ? null : parseStatus(status);
        Specification<DeviceEntity> filter = (root, criteriaQuery, builder) -> builder.conjunction();
        if (testerId != null) {
            filter = filter.and(testerIs(testerId));
        }
        if (platformFilter != null) {
            filter = filter.and(platformIs(platformFilter));
        }
        if (statusFilter != null) {
            filter = filter.and(statusIs(statusFilter));
        }
        Page<DeviceEntity> page =
                devices.findAllScoped(filter, new CursorPageable(query.offset(), query.limit(), NEWEST_FIRST));
        Map<UUID, TesterAccess.TesterRecord> owners = ownerRecordsOf(page.getContent());
        return PageResponse.of(
                page.getContent().stream()
                        .map(row -> viewOf(row, owners.get(row.getTesterId())))
                        .toList(),
                page.hasNext(),
                query.offset() + query.limit());
    }

    /** One device (AC2): the addressed row under the tenant seam — a foreign or unknown id is the genuine miss. */
    @Transactional(readOnly = true)
    public DeviceView get(UUID tenantId, UUID deviceId) {
        DeviceEntity row = devices.findScoped(deviceId).orElseThrow(DeviceService::deviceMiss);
        return viewOf(row, ownerRecordOf(row.getTesterId()));
    }

    /**
     * Updates the descriptive fields (AC2): the model and OS version
     * alone — the identifier and platform are the device's identity
     * and never move. A null clears (an unknown model filled in later
     * may be withdrawn); an unchanged update is still the honest 200,
     * just no audit event (nothing happened).
     */
    @Transactional
    public DeviceView update(UUID actorId, UUID tenantId, UUID deviceId, UpdateCommand command) {
        Map<String, String> errors = new LinkedHashMap<>();
        String model = validateOptionalText("model", command.model(), MAX_MODEL_LENGTH, errors);
        String osVersion = validateOptionalText("os_version", command.osVersion(), MAX_OS_VERSION_LENGTH, errors);
        if (!errors.isEmpty()) {
            throw new DeviceException(DeviceException.Code.INVALID_REQUEST, "the update has invalid fields", errors);
        }
        DeviceEntity row = devices.findScoped(deviceId).orElseThrow(DeviceService::deviceMiss);
        String beforeModel = row.getModel();
        String beforeOsVersion = row.getOsVersion();
        boolean changed = row.applyDescription(model, osVersion);
        if (changed) {
            devices.saveScoped(row);
            Map<String, String> detail = deviceDetail(row, null, null);
            List<String> fields = new ArrayList<>();
            if (!Objects.equals(beforeModel, model)) {
                fields.add("model");
            }
            if (!Objects.equals(beforeOsVersion, osVersion)) {
                fields.add("os_version");
            }
            detail.put("fields", String.join(",", fields));
            audit.publish(AuditEvent.builder(AuditEventType.DEVICE_UPDATED)
                    .occurredAt(Instant.now(clock))
                    .userId(actorId)
                    .orgId(organizationOf(tenantId))
                    .tenantId(tenantId)
                    .success(true)
                    .detail(detail)
                    .build());
        }
        return viewOf(row, ownerRecordOf(row.getTesterId()));
    }

    /**
     * The reversible status switch (AC2/AC5): ACTIVE to DEACTIVATED
     * parks the device without touching its feedback links or the
     * tenant's history; DEACTIVATED to ACTIVE releases it. The no-op
     * crossing refuses — a parking act that changes nothing is a
     * mistaken request, not an event (the tester status surface's
     * discipline).
     */
    @Transactional
    public DeviceView changeStatus(UUID actorId, UUID tenantId, UUID deviceId, String to) {
        Status target = to == null || to.isBlank() ? null : parseStatus(to);
        DeviceEntity row = devices.findScoped(deviceId).orElseThrow(DeviceService::deviceMiss);
        if (target == null || target == row.getStatus()) {
            throw new DeviceException(
                    DeviceException.Code.DEVICE_REQUEST_REFUSED,
                    "the device is already " + row.getStatus(),
                    Map.of("status", "the device is already " + row.getStatus()));
        }
        Status from = row.applyStatus(target);
        devices.saveScoped(row);
        audit.publish(AuditEvent.builder(AuditEventType.DEVICE_STATUS_CHANGED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationOf(tenantId))
                .tenantId(tenantId)
                .success(true)
                .detail(deviceDetail(row, from, target))
                .build());
        return viewOf(row, ownerRecordOf(row.getTesterId()));
    }

    /**
     * The provisioning export (AC3): the tenant's device list as CSV —
     * identifier, platform, model, OS version, and the owner's email
     * (the store-side provisioning columns, Apple's developer portal
     * the named consumer). The default answer is the ACTIVE fleet (the
     * devices a provisioning list registers); {@code status=DEACTIVATED}
     * exports the parked set and {@code status=ALL} the full registry.
     * The identifier's mass disclosure is the audited act itself.
     */
    @Transactional(readOnly = true)
    public CsvExport exportCsv(UUID actorId, UUID tenantId, String status) {
        Status statusFilter;
        if (status == null || status.isBlank()) {
            // The default is the ACTIVE fleet — the provisioning list a
            // store portal consumes registers the devices that may
            // install, and a parked device must not silently return to
            // it.
            statusFilter = Status.ACTIVE;
        } else if ("ALL".equalsIgnoreCase(status.trim())) {
            statusFilter = null;
        } else {
            statusFilter = parseStatus(status);
        }
        Specification<DeviceEntity> filter = (root, criteriaQuery, builder) -> builder.conjunction();
        if (statusFilter != null) {
            filter = filter.and(statusIs(statusFilter));
        }
        // The export is the tenant's whole qualifying set, not a page:
        // a provisioning list cut at a cursor boundary would silently
        // under-provision. The set is bounded by the tenant's fleet.
        Page<DeviceEntity> page = devices.findAllScoped(filter, new CursorPageable(0, Integer.MAX_VALUE, NEWEST_FIRST));
        Map<UUID, TesterAccess.TesterRecord> owners = ownerRecordsOf(page.getContent());
        StringBuilder csv = new StringBuilder();
        csv.append("identifier,platform,model,os_version,owner_email\n");
        for (DeviceEntity row : page.getContent()) {
            TesterAccess.TesterRecord owner = owners.get(row.getTesterId());
            csv.append(csvField(row.getIdentifier()))
                    .append(',')
                    .append(csvField(row.getPlatform().name()))
                    .append(',')
                    .append(csvField(row.getModel()))
                    .append(',')
                    .append(csvField(row.getOsVersion()))
                    .append(',')
                    .append(csvField(owner == null ? "" : owner.email()))
                    .append('\n');
        }
        trace.tenant(tenantId);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("count", String.valueOf(page.getContent().size()));
        detail.put("status", statusFilter == null ? "ALL" : statusFilter.name());
        audit.publish(AuditEvent.builder(AuditEventType.DEVICES_EXPORTED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationOf(tenantId))
                .tenantId(tenantId)
                .success(true)
                .detail(detail)
                .build());
        return new CsvExport(
                csv.toString().getBytes(StandardCharsets.UTF_8),
                page.getContent().size());
    }

    // -------------------------------------------------------------------------
    // DeviceAccess: the feedback link facts (AC4)
    // -------------------------------------------------------------------------

    /**
     * The feedback surfaces' fact lookup: the submission link's
     * precondition (ownership, tenancy, ACTIVITY) and the stored
     * report's display context. The catalog's sanctioned cross-tenant
     * question — the caller validates the tuple against its own
     * tenant, never receives a row.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<DeviceFacts> factsOf(UUID deviceId) {
        return catalog.factsOf(deviceId)
                .map(facts -> new DeviceFacts(
                        facts.getId(),
                        facts.getTesterId(),
                        facts.getTenantId(),
                        facts.getStatus(),
                        facts.getPlatform(),
                        facts.getModel(),
                        facts.getOsVersion()));
    }

    // -------------------------------------------------------------------------
    // Views and commands
    // -------------------------------------------------------------------------

    /** The registration contract (AC1): the tester-typed fields only — the tenant arrives from the route. */
    public record RegisterCommand(UUID testerId, String platform, String model, String osVersion, String identifier) {}

    /** The descriptive update (AC2): both fields optional, null clears. */
    public record UpdateCommand(String model, String osVersion) {}

    /** One device with its owner's contact record (the console's management row). */
    public record DeviceView(
            UUID id,
            UUID testerId,
            String ownerEmail,
            String ownerName,
            String platform,
            String model,
            String osVersion,
            String identifier,
            String status,
            Instant createdAt,
            Instant updatedAt) {}

    /** The export's answer: the CSV bytes and the row count (the audit's own fact). */
    public record CsvExport(byte[] csv, long rows) {}

    // -------------------------------------------------------------------------
    // Validation and concealment helpers
    // -------------------------------------------------------------------------

    private static Platform validatePlatform(String raw, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.put("platform", "platform is required (" + PLATFORM_VOCABULARY + ")");
            return null;
        }
        try {
            return Platform.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.put("platform", "unknown platform '" + raw.trim() + "'; must be one of " + PLATFORM_VOCABULARY);
            return null;
        }
    }

    private static String validateRequiredText(String field, String raw, int maxLength, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            errors.put(field, field + " is required");
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            errors.put(field, field + " must be at most " + maxLength + " characters");
            return null;
        }
        return trimmed;
    }

    private static String validateOptionalText(String field, String raw, int maxLength, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > maxLength) {
            errors.put(field, field + " must be at most " + maxLength + " characters");
            return null;
        }
        return trimmed;
    }

    /** An unknown status token is a validation refusal (the feedback listing's type precedent), never a concealment. */
    private static Status parseStatus(String raw) {
        try {
            return Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DeviceException(
                    DeviceException.Code.INVALID_REQUEST,
                    "unknown status '" + raw.trim() + "'",
                    Map.of("status", "unknown status '" + raw.trim() + "'; must be one of " + STATUS_VOCABULARY));
        }
    }

    private static Platform parsePlatform(String raw) {
        try {
            return Platform.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DeviceException(
                    DeviceException.Code.INVALID_REQUEST,
                    "unknown platform '" + raw.trim() + "'",
                    Map.of("platform", "unknown platform '" + raw.trim() + "'; must be one of " + PLATFORM_VOCABULARY));
        }
    }

    private static DeviceException duplicate() {
        return new DeviceException(
                DeviceException.Code.DEVICE_IDENTIFIER_TAKEN,
                "the identifier is already registered in this tenant",
                Map.of("identifier", "a device with this identifier already exists in the tenant"));
    }

    private static DeviceException deviceMiss() {
        return new DeviceException(DeviceException.Code.DEVICE_NOT_FOUND, "the device did not resolve");
    }

    private static DeviceException tenantMiss() {
        return new DeviceException(DeviceException.Code.DEVICE_NOT_FOUND, "the tenant did not resolve");
    }

    private UUID organizationOf(UUID tenantId) {
        return tenants.owningOrganizationId(tenantId).orElse(null);
    }

    private TesterAccess.TesterRecord ownerRecordOf(UUID testerId) {
        return testers.recordsOf(Set.of(testerId)).getOrDefault(testerId, null);
    }

    private Map<UUID, TesterAccess.TesterRecord> ownerRecordsOf(List<DeviceEntity> rows) {
        Set<UUID> testerIds = rows.stream().map(DeviceEntity::getTesterId).collect(Collectors.toSet());
        return testers.recordsOf(testerIds);
    }

    /** The audit trail's device facts: ids and states only — the identifier is deliberately absent (privacy note). */
    private static Map<String, String> deviceDetail(DeviceEntity row, Status from, Status to) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("deviceId", String.valueOf(row.getId()));
        detail.put("testerId", String.valueOf(row.getTesterId()));
        detail.put("platform", row.getPlatform().name());
        if (from != null) {
            detail.put("from", from.name());
        }
        if (to != null) {
            detail.put("to", to.name());
        } else {
            detail.put("status", row.getStatus().name());
        }
        return detail;
    }

    /**
     * RFC 4180: a field carrying the syntax characters is quoted, and its
     * quotes doubled — after formula neutralization, so the apostrophe
     * lands inside the quotes. Package-private for the same-package
     * serializer unit test (the layered-engine test-tree precedent).
     */
    static String csvField(String value) {
        if (value == null) {
            return "";
        }
        String cell = neutralizeFormula(value);
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
            return '"' + cell.replace("\"", "\"\"") + '"';
        }
        return cell;
    }

    /**
     * Spreadsheet formula injection: a cell opening with =, +, -, @, or a
     * tab executes as a formula when the provisioning export is opened in
     * Excel or Sheets, so the export prefixes it with a neutral
     * apostrophe. Registration trims whitespace today; the tab arm is
     * defense-in-depth for future write paths. A legitimate hyphenated
     * word never opens with the dash and passes through untouched.
     */
    private static String neutralizeFormula(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t') {
            return "'" + value;
        }
        return value;
    }

    private static DeviceView viewOf(DeviceEntity row, TesterAccess.TesterRecord owner) {
        return new DeviceView(
                row.getId(),
                row.getTesterId(),
                owner == null ? null : owner.email(),
                owner == null ? null : owner.fullName(),
                row.getPlatform().name(),
                row.getModel(),
                row.getOsVersion(),
                row.getIdentifier(),
                row.getStatus().name(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    // -------------------------------------------------------------------------
    // Specifications (the listing's conjunctive filters)
    // -------------------------------------------------------------------------

    private static Specification<DeviceEntity> identifierIs(String identifier) {
        return (root, query, builder) -> builder.equal(root.get("identifier"), identifier);
    }

    private static Specification<DeviceEntity> testerIs(UUID testerId) {
        return (root, query, builder) -> builder.equal(root.get("testerId"), testerId);
    }

    private static Specification<DeviceEntity> platformIs(Platform platform) {
        return (root, query, builder) -> builder.equal(root.get("platform"), platform);
    }

    private static Specification<DeviceEntity> statusIs(Status status) {
        return (root, query, builder) -> builder.equal(root.get("status"), status);
    }
}
