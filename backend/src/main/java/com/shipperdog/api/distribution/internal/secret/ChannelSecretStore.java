package com.shipperdog.api.distribution.internal.secret;

import com.shipperdog.api.distribution.SecretMaterial;
import java.util.Optional;
import java.util.UUID;

/**
 * The secret-store port (issue #61, S13-01, PRD 25, ADR-0008): the
 * one interface behind every secret reference the platform holds.
 * Binding a credential stores material ONCE through {@link #store}
 * and keeps only the returned reference; every later use resolves
 * the reference through {@link #resolve} at call time. Two
 * implementations ship: the OSS envelope-encryption store (default)
 * and the GCP Secret Manager client (the production posture of PRD
 * 25) -- selected by {@code shipperdog.secrets.provider}.
 *
 * <p>References are namespaced by store ({@code local-envelope://...}
 * vs {@code gcp-secret-manager://...}); a store resolves only its own
 * namespace and answers empty for every other shape -- an unknown,
 * deleted, rotated-away, or foreign reference is indistinguishable
 * by design (the #58 resolution doctrine), so a caller can learn
 * THAT a reference resolves, never WHY one does not.
 */
public interface ChannelSecretStore {

    /**
     * Stores material under a fresh reference of this store's
     * namespace, answering the reference the binding keeps.
     */
    String store(UUID organizationId, String provider, SecretMaterial material);

    /**
     * Resolves a reference of this store's namespace; empty for every
     * other shape and every failed resolution.
     */
    Optional<SecretMaterial> resolve(String secretReference);

    /**
     * Discards one stored secret of this store's namespace when the
     * binding that kept its reference rotates to a new one -- the
     * store-side hygiene of the slot doctrine, so replaced material
     * does not linger decryptable behind an unreferenced row. A no-op
     * default: stores whose references are not row-per-material (the
     * GCP posture's rotation is a new version under the SAME reference)
     * have nothing to discard, and a foreign namespace is never this
     * store's to delete.
     */
    default void delete(String secretReference) {}
}
