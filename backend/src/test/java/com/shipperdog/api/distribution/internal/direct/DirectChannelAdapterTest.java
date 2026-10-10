package com.shipperdog.api.distribution.internal.direct;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.distribution.AdapterOutcome;
import com.shipperdog.api.distribution.DistributionCommand;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Direct adapter's format rule (issue #61, S13-01, AC1): APK and
 * ad-hoc/enterprise IPA distribute, AAB is structurally refused
 * (FR-006/PRD 19 -- AAB is Play-only), and an unmapped platform is a
 * configuration fault answered loudly, never a silent fallback (the
 * routing table's own doctrine).
 */
class DirectChannelAdapterTest {

    private final DirectChannelAdapter adapter = new DirectChannelAdapter();

    @Test
    void apkAndIpaSlotsDistributeWithNoExternalSystem() {
        UUID apk = UUID.randomUUID();
        UUID ipa = UUID.randomUUID();
        List<AdapterOutcome> outcomes =
                adapter.distribute(command(List.of(slot(apk, "ANDROID", "APK"), slot(ipa, "IOS", "IPA"))));
        assertThat(outcomes).extracting(AdapterOutcome::status).containsExactly("DISTRIBUTED", "DISTRIBUTED");
        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.externalId()).isNull();
            assertThat(outcome.failureCode()).isNull();
        });
    }

    @Test
    void aabIsStructurallyRefusedOnTheDirectChannel() {
        UUID aab = UUID.randomUUID();
        AdapterOutcome outcome = adapter.distribute(command(List.of(slot(aab, "ANDROID", "AAB"))))
                .getFirst();
        assertThat(outcome.status()).isEqualTo(AdapterOutcome.FAILED);
        assertThat(outcome.failureCode()).isEqualTo(DirectChannelAdapter.DIRECT_FORMAT_REFUSED);
        assertThat(outcome.detail()).contains("AAB");
    }

    @Test
    void anUnroutablePlatformIsALoudConfigurationFault() {
        AdapterOutcome outcome = adapter.distribute(command(List.of(slot(UUID.randomUUID(), "WATCHOS", "IPA"))))
                .getFirst();
        assertThat(outcome.status()).isEqualTo(AdapterOutcome.FAILED);
        assertThat(outcome.failureCode()).isEqualTo(DirectChannelAdapter.DIRECT_FORMAT_REFUSED);
    }

    @Test
    void theCatalogFactsAreTheChannelsOwn() {
        assertThat(adapter.channelId()).isEqualTo("direct");
        assertThat(adapter.capabilities())
                .containsExactly(com.shipperdog.api.distribution.AdapterCapability.DIRECT_ISSUANCE);
        assertThat(adapter.credentialProviders()).isEmpty();
        assertThat(adapter.status(UUID.randomUUID(), "direct", null).status()).isEqualTo(AdapterOutcome.DISTRIBUTED);
    }

    private static DistributionCommand.ArtifactSlot slot(UUID artifactId, String platform, String format) {
        return new DistributionCommand.ArtifactSlot(artifactId, platform, format, "app." + format.toLowerCase(), 1L);
    }

    private static DistributionCommand command(List<DistributionCommand.ArtifactSlot> artifacts) {
        return new DistributionCommand(
                UUID.randomUUID(),
                "R-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                null,
                "direct",
                artifacts,
                Set.of(),
                Map.of(),
                null,
                null,
                null,
                1,
                java.time.Instant.now());
    }
}
