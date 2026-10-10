package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.distribution.AdapterException;
import com.shipperdog.api.distribution.AdapterOutcome;
import com.shipperdog.api.distribution.DistributionChannelAdapter;
import com.shipperdog.api.distribution.DistributionCommand;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.distribution.internal.config.DistributionProperties;
import com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity;
import com.shipperdog.api.distribution.internal.registry.DistributionAdapterRegistry;
import com.shipperdog.api.distribution.internal.repository.ChannelDistributionRepository;
import com.shipperdog.api.distribution.internal.secret.ChannelSecretStore;
import com.shipperdog.api.environment.EnvironmentAccess;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseTransitionedEvent;
import com.shipperdog.api.version.VersionArtifacts;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p><strong>Store failures never fail the crossing.</strong> A store
 * adapter's exhausted sandbox writes FAILED records with the taxonomy
 * code and audits the dispatch -- the release still went ACTIVE,
 * because its platform-side availability (the authorization engine's
 * decisions) is not the store's to take away. S13-06 owns rollback
 * orchestration; the #62-#65 connectors own moving store dispatch off
 * the crossing's transaction onto the bus when their latencies demand
 * it (the seam: this listener stays; the sandbox call moves).
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
    private final ChannelSecretStore secrets;
    private final ReleaseAccess releases;
    private final EnvironmentAccess environments;
    private final VersionArtifacts versionArtifacts;
    private final DistributionProperties properties;
    private final AuditLogger audit;
    private final ObservationRegistry observations;
    private final Tracer tracer;
    private final Clock clock;
    private final ExecutorService adapterCalls = Executors.newVirtualThreadPerTaskExecutor();

    public DistributionDispatchService(
            DistributionAdapterRegistry registry,
            DistributionCredentialService credentials,
            ChannelDistributionRepository records,
            ChannelSecretStore secrets,
            ReleaseAccess releases,
            EnvironmentAccess environments,
            VersionArtifacts versionArtifacts,
            DistributionProperties properties,
            AuditLogger audit,
            ObservationRegistry observations,
            Tracer tracer,
            Clock clock) {
        this.registry = registry;
        this.credentials = credentials;
        this.records = records;
        this.secrets = secrets;
        this.releases = releases;
        this.environments = environments;
        this.versionArtifacts = versionArtifacts;
        this.properties = properties;
        this.audit = audit;
        this.observations = observations;
        this.tracer = tracer;
        this.clock = clock;
    }

    @PreDestroy
    void shutdown() {
        adapterCalls.close();
    }

    /** The ACTIVE crossing's dispatch: channels take over exactly where the state machine's distribution begins. */
    @EventListener
    @Transactional
    void onReleaseTransitioned(ReleaseTransitionedEvent event) {
        if (!"ACTIVE".equals(event.toStatus())) {
            return;
        }
        dispatch(event);
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

        SandboxResult sandbox = sandboxedCall(adapter.get(), command);
        if (sandbox.failure() != null) {
            // Store failures never fail the crossing (see class javadoc):
            // the exhausted sandbox lands as FAILED records with the
            // taxonomy code, the crossing commits, S13-06 owns rollback.
            Instant now = Instant.now(clock);
            String failureCode = taxonomyOf(sandbox.failure());
            rows.forEach(row ->
                    row.report(ChannelDistributionEntity.AdapterReport.failed(failureCode), sandbox.calls(), now));
            records.saveAll(rows);
            auditDispatch(event, channel, failureCode, false);
            return;
        }
        Instant now = Instant.now(clock);
        List<AdapterOutcome> outcomes = sandbox.outcomes();
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
                        sandbox.calls(),
                        now);
                allDistributed = false;
            } else {
                row.report(
                        new ChannelDistributionEntity.AdapterReport(
                                ChannelDistributionEntity.Status.valueOf(outcome.status()),
                                outcome.externalId(),
                                outcome.failureCode()),
                        sandbox.calls(),
                        now);
                allDistributed &= AdapterOutcome.DISTRIBUTED.equals(outcome.status());
            }
        }
        records.saveAll(rows);
        auditDispatch(event, channel, allDistributed ? "distributed" : "failed", true);
    }

    /**
     * The sandbox (AC5): one observation per attempt (trace + timer),
     * a wall-clock cut, and bounded category-keyed retries. Anything
     * the adapter throws that is not {@link AdapterException} is
     * PERMANENT (the taxonomy is closed).
     */
    private SandboxResult sandboxedCall(DistributionChannelAdapter adapter, DistributionCommand seed) {
        DistributionProperties.AdapterSandbox sandbox = properties.sandboxOf(adapter.channelId());
        int attempt = 1;
        AdapterException lastFailure = null;
        while (attempt <= sandbox.totalAttempts()) {
            String traceId = currentTraceId();
            String spanId = currentSpanId();
            DistributionCommand command = new DistributionCommand(
                    seed.releaseId(),
                    seed.releaseRef(),
                    seed.applicationId(),
                    seed.versionId(),
                    seed.organizationId(),
                    seed.tenantId(),
                    seed.environmentId(),
                    seed.environmentCode(),
                    seed.channel(),
                    seed.artifacts(),
                    seed.targetTenantIds(),
                    seed.channelConfig(),
                    seed.credential(),
                    traceId,
                    spanId,
                    attempt,
                    seed.initiatedAt());
            Observation observation = Observation.createNotStarted(MetricNames.ADAPTER_CALL, observations)
                    .lowCardinalityKeyValue("channel", adapter.channelId())
                    .lowCardinalityKeyValue("attempt", String.valueOf(attempt));
            Future<List<AdapterOutcome>> call = null;
            try {
                observation.start();
                call = adapterCalls.submit(() -> adapter.distribute(command));
                List<AdapterOutcome> outcomes = call.get(sandbox.timeout().toMillis(), TimeUnit.MILLISECONDS);
                observation.lowCardinalityKeyValue("outcome", "ok");
                observation.stop();
                return new SandboxResult(outcomes, null, attempt);
            } catch (TimeoutException e) {
                // The wall cut: interrupt the hung call so its thread and
                // socket do not linger past the sandbox's verdict.
                if (call != null) {
                    call.cancel(true);
                }
                lastFailure = new AdapterException(
                        AdapterException.Category.TIMEOUT,
                        adapter.channelId(),
                        "the adapter call exceeded its " + sandbox.timeout().toMillis() + " ms timeout");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                lastFailure = cause instanceof AdapterException adapterFailure
                        ? adapterFailure
                        : new AdapterException(
                                AdapterException.Category.PERMANENT,
                                adapter.channelId(),
                                "the adapter failed: " + cause.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                observation.lowCardinalityKeyValue("outcome", "interrupted");
                observation.stop();
                throw new IllegalStateException("adapter dispatch interrupted", e);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                lastFailure = new AdapterException(
                        AdapterException.Category.TRANSIENT, adapter.channelId(), "the adapter pool refused the call");
            }
            observation.lowCardinalityKeyValue("outcome", taxonomyOf(lastFailure));
            observation.stop();
            if (!retryable(lastFailure.category()) || attempt == sandbox.totalAttempts()) {
                return new SandboxResult(null, lastFailure, attempt);
            }
            attempt++;
            sleep(sandbox.retryBackoff());
        }
        return new SandboxResult(null, lastFailure, attempt);
    }

    /** The sandbox's answer: the report on success, the taxonomy failure on exhaustion, and the calls consumed. */
    private record SandboxResult(List<AdapterOutcome> outcomes, AdapterException failure, int calls) {}

    private static boolean retryable(AdapterException.Category category) {
        return category == AdapterException.Category.TRANSIENT
                || category == AdapterException.Category.RATE_LIMITED
                || category == AdapterException.Category.TIMEOUT;
    }

    /** The taxonomy's public code for a failure (the record's and the metric's vocabulary). */
    public static String taxonomyOf(AdapterException failure) {
        return switch (failure.category()) {
            case TIMEOUT -> DistributionException.Code.ADAPTER_TIMEOUT.codeValue();
            case RATE_LIMITED -> DistributionException.Code.ADAPTER_RATE_LIMITED.codeValue();
            case AUTHENTICATION -> DistributionException.Code.ADAPTER_AUTHENTICATION_FAILED.codeValue();
            case TRANSIENT, PERMANENT -> DistributionException.Code.ADAPTER_FAILED.codeValue();
        };
    }

    private void sleep(Duration backoff) {
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("adapter backoff interrupted", e);
        }
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
                    return new DistributionCommand.ArtifactSlot(
                            slot.artifactId(),
                            slot.platform(),
                            facts == null ? null : facts.format(),
                            facts == null ? null : facts.fileName(),
                            facts == null ? 0L : facts.size());
                })
                .toList();

        DistributionCommand.ResolvedCredential credential = resolveCredential(event, adapter);
        if (credential == null && !adapter.credentialProviders().isEmpty()) {
            return null;
        }
        Set<UUID> targets = releases.targetTenantIdsOf(event.releaseId());
        return new DistributionCommand(
                event.releaseId(),
                event.releaseRef(),
                event.applicationId(),
                event.versionId(),
                event.organizationId(),
                event.tenantId(),
                environmentId,
                environmentCode,
                channel,
                artifacts,
                targets,
                channelConfig,
                credential,
                currentTraceId(),
                currentSpanId(),
                1,
                Instant.now(clock));
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
                            account.getProvider(), account.getName(), account.getSecretReference(), material);
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
                .map(row -> new RecordView(
                        row.getId(),
                        row.getReleaseId(),
                        row.getArtifactId(),
                        row.getChannel(),
                        row.getStatus().name(),
                        row.getExternalId(),
                        row.getFailureCode(),
                        row.getAttempts(),
                        row.getLastAttemptAt(),
                        row.getDistributedAt(),
                        row.getCreatedAt(),
                        row.getUpdatedAt()))
                .toList();
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
            int attempts,
            Instant lastAttemptAt,
            Instant distributedAt,
            Instant createdAt,
            Instant updatedAt) {}
}
