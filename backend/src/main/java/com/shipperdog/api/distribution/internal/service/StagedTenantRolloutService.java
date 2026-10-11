package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.StagedRolloutFacts;
import com.shipperdog.api.distribution.internal.apple.AppStorePhasedReleaseService;
import com.shipperdog.api.distribution.internal.apple.TestFlightRolloutArms;
import com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity;
import com.shipperdog.api.distribution.internal.entity.ChannelGroupLinkEntity;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutCohortEntity;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutEventEntity;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutPlanEntity;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutStageEntity;
import com.shipperdog.api.distribution.internal.google.PlayRolloutService;
import com.shipperdog.api.distribution.internal.repository.ChannelDistributionRepository;
import com.shipperdog.api.distribution.internal.repository.ChannelGroupLinkRepository;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutCohortRepository;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutEventRepository;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutPlanRepository;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutStageRepository;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseRolloutPolicyGate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staged TENANT rollout and orchestrated rollback (issue #66,
 * S13-06, FR-018/§37, PRD 16/17): ShipperDog's own rings axis — a
 * release widens across its target TENANT population in controlled
 * stages, each stage executing across every channel the release
 * dispatches on, with ONE action that rolls the whole thing back.
 *
 * <p><strong>The plan (AC1):</strong> authored while the release sits
 * before its ACTIVE crossing — the advancement mode (AUTOMATIC: the
 * S10-06 policy engine decides between stages; CHECKPOINT: a Release
 * Manager's explicit stage approval) and the ordered stages, each a
 * percentage of the target population or an explicit tenant list.
 * The deterministic cohort selection (the seeded SHA-256 order over
 * the release id, {@link StagedRolloutCohortSelection}) resolves
 * every stage's cohort AT AUTHORING and stores it: membership is
 * stable across retries, inspectable before any advancement, and
 * reproducible from the stored ranks. The plan only widens — every
 * stage must contain everything the previous stage reached, and the
 * final stage must reach every target.
 *
 * <p><strong>The commencement and the narrowing (AC3):</strong> the
 * release's ACTIVE crossing starts the plan (stage 1 REACHED, the
 * plan RUNNING) inside the dispatch's own transaction, and the
 * dispatch NARROWS its targets to the reached cohort — the direct
 * channel's availability follows the reached set through the
 * {@link com.shipperdog.api.distribution.StagedRolloutAvailability}
 * gate the download path consults; the TestFlight dispatch assigns
 * builds to the reached cohort's groups only; the Play curve starts
 * at the serving stage's coverage (the PlayRolloutService's staged
 * hook). Each later stage's ADVANCE executes the channel arms for
 * the newly reached delta: the TestFlight arm assigns the delta
 * groups, the Play arm widens the store curve to the stage's
 * cumulative coverage, the Direct arm is the platform's own reached
 * set, and the App Store's curve is Apple's own day-based schedule
 * (recorded not-applicable — the store owns that axis).
 *
 * <p><strong>The advancement engine (AC2):</strong> an advance of an
 * AUTOMATIC plan consults the SAME declarative policy engine the
 * promotion precheck runs (the production ring's BLOCK/HOLD rules —
 * a failing gate blocks the widening with the rule's evidence in the
 * 409, the refusal's evaluation evidence committing in the consult's
 * own transaction); a CHECKPOINT advance IS the Release Manager's
 * stage approval (the actor on the timeline row). BOTH modes answer
 * to the <em>dispatch-failure rollback ownership</em> doctrine since
 * #61 — the promise this story lands: FAILED dispatch records on the
 * release's channels own the refusal until they are resolved; a
 * failed dispatch never fails the ACTIVE crossing (it records FAILED
 * and is audited), and the staged surface is where that failure
 * surfaces as a loud block on widening, never a silent stall.
 *
 * <p><strong>The orchestrated rollback (AC4):</strong> ONE action
 * pulls the whole rollout — the release crosses ACTIVE to
 * ROLLED_BACK through the release module's port (direct downloads
 * cut by the state, the {@code RolledBackCutoffPolicy} seam; the
 * crossing's timeline row carries the mandatory reason), the
 * TestFlight group assignments are REVERTED (the build leaves the
 * mapped groups), the Play rollout is HALTED, the Apple phased curve
 * is halted where one runs, every channel distribution record moves
 * to the rolled-back state, and the artifacts are untouched
 * (INV-008). Each channel arm is best-effort BY DESIGN with its
 * outcome recorded on the timeline: the rollback's other arms are
 * not one channel's fault to veto, and the view shows exactly what
 * applied.
 *
 * <p><strong>The manifest (AC6, PRD 16):</strong> the APPROVED
 * crossing freezes the plan into the manifest's rollout block (the
 * {@link StagedRolloutFacts} port, the store-submission precedent)
 * — the immutable copy of WHAT WAS PLANNED; the rollout's own
 * append-only ledger ({@code staged_rollout_events}) records the
 * reached stages through the COMPLETED event — the manifest never
 * rewrites history, the completion record is the ledger's own.
 */
@Service
public class StagedTenantRolloutService implements StagedRolloutFacts {

    private static final Logger log = LoggerFactory.getLogger(StagedTenantRolloutService.class);

    private static final String CHANNEL_TESTFLIGHT = "testflight";
    private static final String CHANNEL_PLAY = "play";
    private static final String CHANNEL_APPSTORE = "appstore";
    private static final String CHANNEL_DIRECT = "direct";

    /** The plan's bound: a widening ladder beyond ten stages is a staircase nobody climbs. */
    private static final int MAX_STAGES = 10;

