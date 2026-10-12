package com.shipperdog.companion.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.shipperdog.companion.core.HistoryEntry
import com.shipperdog.companion.core.InstallLedger
import com.shipperdog.companion.core.PortalProblem
import com.shipperdog.companion.core.SessionExpiredException
import com.shipperdog.companion.core.downloadTo
import java.io.File
import java.time.Instant

/**
 * The one-tap install flow (issue #73, S15-02): eligibility ->
 * issuance -> APK download -> a PackageInstaller session -> the
 * status callback that lands the ledger entry. Every step runs on
 * the runtime's background executor; the UI hears the outcome through
 * the activity's dialog and the install-settled hook.
 *
 * The permission flow is the Android 8.0+ design: the manifest's
 * REQUEST_INSTALL_PACKAGES makes the app ELIGIBLE, the tester's
 * per-app "install unknown apps" consent (system settings) makes it
 * ABLE — `canRequestPackageInstalls()` is the check, and a false
 * answer routes the tester to the consent screen with a tap-to-return
 * retry. The system then shows its own install confirmation on
 * commit: the companion never bypasses a consent surface, it removes
 * the browser-and-file-manager friction around the ones Android
 * requires.
 */
class Installer(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null

    /** Installs the newest authorized Android build of one application. */
    fun install(row: com.shipperdog.companion.core.CatalogRow, onSettled: () -> Unit) {
        val card = row.card
        showProgress("Resolving the authorized build...")
        Runtime.background.execute {
            try {
                val history = Runtime.session.withSession { bearer ->
                    Runtime.client.history(card.tenantId, card.applicationId, bearer)
                }
                val entry = history.firstOrNull { it.androidArtifactId != null }
                if (entry == null || entry.androidArtifactId == null) {
                    fail("No installable Android artifact is authorized right now.")
                    return@execute
                }
                stageAndCommit(card.tenantId, card.applicationId, entry, onSettled)
            } catch (expired: SessionExpiredException) {
                fail("Session expired — sign in again.")
            } catch (refused: PortalProblem) {
                fail("Refused: " + refused.code)
            } catch (failure: Exception) {
                fail("Install failed: " + (failure.message ?: "unknown error"))
            }
        }
    }

    private fun stageAndCommit(
        tenantId: String,
        applicationId: String,
        entry: HistoryEntry,
        onSettled: () -> Unit,
    ) {
        val issuance = Runtime.session.withSession { bearer ->
            Runtime.client.issuance(
                entry.androidArtifactId!!,
                entry.versionId,
                tenantId,
                bearer,
            )
        }
        showProgress("Downloading " + (issuance.fileName ?: "the build") + "...")
        val staged = File(Runtime.context().cacheDir, "staged-" + entry.versionId + ".apk")
        val url = Runtime.client.absoluteIssuanceUrl(issuance)
        downloadTo(url, staged)

        // The unknown-apps consent gate, checked BEFORE the session: a
        // tester who has not granted it gets the routing dialog, and the
        // download they already fetched stays staged for the retry.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            main.post { routeToUnknownAppsConsent() }
            return
        }

        showProgress("Handing to the system installer...")
        val installer = activity.packageManager.packageInstaller
        val sessionParams = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        val sessionId = installer.createSession(sessionParams)
        val session = installer.openSession(sessionId)
        session.openWrite("base.apk", 0, staged.length()).use { output ->
            staged.inputStream().use { input -> input.copyTo(output) }
            session.fsync(output)
        }
        // The callback lands in InstallReceiver; the pending facts ride
        // the runtime map (the receiver resolves the ledger entry).
        Runtime.pendingInstalls[sessionId] = Runtime.PendingInstall(
            tenantId = tenantId,
            applicationId = applicationId,
            versionId = entry.versionId,
            versionName = entry.versionName,
            telemetryToken = issuance.telemetry?.token,
            telemetryReleaseId = issuance.telemetry?.releaseId,
            sessionId = sessionId,
            packagesBefore = installedPackageNames(),
        )
        val statusIntent = Intent(Runtime.context(), InstallReceiver::class.java)
            .setAction(InstallReceiver.ACTION_INSTALL_STATUS)
            .setPackage(Runtime.context().packageName)
        val statusSender = android.app.PendingIntent.getBroadcast(
            Runtime.context(),
            sessionId,
            statusIntent,
            android.app.PendingIntent.FLAG_MUTABLE,
        )
        session.commit(statusSender.intentSender)
        session.close()
        staged.delete()
        main.post {
            dismissProgress()
            toast("Confirm the install in the system dialog.")
            onSettled()
        }
    }

    /** The device's installed package names (the commit diff's before-set). */
    private fun installedPackageNames(): Set<String> =
        activity.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()

    private fun routeToUnknownAppsConsent() {
        dismissProgress()
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + Runtime.context().packageName),
        )
        AlertDialog.Builder(activity)
            .setTitle("Allow installs from this app")
            .setMessage(
                "Android requires a one-time consent: allow ShipperDog Companion to install " +
                    "unknown apps, then tap Install again — the download is already staged.",
            )
            .setPositiveButton("Open settings") { _, _ -> activity.startActivity(intent) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showProgress(text: String) {
        main.post {
            dismissProgress()
            dialog = AlertDialog.Builder(activity)
                .setMessage(text)
                .setCancelable(false)
                .show()
        }
    }

    fun dismissProgress() {
        dialog?.dismiss()
        dialog = null
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun fail(text: String) {
        main.post {
            dismissProgress()
            AlertDialog.Builder(activity)
                .setTitle("Install")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    companion object {
        /** The ledger's install timestamp (ISO-8601, the core's own convention). */
        fun nowIso(): String = Instant.now().toString()
    }
}

/** Records the landed install (called by the receiver on STATUS_SUCCESS). */
internal fun recordInstall(pending: Runtime.PendingInstall, packageName: String) {
    Runtime.ledger.record(
        InstallLedger.Entry(
            tenantId = pending.tenantId,
            applicationId = pending.applicationId,
            packageName = packageName,
            versionId = pending.versionId,
            versionName = pending.versionName,
            installedAtIso = Installer.nowIso(),
            telemetryToken = pending.telemetryToken,
            telemetryReleaseId = pending.telemetryReleaseId,
        ),
    )
}
