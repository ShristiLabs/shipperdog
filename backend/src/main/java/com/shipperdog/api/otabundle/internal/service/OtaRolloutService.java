package com.shipperdog.api.otabundle.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.application.ApplicationAccess.ApplicationProfile;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.otabundle.OtaBundleException;
import com.shipperdog.api.otabundle.OtaRolloutAccess;
import com.shipperdog.api.otabundle.OtaRolloutTransitionedEvent;
import com.shipperdog.api.otabundle.internal.entity.OtaBundleEntity;
import com.shipperdog.api.otabundle.internal.entity.OtaRolloutEventEntity;
import com.shipperdog.api.otabundle.internal.entity.OtaRolloutEventEntity.Action;
import com.shipperdog.api.otabundle.internal.entity.OtaRolloutPlanEntity;
import com.shipperdog.api.otabundle.internal.entity.OtaRolloutPlanEntity.Mode;
import com.shipperdog.api.otabundle.internal.entity.OtaRolloutPlanEntity.Status;
import com.shipperdog.api.otabundle.internal.entity.OtaRolloutStageEntity;
import com.shipperdog.api.otabundle.internal.repository.OtaBundleScopedRepository;
import com.shipperdog.api.otabundle.internal.repository.OtaRolloutEventRepository;
import com.shipperdog.api.otabundle.internal.repository.OtaRolloutPlanScopedRepository;
import com.shipperdog.api.otabundle.internal.repository.OtaRolloutRegistryRepository;
import com.shipperdog.api.otabundle.internal.repository.OtaRolloutStageRepository;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseAccess.ReleaseFacts;
import com.shipperdog.api.release.ReleaseRolloutPolicyGate;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The OTA device-rollout orchestration (S16-06, issue #168, ADR-0010
 * workstream 3, decisions D6/D10): ONE widening ladder per OTA
 * release, driven entirely by operators. The attachment COMMENCES the
 * plan -- born RUNNING on a live OTA release, stage 1 REACHED, a
 * COMMENCED timeline row -- and every later act is one of the four
 * control actions: ADVANCE (the next stage's basis points), HALT (the
 * manual, audit-trailed stop of D10), PAUSE (the temporary stop), and
 * RESUME. There is NO auto-halt anywhere (D10): threshold-triggered
 * automation is the S16-11 alerts story, which only notifies.
 *
 * <p><strong>The device membership is computed, never stored</strong>
 * (D6): devices are not enumerable the way the V48 tenant cohorts
 * are, so no cohort table exists -- the S16-07 check route composes
 * {@link com.shipperdog.api.otabundle.OtaDeviceBucketing} with the
 * {@link OtaRolloutAccess} standing this service serves, and the
 * bucketing answers from {@code hash(device_id, plan_salt) mod 10000
 * < stage.basis_points} -- stable per device across restarts because
 * it is pure arithmetic over a canonical digest.
 *
 * <p><strong>Every act leaves three records in one
 * transaction</strong>: its {@code ota_rollout_events} row (the
 * append-only timeline), its audit-events entry, and its bus event
 * (the outbox recorder makes it durable under the same commit,
 * ADR-0007). The V56 event vocabulary lacked a PAUSED action for the
 * pause it otherwise names -- V60 widened the CHECK by exactly that
 * value, the gap-fix migration the issue anticipated.
 *
 * <p><strong>Concurrency</strong>: the plan row carries V56's
 * optimistic-lock {@code version} (the V48 mirror); every control
 * action loads, mutates, saves, and FLUSHES inside its guard (the
 * #181 deferred-write lesson) so a concurrent act on the same plan
 * translates HERE into the module's 409 -- never a last-write-win
 * across a checkpoint, never a deferred-commit 500.
 *
 * <p><strong>Scope</strong> (V56's own grain): the plan triple is
 * org-owned -- no tenant column -- so every plan read and write rides
 * the seam bound in ORGANIZATION scope, established from the
 * addressed application's owning organization. The release and
 * bundle guards, whose rows carry the application's own tenant grain,
 * run under the application's own scope binding FIRST (the
 * establish-only executor forbids nesting); the two bindings sequence
 * within one transaction. Cross-organization addresses conceal as
 * the honest 404 through both bindings alike.
 */
@Service
public class OtaRolloutService implements OtaRolloutAccess {

    /** The plan's bound: a widening ladder beyond ten stages is a staircase nobody climbs (the V48 mirror). */
    private static final int MAX_STAGES = 10;

    /** The timeline detail's column bound (V56 {@code ota_rollout_events.detail}). */
    private static final int DETAIL_BOUND = 512;

    /** The audit detail's practical bound (the V48 audit line's own cap). */
    private static final int AUDIT_DETAIL_BOUND = 380;

    /** The plan salt's byte entropy: 16 bytes hex-encoded (32 chars, inside V56's varchar(64)). */
    private static final int SALT_BYTES = 16;

    private static final SecureRandom SALT_RANDOM = new SecureRandom();

    private final OtaRolloutPlanScopedRepository plans;
    private final OtaRolloutStageRepository stageRows;
    private final OtaRolloutEventRepository timeline;
    private final OtaRolloutRegistryRepository registry;
    private final OtaBundleScopedRepository bundles;
    private final ApplicationAccess applications;
    private final ReleaseAccess releases;
    private final ReleaseRolloutPolicyGate policyGate;
    private final TenantScopeExecutor scopeExecutor;
    private final ApplicationEventPublisher events;
    private final AuditLogger audit;
    private final Clock clock;

    OtaRolloutService(
            OtaRolloutPlanScopedRepository plans,
            OtaRolloutStageRepository stageRows,
            OtaRolloutEventRepository timeline,
            OtaRolloutRegistryRepository registry,
            OtaBundleScopedRepository bundles,
            ApplicationAccess applications,
            ReleaseAccess releases,
            ReleaseRolloutPolicyGate policyGate,
            TenantScopeExecutor scopeExecutor,
            ApplicationEventPublisher events,
            AuditLogger audit,
            Clock clock) {
        this.plans = plans;
        this.stageRows = stageRows;
        this.timeline = timeline;
        this.registry = registry;
        this.bundles = bundles;
        this.applications = applications;
        this.releases = releases;
        this.policyGate = policyGate;
        this.scopeExecutor = scopeExecutor;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // The attachment (the commencement): one plan, born RUNNING
    // -------------------------------------------------------------------------

    /**
     * Attaches the release's rollout plan -- and thereby COMMENCES it
     * (the V56 shape has no pre-running standing: the plan is born
     * RUNNING, stage 1 REACHED, a COMMENCED timeline row). The release
     * must be the addressed application's own, LIVE (ACTIVE or
     * PROMOTED -- distributing to devices), and an OTA release (its
     * exactly-one RELEASED bundle linkage, the S16-05 rule); one plan
     * per release, ever. Stages are the cumulative basis-point ladder:
     * strictly ascending, 1..10000, the final stage 10000. A
     * single-stage plan of 10000 commences straight to COMPLETE.
     */
    @Transactional
    public OtaRolloutView attach(UUID actorId, AttachCommand command) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        Mode mode = parseMode(command.mode(), fieldErrors);
        List<StageRequest> stages = parseStages(command.stages(), fieldErrors);
        if (!fieldErrors.isEmpty()) {
            throw invalid("the rollout plan request is invalid", fieldErrors);
        }
        ApplicationProfile application =
                applications.profileOf(command.applicationId()).orElseThrow(OtaRolloutService::applicationMissing);
        ReleaseFacts facts = guardRelease(application, actorId, command.releaseId());
        return inOrganizationScope(application, actorId, () -> {
            if (plans.existsScoped(ofRelease(command.releaseId()))) {
                throw new OtaBundleException(
                        OtaBundleException.Code.ROLLOUT_CONFLICT, "the release already carries its one rollout plan");
            }
            Instant now = Instant.now(clock);
            OtaRolloutPlanEntity plan = new OtaRolloutPlanEntity(
                    command.releaseId(),
                    application.organizationId(),
                    application.applicationId(),
                    facts.versionId(),
                    mode,
                    freshSalt(),
                    actorId);
            plans.saveScoped(plan);
            for (int i = 0; i < stages.size(); i++) {
                StageRequest stage = stages.get(i);
                stageRows.save(
                        new OtaRolloutStageEntity(plan.getId(), i + 1, stage.basisPoints(), stage.note(), i == 0, now));
            }
            boolean terminal = stages.size() == 1;
            if (terminal) {
                plan.move(Status.COMPLETE);
                plans.saveScoped(plan);
            }
            Instant commencedAt = Instant.now(clock);
            record(plan, Action.COMMENCED, 1, "stages=" + stages.size() + ";mode=" + mode.name(), actorId, commencedAt);
            if (terminal) {
                record(plan, Action.COMPLETED, 1, "basis=10000", actorId, commencedAt.plusNanos(1_000));
            }
            try {
                // Push every insert to the database inside the guard so
                // the duplicate-plan race translates HERE (V56's
                // uq_ota_rollout_plans_release), the #181 lesson.
                plans.flush();
            } catch (DataIntegrityViolationException e) {
                throw new OtaBundleException(
                        OtaBundleException.Code.ROLLOUT_CONFLICT, "the release already carries its one rollout plan");
            }
            auditLine(
                    plan,
                    AuditEventType.OTA_ROLLOUT_COMMENCED,
                    "COMMENCED",
                    "stages=" + stages.size() + ";mode=" + mode.name(),
                    facts.tenantId(),
                    actorId,
                    true);
            publish(
                    plan,
                    Action.COMMENCED,
                    Status.RUNNING.name(),
                    plan.getStatus().name(),
                    1,
                    "stages=" + stages.size() + ";mode=" + mode.name(),
                    facts.tenantId(),
                    actorId,
                    now);
            if (terminal) {
                auditLine(
                        plan,
                        AuditEventType.OTA_ROLLOUT_COMPLETED,
                        "COMPLETED",
                        "basis=10000",
                        facts.tenantId(),
                        actorId,
                        true);
                publish(
                        plan,
                        Action.COMPLETED,
                        Status.RUNNING.name(),
                        plan.getStatus().name(),
                        1,
                        "basis=10000",
                        facts.tenantId(),
                        actorId,
                        now);
            }
            return viewOf(plan);
        });
    }

    // -------------------------------------------------------------------------
    // The control actions: ADVANCE, HALT, PAUSE, RESUME
    // -------------------------------------------------------------------------

    /**
     * One rollout control action: every act re-resolves the plan
     * inside the organization scope, refuses the precise conflict of
     * its standing, writes its timeline row + audit line + bus event,
     * and flushes inside the guard so a concurrent act on the same
     * plan answers the optimistic-lock 409 (the V48 pattern), never a
     * lost update. HALT is the manual, audit-trailed stop of D10; no
     * code path halts on its own.
     */
    @Transactional
    public OtaRolloutView control(UUID actorId, UUID applicationId, UUID releaseId, String action, String reason) {
        String act = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        ApplicationProfile application =
                applications.profileOf(applicationId).orElseThrow(OtaRolloutService::applicationMissing);
        // The AUTOMATIC mode's policy consult runs BEFORE the plan's
        // own scope binding (the gate binds the release's tenant scope
        // for its engine reads, and the executor is establish-only --
        // the V48 control path consults unbound, exactly for this
        // reason). The mode is resolved by the registry probe (no
        // binding, the standing seam's own doctrine) -- immutable
        // after attachment, so the pre-act read is the act's own
        // truth. A CHECKPOINT advance consults nothing: the advance
        // IS the approval.
        if ("ADVANCE".equals(act)) {
            OtaRolloutPlanEntity probed = registry.findByReleaseId(releaseId)
                    .filter(plan -> plan.getApplicationId().equals(applicationId))
                    .orElse(null);
            if (probed != null && probed.getMode() == Mode.AUTOMATIC) {
                String ring =
                        releases.distributionEffectiveRingCodeOf(releaseId).orElse(null);
                Optional<String> denial =
                        policyGate.rolloutDenialOf(application.organizationId(), releaseId, ring, actorId);
                if (denial.isPresent()) {
                    // The refusal's evidence: an audit line of its own
                    // (the #45 discipline), no timeline row -- the plan
                    // timeline records acts, and this act did not
                    // happen. The consult's evaluation row committed in
                    // the gate's own transaction and survives the 409.
                    auditLine(
                            probed,
                            AuditEventType.OTA_ROLLOUT_ADVANCED,
                            "ADVANCE-BLOCKED",
                            "policy:" + denial.get(),
                            tenantIdOf(releaseId),
                            actorId,
                            false);
                    throw new OtaBundleException(
                            OtaBundleException.Code.ROLLOUT_BLOCKED, "the advancement is blocked: " + denial.get());
                }
            }
        }
        try {
            return inOrganizationScope(application, actorId, () -> switch (act) {
                case "ADVANCE" -> advance(actorId, application, releaseId);
                case "HALT" -> halt(actorId, application, releaseId, reason);
                case "PAUSE" -> pause(actorId, application, releaseId, reason);
                case "RESUME" -> resume(actorId, application, releaseId, reason);
                default ->
                    throw new OtaBundleException(
                            OtaBundleException.Code.ROLLOUT_ACTION_INVALID,
                            "the rollout action must be one of ADVANCE, HALT, PAUSE, or RESUME");
            });
        } catch (OptimisticLockingFailureException e) {
            throw new OtaBundleException(
                    OtaBundleException.Code.ROLLOUT_CONFLICT, "the rollout changed concurrently; reload and retry");
        }
    }

    /**
     * The advancement (the V48 engine's OTA mirror): the next stage's
     * basis points. A HALTED or PAUSED plan refuses -- resume first;
     * a COMPLETE plan has nothing to advance. The AUTOMATIC mode's
     * policy consult already ran in {@link #control} (outside the
     * scope binding, before the act); a CHECKPOINT advance IS the
     * operator's stage approval. The final stage's ADVANCED carries
     * the plan across to COMPLETE.
     */
    private OtaRolloutView advance(UUID actorId, ApplicationProfile application, UUID releaseId) {
        OtaRolloutPlanEntity plan = planOf(application, releaseId);
        requireRunning(plan, "advance");
        List<OtaRolloutStageEntity> stages = stageRows.findByPlanIdOrderByPositionAsc(plan.getId());
        OtaRolloutStageEntity next = stages.stream()
                .filter(stage -> stage.getPosition() == plan.getCurrentStage() + 1)
                .findFirst()
                .orElseThrow(() -> new OtaBundleException(
                        OtaBundleException.Code.ROLLOUT_ACTION_INVALID,
                        "the final stage is reached; the rollout is complete"));
        UUID tenantId = tenantIdOf(releaseId);
        Instant now = Instant.now(clock);
        next.reach(now);
        stageRows.save(next);
        plan.serve(next.getPosition());
        boolean terminal = next.getPosition() == stages.get(stages.size() - 1).getPosition();
        String from = plan.getStatus().name();
        if (terminal) {
            plan.move(Status.COMPLETE);
        }
        plans.saveScoped(plan);
        record(plan, Action.ADVANCED, next.getPosition(), "basis=" + next.getBasisPoints(), actorId, now);
        if (terminal) {
            record(
                    plan,
                    Action.COMPLETED,
                    next.getPosition(),
                    "basis=" + next.getBasisPoints(),
                    actorId,
                    now.plusNanos(1_000));
        }
        plans.flush();
        auditLine(
                plan,
                AuditEventType.OTA_ROLLOUT_ADVANCED,
                "ADVANCED",
                "stage=" + next.getPosition() + ";basis=" + next.getBasisPoints(),
                tenantId,
                actorId,
                true);
        publish(
                plan,
                Action.ADVANCED,
                from,
                plan.getStatus().name(),
                next.getPosition(),
                "basis=" + next.getBasisPoints(),
                tenantId,
                actorId,
                now);
        if (terminal) {
            auditLine(
                    plan,
                    AuditEventType.OTA_ROLLOUT_COMPLETED,
                    "COMPLETED",
                    "stage=" + next.getPosition(),
                    tenantId,
                    actorId,
                    true);
            publish(
                    plan,
                    Action.COMPLETED,
                    from,
                    plan.getStatus().name(),
                    next.getPosition(),
                    "basis=" + next.getBasisPoints(),
                    tenantId,
                    actorId,
                    now);
        }
        return viewOf(plan);
    }

    /**
     * The halt (D10): the manual, audit-trailed stop -- the one action
     * that ends the rollout's serving until an operator resumes it.
     * The reason is mandatory (the halted rollout says why, the FR-024
     * doctrine); the S16-07 check resolution reads the HALTED standing
     * and answers no-update. A PAUSED plan is still stoppable -- pause
     * was only ever the temporary stop.
     */
    private OtaRolloutView halt(UUID actorId, ApplicationProfile application, UUID releaseId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new OtaBundleException(
                    OtaBundleException.Code.INVALID_REQUEST, "a halt requires a reason (the halted rollout says why)");
        }
        OtaRolloutPlanEntity plan = planOf(application, releaseId);
        if (plan.getStatus() == Status.COMPLETE) {
            throw actionInvalid("the rollout is complete; there is nothing to halt");
        }
        if (plan.getStatus() == Status.HALTED) {
            throw actionInvalid("the rollout is already halted");
        }
        return cross(actorId, application, releaseId, plan, Action.HALTED, Status.HALTED, "reason=" + reason.trim());
    }

    /**
     * The pause: the temporary operator stop. Serving freezes at the
     * current stage (no stage retreats -- the ladder only widens) and
     * resumes exactly where it froze.
     */
    private OtaRolloutView pause(UUID actorId, ApplicationProfile application, UUID releaseId, String reason) {
        OtaRolloutPlanEntity plan = planOf(application, releaseId);
        requireRunning(plan, "pause");
        return cross(actorId, application, releaseId, plan, Action.PAUSED, Status.PAUSED, detailOf("paused", reason));
    }

    /**
     * The resume: back to RUNNING -- from HALTED (the halt's reversal)
     * or from PAUSED. The serving stage is unchanged: resuming widens
     * nothing by itself, it serves again what the plan had reached.
     */
    private OtaRolloutView resume(UUID actorId, ApplicationProfile application, UUID releaseId, String reason) {
        OtaRolloutPlanEntity plan = planOf(application, releaseId);
        if (plan.getStatus() != Status.HALTED && plan.getStatus() != Status.PAUSED) {
            throw actionInvalid("the rollout is " + plan.getStatus().name().toLowerCase(Locale.ROOT)
                    + "; there is nothing to resume");
        }
        return cross(
                actorId, application, releaseId, plan, Action.RESUMED, Status.RUNNING, detailOf("resumed", reason));
    }

    /** One crossing's shared tail: the write, the flush, the three records, the view. */
    private OtaRolloutView cross(
            UUID actorId,
            ApplicationProfile application,
            UUID releaseId,
            OtaRolloutPlanEntity plan,
            Action action,
            Status to,
            String detail) {
        Instant now = Instant.now(clock);
        String from = plan.getStatus().name();
        plan.move(to);
        plans.saveScoped(plan);
        record(plan, action, plan.getCurrentStage(), detail, actorId);
        plans.flush();
        UUID tenantId = tenantIdOf(releaseId);
        auditLine(plan, auditTypeOf(action), action.name(), detail, tenantId, actorId, true);
        publish(plan, action, from, to.name(), plan.getCurrentStage(), detail, tenantId, actorId, now);
        return viewOf(plan);
    }

    // -------------------------------------------------------------------------
    // The view (the status read)
    // -------------------------------------------------------------------------

    /**
     * The rollout's served view: the plan's mode and standing, every
     * stage of the ladder, and the append-only timeline. A foreign or
     * unknown release-and-application pair conceals as the honest 404.
     */
    @Transactional(readOnly = true)
    public OtaRolloutView view(UUID actorId, UUID applicationId, UUID releaseId) {
        ApplicationProfile application =
                applications.profileOf(applicationId).orElseThrow(OtaRolloutService::applicationMissing);
        return inOrganizationScope(application, actorId, () -> viewOf(planOf(application, releaseId)));
    }

    // -------------------------------------------------------------------------
    // The S16-07 seam (OtaRolloutAccess): the standing the check route composes
    // -------------------------------------------------------------------------

    /**
     * The release's rollout standing for the device-check resolution:
     * status, serving stage, its cumulative basis points, and the
     * salt. Answered through the {@link OtaRolloutRegistryRepository}
     * probe ({@code CrossTenantByDesign}, the {@code
     * telemetryFactsOf} doctrine): the S16-07 check route runs on a
     * device surface whose thread binds no operator scope, so the
     * seam cannot serve this read -- the standing TUPLE crosses (the
     * bucketing facts), never a row. A release that is not the named
     * application's own, or carries no plan, answers empty.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<RolloutStanding> rolloutStandingOf(UUID applicationId, UUID releaseId) {
        if (applicationId == null || releaseId == null) {
            return Optional.empty();
        }
        return registry.findByReleaseId(releaseId)
                .filter(plan -> plan.getApplicationId().equals(applicationId))
                .map(plan -> {
                    int basis = stageRows.findByPlanIdOrderByPositionAsc(plan.getId()).stream()
                            .filter(stage -> stage.getPosition() == plan.getCurrentStage())
                            .map(OtaRolloutStageEntity::getBasisPoints)
                            .findFirst()
                            .orElse(com.shipperdog.api.otabundle.OtaDeviceBucketing.BUCKET_SPACE);
                    return new RolloutStanding(
                            releaseId, plan.getStatus().name(), plan.getCurrentStage(), basis, plan.getPlanSalt());
                });
    }

    // -------------------------------------------------------------------------
    // Guards and resolution
    // -------------------------------------------------------------------------

    /**
     * The attachment's release guard, under the application's own
     * scope binding (the release and bundle rows carry the
     * application's grain): the release must be the addressed
     * application's own (the concealed miss otherwise), LIVE
     * (distributing), and OTA (its exactly-one RELEASED bundle
     * linkage).
     */
    private ReleaseFacts guardRelease(ApplicationProfile application, UUID actorId, UUID releaseId) {
        if (releaseId == null) {
            throw invalid("the rollout plan request is invalid", Map.of("releaseId", "releaseId is required"));
        }
        return inScope(application, actorId, () -> {
            ReleaseFacts facts = releases.factsOf(releaseId)
                    .filter(f -> f.organizationId().equals(application.organizationId()))
                    .filter(f -> f.applicationId().equals(application.applicationId()))
                    .orElseThrow(OtaRolloutService::rolloutMissing);
            if (!"ACTIVE".equals(facts.status()) && !"PROMOTED".equals(facts.status())) {
                throw new OtaBundleException(
                        OtaBundleException.Code.ROLLOUT_RELEASE_NOT_LIVE,
                        "the release is " + facts.status() + "; a rollout plan commences on a live (ACTIVE or"
                                + " PROMOTED) release");
            }
            bundles.findOneScoped((root, query, builder) -> builder.equal(root.get("releaseId"), releaseId))
                    .filter(bundle -> bundle.getStatus() == OtaBundleEntity.Status.RELEASED)
                    .orElseThrow(() -> new OtaBundleException(
                            OtaBundleException.Code.ROLLOUT_RELEASE_NOT_OTA,
                            "the release carries no OTA bundle; rollout plans belong to OTA releases"));
            return facts;
        });
    }

    /** The org-guarded plan resolution (the concealed miss across applications and organizations). */
    private OtaRolloutPlanEntity planOf(ApplicationProfile application, UUID releaseId) {
        return plans.findOneScoped(ofRelease(releaseId))
                .filter(plan -> plan.getApplicationId().equals(application.applicationId()))
                .orElseThrow(OtaRolloutService::rolloutMissing);
    }

    private static void requireRunning(OtaRolloutPlanEntity plan, String act) {
        if (plan.getStatus() == Status.COMPLETE) {
            throw actionInvalid("the rollout is complete; every stage reached");
        }
        if (plan.getStatus() == Status.HALTED) {
            throw actionInvalid("the rollout is halted; resume it before a " + act);
        }
        if (plan.getStatus() == Status.PAUSED) {
            throw actionInvalid("the rollout is paused; resume it before a " + act);
        }
    }

    /** The release's owning tenant for fan-out routing, registry-probed (no seam: org-grain caller). */
    private UUID tenantIdOf(UUID releaseId) {
        return releases.distributionFactsOf(releaseId)
                .map(ReleaseFacts::tenantId)
                .orElse(null);
    }

    private <T> T inScope(ApplicationProfile application, UUID actorId, java.util.function.Supplier<T> work) {
        return application.tenantId() != null
                ? scopeExecutor.inScope(application.tenantId(), actorId, work)
                : scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    private <T> T inOrganizationScope(
            ApplicationProfile application, UUID actorId, java.util.function.Supplier<T> work) {
        return scopeExecutor.inOrganizationScope(application.organizationId(), actorId, work);
    }

    // -------------------------------------------------------------------------
    // The three records: timeline row, audit line, bus event
    // -------------------------------------------------------------------------

    private void record(OtaRolloutPlanEntity plan, Action action, Integer stagePosition, String detail, UUID actorId) {
        record(plan, action, stagePosition, detail, actorId, Instant.now(clock));
    }

    /**
     * The timeline row's write. The occurred-at is the CALLER's when
     * one act writes two rows (a terminal advancement: ADVANCED then
     * COMPLETED): the completion rides {@code occurredAt} bumped one
     * MICROSECOND past the advancement -- same act, strictly ordered,
     * because the timeline's read order is {@code occurred_at}, a
     * same-instant pair would render in id (random) order, and
     * PostgreSQL's timestamptz keeps microseconds (a nanosecond bump
     * would truncate back into the tie).
     */
    private void record(
            OtaRolloutPlanEntity plan,
            Action action,
            Integer stagePosition,
            String detail,
            UUID actorId,
            Instant occurredAt) {
        String bounded = detail == null || detail.length() <= DETAIL_BOUND ? detail : detail.substring(0, DETAIL_BOUND);
        timeline.save(new OtaRolloutEventEntity(plan.getId(), action, stagePosition, bounded, actorId, occurredAt));
    }

    private void auditLine(
            OtaRolloutPlanEntity plan,
            AuditEventType type,
            String action,
            String detail,
            UUID tenantId,
            UUID actorId,
            boolean success) {
        Map<String, String> detailMap = new LinkedHashMap<>();
        detailMap.put("releaseId", plan.getReleaseId().toString());
        detailMap.put("action", action);
        if (detail != null) {
            detailMap.put(
                    "detail", detail.length() > AUDIT_DETAIL_BOUND ? detail.substring(0, AUDIT_DETAIL_BOUND) : detail);
        }
        AuditEvent.Builder line = AuditEvent.builder(type)
                .orgId(plan.getOrganizationId())
                .tenantId(tenantId)
                .applicationId(plan.getApplicationId())
                .versionId(plan.getVersionId())
                .detail(detailMap)
                .success(success);
        if (actorId != null) {
            line.userId(actorId);
        }
        audit.record(line.build());
    }

    private void publish(
            OtaRolloutPlanEntity plan,
            Action action,
            String fromStatus,
            String toStatus,
            Integer stagePosition,
            String detail,
            UUID tenantId,
            UUID actorId,
            Instant occurredAt) {
        events.publishEvent(new OtaRolloutTransitionedEvent(
                plan.getId(),
                plan.getReleaseId(),
                plan.getApplicationId(),
                plan.getVersionId(),
                plan.getOrganizationId(),
                tenantId,
                action.name(),
                fromStatus,
                toStatus,
                stagePosition,
                detail,
                actorId,
                occurredAt));
    }

    private static AuditEventType auditTypeOf(Action action) {
        return switch (action) {
            case COMMENCED -> AuditEventType.OTA_ROLLOUT_COMMENCED;
            case ADVANCED -> AuditEventType.OTA_ROLLOUT_ADVANCED;
            case COMPLETED -> AuditEventType.OTA_ROLLOUT_COMPLETED;
            case HALTED -> AuditEventType.OTA_ROLLOUT_HALTED;
            case PAUSED -> AuditEventType.OTA_ROLLOUT_PAUSED;
            case RESUMED -> AuditEventType.OTA_ROLLOUT_RESUMED;
        };
    }

    // -------------------------------------------------------------------------
    // Shape validation
    // -------------------------------------------------------------------------

    private static Mode parseMode(String mode, Map<String, String> errors) {
        String normalized = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "AUTOMATIC" -> Mode.AUTOMATIC;
            case "CHECKPOINT" -> Mode.CHECKPOINT;
            default -> {
                errors.put(
                        "mode",
                        "mode must be one of AUTOMATIC or CHECKPOINT (the plan either widens on the"
                                + " policy gate's verdict or on an operator's explicit stage approval)");
                yield null;
            }
        };
    }

    /**
     * The cumulative ladder's shape: 1..{@value #MAX_STAGES} stages,
     * each 1..10000 basis points, STRICTLY ascending (stage N contains
     * everything stage N-1 reached), the final stage exactly 10000
     * (a rollout's own end is everyone served).
     */
    private static List<StageRequest> parseStages(List<StageRequest> stages, Map<String, String> errors) {
        if (stages == null || stages.isEmpty() || stages.size() > MAX_STAGES) {
            errors.put("stages", "the plan needs between 1 and " + MAX_STAGES + " stages");
            return List.of();
        }
        Integer previous = null;
        for (int i = 0; i < stages.size(); i++) {
            StageRequest stage = stages.get(i);
            if (stage == null
                    || stage.basisPoints() == null
                    || stage.basisPoints() < 1
                    || stage.basisPoints() > 10000) {
                errors.put("stages." + i + ".basisPoints", "basisPoints is a cumulative threshold of 1 to 10000");
                previous = null;
                continue;
            }
            if (stage.note() != null
                    && (stage.note().isBlank() || stage.note().trim().length() > 512)) {
                errors.put("stages." + i + ".note", "note is 1 to 512 characters when present");
            }
            if (previous != null && stage.basisPoints() <= previous) {
                errors.put(
                        "stages." + i + ".basisPoints",
                        "basisPoints must strictly ascend (the ladder only" + " widens: stage " + (i + 1)
                                + " must exceed stage " + i + "'s " + previous + ")");
            }
            previous = stage.basisPoints();
        }
        int last = stages.size() - 1;
        if (stages.get(last) == null
                || stages.get(last).basisPoints() == null
                || stages.get(last).basisPoints() != 10000) {
            errors.put(
                    "stages." + last + ".basisPoints",
                    "the final stage must carry 10000 (the rollout's end is" + " everyone served)");
        }
        return stages;
    }

    private static String freshSalt() {
        byte[] salt = new byte[SALT_BYTES];
        SALT_RANDOM.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    private static String detailOf(String act, String reason) {
        return reason == null || reason.isBlank() ? act : act + ";reason=" + reason.trim();
    }

    private static Specification<OtaRolloutPlanEntity> ofRelease(UUID releaseId) {
        return (root, query, builder) -> builder.equal(root.get("releaseId"), releaseId);
    }

    private static OtaBundleException applicationMissing() {
        return new OtaBundleException(OtaBundleException.Code.APPLICATION_NOT_FOUND, "application not found");
    }

    private static OtaBundleException rolloutMissing() {
        return new OtaBundleException(OtaBundleException.Code.ROLLOUT_NOT_FOUND, "the release carries no rollout plan");
    }

    private static OtaBundleException actionInvalid(String reason) {
        return new OtaBundleException(OtaBundleException.Code.ROLLOUT_ACTION_INVALID, reason);
    }

    private static OtaBundleException invalid(String message) {
        return new OtaBundleException(OtaBundleException.Code.INVALID_REQUEST, message);
    }

    private static OtaBundleException invalid(String message, Map<String, String> fieldErrors) {
        return new OtaBundleException(OtaBundleException.Code.INVALID_REQUEST, message, fieldErrors);
    }

    // -------------------------------------------------------------------------
    // The served shapes
    // -------------------------------------------------------------------------

    /** The attachment request as the controller parsed it. */
    public record AttachCommand(UUID applicationId, UUID releaseId, String mode, List<StageRequest> stages) {}

    /** One ladder stage at attachment: its cumulative basis points and the operator's note. */
    public record StageRequest(Integer basisPoints, String note) {}

    /** The rollout's served view: the plan, the ladder, the timeline. */
    public record OtaRolloutView(
            UUID releaseId,
            UUID applicationId,
            String mode,
            String status,
            Integer currentStage,
            String planSalt,
            List<RolloutStageView> stages,
            List<RolloutEventView> timeline) {

        public OtaRolloutView {
            stages = List.copyOf(stages);
            timeline = List.copyOf(timeline);
        }
    }

    /** One stage: its cumulative threshold, its note, and its standing. */
    public record RolloutStageView(int position, int basisPoints, String note, String status, Instant reachedAt) {}

    /** One timeline row (the append-only ledger the console renders beside the audit trail). */
    public record RolloutEventView(
            String action, Integer stagePosition, String detail, UUID actorId, Instant occurredAt) {}

    /** The view of a resolved plan: the ladder and the timeline from the guarded plan id. */
    private OtaRolloutView viewOf(OtaRolloutPlanEntity plan) {
        List<RolloutStageView> stages = stageRows.findByPlanIdOrderByPositionAsc(plan.getId()).stream()
                .map(stage -> new RolloutStageView(
                        stage.getPosition(),
                        stage.getBasisPoints(),
                        stage.getNote(),
                        stage.getStatus().name(),
                        stage.getReachedAt()))
                .toList();
        List<RolloutEventView> events = timeline.findByPlanIdOrderByOccurredAtAscIdAsc(plan.getId()).stream()
                .map(row -> new RolloutEventView(
                        row.getAction().name(),
                        row.getStagePosition(),
                        row.getDetail(),
                        row.getActorId(),
                        row.getOccurredAt()))
                .toList();
        return new OtaRolloutView(
                plan.getReleaseId(),
                plan.getApplicationId(),
                plan.getMode().name(),
                plan.getStatus().name(),
                plan.getCurrentStage(),
                plan.getPlanSalt(),
                stages,
                events);
    }
}