    /** The advance action (the checkpoint approval and the automatic widening in one vocabulary). */
    public static final String ACTION_ADVANCE = "ADVANCE";

    /** The orchestrated rollback action. */
    public static final String ACTION_ROLLBACK = "ROLLBACK";

    private final StagedRolloutPlanRepository plans;
    private final StagedRolloutStageRepository stageRows;
    private final StagedRolloutCohortRepository cohortRows;
    private final StagedRolloutEventRepository timeline;
    private final ChannelDistributionRepository records;
    private final ChannelGroupLinkRepository groupLinks;
    private final ReleaseAccess releases;
    private final ReleaseRolloutPolicyGate policyGate;
    private final PlayRolloutService playRollout;
    private final AppStorePhasedReleaseService appStorePhased;
    private final TestFlightRolloutArms testFlightArms;
    private final StagedRolloutBlockRecorder blockRecorder;
    private final AuditLogger audit;
    private final Clock clock;

    public StagedTenantRolloutService(
            StagedRolloutPlanRepository plans,
            StagedRolloutStageRepository stageRows,
            StagedRolloutCohortRepository cohortRows,
            StagedRolloutEventRepository timeline,
            ChannelDistributionRepository records,
            ChannelGroupLinkRepository groupLinks,
            ReleaseAccess releases,
            ReleaseRolloutPolicyGate policyGate,
            PlayRolloutService playRollout,
            AppStorePhasedReleaseService appStorePhased,
            TestFlightRolloutArms testFlightArms,
            StagedRolloutBlockRecorder blockRecorder,
            AuditLogger audit,
            Clock clock) {
        this.plans = plans;
        this.stageRows = stageRows;
        this.cohortRows = cohortRows;
        this.timeline = timeline;
        this.records = records;
        this.groupLinks = groupLinks;
        this.releases = releases;
        this.policyGate = policyGate;
        this.playRollout = playRollout;
        this.appStorePhased = appStorePhased;
        this.testFlightArms = testFlightArms;
        this.blockRecorder = blockRecorder;
        this.audit = audit;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // The authoring (AC1)
    // -------------------------------------------------------------------------

    /**
     * Authors the release's staged rollout plan (AC1): validates the
     * mode and the ordered stages over the release's own target set,
     * resolves every stage's cohort through the deterministic
     * selection, and writes the plan PLANNED with its full cohort
     * resolution — inspectable before any advancement runs. The
     * release must sit before its ACTIVE crossing (the plan shapes a
     * distribution that has not started); a release already
     * distributing, terminal, or already carrying its one plan
     * answers the precise refusal. A stage list must end at FULL
     * coverage — a staged rollout's own end is everyone served.
     */
    @Transactional
    public StagedRolloutView author(
            UUID organizationId, UUID releaseId, UUID actorId, String mode, List<StageRequest> stageRequests) {
        // The org guard FIRST (the concealment doctrine: a foreign
        // release is the byte-identical 404 of an unknown one — never
        // a 409 that discloses a foreign plan's existence).
        ReleaseAccess.ReleaseFacts facts = releaseFactsOf(organizationId, releaseId);
        requirePreActivation(facts.status());
        if (plans.findByReleaseId(releaseId).isPresent()) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_CONFLICT,
                    "the release already carries its one staged rollout plan");
        }
        StagedRolloutPlanEntity.Mode advancement = parseMode(mode);
        if (stageRequests == null || stageRequests.isEmpty() || stageRequests.size() > MAX_STAGES) {
            throw planInvalid("the plan needs between 1 and " + MAX_STAGES + " stages");
        }
        Set<UUID> targets = releases.distributionTargetTenantIdsOf(releaseId);
        if (targets.isEmpty()) {
            throw planInvalid("the release targets no tenants; there is nothing to stage");
        }
        List<StagedRolloutCohortSelection.StageShape> shapes = new ArrayList<>(stageRequests.size());
        for (StageRequest request : stageRequests) {
            if (request.percent() != null) {
                shapes.add(new StagedRolloutCohortSelection.PercentStage(request.percent()));
            } else if (request.tenants() != null) {
                shapes.add(new StagedRolloutCohortSelection.ExplicitStage(List.copyOf(request.tenants())));
            } else {
                throw planInvalid("each stage is either a percentage or an explicit tenant list");
            }
        }
        StagedRolloutCohortSelection.ResolvedCohorts resolved;
        try {
            resolved = StagedRolloutCohortSelection.resolve(releaseId, List.copyOf(targets), shapes);
        } catch (IllegalArgumentException e) {
            throw planInvalid(e.getMessage());
        }
        for (int i = 0; i < resolved.stages().size(); i++) {
            if (resolved.stages().get(i).isEmpty()) {
                throw planInvalid("stage " + (i + 1) + " resolves to an empty cohort (a stage must reach someone)");
            }
        }
        List<StagedRolloutCohortSelection.RankedTenant> lastCohort =
                resolved.stages().get(resolved.stages().size() - 1);
        if (lastCohort.size() != targets.size()) {
            throw planInvalid("the final stage must reach every target tenant (" + lastCohort.size() + " of "
                    + targets.size() + ")");
        }

