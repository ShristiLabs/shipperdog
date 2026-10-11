package com.shipperdog.api.distribution.internal.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.internal.entity.ChannelRolloutEntity;
import com.shipperdog.api.distribution.internal.repository.ChannelRolloutRepository;
import com.shipperdog.api.distribution.internal.repository.ChannelRolloutStepRepository;
import com.shipperdog.api.distribution.internal.secret.ChannelSecretStore;
import com.shipperdog.api.distribution.internal.service.AdapterCallSandbox;
import com.shipperdog.api.distribution.internal.service.DistributionCredentialService;
import com.shipperdog.api.distribution.internal.service.StagedRolloutReader;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseRolloutPolicyGate;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The staged rollout's play arms' guard proofs (issue #66, S13-06,
 * AC3/AC4): the arms answer their honest outcome vocabulary for every
 * standing the curve can hold — and never dial the provider when a
 * guard refuses, and never override the store side's own standing
 * (a manually halted curve stays halted; the outcome says so).
 */
class PlayRolloutArmGuardsTest {

    private ChannelRolloutRepository rollouts;
    private GooglePlayDeveloperApiClient play;
    private PlayRolloutService service;
    private final UUID org = UUID.randomUUID();
    private final UUID release = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        rollouts = mock(ChannelRolloutRepository.class);
        play = mock(GooglePlayDeveloperApiClient.class);
        service = new PlayRolloutService(
                rollouts,
                mock(ChannelRolloutStepRepository.class),
                mock(DistributionCredentialService.class),
                mock(ChannelSecretStore.class),
                mock(ReleaseAccess.class),
                null,
                mock(ReleaseRolloutPolicyGate.class),
                mock(StagedRolloutReader.class),
                play,
                mock(AdapterCallSandbox.class),
                mock(AuditLogger.class),
                Clock.systemUTC());
    }

    private Optional<ChannelRolloutEntity> curveOf(ChannelRolloutEntity.Status status) {
        ChannelRolloutEntity state = new ChannelRolloutEntity(
                release, org, "play", "production#42#com.example.app", 25, status, "production");
        return Optional.of(state);
    }

    @Test
    void noCurveAnswersNoStoreCurveOnBothArms() {
        when(rollouts.findByReleaseIdAndChannelAndOrganizationId(any(), anyString(), any()))
                .thenReturn(Optional.empty());

        assertThat(service.widenForStagedRollout(org, release, 50, null)).isEqualTo("no-store-curve");
        assertThat(service.haltForStagedRollback(org, release, null)).isEqualTo("no-store-curve");
        verifyNoInteractions(play);
    }

    @Test
    void aHaltedCurveIsNeverSilentlyOverridden() {
        when(rollouts.findByReleaseIdAndChannelAndOrganizationId(any(), anyString(), any()))
                .thenReturn(curveOf(ChannelRolloutEntity.Status.HALTED));

        // The widening arm records the operator's own stop — never a
        // provider write past it; the rollback arm is idempotent-tolerant.
        assertThat(service.widenForStagedRollout(org, release, 50, null)).isEqualTo("curve-halted");
        assertThat(service.haltForStagedRollback(org, release, null)).isEqualTo("already-halted");
        verifyNoInteractions(play);
    }

    @Test
    void aCompleteCurveRefusesTheWidening() {
        when(rollouts.findByReleaseIdAndChannelAndOrganizationId(any(), anyString(), any()))
                .thenReturn(curveOf(ChannelRolloutEntity.Status.COMPLETE));

        assertThat(service.widenForStagedRollout(org, release, 50, null)).isEqualTo("curve-complete");
        assertThat(service.haltForStagedRollback(org, release, null)).isEqualTo("already-halted");
        verifyNoInteractions(play);
    }
}
