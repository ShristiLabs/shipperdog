package com.shipperdog.companion.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

/**
 * The install ledger's suite (issue #73, S15-02): the persistent
 * (tenant, application) -> package mapping survives reload, overwrites
 * on re-install, and drops on observed uninstall — the update-state
 * join's whole ground truth.
 */
class InstallLedgerTest {

    private fun newLedger(): Pair<InstallLedger, java.io.File> {
        val dir = createTempDirectory("companion-ledger")
        val file = dir.toFile().resolve("installs.tsv")
        return InstallLedger(file) to file
    }

    @Test
    fun recordsAndFindsEntries() {
        val (ledger, _) = newLedger()
        ledger.record(
            InstallLedger.Entry(
                tenantId = "t1",
                applicationId = "a1",
                packageName = "com.example.fieldops",
                versionId = "v9",
                versionName = "2.1.0",
                installedAtIso = "2026-10-12T00:00:00Z",
                telemetryToken = "sdt1.xyz",
                telemetryReleaseId = "r1",
            ),
        )
        val found = ledger.find("t1", "a1")
        assertEquals("com.example.fieldops", found?.packageName)
        assertEquals("sdt1.xyz", found?.telemetryToken)
        assertEquals(1, ledger.all().size)
    }

    @Test
    fun persistsAcrossReloads() {
        val (ledger, file) = newLedger()
        ledger.record(
            InstallLedger.Entry("t1", "a1", "com.example.fieldops", "v9", "2.1.0", "2026-10-12T00:00:00Z"),
        )
        val reloaded = InstallLedger(file)
        assertEquals("2.1.0", reloaded.find("t1", "a1")?.versionName)
        assertNull(reloaded.find("t1", "a1")?.telemetryToken)
    }

    @Test
    fun reInstallOverwritesTheSameApplication() {
        val (ledger, _) = newLedger()
        ledger.record(InstallLedger.Entry("t1", "a1", "com.example.fieldops", "v8", "2.0.0", "2026-10-01T00:00:00Z"))
        ledger.record(InstallLedger.Entry("t1", "a1", "com.example.fieldops", "v9", "2.1.0", "2026-10-12T00:00:00Z"))
        assertEquals(1, ledger.all().size)
        assertEquals("v9", ledger.find("t1", "a1")?.versionId)
    }

    @Test
    fun observedUninstallRemovesTheEntry() {
        val (ledger, _) = newLedger()
        ledger.record(InstallLedger.Entry("t1", "a1", "com.example.fieldops", "v9", "2.1.0", "2026-10-12T00:00:00Z"))
        ledger.remove("t1", "a1")
        assertNull(ledger.find("t1", "a1"))
        assertTrue(ledger.all().isEmpty())
    }

    @Test
    fun applicationsAreKeyedPerTenant() {
        val (ledger, _) = newLedger()
        ledger.record(InstallLedger.Entry("t1", "a1", "com.example.one", "v1", "1.0.0", "2026-10-12T00:00:00Z"))
        ledger.record(InstallLedger.Entry("t2", "a1", "com.example.two", "v2", "1.0.0", "2026-10-12T00:00:00Z"))
        assertEquals(2, ledger.all().size)
        assertEquals("com.example.two", ledger.find("t2", "a1")?.packageName)
    }

    @Test
    fun theTelemetryStashRoundTripsAcrossReloads() {
        val (ledger, file) = newLedger()
        ledger.record(
            InstallLedger.Entry(
                "t1", "a1", "com.example.fieldops", "v9", "2.1.0", "2026-10-12T00:00:00Z",
                telemetryToken = "sdt1.xyz", telemetryReleaseId = "r1",
            ),
        )
        val reloaded = InstallLedger(file)
        assertEquals("sdt1.xyz", reloaded.find("t1", "a1")?.telemetryToken)
        assertEquals("r1", reloaded.find("t1", "a1")?.telemetryReleaseId)
    }

    @Test
    fun corruptLinesAreSkippedNotFatal() {
        val (ledger, file) = newLedger()
        ledger.record(
            InstallLedger.Entry("t1", "a1", "com.example.fieldops", "v9", "2.1.0", "2026-10-12T00:00:00Z"),
        )
        file.writeText(
            file.readText() +
                "not a ledger line at all\n" +
                "\n" +
                "only\tthree\tfields\n",
        )
        val reloaded = InstallLedger(file)
        assertEquals(1, reloaded.all().size)
        assertEquals("com.example.fieldops", reloaded.find("t1", "a1")?.packageName)
    }
}
