package com.shipperdog.api.distribution.internal.secret;

import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.distribution.internal.entity.ManagedSecretEntity;
import com.shipperdog.api.distribution.internal.repository.ManagedSecretRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The OSS envelope-encryption store (issue #61, S13-01, ADR-0008):
 * AES-256-GCM, one random data key per secret (the DEK), the DEK
 * itself wrapped by the key-encryption key (the KEK, sourced from the
 * deployment environment, never committed), and the reference plus
 * key id bound as additional authenticated data so ciphertext and its
 * address cannot be swapped. The database holds only ciphertext and
 * the wrapped DEK (the {@code managed_secrets} table; the schema test
 * pins it), the KEK never leaves this process, and plaintext exists
 * only between the binding write's decrypt and the adapter call.
 *
 * <p>Production posture is GCP Secret Manager + Cloud KMS (PRD 25);
 * this store is the self-hostable default, and ADR-0008 records the
 * trade. A blank configured KEK mints an EPHEMERAL process-local key
 * with a loud warning: acceptable for a local stack whose secrets
 * are re-bindable, never for production -- a restart stops every
 * reference resolving (the verify probe answers {@code resolved:
 * false}, the honest failure; nothing is corrupted).
 */
@Component
@ConditionalOnProperty(name = "shipperdog.secrets.provider", havingValue = "envelope", matchIfMissing = true)
class EnvelopeEncryptionSecretStore implements ChannelSecretStore {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeEncryptionSecretStore.class);

    /** The store's reference namespace (the store resolves only its own). */
    static final String REFERENCE_PREFIX = "local-envelope://";

    private static final String ALGORITHM = "AES-256-GCM";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int DEK_BYTES = 32;
    private static final String CIPHER = "AES/GCM/NoPadding";

    private final ManagedSecretRepository secrets;
    private final SecretKey kek;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    EnvelopeEncryptionSecretStore(ManagedSecretRepository secrets, DistributionSecretProperties properties) {
        this.secrets = secrets;
        SecretKey key = kekOf(properties.envelope().kek());
        this.kek = key;
        this.keyId = keyIdOf(key);
    }

    @Override
    @Transactional
    public String store(UUID organizationId, String provider, SecretMaterial material) {
        String reference = REFERENCE_PREFIX + organizationId + "/" + provider + "/" + UUID.randomUUID();
        byte[] dek = new byte[DEK_BYTES];
        random.nextBytes(dek);
        String ciphertext = seal(material.value().getBytes(StandardCharsets.UTF_8), dek, reference);
        String wrappedDataKey = wrap(dek);
        secrets.save(new ManagedSecretEntity(reference, ciphertext, wrappedDataKey, keyId, ALGORITHM));
        return reference;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SecretMaterial> resolve(String secretReference) {
        if (secretReference == null || !secretReference.startsWith(REFERENCE_PREFIX)) {
            return Optional.empty();
        }
        return secrets.findBySecretReference(secretReference)
                .map(entity -> new SecretMaterial(open(entity)))
                .filter(SecretMaterial::present);
    }

    @Override
    @Transactional
    public void delete(String secretReference) {
        if (secretReference != null && secretReference.startsWith(REFERENCE_PREFIX)) {
            // Rotation hygiene: the replaced material's ciphertext goes with
            // its reference (the slot doctrine leaves no decryptable orphans).
            secrets.findBySecretReference(secretReference).ifPresent(secrets::delete);
        }
    }

    /** AEAD-seals the material under the DEK, the reference and key id bound as AAD. */
    private String seal(byte[] material, byte[] dek, String reference) {
        byte[] iv = new byte[GCM_IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD((reference + "|" + keyId).getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(material);
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("envelope seal failed", e);
        }
    }

    /** Wraps the DEK under the KEK, the key id bound as AAD. */
    private String wrap(byte[] dek) {
        byte[] iv = new byte[GCM_IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(("shipperdog/dek/v1|" + keyId).getBytes(StandardCharsets.UTF_8));
            byte[] wrapped = cipher.doFinal(dek);
            byte[] out = new byte[iv.length + wrapped.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(wrapped, 0, out, iv.length, wrapped.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("envelope wrap failed", e);
        }
    }

    /** Opens one stored secret; any failure is an unresolvable reference by design (empty, never an echo). */
    private String open(ManagedSecretEntity entity) {
        try {
            byte[] wrapped = Base64.getDecoder().decode(entity.getWrappedDataKey());
            Cipher unwrap = Cipher.getInstance(CIPHER);
            unwrap.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, wrapped, 0, GCM_IV_BYTES));
            unwrap.updateAAD(("shipperdog/dek/v1|" + entity.getKeyId()).getBytes(StandardCharsets.UTF_8));
            byte[] dek = unwrap.doFinal(wrapped, GCM_IV_BYTES, wrapped.length - GCM_IV_BYTES);

            byte[] sealed = Base64.getDecoder().decode(entity.getCiphertext());
            Cipher open = Cipher.getInstance(CIPHER);
            open.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, sealed, 0, GCM_IV_BYTES));
            open.updateAAD((entity.getSecretReference() + "|" + entity.getKeyId()).getBytes(StandardCharsets.UTF_8));
            return new String(open.doFinal(sealed, GCM_IV_BYTES, sealed.length - GCM_IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // A rotated-away KEK, tampered ciphertext, or torn row: the
            // reference does not resolve. The caller learns nothing more.
            log.debug("secret reference failed to open under the active KEK generation");
            return "";
        }
    }

    /** The KEK: the configured 32-byte key, or an ephemeral one with the local-only warning. */
    private static SecretKey kekOf(String configuredKek) {
        if (configuredKek == null || configuredKek.isBlank()) {
            log.warn("shipperdog.secrets.envelope.kek is not configured: an EPHEMERAL process-local key-encryption"
                    + " key was minted. Secrets stored now STOP resolving on restart. Set SECRETS_ENVELOPE_KEK"
                    + " (Base64 of 32 bytes) or switch shipperdog.secrets.provider=gcp-secret-manager for"
                    + " production (PRD 25, ADR-0008).");
            byte[] ephemeral = new byte[DEK_BYTES];
            new SecureRandom().nextBytes(ephemeral);
            return new SecretKeySpec(ephemeral, "AES");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configuredKek.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "shipperdog.secrets.envelope.kek is not valid Base64; refusing to start with a broken KEK");
        }
        if (decoded.length != DEK_BYTES) {
            throw new IllegalStateException(
                    "shipperdog.secrets.envelope.kek must decode to 32 bytes" + " (AES-256); found " + decoded.length);
        }
        return new SecretKeySpec(decoded, "AES");
    }

    /** The KEK generation's identifier: the digest prefix rotation compares. */
    private static String keyIdOf(SecretKey key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
            return java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
