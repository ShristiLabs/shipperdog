package com.shipperdog.api.distribution.internal.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.distribution.internal.entity.ManagedSecretEntity;
import com.shipperdog.api.distribution.internal.repository.ManagedSecretRepository;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The envelope store's crypto half of ADR-0008 (issue #61, S13-01):
 * round-trip, tamper rejection, rotated-away-KEK rejection, the
 * reference namespace discipline, and the KEK's own validation rules
 * -- all against a Mockito repository (no Spring context).
 */
class EnvelopeEncryptionSecretStoreTest {

    /** A repository whose save/delete/findBySecretReference triple behaves like an in-memory map. */
    private static ManagedSecretRepository inMemorySecrets() {
        ManagedSecretRepository repository = mock(ManagedSecretRepository.class);
        Map<String, ManagedSecretEntity> rows = new HashMap<>();
        when(repository.save(any(ManagedSecretEntity.class))).thenAnswer(invocation -> {
            ManagedSecretEntity entity = invocation.getArgument(0);
            rows.put(entity.getSecretReference(), entity);
            return entity;
        });
        org.mockito.Mockito.doAnswer(invocation -> {
                    ManagedSecretEntity entity = invocation.getArgument(0);
                    rows.remove(entity.getSecretReference());
                    return null;
                })
                .when(repository)
                .delete(any(ManagedSecretEntity.class));
        when(repository.findBySecretReference(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.getArgument(0))));
        return repository;
    }

    private static DistributionSecretProperties propertiesWithKek(String kek) {
        return new DistributionSecretProperties(
                DistributionSecretProperties.Provider.ENVELOPE,
                new DistributionSecretProperties.Envelope(kek),
                new DistributionSecretProperties.Gcp("", ""));
    }

    private static String kek() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void materialRoundTripsBehindTheReference() {
        ManagedSecretRepository secrets = inMemorySecrets();
        EnvelopeEncryptionSecretStore store = new EnvelopeEncryptionSecretStore(secrets, propertiesWithKek(kek()));

        String reference = store.store(UUID.randomUUID(), "apple", new SecretMaterial("the-api-key"));
        assertThat(reference).startsWith(EnvelopeEncryptionSecretStore.REFERENCE_PREFIX);
        Optional<SecretMaterial> resolved = store.resolve(reference);
        assertThat(resolved).isPresent();
        assertThat(resolved.get().value()).isEqualTo("the-api-key");

        // The stored row is ciphertext, not material.
        ArgumentCaptor<ManagedSecretEntity> saved = ArgumentCaptor.forClass(ManagedSecretEntity.class);
        verify(secrets).save(saved.capture());
        assertThat(saved.getValue().getCiphertext()).doesNotContain("the-api-key");
        assertThat(saved.getValue().getAlgorithm()).isEqualTo("AES-256-GCM");
    }

    @Test
    void foreignReferencesAndTamperedRowsDoNotResolve() {
        ManagedSecretRepository secrets = inMemorySecrets();
        EnvelopeEncryptionSecretStore store = new EnvelopeEncryptionSecretStore(secrets, propertiesWithKek(kek()));

        // Only this store's namespace resolves.
        assertThat(store.resolve("gcp-secret-manager://projects/p/secrets/s")).isEmpty();
        assertThat(store.resolve("not-a-reference")).isEmpty();
        assertThat(store.resolve(null)).isEmpty();

        // A tampered ciphertext fails the AAD/tag check: the reference
        // does not resolve, and the caller learns nothing more.
        String reference = store.store(UUID.randomUUID(), "apple", new SecretMaterial("material"));
        ArgumentCaptor<ManagedSecretEntity> saved = ArgumentCaptor.forClass(ManagedSecretEntity.class);
        verify(secrets).save(saved.capture());
        ManagedSecretEntity tampered = new ManagedSecretEntity(
                reference,
                saved.getValue().getCiphertext().substring(0, 8) + "AAAA",
                saved.getValue().getWrappedDataKey(),
                saved.getValue().getKeyId(),
                saved.getValue().getAlgorithm());
        when(secrets.findBySecretReference(reference)).thenReturn(Optional.of(tampered));
        assertThat(store.resolve(reference)).isEmpty();
    }

    @Test
    void aRotatedAwayKekStopsResolutionWithoutCorruptingAnything() {
        ManagedSecretRepository secrets = inMemorySecrets();
        String reference = new EnvelopeEncryptionSecretStore(secrets, propertiesWithKek(kek()))
                .store(UUID.randomUUID(), "apple", new SecretMaterial("old-generation"));

        // A NEW process with a different KEK cannot open the old rows:
        // every reference of the rotated-away generation answers empty.
        EnvelopeEncryptionSecretStore rotated = new EnvelopeEncryptionSecretStore(secrets, propertiesWithKek(kek()));
        assertThat(rotated.resolve(reference)).isEmpty();
    }

    @Test
    void secretMaterialRedactsInEveryDump() {
        SecretMaterial material = new SecretMaterial("super-secret");
        assertThat(material.toString()).isEqualTo(SecretMaterial.REDACTED).doesNotContain("super-secret");
        Map<String, Object> dump = new HashMap<>();
        dump.put("credential", material);
        assertThat(dump.toString()).doesNotContain("super-secret");
    }

    @Test
    void deleteRemovesTheStoredRowAndForeignNamespacesAreNotOursToDelete() {
        ManagedSecretRepository secrets = inMemorySecrets();
        EnvelopeEncryptionSecretStore store = new EnvelopeEncryptionSecretStore(secrets, propertiesWithKek(kek()));

        // A foreign reference is a no-op, never a throw and never a
        // row touched (the store owns only its namespace).
        store.delete("gcp-secret-manager://projects/p/secrets/s");
        store.delete(null);

        // The owned reference's row goes with it: resolution answers
        // empty afterward (rotation hygiene leaves no decryptable orphans).
        String reference = store.store(UUID.randomUUID(), "apple", new SecretMaterial("gone-soon"));
        store.delete(reference);
        assertThat(store.resolve(reference)).isEmpty();
    }

    @Test
    void aBrokenKekRefusesStartupLoudly() {
        ManagedSecretRepository secrets = inMemorySecrets();
        assertThatThrownBy(() -> new EnvelopeEncryptionSecretStore(secrets, propertiesWithKek("not-base64!!")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EnvelopeEncryptionSecretStore(
                        secrets, propertiesWithKek(Base64.getEncoder().encodeToString(new byte[16]))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
