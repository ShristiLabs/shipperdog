package com.shipperdog.api.distribution.internal.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.distribution.internal.entity.ChannelRolloutEntity;
import com.shipperdog.api.distribution.internal.entity.StoreAccountEntity;
import com.shipperdog.api.distribution.internal.repository.ChannelRolloutRepository;
import com.shipperdog.api.distribution.internal.repository.ChannelRolloutStepRepository;
import com.shipperdog.api.distribution.internal.secret.ChannelSecretStore;
import com.shipperdog.api.distribution.internal.service.AdapterCallSandbox;
import com.shipperdog.api.distribution.internal.service.DistributionCredentialService;
import com.shipperdog.api.distribution.internal.service.StagedRolloutReader;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseRolloutPolicyGate;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * The view-path optimistic-conflict translation's own proof (the #65
 * reviewer's follow-up, landed by S13-06): a dashboard READ that
 * loses the version race to a concurrent control retries its
 * observation on the winner's row, and real contention answers the
 * control path's own precise 409 — never a generic 500.
 */
class PlayRolloutViewConflictTest {

    private static final String ACCOUNT_JSON =
            "{\"client_email\":\"svc@test.iam.gserviceaccount.com\",\"private_key\":\"-----BEGIN PRIVATE KEY-----"
                    + "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC-----END PRIVATE KEY-----\\n\","
                    + "\"token_uri\":\"https://oauth2.googleapis.com/token\"}";

    private ChannelRolloutRepository rollouts;
    private AdapterCallSandbox sandbox;
    private GooglePlayDeveloperApiClient play;
    private PlayRolloutService service;
    private ChannelRolloutEntity state;

    @BeforeEach
    void setUp() {
        rollouts = mock(ChannelRolloutRepository.class);
        sandbox = mock(AdapterCallSandbox.class);
        play = mock(GooglePlayDeveloperApiClient.class);
        DistributionCredentialService credentials = mock(DistributionCredentialService.class);
        ChannelSecretStore secrets = mock(ChannelSecretStore.class);
        ReleaseAccess releases = mock(ReleaseAccess.class);
        when(credentials.resolve(any(), any(), any(), anyString()))
                .thenReturn(Optional.of(new StoreAccountEntity(
                        UUID.randomUUID(), null, null, "google-play", "Test Account", null, "test://play")));
        when(secrets.resolve(anyString())).thenReturn(Optional.of(new SecretMaterial(ACCOUNT_JSON)));
        when(releases.distributionFactsOf(any())).thenReturn(Optional.empty());
        when(releases.targetTenantIdsOf(any())).thenReturn(java.util.Set.of());
        // The sandbox runs the conversation's lambda for real: the
        // observation reads the stubbed live track below.
        when(sandbox.run(anyString(), anyString(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            IntFunction<Object> call = invocation.getArgument(2);
            return new AdapterCallSandbox.Result<>(call.apply(1), null, 1);
        });
        when(play.liveTrackOf(any(), anyString(), anyString()))
                .thenReturn(new GooglePlayDeveloperApiClient.Track(
                        "production",
                        List.of(new GooglePlayDeveloperApiClient.TrackRelease(
                                "staged-42", List.of(42L), List.of(), "inProgress", 0.25d))));
        service = new PlayRolloutService(
                rollouts,
                mock(ChannelRolloutStepRepository.class),
                credentials,
                secrets,
                releases,
                null,
                mock(ReleaseRolloutPolicyGate.class),
                mock(StagedRolloutReader.class),
                play,
                sandbox,
                mock(AuditLogger.class),
                Clock.systemUTC());
    }

    @Test
    void aLostVersionRaceIsRetriedOnTheWinnersRowAndServesTheObservation() {
        state = new ChannelRolloutEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "play",
                "production#42#com.example.app",
                25,
                ChannelRolloutEntity.Status.ROLLING,
                "production");
        // First save loses the race; the second (the retry on the
        // re-read row) commits. The observation is idempotent, so the
        // dashboard answer is the provider's, never the race's.
        when(rollouts.findByReleaseIdAndChannelAndOrganizationId(any(), anyString(), any()))
                .thenReturn(Optional.of(state));
        when(rollouts.saveAndFlush(any(ChannelRolloutEntity.class)))
                .thenThrow(new OptimisticLockingFailureException("stale"))
                .thenReturn(null);

        PlayRolloutService.RolloutView viewed = service.view(UUID.randomUUID(), UUID.randomUUID());

        assertThat(viewed.intendedPercent()).isEqualTo(25);
        assertThat(viewed.observedPercent()).isEqualTo(25);
        verify(rollouts, times(2)).saveAndFlush(any(ChannelRolloutEntity.class));
    }

    @Test
    void persistentContentionAnswersThePreciseConflictNeverAGeneric500() {
        state = new ChannelRolloutEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "play",
                "production#42#com.example.app",
                25,
                ChannelRolloutEntity.Status.ROLLING,
                "production");
        when(rollouts.findByReleaseIdAndChannelAndOrganizationId(any(), anyString(), any()))
                .thenReturn(Optional.of(state));
        when(rollouts.saveAndFlush(any(ChannelRolloutEntity.class)))
                .thenThrow(new OptimisticLockingFailureException("stale"));

        assertThatThrownBy(() -> service.view(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DistributionException.class)
                .hasMessageContaining("changed concurrently");
        // Every attempt re-read the winner's row before giving up.
        verify(rollouts, times(3)).saveAndFlush(any(ChannelRolloutEntity.class));
    }
}
