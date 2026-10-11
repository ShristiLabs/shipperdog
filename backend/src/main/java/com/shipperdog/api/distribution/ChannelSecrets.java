package com.shipperdog.api.distribution;

import java.util.Optional;
import java.util.UUID;

/**
 * The secret store's public face for the platform's other bounded
 * contexts (S14-01, issue #67): the same store/reference/resolve
 * doctrine the distribution credential chain established (S13-01,
 * ADR-0008, PRD 25 "store references, not credentials"), opened to
 * modules that hold their own credential-bearing configuration -- the
 * identity module's OIDC client secret is the first. Material is
 * stored ONCE through {@link #store}; the row keeps only the returned
 * reference; every use resolves it at call time. Cross-module callers
 * must use this surface, never the store implementation (ArchUnit).
 */
public interface ChannelSecrets {

    /**
     * Stores material under a fresh reference of the selected store's
     * namespace, answering the reference the caller's row keeps.
     */
    String store(UUID organizationId, String provider, SecretMaterial material);

    /**
     * Resolves a reference; empty for every foreign shape and every
     * failed resolution (unknown, deleted, access denied -- the caller
     * cannot distinguish, by design).
     */
    Optional<SecretMaterial> resolve(String secretReference);

    /**
     * Discards one stored secret when its keeping row rotates to a new
     * reference -- replaced material must not linger decryptable behind
     * an unreferenced row.
     */
    void delete(String secretReference);
}
