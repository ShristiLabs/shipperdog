package com.shipperdog.ota

import java.io.File

/**
 * The app-supplied apply callback's declared outcome (issue #174,
 * S16-12): [Applied] reports OTA_APPLIED SUCCEEDED; [Refused]
 * reports OTA_APPLIED FAILED — the app's considered refusal (a
 * low-battery deferral, a consent gate), distinct from a THROWN
 * exception, which reports OTA_FAILED apply_error (an attempted
 * apply that broke).
 */
sealed class ApplyResult {
    object Applied : ApplyResult()

    object Refused : ApplyResult()
}

/**
 * The app-supplied apply callback (issue #174, S16-12; ADR-0010):
 * the SDK verifies and hands over the staged bundle — swapping it
 * in, hot-loading it, or staging it for the next launch is the
 * HOST's own act, because only the app knows its bundle format and
 * its apply mechanics. The callback runs AFTER verification, never
 * before (the acceptance criterion's own ordering), on the thread
 * the host called [OtaUpdater.updateNow] from.
 *
 * The post-apply rollback verdict — the app detecting a crash loop
 * and reverting — is reported separately through
 * [OtaUpdater.reportRolledBack].
 */
fun interface BundleApplier {

    /** Applies the VERIFIED bundle [bundle] the [manifest] described. */
    fun apply(manifest: OtaManifest, bundle: File): ApplyResult
}
