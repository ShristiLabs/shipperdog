package com.shipperdog.api.release.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseException;
import com.shipperdog.api.release.internal.entity.ReleaseArtifactEntity;
import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetGroupEntity;
import com.shipperdog.api.release.internal.entity.RingEntity;
import com.shipperdog.api.release.internal.repository.ReleaseArtifactRepository;
import com.shipperdog.api.release.internal.repository.ReleaseScopedRepository;
import com.shipperdog.api.release.internal.repository.ReleaseTargetGroupRepository;
import com.shipperdog.api.release.internal.repository.ReleaseTargetRepository;
import com.shipperdog.api.release.internal.repository.RingRepository;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.tester.TesterGroupAccess;
import com.shipperdog.api.version.VersionAccess;
import com.shipperdog.api.version.VersionDistributionContributor;
import com.shipperdog.api.version.VersionException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The release module's core (issue #55, S9-01, FR-015, PRD 8.6/13):
 * creation of a DRAFT release with its per-platform artifacts, target
 * tenants, per-target tester groups, and starting ring; the list and
 * detail reads; and the two distribution probes the platform reserved
 * for this module — {@link ReleaseAccess} (the application registry's
 * zero-distributions precondition on the ownership-scope transition,
 * issue #14) and {@link VersionDistributionContributor} (INV-004's
 * artifact-set freeze, issue #19).
 *
 * <p>Every route is top-level and body/query-addressed
 * ({@code POST/GET /api/v1/releases}), the artifact-collection precedent:
 * the permission check targets the application the body carries
 * ({@code app:releases:manage}/{@code app:releases:read} through the
 * evaluator's application-to-owner hop), and the service establishes
 * the owning scope through the sanctioned {@link TenantScopeExecutor}
 * once the application resolves — the release module never binds a
 * scope of its own, and the seam's write guard is the final authority
 * that a new row belongs to the request's bound scope.
 *
 * <p>Validation order is deliberate: identity first (application,
 * version, ring — the concealed misses), then the artifact selection
 * (READY and of the version, S5-05), then the target set, whose
 * failures accumulate into ONE 422 carrying every offending tenant and
 * group (issue AC2's per-tenant errors — a Release Manager fixes the
 * whole set in one pass). Nothing is written until every validation
 * passes; the write is one transaction, and the database's
 * {@code uq_releases_application_version} is the duplicate authority
 * the lost-race translation answers (V1's constraint, one release per
 * application and version).
 */
@Service
public class ReleaseService implements ReleaseAccess, VersionDistributionContributor {

    /** The artifact platforms a release slot may carry (V1's platform vocabulary). */
    private static final Set<String> PLATFORMS = Set.of("IOS", "ANDROID");

    /** Newest first: the quotable number descending, id descending as the deterministic tiebreak. */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "releaseNumber").and(Sort.by(Sort.Direction.DESC, "id"));

    private final ReleaseScopedRepository releases;
    private final ReleaseTargetRepository targets;
    private final ReleaseTargetGroupRepository targetGroups;
    private final ReleaseArtifactRepository releaseArtifacts;
    private final RingRepository rings;
    private final ApplicationAccess applications;
    private final VersionAccess versions;
    private final ArtifactAccess artifacts;
    private final TesterGroupAccess testerGroups;
    private final TenantScopeExecutor scopeExecutor;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TraceAttributes trace;
    private final JdbcTemplate jdbc;

    ReleaseService(
            ReleaseScopedRepository releases,
            ReleaseTargetRepository targets,
            ReleaseTargetGroupRepository targetGroups,
            ReleaseArtifactRepository releaseArtifacts,
            RingRepository rings,
            ApplicationAccess applications,
            VersionAccess versions,
            ArtifactAccess artifacts,
            TesterGroupAccess testerGroups,
            TenantScopeExecutor scopeExecutor,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace,
            JdbcTemplate jdbc) {
        this.releases = releases;
        this.targets = targets;
        this.targetGroups = targetGroups;
        this.releaseArtifacts = releaseArtifacts;
        this.rings = rings;
        this.applications = applications;
        this.versions = versions;
        this.artifacts = artifacts;
        this.testerGroups = testerGroups;
        this.scopeExecutor = scopeExecutor;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // Creation (AC1-AC4, AC6)
    // -------------------------------------------------------------------------

    /**
     * Creates a DRAFT release (AC1): one application, one version, one
     * READY artifact per platform, an explicit target set, and a starting
     * ring — every validation refusing before any write, the whole write
     * one transaction, and the creation audited with the full target set
     * (AC6) as {@code RELEASE_CREATED}. The response carries the
     * quotable id ({@code R-1001}, AC5) beside the uuid.
     */
    @Transactional
    public ReleaseDetail create(UUID actorId, CreateCommand command) {
        ApplicationAccess.ApplicationProfile application = profileOf(command.applicationId());
        if ("ARCHIVED".equals(application.status())) {
            throw new ReleaseException(
                    ReleaseException.Code.APPLICATION_ARCHIVED, "application is archived; no new releases");
        }
        if (command.versionId() == null) {
            throw invalid(Map.of("versionId", "versionId is required"));
        }
        VersionAccess.VersionState version = versionOf(command.applicationId(), command.versionId());
        if ("ARCHIVED".equals(version.status())) {
            throw new VersionException(
                    VersionException.Code.VERSION_ARCHIVED, "version is archived; no releases can target it");
        }
        if ("REVOKED".equals(version.status())) {
            throw new VersionException(
                    VersionException.Code.VERSION_REVOKED, "version's distribution is revoked; it cannot be released");
        }
        RingEntity ring = ringOf(command.ring(), application.organizationId());
        List<ArtifactSlot> slots =
                validatedArtifacts(command.applicationId(), command.versionId(), command.artifacts());
        Map<UUID, List<UUID>> groupsByTenant = validatedTargets(command.applicationId(), command.targets());

        ReleaseDetail detail = inScope(application, actorId, () -> {
            if (releases.existsScoped(ofApplication(command.applicationId()).and(versionIs(command.versionId())))) {
                throw releaseExists();
            }
            long releaseNumber = jdbc.queryForObject("select nextval('release_number_seq')", Long.class);
            ReleaseEntity release = new ReleaseEntity(
                    command.applicationId(),
                    command.versionId(),
                    application.organizationId(),
                    application.tenantId(),
                    ring.getId(),
                    releaseNumber,
                    actorId);
            ReleaseEntity saved;
            try {
                saved = releases.saveScoped(release);
                for (Map.Entry<UUID, List<UUID>> target : groupsByTenant.entrySet()) {
                    targets.save(new ReleaseTargetEntity(saved.getId(), target.getKey(), ring.getId()));
                    for (UUID groupId : target.getValue()) {
                        targetGroups.save(new ReleaseTargetGroupEntity(saved.getId(), target.getKey(), groupId));
                    }
                }
                for (ArtifactSlot slot : slots) {
                    releaseArtifacts.save(new ReleaseArtifactEntity(saved.getId(), slot.artifactId(), slot.platform()));
                }
                // Push the inserts to the database inside the guard so a
                // lost uniqueness race translates here, not at commit.
                releases.flush();
            } catch (DataIntegrityViolationException e) {
                // Only the duplicate authority translates: a different
                // constraint violation is a bug to surface, never a
                // wrong 409 masking it.
                if (e.getMessage() != null && e.getMessage().contains("uq_releases_application_version")) {
                    throw releaseExists();
                }
                throw e;
            }
            return detailOf(saved, ring);
        });

        trace.application(command.applicationId());
        Map<String, String> auditDetail = new LinkedHashMap<>();
        auditDetail.put("releaseRef", detail.releaseRef());
        auditDetail.put("versionId", command.versionId().toString());
        auditDetail.put("status", "DRAFT");
        auditDetail.put("ring", ring.getCode());
        auditDetail.put(
                "artifacts",
                slots.stream()
                        .map(slot -> slot.platform() + ":" + slot.artifactId())
                        .toList()
                        .toString());
        auditDetail.put(
                "targets",
                groupsByTenant.entrySet().stream()
                        .map(target -> target.getKey() + ":" + target.getValue().size() + "groups")
                        .sorted()
                        .toList()
                        .toString());
        audit.publish(AuditEvent.builder(AuditEventType.RELEASE_CREATED)
                .occurredAt(Instant.now(clock))
                .userId(actorId)
                .orgId(application.organizationId())
                .tenantId(application.tenantId())
                .applicationId(command.applicationId())
                .versionId(command.versionId())
                .success(true)
                .detail(auditDetail)
                .build());
        return detail;
    }

    // -------------------------------------------------------------------------
    // Reads: the filtered listing (AC5) and the detail
    // -------------------------------------------------------------------------

    /**
     * The release listing (AC5): filterable by application (required —
     * the permission target and the listing's scope), version, status,
     * and target tenant, newest (quotable number) first, standard
     * limit/cursor pagination. The tenant filter is a subquery over the
     * target rows composed with (never instead of) the seam's forced
     * scope predicate — the seventh amendment's tag-filter shape.
     */
    @Transactional(readOnly = true)
    public Page<ReleaseView> list(
            UUID actorId, UUID applicationId, UUID versionId, String status, UUID tenantId, long offset, int limit) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        ReleaseEntity.Status statusFilter = status == null || status.isBlank() ? null : parseStatus(status);
        return inScope(application, actorId, () -> {
            Specification<ReleaseEntity> scope = ofApplication(applicationId);
            if (versionId != null) {
                scope = scope.and(versionIs(versionId));
            }
            if (statusFilter != null) {
                scope = scope.and(statusIs(statusFilter));
            }
            if (tenantId != null) {
                scope = scope.and(targetsTenant(tenantId));
            }
            Page<ReleaseEntity> found = releases.findAllScoped(scope, new CursorPageable(offset, limit, NEWEST_FIRST));
            Map<UUID, Integer> targetCounts = targetCountsOf(found.getContent());
            Map<UUID, String> ringCodes = ringCodesOf(found.getContent());
            return found.map(release -> new ReleaseView(
                    release.getId(),
                    release.releaseRef(),
                    release.getApplicationId(),
                    release.getVersionId(),
                    release.getStatus().name(),
                    ringCodes.getOrDefault(release.getStartingRingId(), null),
                    targetCounts.getOrDefault(release.getId(), 0),
                    release.getCreatedAt(),
                    release.getUpdatedAt()));
        });
    }

    /**
     * The release detail: the full creation record — the quotable id, the
     * ring, the per-platform artifact selection, and the complete target
     * set with each target's tester groups (AC6's audited facts, read
     * back). A release id that does not belong to the addressed
     * application is the standard concealed miss, indistinguishable from
     * an unknown id.
     */
    @Transactional(readOnly = true)
    public ReleaseDetail get(UUID actorId, UUID applicationId, UUID releaseId) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseEntity release = releases.findScoped(releaseId)
                    .orElseThrow(() -> new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found"));
            if (!release.getApplicationId().equals(applicationId)) {
                throw new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found");
            }
            RingEntity ring = rings.findById(release.getStartingRingId()).orElse(null);
            return detailOf(release, ring);
        });
    }

    // -------------------------------------------------------------------------
    // The reserved distribution probes (issues #14 and #19)
    // -------------------------------------------------------------------------

    /**
     * The application registry's zero-distributions precondition (issue
     * #14 reserved it for this landing): whether the application has any
     * release, in any state — history blocks exactly like an active
     * release would, because a release row is never deleted. Called from
     * the scope transition inside the application's bound scope.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasReleases(UUID applicationId) {
        return releases.existsScoped(ofApplication(applicationId));
    }

    /**
     * INV-004's distribution-record probe (issue #19): whether the
     * version carries any release — the artifact-set freeze's release
     * leg, consulted by the version module's guard inside the caller's
     * bound scope. A DRAFT release counts: pinning artifacts into a
     * release is exactly the moment the set must stop moving.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasDistributionRecords(UUID versionId) {
        return releases.existsScoped(versionIs(versionId));
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /** The list-item view: registry fields, the quotable id, ring code, and target count. */
    public record ReleaseView(
            UUID id,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            String status,
            String ring,
            int targetCount,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * The detail view: the list-item shape plus the complete creation
     * record — the per-platform artifact selection and the full target
     * set with each target's tester groups and its ring.
     */
    public record ReleaseDetail(
            UUID id,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            String status,
            String ring,
            String ringName,
            List<ArtifactSlotView> artifacts,
            List<TargetView> targets,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {}

    /** One per-platform artifact slot of the detail: the platform and its selected artifact. */
    public record ArtifactSlotView(String platform, UUID artifactId) {}

    /** One target of the detail: the tenant, its ring, and its tester-group selection. */
    public record TargetView(UUID tenantId, String ring, List<UUID> testerGroupIds) {}

    /** The creation command (the controller's request, already the module's own shape). */
    public record CreateCommand(
            UUID applicationId,
            UUID versionId,
            String ring,
            List<ArtifactSlot> artifacts,
            List<TargetCommand> targets) {}

    /** One per-platform artifact selection: the platform and a READY artifact of the version. */
    public record ArtifactSlot(String platform, UUID artifactId) {}

    /** One target: the tenant and its tester-group selection (empty targets the tenant at large). */
    public record TargetCommand(UUID tenantId, List<UUID> testerGroupIds) {}

    // -------------------------------------------------------------------------
    // Helpers: resolution, validation, scope, audit
    // -------------------------------------------------------------------------

    /** The application profile through the public port (tuples only, never the row): the concealed-miss backstop. */
    private ApplicationAccess.ApplicationProfile profileOf(UUID applicationId) {
        return applications
                .profileOf(applicationId)
                .orElseThrow(() ->
                        new ReleaseException(ReleaseException.Code.APPLICATION_NOT_FOUND, "application not found"));
    }

    /** The version of the addressed application, or the concealed miss (unknown, foreign, or another application's). */
    private VersionAccess.VersionState versionOf(UUID applicationId, UUID versionId) {
        VersionAccess.VersionState state = versions.stateOf(versionId)
                .orElseThrow(() -> new ReleaseException(ReleaseException.Code.VERSION_NOT_FOUND, "version not found"));
        if (!state.applicationId().equals(applicationId)) {
            throw new ReleaseException(ReleaseException.Code.VERSION_NOT_FOUND, "version not found");
        }
        return state;
    }

    /** The ring by code within the organization's vocabulary (organization's own ring preferred, else the system default). */
    private RingEntity ringOf(String code, UUID organizationId) {
        if (code == null || code.isBlank()) {
            throw invalid(Map.of("ring", "ring is required"));
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return rings.byCode(normalized, organizationId)
                .orElseThrow(() -> new ReleaseException(
                        ReleaseException.Code.RELEASE_RING_UNKNOWN,
                        "ring is not defined for the organization",
                        Map.of("ring", "unknown ring code '" + normalized + "'")));
    }

    /**
     * The artifact selection (AC1): at least one slot, distinct
     * platforms, every artifact READY (S5-05) and belonging to the
     * release's version. An artifact that is unknown, foreign, or of
     * another version is the artifact read's own concealed miss; a
     * not-READY artifact is one 422 naming every offending platform.
     */
    private List<ArtifactSlot> validatedArtifacts(UUID applicationId, UUID versionId, List<ArtifactSlot> request) {
        if (request == null || request.isEmpty()) {
            throw invalid(Map.of("artifacts", "at least one artifact is required"));
        }
        Map<String, String> malformed = new LinkedHashMap<>();
        Set<String> platforms = new LinkedHashSet<>();
        Set<UUID> artifactIds = new LinkedHashSet<>();
        for (ArtifactSlot slot : request) {
            if (slot.artifactId() == null) {
                malformed.put("artifacts", "artifactId is required for every artifact slot");
                continue;
            }
            String platform =
                    slot.platform() == null ? "" : slot.platform().trim().toUpperCase(Locale.ROOT);
            if (!PLATFORMS.contains(platform)) {
                malformed.putIfAbsent("artifacts", "platform must be IOS or ANDROID");
                continue;
            }
            if (!platforms.add(platform)) {
                malformed.put("artifacts", "at most one artifact per platform");
                continue;
            }
            if (!artifactIds.add(slot.artifactId())) {
                malformed.put("artifacts", "an artifact may be selected at most once");
            }
        }
        if (!malformed.isEmpty()) {
            throw invalid(malformed);
        }
        Map<String, String> notReady = new LinkedHashMap<>();
        List<ArtifactSlot> resolved = new ArrayList<>(request.size());
        for (ArtifactSlot slot : request) {
            String platform = slot.platform().trim().toUpperCase(Locale.ROOT);
            Optional<ArtifactAccess.ArtifactState> state = artifacts.stateOf(slot.artifactId());
            if (state.isEmpty() || !versionId.equals(state.get().versionId())) {
                // Concealment parity with the artifact read: an artifact
                // of another application or version is a miss, never a
                // confirmed existence leak.
                throw new ReleaseException(ReleaseException.Code.ARTIFACT_NOT_FOUND, "artifact not found");
            }
            if (!platform.equals(state.get().platform())) {
                malformed.put(
                        "artifacts",
                        "platform " + platform + " does not match the artifact's platform "
                                + state.get().platform());
                continue;
            }
            if (!"READY".equals(state.get().status())) {
                notReady.put(
                        platform,
                        "artifact " + slot.artifactId() + " is " + state.get().status());
                continue;
            }
            resolved.add(new ArtifactSlot(platform, slot.artifactId()));
        }
        if (!malformed.isEmpty()) {
            throw invalid(malformed);
        }
        if (!notReady.isEmpty()) {
            throw new ReleaseException(
                    ReleaseException.Code.RELEASE_ARTIFACT_NOT_READY, "selected artifacts are not READY", notReady);
        }
        return resolved;
    }

    /**
     * The target set (AC2/AC3): at least one target, distinct tenants,
     * every tenant holding the application (tenant-owned or
     * shared+authorized — the S3-03 effective-visibility predicate), and
     * every tester group belonging to its target tenant. Every offense
     * accumulates into ONE 422 keyed by tenant or group id.
     */
    private Map<UUID, List<UUID>> validatedTargets(UUID applicationId, List<TargetCommand> request) {
        if (request == null || request.isEmpty()) {
            throw invalid(Map.of("targets", "at least one target is required"));
        }
        Map<String, String> malformed = new LinkedHashMap<>();
        Set<UUID> tenants = new LinkedHashSet<>();
        for (TargetCommand target : request) {
            if (target.tenantId() == null) {
                malformed.put("targets", "tenantId is required for every target");
            } else if (!tenants.add(target.tenantId())) {
                malformed.put("targets", "a tenant may be targeted at most once");
            }
        }
        if (!malformed.isEmpty()) {
            throw invalid(malformed);
        }
        Map<String, String> invalid = new LinkedHashMap<>();
        Map<UUID, List<UUID>> groupsByTenant = new LinkedHashMap<>();
        for (TargetCommand target : request) {
            if (!applications.visibleToTenant(applicationId, target.tenantId())) {
                invalid.put(
                        target.tenantId().toString(),
                        "tenant does not hold the application (tenant-owned or shared with an ACTIVE authorization)");
            }
            List<UUID> groups = target.testerGroupIds() == null ? List.of() : target.testerGroupIds();
            Set<UUID> distinct = new LinkedHashSet<>();
            for (UUID groupId : groups) {
                if (groupId == null || !distinct.add(groupId)) {
                    invalid.putIfAbsent(target.tenantId().toString(), "tester groups must be distinct within a target");
                    continue;
                }
                Optional<UUID> owning = testerGroups.owningTenantOf(groupId);
                if (owning.isEmpty() || !owning.get().equals(target.tenantId())) {
                    invalid.put(groupId.toString(), "tester group does not belong to target tenant");
                }
            }
            groupsByTenant.put(target.tenantId(), List.copyOf(distinct));
        }
        if (!invalid.isEmpty()) {
            throw new ReleaseException(
                    ReleaseException.Code.RELEASE_TARGETS_INVALID, "invalid release targets", invalid);
        }
        return groupsByTenant;
    }

    /** Establishes the application's owning scope and runs the work inside it (the artifact module's executor pattern). */
    private <T> T inScope(ApplicationAccess.ApplicationProfile application, UUID actorId, Supplier<T> work) {
        return application.tenantId() != null
                ? scopeExecutor.inScope(application.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    private ReleaseDetail detailOf(ReleaseEntity release, RingEntity ring) {
        List<ReleaseArtifactEntity> artifactRows =
                releaseArtifacts.findByReleaseIdOrderByPlatformAscArtifactIdAsc(release.getId());
        List<ReleaseTargetEntity> targetRows = targets.findByReleaseIdOrderByTenantIdAsc(release.getId());
        List<ReleaseTargetGroupEntity> groupRows =
                targetGroups.findByReleaseIdOrderByTenantIdAscGroupIdAsc(release.getId());
        Map<UUID, List<UUID>> groupsByTenant = new LinkedHashMap<>();
        for (ReleaseTargetGroupEntity group : groupRows) {
            groupsByTenant
                    .computeIfAbsent(group.getTenantId(), tenant -> new ArrayList<>())
                    .add(group.getGroupId());
        }
        return new ReleaseDetail(
                release.getId(),
                release.releaseRef(),
                release.getApplicationId(),
                release.getVersionId(),
                release.getStatus().name(),
                ring == null ? null : ring.getCode(),
                ring == null ? null : ring.getName(),
                artifactRows.stream()
                        .map(row -> new ArtifactSlotView(row.getPlatform(), row.getArtifactId()))
                        .toList(),
                targetRows.stream()
                        .map(row -> new TargetView(
                                row.getTenantId(),
                                ring == null ? null : ring.getCode(),
                                groupsByTenant.getOrDefault(row.getTenantId(), List.of())))
                        .toList(),
                release.getCreatedBy(),
                release.getCreatedAt(),
                release.getUpdatedAt());
    }

    private Map<UUID, Integer> targetCountsOf(List<ReleaseEntity> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (ReleaseTargetEntity row : targets.findByReleaseIdIn(
                page.stream().map(ReleaseEntity::getId).toList())) {
            counts.merge(row.getReleaseId(), 1, Integer::sum);
        }
        return counts;
    }

    private Map<UUID, String> ringCodesOf(List<ReleaseEntity> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ringIds = new LinkedHashSet<>();
        page.forEach(release -> ringIds.add(release.getStartingRingId()));
        Map<UUID, String> codes = new LinkedHashMap<>();
        for (RingEntity ring : rings.findAllById(ringIds)) {
            codes.put(ring.getId(), ring.getCode());
        }
        return codes;
    }

    private static ReleaseEntity.Status parseStatus(String status) {
        try {
            return ReleaseEntity.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(Map.of("status", "unknown release status '" + status.trim() + "'"));
        }
    }

    private static ReleaseException releaseExists() {
        return new ReleaseException(
                ReleaseException.Code.RELEASE_ALREADY_EXISTS,
                "a release already exists for this application and version");
    }

    private static ReleaseException invalid(Map<String, String> errors) {
        return new ReleaseException(ReleaseException.Code.INVALID_REQUEST, "the request is invalid", errors);
    }

    private static Specification<ReleaseEntity> ofApplication(UUID applicationId) {
        return (root, query, builder) -> builder.equal(root.get("applicationId"), applicationId);
    }

    private static Specification<ReleaseEntity> versionIs(UUID versionId) {
        return (root, query, builder) -> builder.equal(root.get("versionId"), versionId);
    }

    private static Specification<ReleaseEntity> statusIs(ReleaseEntity.Status status) {
        return (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    /**
     * Releases targeting the tenant (AC5's tenant filter): a subquery
     * over the target rows, composed with the seam's scope on the outer
     * release — the filter narrows the releases inside the bound scope,
     * never widens it (the tag-filter shape, seventh amendment).
     */
    private static Specification<ReleaseEntity> targetsTenant(UUID tenantId) {
        return (root, query, builder) -> {
            jakarta.persistence.criteria.Subquery<UUID> targeted = query.subquery(UUID.class);
            jakarta.persistence.criteria.Root<ReleaseTargetEntity> targetRow = targeted.from(ReleaseTargetEntity.class);
            targeted.select(targetRow.get("releaseId")).where(builder.equal(targetRow.get("tenantId"), tenantId));
            return builder.in(root.get("id")).value(targeted);
        };
    }
}
