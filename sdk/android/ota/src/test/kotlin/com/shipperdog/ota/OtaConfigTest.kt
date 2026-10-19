package com.shipperdog.ota

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The config's wire and boundary contract (issue #174, S16-12): the
 * check query against the frozen baseline.yaml surface — every member
 * the device reports, lowercased and URL-encoded exactly once, the
 * OPTIONAL `bundleVersion` present only when the device carries a
 * label — and the construction-time bounds that mirror the check
 * surface's own validation (S16-07) plus D8's frozen key shapes, so a
 * misconfigured SDK fails before the network ever does.
 */
class OtaConfigTest {

    private fun config(
        deviceId: String = "device-1",
        platform: String = "Android",
        appVersion: String = "2.0.0",
        channel: String = "OTA",
        bundleVersion: String? = null,
        verificationKeys: Map<String, String> = emptyMap(),
        pollIntervalSeconds: Long = 60,
        maxQueueEvents: Int = 200,
    ): OtaConfig = OtaConfig(
        baseUrl = "https://shipperdog.example.com",
        appToken = "sdc1.fleet-token",
        deviceId = deviceId,
        platform = platform,
        appVersion = appVersion,
        channel = channel,
        bundleVersion = bundleVersion,
        pollIntervalSeconds = pollIntervalSeconds,
        verificationKeys = verificationKeys,
        maxQueueEvents = maxQueueEvents,
    )

    @Test
    fun theCheckQueryCarriesEveryMemberEncodedAndLowercased() {
        // The members the wire can hand the encoder: a space, an
        // ampersand, mixed case — the query must stay ONE query.
        val query = config(
            deviceId = "dev 1&x",
            platform = "ANDROID OS",
            channel = "Canary",
            bundleVersion = "1.4.1",
        ).checkQuery()
        assertEquals(
            "deviceId=dev+1%26x&platform=android+os&appVersion=2.0.0&bundleVersion=1.4.1&channel=canary",
            query,
        )
    }

    @Test
    fun theOptionalBundleVersionMemberIsAbsentUntilTheDeviceCarriesALabel() {
        assertTrue(!config().checkQuery().contains("bundleVersion"))
        assertTrue(config(bundleVersion = "1.4.1").checkQuery().contains("&bundleVersion=1.4.1&"))
    }

    @Test
    fun theFrozenRoutesDeriveFromTheConfiguredBase() {
        assertEquals("https://shipperdog.example.com/api/v1/ota/check", config().checkUrl)
        assertEquals(
            "https://shipperdog.example.com/api/v1/ota/check",
            config().let { it.copy(baseUrl = it.baseUrl + "/") }.checkUrl,
        )
    }

    @Test
    fun theBoundsMirrorTheCheckSurfacesOwnValidation() {
        assertFailsWith<IllegalArgumentException> { config(deviceId = "") }
        assertFailsWith<IllegalArgumentException> { config(deviceId = "d".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { config(platform = "p".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { config(appVersion = "v".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { config(channel = "c".repeat(65)) }
        assertFailsWith<IllegalArgumentException> { config(bundleVersion = " ") }
        assertFailsWith<IllegalArgumentException> { config(bundleVersion = "b".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { config(pollIntervalSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { config(maxQueueEvents = 0) }
    }

    @Test
    fun theAppTokenMustBeTheSdc1FleetCredential() {
        assertFailsWith<IllegalArgumentException> { config().copy(appToken = "sdt1.wrong-prefix") }
        assertFailsWith<IllegalArgumentException> { config().copy(appToken = "bare-token") }
    }

    @Test
    fun verificationKeysAcceptOnlyTheFrozenShapes() {
        val rawKey = ByteArray(OtaConfig.ED25519_KEY_BYTES) { it.toByte() }
        val keyId = OtaVerifier.keyIdOf(rawKey)
        // The frozen shape passes: 16-hex id, Base64 of 32 raw bytes.
        config(verificationKeys = mapOf(keyId to Base64.getEncoder().encodeToString(rawKey)))
        // A key that is not 32 raw bytes, an id that is not 16 hex.
        assertFailsWith<IllegalArgumentException> {
            config(verificationKeys = mapOf(keyId to Base64.getEncoder().encodeToString(ByteArray(31))))
        }
        assertFailsWith<IllegalArgumentException> {
            config(verificationKeys = mapOf("short-id" to Base64.getEncoder().encodeToString(rawKey)))
        }
    }
}
