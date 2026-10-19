package com.shipperdog.api.otabundle.internal.service;

import com.shipperdog.api.application.ApplicationAccess.ApplicationProfile;
import com.shipperdog.api.distribution.ChannelSecrets;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.otabundle.OtaBundleException;
import com.shipperdog.api.otabundle.internal.entity.OtaSigningKeyEntity;
import com.shipperdog.api.otabundle.internal.repository.OtaSigningKeyScopedRepository;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * App-scoped Ed25519 signing custody (S16-03, issue #165, ADR-0010
 * D8): the secret-store port generalized from channel credentials to
 * bundle signing keys. The private key lives behind {@link
 * ChannelSecrets} (ADR-0008 — envelope-encrypted self-hosted, GCP
 * Secret Manager in production) and exists in JVM memory only for the
 * length of one signing flight; this module's table keeps the public
 * half and the store reference. All cryptography is the JCA's Ed25519
 * — no hand-rolled paths, no new crypto dependencies (ADR-0009).
 *
 * <p>Key material shapes, frozen here because both SDKs verify
 * against them (S16-12/S16-13):
 * <ul>
 *   <li>{@code publicKey} — Base64 of the RAW 32-byte Ed25519 public
 *       key (CryptoKit's {@code rawRepresentation} and Kotlin's
 *       expected form; NOT the 44-byte X.509 SubjectPublicKeyInfo
 *       wrapping JCA's {@code getEncoded()} answers).</li>
 *   <li>{@code signature} — Base64 of the 64-byte Ed25519 signature
 *       over the manifest sha256's 64 lowercase hex characters, UTF-8
 *       encoded: the devices verify exactly what the manifest
 *       carries, byte for byte.</li>
 *   <li>{@code keyId} — 16 hex characters of the SHA-256 of the raw
 *       public key: stable per key generation, collision-safe, and
 *       computable by any holder of the public key alone.</li>
 * </ul>
 *
 * <p>A key is generated lazily, at the first finalize that needs one:
 * reads never mint material (a console read of a key that does not
 * exist yet answers empty, not a surprise side effect), and an
 * application that never authors a bundle never holds a key.
 */
@Service
public class OtaSigningKeyService {

    /**
     * The Ed25519 X.509 SubjectPublicKeyInfo prefix (RFC 8410): the
     * fixed 12-byte DER header {@code 302a300506032b6570032100} before
     * the raw 32 public bytes — the 44-byte encoding JCA's {@code
     * getEncoded()} answers and every crypto stack accepts.
     */
    private static final byte[] ED25519_X509_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private static final int RAW_PUBLIC_BYTES = 32;

    /** The secret-store provider namespace OTA signing keys store under. */
    static final String STORE_PROVIDER = "ota-signing";

    private final OtaSigningKeyScopedRepository keys;
    private final ChannelSecrets secrets;
    private final SecureRandom random = new SecureRandom();

    OtaSigningKeyService(OtaSigningKeyScopedRepository keys, ChannelSecrets secrets) {
        this.keys = keys;
        this.secrets = secrets;
    }

    /**
     * The application's ACTIVE signing key, generating one through the
     * secret-store port when none exists: the keypair is minted in
     * process, the private half is stored ONCE behind the port (the
     * returned reference is all this module keeps), and the public
     * half lands in the registry row — all in the caller's
     * transaction, so a rollback leaves neither row nor stored secret.
     */
    @Transactional
    public OtaSigningKeyEntity activeKeyOf(ApplicationProfile application, UUID actorId) {
        return keys.findOneScoped(activeOf(application.applicationId()))
                .orElseGet(() -> generate(application, actorId));
    }

    /** The ACTIVE key read without any generation side effect; empty before the first bundle. */
    @Transactional(readOnly = true)
    public java.util.Optional<OtaSigningKeyEntity> findActiveKeyOf(UUID applicationId) {
        return keys.findOneScoped(activeOf(applicationId));
    }

    /**
     * Signs the manifest checksum under the key: the private half
     * resolves from the store at call time (never cached — rotation
     * and KEK generations take effect on the next flight), signs the
     * 64 hex characters UTF-8, and the material leaves memory with the
     * call. An unresolvable reference (a rotated-away KEK generation,
     * a deleted store entry) is the honest 503 — never a skip, never a
     * weaker fallback.
     */
    public String sign(OtaSigningKeyEntity key, String sha256Hex) {
        PrivateKey privateKey = resolvePrivateKey(key);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey, random);
            signer.update(sha256Hex.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException | java.security.SignatureException e) {
            throw new OtaBundleException(OtaBundleException.Code.SIGNING_UNAVAILABLE, "the signing operation failed");
        }
    }

    /**
     * Verifies a signature over the checksum — the same JCA path the
     * SDKs mirror. The server-side check the integration suite and the
     * future check response ride; also the honest way this module's
     * tests prove the signed bytes.
     */
    public static boolean verify(String publicKeyBase64, String sha256Hex, String signatureBase64) {
        try {
            PublicKey publicKey = publicKeyOf(Base64.getDecoder().decode(publicKeyBase64));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(sha256Hex.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** The key id of a raw public key: 16 hex of its SHA-256. */
    public static String keyIdOf(byte[] rawPublicKey) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(rawPublicKey))
                    .substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private OtaSigningKeyEntity generate(ApplicationProfile application, UUID actorId) {
        KeyPairGenerator generator;
        try {
            generator = KeyPairGenerator.getInstance("Ed25519");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 is unavailable", e);
        }
        KeyPair pair = generator.generateKeyPair();
        byte[] rawPublic = rawPublicOf(pair.getPublic().getEncoded());
        String reference = secrets.store(
                application.organizationId(),
                STORE_PROVIDER,
                new SecretMaterial(
                        Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())));
        OtaSigningKeyEntity entity = new OtaSigningKeyEntity(
                UUID.randomUUID(),
                application.organizationId(),
                application.tenantId(),
                application.applicationId(),
                keyIdOf(rawPublic),
                Base64.getEncoder().encodeToString(rawPublic),
                reference,
                actorId);
        // The concurrent first-finalize race is resolved by the DATABASE,
        // not a catch: the save's insert defers to commit, so a lost race
        // to V58's one-ACTIVE-row index aborts the WHOLE transaction
        // there — this caller's secret store row rolls back with it (no
        // orphan to hygiene away, the #181 review's correction) and the
        // attempt answers 500; the documented retry self-heals, because
        // activeKeyOf's read then resolves the winner. A flush-here catch
        // would ride the winner but COMMIT this caller's now-unreferenced
        // secret row — strictly worse, which is why there is no catch.
        return keys.saveScoped(entity);
    }

    private PrivateKey resolvePrivateKey(OtaSigningKeyEntity key) {
        SecretMaterial material = secrets.resolve(key.getSecretReference()).orElse(null);
        if (material == null || !material.present()) {
            throw new OtaBundleException(
                    OtaBundleException.Code.SIGNING_UNAVAILABLE,
                    "the signing key reference does not resolve under the active secret-store generation");
        }
        try {
            byte[] pkcs8 = Base64.getDecoder().decode(material.value());
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new OtaBundleException(
                    OtaBundleException.Code.SIGNING_UNAVAILABLE, "the stored signing key material is unusable");
        }
    }

    private static Specification<OtaSigningKeyEntity> activeOf(UUID applicationId) {
        return (root, query, builder) -> builder.and(
                builder.equal(root.get("applicationId"), applicationId), builder.isNull(root.get("revokedAt")));
    }

    /** The raw 32 bytes out of the X.509 SubjectPublicKeyInfo encoding. */
    private static byte[] rawPublicOf(byte[] x509Encoded) {
        if (x509Encoded == null || x509Encoded.length != ED25519_X509_PREFIX.length + RAW_PUBLIC_BYTES) {
            throw new IllegalStateException("unexpected Ed25519 public key encoding length");
        }
        return Arrays.copyOfRange(x509Encoded, x509Encoded.length - RAW_PUBLIC_BYTES, x509Encoded.length);
    }

    /** Rebuilds the JCA public key from the raw 32 bytes (the X.509 wrap). */
    private static PublicKey publicKeyOf(byte[] rawPublic) throws java.security.GeneralSecurityException {
        byte[] x509 = new byte[ED25519_X509_PREFIX.length + rawPublic.length];
        System.arraycopy(ED25519_X509_PREFIX, 0, x509, 0, ED25519_X509_PREFIX.length);
        System.arraycopy(rawPublic, 0, x509, ED25519_X509_PREFIX.length, rawPublic.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(x509));
    }
}