        StagedRolloutPlanEntity plan = new StagedRolloutPlanEntity(
                releaseId, organizationId, facts.applicationId(), facts.versionId(), advancement, actorId);
        plan = plans.save(plan);
        for (int i = 0; i < resolved.stages().size(); i++) {
            int position = i + 1;
            StageRequest request = stageRequests.get(i);
            StagedRolloutStageEntity stage = new StagedRolloutStageEntity(
                    plan.getId(),
                    position,
                    request.percent() != null
                            ? StagedRolloutStageEntity.Kind.PERCENT
                            : StagedRolloutStageEntity.Kind.EXPLICIT,
                    request.percent(),
                    resolved.stages().get(i).size());
            stage = stageRows.save(stage);
            List<StagedRolloutCohortEntity> membership =
                    new ArrayList<>(resolved.stages().get(i).size());
            for (StagedRolloutCohortSelection.RankedTenant ranked :
                    resolved.stages().get(i)) {
                membership.add(new StagedRolloutCohortEntity(stage.getId(), ranked.tenantId(), ranked.rank()));
            }
            cohortRows.saveAll(membership);
        }
        record(
                plan,
                StagedRolloutEventEntity.Action.PLANNED,
                null,
                null,
                "stages=" + resolved.stages().size() + ";mode=" + advancement.name(),
                actorId);
        auditLine(
                plan,
                AuditEventType.STAGED_ROLLOUT_PLANNED,
                "planned",
                "stages=" + resolved.stages().size() + ";mode=" + advancement.name(),
                actorId,
                true);
        return view(organizationId, releaseId);
    }

    // -------------------------------------------------------------------------
    // The commencement (AC3) — the ACTIVE crossing's start, inside the dispatch's transaction
    // -------------------------------------------------------------------------

    /**
     * The ACTIVE crossing's commencement hook: a PLANNED plan becomes
     * RUNNING with stage 1 REACHED, before the dispatch builds its
     * command — the dispatch's target narrowing (same module, the
     * {@link StagedRolloutReader}) reads this standing, so the
     * release's first distribution serves exactly the first cohort.
     * No gate consult here: the engine judges BETWEEN stages; the
     * commencement is the plan's own birth. Idempotent by standing:
     * a replayed transition finds RUNNING and does nothing.
     */
    @Transactional
    public void commenceIfPlanned(UUID releaseId, UUID organizationId) {
        Optional<StagedRolloutPlanEntity> planned = plans.findByReleaseId(releaseId)
                .filter(plan -> plan.getStatus() == StagedRolloutPlanEntity.Status.PLANNED);
        if (planned.isEmpty()) {
            return;
        }
        StagedRolloutPlanEntity plan = planned.get();
        List<StagedRolloutStageEntity> stages = stageRows.findByPlanIdOrderByPositionAsc(plan.getId());
        if (stages.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        StagedRolloutStageEntity first = stages.get(0);
        first.reach(now);
        stageRows.save(first);
        plan.serve(first.getPosition());
        plan.move(StagedRolloutPlanEntity.Status.RUNNING);
        plans.save(plan);
        record(
                plan,
                StagedRolloutEventEntity.Action.STARTED,
                first.getPosition(),
                CHANNEL_DIRECT,
                "cohort=" + first.getCohortSize(),
                null);
        auditLine(
                plan,
                AuditEventType.STAGED_ROLLOUT_ADVANCED,
                "started",
                "stage=" + first.getPosition() + ";cohort=" + first.getCohortSize(),
                null,
                true);
    }

    // -------------------------------------------------------------------------
    // The actions (AC2/AC4): advance and the orchestrated rollback
    // -------------------------------------------------------------------------

    /**
     * One staged rollout action (AC2/AC4): ADVANCE — the checkpoint
     * approval or the (gated) automatic widening — or ROLLBACK — the
     * one-action orchestrated pull. Every act records on the timeline
     * and audits; a concurrent act on the same plan answers the
     * precise 409 (the optimistic stamp), never a last-write-win.
     */
    @Transactional
    public StagedRolloutView control(UUID organizationId, UUID releaseId, UUID actorId, String action, String reason) {
        String act = action == null ? "" : action.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            switch (act) {
                case ACTION_ADVANCE -> {
                    advance(organizationId, releaseId, actorId);
                    return view(organizationId, releaseId);
                }
                case ACTION_ROLLBACK -> {
                    rollback(organizationId, releaseId, actorId, reason);
                    return view(organizationId, releaseId);
                }
                default ->
                    throw new DistributionException(
                            DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_ACTION_INVALID,
                            "the staged rollout action must be one of ADVANCE or ROLLBACK");
            }
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_CONFLICT,
                    "the staged rollout changed concurrently; reload and retry");
        }
    }

    /**
     * The advancement engine (AC2): the next stage's execution. The
     * dispatch-failure ownership rule first (FAILED records own the
     * refusal, both modes), then the AUTOMATIC mode's policy consult
     * (the production ring's BLOCK/HOLD rules, the promotion
     * question's own wording applied to the widening), then the
     * channel arms for the newly reached delta, then the stage's
     * REACHED. A CHECKPOINT advance is the Release Manager's approval
     * itself — the actor on the STAGE_APPROVED timeline row.
     */
    private void advance(UUID organizationId, UUID releaseId, UUID actorId) {
        StagedRolloutPlanEntity plan = planOf(organizationId, releaseId);
        requireAdvancementStanding(plan);
        List<StagedRolloutStageEntity> stages = stageRows.findByPlanIdOrderByPositionAsc(plan.getId());
        StagedRolloutStageEntity current = stages.get(plan.getCurrentStage() - 1);
        int nextPosition = current.getPosition() + 1;
        StagedRolloutStageEntity next = stages.stream()
                .filter(stage -> stage.getPosition() == nextPosition)
                .findFirst()
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_ACTION_INVALID,
                        "the final stage is reached; the rollout is complete"));

        // The dispatch-failure rollback ownership (the #61 promise,
        // landed here): FAILED dispatch records never fail the ACTIVE
        // crossing — they record FAILED and are audited — and THIS
        // surface owns the refusal: a channel whose dispatch failed
        // blocks the widening until the dispatch is resolved, loudly.
        String failedDispatches = failedDispatchCodesOf(organizationId, releaseId);
        if (failedDispatches != null) {
            block(plan, next, "failed-dispatches:" + failedDispatches, actorId);
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_BLOCKED,
                    "the advancement is blocked: the release carries FAILED dispatch records (" + failedDispatches
                            + "); resolve the dispatch before widening the rollout");
        }

        // The AUTOMATIC mode's gate (AC2): the policy engine answers
        // the widening question exactly as it answers the promotion's.
        if (plan.getMode() == StagedRolloutPlanEntity.Mode.AUTOMATIC) {
            String ring = releases.distributionEffectiveRingCodeOf(releaseId).orElse(null);
            Optional<String> denial = policyGate.rolloutDenialOf(organizationId, releaseId, ring, actorId);
            if (denial.isPresent()) {
                block(plan, next, "policy:" + denial.get(), actorId);
                throw new DistributionException(
                        DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_BLOCKED,
                        "the advancement is blocked: " + denial.get());
            }
        } else {
            record(plan, StagedRolloutEventEntity.Action.STAGE_APPROVED, next.getPosition(), null, null, actorId);
        }

        // The channel arms (AC3): the delta the stage newly reaches,
        // applied on every channel the release dispatches on.
        Set<UUID> reachedBefore = reachedOf(stages, current);
        Set<UUID> cohortAfter = cohortOf(next);
        Set<UUID> delta = new LinkedHashSet<>(cohortAfter);
        delta.removeAll(reachedBefore);
        applyAdvanceArms(plan, delta, next);

        Instant now = Instant.now(clock);
        next.reach(now);
        stageRows.save(next);
        plan.serve(next.getPosition());
        boolean terminal = next.getPosition() == stages.get(stages.size() - 1).getPosition();
        int coverage = coveragePercentOf(plan, cohortAfter.size());
        if (terminal) {
            plan.move(StagedRolloutPlanEntity.Status.COMPLETE);
        }
        plans.save(plan);
        record(
                plan,
                StagedRolloutEventEntity.Action.STAGE_ADVANCED,
                next.getPosition(),
                null,
                "delta=" + delta.size() + ";coverage=" + coverage,
                actorId);
        auditLine(
                plan,
                AuditEventType.STAGED_ROLLOUT_ADVANCED,
                "advanced",
                "stage=" + next.getPosition() + ";delta=" + delta.size() + ";coverage=" + coverage,
                actorId,
                true);
        if (terminal) {
            // The completion record (AC6): the reached-stages summary
            // on the rollout's own append-only ledger — the manifest
            // froze the plan; the ledger records the journey's end.
            String reachedSummary = stages.stream()
                    .map(stage -> String.valueOf(stage.getPosition()))
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
            record(plan, StagedRolloutEventEntity.Action.COMPLETED, null, null, "reached=" + reachedSummary, actorId);
            auditLine(
                    plan,
                    AuditEventType.STAGED_ROLLOUT_ADVANCED,
                    "completed",
                    "reached=" + reachedSummary,
                    actorId,
                    true);
        }
    }

    /**
     * The orchestrated rollback (AC4): ONE action. The release-side
     * crossing first (ACTIVE to ROLLED_BACK through the release
     * module's port — the direct downloads cut, the timeline row
     * carries the mandatory reason), then every channel arm
     * best-effort with its outcome recorded (the TestFlight
     * assignments reverted, the Play rollout halted, the Apple phased
     * curve halted where one runs), then the records to the
     * rolled-back state and the plan's own ROLLED_BACK standing. The
     * artifacts are untouched (INV-008) — the rollback serves
     * nothing, it deletes nothing.
     */
    private void rollback(UUID organizationId, UUID releaseId, UUID actorId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_ACTION_INVALID,
                    "a rollback requires a reason (the pulled release says why, FR-024)");
        }
        StagedRolloutPlanEntity plan = planOf(organizationId, releaseId);
        if (plan.getStatus() != StagedRolloutPlanEntity.Status.RUNNING
                && plan.getStatus() != StagedRolloutPlanEntity.Status.COMPLETE) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_ACTION_INVALID,
                    "the plan is " + plan.getStatus() + "; there is no distribution to roll back");
        }
        // The release-side crossing first: its refusal (a release not
        // sitting on ACTIVE) is the rollback's own refusal — nothing
        // else applies. The port's ReleaseException is translated HERE
        // because the release module's handler is controller-scoped
        // (ReleaseController/ReleasePolicyController): unhandled it
        // would render a generic 500 on the distribution route — the
        // crossing's own precise conflict becomes the staged surface's.
        try {
            releases.rollbackActiveRelease(organizationId, releaseId, actorId, reason.trim());
        } catch (com.shipperdog.api.release.ReleaseException e) {
            if (e.code() == com.shipperdog.api.release.ReleaseException.Code.NOT_FOUND) {
                throw new DistributionException(
                        DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_NOT_FOUND,
                        "the release carries no staged rollout plan");
            }
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_CONFLICT,
                    "the release refuses the rollback crossing: " + e.getMessage());
        }

        Map<String, String> armOutcomes = new LinkedHashMap<>();
        for (String channel : dispatchedChannelsOf(organizationId, releaseId)) {
            armOutcomes.put(channel, rollbackArmOf(channel, organizationId, releaseId, actorId, plan));
        }
        List<ChannelDistributionEntity> rows =
                records.findByReleaseIdAndOrganizationIdOrderByChannelAsc(releaseId, organizationId);
        rows.forEach(ChannelDistributionEntity::markRolledBack);
        records.saveAll(rows);
        List<StagedRolloutStageEntity> stages = stageRows.findByPlanIdOrderByPositionAsc(plan.getId());
        stages.stream()
                .filter(stage -> stage.getStatus() == StagedRolloutStageEntity.Status.REACHED)
                .forEach(stage -> {
                    stage.pull();
                    stageRows.save(stage);
                });
        plan.move(StagedRolloutPlanEntity.Status.ROLLED_BACK);
        plans.save(plan);
        String outcomeDetail = armOutcomes.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((a, b) -> a + ";" + b)
                .orElse("no-channels");
        record(
                plan,
                StagedRolloutEventEntity.Action.ROLLED_BACK,
                plan.getCurrentStage(),
                null,
                "reason=" + reason.trim() + ";" + outcomeDetail,
                actorId);
        auditLine(
                plan,
                AuditEventType.STAGED_ROLLOUT_ROLLED_BACK,
                "rolled-back",
                "reason=" + reason.trim() + ";" + outcomeDetail,
                actorId,
                true);
        log.warn("staged rollout of release {} rolled back: {} (arms: {})", releaseId, reason.trim(), outcomeDetail);
    }

    /**
     * One channel's rollback arm, by channel vocabulary: TestFlight's
     * assignments revert (the build leaves the mapped groups), Play's
     * rollout halts, the App Store's phased curve halts where one
     * runs, and Direct's cut is the release state itself. Best-effort
     * BY DESIGN: the arm's outcome is the answer, never a throw — the
     * rollback's other arms are not one channel's fault to veto.
     */
    private String rollbackArmOf(
            String channel, UUID organizationId, UUID releaseId, UUID actorId, StagedRolloutPlanEntity plan) {
        return switch (channel) {
            case CHANNEL_TESTFLIGHT -> testFlightRevertArm(organizationId, releaseId, plan);
            case CHANNEL_PLAY -> playRollout.haltForStagedRollback(organizationId, releaseId, actorId);
            case CHANNEL_APPSTORE -> appStorePhased.haltForStagedRollback(organizationId, releaseId, actorId);
            case CHANNEL_DIRECT -> "cut-by-release-state";
            default -> "no-arm";
        };
    }

    // -------------------------------------------------------------------------
    // The channel arms (AC3)
    // -------------------------------------------------------------------------

    /**
     * The advance's channel arms: the newly reached delta applied on
     * every channel the release dispatches on — TestFlight's build
     * assignment for the delta groups, Play's curve widened to the
     * stage's coverage, Direct's availability widened with the
     * reached set itself, the App Store's own curve recorded
     * not-applicable (Apple owns that day-based axis). Each arm's
     * outcome lands on the timeline (CHANNEL_APPLIED); an arm's
     * failure is recorded loudly and never vetoes the platform's
     * stage — the store-side standing surfaces as drift on the
     * channel's own view, the #65 doctrine.
     */
    private void applyAdvanceArms(StagedRolloutPlanEntity plan, Set<UUID> delta, StagedRolloutStageEntity next) {
        for (String channel : dispatchedChannelsOf(plan.getOrganizationId(), plan.getReleaseId())) {
            String outcome = switch (channel) {
                case CHANNEL_TESTFLIGHT -> testFlightAssignArm(plan, delta);
                case CHANNEL_PLAY ->
                    playRollout.widenForStagedRollout(
                            plan.getOrganizationId(),
                            plan.getReleaseId(),
                            coveragePercentOf(plan, next.getCohortSize()),
                            null);
                case CHANNEL_APPSTORE -> "not-applicable";
                case CHANNEL_DIRECT -> "availability-widened:" + delta.size();
                default -> "no-arm";
            };
            record(plan, StagedRolloutEventEntity.Action.CHANNEL_APPLIED, next.getPosition(), channel, outcome, null);
        }
    }

    /**
     * The TestFlight arm's advance half: the release's iOS build
     * assigned to every mapped group of the delta tenants' target
     * groups — the same provider call the dispatch runs, driven by
     * the plan's own widening (the arms component beside the client
     * speaks the credential; this service resolves WHO the stage
     * reaches).
     */
    private String testFlightAssignArm(StagedRolloutPlanEntity plan, Set<UUID> delta) {
        List<String> builds = testFlightBuildIdsOf(plan.getOrganizationId(), plan.getReleaseId());
        if (builds.isEmpty()) {
            return "no-build-record";
        }
        Map<UUID, String> links = mappedGroupsOf(plan.getReleaseId(), delta);
        if (links.isEmpty()) {
            return "groups-unmapped";
        }
        ReleaseAccess.ReleaseFacts facts =
                releases.distributionFactsOf(plan.getReleaseId()).orElse(null);
        return testFlightArms.assignBuildToGroups(
                plan.getOrganizationId(),
                facts == null ? plan.getApplicationId() : facts.applicationId(),
                facts == null ? null : facts.environmentId(),
                builds,
                links.values());
    }

    /**
     * The TestFlight arm's rollback half (AC4): the release's build
     * REMOVED from every mapped group of the reached cohort — the
     * assignments the rollout made, reverted.
     */
    private String testFlightRevertArm(UUID organizationId, UUID releaseId, StagedRolloutPlanEntity plan) {
        List<String> builds = testFlightBuildIdsOf(organizationId, releaseId);
        if (builds.isEmpty()) {
            return "no-build-record";
        }
        Set<UUID> reached = reachedTenantIdsOf(plan);
        Map<UUID, String> links = mappedGroupsOf(releaseId, reached);
        if (links.isEmpty()) {
            return "groups-unmapped";
        }
        ReleaseAccess.ReleaseFacts facts =
                releases.distributionFactsOf(releaseId).orElse(null);
        return testFlightArms.removeBuildFromGroups(
                organizationId,
                facts == null ? plan.getApplicationId() : facts.applicationId(),
                facts == null ? null : facts.environmentId(),
                builds,
                links.values());
    }

    /** The release's TestFlight build external ids (the ASC build ids the arms address). */
    private List<String> testFlightBuildIdsOf(UUID organizationId, UUID releaseId) {
        return records
                .findByReleaseIdAndOrganizationIdAndChannelOrderByCreatedAtAsc(
                        releaseId, organizationId, CHANNEL_TESTFLIGHT)
                .stream()
                .map(ChannelDistributionEntity::getExternalId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    /** The delta tenants' (or the reached set's) target groups with their channel links resolved. */
    private Map<UUID, String> mappedGroupsOf(UUID releaseId, Set<UUID> tenants) {
        List<UUID> groupIds = releases.distributionTargetGroupsOf(releaseId).stream()
                .filter(group -> tenants.contains(group.tenantId()))
                .map(ReleaseAccess.ReleaseTargetGroup::groupId)
                .distinct()
                .toList();
        if (groupIds.isEmpty()) {
            return Map.of();
        }
        return groupLinks.findByChannelAndGroupIdIn(CHANNEL_TESTFLIGHT, groupIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ChannelGroupLinkEntity::getGroupId, ChannelGroupLinkEntity::getExternalGroupId, (a, b) -> a));
    }

    // -------------------------------------------------------------------------
    // The scheduled advancement pass (AC2's automatic mode)
    // -------------------------------------------------------------------------

    /**
     * The advancement pass's input: RUNNING AUTOMATIC plans that still
     * have a stage to execute — each one a candidate the pass tries
     * once, a refusal (the gate, the dispatch-failure rule) recorded
     * and never the pass's.
     */
    @Transactional(readOnly = true)
    public List<StagedRolloutPlanEntity> advanceablePlans() {
        return plans.findByStatus(StagedRolloutPlanEntity.Status.RUNNING).stream()
                .filter(plan -> plan.getMode() == StagedRolloutPlanEntity.Mode.AUTOMATIC)
                .filter(plan -> plan.getCurrentStage() != null
                        && plan.getCurrentStage()
                                < stageRows
                                        .findByPlanIdOrderByPositionAsc(plan.getId())
                                        .size())
                .toList();
    }

    /**
     * One plan's scheduled advancement attempt (a refusal is recorded,
     * never the pass's): the same engine the route drives, with no
     * actor (the pass is the platform's own cadence).
     */
    @Transactional
    public void tryAdvance(StagedRolloutPlanEntity plan) {
        try {
            advance(plan.getOrganizationId(), plan.getReleaseId(), null);
        } catch (DistributionException e) {
            log.info("scheduled staged advancement of release {} refused: {}", plan.getReleaseId(), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // The view (AC1/AC3): the plan, the cohorts, the per-tenant progress
    // -------------------------------------------------------------------------

    /**
     * The staged rollout's served view (AC1/AC3): the plan's mode and
     * standing, every stage with its resolved cohort and standing,
     * the PER-TENANT progress (which stage reached each tenant —
     * Acme@50%, Contoso not yet), and the append-only timeline. A
     * foreign or unknown release-and-organization pair conceals as
     * the honest 404, exactly like every distribution surface.
     */
    @Transactional(readOnly = true)
    public StagedRolloutView view(UUID organizationId, UUID releaseId) {
        StagedRolloutPlanEntity plan = planOf(organizationId, releaseId);
        List<StagedRolloutStageEntity> stages = stageRows.findByPlanIdOrderByPositionAsc(plan.getId());
        Set<UUID> targets = releases.distributionTargetTenantIdsOf(releaseId);
        Map<UUID, List<StagedRolloutCohortEntity>> cohortsByStage = new LinkedHashMap<>();
        if (!stages.isEmpty()) {
            cohortRows
                    .findByStageIdIn(
                            stages.stream().map(StagedRolloutStageEntity::getId).toList())
                    .forEach(row -> cohortsByStage
                            .computeIfAbsent(row.getStageId(), stage -> new ArrayList<>())
                            .add(row));
        }
        List<StageView> stageViews = new ArrayList<>(stages.size());
        Map<UUID, Integer> reachedAtStage = new LinkedHashMap<>();
        Map<UUID, Instant> reachedAt = new LinkedHashMap<>();
        for (StagedRolloutStageEntity stage : stages) {
            List<StagedRolloutCohortEntity> cohort = cohortsByStage.getOrDefault(stage.getId(), List.of());
            cohort.stream()
                    .sorted(java.util.Comparator.comparingInt(StagedRolloutCohortEntity::getRank))
                    .forEach(row -> {
                        reachedAtStage.putIfAbsent(row.getTenantId(), stage.getPosition());
                        if (stage.getStatus() == StagedRolloutStageEntity.Status.REACHED
                                && !reachedAt.containsKey(row.getTenantId())) {
                            reachedAt.put(row.getTenantId(), stage.getReachedAt());
                        }
                    });
            stageViews.add(new StageView(
                    stage.getPosition(),
                    stage.getKind().name(),
                    stage.getPercent(),
                    stage.getCohortSize(),
                    stage.getStatus().name(),
                    stage.getReachedAt(),
                    cohort.stream()
                            .sorted(java.util.Comparator.comparingInt(StagedRolloutCohortEntity::getRank))
                            .map(StagedRolloutCohortEntity::getTenantId)
                            .toList()));
        }
        List<TenantProgressView> progress = new ArrayList<>(targets.size());
        for (UUID tenant : new java.util.TreeSet<>(targets)) {
            progress.add(new TenantProgressView(
                    tenant,
                    reachedAtStage.containsKey(tenant) && reachedAt.containsKey(tenant)
                            ? reachedAtStage.get(tenant)
                            : null,
                    reachedAt.get(tenant)));
        }
        List<EventView> events = new ArrayList<>();
        for (StagedRolloutEventEntity row : timeline.findByPlanIdOrderByOccurredAtAscIdAsc(plan.getId())) {
            events.add(new EventView(
                    row.getAction().name(),
                    row.getStagePosition(),
                    row.getChannel(),
                    row.getDetail(),
                    row.getActorId(),
                    row.getOccurredAt()));
        }
        return new StagedRolloutView(
                plan.getReleaseId(),
                plan.getMode().name(),
                plan.getStatus().name(),
                plan.getCurrentStage(),
                stageViews,
                progress,
                events);
    }

    // -------------------------------------------------------------------------
    // The manifest freeze port (AC6, PRD 16)
    // -------------------------------------------------------------------------

    /** The plan as the APPROVED crossing freezes it: values only, the store-submission precedent. */
    @Override
    @Transactional(readOnly = true)
    public Optional<FrozenPlan> planOf(UUID releaseId) {
        return plans.findByReleaseId(releaseId).map(plan -> {
            List<StagedRolloutStageEntity> stages = stageRows.findByPlanIdOrderByPositionAsc(plan.getId());
            List<FrozenStage> frozen = new ArrayList<>(stages.size());
            for (StagedRolloutStageEntity stage : stages) {
                List<UUID> cohort = cohortRows.findByStageIdOrderByRankAsc(stage.getId()).stream()
                        .map(StagedRolloutCohortEntity::getTenantId)
                        .toList();
                frozen.add(new FrozenStage(stage.getPosition(), stage.getKind().name(), stage.getPercent(), cohort));
            }
            return new FrozenPlan(plan.getMode().name(), frozen);
        });
    }

    // -------------------------------------------------------------------------
    // Resolution helpers
    // -------------------------------------------------------------------------

    /** The org-guarded plan resolution (the concealed miss across organizations). */
    private StagedRolloutPlanEntity planOf(UUID organizationId, UUID releaseId) {
        return plans.findByReleaseIdAndOrganizationId(releaseId, organizationId)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_NOT_FOUND,
                        "the release carries no staged rollout plan"));
    }

    /** The release facts, organization-guarded (the concealed miss). */
    private ReleaseAccess.ReleaseFacts releaseFactsOf(UUID organizationId, UUID releaseId) {
        return releases.distributionFactsOf(releaseId)
                .filter(facts -> facts.organizationId().equals(organizationId))
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_NOT_FOUND,
                        "the release carries no staged rollout plan"));
    }

    private static void requirePreActivation(String status) {
        if ("ACTIVE".equals(status) || "PROMOTED".equals(status)) {
            throw planInvalid(
                    "the release is already distributing; a staged plan shapes a distribution before it starts");
        }
        if ("ROLLED_BACK".equals(status) || "COMPLETED".equals(status) || "FAILED".equals(status)) {
            throw planInvalid("the release is terminal (" + status + "); a staged plan needs a live release");
        }
    }

    private void requireAdvancementStanding(StagedRolloutPlanEntity plan) {
        if (plan.getStatus() == StagedRolloutPlanEntity.Status.PLANNED) {
            throw actionInvalid("the plan has not started (the release is not yet ACTIVE)");
        }
        if (plan.getStatus() == StagedRolloutPlanEntity.Status.COMPLETE) {
            throw actionInvalid("the rollout is complete; every stage reached");
        }
        if (plan.getStatus() == StagedRolloutPlanEntity.Status.ROLLED_BACK) {
            throw actionInvalid("the rollout was rolled back; the plan is terminal");
        }
    }

    /** The FAILED dispatch records' failure codes, one entry per record; null when none failed (the ownership rule's input). */
    private String failedDispatchCodesOf(UUID organizationId, UUID releaseId) {
        List<String> codes =
                records.findByReleaseIdAndOrganizationIdOrderByChannelAsc(releaseId, organizationId).stream()
                        .filter(row -> row.getStatus() == ChannelDistributionEntity.Status.FAILED)
                        .map(row -> row.getChannel() + ":"
                                + (row.getFailureCode() == null ? "UNKNOWN" : row.getFailureCode()))
                        .distinct()
                        .toList();
        return codes.isEmpty() ? null : String.join(",", codes);
    }

    /** The channels the release dispatched on (distinct, channel-ordered); direct when no records exist yet. */
    private List<String> dispatchedChannelsOf(UUID organizationId, UUID releaseId) {
        List<String> channels =
                records.findByReleaseIdAndOrganizationIdOrderByChannelAsc(releaseId, organizationId).stream()
                        .map(ChannelDistributionEntity::getChannel)
                        .distinct()
                        .toList();
        return channels.isEmpty() ? List.of(CHANNEL_DIRECT) : channels;
    }

    private Set<UUID> reachedOf(List<StagedRolloutStageEntity> stages, StagedRolloutStageEntity through) {
        Set<UUID> tenants = new LinkedHashSet<>();
        List<UUID> reachedStages = stages.stream()
                .filter(stage -> stage.getPosition() <= through.getPosition()
                        && stage.getStatus() == StagedRolloutStageEntity.Status.REACHED)
                .map(StagedRolloutStageEntity::getId)
                .toList();
        if (!reachedStages.isEmpty()) {
            cohortRows.findByStageIdIn(reachedStages).forEach(row -> tenants.add(row.getTenantId()));
        }
        return tenants;
    }

    private Set<UUID> cohortOf(StagedRolloutStageEntity stage) {
        Set<UUID> tenants = new LinkedHashSet<>();
        cohortRows.findByStageIdOrderByRankAsc(stage.getId()).forEach(row -> tenants.add(row.getTenantId()));
        return tenants;
    }

    private Set<UUID> reachedTenantIdsOf(StagedRolloutPlanEntity plan) {
        Set<UUID> tenants = new LinkedHashSet<>();
        List<UUID> reachedStages = stageRows.findByPlanIdOrderByPositionAsc(plan.getId()).stream()
                .filter(stage -> stage.getStatus() == StagedRolloutStageEntity.Status.REACHED)
                .map(StagedRolloutStageEntity::getId)
                .toList();
        if (!reachedStages.isEmpty()) {
            cohortRows.findByStageIdIn(reachedStages).forEach(row -> tenants.add(row.getTenantId()));
        }
        return tenants;
    }

    /** The cohort's share of the target population, 1..100 (the play curve's equivalent). */
    private int coveragePercentOf(StagedRolloutPlanEntity plan, int cohortSize) {
        int targets =
                releases.distributionTargetTenantIdsOf(plan.getReleaseId()).size();
        if (targets <= 0) {
            return 100;
        }
        return (int) Math.min(100, Math.max(1, Math.round(cohortSize * 100.0d / targets)));
    }

    /**
     * The refusal's record — REQUIRES_NEW through the {@link
     * StagedRolloutBlockRecorder} (the #45 discipline): the STAGE_BLOCKED
     * timeline row and the audit line commit in their own transaction,
     * so the evidence survives the 409's rollback.
     */
    private void block(StagedRolloutPlanEntity plan, StagedRolloutStageEntity stage, String detail, UUID actorId) {
        blockRecorder.recordBlocked(plan, stage.getPosition(), detail, actorId);
    }

    private void record(
            StagedRolloutPlanEntity plan,
            StagedRolloutEventEntity.Action action,
            Integer stagePosition,
            String channel,
            String detail,
            UUID actorId) {
        String bounded = detail == null || detail.length() <= 512 ? detail : detail.substring(0, 512);
        timeline.save(new StagedRolloutEventEntity(
                plan.getId(), action, stagePosition, channel, bounded, actorId, Instant.now(clock)));
    }

    private void auditLine(
            StagedRolloutPlanEntity plan,
            AuditEventType type,
            String action,
            String detail,
            UUID actorId,
            boolean success) {
        Map<String, String> detailMap = new LinkedHashMap<>();
        detailMap.put("releaseId", plan.getReleaseId().toString());
        detailMap.put("action", action);
        if (detail != null) {
            detailMap.put("detail", detail.length() > 380 ? detail.substring(0, 380) : detail);
        }
        AuditEvent.Builder line = AuditEvent.builder(type)
                .orgId(plan.getOrganizationId())
                .applicationId(plan.getApplicationId())
                .detail(detailMap)
                .success(success);
        if (actorId != null) {
            line.userId(actorId);
        }
        audit.record(line.build());
    }

    private static StagedRolloutPlanEntity.Mode parseMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "AUTOMATIC" -> StagedRolloutPlanEntity.Mode.AUTOMATIC;
            case "CHECKPOINT" -> StagedRolloutPlanEntity.Mode.CHECKPOINT;
            default ->
                throw planInvalid("the advancement mode must be one of AUTOMATIC or CHECKPOINT (a staged plan either"
                        + " widens on the policy engine's verdict or on a Release Manager's explicit stage approval)");
        };
    }

    private static DistributionException planInvalid(String reason) {
        return new DistributionException(DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_PLAN_INVALID, reason);
    }

    private static DistributionException actionInvalid(String reason) {
        return new DistributionException(DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_ACTION_INVALID, reason);
    }

    // -------------------------------------------------------------------------
    // The served shapes
    // -------------------------------------------------------------------------

    /** One authoring stage: a percentage of the target population, or an explicit tenant list. */
    public record StageRequest(Integer percent, List<UUID> tenants) {}

    /** The staged rollout's served view (AC1/AC3). */
    public record StagedRolloutView(
            UUID releaseId,
            String mode,
            String status,
            Integer currentStage,
            List<StageView> stages,
            List<TenantProgressView> tenantProgress,
            List<EventView> timeline) {

        public StagedRolloutView {
            stages = List.copyOf(stages);
            tenantProgress = List.copyOf(tenantProgress);
            timeline = List.copyOf(timeline);
        }
    }

    /** One stage: its shape, its resolved cohort (inspectable before advancement), and its standing. */
    public record StageView(
            int position,
            String kind,
            Integer percent,
            int cohortSize,
            String status,
            Instant reachedAt,
            List<UUID> cohort) {

        public StageView {
            cohort = List.copyOf(cohort);
        }
    }

    /**
     * One target tenant's progress (AC3): the stage that reached it
     * and when — null while not yet reached (Contoso not yet).
     */
    public record TenantProgressView(UUID tenantId, Integer reachedAtStage, Instant reachedAt) {}

    /** One timeline row (the append-only ledger the dashboard renders beside the audit trail). */
    public record EventView(
            String action, Integer stagePosition, String channel, String detail, UUID actorId, Instant occurredAt) {}
}
