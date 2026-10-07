package com.shipperdog.api.environment.internal.service;

import com.shipperdog.api.environment.SecretReferenceResolver;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The default {@link SecretReferenceResolver} until the secrets story
 * wires GCP Secret Manager plus Cloud KMS (PRD 25; this story
 * deliberately stores references only). Honest by construction: every
 * reference resolves to nothing, so the credential verify probe answers
 * {@code resolved: false} for every binding on every environment — the
 * port is wired and demonstrably consulted (the probe is audited), and
 * no test or caller can mistake the stub for a store. Integration
 * tests register their own resolver to prove independent resolution
 * (AC5).
 */
@Component
class UnbackedSecretReferenceResolver implements SecretReferenceResolver {

    @Override
    public Optional<String> resolve(String secretReference) {
        return Optional.empty();
    }
}
