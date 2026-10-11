package com.shipperdog.api.distribution.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shipperdog.api.distribution.internal.config.DistributionProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The reconciliation pass's own guard (issue #62, S13-02): the
 * opt-in is the safety — a default-configured stack never wakes up
 * dialing Apple, and an enabled pass walks exactly the refreshable
 * records with one refresh each, surviving per-record failures.
 */
class DistributionReconciliationSchedulerTest {

    @Test
    void aDisabledPassNeverTouchesTheRecords() {
        DistributionDispatchService dispatch = mock(DistributionDispatchService.class);
        DistributionProperties properties = new DistributionProperties(
                java.util.Map.of(),
                new DistributionProperties.AppleConnector(
                        "https://api.appstoreconnect.apple.com",
                        java.time.Duration.ofSeconds(20),
                        java.time.Duration.ofSeconds(20)),
                new DistributionProperties.GooglePlayConnector(
                        "https://androidpublisher.googleapis.com/androidpublisher/v3/applications",
                        java.time.Duration.ofSeconds(20),
                        java.time.Duration.ofSeconds(60)),
                new DistributionProperties.Reconciliation(false, java.time.Duration.ofMinutes(15)),
                null);
        DistributionReconciliationScheduler scheduler = new DistributionReconciliationScheduler(dispatch, properties);

        scheduler.refreshWalkingRecords();

        verify(dispatch, never()).refreshableRecords(anyInt());
        verify(dispatch, never()).refresh(any(), any(), any());
    }

    @Test
    void anEnabledPassRefreshesEveryDueRecordAndSurvivesFailures() {
        DistributionDispatchService dispatch = mock(DistributionDispatchService.class);
        var firstRecord = recordOf(UUID.randomUUID());
        var secondRecord = recordOf(UUID.randomUUID());
        when(dispatch.refreshableRecords(anyInt())).thenReturn(List.of(firstRecord, secondRecord));
        when(dispatch.refresh(any(), any(), any()))
                .thenThrow(new RuntimeException("provider fault"))
                .thenReturn(null);
        DistributionProperties properties = new DistributionProperties(
                java.util.Map.of(),
                new DistributionProperties.AppleConnector(
                        "https://api.appstoreconnect.apple.com",
                        java.time.Duration.ofSeconds(20),
                        java.time.Duration.ofSeconds(20)),
                new DistributionProperties.GooglePlayConnector(
                        "https://androidpublisher.googleapis.com/androidpublisher/v3/applications",
                        java.time.Duration.ofSeconds(20),
                        java.time.Duration.ofSeconds(60)),
                new DistributionProperties.Reconciliation(true, java.time.Duration.ofMinutes(15)),
                null);
        DistributionReconciliationScheduler scheduler = new DistributionReconciliationScheduler(dispatch, properties);

        scheduler.refreshWalkingRecords();

        // Both records attempted (the pass's blast-radius doctrine: one
        // record's provider fault is never the pass's), the failure
        // swallowed to the log line.
        verify(dispatch).refreshableRecords(DistributionReconciliationScheduler.PASS_LIMIT);
        verify(dispatch).refresh(firstRecord.getOrganizationId(), firstRecord.getId(), null);
        verify(dispatch).refresh(secondRecord.getOrganizationId(), secondRecord.getId(), null);
        assertThat(DistributionReconciliationScheduler.PASS_LIMIT).isPositive();
    }

    private com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity recordOf(UUID id) {
        try {
            var constructor = com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity.class
                    .getDeclaredConstructor();
            constructor.setAccessible(true);
            var entity = constructor.newInstance();
            var field =
                    com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity.class.getDeclaredField(
                            "id");
            field.setAccessible(true);
            field.set(entity, id);
            var organization =
                    com.shipperdog.api.distribution.internal.entity.ChannelDistributionEntity.class.getDeclaredField(
                            "organizationId");
            organization.setAccessible(true);
            organization.set(entity, UUID.randomUUID());
            return entity;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
