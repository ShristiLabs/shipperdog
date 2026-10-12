package com.shipperdog.companion.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * The PackageInstaller status callback (issue #73, S15-02): the
 * commit's PendingIntent lands here. On success the receiver resolves
 * WHICH package the session installed — a fresh install appears in
 * the before/after diff of the device's package set, and an UPDATE
 * replaces a package the ledger already recorded — then records the
 * ledger entry (with the issuance's telemetry token stashed for the
 * UNINSTALL-emitter path the memo documents) and pings the catalog's
 * refresh hook. Failures surface their status message verbatim: the
 * system installer's own words, never a guess.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) {
            return
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val pending = Runtime.pendingInstalls.remove(sessionId) ?: return
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                val after = context.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
                val fresh = (after - pending.packagesBefore).singleOrNull()
                val updated = Runtime.ledger.find(pending.tenantId, pending.applicationId)?.packageName
                val packageName = fresh ?: updated
                if (packageName != null) {
                    recordInstall(pending, packageName)
                }
                Runtime.onInstallSettled?.invoke()
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Runtime.onInstallSettled?.invoke()
                android.widget.Toast.makeText(
                    context,
                    "Install did not complete: " + (message ?: ("status " + status)),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS: String = "com.shipperdog.companion.INSTALL_STATUS"
    }
}
