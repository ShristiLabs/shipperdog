package com.shipperdog.api.otabundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.otabundle.internal.service.OtaSigningKeyService;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * The frozen signing-contract shapes (S16-03, issue #165, ADR-0010 D8)
 * both device SDKs verify against (S16-12/S16-13): the key id is
 * derivable from the public key alone, the signature is over the
 * manifest checksum's 64 lowercase hex characters UTF-8 encoded, and
 * verification refuses tampered bytes, wrong checksums, and malformed
 * material. Pure JCA round-trips, no container — the full lifecycle
 * (store custody, per-app reuse) lives in the integration suite.
 */
class OtaSigningKeyServiceTest {

    private static final String CHECKSUM = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** An Ed25519 keypair minted through the same JCA the service uses. */
    private record Material(KeyPair pair, byte[] rawPublic, String publicKeyBase64) {}

    @Test
    void keyIdIsDerivableFromThePublicKeyAlone() throws Exception {
        Material material = mint();

        String keyId = OtaSigningKeyService.keyIdOf(material.rawPublic());

        // The SDK contract: 16 lowercase hex of the SHA-256 of the raw
        // public key — any holder of the public key computes the same id.
        assertThat(keyId).hasSize(16).matches("[0-9a-f]{16}");
        String expected = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(material.rawPublic()))
                .substring(0, 16);
        assertThat(keyId).isEqualTo(expected);

        // The console surface hands out Base64 of the raw 32 bytes (44
        // characters — never the 44-byte X.509 wrap, which Base64s to 60).
        assertThat(material.publicKeyBase64()).hasSize(44);
        assertThat(material.rawPublic()).hasSize(32);
    }

    @Test
    void signatureVerifiesOverTheManifestChecksum() throws Exception {
        Material material = mint();
        String signature = signWith(material, CHECKSUM);

        assertThat(OtaSigningKeyService.verify(material.publicKeyBase64(), CHECKSUM, signature))
                .as("the signature verifies over the checksum it was made over")
                .isTrue();
        // Both sides normalize case: the manifest carries lowercase, and
        // an uppercase render of the same characters verifies too.
        assertThat(OtaSigningKeyService.verify(material.publicKeyBase64(), CHECKSUM.toUpperCase(), signature))
                .isTrue();
    }

    @Test
    void verificationRefusesTamperedAndForeignMaterial() throws Exception {
        Material material = mint();
        String signature = signWith(material, CHECKSUM);

        // A flipped checksum byte (the classic substitution): refused.
        char[] tampered = CHECKSUM.toCharArray();
        tampered[0] = tampered[0] == '0' ? '1' : '0';
        assertThat(OtaSigningKeyService.verify(material.publicKeyBase64(), new String(tampered), signature))
                .isFalse();

        // A signature made under a different key: refused.
        Material other = mint();
        assertThat(OtaSigningKeyService.verify(other.publicKeyBase64(), CHECKSUM, signature))
                .isFalse();

        // Malformed inputs degrade to refusal, never throw.
        assertThat(OtaSigningKeyService.verify("not-base64!", CHECKSUM, signature))
                .isFalse();
        assertThat(OtaSigningKeyService.verify(material.publicKeyBase64(), CHECKSUM, "not-base64!"))
                .isFalse();
        byte[] raw = Base64.getDecoder().decode(material.publicKeyBase64());
        raw[0] ^= 0x01;
        assertThat(OtaSigningKeyService.verify(Base64.getEncoder().encodeToString(raw), CHECKSUM, signature))
                .as("an Ed25519 public key with one flipped byte")
                .isFalse();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Material mint() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] x509 = pair.getPublic().getEncoded();
        byte[] rawPublic = java.util.Arrays.copyOfRange(x509, x509.length - 32, x509.length);
        return new Material(pair, rawPublic, Base64.getEncoder().encodeToString(rawPublic));
    }

    /** Signs exactly the way the service's sign() does, via raw JCA. */
    private static String signWith(Material material, String checksumHex) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(java.security.KeyFactory.getInstance("Ed25519")
                .generatePrivate(
                        new PKCS8EncodedKeySpec(material.pair().getPrivate().getEncoded())));
        signer.update(checksumHex.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
