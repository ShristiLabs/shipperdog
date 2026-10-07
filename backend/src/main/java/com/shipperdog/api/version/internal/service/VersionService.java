package com.shipperdog.api.version.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.CursorPageable;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.tenant.TenantContext;
import com.shipperdog.api.version.VersionArtifacts;
import com.shipperdog.api.version.VersionArtifacts.VersionArtifactSlot;
import com.shipperdog.api.version.VersionDistributionContributor;
import com.shipperdog.api.version.VersionDistributionGuard;
import com.shipperdog.api.version.VersionException;
import com.shipperdog.api.version.internal.entity.VersionEntity;
import com.shipperdog.api.version.internal.entity.VersionStatusHistoryEntity;
import com.shipperdog.api.version.internal.entity.VersionTagEntity;
import com.shipperdog.api.version.internal.repository.VersionRegistryRepository;
import com.shipperdog.api.version.internal.repository.VersionScopedRepository;
import com.shipperdog.api.version.internal.repository.VersionStatusHistoryRepository;
import com.shipperdog.api.version.internal.repository.VersionTagRepository;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
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
 * <p>Tags (issue #18, S4-02, FR-009): a version carries an arbitrary
 * set of case-normalized UPPER tags — the targeting primitive S7-02's
 * tag-based grants and policy rules later consume. Tag rows are plain
 * children of the seam-scoped version (V13): they are read and written
 * only after the version resolves inside the bound scope, and the
 * replace-set reconciliation is race-free by construction (targeted
 * deletes, insert-if-absent claims).
 *
 * <p>The lifecycle and permanent history (issue #19, S4-03, FR-008,
 * INV-004/INV-006): a version is born DRAFT and may reach either
 * terminal state — ARCHIVED (read-only, out of the default listing,
 * still fully readable) via the archive action, or REVOKED (new
 * downloads blocked, history preserved) via the revoke-distribution
 * action, whose mandatory reason feeds both the audit event (FR-024)
 * and the status timeline. Both terminals are final; nothing ever
 * deletes a version or its children (PRD P5). INV-004's artifact-set
 * freeze is the {@link VersionDistributionGuard} this service
 * implements: terminal status, or a distribution record reported by
 * any registered {@link VersionDistributionContributor} (E7 grants and
 * M2 releases when they land), freezes the artifact set — the check
 * survives each later distribution model because the version module
 * asks the seam, never the foreign tables. The chronological timeline
 * ({@code version_status_history}, V14) is the history query's spine:
 * statuses with timestamps and the revoke reason, plus the
 * chronological artifact list E5 fills.
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
public class VersionService implements VersionDistributionGuard {

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

    /**
     * The tag vocabulary (issue #18 AC1, FR-009): case-normalized UPPER,
     * one to fifty characters — a letter or digit first, then letters,
     * digits, underscore, or hyphen. The reserved words (QA, UAT, BETA,
     * PILOT, STABLE, PRODUCTION, LEGACY) are suggestions surfaced in the
     * OpenAPI description, never the enforced options (AC3); the V13
     * {@code ck_version_tags_tag_shape} pins the same shape at the
     * database.
     */
    private static final Pattern TAG_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,49}$");

    /**
     * The per-version tag cap (issue #18): tags are a targeting
     * vocabulary, not a free-form label dump — twenty distinct values is
     * far beyond any real cohort and bounds the join rows per version.
     */
    private static final int MAX_TAGS_PER_VERSION = 20;

    /**
     * The revoke reason cap (issue #19 AC3): the reason feeds the audit
     * detail and the timeline row; a bounded paragraph is the whole
     * need, and the V14 {@code ck_version_status_history_reason_length}
     * check pins the same width at the database.
     */
    private static final int MAX_REASON_LENGTH = 1000;

    /**
     * Transition attempts before the deterministic re-read conflict:
     * statuses only move forward (DRAFT/ACTIVE -> ARCHIVED -> REVOKED),
     * so two is the mathematical bound against any interleaving; three
     * is the same bound with headroom.
     */
    private static final int MAX_TRANSITION_ATTEMPTS = 3;

    /** Newest first: sort key descending, id descending as the tiebreak. */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Direction.DESC, "sortKey").and(Sort.by(Sort.Direction.DESC, "id"));

    private final VersionScopedRepository versions;
    private final VersionRegistryRepository registry;
    private final VersionTagRepository tags;
    private final VersionStatusHistoryRepository statusHistory;
    private final ApplicationAccess applications;
    private final AuditEventPublisher audit;
    private final Clock clock;
    private final TraceAttributes trace;
    private final ObjectProvider<VersionDistributionContributor> distributionContributors;
    private final ObjectProvider<VersionArtifacts> artifactsContributor;

    VersionService(
            VersionScopedRepository versions,
            VersionRegistryRepository registry,
            VersionTagRepository tags,
            VersionStatusHistoryRepository statusHistory,
            ApplicationAccess applications,
            AuditEventPublisher audit,
            Clock clock,
            TraceAttributes trace,
            ObjectProvider<VersionDistributionContributor> distributionContributors,
            ObjectProvider<VersionArtifacts> artifactsContributor) {
        this.versions = versions;
        this.registry = registry;
        this.tags = tags;
        this.statusHistory = statusHistory;
        this.applications = applications;
        this.audit = audit;
        this.clock = clock;
        this.trace = trace;
        this.distributionContributors = distributionContributors;
        this.artifactsContributor = artifactsContributor;
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
        recordStatus(saved.getId(), VersionEntity.Status.DRAFT, null);
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
        return detailOf(saved, application.platforms(), Set.of(), List.of());
    }

    // -------------------------------------------------------------------------
    // Reads: listing (AC2) and detail (AC2, AC4)
    // -------------------------------------------------------------------------

    /**
     * The per-application listing (AC2 + issue #19 AC2): newest first by
     * the normalized semver key, standard limit/cursor pagination, every
     * item carrying its tags (issue #18, sorted UPPER) and its artifact
     * slots grouped by platform (empty until E5). The optional
     * {@code tag} filter (issue #18 AC2) narrows the list to versions
     * carrying that tag — case-insensitive, normalized to the stored
     * UPPER form before the query, composed with the same scope and
     * ordering. The default listing is the live working set (DRAFT and
     * the M2-reserved ACTIVE); the terminal states are history, not the
     * working surface — ARCHIVED returns with {@code include=archived}
     * (issue #19 AC2) and REVOKED with {@code include=revoked}
     * (INV-006: revoked versions stay listable and readable, they are
     * only out of the default surface and blocked from new
     * downloads). The two flags compose.
     */
    @Transactional(readOnly = true)
    public Page<VersionView> list(UUID applicationId, String tag, String include, long offset, int limit) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        String normalizedTag = normalizedTagFilter(tag);
        Specification<VersionEntity> scope = ofApplication(applicationId).and(statusIn(statusesOf(include)));
        if (normalizedTag != null) {
            scope = scope.and(hasTag(normalizedTag));
        }
        Page<VersionEntity> found = versions.findAllScoped(scope, new CursorPageable(offset, limit, NEWEST_FIRST));
        Map<UUID, Set<String>> tagsByVersion = tagsOfVersions(
                found.getContent().stream().map(VersionEntity::getId).toList());
        Map<UUID, List<VersionArtifactSlot>> artifactsByVersion = artifactsOfVersions(
                found.getContent().stream().map(VersionEntity::getId).toList());
        return found.map(version -> viewOf(
                version,
                application.platforms(),
                tagsByVersion.getOrDefault(version.getId(), Set.of()),
                artifactsByVersion.getOrDefault(version.getId(), List.of())));
    }

    /**
     * The version detail (AC2 + AC4): tags (issue #18), artifact slots
     * grouped by platform (empty in M1) and the release-history section
     * (PRD 8.6 "a version can exist without being released" — releases
     * are M2, so the section exists and is empty). A version id that
     * does not belong to the addressed application is the standard
     * concealed miss, indistinguishable from an unknown id.
     */
    @Transactional(readOnly = true)
    public VersionDetail get(UUID applicationId, UUID versionId) {
        VersionEntity version = scopedVersionOf(applicationId, versionId);
        return detailOf(
                version,
                profileOf(applicationId).platforms(),
                tagsOfVersion(version.getId()),
                artifactsOfVersions(List.of(version.getId())).getOrDefault(version.getId(), List.of()));
    }

    // -------------------------------------------------------------------------
    // Lifecycle: archive, revoke distribution, history (issue #19, S4-03)
    // -------------------------------------------------------------------------

    /**
     * Archives the version (issue #19 AC2, FR-008): the soft terminal
     * state — read-only from then on (tags and, later, artifact uploads
     * are rejected 409), out of the default listing, back with
     * {@code include=archived}, and fully readable forever (PRD P5).
     * Reachable from DRAFT or the M2-reserved ACTIVE; an already
     * archived version answers the precise 409, and a revoked one is
     * past the stronger terminal. One real transition, one
     * {@code VERSION_ARCHIVED} audit event, one timeline row; a repeat
     * is a conflict, not a second event.
     */
    @Transactional
    public VersionDetail archive(UUID actorId, UUID applicationId, UUID versionId) {
        return transition(
                actorId,
                applicationId,
                versionId,
                VersionEntity.Status.ARCHIVED,
                null,
                AuditEventType.VERSION_ARCHIVED);
    }

    /**
     * Revokes the version's distribution (issue #19 AC3, FR-008,
     * INV-006/INV-007): the strongest terminal state — new downloads of
     * this version are blocked (E7's decision API consumes the status;
     * within this version's distribution scope, nothing narrower exists
     * in M1) while everything stays exactly where it was: no version,
     * tag, timeline row, or (later) artifact is deleted, and reads
     * remain fully authorized. The reason is mandatory and feeds both
     * the {@code VERSION_DISTRIBUTION_REVOKED} audit event (FR-024:
     * every security-sensitive operation carries its reason) and the
     * timeline row, so the takedown is self-explaining history.
     * Reachable from DRAFT, ACTIVE, or ARCHIVED (a compliance takedown
     * of an already-retired version is exactly the case); a revoked
     * version answers the precise 409. Not idempotency-keyed, like
     * every transition: the state itself is the replay answer.
     */
    @Transactional
    public VersionDetail revokeDistribution(UUID actorId, UUID applicationId, UUID versionId, String reason) {
        String validatedReason = requireReason(reason);
        return transition(
                actorId,
                applicationId,
                versionId,
                VersionEntity.Status.REVOKED,
                validatedReason,
                AuditEventType.VERSION_DISTRIBUTION_REVOKED);
    }

    /**
     * The version's audit history (issue #19 AC5, FR-008 "view release
     * history" kernel): the version summary plus its chronological
     * status timeline — creation's DRAFT row, every archive, every
     * revoke with its reason — plus the chronological artifact list
     * (empty until E5 uploads binaries; the shape lands with S5-01).
     * The Compliance Reviewer's roll-forward question — what was this
     * version, when did it move, and why was it pulled — is answered
     * from this one read, and the answer never expires (INV-006).
     */
    @Transactional(readOnly = true)
    public VersionHistoryView history(UUID applicationId, UUID versionId) {
        VersionEntity version = scopedVersionOf(applicationId, versionId);
        List<StatusTransitionView> timeline = new ArrayList<>();
        for (VersionStatusHistoryEntity row : statusHistory.findByVersionIdOrderByOccurredAtAscIdAsc(version.getId())) {
            timeline.add(new StatusTransitionView(row.getStatus().name(), row.getReason(), row.getOccurredAt()));
        }
        List<VersionArtifactSlot> artifacts =
                artifactsOfVersions(List.of(version.getId())).getOrDefault(version.getId(), List.of());
        return new VersionHistoryView(
                version.getId(),
                version.getApplicationId(),
                version.getVersionName(),
                version.getStatus().name(),
                version.getCreatedAt(),
                version.getUpdatedAt(),
                List.copyOf(timeline),
                List.copyOf(artifacts));
    }

    /**
     * INV-004's freeze authority (issue #19 AC1), the
     * {@link VersionDistributionGuard} implementation E5's upload path
     * (S5-01) calls before adding any artifact: a version in a terminal
     * lifecycle state, or carrying any distribution record reported by
     * a registered {@link VersionDistributionContributor} (the E7
     * download grants and M2 releases when they land — none exist in
     * the M1 kernel), has its artifact set frozen. The story's
     * technical note is the design: the version module asks the
     * contributor seam instead of reading foreign tables, so the check
     * survives each later distribution model unchanged. Read-only —
     * freezing is a rejection, not a mutation.
     */
    @Override
    @Transactional(readOnly = true)
    public void requireArtifactsMutable(UUID applicationId, UUID versionId) {
        VersionEntity version = scopedVersionOf(applicationId, versionId);
        if (version.getStatus() == VersionEntity.Status.ARCHIVED
                || version.getStatus() == VersionEntity.Status.REVOKED) {
            throw new VersionException(
                    VersionException.Code.VERSION_IMMUTABLE,
                    "version is " + version.getStatus().name().toLowerCase(Locale.ROOT)
                            + "; its artifact set is frozen");
        }
        for (VersionDistributionContributor contributor :
                distributionContributors.stream().toList()) {
            if (contributor.hasDistributionRecords(version.getId())) {
                throw new VersionException(
                        VersionException.Code.VERSION_IMMUTABLE,
                        "version has distribution records; its artifact set is frozen");
            }
        }
    }

    /**
     * One real lifecycle transition (issue #19), serialized against
     * concurrent writers by the database compare-and-set: the row moves
     * only while its committed status is still the source state this
     * attempt read, so two racing transitions can never both commit —
     * without the CAS, a stale archive landing after a revoke would
     * silently un-revoke a pulled version, and even the harmless
     * same-target race would double the audit and timeline rows. The
     * winner appends the timeline row (with the revoke reason when
     * present), audits with before/after, and answers the refreshed
     * detail; a loser re-reads the committed state and either retries
     * the still-legal crossing (ARCHIVED to REVOKED, the one
     * sanctioned direction) or answers the precise conflict — statuses
     * only move forward, so the loop terminates in at most two
     * attempts against any interleaving.
     */
    private VersionDetail transition(
            UUID actorId,
            UUID applicationId,
            UUID versionId,
            VersionEntity.Status to,
            String reason,
            AuditEventType event) {
        for (int attempt = 0; attempt < MAX_TRANSITION_ATTEMPTS; attempt++) {
            VersionEntity version = scopedVersionOf(applicationId, versionId);
            VersionEntity.Status from = version.getStatus();
            if (from == to) {
                throw transitionRepeat(to);
            }
            if (!reachable(from, to)) {
                throw transitionConflict(from, to);
            }
            if (statusHistory.transitionStatusIfCurrent(version.getId(), from.name(), to.name()) == 1) {
                recordStatus(version.getId(), to, reason);
                trace.application(applicationId);
                Map<String, String> detail = new LinkedHashMap<>();
                detail.put("versionName", version.getVersionName());
                detail.put("fromStatus", from.name());
                detail.put("toStatus", to.name());
                if (reason != null) {
                    detail.put("reason", reason);
                }
                audit(event, actorId, version.getOrganizationId(), version.getTenantId(), applicationId, detail);
                VersionEntity fresh = scopedVersionOf(applicationId, version.getId());
                return detailOf(
                        fresh,
                        profileOf(applicationId).platforms(),
                        tagsOfVersion(fresh.getId()),
                        artifactsOfVersions(List.of(fresh.getId())).getOrDefault(fresh.getId(), List.of()));
            }
        }
        // Unreachable against forward-only statuses; the deterministic
        // answer if it ever were reached is the current state's precise
        // conflict, re-read fresh.
        throw transitionConflict(scopedVersionOf(applicationId, versionId).getStatus(), to);
    }

    /**
     * The transition graph (issue #19): DRAFT and the M2-reserved
     * ACTIVE archive; DRAFT, ACTIVE, and ARCHIVED revoke (the
     * compliance takedown of an already-retired version); both
     * terminals are final, and nothing exits REVOKED.
     */
    private static boolean reachable(VersionEntity.Status from, VersionEntity.Status to) {
        return switch (to) {
            case ARCHIVED -> from == VersionEntity.Status.DRAFT || from == VersionEntity.Status.ACTIVE;
            case REVOKED ->
                from == VersionEntity.Status.DRAFT
                        || from == VersionEntity.Status.ACTIVE
                        || from == VersionEntity.Status.ARCHIVED;
            case DRAFT, ACTIVE -> false;
        };
    }

    /** A repeat of the terminal the version already holds: the precise 409. */
    private static VersionException transitionRepeat(VersionEntity.Status to) {
        if (to == VersionEntity.Status.ARCHIVED) {
            return new VersionException(VersionException.Code.VERSION_ARCHIVED, "version is already archived");
        }
        return new VersionException(VersionException.Code.VERSION_REVOKED, "version distribution is already revoked");
    }

    /** A transition the source state forbids: the precise 409 naming the wall. */
    private static VersionException transitionConflict(VersionEntity.Status from, VersionEntity.Status to) {
        if (from == VersionEntity.Status.REVOKED) {
            return new VersionException(
                    VersionException.Code.VERSION_REVOKED,
                    to == VersionEntity.Status.ARCHIVED
                            ? "a revoked version cannot be archived"
                            : "a revoked version is final");
        }
        return new VersionException(VersionException.Code.VERSION_ARCHIVED, "the version cannot reach " + to);
    }

    /** Appends one timeline row (V14); the service clock is the timeline's clock. */
    private void recordStatus(UUID versionId, VersionEntity.Status status, String reason) {
        statusHistory.save(new VersionStatusHistoryEntity(versionId, status, reason, Instant.now(clock)));
    }

    /**
     * The revoke reason (issue #19 AC3): required, trimmed, 1..1000
     * characters — mandatory because it feeds the audit trail (FR-024)
     * and the timeline; a missing or blank reason is a broken request,
     * not an unlucky one.
     */
    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw invalid("reason is required and must not be blank");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw invalid("reason is at most " + MAX_REASON_LENGTH + " characters");
        }
        return trimmed;
    }

    // -------------------------------------------------------------------------
    // Tags (issue #18, S4-02, FR-009)
    // -------------------------------------------------------------------------

    /**
     * Replaces the version's tag set (issue #18 AC1/AC4): the request
     * list is trimmed, case-normalized to UPPER, deduplicated, and
     * validated against the tag vocabulary; the stored set is then
     * reconciled — tags no longer requested are removed, tags newly
     * requested are claimed — so a PUT is naturally idempotent and the
     * empty list clears the set. Only the join rows change: INV-004
     * freezes a version's binaries, and a tag is not a binary — the
     * version row itself (name, status, timestamps) is never written by
     * this path.
     *
     * <p>Concurrent replace-sets on one version are serialized by the
     * transaction-scoped advisory lock taken the moment the version
     * resolves ({@link VersionTagRepository#lockVersionTagMutations}):
     * the second writer blocks until the first commits, then reconciles
     * against the committed baseline — last writer's set wins exactly,
     * never a union of the two (insert-if-absent alone would merge
     * them), and two writers sending the same set collapse to a single
     * audit event. Within a serialized writer the reconciliation is
     * race-free by construction: the removal is a targeted delete
     * (absent rows are a no-op) and each claim is an insert-if-absent
     * whose conflict target is the composite primary key, so no
     * constraint violation ever surfaces to a caller.
     *
     * <p>A real change is audited as {@code VERSION_TAGS_CHANGED} with
     * the added and removed lists in the detail (comma-joined; the
     * vocabulary carries no commas); a PUT that changes nothing writes
     * no audit event and no rows — one event per real transition, the
     * authorization-flip precedent. The version is resolved scoped
     * first: a version id of another application (or another scope) is
     * the standard concealed miss.
     */
    @Transactional
    public TagsView replaceTags(UUID actorId, UUID applicationId, UUID versionId, List<String> request) {
        VersionEntity version = scopedVersionOf(applicationId, versionId);
        requireMutableMetadata(version);
        tags.lockVersionTagMutations(version.getId());
        Set<String> requested = normalizeTags(request);
        Set<String> before = tagsOfVersion(version.getId());
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(requested);
        Set<String> added = new TreeSet<>(requested);
        added.removeAll(before);
        if (!removed.isEmpty()) {
            tags.removeTags(version.getId(), removed);
        }
        for (String tag : added) {
            tags.insertTagIfAbsent(version.getId(), tag);
        }
        Set<String> after = tagsOfVersion(version.getId());
        if (!added.isEmpty() || !removed.isEmpty()) {
            trace.application(applicationId);
            Map<String, String> detail = new LinkedHashMap<>();
            detail.put("versionName", version.getVersionName());
            if (!added.isEmpty()) {
                detail.put("added", String.join(",", added));
            }
            if (!removed.isEmpty()) {
                detail.put("removed", String.join(",", removed));
            }
            audit(
                    AuditEventType.VERSION_TAGS_CHANGED,
                    actorId,
                    version.getOrganizationId(),
                    version.getTenantId(),
                    applicationId,
                    detail);
        }
        return new TagsView(List.copyOf(after));
    }

    /** The replace response: the version's resulting tags, sorted UPPER. */
    public record TagsView(List<String> tags) {}

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    /**
     * The list-item view: registry fields plus the version's tags
     * (issue #18, sorted UPPER) and the per-platform artifact slots.
     * The slot value is a list (a version may carry re-uploads of the
     * same platform — issue #20's INV-003 rule that a re-upload is a
     * NEW artifact); the chronological order is the history read's
     * order, newest last. Slots the application has not registered
     * carry no key; an artifact of an unregistered platform cannot
     * exist (upload validates against the application's platform set).
     */
    public record VersionView(
            UUID id,
            UUID applicationId,
            String versionName,
            String status,
            List<String> tags,
            Instant createdAt,
            Instant updatedAt,
            Map<String, List<VersionArtifactSlot>> artifactsByPlatform) {

        static VersionView from(
                VersionEntity version, List<String> platforms, Set<String> tags, List<VersionArtifactSlot> artifacts) {
            return new VersionView(
                    version.getId(),
                    version.getApplicationId(),
                    version.getVersionName(),
                    version.getStatus().name(),
                    List.copyOf(tags),
                    version.getCreatedAt(),
                    version.getUpdatedAt(),
                    slotsOf(platforms, artifacts));
        }
    }

    /**
     * The detail view: the list-item shape plus the release history
     * (AC4).
     */
    public record VersionDetail(
            UUID id,
            UUID applicationId,
            String versionName,
            String status,
            List<String> tags,
            Instant createdAt,
            Instant updatedAt,
            Map<String, List<VersionArtifactSlot>> artifactsByPlatform,
            List<Object> releaseHistory) {

        static VersionDetail from(
                VersionEntity version, List<String> platforms, Set<String> tags, List<VersionArtifactSlot> artifacts) {
            return new VersionDetail(
                    version.getId(),
                    version.getApplicationId(),
                    version.getVersionName(),
                    version.getStatus().name(),
                    List.copyOf(tags),
                    version.getCreatedAt(),
                    version.getUpdatedAt(),
                    slotsOf(platforms, artifacts),
                    List.of());
        }
    }

    /**
     * One timeline entry of the history view (issue #19 AC5): the
     * status the version took and when; the revoke transition's
     * mandatory reason rides its row, every other transition carries
     * none.
     */
    public record StatusTransitionView(String status, String reason, Instant occurredAt) {}

    /**
     * The history view (issue #19 AC5, FR-008): the version summary and
     * its chronological status timeline, plus the chronological
     * artifact list — filled since S5-01 (issue #20): every artifact
     * the version ever carried, upload order, exactly as INV-003 and
     * INV-006 keep them (nothing is ever replaced or removed). Tags
     * are deliberately absent: they are mutable metadata (S4-02), not
     * history, and live in the detail.
     */
    public record VersionHistoryView(
            UUID id,
            UUID applicationId,
            String versionName,
            String status,
            Instant createdAt,
            Instant updatedAt,
            List<StatusTransitionView> statusHistory,
            List<VersionArtifactSlot> artifacts) {}

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
                .orElseThrow(() ->
                        new VersionException(VersionException.Code.APPLICATION_NOT_FOUND, "application not found"));
    }

    /** Every version of one application, composed with the seam's scope. */
    private static Specification<VersionEntity> ofApplication(UUID applicationId) {
        return (root, query, builder) -> builder.equal(root.get("applicationId"), applicationId);
    }

    /**
     * Versions carrying the normalized tag (issue #18 AC2): a subquery
     * over the tag join rows, composed with the seam's scope on the
     * outer version — the filter never widens the bound scope, it only
     * narrows the versions inside it (tags are children of the scoped
     * parent, V13).
     */
    private static Specification<VersionEntity> hasTag(String tag) {
        return (root, query, builder) -> {
            Subquery<UUID> tagged = query.subquery(UUID.class);
            Root<VersionTagEntity> tagRow = tagged.from(VersionTagEntity.class);
            tagged.select(tagRow.get("versionId")).where(builder.equal(tagRow.get("tag"), tag));
            return builder.in(root.get("id")).value(tagged);
        };
    }

    private static Specification<VersionEntity> versionNameIs(String versionName) {
        return (root, query, builder) -> builder.equal(root.get("versionName"), versionName);
    }

    /**
     * The listing's status window (issue #19 AC2): the working set by
     * default, widened by the comma-separated {@code include} flags —
     * {@code archived} re-adds ARCHIVED, {@code revoked} re-adds
     * REVOKED, and the two compose. An unknown flag is a 400: a typo'd
     * {@code include=archvied} silently narrowing nothing (the default
     * window would still apply) is the kind of quiet wrong answer the
     * platform refuses.
     */
    private static Set<VersionEntity.Status> statusesOf(String include) {
        Set<VersionEntity.Status> statuses = EnumSet.of(VersionEntity.Status.DRAFT, VersionEntity.Status.ACTIVE);
        if (include == null || include.isBlank()) {
            return statuses;
        }
        for (String raw : include.split(",")) {
            String flag = raw.trim().toLowerCase(Locale.ROOT);
            switch (flag) {
                case "archived" -> statuses.add(VersionEntity.Status.ARCHIVED);
                case "revoked" -> statuses.add(VersionEntity.Status.REVOKED);
                default ->
                    throw invalid(
                            "include accepts only 'archived' and 'revoked' (comma-separated; e.g. include=archived,revoked)");
            }
        }
        return statuses;
    }

    private static Specification<VersionEntity> statusIn(Set<VersionEntity.Status> statuses) {
        return (root, query, builder) -> root.get("status").in(statuses);
    }

    /**
     * The metadata-mutation guard (issue #19 AC2: archived versions are
     * read-only; a revoked one is past the stronger terminal): tags are
     * the only mutable metadata in M1, and they stop being mutable the
     * moment the version reaches either terminal state — the precise
     * code names which wall was hit.
     */
    private static void requireMutableMetadata(VersionEntity version) {
        if (version.getStatus() == VersionEntity.Status.ARCHIVED) {
            throw new VersionException(VersionException.Code.VERSION_ARCHIVED, "an archived version is read-only");
        }
        if (version.getStatus() == VersionEntity.Status.REVOKED) {
            throw new VersionException(VersionException.Code.VERSION_REVOKED, "a revoked version is read-only");
        }
    }

    /**
     * The version resolved scoped (the seam's bound scope) and verified
     * against the addressed application: any version id that is unknown,
     * out of scope, or belonging to a different application is the
     * standard concealed miss. The platform-administrator wildcard
     * reaches an UNKNOWN application's routes with no context bound
     * (the application interceptor binds only existing applications,
     * and the seam is fail-closed); the empty context renders the
     * application miss, never a 500 (the mustFindGroup precedent,
     * S7-05 issue #34 folding the #27 candidate).
     */
    private VersionEntity scopedVersionOf(UUID applicationId, UUID versionId) {
        if (TenantContext.current().isEmpty()) {
            throw new VersionException(VersionException.Code.APPLICATION_NOT_FOUND, "application not found");
        }
        VersionEntity version = versions.findScoped(versionId)
                .orElseThrow(() -> new VersionException(VersionException.Code.NOT_FOUND, "version not found"));
        if (!version.getApplicationId().equals(applicationId)) {
            throw new VersionException(VersionException.Code.NOT_FOUND, "version not found");
        }
        return version;
    }

    /** The version's tags, sorted UPPER (one query; the join rows of one parent). */
    private Set<String> tagsOfVersion(UUID versionId) {
        return tagsOfVersions(List.of(versionId)).getOrDefault(versionId, Set.of());
    }

    /**
     * The tags of many versions in one query: the listing page's bulk
     * read, so a page costs one tag query regardless of page size (no
     * per-version fan-out).
     */
    private Map<UUID, Set<String>> tagsOfVersions(Collection<UUID> versionIds) {
        Map<UUID, Set<String>> byVersion = new LinkedHashMap<>();
        if (versionIds.isEmpty()) {
            return byVersion;
        }
        for (VersionTagEntity row : tags.findByVersionIdIn(versionIds)) {
            byVersion.computeIfAbsent(row.getVersionId(), id -> new TreeSet<>()).add(row.getTag());
        }
        return byVersion;
    }

    /**
     * Normalizes and validates a whole request list (issue #18 AC1):
     * trim, UPPER, drop duplicates after normalization, enforce the
     * vocabulary and the per-version cap — the raw request size first
     * (the #18 reviewer's early guard: an oversized list is rejected
     * before any per-element work), the post-dedup size second, since
     * normalization can only shrink it. Null elements and blank values
     * are rejections, not silent drops — a client sending them is
     * broken, not unlucky.
     */
    private static Set<String> normalizeTags(List<String> request) {
        if (request == null) {
            throw invalid("tags is required");
        }
        if (request.size() > MAX_TAGS_PER_VERSION) {
            throw invalid("at most " + MAX_TAGS_PER_VERSION + " tags per version");
        }
        Set<String> normalized = new TreeSet<>();
        for (String raw : request) {
            normalized.add(normalizeTag(raw, "tags"));
        }
        if (normalized.size() > MAX_TAGS_PER_VERSION) {
            throw invalid("at most " + MAX_TAGS_PER_VERSION + " tags per version");
        }
        return normalized;
    }

    /** The {@code tag} list filter of AC2: same normalization, same rules. */
    private static String normalizedTagFilter(String tag) {
        if (tag == null) {
            return null;
        }
        return normalizeTag(tag, "tag filter");
    }

    /**
     * One tag value, trimmed and case-normalized to UPPER (AC1), then
     * validated against the vocabulary the database CHECK pins — the
     * service answers the common case with the precise message; the V13
     * constraint is the authority for any non-service writer.
     */
    private static String normalizeTag(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw invalid(field + " must not contain empty values");
        }
        String tag = raw.trim().toUpperCase(Locale.ROOT);
        if (!TAG_PATTERN.matcher(tag).matches()) {
            throw invalid(field + " values are 1..50 characters of letters, digits, underscore, or hyphen"
                    + " (uppercased; e.g. BETA or INTERNAL_PILOT)");
        }
        return tag;
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

    /**
     * The artifact slots of the requested versions through the read-side
     * seam (issue #20): the artifact module's {@link VersionArtifacts}
     * implementation answers inside the request's bound scope; with no
     * implementation registered the map is empty and every slot renders
     * empty — the same empty-safe contributor default as the
     * distribution seam.
     */
    private Map<UUID, List<VersionArtifactSlot>> artifactsOfVersions(Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        return artifactsContributor.stream()
                .findFirst()
                .map(contributor -> contributor.byVersionIds(versionIds))
                .orElseGet(Map::of);
    }

    /**
     * Groups the chronological artifact list into the per-platform
     * slots, keyed by the application's registered platform order: the
     * detail's promise since S4-01 — the slot list per platform is
     * exactly the grouped surface the story asks for, newest last.
     */
    private static Map<String, List<VersionArtifactSlot>> slotsOf(
            List<String> platforms, List<VersionArtifactSlot> artifacts) {
        Map<String, List<VersionArtifactSlot>> slots = new LinkedHashMap<>();
        for (String platform : platforms) {
            slots.put(platform, new ArrayList<>());
        }
        for (VersionArtifactSlot artifact : artifacts) {
            slots.computeIfAbsent(artifact.platform(), platform -> new ArrayList<>())
                    .add(artifact);
        }
        return slots;
    }

    private VersionView viewOf(
            VersionEntity version, List<String> platforms, Set<String> tags, List<VersionArtifactSlot> artifacts) {
        return VersionView.from(version, platforms, tags, artifacts);
    }

    private VersionDetail detailOf(
            VersionEntity version, List<String> platforms, Set<String> tags, List<VersionArtifactSlot> artifacts) {
        return VersionDetail.from(version, platforms, tags, artifacts);
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
