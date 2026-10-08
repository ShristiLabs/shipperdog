package com.shipperdog.api.release.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.artifact.ArtifactAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.environment.EnvironmentAccess;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseException;
import com.shipperdog.api.release.ReleaseTransitionPolicy;
import com.shipperdog.api.release.ReleaseTransitionedEvent;
import com.shipperdog.api.release.internal.entity.ReleaseArtifactEntity;
import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.entity.ReleaseStatusHistoryEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetGroupEntity;
import com.shipperdog.api.release.internal.entity.RingEntity;
import com.shipperdog.api.release.internal.repository.ReleaseArtifactRepository;
import com.shipperdog.api.release.internal.repository.ReleaseScopedRepository;
import com.shipperdog.api.release.internal.repository.ReleaseStatusHistoryRepository;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
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
 * <p>The state machine (issue #56, S9-02, PRD 17): the transition route
 * moves a release along the diagram's edges only — legality is
 * {@link ReleaseStateMachine}'s single exhaustive table, serialized
 * against concurrent writers by the database compare-and-set (the V14
 * CAS discipline: the row moves only while its committed status is
 * still the source state this attempt read, so racing transitions can
 * never double-commit or un-terminal). Every committed crossing
 * appends its {@code release_status_history} row (the append-only
 * timeline the history route returns — creation wrote the row every
 * release starts with), audits {@code RELEASE_TRANSITIONED} with
 * before/after and the mandatory failure/rollback reason (FR-024), and
 * publishes {@link ReleaseTransitionedEvent} for the listeners that
 * follow (S12-01's bus first). Side conditions compose through the
 * {@link ReleaseTransitionPolicy} seam — E10's approval evaluation is
 * its first registrant; none ships here. No transition touches the
 * pinned artifact set: the freeze is absolute, and a FAILED release's
 * fix path is a new version and a new release (the documented
 * decision).
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

    /**
     * Transition attempts before the deterministic re-read conflict:
     * PRD 17's graph is forward-only with terminal walls, so two is
     * the mathematical bound against any interleaving; three is the
     * same bound with headroom (the V14 CAS loop's arithmetic).
     */
    private static final int MAX_TRANSITION_ATTEMPTS = 3;

    /** The reason cap (V25's database check pins the same width, the V14 cap). */
    private static final int MAX_REASON_LENGTH = 1000;

    private final ReleaseScopedRepository releases;
    private final ReleaseTargetRepository targets;
    private final ReleaseTargetGroupRepository targetGroups;
    private final ReleaseArtifactRepository releaseArtifacts;
    private final ReleaseStatusHistoryRepository statusHistory;
    private final RingRepository rings;
    private final ApplicationAccess applications;
    private final VersionAccess versions;
    private final ArtifactAccess artifacts;
    private final TesterGroupAccess testerGroups;
    private final EnvironmentAccess environments;
    private final TenantScopeExecutor scopeExecutor;
    private final AuditEventPublisher audit;
    private final ObjectProvider<ReleaseTransitionPolicy> transitionPolicies;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TraceAttributes trace;
    private final JdbcTemplate jdbc;

    ReleaseService(
            ReleaseScopedRepository releases,
            ReleaseTargetRepository targets,
            ReleaseTargetGroupRepository targetGroups,
            ReleaseArtifactRepository releaseArtifacts,
            ReleaseStatusHistoryRepository statusHistory,
            RingRepository rings,
            ApplicationAccess applications,
            VersionAccess versions,
            ArtifactAccess artifacts,
            TesterGroupAccess testerGroups,
            EnvironmentAccess environments,
            TenantScopeExecutor scopeExecutor,
            AuditEventPublisher audit,
            ObjectProvider<ReleaseTransitionPolicy> transitionPolicies,
            ApplicationEventPublisher events,
            Clock clock,
            TraceAttributes trace,
            JdbcTemplate jdbc) {
        this.releases = releases;
        this.targets = targets;
        this.targetGroups = targetGroups;
        this.releaseArtifacts = releaseArtifacts;
        this.statusHistory = statusHistory;
        this.rings = rings;
        this.applications = applications;
        this.versions = versions;
        this.artifacts = artifacts;
        this.testerGroups = testerGroups;
        this.environments = environments;
        this.scopeExecutor = scopeExecutor;
        this.audit = audit;
        this.transitionPolicies = transitionPolicies;
        this.events = events;
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
        EnvironmentAccess.EnvironmentState environment =
                environmentOf(command.environmentId(), application.organizationId());
        Set<UUID> environmentTenants = environment == null ? null : environments.assignedTenantIdsOf(environment.id());
        List<ArtifactSlot> slots =
                validatedArtifacts(command.applicationId(), command.versionId(), command.artifacts());
        Map<UUID, List<UUID>> groupsByTenant =
                validatedTargets(command.applicationId(), command.targets(), environmentTenants);

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
                    environment == null ? null : environment.id(),
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
                // The timeline row every release starts with (V25): born
                // DRAFT — from is null on exactly this row.
                statusHistory.save(new ReleaseStatusHistoryEntity(
                        saved.getId(), null, ReleaseEntity.Status.DRAFT, null, actorId, Instant.now(clock)));
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
        if (environment != null) {
            trace.environment(environment.id());
        }
        Map<String, String> auditDetail = new LinkedHashMap<>();
        auditDetail.put("releaseRef", detail.releaseRef());
        auditDetail.put("versionId", command.versionId().toString());
        auditDetail.put("status", "DRAFT");
        auditDetail.put("ring", ring.getCode());
        if (environment != null) {
            auditDetail.put("environment", environment.code());
        }
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
            Map<UUID, String> environmentCodes = environmentCodesOf(found.getContent());
            return found.map(release -> new ReleaseView(
                    release.getId(),
                    release.releaseRef(),
                    release.getApplicationId(),
                    release.getVersionId(),
                    release.getStatus().name(),
                    ringCodes.getOrDefault(release.getStartingRingId(), null),
                    release.getEnvironmentId() == null
                            ? null
                            : environmentCodes.getOrDefault(release.getEnvironmentId(), null),
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
            ReleaseEntity release = scopedReleaseOf(applicationId, releaseId);
            RingEntity ring = rings.findById(release.getStartingRingId()).orElse(null);
            return detailOf(release, ring);
        });
    }

    // -------------------------------------------------------------------------
    // The state machine (issue #56, S9-02, PRD 17)
    // -------------------------------------------------------------------------

    /**
     * One release transition (issue #56 AC1-AC3): the crossing must be
     * one of PRD 17's edges ({@link ReleaseStateMachine}'s table answers
     * 409 with the current and attempted states otherwise — including
     * the self-crossing and every exit from a terminal), the failure
     * and rollback edges carry a mandatory reason (422 when missing),
     * and every registered {@link ReleaseTransitionPolicy} must permit
     * the crossing (422 keyed by the refusing contributor; none ships
     * in M2 — the approval evaluator is S10-01). Serialized against
     * concurrent writers by the database compare-and-set: the row moves
     * only while its committed status is still the source state this
     * attempt read, so racing transitions can never double-commit or
     * un-terminal; a loser re-reads the committed state and either
     * retries a still-legal crossing or answers the precise conflict
     * (the graph is forward-only with terminal walls, so the loop
     * terminates). The winner appends the timeline row, audits
     * {@code RELEASE_TRANSITIONED} (FR-024), publishes
     * {@link ReleaseTransitionedEvent}, and answers the refreshed
     * detail. Not idempotency-keyed: the state itself is the replay
     * answer.
     */
    @Transactional
    public ReleaseDetail transition(UUID actorId, UUID applicationId, UUID releaseId, String to, String reason) {
        ReleaseEntity.Status target = parseTransitionTarget(to);
        String validatedReason = validatedReason(target, reason);
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            for (int attempt = 0; attempt < MAX_TRANSITION_ATTEMPTS; attempt++) {
                ReleaseEntity release = scopedReleaseOf(applicationId, releaseId);
                ReleaseEntity.Status from = release.getStatus();
                if (!ReleaseStateMachine.legal(from, target)) {
                    throw transitionConflict(from, target);
                }
                for (ReleaseTransitionPolicy policy :
                        transitionPolicies.orderedStream().toList()) {
                    Optional<String> denial = policy.denial(new ReleaseTransitionPolicy.TransitionCheck(
                            release.getId(),
                            release.releaseRef(),
                            release.getApplicationId(),
                            release.getVersionId(),
                            release.getOrganizationId(),
                            from.name(),
                            target.name()));
                    if (denial.isPresent()) {
                        throw new ReleaseException(
                                ReleaseException.Code.RELEASE_TRANSITION_BLOCKED,
                                "a transition policy refuses this transition",
                                Map.of(policy.getClass().getSimpleName(), denial.get()));
                    }
                }
                if (statusHistory.transitionStatusIfCurrent(release.getId(), from.name(), target.name()) == 1) {
                    Instant occurredAt = Instant.now(clock);
                    statusHistory.save(new ReleaseStatusHistoryEntity(
                            release.getId(), from, target, validatedReason, actorId, occurredAt));
                    trace.application(applicationId);
                    Map<String, String> auditDetail = new LinkedHashMap<>();
                    auditDetail.put("releaseRef", release.releaseRef());
                    auditDetail.put("fromStatus", from.name());
                    auditDetail.put("toStatus", target.name());
                    if (validatedReason != null) {
                        auditDetail.put("reason", validatedReason);
                    }
                    audit.publish(AuditEvent.builder(AuditEventType.RELEASE_TRANSITIONED)
                            .occurredAt(occurredAt)
                            .userId(actorId)
                            .orgId(release.getOrganizationId())
                            .tenantId(release.getTenantId())
                            .applicationId(applicationId)
                            .versionId(release.getVersionId())
                            .success(true)
                            .detail(auditDetail)
                            .build());
                    events.publishEvent(new ReleaseTransitionedEvent(
                            release.getId(),
                            release.releaseRef(),
                            applicationId,
                            release.getVersionId(),
                            release.getOrganizationId(),
                            release.getTenantId(),
                            from.name(),
                            target.name(),
                            validatedReason,
                            actorId,
                            occurredAt));
                    ReleaseEntity fresh = scopedReleaseOf(applicationId, releaseId);
                    return detailOf(
                            fresh, rings.findById(fresh.getStartingRingId()).orElse(null));
                }
            }
            // Unreachable against the forward-only graph; the
            // deterministic answer if it ever were reached is the
            // committed state's precise conflict, re-read fresh.
            throw transitionConflict(scopedReleaseOf(applicationId, releaseId).getStatus(), target);
        });
    }

    /**
     * The release's transition history (issue #56 AC5, FR-024): the
     * release summary plus its chronological timeline — the creation's
     * DRAFT row (from null, the creator as actor) and one row per
     * committed crossing with its actor, its reason where one was
     * given or required, and the service clock's timestamp. The
     * Compliance Reviewer's roll-forward question — what did this
     * release do, when, and who moved it — answers from this one read,
     * and the answer never expires (PRD P5). The S9-05 dashboard
     * renders this timeline directly.
     */
    @Transactional(readOnly = true)
    public ReleaseHistoryView history(UUID actorId, UUID applicationId, UUID releaseId) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseEntity release = scopedReleaseOf(applicationId, releaseId);
            List<TransitionView> transitions = new ArrayList<>();
            for (ReleaseStatusHistoryEntity row :
                    statusHistory.findByReleaseIdOrderByOccurredAtAscIdAsc(release.getId())) {
                transitions.add(new TransitionView(
                        row.getFromStatus() == null ? null : row.getFromStatus().name(),
                        row.getToStatus().name(),
                        row.getReason(),
                        row.getActorId(),
                        row.getOccurredAt()));
            }
            return new ReleaseHistoryView(
                    release.getId(),
                    release.releaseRef(),
                    release.getApplicationId(),
                    release.getVersionId(),
                    release.getStatus().name(),
                    release.getCreatedAt(),
                    release.getUpdatedAt(),
                    List.copyOf(transitions));
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
    // ReleaseAccess (S10-01, issue #40): the approval engine's read facts
    // -------------------------------------------------------------------------

    /**
     * The seam-served release snapshot for the approval engine
     * (S10-01): unknown, out-of-scope, and foreign answer empty — the
     * concealed miss the workflow module renders as its own miss. The
     * ring is the RESOLVED starting ring (code beside id, the
     * definition-attachment vocabulary); the status is the stable
     * string vocabulary (events' discipline: cross-module facts never
     * leak the internal enum).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ReleaseAccess.ReleaseFacts> factsOf(UUID releaseId) {
        return releases.findScoped(releaseId).map(release -> {
            RingEntity ring = rings.findById(release.getStartingRingId()).orElse(null);
            return new ReleaseAccess.ReleaseFacts(
                    release.getId(),
                    release.releaseRef(),
                    release.getApplicationId(),
                    release.getOrganizationId(),
                    release.getTenantId(),
                    release.getCreatedBy(),
                    release.getStatus().name(),
                    ring == null ? null : ring.getId(),
                    ring == null ? null : ring.getCode(),
                    release.getEnvironmentId());
        });
    }

    /**
     * The ring resolution of the creation surface (S10-01's definition
     * attachment): the organization's own ring preferred over the
     * system default of the same code, so a definition attached to a
     * code and a release created against it can never disagree on
     * which ring they mean.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> ringIdOf(UUID organizationId, String ringCode) {
        if (ringCode == null || ringCode.isBlank()) {
            return Optional.empty();
        }
        return rings.byCode(ringCode.trim().toLowerCase(Locale.ROOT), organizationId)
                .map(RingEntity::getId);
    }

    /** The release's target tenants, served through the seam (the tenant-scoped approval steps' applicability set). */
    @Override
    @Transactional(readOnly = true)
    public Set<UUID> targetTenantIdsOf(UUID releaseId) {
        Set<UUID> targeted = new LinkedHashSet<>();
        releases.findScoped(releaseId)
                .ifPresent(
                        release -> targeted.addAll(targets.findByReleaseIdOrderByTenantIdAsc(release.getId()).stream()
                                .map(ReleaseTargetEntity::getTenantId)
                                .toList()));
        return targeted;
    }

    /** The code of a ring id (S10-01's definition views render the attachment as the API's vocabulary). */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> ringCodeOf(UUID ringId) {
        return rings.findById(ringId).map(RingEntity::getCode);
    }

    /**
     * The approval-policy name of the ORGANIZATION-EFFECTIVE ring for
     * this ring id (S10-04, issue #43): the row resolved
     * organization-preferred (the V24 shadowing contract — a system
     * ring an organization redefined under the same code answers the
     * organization ring), and its {@code approval_policy} when present.
     * The approval engine's ring leg (the precedence chain's first
     * named binding).
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<String> ringApprovalPolicyNameOf(UUID organizationId, UUID ringId) {
        return rings.findById(ringId)
                .flatMap(ring -> rings.byCode(ring.getCode(), organizationId))
                .map(RingEntity::getApprovalPolicy)
                .filter(policy -> policy != null && !policy.isBlank());
    }

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /** The list-item view: registry fields, the quotable id, ring and environment codes, and target count. */
    public record ReleaseView(
            UUID id,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            String status,
            String ring,
            String environment,
            int targetCount,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * The detail view: the list-item shape plus the complete creation
     * record — the per-platform artifact selection, the full target set
     * with each target's tester groups and its ring, and the targeted
     * environment when creation named one (S9-04).
     */
    public record ReleaseDetail(
            UUID id,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            String status,
            String ring,
            String ringName,
            UUID environmentId,
            String environment,
            String environmentName,
            List<ArtifactSlotView> artifacts,
            List<TargetView> targets,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt) {}

    /** One per-platform artifact slot of the detail: the platform and its selected artifact. */
    public record ArtifactSlotView(String platform, UUID artifactId) {}

    /** One target of the detail: the tenant, its ring, and its tester-group selection. */
    public record TargetView(UUID tenantId, String ring, List<UUID> testerGroupIds) {}

    /**
     * One timeline entry of the history view (issue #56 AC5): the
     * crossing's source and target states (the creation row's source
     * is null — a release is born, it does not transition into
     * DRAFT), the reason where one was given or required, the actor,
     * and when.
     */
    public record TransitionView(String from, String to, String reason, UUID actorId, Instant occurredAt) {}

    /**
     * The history view (issue #56 AC5, FR-024): the release summary
     * and its chronological transition timeline — the append-only
     * record the S9-05 dashboard renders.
     */
    public record ReleaseHistoryView(
            UUID id,
            String releaseRef,
            UUID applicationId,
            UUID versionId,
            String status,
            Instant createdAt,
            Instant updatedAt,
            List<TransitionView> transitions) {}

    /** The creation command (the controller's request, already the module's own shape). */
    public record CreateCommand(
            UUID applicationId,
            UUID versionId,
            String ring,
            UUID environmentId,
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

    /**
     * The release resolved scoped (the seam's bound scope) and verified
     * against the addressed application: any release id that is
     * unknown, out of scope, or belonging to a different application
     * is the standard concealed miss, indistinguishable from an
     * unknown id.
     */
    private ReleaseEntity scopedReleaseOf(UUID applicationId, UUID releaseId) {
        ReleaseEntity release = releases.findScoped(releaseId)
                .orElseThrow(() -> new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found"));
        if (!release.getApplicationId().equals(applicationId)) {
            throw new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found");
        }
        return release;
    }

    /**
     * The transition route's target state: the same vocabulary as the
     * listing's status filter, keyed by this route's own field — an
     * unknown value is a 400 naming {@code to}, never a
     * silently-answered crossing.
     */
    private static ReleaseEntity.Status parseTransitionTarget(String to) {
        if (to == null || to.isBlank()) {
            throw invalid(Map.of("to", "to is required and must name a release status"));
        }
        try {
            return ReleaseEntity.Status.valueOf(to.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(Map.of("to", "unknown release status '" + to.trim() + "'"));
        }
    }

    /**
     * The crossing's reason (issue #56 AC3): mandatory on the failure
     * and rollback edges (the S4-03 revoke analog — a release that
     * died or was pulled says why, feeding the audit event and the
     * timeline row), optional everywhere else. Trimmed; a blank
     * reason on an optional crossing is no reason at all.
     */
    private static String validatedReason(ReleaseEntity.Status target, String reason) {
        if (reason == null || reason.isBlank()) {
            if (ReleaseStateMachine.reasonRequired(target)) {
                throw new ReleaseException(
                        ReleaseException.Code.RELEASE_TRANSITION_REASON_REQUIRED,
                        "a reason is required for transitions to " + target,
                        Map.of("reason", "a reason is required for transitions to " + target));
            }
            return null;
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw invalid(Map.of("reason", "reason is at most " + MAX_REASON_LENGTH + " characters"));
        }
        return trimmed;
    }

    /** A crossing PRD 17 forbids: the precise 409 naming both states (the current includes the self-crossing). */
    private static ReleaseException transitionConflict(ReleaseEntity.Status from, ReleaseEntity.Status to) {
        return new ReleaseException(
                ReleaseException.Code.RELEASE_TRANSITION_INVALID,
                "the release cannot transition from " + from + " to " + to,
                Map.of("current", from.name(), "attempted", to.name()));
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
     * The named environment of creation (S9-04, issue #58 AC4): null
     * when the request named none; otherwise it must exist, belong to
     * the application's organization (a foreign environment is the
     * concealed miss, indistinguishable from unknown), and be ACTIVE
     * (a parked environment may not be named). Resolution precedes the
     * target validation so the assignment check composes with the
     * visibility rule into the same per-tenant 422.
     */
    private EnvironmentAccess.EnvironmentState environmentOf(UUID environmentId, UUID organizationId) {
        if (environmentId == null) {
            return null;
        }
        EnvironmentAccess.EnvironmentState state = environments
                .stateOf(environmentId)
                .orElseThrow(() ->
                        new ReleaseException(ReleaseException.Code.ENVIRONMENT_NOT_FOUND, "environment not found"));
        if (!state.organizationId().equals(organizationId)) {
            throw new ReleaseException(ReleaseException.Code.ENVIRONMENT_NOT_FOUND, "environment not found");
        }
        if (!"ACTIVE".equals(state.status())) {
            throw new ReleaseException(
                    ReleaseException.Code.ENVIRONMENT_DISABLED,
                    "environment '" + state.code() + "' is disabled; no new releases may target it");
        }
        return state;
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
     * The target set (AC2/AC3; the environment leg since S9-04, AC4):
     * at least one target, distinct tenants, every tenant holding the
     * application (tenant-owned or shared+authorized — the S3-03
     * effective-visibility predicate), every tester group belonging to
     * its target tenant, and — when creation names an environment —
     * every target tenant sitting inside that environment's
     * assignments (deny-by-default: an empty assignment set admits no
     * tenant). Every offense accumulates into ONE 422 keyed by tenant
     * or group id.
     */
    private Map<UUID, List<UUID>> validatedTargets(
            UUID applicationId, List<TargetCommand> request, Set<UUID> environmentTenants) {
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
            if (environmentTenants != null && !environmentTenants.contains(target.tenantId())) {
                invalid.putIfAbsent(
                        target.tenantId().toString(),
                        "tenant is not assigned to the release environment (environment assignments are "
                                + "deny-by-default)");
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
        // Each target renders its OWN ring's code (S10-05: per-target
        // ring moves) — resolved organization-preferred, the shadowing
        // contract, so a code always renders the meaning the
        // organization gives it (all targets share the starting ring
        // until the promotion engine moves them).
        Map<UUID, String> targetRingCodes = new LinkedHashMap<>();
        for (ReleaseTargetEntity row : targetRows) {
            if (row.getRingId() != null && !targetRingCodes.containsKey(row.getRingId())) {
                rings.findById(row.getRingId()).ifPresent(targetRing -> {
                    targetRingCodes.put(
                            row.getRingId(),
                            rings.byCode(targetRing.getCode(), release.getOrganizationId())
                                    .map(RingEntity::getCode)
                                    .orElse(targetRing.getCode()));
                });
            }
        }
        EnvironmentAccess.EnvironmentState environment = release.getEnvironmentId() == null
                ? null
                : environments.stateOf(release.getEnvironmentId()).orElse(null);
        return new ReleaseDetail(
                release.getId(),
                release.releaseRef(),
                release.getApplicationId(),
                release.getVersionId(),
                release.getStatus().name(),
                ring == null ? null : ring.getCode(),
                ring == null ? null : ring.getName(),
                release.getEnvironmentId(),
                environment == null ? null : environment.code(),
                environment == null ? null : environment.name(),
                artifactRows.stream()
                        .map(row -> new ArtifactSlotView(row.getPlatform(), row.getArtifactId()))
                        .toList(),
                targetRows.stream()
                        .map(row -> new TargetView(
                                row.getTenantId(),
                                targetRingCodes.getOrDefault(row.getRingId(), ring == null ? null : ring.getCode()),
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

    /** The environment codes of a page of releases (null ids skip — most releases name no environment yet). */
    private Map<UUID, String> environmentCodesOf(List<ReleaseEntity> page) {
        Map<UUID, String> codes = new LinkedHashMap<>();
        for (ReleaseEntity release : page) {
            UUID environmentId = release.getEnvironmentId();
            if (environmentId != null && !codes.containsKey(environmentId)) {
                environments.stateOf(environmentId).ifPresent(state -> codes.put(environmentId, state.code()));
            }
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
