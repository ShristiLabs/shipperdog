package com.shipperdog.api.environment.internal.service;

import com.shipperdog.api.environment.SecretReferenceResolver;
import java.util.Optional;

/**
 * The fallback {@link SecretReferenceResolver} for contexts that
 * register no secret store. Since S13-01 (issue #61) the production
 * backing is the distribution module's selected store (GCP Secret
 * Manager per PRD 25, or the OSS envelope default, ADR-0008) and this
 * stub answers only when no store-backed bean exists -- exactly the
 * honesty it was born with: every reference resolves to nothing, so
 * the credential verify probe answers {@code resolved: false} for
 * every binding, the port is demonstrably consulted, and no caller
 * mistakes the stub for a store. Registered through {@link
 * SecretResolutionFallback} behind {@code @ConditionalOnMissingBean}.
 */
class UnbackedSecretReferenceResolver implements SecretReferenceResolver {

    @Override
    public Optional<String> resolve(String secretReference) {
        return Optional.empty();
    }
}
