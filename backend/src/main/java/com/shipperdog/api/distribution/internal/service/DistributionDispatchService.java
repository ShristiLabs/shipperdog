package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.AdapterCapability;
import com.shipperdog.api.distribution.AdapterException;
import com.shipperdog.api.distribution.AdapterOutcome;
import com.shipperdog.api.distribution.DistributionChannelAdapter;
import com.shipperdog.api.distribution.DistributionCommand;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity;
import com.shipperdog.api.distribution.internal.registry.DistributionAdapterRegistry;
import com.shipperdog.api.distribution.internal.repository.ChannelDistributionRepository;
import com.shipperdog.api.distribution.internal.secret.ChannelSecretStore;
import com.shipperdog.api.environment.EnvironmentAccess;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseSubmissionManifests;
import com.shipperdog.api.release.ReleaseTransitionedEvent;
import com.shipperdog.api.version.VersionArtifacts;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The dispatch half of the adapter framework (issue #61, S13-01,
 * AC4/AC5/AC6): the glue that turns a release's ACTIVE crossing into
 * sandboxed adapter calls and adapter reports into distribution
 * records. Listens on the state machine's transition event (the
 * manifest-freeze precedent -- synchronous, inside the crossing's
 * committing transaction, so records and crossing commit or roll back
 * together), resolves the channel through the environment's
 * name-only binding ({@code direct} the default), and runs the
 * adapter under the per-channel sandbox: a wall-clock timeout, bounded
 * retries with backoff keyed on the {@link AdapterException.Category}
 * taxonomy, and one traced, timed observation per attempt ({@code
 * shipperdog.adapter.call.duration}; PRD 28 -- store latency is a top
 * debugging question).
 *
 * <p><strong>Store failures never fail the crossing — except a
 * refused SUBMISSION.</strong> A store adapter's exhausted sandbox
 * writes FAILED records with the taxonomy code and audits the
 * dispatch -- the release still went ACTIVE, because its
 * platform-side availability (the authorization engine's decisions)
 * is not the store's to take away. S13-03's doctrine split (issue
 * #63, AC4, PRD 17): for a channel whose adapter declares the
 * SUBMISSION capability, a PERMANENT exhaustion (Apple refused the
 * submission — metadata rejected, with Apple's own words in the
 * failure detail) VETOES the crossing: this listener registers a
 * post-rollback handler and throws, the crossing's transaction rolls
 * back, and the handler writes the FAILED records in a fresh
 * transaction and carries the release across PRD 17's DEPLOYING to
 * FAILED edge with the actionable reason (the {@link
 * com.shipperdog.api.release.ReleaseDistributionFailures} port). The
 * production submission channel's deliverable IS the store's
 * acceptance; the assignment channels keep the never-fail doctrine
 * (their platform-side availability stands alone). Retryable
 * exhaustion keeps the record-level doctrine on every channel.
 * S13-06 owns rollback orchestration; the #62-#65 connectors own
 * moving store dispatch off the crossing's transaction onto the bus
 * when their latencies demand it (the seam: this listener stays; the
 * sandbox call moves).
 *
 * <p><strong>Idempotency.</strong> The record grain (release,
 * artifact, channel) is unique-indexed: a re-delivered transition or
 * a replayed dispatch re-reports onto the same rows -- a store's
 * external id lands once, later reports overwrite status and external
 * id forward (INV-006's character: history is kept by append-only
 * audit, not by row duplication).
 */
@Service
public class DistributionDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DistributionDispatchService.class);

    /** The failure code when the channel binding names no registered adapter (a configuration fault). */
    public static final String FAILURE_CHANNEL_UNKNOWN = "CHANNEL_UNKNOWN";

    /** The failure code when the adapter's declared provider has no binding anywhere on the chain. */
    public static final String FAILURE_CREDENTIAL_UNRESOLVED = "CREDENTIAL_UNRESOLVED";

    private final DistributionAdapterRegistry registry;
    private final DistributionCredentialService credentials;
    private final ChannelDistributionRepository records;
    private final com.shipperdog.api.distribution.internal.repository.ChannelGroupLinkRepository groupLinks;
    private final ChannelSecretStore secrets;
    private final ReleaseAccess releases;
    private final ReleaseSubmissionManifests submissionManifests;
    private final EnvironmentAccess environments;
    private final VersionArtifacts versionArtifacts;
    private final com.shipperdog.api.artifact.ArtifactStore artifactStorage;
    private final AdapterCallSandbox sandbox;
    private final SubmissionFailureRecorder submissionFailures;
    private final AuditLogger audit;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final Tracer tracer;
    private final Clock clock;

    public DistributionDispatchService(
            DistributionAdapterRegistry registry,
            DistributionCredentialService credentials,
            ChannelDistributionRepository records,
            com.shipperdog.api.distribution.internal.repository.ChannelGroupLinkRepository groupLinks,
            ChannelSecretStore secrets,
            ReleaseAccess releases,
            ReleaseSubmissionManifests submissionManifests,
            EnvironmentAccess environments,
            VersionArtifacts versionArtifacts,
            com.shipperdog.api.artifact.ArtifactStore artifactStorage,
            AdapterCallSandbox sandbox,
            SubmissionFailureRecorder submissionFailures,
            AuditLogger audit,
            org.springframework.context.ApplicationEventPublisher events,
            Tracer tracer,
            Clock clock) {
        this.registry = registry;
        this.credentials = credentials;
        this.records = records;
        this.groupLinks = groupLinks;
        this.secrets = secrets;
        this.releases = releases;
        this.submissionManifests = submissionManifests;
        this.environments = environments;
        this.versionArtifacts = versionArtifacts;
        this.artifactStorage = artifactStorage;
        this.sandbox = sandbox;
        this.submissionFailures = submissionFailures;
        this.audit = audit;
        this.events = events;
        this.tracer = tracer;
        this.clock = clock;
    }

    /**
     * The ACTIVE crossing's dispatch: channels take over exactly
     * where the state machine's distribution begins. The PROMOTED
     * crossing (the promotion engine's final-ring landing — every
     * target on the final ring, Production-last) dispatches the
     * RING-GRADED channels again (S13-05, #65): an adapter that
     * {@link DistributionChannelAdapter#followsPromotionCrossings()
     * follows the walk} re-targets the same release onto the wider
     * surface its current ring maps to — the Play channel's
     * production track. Every other adapter crosses untouched (one
     * dispatch at ACTIVE stands; a re-run would duplicate an upload
     * or a submission).
     */
    @EventListener
    @Transactional
    void onReleaseTransitioned(ReleaseTransitionedEvent event) {
        if ("ACTIVE".equals(event.toStatus())) {
            dispatch(event);
            return;
        }
        if ("PROMOTED".equals(event.toStatus())) {
            UUID environmentId = releases.factsOf(event.releaseId())
                    .map(ReleaseAccess.ReleaseFacts::environmentId)
                    .orElse(null);
            String channel = channelOf(environmentId);
            registry.byChannelId(channel)
                    .filter(DistributionChannelAdapter::followsPromotionCrossings)
                    .ifPresent(adapter -> dispatch(event));
        }
    }

    /** One release's dispatch through its resolved channel; the crossing's own transaction commits the records. */
    void dispatch(ReleaseTransitionedEvent event) {
        // The transition event carries the release's owning dimensions;
        // the environment it targets comes from the facts (the event
        // stays the state machine's own vocabulary).
        UUID environmentId = releases.factsOf(event.releaseId())
                .map(ReleaseAccess.ReleaseFacts::environmentId)
                .orElse(null);
        String channel = channelOf(environmentId);
        List<ReleaseAccess.ReleaseArtifactSlot> slots = releases.artifactSlotsOf(event.releaseId());
        if (slots.isEmpty()) {
            log.debug("release {} has no artifact slots; nothing to dispatch", event.releaseRef());
            return;
        }
        List<ChannelDistributionEntity> rows = ensureRecords(event, channel, slots);
        Optional<DistributionChannelAdapter> adapter = registry.byChannelId(channel);
        if (adapter.isEmpty()) {
            Instant now = Instant.now(clock);
            rows.forEach(
                    row -> row.report(ChannelDistributionEntity.AdapterReport.failed(FAILURE_CHANNEL_UNKNOWN), 0, now));
            records.saveAll(rows);
            auditDispatch(event, channel, "unknown-channel", false);
            return;
        }

        DistributionCommand command = commandOf(event, environmentId, channel, adapter.get(), slots);
        if (command == null) {
            Instant now = Instant.now(clock);
            rows.forEach(row ->
                    row.report(ChannelDistributionEntity.AdapterReport.failed(FAILURE_CREDENTIAL_UNRESOLVED), 0, now));
            records.saveAll(rows);
            auditDispatch(event, channel, FAILURE_CREDENTIAL_UNRESOLVED, false);
            return;
        }

        AdapterCallSandbox.Result<List<AdapterOutcome>> dispatchRun =
                sandbox.run(channel, "distribute", attempt -> adapter.get().distribute(withAttempt(command, attempt)));
        if (dispatchRun.failure() != null) {
            // The S13-03 doctrine split (issue #63, AC4, ring-graded
            // by S13-05): a PERMANENT refusal of a SUBMISSION-GRADE
            // dispatch — the adapter declares SUBMISSION AND grades
            // the dispatch's ring submission-grade ({@code
            // submitsAtRing}: the App Store's every ring, the Play
            // channel's production ring alone) — vetoes the crossing
            // it rode on: the deliverable of THAT dispatch is the
            // store's acceptance, so the veto registers the
            // post-rollback failure recorder (FAILED records, the
            // DEPLOYING to FAILED edge where the release sits on it,
            // and the audits in a fresh transaction) and throws to
            // roll this transaction back — at the PROMOTED crossing
            // the hop itself rolls back (the promotion refuses with
            // the store's words; the release stays ACTIVE on its
            // ring, the failure records still land). Every other
            // exhaustion keeps the never-fail-the-crossing doctrine:
            // FAILED records with the taxonomy code, the crossing
            // commits, S13-06 owns rollback.
            if (dispatchRun.failure().category() == AdapterException.Category.PERMANENT
                    && adapter.get().capabilities().contains(AdapterCapability.SUBMISSION)
                    && adapter.get().submitsAtRing(command.ringCode())) {
                registerSubmissionVeto(event, channel, slots, dispatchRun.failure());
                throw new DistributionException(
                        AdapterCallSandbox.taxonomyCodeOf(dispatchRun.failure()),
                        "the store refused the submission and the release cannot go " + event.toStatus() + ": "
                                + dispatchRun.failure().getMessage());
            }
            Instant now = Instant.now(clock);
            String failureCode = AdapterCallSandbox.taxonomyOf(dispatchRun.failure());
            String failureDetail = dispatchRun.failure().getMessage();
            rows.forEach(row -> row.report(
                    ChannelDistributionEntity.AdapterReport.failed(failureCode, failureDetail),
                    dispatchRun.calls(),
                    now));
            records.saveAll(rows);
            auditDispatch(event, channel, failureCode, false);
            return;
        }
        Instant now = Instant.now(clock);
        List<AdapterOutcome> outcomes = dispatchRun.answer();
        Map<UUID, AdapterOutcome> byArtifact = new LinkedHashMap<>();
        for (AdapterOutcome outcome : outcomes) {
            byArtifact.put(outcome.artifactId(), outcome);
        }
        boolean allDistributed = !outcomes.isEmpty();
        for (ChannelDistributionEntity row : rows) {
            AdapterOutcome outcome = byArtifact.get(row.getArtifactId());
            if (outcome == null) {
                row.report(
                        ChannelDistributionEntity.AdapterReport.failed(
                                DistributionException.Code.ADAPTER_FAILED.codeValue()),
                        dispatchRun.calls(),
                        now);
                allDistributed = false;
            } else {
                row.report(
                        new ChannelDistributionEntity.AdapterReport(
                                ChannelDistributionEntity.Status.valueOf(outcome.status()),
                                outcome.externalId(),
                                outcome.failureCode(),
                                outcome.detail(),
                                outcome.providerStatus()),
                        dispatchRun.calls(),
                        now);
                allDistributed &= AdapterOutcome.DISTRIBUTED.equals(outcome.status());
            }
        }
        records.saveAll(rows);
        auditDispatch(event, channel, allDistributed ? "distributed" : "failed", true);
        // The framework-generic completion fact (S13-05, #65): channel
        // services that key off a landed dispatch — the Play rollout
        // state's first write — listen here, with no channel knowledge
        // in this service. Published inside the crossing's
        // transaction: the state the listener writes commits or rolls
        // back with the dispatch it belongs to.
        events.publishEvent(new ChannelDistributionReported(
                event.releaseId(),
                event.organizationId(),
                event.applicationId(),
                channel,
                command.ringCode(),
                List.copyOf(outcomes)));
    }

    /**
     * The veto's post-rollback half (AC4): registers the failure
     * recorder to run once THIS transaction has rolled back (the
     * recorder writes the FAILED records, the audits, and the
     * DEPLOYING to FAILED crossing in its own fresh transaction — a
     * synchronization, so a commit despite the throw would leave the
     * recorder quiet).
     */
    private void registerSubmissionVeto(
            ReleaseTransitionedEvent event,
            String channel,
            List<ReleaseAccess.ReleaseArtifactSlot> slots,
            AdapterException failure) {
        // The aftermath runs after the rollback, outside every bound
        // scope the crossing ran in: the slots are captured HERE, and
        // the recorder reads nothing scoped.
        List<UUID> artifactIds = slots.stream()
                .map(ReleaseAccess.ReleaseArtifactSlot::artifactId)
                .toList();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to roll back (a direct invocation, e.g. a
            // test): record the failure immediately in its own
            // transaction instead of after a rollback that will not
            // come.
            submissionFailures.record(
                    event, channel, artifactIds, AdapterCallSandbox.taxonomyOf(failure), failure.getMessage());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    submissionFailures.record(
                            event, channel, artifactIds, AdapterCallSandbox.taxonomyOf(failure), failure.getMessage());
                }
            }
        });
    }

    /** The command rebuilt for one sandbox attempt (the attempt number rides the retry). */
    private static DistributionCommand withAttempt(DistributionCommand seed, int attempt) {
        return new DistributionCommand(
                seed.releaseId(),
                seed.releaseRef(),
                seed.applicationId(),
                seed.versionId(),
                seed.organizationId(),
                seed.tenantId(),
                seed.environmentId(),
                seed.environmentCode(),
                seed.ringCode(),
                seed.channel(),
                seed.artifacts(),
                seed.targetTenantIds(),
                seed.targetGroups(),
                seed.channelConfig(),
                seed.credential(),
                seed.traceId(),
                seed.spanId(),
                attempt,
                seed.initiatedAt(),
                seed.submission());
    }

    private String channelOf(UUID environmentId) {
        if (environmentId == null) {
            return DistributionAdapterRegistry.DIRECT_CHANNEL;
        }
        return environments
                .distributionChannelNameOf(environmentId)
                .filter(name -> !name.isBlank())
                .orElse(DistributionAdapterRegistry.DIRECT_CHANNEL);
    }

    private List<ChannelDistributionEntity> ensureRecords(
            ReleaseTransitionedEvent event, String channel, List<ReleaseAccess.ReleaseArtifactSlot> slots) {
        List<ChannelDistributionEntity> rows = new ArrayList<>();
        for (ReleaseAccess.ReleaseArtifactSlot slot : slots) {
            rows.add(records.findByReleaseIdAndArtifactIdAndChannel(event.releaseId(), slot.artifactId(), channel)
                    .orElseGet(() -> new ChannelDistributionEntity(
                            event.releaseId(), slot.artifactId(), event.organizationId(), channel)));
        }
        return rows;
    }

    private DistributionCommand commandOf(
            ReleaseTransitionedEvent event,
            UUID environmentId,
            String channel,
            DistributionChannelAdapter adapter,
            List<ReleaseAccess.ReleaseArtifactSlot> slots) {
        Map<String, String> channelConfig = environmentId == null
                ? Map.of()
                : Map.copyOf(environments.configSnapshotOf(environmentId).entries());
        String environmentCode = environmentId == null
                ? null
                : environments.stateOf(environmentId).map(state -> state.code()).orElse(null);

        Map<UUID, VersionArtifacts.VersionArtifactSlot> enrich =
                versionArtifacts
                        .byVersionIds(List.of(event.versionId()))
                        .getOrDefault(event.versionId(), List.of())
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                VersionArtifacts.VersionArtifactSlot::id, slot -> slot, (a, b) -> a));
        List<DistributionCommand.ArtifactSlot> artifacts = slots.stream()
                .map(slot -> {
                    VersionArtifacts.VersionArtifactSlot facts = enrich.get(slot.artifactId());
                    // The upload channels' byte source (S13-04, #64): a
                    // RE-OPENABLE supplier over the stored object, so an
                    // adapter streams the binary and a sandbox retry
                    // re-streams from the start. Null when the slot's
                    // object carries no key (the assignment channels'
                    // honest shape).
                    java.util.function.Supplier<java.io.InputStream> contentOf =
                            facts == null || facts.storageKey() == null
                                    ? null
                                    : () -> artifactStorage.open(facts.storageKey());
                    return new DistributionCommand.ArtifactSlot(
                            slot.artifactId(),
                            slot.platform(),
                            facts == null ? null : facts.format(),
                            facts == null ? null : facts.fileName(),
                            facts == null ? 0L : facts.size(),
                            facts == null ? null : facts.buildNumber(),
                            facts == null ? null : facts.bundleId(),
                            facts == null ? null : facts.versionName(),
                            contentOf);
                })
                .toList();

        DistributionCommand.ResolvedCredential credential = resolveCredential(event, adapter);
        if (credential == null && !adapter.credentialProviders().isEmpty()) {
            return null;
        }
        Set<UUID> targets = releases.targetTenantIdsOf(event.releaseId());
        // The targeted tester groups with the channel's resolved links
        // (S13-02): the framework resolves the mapping so the adapter
        // never reads platform tables — groups without a link carry a
        // null external id and the adapter reports the gap loudly.
        List<ReleaseAccess.ReleaseTargetGroup> targeted = releases.targetGroupsOf(event.releaseId());
        Map<UUID, String> linksOfGroup = groupLinks
                .findByChannelAndGroupIdIn(
                        channel,
                        targeted.stream()
                                .map(ReleaseAccess.ReleaseTargetGroup::groupId)
                                .toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.shipperdog.api.distribution.internal.entity.ChannelGroupLinkEntity::getGroupId,
                        com.shipperdog.api.distribution.internal.entity.ChannelGroupLinkEntity::getExternalGroupId,
                        (a, b) -> a));
        List<DistributionCommand.TargetGroup> targetGroups = targeted.stream()
                .map(group -> new DistributionCommand.TargetGroup(
                        group.groupId(), group.tenantId(), linksOfGroup.get(group.groupId())))
                .toList();
        // The frozen store submission (S13-03, #63, AC1; generalized
        // by S13-04, #64): the release manifest's frozen block verbatim
        // — THE immutable copy of what was sent (PRD 16), never the
        // live authoring rows, so the manifest and the wire can never
        // drift apart. Since #64 the freeze is channel-generic (the
        // App Store's listing metadata and the Play channel's release
        // notes both freeze); the dispatch resolves whatever block the
        // release froze, and each adapter decides what its block means
        // — null when the release froze none.
        DistributionCommand.Submission submission = submissionManifests
                .frozenSubmissionOf(event.releaseId())
                .map(frozen -> new DistributionCommand.Submission(
                        frozen.channel(),
                        frozen.phasedRelease(),
                        frozen.locales().stream()
                                .map(locale -> new DistributionCommand.SubmissionLocale(
                                        locale.locale(),
                                        locale.description(),
                                        locale.keywords(),
                                        locale.screenshotRefs(),
                                        locale.releaseNotes()))
                                .toList()))
                .orElse(null);
        // The release's CURRENT effective ring (S13-05, #65): the
        // earliest effective-order position still holding a target —
        // the starting ring at the ACTIVE crossing, the final ring at
        // the PROMOTED crossing the walk's landing fires. The
        // ring-graded adapters (the Play map) target their surface by
        // where the walk actually stands, never where it was born.
        String ringCode = releases.effectiveRingCodeOf(event.releaseId()).orElse(null);
        return new DistributionCommand(
                event.releaseId(),
                event.releaseRef(),
                event.applicationId(),
                event.versionId(),
                event.organizationId(),
                event.tenantId(),
                environmentId,
                environmentCode,
                ringCode,
                channel,
                artifacts,
                targets,
                targetGroups,
                channelConfig,
                credential,
                currentTraceId(),
                currentSpanId(),
                1,
                Instant.now(clock),
                submission);
    }

    private DistributionCommand.ResolvedCredential resolveCredential(
            ReleaseTransitionedEvent event, DistributionChannelAdapter adapter) {
        String provider = adapter.credentialProviders().stream().findFirst().orElse(null);
        if (provider == null) {
            return null;
        }
        return credentials
                .resolve(event.organizationId(), event.applicationId(), environmentIdOf(event), provider)
                .map(account -> {
                    SecretMaterial material;
                    try {
                        material = secrets.resolve(account.getSecretReference()).orElse(new SecretMaterial(""));
                    } catch (RuntimeException e) {
                        // Belt-and-braces beside the store's own resolve-side
                        // containment: a store that still throws must not take
                        // the crossing down with it. The null credential walks
                        // the CREDENTIAL_UNRESOLVED record path -- FAILED
                        // records, the crossing commits (S13-06 owns rollback).
                        log.warn(
                                "credential material resolution failed through the secret store for release {};"
                                        + " recording the store fault and proceeding with the crossing",
                                event.releaseRef(),
                                e);
                        return null;
                    }
                    return new DistributionCommand.ResolvedCredential(
                            account.getProvider(),
                            account.getName(),
                            account.getSecretReference(),
                            material,
                            account.getMetadata());
                })
                .orElse(null);
    }

    private void auditDispatch(ReleaseTransitionedEvent event, String channel, String outcome, boolean success) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("channel", channel);
        detail.put("outcome", outcome);
        detail.put("releaseRef", event.releaseRef() == null ? "" : event.releaseRef());
        audit.record(AuditEvent.builder(AuditEventType.DISTRIBUTION_DISPATCHED)
                .orgId(event.organizationId())
                .applicationId(event.applicationId())
                .versionId(event.versionId())
                .detail(detail)
                .success(success)
                .build());
    }

    private UUID environmentIdOf(ReleaseTransitionedEvent event) {
        return releases.factsOf(event.releaseId())
                .map(ReleaseAccess.ReleaseFacts::environmentId)
                .orElse(null);
    }

    private String currentTraceId() {
        return tracer.currentTraceContext() != null
                        && tracer.currentTraceContext().context() != null
                ? tracer.currentTraceContext().context().traceId()
                : null;
    }

    private String currentSpanId() {
        return tracer.currentTraceContext() != null
                        && tracer.currentTraceContext().context() != null
                ? tracer.currentTraceContext().context().spanId()
                : null;
    }

    /** The records read for one release inside one organization (the reconciliation view, AC4). */
    @Transactional(readOnly = true)
    public List<RecordView> recordsOf(UUID organizationId, UUID releaseId) {
        return records.findByReleaseIdAndOrganizationIdOrderByChannelAsc(releaseId, organizationId).stream()
                .map(DistributionDispatchService::viewOf)
                .toList();
    }

    /**
     * One record's refresh from its provider (S13-02, #62, AC4/AC5):
     * the release dashboard's explicit refresh and the reconciliation
     * pass's cadence both land here — the channel's adapter answers
     * the provider-side status under the same sandbox, the record
     * takes the report verbatim (status, external id, provider status
     * word), and the refresh is audited. An adapter failure is the
     * honest answer, never a row mutation: the record keeps its
     * standing until a provider answer changes it.
     *
     * @throws DistributionException DISTRIBUTION_RECORD_NOT_FOUND for
     *                               the concealed miss (unknown or
     *                               foreign record)
     */
    @Transactional
    public RecordView refresh(UUID organizationId, UUID recordId, UUID actorId) {
        ChannelDistributionEntity row = records.findById(recordId)
                .filter(existing -> existing.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_RECORD_NOT_FOUND, "unknown distribution record"));
        DistributionChannelAdapter adapter = registry.byChannelId(row.getChannel())
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.CHANNEL_UNKNOWN, "the record's channel is not registered"));
        // The non-seam-served facts read (S13-02): the refresh runs on
        // organization-grain routes and background threads whose bound
        // scope is not the release's owning scope -- the record's own
        // organization check is this read's guard.
        UUID applicationId = releases.distributionFactsOf(row.getReleaseId())
                .map(ReleaseAccess.ReleaseFacts::applicationId)
                .orElse(null);
        UUID environmentId = releases.distributionFactsOf(row.getReleaseId())
                .map(ReleaseAccess.ReleaseFacts::environmentId)
                .orElse(null);
        String provider = adapter.credentialProviders().stream().findFirst().orElse(null);
        DistributionCommand.ResolvedCredential credential = provider == null
                ? null
                : credentials
                        .resolve(row.getOrganizationId(), applicationId, environmentId, provider)
                        .map(account -> {
                            SecretMaterial material = secrets.resolve(account.getSecretReference())
                                    .orElseGet(() -> new SecretMaterial(""));
                            return new DistributionCommand.ResolvedCredential(
                                    account.getProvider(),
                                    account.getName(),
                                    account.getSecretReference(),
                                    material,
                                    account.getMetadata());
                        })
                        .orElse(null);
        if (credential == null) {
            // The dispatch doctrine, held at the refresh: a provider
            // with no resolvable credential is the chain's own miss,
            // answered as the chain's own problem -- never a provider
            // call, never an NPE (the reviewer's finding).
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_CREDENTIAL_NOT_FOUND,
                    "no credential is bound on the delegation chain for this record's channel and scope");
        }
        AdapterCallSandbox.Result<AdapterOutcome> refresh = sandbox.run(
                row.getChannel(),
                "status",
                attempt -> adapter.status(row.getArtifactId(), row.getChannel(), row.getExternalId(), credential));
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("channel", row.getChannel());
        detail.put("recordId", row.getId().toString());
        detail.put(
                "outcome",
                refresh.answer() == null
                        ? AdapterCallSandbox.taxonomyOf(refresh.failure())
                        : refresh.answer().status());
        detail.put(
                "providerStatus",
                refresh.answer() == null || refresh.answer().providerStatus() == null
                        ? ""
                        : refresh.answer().providerStatus());
        AuditEvent.Builder auditLine = AuditEvent.builder(AuditEventType.DISTRIBUTION_RECORD_REFRESHED)
                .orgId(organizationId)
                .applicationId(applicationId)
                .detail(detail)
                .success(refresh.answer() != null);
        if (actorId != null) {
            auditLine.userId(actorId);
        }
        audit.record(auditLine.build());
        if (refresh.answer() == null) {
            // The provider's honest failure: the record keeps its
            // standing, the refresh answers the taxonomy's problem.
            AdapterException failure = refresh.failure();
            throw new DistributionException(
                    AdapterCallSandbox.taxonomyCodeOf(failure),
                    failure.getMessage() == null ? "the provider refresh failed" : failure.getMessage());
        }
        row.report(
                new ChannelDistributionEntity.AdapterReport(
                        ChannelDistributionEntity.Status.valueOf(
                                refresh.answer().status()),
                        refresh.answer().externalId(),
                        refresh.answer().failureCode(),
                        refresh.answer().detail(),
                        refresh.answer().providerStatus()),
                refresh.calls(),
                Instant.now(clock));
        records.save(row);
        return viewOf(row);
    }

    /** The refresh-eligible records (the reconciliation pass's input): store channels with an external id, non-terminal or stale. */
    @Transactional(readOnly = true)
    public List<ChannelDistributionEntity> refreshableRecords(int limit) {
        return records.findByStatusInAndExternalIdIsNotNull(
                        java.util.List.of(
                                ChannelDistributionEntity.Status.PENDING,
                                ChannelDistributionEntity.Status.DISTRIBUTING),
                        org.springframework.data.domain.PageRequest.of(0, limit))
                .getContent();
    }

    private static RecordView viewOf(ChannelDistributionEntity row) {
        return new RecordView(
                row.getId(),
                row.getReleaseId(),
                row.getArtifactId(),
                row.getChannel(),
                row.getStatus().name(),
                row.getExternalId(),
                row.getFailureCode(),
                row.getFailureDetail(),
                row.getProviderStatus(),
                row.getProviderStatusAt(),
                row.getAttempts(),
                row.getLastAttemptAt(),
                row.getDistributedAt(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    /** One distribution record's served view (the adapter's report plus the framework's bookkeeping). */
    public record RecordView(
            UUID id,
            UUID releaseId,
            UUID artifactId,
            String channel,
            String status,
            String externalId,
            String failureCode,
            String failureDetail,
            String providerStatus,
            Instant providerStatusAt,
            int attempts,
            Instant lastAttemptAt,
            Instant distributedAt,
            Instant createdAt,
            Instant updatedAt) {}
}
