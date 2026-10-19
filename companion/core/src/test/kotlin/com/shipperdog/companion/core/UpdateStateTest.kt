package com.shipperdog.companion.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.io.path.createTempDirectory

/**
 * The catalog row's derived state (issue #73, S15-02): all the honest
 * states — not installable, untracked (the bundleId gap's cost),
 * uninstalled, up to date, the update badge, and the OTA channel
 * (S16-16: a bundle-backed newest build never misreports an update) —
 * derived from the same join the catalog screen renders.
 */
class UpdateStateTest {

    private val ledger = InstallLedger(createTempDirectory("companion-state").toFile().resolve("installs.tsv"))

    private fun card(androidVersion: String? = "2.1.0", androidIsOta: Boolean = false): CatalogCard = CatalogCard(
        tenantId = "t1",
        applicationId = "a1",
        applicationName = "Field Ops",
        latestVersionId = if (androidVersion == null) null else "v9",
        latestVersionName = androidVersion,
        androidVersionId = if (androidVersion == null) null else "v9",
        androidVersionName = androidVersion,
        androidIsOta = androidIsOta,
    )

    @Test
    fun noAndroidSlotIsNotInstallable() {
        val row = deriveRow(card(androidVersion = null), ledger) { null }
        assertEquals(InstallState.NOT_INSTALLABLE, row.state)
        assertNull(row.installVersionName)
    }

    @Test
    fun noLedgerEntryIsUntracked() {
        val row = deriveRow(card(), ledger) { null }
        assertEquals(InstallState.UNTRACKED, row.state)
        assertEquals("2.1.0", row.installVersionName)
        assertNull(row.installedVersionName)
    }

    @Test
    fun recordedButGoneIsNotInstalled() {
        ledger.record(InstallLedger.Entry("t1", "a1", "com.example.fieldops", "v8", "2.0.0", "2026-10-01T00:00:00Z"))
        val row = deriveRow(card(), ledger) { packageName -> null }
        assertEquals(InstallState.NOT_INSTALLED, row.state)
    }

    @Test
    fun matchingVersionIsUpToDate() {
        ledger.record(InstallLedger.Entry("t1", "a1", "com.example.fieldops", "v9", "2.1.0", "2026-10-12T00:00:00Z"))
        val row = deriveRow(card(), ledger) { "2.1.0" }
        assertEquals(InstallState.UP_TO_DATE, row.state)
        assertEquals("2.1.0", row.installedVersionName)
    }

    @Test
    fun newerAuthorizedVersionIsTheUpdateBadge() {
        ledger.record(InstallLedger.Entry("t1", "a1", "com.example.fieldops", "v8", "2.0.0", "2026-10-01T00:00:00Z"))
        val row = deriveRow(card(), ledger) { "2.0.0" }
        assertEquals(InstallState.UPDATE_AVAILABLE, row.state)
        assertEquals("2.1.0", row.installVersionName)
        assertEquals("2.0.0", row.installedVersionName)
    }

    @Test
    fun anOtaChannelCardNeverMisreportsAnUpdateFromABundleLabel() {
        // S16-16: the newest authorized ANDROID version is the OTA
        // bundle's own row — a tracked, OLDER store install must not
        // turn the bundle label into an update badge the companion
        // cannot act on (no updater in v1, D11).
        ledger.record(InstallLedger.Entry("t1", "a1", "com.example.fieldops", "v8", "2.0.0", "2026-10-01T00:00:00Z"))
        val row = deriveRow(card(androidIsOta = true), ledger) { "2.0.0" }
        assertEquals(InstallState.OTA_CHANNEL, row.state)
        assertNull(row.installVersionName)
        assertNull(row.installedVersionName)
    }

    @Test
    fun anOtaChannelCardIsHonestEvenUntracked() {
        val row = deriveRow(card(androidIsOta = true), ledger) { null }
        assertEquals(InstallState.OTA_CHANNEL, row.state)
    }

    @Test
    fun theWholeCatalogDerivesTogether() {
        ledger.record(InstallLedger.Entry("t1", "a1", "com.example.fieldops", "v9", "2.1.0", "2026-10-12T00:00:00Z"))
        val otherCard = CatalogCard(
            tenantId = "t2",
            applicationId = "a2",
            applicationName = "Warehouse",
            latestVersionId = "v5",
            latestVersionName = "1.0.0",
            androidVersionId = "v5",
            androidVersionName = "1.0.0",
            androidIsOta = false,
        )
        val rows = deriveRows(listOf(card(), otherCard), ledger) { packageName ->
            if (packageName == "com.example.fieldops") "2.1.0" else null
        }
        assertEquals(InstallState.UP_TO_DATE, rows[0].state)
        assertEquals(InstallState.UNTRACKED, rows[1].state)
    }
}
