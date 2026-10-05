package com.shipperdog.api.version.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.version.VersionException;
import com.shipperdog.api.version.internal.entity.VersionEntity;
import com.shipperdog.api.version.internal.repository.VersionRegistryRepository;
import com.shipperdog.api.version.internal.repository.VersionScopedRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The version layer (issue #17, S4-01, FR-008, PRD 8.4/8.6): logical
 * versions per application — {@code Banking 7.12.0} holding one artifact
 * per platform — created DRAFT (the DRAFT/ACTIVE/ARCHIVED lifecycle is
 * S4-03) with a validated semver-style name, unique per application
 * (the V1 {@code uq_versions_application_version} index is the
 * race-safe authority; the pre-check answers the common case with the
 * precise code). A version exists without being released: the release
 * history is part of the detail shape and stays empty until M2, and the
 * per-platform artifact slots stay empty until E5 uploads binaries
 * (S5-01) — the slot list per platform of the application is exactly
 * the "grouped by platform" surface the issue asks for.
 *
 * <p>Tenant isolation (INV-001, ADR-0002): the route interceptor binds
 * the application's owning scope for application-addressing routes (the
 * owning tenant's context, or the owning organization's for a shared
 * application, S3-02), and every version read and write composes
 * through the seam inside that bound scope — a version of another
 * tenant's or another organization's application is indistinguishable
 * from an unknown id: the standard concealed miss (PRD 12). Creation
 * copies the owning-dimension snapshot from the application's profile
 * (never from the caller), so the seam's write guard is the final
 * authority that the new row belongs to the request's bound scope.
 *
 * <p>The ownership-scope transition (issue #14) carries versions along
 * (see {@link VersionRegistryRepository}); creation still guards the
 * transition race directly: after the insert flushes, the application's
 * ownership is re-read, and a transition that committed in between is
 * followed by the same sanctioned carry-along update, so the new row
 * lands in the scope its application now has. The residual window —
 * the re-read itself racing the transition's update between it and
 * this transaction's commit — leaves the seam's fail-closed guards and
 * the transition's sweep as the authorities; no unscoped write can
 * result, in any interleaving.
 */
@Service
public class VersionService {

    /**
     * The semver-style name vocabulary (issue #17 AC1 and technical
     * notes): a numeric triple, each segment 0..99999 without leading
     * zeros (the semver rule; the width cap is what keeps the sort
     * key's five-digit padding total). Pre-release and build suffixes
     * are deliberately not part of the name: tagging is S4-02's
     * vocabulary (QA, Pilot, ...).
     */
    private static final Pattern SEMVER_TRIPLE =
            Pattern.compile("^(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})$");

    /** Segment width of the normalized sort key (0..99999 zero-padded). */
    private static final int SORT_SEGMENT_WIDTH = 5;

    /** Newest first: sort key descending, id descending as the tiebreak. */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "sortKey").and(Sort.by(Sort.Direction.DESC, "id"));

    private final VersionScopedRepository versions;
    private final VersionRegistryRepository registry;
    private final ApplicationAccess applications;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TraceAttributes trace;

    VersionService(
            VersionScopedRepository versions,
            VersionRegistryRepository registry,
            ApplicationAccess applications,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace) {
        this.versions = versions;
        this.registry = registry;
        this.applications = applications;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
    }

    // -------------------------------------------------------------------------
    // Creation (AC1) under the archived-application guard
    // -------------------------------------------------------------------------

    /**
     * Creates a version (AC1): the name is validated semver-style, the
     * row is born DRAFT under the application's owning-dimension
     * snapshot, and the creation is audited as {@code VERSION_CREATED}
     * (AC5). Duplicates per application are a 409 answered by the
     * pre-check and, on a lost race, by the unique index translated the
     * same way — {@code uq_versions_application_version} is the only
     * insert constraint this path can violate, because the owning
     * snapshot is copied from the application row the same transaction
     * reads. Creating under an ARCHIVED application is the 409 the
     * registry reserved in issue #13 (no new versions on archived apps;
     * archived applications stay readable with their existing versions,
     * issue #13 AC4).
     */
    @Transactional
    public VersionDetail create(UUID actorId, UUID applicationId, String versionName) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        if ("ARCHIVED".equals(application.status())) {
            throw new VersionException(
                    VersionException.Code.APPLICATION_ARCHIVED, "application is archived; no new versions");
        }
        String validatedName = requireVersionName(versionName);
        if (versions.existsScoped(ofApplication(applicationId).and(versionNameIs(validatedName)))) {
            throw nameTaken();
        }
        VersionEntity entity = new VersionEntity(
                applicationId,
                application.organizationId(),
                application.tenantId(),
                validatedName,
                sortKeyOf(validatedName));
        VersionEntity saved;
        try {
            saved = versions.saveScoped(entity);
            // Push the insert to the database inside the guard so a lost
            // uniqueness race translates here, not at commit.
            versions.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameTaken();
        }
        followOwnershipTransition(applicationId, application.tenantId(), saved.getTenantId());
        trace.application(applicationId);
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("versionName", validatedName);
        detail.put("status", saved.getStatus().name());
        audit(
                AuditEventType.VERSION_CREATED,
                actorId,
                saved.getOrganizationId(),
                saved.getTenantId(),
                applicationId,
                detail);
        return detailOf(saved, application.platforms());
    }

    // -------------------------------------------------------------------------
    // Reads: listing (AC2) and detail (AC2, AC4)
    // -------------------------------------------------------------------------

    /**
     * The per-application listing (AC2): newest first by the normalized
     * semver key, standard limit/cursor pagination, every item carrying
     * its artifact slots grouped by platform (empty until E5).
     */
    @Transactional(readOnly = true)
    public Page<VersionView> list(UUID applicationId, long offset, int limit) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        Page<VersionEntity> found =
                versions.findAllScoped(ofApplication(applicationId), new CursorPageable(offset, limit, NEWEST_FIRST));
        return found.map(version -> viewOf(version, application.platforms()));
    }

    /**
     * The version detail (AC2 + AC4): artifact slots grouped by platform
     * (empty in M1) and the release-history section (PRD 8.6 "a version
     * can exist without being released" — releases are M2, so the
     * section exists and is empty). A version id that does not belong
     * to the addressed application is the standard concealed miss,
     * indistinguishable from an unknown id.
     */
    @Transactional(readOnly = true)
    public VersionDetail get(UUID applicationId, UUID versionId) {
        VersionEntity version = versions.findScoped(versionId)
                .orElseThrow(() -> new VersionException(VersionException.Code.NOT_FOUND, "version not found"));
        if (!version.getApplicationId().equals(applicationId)) {
            throw new VersionException(VersionException.Code.NOT_FOUND, "version not found");
        }
        return detailOf(version, profileOf(applicationId).platforms());
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /**
     * The list-item view: registry fields plus the per-platform artifact
     * slots. The slot value is a list because the platform grouping is
     * the shape E5 fills (one artifact per platform per version, the
     * story's technical note); it is empty in M1.
     */
    public record VersionView(
            UUID id,
            UUID applicationId,
            String versionName,
            String status,
            Instant createdAt,
            Instant updatedAt,
            Map<String, List<Object>> artifactsByPlatform) {

        static VersionView from(VersionEntity version, List<String> platforms) {
            return new VersionView(
                    version.getId(),
                    version.getApplicationId(),
                    version.getVersionName(),
                    version.getStatus().name(),
                    version.getCreatedAt(),
                    version.getUpdatedAt(),
                    emptySlots(platforms));
        }
    }

    /** The detail view: the list-item shape plus the release history (AC4). */
    public record VersionDetail(
            UUID id,
            UUID applicationId,
            String versionName,
            String status,
            Instant createdAt,
            Instant updatedAt,
            Map<String, List<Object>> artifactsByPlatform,
            List<Object> releaseHistory) {

        static VersionDetail from(VersionEntity version, List<String> platforms) {
            return new VersionDetail(
                    version.getId(),
                    version.getApplicationId(),
                    version.getVersionName(),
                    version.getStatus().name(),
                    version.getCreatedAt(),
                    version.getUpdatedAt(),
                    emptySlots(platforms),
                    List.of());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers: profiles, specifications, validation, audit
    // -------------------------------------------------------------------------

    /**
     * The application profile through the public port (tuples only,
     * never the row): the owning-dimension snapshot for creation, the
     * platform set for the artifact slots, and the status for the
     * archived guard. A missing application is the standard miss; the
     * permission check conceal-denies foreign applications before the
     * service runs.
     */
    private ApplicationAccess.ApplicationProfile profileOf(UUID applicationId) {
        return applications
                .profileOf(applicationId)
                .orElseThrow(() -> new VersionException(VersionException.Code.NOT_FOUND, "application not found"));
    }

    /** Every version of one application, composed with the seam's scope. */
    private static Specification<VersionEntity> ofApplication(UUID applicationId) {
        return (root, query, builder) -> builder.equal(root.get("applicationId"), applicationId);
    }

    private static Specification<VersionEntity> versionNameIs(String versionName) {
        return (root, query, builder) -> builder.equal(root.get("versionName"), versionName);
    }

    /**
     * The create-vs-transition race repair (see the class javadoc): if
     * the application's ownership moved between the profile read and
     * the flushed insert, the new row follows through the same
     * sanctioned carry-along update the transition itself uses.
     */
    private void followOwnershipTransition(UUID applicationId, UUID tenantIdAtRead, UUID tenantIdOfRow) {
        if (Objects.equals(tenantIdAtRead, tenantIdOfRow)) {
            return;
        }
        applications
                .ownershipOf(applicationId)
                .ifPresent(ownership ->
                        registry.reassignOwnership(applicationId, ownership.tenantId(), Instant.now(clock)));
    }

    /** Empty artifact slot per platform of the application, in its order. */
    private static Map<String, List<Object>> emptySlots(List<String> platforms) {
        Map<String, List<Object>> slots = new LinkedHashMap<>();
        for (String platform : platforms) {
            slots.put(platform, List.of());
        }
        return slots;
    }

    private VersionView viewOf(VersionEntity version, List<String> platforms) {
        return VersionView.from(version, platforms);
    }

    private VersionDetail detailOf(VersionEntity version, List<String> platforms) {
        return VersionDetail.from(version, platforms);
    }

    /** Trims and validates against the semver-style triple vocabulary. */
    private static String requireVersionName(String versionName) {
        if (versionName == null || versionName.isBlank()) {
            throw invalid("versionName is required");
        }
        String trimmed = versionName.trim();
        if (!SEMVER_TRIPLE.matcher(trimmed).matches()) {
            throw invalid("versionName must be a semver-style triple like 7.12.0"
                    + " (each segment 0..99999, no leading zeros)");
        }
        return trimmed;
    }

    /**
     * The normalized sort key: each numeric segment zero-padded to the
     * fixed width, so lexicographic order is numeric order ("10.0.0"
     * after "9.9.9"). The database CHECK pins the same shape.
     */
    private static String sortKeyOf(String versionName) {
        String[] segments = versionName.split("\\.");
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                key.append('.');
            }
            key.append("0".repeat(SORT_SEGMENT_WIDTH - segments[i].length())).append(segments[i]);
        }
        return key.toString();
    }

    private static VersionException nameTaken() {
        return new VersionException(
                VersionException.Code.VERSION_NAME_TAKEN, "version name is already in use for the application");
    }

    private static VersionException invalid(String message) {
        return new VersionException(VersionException.Code.INVALID_REQUEST, message);
    }

    private void audit(
            AuditEventType type,
            UUID actorId,
            UUID organizationId,
            UUID tenantId,
            UUID applicationId,
            Map<String, String> detail) {
        audit.publish(AuditEvent.builder(type)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(organizationId)
                .tenantId(tenantId)
                .applicationId(applicationId)
                .success(true)
                .detail(detail)
                .build());
    }
}
