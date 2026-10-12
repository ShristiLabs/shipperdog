package com.shipperdog.companion.core

import java.io.File

/**
 * The companion's install ledger (issue #73, S15-02): the persistent
 * record of WHAT the companion installed, WHERE (the Android package
 * name the session's APK became), and the telemetry pairing block the
 * issuance answered with. It exists because the tester-facing
 * contracts do NOT carry the artifact's `bundleId` (the catalog card
 * and the eligibility slot name the artifact id and build number, but
 * never the Android package name — a platform gap the spike's memo
 * records), so the companion learns the package name at install time
 * and remembers the (tenant, application) -> package mapping itself.
 *
 * File-backed, one properties-shaped line per entry, the
 * EventQueue-of-the-SDK doctrine: plain text, human-readable, written
 * atomically-enough for a single-writer app (the companion is the
 * only writer; a torn write costs one re-install, never a wrong
 * install — the install itself is the system installer's act).
 */
class InstallLedger(private val file: File) {

    /** One recorded install: what the companion installed and where. */
    data class Entry(
        val tenantId: String,
        val applicationId: String,
        val packageName: String,
        val versionId: String,
        val versionName: String,
        val installedAtIso: String,
        val telemetryToken: String? = null,
        val telemetryReleaseId: String? = null,
    )

    private val entries: MutableMap<String, Entry> = load()

    /** Records (or overwrites) the mapping for one application. */
    @Synchronized
    fun record(entry: Entry) {
        entries[key(entry.tenantId, entry.applicationId)] = entry
        persist()
    }

    /** The recorded entry for one application, if the companion installed it. */
    @Synchronized
    fun find(tenantId: String, applicationId: String): Entry? = entries[key(tenantId, applicationId)]

    /** All recorded entries (the catalog join's other side). */
    @Synchronized
    fun all(): List<Entry> = entries.values.toList()

    /** Drops one entry (an observed uninstall — the UNINSTALL-emitter path the memo documents). */
    @Synchronized
    fun remove(tenantId: String, applicationId: String) {
        entries.remove(key(tenantId, applicationId))
        persist()
    }

    private fun key(tenantId: String, applicationId: String): String = "$tenantId/$applicationId"

    private fun load(): MutableMap<String, Entry> {
        val loaded = LinkedHashMap<String, Entry>()
        if (!file.exists()) {
            return loaded
        }
        file.readLines().forEach { line ->
            if (line.isBlank()) {
                return@forEach
            }
            val fields = line.split('\t')
            if (fields.size < 6) {
                return@forEach
            }
            val entry = Entry(
                tenantId = fields[0],
                applicationId = fields[1],
                packageName = fields[2],
                versionId = fields[3],
                versionName = fields[4],
                installedAtIso = fields[5],
                telemetryToken = fields.getOrNull(6)?.takeIf { it.isNotBlank() },
                telemetryReleaseId = fields.getOrNull(7)?.takeIf { it.isNotBlank() },
            )
            loaded[key(entry.tenantId, entry.applicationId)] = entry
        }
        return loaded
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val text = entries.values.joinToString("\n") { entry ->
            listOf(
                entry.tenantId,
                entry.applicationId,
                entry.packageName,
                entry.versionId,
                entry.versionName,
                entry.installedAtIso,
                entry.telemetryToken ?: "",
                entry.telemetryReleaseId ?: "",
            ).joinToString("\t")
        }
        file.writeText(if (text.isEmpty()) "" else text + "\n")
    }
}
