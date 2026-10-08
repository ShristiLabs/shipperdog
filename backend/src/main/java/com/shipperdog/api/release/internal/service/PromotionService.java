package com.shipperdog.api.release.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.gate.ReleaseGateEvaluation;
import com.shipperdog.api.release.PromotionApprovalSource;
import com.shipperdog.api.release.ReleaseException;
import com.shipperdog.api.release.ReleasePromotionPolicy;
import com.shipperdog.api.release.RingEligibility.Admission;
import com.shipperdog.api.release.internal.entity.ReleaseEntity;
import com.shipperdog.api.release.internal.entity.ReleasePromotionEntity;
import com.shipperdog.api.release.internal.entity.ReleaseTargetEntity;
import com.shipperdog.api.release.internal.entity.RingEntity;
import com.shipperdog.api.release.internal.repository.ReleasePromotionRepository;
import com.shipperdog.api.release.internal.repository.ReleaseScopedRepository;
import com.shipperdog.api.release.internal.repository.ReleaseTargetRepository;
import com.shipperdog.api.release.internal.repository.RingRepository;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The promotion engine (issue #44, S10-05, FR-018, PRD 8.10/17/18/33):
 * the governed ring-to-ring move — one action, {@code promote}, that
 * evaluates the TARGET ring's rules (its quality gates through the
 * {@link ReleaseGateEvaluation} port, its approval policy through the
 * {@link PromotionApprovalSource} leg, its eligibility through the
 * widening seam, and every registered {@link ReleasePromotionPolicy}),
 * and only when every leg passes moves the release's targets one ring
 * forward in the organization's effective order, records the hop on
 * the append-only promotions timeline, audits with the actor, and —
 * when the hop lands the final ring for every target — carries the
 * release across PRD 17's ACTIVE to PROMOTED edge with the state
 * machine's own ceremony.
 *
 * <p><strong>One evaluation, three surfaces</strong> (the issue's
 * technical note): {@link #precheck} is the same evaluation the
 * promote act runs, answered as a read — the blocker list the 409
 * carries is byte-for-byte the list the precheck renders, so the
 * console's "why can't I promote" screen and the CLI's refusal output
 * are the API's own answer, never a reimplementation.
 *
 * <p><strong>Per-target rings, uniform hops.</strong> Every target
 * tenant carries its own {@code ring_id}; promotion moves a SET of
 * targets — all of them, or the selective set the request names
 * (staged-rollout groundwork: Acme first, Contoso later, AC3). A move
 * set must sit on ONE ring (the effective order's position+1 is a
 * single hop); staggered sets — the deliberate result of selective
 * promotion — promote ring group by ring group, and the split refusal
 * says exactly that, naming each tenant's ring.
 *
 * <p><strong>INV-008 is absolute</strong> (PRD 9): the engine writes
 * ring ids, promotion rows, status history, audits, and events — never
 * an artifact column, never the manifest (frozen at APPROVED, served
 * stored). There is no code path from here to either surface, and the
 * integration suite pins the artifact SHA-256s and the manifest digest
 * unchanged across every hop.
 *
 * <p><strong>The skipping posture</strong> (AC6): the only hop is the
 * effective order's position+1; a request naming a farther ring
 * answers the skip refusal. Disallow is the DEFAULT — the relaxation
 * knob is a promotion policy registrant (S10-06's seam), and none
 * ships, so the documented posture stands for every organization.
 *
 * <p><strong>State interplay</strong> (PRD 17): promotion serves live
 * distribution — the release must be ACTIVE (or already PROMOTED, the
 * #60 walk's terminal: its hops continue widening rings without a
 * second status crossing). The ACTIVE to PROMOTED edge fires exactly
 * once, when a hop lands the final ring for EVERY target; COMPLETED
 * stays the operator's explicit crossing (the transitions route's
 * edge, unchanged).
 *
 * <p><strong>Transactions</strong>: this orchestration is deliberately
 * NOT one transaction — a refused promote answers 409 by throwing, and
 * the governed facts of the refusal (the approval round the attempt
 * opened, the evidence row it recorded) must survive that rollback.
 * The reads run per-repository; {@link PromotionWriter} owns the two
 * commits: the refused attempt's row in its own transaction
 * ({@code REQUIRES_NEW}), and the committed hop as ONE atomic
 * transaction that re-runs the precheck inside it (the transitions
 * route's shape — the evaluation and the compare-and-set serialize in
 * one transaction, so no leg's answer can go stale between the check
 * and the move).
 *
 * <p>Every route is release-addressed with the application query/
 * body-addressed permission target ({@code app:releases:manage} to
 * promote, {@code app:releases:read} for the reads), the release
 * surface's standing; the service establishes the owning scope through
 * the sanctioned executor once the application resolves, and a release
 * of another application is the standard concealed miss.
 */
@Service
public class PromotionService {

    /** The statuses promotion serves: live distribution (PRD 17), plus the crossing the engine itself performs. */
    private static final Set<ReleaseEntity.Status> PROMOTABLE_STATUSES =
            Set.of(ReleaseEntity.Status.ACTIVE, ReleaseEntity.Status.PROMOTED);

    private final ReleaseScopedRepository releases;
    private final ReleaseTargetRepository targets;
    private final ReleasePromotionRepository promotions;
    private final RingRepository rings;
    private final RingService ringService;
    private final RingEligibilityService eligibility;
    private final ReleaseGateEvaluation gateEvaluation;
    private final PromotionApprovalSource approvalLeg;
    private final ObjectProvider<ReleasePromotionPolicy> promotionPolicies;
    private final ApplicationAccess applications;
    private final TenantScopeExecutor scopeExecutor;
    private final PromotionWriter writer;
    private final Clock clock;

    PromotionService(
            ReleaseScopedRepository releases,
            ReleaseTargetRepository targets,
            ReleasePromotionRepository promotions,
            RingRepository rings,
            RingService ringService,
            RingEligibilityService eligibility,
            ReleaseGateEvaluation gateEvaluation,
            PromotionApprovalSource approvalLeg,
            ObjectProvider<ReleasePromotionPolicy> promotionPolicies,
            ApplicationAccess applications,
            TenantScopeExecutor scopeExecutor,
            PromotionWriter writer,
            Clock clock) {
        this.releases = releases;
        this.targets = targets;
        this.promotions = promotions;
        this.rings = rings;
        this.ringService = ringService;
        this.eligibility = eligibility;
        this.gateEvaluation = gateEvaluation;
        this.approvalLeg = approvalLeg;
        this.promotionPolicies = promotionPolicies;
        this.applications = applications;
        this.scopeExecutor = scopeExecutor;
        this.writer = writer;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // The promote act (AC1/AC2/AC3)
    // -------------------------------------------------------------------------

    /**
     * One promotion attempt (AC1/AC2): resolves the move set (every
     * target, or the selective set the body names), walks the
     * effective order to the next ring, runs the precheck — and when
     * any leg refuses, answers 409 with the COMPLETE blocker list,
     * every blocker named with its evidence, having first recorded the
     * refused attempt on the promotions timeline in its own committed
     * transaction (PRD 33's recorded evidence: the row and the approval
     * round the attempt opened survive the refusal). When every leg
     * passes, the hop commits atomically through {@link
     * PromotionWriter#commitHop} — the ring CAS, the timeline row, the
     * {@code RELEASE_PROMOTED} audit, the {@link
     * com.shipperdog.api.release.ReleasePromotedEvent} (S10-06's
     * hook), and — when the hop lands the final ring for every target
     * — the release's ACTIVE to PROMOTED crossing with the state
     * machine's own ceremony.
     */
    public PromotionResultView promote(UUID actorId, UUID applicationId, UUID releaseId, PromoteCommand command) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseEntity release = scopedReleaseOf(applicationId, releaseId);
            Instant occurredAt = Instant.now(clock);
            Hop hop = hopOf(release, command, actorId);
            // The engine's consult: opens the approval round a resolved
            // policy demands (its own committed transaction — the round
            // is a governed fact of the ATTEMPT, recorded even when the
            // attempt refuses).
            PromotionApprovalSource.Standing approval = approvalLeg.openRoundFor(
                    release.getId(), release.getOrganizationId(), hop.toRing().getId(), actorId);
            Precheck precheck = evaluate(release, hop, approval, actorId, ReleasePromotionPolicy.Purpose.ATTEMPT);
            if (!precheck.blockers().isEmpty()) {
                throw blocked(release, hop, precheck, actorId, occurredAt);
            }
            // The commit re-runs the precheck inside its transaction
            // (the transitions route's shape): a leg that went stale
            // between the read and the move answers the same 409, its
            // evidence row committed the same way.
            ReleasePromotionEntity record = writer.commitHop(
                    release,
                    hop.moveSet(),
                    hop.fromRing(),
                    hop.toRing(),
                    finalRingCodeOf(release),
                    actorId,
                    occurredAt,
                    () -> evaluate(
                                    release,
                                    hop,
                                    approvalLeg.standingOf(
                                            release.getId(),
                                            release.getOrganizationId(),
                                            hop.toRing().getId()),
                                    actorId,
                                    ReleasePromotionPolicy.Purpose.RE_COMMIT)
                            .blockers());
            boolean crossed = record != null && allTargetsAtFinalRing(release);
            String status =
                    scopedReleaseOf(applicationId, releaseId).getStatus().name();
            return new PromotionResultView(
                    release.getId(),
                    release.releaseRef(),
                    status,
                    hop.fromRing().getCode(),
                    hop.toRing().getCode(),
                    hop.tenants(),
                    crossed,
                    record.getId(),
                    PromotionResultView.PromotionStatus.COMPLETED,
                    Map.of(),
                    targetRingViewOf(release));
        });
    }

    /** The refused attempt's complete answer: the evidence row commits first, then the 409 carries the list. */
    private ReleaseException blocked(
            ReleaseEntity release, Hop hop, Precheck precheck, UUID actorId, Instant occurredAt) {
        writer.recordBlocked(
                release,
                hop.moveSet().get(0).getRingId(),
                hop.toRing().getId(),
                hop.tenants(),
                precheck.blockers(),
                actorId,
                occurredAt);
        return new ReleaseException(
                ReleaseException.Code.RELEASE_PROMOTION_BLOCKED,
                "the promotion to ring '" + hop.toRing().getCode() + "' is blocked",
                precheck.blockers());
    }

    // -------------------------------------------------------------------------
    // The precheck read (the same evaluation, three surfaces)
    // -------------------------------------------------------------------------

    /**
     * The promotion precheck as a READ (the issue's technical note):
     * the same evaluation the promote act runs — next ring, gate
     * states, approval standing, eligibility widening, policies — with
     * nothing written and no round opened (the approval leg reports
     * NOT_OPEN when a policy gates the ring and no round exists: the
     * promote act opens it). The blocker map here is byte-for-byte the
     * 409's {@code errors} member.
     */
    public PrecheckView precheck(UUID actorId, UUID applicationId, UUID releaseId) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseEntity release = scopedReleaseOf(applicationId, releaseId);
            Hop hop = hopOf(release, new PromoteCommand(null, null), actorId);
            Precheck precheck = evaluate(
                    release,
                    hop,
                    approvalLeg.standingOf(
                            release.getId(),
                            release.getOrganizationId(),
                            hop.toRing().getId()),
                    actorId,
                    ReleasePromotionPolicy.Purpose.PRECHECK);
            return precheck.viewOf();
        });
    }

    /**
     * The promotion history (AC5): the append-only timeline — every
     * COMPLETED hop with its two rings, moved tenants, and actor, and
     * every BLOCKED attempt with its recorded evidence — the record
     * the release dashboard renders beside the status timeline.
     */
    public PromotionHistoryView history(UUID actorId, UUID applicationId, UUID releaseId) {
        ApplicationAccess.ApplicationProfile application = profileOf(applicationId);
        return inScope(application, actorId, () -> {
            ReleaseEntity release = scopedReleaseOf(applicationId, releaseId);
            List<PromotionRecordView> rows = new ArrayList<>();
            for (ReleasePromotionEntity row : promotions.findByReleaseIdOrderByOccurredAtAscIdAsc(release.getId())) {
                rows.add(new PromotionRecordView(
                        row.getId(),
                        ringCodeOf(row.getFromRingId()),
                        row.getToRingId() == null ? null : ringCodeOf(row.getToRingId()),
                        row.getTenants() == null
                                ? List.of()
                                : row.getTenants().stream()
                                        .map(UUID::fromString)
                                        .toList(),
                        row.getStatus().name(),
                        row.getBlockers() == null ? Map.of() : row.getBlockers(),
                        row.getActorId(),
                        row.getOccurredAt()));
            }
            return new PromotionHistoryView(
                    release.getId(), release.releaseRef(), release.getStatus().name(), List.copyOf(rows));
        });
    }

    // -------------------------------------------------------------------------
    // The hop: move set, current ring, next ring, skip posture
    // -------------------------------------------------------------------------

    /** The resolved hop: the move set, the ring it sits on, and the next ring in the effective order. */
    record Hop(List<ReleaseTargetEntity> moveSet, RingEntity fromRing, RingEntity toRing, List<UUID> tenants) {}

    /**
     * Resolves the hop (the #43 follow-up: the walk is the EFFECTIVE
     * order — position, organization ring first at a tie, code — never
     * raw positions): the move set (the body's selective tenants, or
     * every target), the ring that set sits on (uniform — a staggered
     * set is the split refusal naming each tenant's ring), and the
     * position+1 ring. No next ring is the final-ring refusal; a body
     * naming any ring other than the next is the skip refusal (the
     * default posture; S10-06's registrant may relax it) or the 400
     * when the name is not the vocabulary's at all. Pre-ring refusals
     * (state, split, final, skip) answer the 409 alone: they are
     * request-shape and state conflicts, not governed evidence — the
     * BLOCKED timeline rows belong to precheck refusals, which name
     * the ring they were refused entry to.
     */
    private Hop hopOf(ReleaseEntity release, PromoteCommand command, UUID actorId) {
        if (!PROMOTABLE_STATUSES.contains(release.getStatus())) {
            throw invalidState(release);
        }
        List<ReleaseTargetEntity> targetRows = targets.findByReleaseIdOrderByTenantIdAsc(release.getId());
        if (targetRows.isEmpty()) {
            // Not reachable (creation demands a target set); the honest
            // answer if the data ever drifted is the final refusal.
            throw new ReleaseException(
                    ReleaseException.Code.RELEASE_PROMOTION_RING_FINAL, "the targets already sit on the final ring");
        }
        List<ReleaseTargetEntity> moveSet = moveSetOf(targetRows, command);
        Set<String> effectiveCodes = new LinkedHashSet<>();
        for (ReleaseTargetEntity target : moveSet) {
            effectiveCodes.add(effectiveCodeOf(release, target.getRingId()));
        }
        if (effectiveCodes.size() > 1) {
            throw ringSplit(release, moveSet);
        }
        String fromCode = effectiveCodes.iterator().next();
        List<RingEntity> order = ringService.effectiveVocabularyOf(release.getOrganizationId());
        int position = ringPositionOf(order, fromCode);
        if (position == -1) {
            // The stored ring left the vocabulary (not reachable while
            // the reference exists — the delete guard refuses); the
            // fail-closed answer is the split refusal's shape.
            throw ringSplit(release, moveSet);
        }
        if (position == order.size() - 1) {
            throw new ReleaseException(
                    ReleaseException.Code.RELEASE_PROMOTION_RING_FINAL,
                    "the targets already sit on the final ring",
                    Map.of("ring", fromCode));
        }
        RingEntity toRing = order.get(position + 1);
        if (command.to() != null && !command.to().isBlank()) {
            String named = command.to().trim().toLowerCase(Locale.ROOT);
            if (!named.equals(toRing.getCode())) {
                int namedPosition = ringPositionOf(order, named);
                if (namedPosition == -1) {
                    throw new ReleaseException(
                            ReleaseException.Code.INVALID_REQUEST,
                            "the request is invalid",
                            Map.of("to", "unknown ring code '" + named + "' for this organization"));
                }
                if (namedPosition > position
                        && skipAdmitted(release, command, moveSet, order, position, namedPosition, actorId)) {
                    // The organization's skip-relaxation knob admits
                    // the wider hop (S10-06's registrant): the walk
                    // carries straight to the named ring, every leg of
                    // the precheck evaluating entry to THAT ring.
                    toRing = order.get(namedPosition);
                } else {
                    throw new ReleaseException(
                            ReleaseException.Code.RELEASE_PROMOTION_RING_SKIP,
                            "skipping rings is disallowed; promotion moves one ring at a time",
                            Map.of(
                                    "next",
                                    toRing.getCode(),
                                    "named",
                                    named,
                                    "note",
                                    "the skip posture is the default; the organization's policy settings may relax it"));
                }
            }
        }
        RingEntity fromRing = order.get(position);
        List<UUID> tenants =
                moveSet.stream().map(ReleaseTargetEntity::getTenantId).toList();
        return new Hop(moveSet, fromRing, toRing, tenants);
    }

    /**
     * The skip-relaxation consult (S10-06, the seam's widening path):
     * a named ring BEYOND the next is admitted only when EVERY
     * registered {@link ReleasePromotionPolicy} admits it (fail-closed
     * composition — the default implementation refuses, so the #44
     * posture stands unless every contributor deliberately widens
     * it). A named ring at or before the current position is never a
     * skip to admit — backward movement is rollback's own crossing.
     */
    private boolean skipAdmitted(
            ReleaseEntity release,
            PromoteCommand command,
            List<ReleaseTargetEntity> moveSet,
            List<RingEntity> order,
            int position,
            int namedPosition,
            UUID actorId) {
        if (namedPosition <= position) {
            return false;
        }
        List<UUID> tenants =
                moveSet.stream().map(ReleaseTargetEntity::getTenantId).toList();
        ReleasePromotionPolicy.PromotionCheck check = new ReleasePromotionPolicy.PromotionCheck(
                release.getId(),
                release.releaseRef(),
                release.getApplicationId(),
                release.getOrganizationId(),
                order.get(position).getCode(),
                order.get(namedPosition).getCode(),
                tenants,
                actorId,
                ReleasePromotionPolicy.Purpose.ATTEMPT);
        boolean admitted = true;
        for (ReleasePromotionPolicy policy : promotionPolicies.orderedStream().toList()) {
            admitted &= policy.admitsSkip(check);
        }
        return admitted;
    }

    /** The move set: the body's selective tenants (every one must be a target), or every target. */
    private List<ReleaseTargetEntity> moveSetOf(List<ReleaseTargetEntity> targetRows, PromoteCommand command) {
        if (command.tenants() == null || command.tenants().isEmpty()) {
            return targetRows;
        }
        Map<String, String> malformed = new LinkedHashMap<>();
        Set<UUID> named = new LinkedHashSet<>();
        for (UUID tenantId : command.tenants()) {
            if (tenantId == null) {
                malformed.put("tenants", "tenantId is required for every entry");
            } else if (!named.add(tenantId)) {
                malformed.put("tenants", "a tenant may be named at most once");
            }
        }
        if (!malformed.isEmpty()) {
            throw new ReleaseException(ReleaseException.Code.INVALID_REQUEST, "the request is invalid", malformed);
        }
        List<ReleaseTargetEntity> moveSet = new ArrayList<>();
        for (ReleaseTargetEntity target : targetRows) {
            if (named.contains(target.getTenantId())) {
                moveSet.add(target);
            }
        }
        Map<String, String> notTargets = new LinkedHashMap<>();
        for (UUID tenantId : named) {
            boolean isTarget =
                    targetRows.stream().anyMatch(target -> target.getTenantId().equals(tenantId));
            if (!isTarget) {
                notTargets.put(tenantId.toString(), "tenant is not a target of this release");
            }
        }
        if (!notTargets.isEmpty()) {
            throw new ReleaseException(
                    ReleaseException.Code.RELEASE_TARGETS_INVALID, "invalid promotion targets", notTargets);
        }
        return moveSet;
    }

    // -------------------------------------------------------------------------
    // The precheck: one evaluation, every leg (AC1)
    // -------------------------------------------------------------------------

    /**
     * The one evaluation the three surfaces share: the target ring's
     * GATES (the {@link ReleaseGateEvaluation} port — live state at
     * the moment of the call, overrides honored, each denial carrying
     * the gate's evidence URL — PRD 33's "a failed gate blocks
     * promotion with recorded evidence", the evidence riding the
     * blocker AND the timeline row the refusal records), its APPROVALS
     * (the {@link PromotionApprovalSource} leg — the ring's named
     * policy; the consult opened the round on the promote act), its
     * ELIGIBILITY (the widening seam — each moved tenant the target
     * ring's rules would exclude, with the admission arithmetic's own
     * reasons), and every registered {@link ReleasePromotionPolicy}
     * (none ships; S10-06 registers here). Blockers accumulate into
     * ONE map — the complete list the 409 answers and the precheck
     * renders, keyed {@code gate:TYPE}, {@code approval}, {@code
     * eligibility:TENANT}, and the contributor's name.
     */
    private Precheck evaluate(
            ReleaseEntity release,
            Hop hop,
            PromotionApprovalSource.Standing approval,
            UUID actorId,
            ReleasePromotionPolicy.Purpose purpose) {
        Map<String, String> blockers = new LinkedHashMap<>();
        List<ReleaseGateEvaluation.Evaluation.GateState> gates = List.of();
        ReleaseGateEvaluation.Evaluation evaluation =
                gateEvaluation.evaluate(release.getId(), hop.toRing().getCode()).orElse(null);
        if (evaluation != null) {
            gates = evaluation.gates();
            for (Map.Entry<String, String> denial : evaluation.denials().entrySet()) {
                // The gate module's own denial already carries its evidence
                // URL; append it only when the message does not (every
                // blocker names its evidence — never twice).
                String evidence = gates.stream()
                        .filter(gate -> gate.gateType().equals(denial.getKey()))
                        .findFirst()
                        .map(ReleaseGateEvaluation.Evaluation.GateState::evidenceUrl)
                        .orElse(null);
                String message = denial.getValue();
                if (evidence != null && !message.contains(evidence)) {
                    message = message + " (evidence: " + evidence + ")";
                }
                blockers.put("gate:" + denial.getKey(), message);
            }
        }
        switch (approval.requirement()) {
            case NOT_REQUIRED, SATISFIED -> {}
            case PENDING ->
                blockers.put(
                        "approval",
                        "approval policy '" + approval.policyName() + "' round " + approval.roundNumber()
                                + " is pending: " + approval.waitedOn());
            case REJECTED ->
                blockers.put(
                        "approval",
                        "approval policy '" + approval.policyName() + "' round " + approval.roundNumber()
                                + " was rejected; rework and promote again to re-open the chain");
            case NOT_OPEN ->
                blockers.put(
                        "approval",
                        "approval policy '" + approval.policyName()
                                + "' gates this ring; no round is open yet (the promote attempt opens it)");
            case UNRESOLVED ->
                blockers.put(
                        "approval",
                        "approval policy '" + approval.policyName()
                                + "' does not resolve to a defined workflow (fail closed)");
        }
        List<Admission.TargetAdmission> widened = new ArrayList<>();
        Set<UUID> moveSet = new LinkedHashSet<>(hop.tenants());
        for (Admission.TargetAdmission admission : eligibility.admissionsAgainst(release.getId(), hop.toRing())) {
            if (!moveSet.contains(admission.tenantId())) {
                continue;
            }
            widened.add(admission);
            if (!admission.admitted()) {
                blockers.put(
                        "eligibility:" + admission.tenantId(),
                        "the ring's eligibility excludes this tenant: " + admission.reasons());
            }
        }
        for (ReleasePromotionPolicy policy : promotionPolicies.orderedStream().toList()) {
            String denial = policy.denial(new ReleasePromotionPolicy.PromotionCheck(
                            release.getId(),
                            release.releaseRef(),
                            release.getApplicationId(),
                            release.getOrganizationId(),
                            hop.fromRing().getCode(),
                            hop.toRing().getCode(),
                            hop.tenants(),
                            actorId,
                            purpose))
                    .orElse(null);
            if (denial != null) {
                blockers.put(policy.getClass().getSimpleName(), denial);
            }
        }
        return new Precheck(blockers, gates, approval, widened);
    }

    /** The evaluation's internal shape: the complete answer before it renders. */
    private record Precheck(
            Map<String, String> blockers,
            List<ReleaseGateEvaluation.Evaluation.GateState> gates,
            PromotionApprovalSource.Standing approval,
            List<Admission.TargetAdmission> widened) {

        PrecheckView viewOf() {
            return new PrecheckView(
                    blockers.isEmpty(),
                    blockers,
                    gates,
                    new PrecheckView.ApprovalView(
                            approval.requirement().name(),
                            approval.policyName(),
                            approval.roundNumber(),
                            approval.roundStatus() == null
                                    ? null
                                    : approval.roundStatus().name(),
                            approval.waitedOn()),
                    widened);
        }
    }

    // -------------------------------------------------------------------------
    // Views and commands
    // -------------------------------------------------------------------------

    /** The promote command: the selective tenant set (empty moves every target) and the optional asserted target ring. */
    public record PromoteCommand(List<UUID> tenants, String to) {}

    /**
     * The promote act's answer: the hop's two rings, the tenants moved,
     * whether the hop carried the release onto PRD 17's PROMOTED
     * crossing, the timeline row's id, and the refreshed per-target
     * rings — the widened distribution, read back.
     */
    public record PromotionResultView(
            UUID releaseId,
            String releaseRef,
            String status,
            String fromRing,
            String toRing,
            List<UUID> tenants,
            boolean crossedToPromoted,
            UUID promotionId,
            PromotionStatus promotionStatus,
            Map<String, String> blockers,
            List<TargetRingView> targets) {

        /** The attempt's standing: COMPLETED (this view answers a committed hop). */
        public enum PromotionStatus {
            COMPLETED
        }

        /** One target's refreshed ring after the hop. */
        public record TargetRingView(UUID tenantId, String ring) {}
    }

    /**
     * The precheck read (the same evaluation, three surfaces): the
     * verdict, the complete blocker list (byte-for-byte the 409's
     * {@code errors} member), the target ring's gate states with their
     * evidence URLs, the approval leg's standing, and the widened
     * admission rows.
     */
    public record PrecheckView(
            boolean promotable,
            Map<String, String> blockers,
            List<ReleaseGateEvaluation.Evaluation.GateState> gates,
            ApprovalView approval,
            List<Admission.TargetAdmission> eligibility) {

        /** The approval leg as the surface renders it. */
        public record ApprovalView(
                String requirement,
                String policyName,
                Long roundNumber,
                String roundStatus,
                Map<String, String> waitedOn) {}
    }

    /** The promotion history (AC5): the release summary and the append-only timeline. */
    public record PromotionHistoryView(
            UUID releaseId, String releaseRef, String status, List<PromotionRecordView> promotions) {}

    /** One timeline row: the hop's two rings (or the refusal), the tenants, the evidence, the actor, the moment. */
    public record PromotionRecordView(
            UUID id,
            String fromRing,
            String toRing,
            List<UUID> tenants,
            String status,
            Map<String, Object> blockers,
            UUID actorId,
            Instant occurredAt) {}

    // -------------------------------------------------------------------------
    // Helpers: resolution, scope, refusals
    // -------------------------------------------------------------------------

    private ApplicationAccess.ApplicationProfile profileOf(UUID applicationId) {
        return applications
                .profileOf(applicationId)
                .orElseThrow(() ->
                        new ReleaseException(ReleaseException.Code.APPLICATION_NOT_FOUND, "application not found"));
    }

    /** The release resolved scoped and verified against the addressed application — the release surface's concealed miss. */
    private ReleaseEntity scopedReleaseOf(UUID applicationId, UUID releaseId) {
        ReleaseEntity release = releases.findScoped(releaseId)
                .orElseThrow(() -> new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found"));
        if (!release.getApplicationId().equals(applicationId)) {
            throw new ReleaseException(ReleaseException.Code.NOT_FOUND, "release not found");
        }
        return release;
    }

    /** Establishes the application's owning scope and runs the work inside it (the release module's executor pattern). */
    private <T> T inScope(ApplicationAccess.ApplicationProfile application, UUID actorId, Supplier<T> work) {
        return application.tenantId() != null
                ? scopeExecutor.inScope(application.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    /** The effective code of a stored ring id: the row's code (shadowing keeps codes identical by definition). */
    private String effectiveCodeOf(ReleaseEntity release, UUID ringId) {
        RingEntity referenced = rings.findById(ringId).orElse(null);
        if (referenced == null) {
            return "?" + ringId;
        }
        return rings.byCode(referenced.getCode(), release.getOrganizationId())
                .map(RingEntity::getCode)
                .orElse(referenced.getCode());
    }

    private static int ringPositionOf(List<RingEntity> order, String code) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).getCode().equals(code)) {
                return i;
            }
        }
        return -1;
    }

    /** The final ring's code: the effective order's last entry (Production-last is enforced on writes). */
    private String finalRingCodeOf(ReleaseEntity release) {
        List<RingEntity> order = ringService.effectiveVocabularyOf(release.getOrganizationId());
        return order.get(order.size() - 1).getCode();
    }

    /** Whether every target's ring is the final code — the result view's crossedToPromoted render. */
    private boolean allTargetsAtFinalRing(ReleaseEntity release) {
        String finalCode = finalRingCodeOf(release);
        List<ReleaseTargetEntity> after = targets.findByReleaseIdOrderByTenantIdAsc(release.getId());
        if (after.isEmpty()) {
            return false;
        }
        for (ReleaseTargetEntity target : after) {
            if (!finalCode.equals(effectiveCodeOf(release, target.getRingId()))) {
                return false;
            }
        }
        return true;
    }

    /** The refreshed per-target rings (the widened distribution the hop produced). */
    private List<PromotionResultView.TargetRingView> targetRingViewOf(ReleaseEntity release) {
        List<PromotionResultView.TargetRingView> rows = new ArrayList<>();
        for (ReleaseTargetEntity target : targets.findByReleaseIdOrderByTenantIdAsc(release.getId())) {
            rows.add(new PromotionResultView.TargetRingView(target.getTenantId(), ringCodeOf(target.getRingId())));
        }
        return rows;
    }

    private String ringCodeOf(UUID ringId) {
        return ringId == null
                ? null
                : rings.findById(ringId).map(RingEntity::getCode).orElse(null);
    }

    private static ReleaseException invalidState(ReleaseEntity release) {
        return new ReleaseException(
                ReleaseException.Code.RELEASE_PROMOTION_INVALID_STATE,
                "only an ACTIVE release can be promoted ring-to-ring",
                Map.of(
                        "current",
                        release.getStatus().name(),
                        "required",
                        "ACTIVE (or PROMOTED, whose hops continue widening rings)"));
    }

    /** The staggered-set refusal: every tenant's current ring, so the operator promotes per ring group. */
    private ReleaseException ringSplit(ReleaseEntity release, List<ReleaseTargetEntity> moveSet) {
        Map<String, String> ringsByTenant = new LinkedHashMap<>();
        for (ReleaseTargetEntity target : moveSet) {
            ringsByTenant.put(target.getTenantId().toString(), effectiveCodeOf(release, target.getRingId()));
        }
        return new ReleaseException(
                ReleaseException.Code.RELEASE_PROMOTION_RING_SPLIT,
                "the promotion's targets sit on more than one ring; promote them per ring",
                ringsByTenant);
    }
}
