package com.shipperdog.api.distribution.internal.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shipperdog.api.distribution.AdapterCapability;
import com.shipperdog.api.distribution.AdapterOutcome;
import com.shipperdog.api.distribution.DistributionChannelAdapter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The registry's fail-fast half of AC6 (issue #61, S13-01): two beans
 * claiming one channel id are a wiring bug that stops startup loudly
 * -- never a last-registration-wins accident that silently strands one
 * adapter's dispatches on the other's implementation. The bean-discovery
 * half (a test-only adapter joining with zero core changes) is the
 * framework integration suite's proof.
 */
class DistributionAdapterRegistryTest {

    private static DistributionChannelAdapter adapterOf(String channelId, String displayName) {
        return new DistributionChannelAdapter() {

            @Override
            public String channelId() {
                return channelId;
            }

            @Override
            public String displayName() {
                return displayName;
            }

            @Override
            public String description() {
                return displayName + " description";
            }

            @Override
            public Set<AdapterCapability> capabilities() {
                return Set.of(AdapterCapability.BUILD_UPLOAD);
            }

            @Override
            public List<AdapterOutcome> distribute(com.shipperdog.api.distribution.DistributionCommand command) {
                return List.of();
            }

            @Override
            public AdapterOutcome status(UUID artifactId, String channel, String externalId) {
                return new AdapterOutcome(artifactId, AdapterOutcome.FAILED, null, "never", null);
            }
        };
    }

    @Test
    void twoAdaptersClaimingOneChannelIdStopStartupLoudlyNamingBoth() {
        DistributionChannelAdapter first = adapterOf("play", "First Play");
        DistributionChannelAdapter second = adapterOf("play", "Second Play");
        assertThatThrownBy(() -> new DistributionAdapterRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("play")
                .hasMessageContaining(first.getClass().getName())
                .hasMessageContaining(second.getClass().getName());
    }

    @Test
    void anEmptyAdapterListIsTheDirectOnlyWorld() {
        DistributionAdapterRegistry registry = new DistributionAdapterRegistry(List.of());
        assertThat(registry.byChannelId(DistributionAdapterRegistry.DIRECT_CHANNEL))
                .isEmpty();
        assertThat(registry.knows("direct")).isFalse();
        assertThat(registry.catalog()).isEmpty();
    }

    @Test
    void indexingKnowsAndCatalogAnswerTheRegisteredFacts() {
        DistributionChannelAdapter direct = adapterOf("direct", "Direct Download");
        DistributionChannelAdapter play = adapterOf("play", "Google Play");
        DistributionAdapterRegistry registry = new DistributionAdapterRegistry(List.of(direct, play));

        assertThat(registry.byChannelId("play")).contains(play);
        assertThat(registry.byChannelId("nope")).isEmpty();
        assertThat(registry.knows("direct")).isTrue();
        assertThat(registry.knows("nope")).isFalse();

        // The catalog is every registered adapter's declared surface,
        // registration order preserved for the stable console view.
        assertThat(registry.catalog())
                .extracting(DistributionAdapterRegistry.CatalogEntry::channelId)
                .containsExactly("direct", "play");
        assertThat(registry.catalog().get(1).capabilities()).containsExactly(AdapterCapability.BUILD_UPLOAD);
    }
}
