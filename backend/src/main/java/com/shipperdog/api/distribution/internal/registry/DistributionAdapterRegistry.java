package com.shipperdog.api.distribution.internal.registry;

import com.shipperdog.api.distribution.AdapterCapability;
import com.shipperdog.api.distribution.DistributionChannelAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The adapter registry (issue #61, S13-01, AC6): every Spring bean of
 * the {@link DistributionChannelAdapter} SPI joins at startup -- a new
 * channel is a {@code @Bean}, and the catalog, credential resolution,
 * and dispatch follow with zero framework edits. Duplicate channel
 * ids fail startup loudly (two adapters claiming one channel is a
 * wiring bug, never a last-registration-wins accident).
 */
@Component
public class DistributionAdapterRegistry {

    /** The channel id reserved for the platform's own direct-download path (the V1 default). */
    public static final String DIRECT_CHANNEL = "direct";

    private final Map<String, DistributionChannelAdapter> byChannel;

    public DistributionAdapterRegistry(List<DistributionChannelAdapter> adapters) {
        Map<String, DistributionChannelAdapter> indexed = new LinkedHashMap<>();
        for (DistributionChannelAdapter adapter : adapters) {
            String id = adapter.channelId();
            DistributionChannelAdapter existing = indexed.putIfAbsent(id, adapter);
            if (existing != null) {
                throw new IllegalStateException("two distribution channel adapters claim channel id '" + id + "': "
                        + existing.getClass().getName() + " and "
                        + adapter.getClass().getName());
            }
        }
        // Insertion order preserved for the catalog's stable console view
        // (Map.copyOf would discard it -- the catalog would hash-order).
        this.byChannel = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
    }

    /** The adapter of one channel id; empty when no adapter registered it. */
    public Optional<DistributionChannelAdapter> byChannelId(String channelId) {
        return Optional.ofNullable(byChannel.get(channelId));
    }

    /** Whether the channel id is registered (the dispatch's unknown-channel guard). */
    public boolean knows(String channelId) {
        return byChannel.containsKey(channelId);
    }

    /** The console catalog: every registered channel with its capabilities and credential providers. */
    public List<CatalogEntry> catalog() {
        return byChannel.values().stream()
                .map(adapter -> new CatalogEntry(
                        adapter.channelId(),
                        adapter.displayName(),
                        adapter.description(),
                        List.copyOf(adapter.capabilities()),
                        Set.copyOf(adapter.credentialProviders())))
                .toList();
    }

    /** One console-catalog row (the channel's declared surface, read-only facts). */
    public record CatalogEntry(
            String channelId,
            String displayName,
            String description,
            List<AdapterCapability> capabilities,
            Set<String> credentialProviders) {}
}
