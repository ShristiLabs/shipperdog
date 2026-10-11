package com.shipperdog.api.distribution.internal.secret;

import com.shipperdog.api.distribution.ChannelSecrets;
import com.shipperdog.api.distribution.SecretMaterial;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The {@link ChannelSecrets} public port's bean: a thin delegate over
 * the selected {@link ChannelSecretStore} (S14-01, issue #67), so other
 * bounded contexts share the one store -- the same envelope-encrypted
 * rows and the same reference doctrine -- without importing this
 * package's internals.
 */
@Component
class ChannelSecretsFacade implements ChannelSecrets {

    private final ChannelSecretStore store;

    ChannelSecretsFacade(ChannelSecretStore store) {
        this.store = store;
    }

    @Override
    public String store(UUID organizationId, String provider, SecretMaterial material) {
        return store.store(organizationId, provider, material);
    }

    @Override
    public Optional<SecretMaterial> resolve(String secretReference) {
        return store.resolve(secretReference);
    }

    @Override
    public void delete(String secretReference) {
        store.delete(secretReference);
    }
}
