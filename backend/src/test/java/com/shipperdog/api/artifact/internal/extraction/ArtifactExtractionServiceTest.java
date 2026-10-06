package com.shipperdog.api.artifact.internal.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shipperdog.api.artifact.internal.entity.ArtifactEntity;
import com.shipperdog.api.artifact.internal.entity.ArtifactEntity.Platform;
import com.shipperdog.api.artifact.internal.repository.ArtifactScopedRepository;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.config.MetricNames;
import com.shipperdog.api.config.TraceAttributes;
import com.shipperdog.api.tenant.TenantScopeExecutor;
import com.shipperdog.api.version.VersionAccess;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Unit legs of the async orchestrator's scheduling contract (issue
 * #21): the bounded queue's loud refusal (the artifact records
 * EXTRACTION_QUEUE_FULL and stays VALIDATING), the write-once guard
 * (a double schedule never rewrites a row), and the no-parser skip
 * (a platform with no registered parser schedules nothing — IOS and
 * ANDROID both carry parsers since S5-03, so this test registers only
 * one of them and proves the net still holds). The end-to-end legs
 * live in the container-backed integration suite.
 */
class ArtifactExtractionServiceTest {

    private final ArtifactScopedRepository artifacts = mock(ArtifactScopedRepository.class);

    @SuppressWarnings("unchecked")
    private final TenantScopeExecutor scopeExecutor = mock(TenantScopeExecutor.class);

    {
        // Every test stubs both establishment entry points; which one
        // runs depends on the fixture's owning dimension.
        when(scopeExecutor.inScope(any(), any(), any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(2);
            return work.get();
        });
        org.mockito.Mockito.when(scopeExecutor.inOrganizationScope(any(), any(), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> work = invocation.getArgument(2);
                    return work.get();
                });
    }

    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);

    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final IpaMetadataParser parser =
            new IpaMetadataParser(new ArtifactExtractionProperties(2, 200, 1024 * 1024, 3));

    @Test
    void queueSaturationRecordsTheBlockAndLeavesTheArtifactValidating() {
        ArtifactEntity entity = new ArtifactEntity(
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
        when(artifacts.findScoped(entity.getId())).thenReturn(Optional.of(entity));
        org.mockito.Mockito.doThrow(new TaskRejectedException("queue full"))
                .when(executor)
                .execute(any(Runnable.class));
        ArtifactExtractionService service = service();

        service.schedule(entity, ownership());

        ArgumentCaptor<ArtifactEntity> saved = ArgumentCaptor.forClass(ArtifactEntity.class);
        org.mockito.Mockito.verify(artifacts).saveScoped(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ArtifactEntity.Status.VALIDATING);
        assertThat(saved.getValue().extractionOutcome()).isEqualTo("BLOCKED");
        assertThat(saved.getValue().getExtractionError()).contains("EXTRACTION_QUEUE_FULL");
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(audit).publish(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuditEventType.ARTIFACT_METADATA_EXTRACTED);
        assertThat(event.getValue().success()).isFalse();
        // The refusal writes inside the request's ALREADY-BOUND scope:
        // establishing another would be the re-scope the seam forbids.
        verifyNoInteractions(scopeExecutor);
        assertThat(meters.get(MetricNames.ARTIFACT_EXTRACTION_DURATION)
                        .timer()
                        .getId()
                        .getTags())
                .anySatisfy(tag -> assertThat(tag.getValue()).isEqualTo("failed-infra"));
    }

    @Test
    void doubleScheduleNeverRewritesAnAlreadyDecidedRow() {
        ArtifactEntity entity = new ArtifactEntity(
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
        entity.recordExtractionFailure("{\"code\":\"NOT_A_ZIP\",\"message\":\"x\"}");
        when(artifacts.findScoped(entity.getId())).thenReturn(Optional.of(entity));
        org.mockito.Mockito.doThrow(new TaskRejectedException("queue full"))
                .when(executor)
                .execute(any(Runnable.class));
        ArtifactExtractionService service = service();

        service.schedule(entity, ownership());

        org.mockito.Mockito.verify(artifacts, org.mockito.Mockito.never()).saveScoped(any());
        assertThat(entity.getStatus()).isEqualTo(ArtifactEntity.Status.FAILED);
    }

    @Test
    void platformsWithoutAParserScheduleNothing() {
        ArtifactEntity android = new ArtifactEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Platform.ANDROID,
                "app.apk",
                "key",
                "0".repeat(64),
                100L,
                null,
                ArtifactEntity.Status.VALIDATING,
                UUID.randomUUID(),
                null);
        ArtifactExtractionService service = service();

        service.schedule(android, ownership());

        verifyNoInteractions(executor);
        assertThat(android.extractionOutcome()).isEqualTo("PENDING");
    }

    private ArtifactExtractionService service() {
        return new ArtifactExtractionService(
                artifacts,
                mock(com.shipperdog.api.artifact.ArtifactStore.class),
                java.util.List.of(parser),
                mock(com.shipperdog.api.artifact.internal.validation.ArtifactValidationService.class),
                scopeExecutor,
                audit,
                Clock.systemUTC(),
                new TraceAttributes(io.micrometer.observation.ObservationRegistry.NOOP),
                meters,
                new ArtifactExtractionProperties(2, 200, 1024 * 1024, 3),
                executor);
    }

    private static VersionAccess.VersionOwnership ownership() {
        return new VersionAccess.VersionOwnership(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null);
    }
}
