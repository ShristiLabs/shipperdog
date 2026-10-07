package com.shipperdog.api.environment;

import java.util.Optional;

/**
 * The secret-reference resolution port (issue #58, S9-04; PRD 25: store
 * references, not credentials). The database and every API surface hold
 * only the opaque {@code secret_reference}; material exists solely
 * behind this port at resolution time and is never persisted, logged,
 * or returned by the environment surface — the credential verify probe
 * answers whether a reference RESOLVES, never what it resolves to.
 *
 * <p>The production wiring is GCP Secret Manager plus Cloud KMS (PRD
 * 25) and lands with the secrets story this one deliberately excludes.
 * Until then the default bean is the honest stub: every reference
 * resolves to nothing, so the verify probe answers {@code resolved:
 * false} for every binding — demonstrably wired, deliberately
 * unbacked. Integration tests register their own resolver to prove
 * different environments' references resolve independently (AC5:
 * Production is not QA).
 */
public interface SecretReferenceResolver {

    /**
     * Resolves the secret reference to its material, or empty when the
     * reference does not resolve (unknown, deleted, or access denied —
     * the caller cannot distinguish, by design).
     */
    Optional<String> resolve(String secretReference);
}
