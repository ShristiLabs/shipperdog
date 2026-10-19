package com.shipperdog.ota

import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale

/**
 * The OTA bundle verifier (issue #174, S16-12; ADR-0010 D8's frozen
 * crypto contract, pinned exactly as the backend's
 * `OtaSigningKeyService` shapes it — the bytes issue #181's tester
 * proved):
 *
 *  - `publicKey` is Base64 of the RAW 32-byte Ed25519 public key;
 *  - `keyId` is the first 16 LOWERCASE hex characters of the
 *    SHA-256 of that raw key (re-derived here as a pin — a manifest
 *    cannot re-point a key id at another key);
 *  - `signature` is Base64 of the 64-byte Ed25519 signature over the
 *    manifest sha256's 64 LOWERCASE hex characters, UTF-8 — never
 *    over the bundle bytes themselves.
 *
 * JCA only (`java.security`), the ADR-0009 doctrine: no hand-rolled
 * signature paths. The raw 32-byte key rides the standard RFC 8410
 * SubjectPublicKeyInfo prefix into `KeyFactory` — encoding, not
 * hand-rolled curve math. Verification is two independent legs: the
 * content hash (the staged file's sha256 must equal the manifest's
 * label) and the label's Ed25519 signature under the embedded key
 * the manifest's keyId selects. The failure outcomes name the leg
 * that refused, in the server's own report vocabulary
 * (checksum_mismatch, signature_invalid).
 */
class OtaVerifier(verificationKeys: Map<String, String>) {

    private val keys: Map<String, VerifiedKey> = verificationKeys.mapValues { (_, base64) ->
        val raw = Base64.getDecoder().decode(base64)
        require(raw.size == OtaConfig.ED25519_KEY_BYTES) { "a verification key is Base64 of 32 raw bytes" }
        VerifiedKey(raw, publicKeyOf(raw))
    }

    /** The key id of a raw public key: 16 lowercase hex of its SHA-256 (the contract's own derivation). */
    fun keyIdOf(publicKeyBase64: String): String = keyIdOf(Base64.getDecoder().decode(publicKeyBase64))

    /** Verifies the staged bundle file. */
    fun verify(manifest: OtaManifest, bundle: File): VerificationResult =
        bundle.inputStream().use { stream -> verify(manifest, stream, bundle.length()) }

    /** The streaming shape: hashes [length] bytes (or to EOF when unknown), then checks the signature leg. */
    fun verify(manifest: OtaManifest, bytes: InputStream, length: Long): VerificationResult {
        if (length >= 0 && manifest.sizeBytes != null && length != manifest.sizeBytes) {
            return VerificationResult.ChecksumMismatch
        }
        val actualHex = sha256HexOf(bytes)
        if (manifest.sha256.lowercase(Locale.ROOT) != actualHex) {
            return VerificationResult.ChecksumMismatch
        }
        val key = keys[manifest.keyId.lowercase(Locale.ROOT)] ?: return VerificationResult.SignatureInvalid
        if (manifest.keyId.lowercase(Locale.ROOT) != keyIdOf(key.raw)) {
            return VerificationResult.SignatureInvalid
        }
        return try {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key.publicKey)
            verifier.update(actualHex.toByteArray(Charsets.UTF_8))
            if (verifier.verify(Base64.getDecoder().decode(manifest.signature))) {
                VerificationResult.Verified(actualHex)
            } else {
                VerificationResult.SignatureInvalid
            }
        } catch (malformed: IllegalArgumentException) {
            VerificationResult.SignatureInvalid
        } catch (malformed: java.security.SignatureException) {
            // A corrupted signature decodes to an invalid point: the
            // same report leg as a failing one, never an oracle.
            VerificationResult.SignatureInvalid
        }
    }

    /** The verification legs' outcomes, in the ingest contract's own report vocabulary. */
    sealed class VerificationResult {
        data class Verified(val sha256Hex: String) : VerificationResult()

        object ChecksumMismatch : VerificationResult()

        object SignatureInvalid : VerificationResult()
    }

    private class VerifiedKey(val raw: ByteArray, val publicKey: PublicKey)

    companion object {

        /** The RFC 8410 SubjectPublicKeyInfo header of a raw Ed25519 point: fixed, standard, no curve math. */
        internal val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )

        /** 16 lowercase hex of the SHA-256 of the raw key bytes — the keyId derivation. */
        fun keyIdOf(rawPublicKey: ByteArray): String =
            hexOf(MessageDigest.getInstance("SHA-256").digest(rawPublicKey)).substring(0, OtaConfig.KEY_ID_LENGTH)

        /** The manifest sha256's own shape: 64 lowercase hex characters. */
        fun sha256HexOf(bytes: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(16_384)
            while (true) {
                val read = bytes.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
            return hexOf(digest.digest())
        }

        fun hexOf(bytes: ByteArray): String = buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append(HEX[(byte.toInt() shr 4) and 0xF])
                append(HEX[byte.toInt() and 0xF])
            }
        }

        private const val HEX = "0123456789abcdef"

        /** Rebuilds the JCA public key from the raw 32-byte point (RFC 8410's encoded form). */
        internal fun publicKeyOf(raw: ByteArray): PublicKey {
            require(raw.size == OtaConfig.ED25519_KEY_BYTES) { "an Ed25519 public key is 32 raw bytes" }
            val spki = ED25519_SPKI_PREFIX + raw
            return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        }
    }
}
