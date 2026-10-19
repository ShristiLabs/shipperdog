package com.shipperdog.ota

import java.io.ByteArrayInputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The frozen crypto contract's proof (issue #174, S16-12; the bytes
 * issue #181's tester pinned against the backend's own
 * `OtaSigningKeyService`):
 *
 *  - the public key is Base64 of the RAW 32-byte Ed25519 point;
 *  - the keyId is the first 16 lowercase hex of the key's SHA-256;
 *  - the signature is Ed25519 (JCA) over the manifest sha256's 64
 *    LOWERCASE hex characters, UTF-8 — never over the bundle bytes.
 *
 * The suite mints a REAL keypair and signs the way the backend does,
 * then proves the acceptance criterion's own legs: a valid bundle
 * verifies; the tampered-SIGNATURE and tampered-CHECKSUM cases are
 * both rejected, each named for its report word.
 */
class OtaVerifierTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private val rawPublicKey: ByteArray = keyPair.public.encoded.copyOfRange(12, 44)

    private val publicKeyBase64: String = Base64.getEncoder().encodeToString(rawPublicKey)

    private val keyId: String = OtaVerifier.keyIdOf(rawPublicKey)

    private val content: ByteArray = ByteArray(300) { it.toByte() }

    private val checksumHex: String = OtaVerifier.sha256HexOf(ByteArrayInputStream(content))

    private fun signedChecksumHex(hex: String = checksumHex): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(hex.toByteArray(Charsets.UTF_8))
                sign()
            },
        )

    private fun manifest(
        sha256: String = checksumHex,
        signature: String = signedChecksumHex(),
        keyId: String = this.keyId,
        sizeBytes: Long? = content.size.toLong(),
    ): OtaManifest = OtaManifest(
        bundleId = "11111111-1111-1111-1111-111111111111",
        bundleVersion = "1.4.2",
        url = "https://downloads.example.com/api/v1/downloads/t",
        sha256 = sha256,
        signature = signature,
        keyId = keyId,
        sizeBytes = sizeBytes,
        forced = false,
        minAppVersion = null,
        ingestToken = "sdt1.grant",
        pollIntervalSeconds = 60,
    )

    private fun verifier(keys: Map<String, String> = mapOf(keyId to publicKeyBase64)) = OtaVerifier(keys)

    @Test
    fun theKeyIdIsTheLowercaseSixteenHexOfTheKeysSha256() {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(rawPublicKey)
        val expected = OtaVerifier.hexOf(digest).substring(0, 16)
        assertEquals(expected, keyId)
        assertEquals(16, keyId.length)
        assertTrue(keyId == keyId.lowercase())
    }

    @Test
    fun aValidBundleVerifies() {
        val staged = createTempDirectory("sd-ota-verify").resolve("bundle").toFile()
        staged.writeBytes(content)
        val result = verifier().verify(manifest(), staged)
        assertTrue(result is OtaVerifier.VerificationResult.Verified)
        assertEquals(checksumHex, (result as OtaVerifier.VerificationResult.Verified).sha256Hex)
    }

    @Test
    fun theTamperedSignatureCaseIsRejected() {
        val tampered = Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(signedChecksumHex()).also { it[0] = (it[0].toInt() xor 0x40).toByte() },
        )
        val staged = createTempDirectory("sd-ota-verify").resolve("bundle").toFile()
        staged.writeBytes(content)
        val result = verifier().verify(manifest(signature = tampered), staged)
        assertTrue(result is OtaVerifier.VerificationResult.SignatureInvalid)
    }

    @Test
    fun theTamperedChecksumCaseIsRejected() {
        val staged = createTempDirectory("sd-ota-verify").resolve("bundle").toFile()
        staged.writeBytes(content)
        val flipped = content.copyOf().also { it[7] = (it[7].toInt() xor 0x01).toByte() }
        val tamperedFile = createTempDirectory("sd-ota-verify").resolve("tampered").toFile()
        tamperedFile.writeBytes(flipped)
        // A staged file whose bytes differ from the manifest label.
        val result = verifier().verify(manifest(), tamperedFile)
        assertTrue(result is OtaVerifier.VerificationResult.ChecksumMismatch)
        // And the label-side tamper: content matches, label does not.
        val wrongLabel = checksumHex.substring(0, 63) + if (checksumHex.endsWith("0")) "1" else "0"
        assertTrue(verifier().verify(manifest(sha256 = wrongLabel), staged) is OtaVerifier.VerificationResult.ChecksumMismatch)
    }

    @Test
    fun aWrongKeyOrRepointedKeyIdIsRejected() {
        val otherPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val otherKey = Base64.getEncoder().encodeToString(otherPair.public.encoded.copyOfRange(12, 44))
        val staged = createTempDirectory("sd-ota-verify").resolve("bundle").toFile()
        staged.writeBytes(content)
        // The manifest's keyId selects a DIFFERENT key than signed: unknown key id.
        assertTrue(
            verifier(keys = mapOf(OtaVerifier.keyIdOf(otherPair.public.encoded.copyOfRange(12, 44)) to otherKey))
                .verify(manifest(), staged) is OtaVerifier.VerificationResult.SignatureInvalid,
        )
        // A repointed keyId: the id names one key, the signature came from another.
        val repointed = mapOf(
            keyId to otherKey,
            OtaVerifier.keyIdOf(otherPair.public.encoded.copyOfRange(12, 44)) to otherKey,
        )
        assertTrue(
            verifier(keys = repointed).verify(
                manifest(keyId = OtaVerifier.keyIdOf(otherPair.public.encoded.copyOfRange(12, 44))),
                staged,
            ) is OtaVerifier.VerificationResult.SignatureInvalid,
        )
    }

    @Test
    fun aSizeMismatchIsRejectedBeforeHashing() {
        val result = verifier().verify(manifest(sizeBytes = content.size + 1L), ByteArrayInputStream(content), content.size.toLong())
        assertTrue(result is OtaVerifier.VerificationResult.ChecksumMismatch)
    }

    @Test
    fun theChecksumContractIsLowercaseHexOverTheBytesNotTheLabel() {
        // The signature must verify over the LOWERCASE hex string: an
        // uppercase-hex digest signed by the backend path verifies here.
        assertEquals(64, checksumHex.length)
        assertTrue(checksumHex == checksumHex.lowercase())
        val stream = ByteArrayInputStream(content)
        val verified = verifier().verify(manifest(), stream, content.size.toLong())
        assertTrue(verified is OtaVerifier.VerificationResult.Verified)
    }

    @Test
    fun theStringOverloadDerivesTheSameKeyId() {
        assertEquals(keyId, verifier().keyIdOf(publicKeyBase64))
    }

    @Test
    fun aConstructorRefusesAKeyThatIsNotThirtyTwoRawBytes() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(31))
        kotlin.test.assertFailsWith<IllegalArgumentException> { OtaVerifier(mapOf("aaaaaaaaaaaaaaaa" to shortKey)) }
    }

    @Test
    fun uppercasedLabelsAndKeyIdsNormalizeToTheFrozenContract() {
        // The wire may hand back uppercase hex; the content leg and the
        // keyId lookup normalize, and the signature stays over the
        // LOWERCASE digest the backend signed.
        val staged = createTempDirectory("sd-ota-verify").resolve("bundle").toFile()
        staged.writeBytes(content)
        val upper = verifier().verify(
            manifest(sha256 = checksumHex.uppercase(), keyId = keyId.uppercase()),
            staged,
        )
        assertTrue(upper is OtaVerifier.VerificationResult.Verified)
    }
}
