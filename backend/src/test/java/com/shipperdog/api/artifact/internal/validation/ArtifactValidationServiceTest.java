package com.shipperdog.api.artifact.internal.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.artifact.ArtifactException;
import com.shipperdog.api.artifact.ArtifactStore;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity.Platform;
import com.shipperdog.api.artifact.internal.repository.ArtifactScopedRepository;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.version.VersionAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The two infra legs of the validation pipeline (issue #24, S5-05) —
 * the outcomes the integration suite cannot reach deterministically:
 * a saturated queue refuses at schedule time and records the block
 * synchronously in the caller's scope, and an unreadable store retries
 * a bounded number of times before the attempt records
 * VALIDATION_UNAVAILABLE. Both keep the artifact VALIDATING: the
 * document is what makes the stall visible instead of silent, and
 * infrastructure never fails a binary it could not read.
 */
class ArtifactValidationServiceTest {

    /** Documents are plain maps of scalars; one mapper reads them all. */
    private static final ObjectMapper DOCUMENTS = new ObjectMapper();

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), java.time.ZoneOffset.UTC);

    private final ArtifactScopedRepository artifacts = mock(ArtifactScopedRepository.class);

    private final ArtifactStore store = mock(ArtifactStore.class);

    private final ApplicationAccess applications = mock(ApplicationAccess.class);

    private final VersionAccess versions = mock(VersionAccess.class);

    private final TenantScopeExecutor scopeExecutor = mock(TenantScopeExecutor.class);

    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);

    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private ArtifactValidationService service;

    private final AtomicReference<ArtifactEntity> saved = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        // The scope executor runs the work inline: the write phase is
        // what the tests assert, not the scope establishment.
        doAnswer(invocation -> invocation
                        .getArgument(2, java.util.function.Supplier.class)
                        .get())
                .when(scopeExecutor)
                .inScope(any(), any(), any());
        doAnswer(invocation -> invocation
                        .getArgument(2, java.util.function.Supplier.class)
                        .get())
                .when(scopeExecutor)
                .inOrganizationScope(any(), any(), any());
        doAnswer(invocation -> {
                    saved.set(invocation.getArgument(0, ArtifactEntity.class));
                    return invocation.getArgument(0, ArtifactEntity.class);
                })
                .when(artifacts)
                .saveScoped(any());
        service = new ArtifactValidationService(
                artifacts,
                store,
                applications,
                versions,
                scopeExecutor,
                audit,
                eventPublisher(),
                CLOCK,
                new TraceAttributes(ObservationRegistry.NOOP),
                meters,
                new ArtifactValidationProperties(2, 200, 2),
                executor);
    }

    /** The bus publisher stub (S12-01): the recorder is tested in the notification suite; this seam is a no-op. */
    private static ApplicationEventPublisher eventPublisher() {
        return event -> {};
    }

    @Test
    void aSaturatedQueueRecordsTheBlockSynchronouslyAndStaysValidating() {
        ArtifactEntity artifact = artifact();
        when(artifacts.findScoped(artifact.getId())).thenReturn(Optional.of(artifact));
        doThrow(new TaskRejectedException("queue full")).when(executor).execute(any(Runnable.class));

        service.schedule(artifact, ownership());

        JsonNode document = DOCUMENTS.readTree(artifact.getValidation());
        assertThat(document.get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(document.get("code").asText()).isEqualTo("VALIDATION_QUEUE_FULL");
        assertThat(document.get("reason").asText()).contains("queue is full");
        assertThat(artifact.getStatus()).isEqualTo(ArtifactEntity.Status.VALIDATING);
        Mockito.verify(artifacts).saveScoped(artifact);
        Mockito.verify(audit).publish(any());
        assertThat(meters.get("shipperdog.artifact.validation.duration")
                        .tag("platform", "IOS")
                        .tag("outcome", "blocked")
                        .timer()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void anUnreadableStoreRetriesThenRecordsUnavailable() {
        ArtifactEntity artifact = artifact();
        when(artifacts.findScoped(artifact.getId())).thenReturn(Optional.of(artifact));
        when(store.sizeOf(any()))
                .thenThrow(new ArtifactException(ArtifactException.Code.STORAGE_UNAVAILABLE, "store down"));
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(executor)
                .execute(any(Runnable.class));

        long started = System.nanoTime();
        service.schedule(artifact, ownership());
        // Two attempts with the bounded backoff between them.
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isGreaterThanOrEqualTo(250);

        Mockito.verify(store, Mockito.times(2)).sizeOf(any());
        JsonNode document = DOCUMENTS.readTree(artifact.getValidation());
        assertThat(document.get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(document.get("code").asText()).isEqualTo("VALIDATION_UNAVAILABLE");
        assertThat(document.get("reason").asText()).contains("could not be read");
        assertThat(artifact.getStatus()).isEqualTo(ArtifactEntity.Status.VALIDATING);
    }

    @Test
    void anUnsettledExtractionBlocksTheAttemptVisibly() {
        // The manual re-drive window: validation scheduled while the
        // extraction tier has produced no outcome. The attempt closes
        // BLOCKED (not a stage failure), the artifact stays
        // VALIDATING, and the document carries the reason.
        ArtifactEntity artifact = artifact();
        when(artifacts.findScoped(artifact.getId())).thenReturn(Optional.of(artifact));
        when(store.sizeOf(any())).thenReturn(java.util.OptionalLong.of(artifact.getSize()));
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(executor)
                .execute(any(Runnable.class));

        service.schedule(artifact, ownership());

        JsonNode document = DOCUMENTS.readTree(artifact.getValidation());
        assertThat(document.get("outcome").asText()).isEqualTo("BLOCKED");
        assertThat(document.get("reason").asText()).contains("not settled");
        assertThat(document.has("code")).isFalse();
        assertThat(document.get("stages").size()).isEqualTo(1);
        assertThat(document.get("stages").get(0).get("name").asText()).isEqualTo("FILE");
        assertThat(artifact.getStatus()).isEqualTo(ArtifactEntity.Status.VALIDATING);
    }

    private static ArtifactEntity artifact() {
        return new ArtifactEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Platform.IOS,
                "app.ipa",
                "key",
                "0".repeat(64),
                100L,
                null,
                ArtifactEntity.Status.VALIDATING,
                UUID.randomUUID(),
                null);
    }

    private static VersionAccess.VersionOwnership ownership() {
        return new VersionAccess.VersionOwnership(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);
    }
}
