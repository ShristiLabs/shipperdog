package com.shipperdog.api.distribution.internal.google;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.AdapterException;
import com.shipperdog.api.distribution.AdapterOutcome;
import com.shipperdog.api.distribution.DistributionCommand;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.distribution.internal.entity.ChannelRolloutEntity;
import com.shipperdog.api.distribution.internal.entity.ChannelRolloutStepEntity;
import com.shipperdog.api.distribution.internal.repository.ChannelRolloutRepository;
import com.shipperdog.api.distribution.internal.repository.ChannelRolloutStepRepository;
import com.shipperdog.api.distribution.internal.secret.ChannelSecretStore;
import com.shipperdog.api.distribution.internal.service.AdapterCallSandbox;
import com.shipperdog.api.distribution.internal.service.ChannelDistributionReported;
import com.shipperdog.api.distribution.internal.service.DistributionCredentialService;
import com.shipperdog.api.environment.EnvironmentAccess;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseRolloutPolicyGate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Google Play staged-rollout surface (issue #65, S13-05, AC1-AC5,
 * FR-021, PRD 17/33): the release actions that govern a production
 * release's rollout percentage — the intent model the story's
 * technical note names. ShipperDog owns the INTENDED curve (the
 * {@code channel_rollouts} row: what the operator asked Google to
 * serve), Play owns the mechanics ({@code userFraction} on the
 * in-progress production track release), and every control action —
 * ADVANCE, HALT, RESUME, REAPPLY — applies the intent through the
 * Play API inside the same sandbox every adapter conversation runs
 * under, records its step on the append-only timeline with its actor
 * and percentages (AC3), and audits the act.
 *
 * <p><strong>Drift is reported, never silently corrected (AC5):</strong>
 * the view reads the live track and answers the OBSERVED percentage
 * beside the intent — a manual Play Console change surfaces as drift
 * on the dashboard and the audit line, and the explicit REAPPLY
 * action is the operator's own act of re-writing the intent (the
 * story's "report + re-apply intent explicitly, never silently").
 *
 * <p><strong>The policy gate (AC4):</strong> an ADVANCE is a
 * promotion-grade act — the production ring's BLOCK/HOLD rules
 * (S10-06's engine, the same evaluation the promotion precheck runs)
 * refuse the increase while the release's metrics fail their
 * thresholds, with the rule's evidence in the 409 — and the refusal's
 * EVALUATION evidence commits in the consult's own transaction, so it
 * survives the 409's rollback exactly a refused promotion's does (the
 * #45 discipline). HALT never consults the gate: stopping
 * distribution is always allowed; the gate exists so a bad release
 * stops reaching users, never to keep one going.
 *
 * <p><strong>The control answers from the write's own echo:</strong>
 * the action's answer is the provider's echo of the track release the
 * control just wrote — the store's own confirmation of the act — and
 * never a closing re-read of the live track inside the control's
 * transaction. A transient-exhausted read AFTER a successful provider
 * write must never roll back platform state for an action Google
 * already applied; the view's own read (a separate transaction, the
 * dashboard's) refreshes the observed standing and reports drift.
 *
 * <p><strong>Concurrent controls refuse, never last-write-win:</strong>
 * the intent row carries an optimistic {@code version} stamp (the
 * #51 subscription precedent); a control that loses the race answers
 * the precise 409 {@code DISTRIBUTION_ROLLOUT_CONFLICT} — two
 * concurrent ADVANCEs can never lower the curve or double-apply a
 * step.
 * <p><strong>The first write (AC1):</strong> the production dispatch's
 * landing publishes the framework-generic {@link
 * ChannelDistributionReported} event; this service's listener writes
 * the rollout state at the CONFIGURED initial percentage (the
 * environment's {@code play-rollout-percent} key — the same source
 * the adapter wrote from) with the START step on the timeline. The
 * listener commits inside the crossing's transaction: the state and
 * the dispatch it belongs to land or roll back together.
 */
@Service
public class PlayRolloutService {

    private static final Logger log = LoggerFactory.getLogger(PlayRolloutService.class);

    private static final String CHANNEL = "play";

    /** The advance action: the curve widens to a strictly greater percentage (AC1). */
    public static final String ACTION_ADVANCE = "ADVANCE";

    /** The halt action: distribution stops at the current percentage (AC2). */
    public static final String ACTION_HALT = "HALT";

    /** The resume action: a halted curve continues from its percentage (AC2). */
    public static final String ACTION_RESUME = "RESUME";

    /** The explicit re-apply of intent after drift (AC5 — never silent). */
    public static final String ACTION_REAPPLY = "REAPPLY";

    private final ChannelRolloutRepository rollouts;
    private final ChannelRolloutStepRepository steps;
    private final DistributionCredentialService credentials;
    private final ChannelSecretStore secrets;
    private final ReleaseAccess releases;
    private final EnvironmentAccess environments;
    private final ReleaseRolloutPolicyGate policyGate;
    private final GooglePlayDeveloperApiClient play;
    private final AdapterCallSandbox sandbox;
    private final AuditLogger audit;
    private final Clock clock;

    public PlayRolloutService(
            ChannelRolloutRepository rollouts,
            ChannelRolloutStepRepository steps,
            DistributionCredentialService credentials,
            ChannelSecretStore secrets,
            ReleaseAccess releases,
            EnvironmentAccess environments,
            ReleaseRolloutPolicyGate policyGate,
            GooglePlayDeveloperApiClient play,
            AdapterCallSandbox sandbox,
            AuditLogger audit,
            Clock clock) {
        this.rollouts = rollouts;
        this.steps = steps;
        this.credentials = credentials;
        this.secrets = secrets;
        this.releases = releases;
        this.environments = environments;
        this.policyGate = policyGate;
        this.play = play;
        this.sandbox = sandbox;
        this.audit = audit;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // The first write: the production dispatch's landing (AC1)
    // -------------------------------------------------------------------------

    /**
     * The dispatch's landing listener: a play dispatch whose outcome
     * addresses the PRODUCTION track writes the rollout state at the
     * configured initial percentage with the START step — once (a
     * replayed dispatch re-reports onto the records, but the rollout
     * state keeps its standing: advances already applied are the
     * platform's own record, never regressed by a replay).
     */
    @EventListener
    @Transactional
    void onDistributionReported(ChannelDistributionReported event) {
        if (!CHANNEL.equals(event.channel())) {
            return;
        }
        AdapterOutcome production = event.outcomes().stream()
                .filter(outcome -> PlayChannelAdapter.TRACK_PRODUCTION.equals(trackOf(outcome.externalId())))
                .findFirst()
                .orElse(null);
        if (production == null) {
            return;
        }
        if (rollouts.findByReleaseIdAndChannel(event.releaseId(), CHANNEL).isPresent()) {
            return;
        }
        int intended = configuredInitialPercentOf(event.releaseId());
        boolean fullList = intended >= 100;
        ChannelRolloutEntity state = new ChannelRolloutEntity(
                event.releaseId(),
                event.organizationId(),
                CHANNEL,
                production.externalId(),
                intended,
                fullList ? ChannelRolloutEntity.Status.COMPLETE : ChannelRolloutEntity.Status.ROLLING,
                event.ringCode() == null ? PlayChannelAdapter.TRACK_PRODUCTION : event.ringCode());
        state.observe(fullList ? 100 : intended);
        state = rollouts.save(state);
        steps.save(new ChannelRolloutStepEntity(
                state.getId(), ChannelRolloutStepEntity.Action.START, null, intended, null, Instant.now(clock)));
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("channel", CHANNEL);
        detail.put("releaseId", event.releaseId().toString());
        detail.put("action", ChannelRolloutStepEntity.Action.START.name());
        detail.put("toPercent", String.valueOf(intended));
        audit.record(AuditEvent.builder(AuditEventType.DISTRIBUTION_ROLLOUT_CONTROLLED)
                .orgId(event.organizationId())
                .applicationId(event.applicationId())
                .detail(detail)
                .success(true)
                .build());
    }

    /** The configured initial percentage (the adapter's own source: the environment's channel config). */
    private int configuredInitialPercentOf(UUID releaseId) {
        UUID environmentId = releases.distributionFactsOf(releaseId)
                .map(ReleaseAccess.ReleaseFacts::environmentId)
                .orElse(null);
        String configured = environmentId == null
                ? null
                : environments.configSnapshotOf(environmentId).entries().get(PlayChannelAdapter.CONFIG_ROLLOUT_PERCENT);
        if (configured == null || configured.isBlank()) {
            return 100;
        }
        try {
            int percent = Integer.parseInt(configured.trim());
            return percent >= 1 && percent <= 100 ? percent : 100;
        } catch (NumberFormatException e) {
            // The adapter refused the same fault loudly at the dispatch;
            // this read answers the honest full-list default — the
            // landing never reaches here with a faulted config.
            return 100;
        }
    }

    // -------------------------------------------------------------------------
    // The view: the curve's state for the dashboard (AC2/AC5)
    // -------------------------------------------------------------------------

    /**
     * The rollout's state (AC2/AC5): the intent, the OBSERVED
     * percentage read from the live track, the drift flag, the
     * provider's own status word, and the timeline — the dashboard's
     * rollout answer. The observation persists (the drift an audit
     * line reports is a fact of the record, not of the read), and a
     * provider-observed completion moves the curve to COMPLETE.
     *
     * @throws DistributionException DISTRIBUTION_ROLLOUT_NOT_FOUND for
     *                               the concealed miss (no rollout, or
     *                               a foreign organization's)
     */
    @Transactional
    public RolloutView view(UUID organizationId, UUID releaseId) {
        ChannelRolloutEntity state = rolloutOf(organizationId, releaseId);
        String[] reference = referenceOf(state.getExternalId());
        GooglePlayCredentials credential = credentialOf(state);
        long versionCode = Long.parseLong(reference[1]);
        AdapterCallSandbox.Result<ProviderObservation> observed = sandbox.run(
                CHANNEL, "rollout-view", attempt -> observe(credential, reference[2], reference[0], versionCode));
        if (observed.failure() != null) {
            throw problemOf(observed.failure());
        }
        ProviderObservation answer = observed.answer();
        if (answer == null) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_ROLLOUT_NOT_FOUND,
                    "the production track no longer carries the release's track release on the store side");
        }
        Integer previous = state.getObservedPercent();
        state.observe(answer.percent());
        if ("completed".equals(answer.status())) {
            state.move(ChannelRolloutEntity.Status.COMPLETE);
        }
        rollouts.save(state);
        boolean drift = answer.percent() != null && answer.percent() != state.getIntendedPercent();
        if (drift && (previous == null || previous != answer.percent())) {
            // The drift's FIRST observation is the report (the audit
            // line AC5 names); steady-state drift is the standing the
            // dashboard renders, never a new audit line per read.
            auditDrift(organizationId, state, answer);
        }
        return viewOf(state, answer, drift);
    }

    // -------------------------------------------------------------------------
    // The control: one release action on the curve (AC1/AC2/AC5)
    // -------------------------------------------------------------------------

    /**
     * One release action on the curve (AC1/AC2/AC5): ADVANCE to a
     * strictly greater percentage (the policy gate consulted first,
     * AC4), HALT (always allowed — stopping distribution is never a
     * policy question), RESUME from a halt, and REAPPLY of intent
     * after drift. Every action applies through the Play API inside
     * the sandbox, records its step on the timeline with the actor,
     * and audits; the answer is the WRITE'S OWN ECHO — the provider's
     * confirmation of the track release just written — persisted as
     * the curve's observation, never a closing re-read of the live
     * track (a transient-exhausted read after a successful write must
     * not roll back platform state for an act the store already
     * applied). A concurrent control of the same curve refuses with
     * the precise 409 ({@code DISTRIBUTION_ROLLOUT_CONFLICT}, the
     * optimistic version stamp) instead of last-write-winning.
     */
    @Transactional
    public RolloutView control(UUID organizationId, UUID releaseId, String action, Integer percent, UUID actorId) {
        String act = action == null ? "" : action.trim().toUpperCase(java.util.Locale.ROOT);
        ChannelRolloutEntity state = rolloutOf(organizationId, releaseId);
        ProviderObservation echo;
        try {
            echo = switch (act) {
                case ACTION_ADVANCE -> advance(state, percent, actorId);
                case ACTION_HALT -> halt(state, actorId);
                case ACTION_RESUME -> resume(state, actorId);
                case ACTION_REAPPLY -> reapply(state, actorId);
                default ->
                    throw new DistributionException(
                            DistributionException.Code.DISTRIBUTION_ROLLOUT_ACTION_INVALID,
                            "the rollout action must be one of ADVANCE, HALT, RESUME, or REAPPLY");
            };
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            // The version stamp's refusal, translated HERE (the actions
            // flush inside the method) — the stale action's precise
            // conflict, never a generic 500 and never a silent
            // last-write-win over the winner's curve.
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_ROLLOUT_CONFLICT,
                    "the rollout changed concurrently; reload and retry");
        }
        return viewOf(state, echo, false);
    }

    private ProviderObservation advance(ChannelRolloutEntity state, Integer percent, UUID actorId) {
        if (state.getStatus() != ChannelRolloutEntity.Status.ROLLING) {
            throw actionInvalid("an advance serves a rolling rollout (current state " + state.getStatus() + ")");
        }
        if (percent == null || percent < 1 || percent > 100) {
            throw actionInvalid("the advance percentage must be an integer between 1 and 100");
        }
        if (percent <= state.getIntendedPercent()) {
            throw actionInvalid("the advance percentage must be strictly greater than the curve's current intent ("
                    + state.getIntendedPercent() + "%)");
        }
        // The policy gate (AC4): the production ring's rules answer
        // the promotion-grade question of whether the curve may widen.
        Optional<String> denial = policyGate.rolloutDenialOf(
                state.getOrganizationId(), state.getReleaseId(), state.getRingCode(), actorId);
        if (denial.isPresent()) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_ROLLOUT_BLOCKED,
                    "the rollout increase to " + percent + "% is blocked: " + denial.get());
        }
        int from = state.getIntendedPercent();
        ProviderObservation echo = applyToProvider(state, "inProgress", percent / 100.0d);
        state.intend(percent);
        state.move(ChannelRolloutEntity.Status.ROLLING);
        state.observe(echo.percent());
        rollouts.saveAndFlush(state);
        stepAndAudit(state, ChannelRolloutStepEntity.Action.ADVANCE, from, percent, actorId);
        return echo;
    }

    private ProviderObservation halt(ChannelRolloutEntity state, UUID actorId) {
        if (state.getStatus() != ChannelRolloutEntity.Status.ROLLING) {
            throw actionInvalid("a halt serves a rolling rollout (current state " + state.getStatus() + ")");
        }
        int from = state.getIntendedPercent();
        ProviderObservation echo = applyToProvider(state, "halted", from / 100.0d);
        state.move(ChannelRolloutEntity.Status.HALTED);
        state.observe(echo.percent());
        rollouts.saveAndFlush(state);
        stepAndAudit(state, ChannelRolloutStepEntity.Action.HALT, from, from, actorId);
        return echo;
    }

    private ProviderObservation resume(ChannelRolloutEntity state, UUID actorId) {
        if (state.getStatus() != ChannelRolloutEntity.Status.HALTED) {
            throw actionInvalid("a resume serves a halted rollout (current state " + state.getStatus() + ")");
        }
        int at = state.getIntendedPercent();
        ProviderObservation echo = applyToProvider(state, "inProgress", at / 100.0d);
        state.move(ChannelRolloutEntity.Status.ROLLING);
        state.observe(echo.percent());
        rollouts.saveAndFlush(state);
        stepAndAudit(state, ChannelRolloutStepEntity.Action.RESUME, at, at, actorId);
        return echo;
    }

    private ProviderObservation reapply(ChannelRolloutEntity state, UUID actorId) {
        if (state.getStatus() == ChannelRolloutEntity.Status.COMPLETE) {
            throw actionInvalid("a re-apply serves a rollout still under control (current state COMPLETE)");
        }
        int at = state.getIntendedPercent();
        ProviderObservation echo = applyToProvider(
                state, state.getStatus() == ChannelRolloutEntity.Status.HALTED ? "halted" : "inProgress", at / 100.0d);
        state.observe(echo.percent() == null ? at : echo.percent());
        rollouts.saveAndFlush(state);
        stepAndAudit(state, ChannelRolloutStepEntity.Action.REAPPLY, at, at, actorId);
        return echo;
    }

    private static DistributionException actionInvalid(String reason) {
        return new DistributionException(DistributionException.Code.DISTRIBUTION_ROLLOUT_ACTION_INVALID, reason);
    }

    // -------------------------------------------------------------------------
    // The provider conversation
    // -------------------------------------------------------------------------

    /**
     * Applies one control write through the Play edit model: the
     * production track release rewritten with the action's status and
     * fraction (the release's own version codes and frozen notes
     * carried verbatim — a control action never re-uploads and never
     * rewrites what the dispatch sent). The answer is the write's OWN
     * ECHO — the provider's confirmation of the track release just
     * written — which the control persists as the curve's observation
     * instead of re-reading the live track (the store's answer to the
     * act is the honest observation of the act).
     */
    private ProviderObservation applyToProvider(ChannelRolloutEntity state, String status, double fraction) {
        GooglePlayCredentials credential = credentialOf(state);
        String[] reference = referenceOf(state.getExternalId());
        String packageName = reference[2];
        String track = reference[0];
        long versionCode = Long.parseLong(reference[1]);
        AdapterCallSandbox.Result<ProviderObservation> applied = sandbox.run(CHANNEL, "rollout-control", attempt -> {
            GooglePlayDeveloperApiClient.AppEdit edit = play.insertEdit(credential, packageName);
            GooglePlayDeveloperApiClient.Track editTrack = play.trackOfEdit(credential, packageName, edit.id(), track);
            GooglePlayDeveloperApiClient.TrackRelease current = editTrack.releases().stream()
                    .filter(release -> release.versionCodes().contains(versionCode))
                    .findFirst()
                    .orElseThrow(() -> new DistributionException(
                            DistributionException.Code.DISTRIBUTION_ROLLOUT_NOT_FOUND,
                            "the production track no longer carries the release's track release on the store side"));
            GooglePlayDeveloperApiClient.TrackRelease rewritten = new GooglePlayDeveloperApiClient.TrackRelease(
                    current.name(), current.versionCodes(), current.releaseNotes(), status, fraction);
            GooglePlayDeveloperApiClient.Track written = play.putTrack(
                    credential,
                    packageName,
                    edit.id(),
                    new GooglePlayDeveloperApiClient.Track(track, List.of(rewritten)));
            play.commitEdit(credential, packageName, edit.id());
            return written.releases().stream()
                    .filter(release -> release.versionCodes().contains(versionCode))
                    .findFirst()
                    .map(release -> new ProviderObservation(
                            release.userFraction() == null ? null : (int) Math.round(release.userFraction() * 100.0d),
                            release.status()))
                    .orElseGet(() -> new ProviderObservation((int) Math.round(fraction * 100.0d), status));
        });
        if (applied.failure() != null) {
            throw problemOf(applied.failure());
        }
        return applied.answer();
    }

    /**
     * The live observation: THE rollout's own track release (matched
     * by its version code — a track can hold a finishing previous
     * release beside the curve's own), its fraction and Google's own
     * status word. Null when the store no longer carries the release.
     */
    private ProviderObservation observe(
            GooglePlayCredentials credential, String packageName, String track, long versionCode) {
        GooglePlayDeveloperApiClient.Track live = play.liveTrackOf(credential, packageName, track);
        for (GooglePlayDeveloperApiClient.TrackRelease release : live.releases()) {
            if (release.versionCodes().contains(versionCode)) {
                Double fraction = release.userFraction();
                return new ProviderObservation(
                        fraction == null ? null : (int) Math.round(fraction * 100.0d), release.status());
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Resolution, recording, views
    // -------------------------------------------------------------------------

    /** The rollout row (the organization's own guard — the concealed miss). */
    private ChannelRolloutEntity rolloutOf(UUID organizationId, UUID releaseId) {
        return rollouts.findByReleaseIdAndChannelAndOrganizationId(releaseId, CHANNEL, organizationId)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_ROLLOUT_NOT_FOUND,
                        "the release carries no staged rollout on the play channel"));
    }

    /** The credential chain resolved at the release's scope, exactly as the dispatch resolves it. */
    private GooglePlayCredentials credentialOf(ChannelRolloutEntity state) {
        ReleaseAccess.ReleaseFacts facts =
                releases.distributionFactsOf(state.getReleaseId()).orElse(null);
        UUID applicationId = facts == null ? null : facts.applicationId();
        UUID environmentId = facts == null ? null : facts.environmentId();
        DistributionCommand.ResolvedCredential resolved = credentials
                .resolve(state.getOrganizationId(), applicationId, environmentId, "google-play")
                .map(account -> {
                    SecretMaterial material =
                            secrets.resolve(account.getSecretReference()).orElse(new SecretMaterial(""));
                    return new DistributionCommand.ResolvedCredential(
                            account.getProvider(),
                            account.getName(),
                            account.getSecretReference(),
                            material,
                            account.getMetadata());
                })
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_CREDENTIAL_NOT_FOUND,
                        "no credential is bound on the delegation chain for this release's channel and scope"));
        GooglePlayCredentials credential = GooglePlayCredentials.of(resolved);
        if (!credential.complete()) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_CREDENTIAL_NOT_FOUND,
                    "the resolved google-play credential is incomplete (the service-account JSON document as"
                            + " material)");
        }
        return credential;
    }

    /** The timeline step and the audit line — AC3's (actor, percentage, time) on both surfaces. */
    private void stepAndAudit(
            ChannelRolloutEntity state,
            ChannelRolloutStepEntity.Action action,
            Integer fromPercent,
            int toPercent,
            UUID actorId) {
        steps.save(new ChannelRolloutStepEntity(
                state.getId(), action, fromPercent, toPercent, actorId, Instant.now(clock)));
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("channel", CHANNEL);
        detail.put("releaseId", state.getReleaseId().toString());
        detail.put("action", action.name());
        if (fromPercent != null) {
            detail.put("fromPercent", String.valueOf(fromPercent));
        }
        detail.put("toPercent", String.valueOf(toPercent));
        AuditEvent.Builder line = AuditEvent.builder(AuditEventType.DISTRIBUTION_ROLLOUT_CONTROLLED)
                .orgId(state.getOrganizationId())
                .detail(detail)
                .success(true);
        if (actorId != null) {
            line.userId(actorId);
        }
        audit.record(line.build());
    }

    private void auditDrift(UUID organizationId, ChannelRolloutEntity state, ProviderObservation observed) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("channel", CHANNEL);
        detail.put("releaseId", state.getReleaseId().toString());
        detail.put("intendedPercent", String.valueOf(state.getIntendedPercent()));
        detail.put("observedPercent", observed.percent() == null ? "" : String.valueOf(observed.percent()));
        audit.record(AuditEvent.builder(AuditEventType.DISTRIBUTION_ROLLOUT_DRIFT)
                .orgId(organizationId)
                .detail(detail)
                .success(true)
                .build());
        log.warn(
                "play rollout drift: release {} intends {}% but the store serves {}%",
                state.getReleaseId(), state.getIntendedPercent(), observed.percent());
    }

    private RolloutView viewOf(ChannelRolloutEntity state, ProviderObservation observed, boolean drift) {
        String[] reference = referenceOf(state.getExternalId());
        List<StepView> timeline = new ArrayList<>();
        for (ChannelRolloutStepEntity step : steps.findByRolloutIdOrderByOccurredAtAscIdAsc(state.getId())) {
            timeline.add(new StepView(
                    step.getAction().name(),
                    step.getFromPercent(),
                    step.getToPercent(),
                    step.getActorId(),
                    step.getOccurredAt()));
        }
        return new RolloutView(
                state.getReleaseId(),
                state.getChannel(),
                state.getStatus().name(),
                state.getRingCode(),
                reference[0],
                Long.parseLong(reference[1]),
                reference[2],
                state.getIntendedPercent(),
                observed.percent(),
                observed.status(),
                drift,
                timeline);
    }

    /** The external id's track segment (null when the reference carries none of the shape). */
    private static String trackOf(String externalId) {
        return externalId == null || externalId.isEmpty() ? null : externalId.split("#")[0];
    }

    /** The external id split into its three facts; PERMANENT-shaped problem on a malformed reference. */
    private static String[] referenceOf(String externalId) {
        String[] parts = externalId == null ? new String[0] : externalId.split("#");
        if (parts.length != 3 || parts[0].isBlank() || parts[2].isBlank()) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_ROLLOUT_NOT_FOUND,
                    "the rollout's external id is not a track release reference (track#versionCode#packageName)");
        }
        try {
            Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_ROLLOUT_NOT_FOUND,
                    "the rollout's external id is not a track release reference (track#versionCode#packageName)");
        }
        return parts;
    }

    /** The taxonomy's problem code for a sandbox failure (the same closed mapping everywhere). */
    private static DistributionException problemOf(AdapterException failure) {
        return new DistributionException(AdapterCallSandbox.taxonomyCodeOf(failure), failure.getMessage());
    }

    /** The provider's own answer for the curve: the observed percentage and status word. */
    private record ProviderObservation(Integer percent, String status) {}

    /** The curve's served view (the dashboard's rollout answer). */
    public record RolloutView(
            UUID releaseId,
            String channel,
            String status,
            String ringCode,
            String track,
            long versionCode,
            String packageName,
            int intendedPercent,
            Integer observedPercent,
            String providerStatus,
            boolean drift,
            List<StepView> steps) {

        public RolloutView {
            steps = List.copyOf(steps);
        }
    }

    /** One timeline step (AC3: the action, the curve's two ends, the actor, the moment). */
    public record StepView(String action, Integer fromPercent, int toPercent, UUID actorId, Instant occurredAt) {}
}
