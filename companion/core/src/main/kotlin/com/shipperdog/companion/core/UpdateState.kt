package com.shipperdog.companion.core

/**
 * The catalog row's honest state (issue #73, S15-02's "update
 * available" criterion): what the tester sees per application is
 * derived, never guessed — the catalog's authorized Android version
 * (the S7 decision path's live answer) joined against the ledger's
 * recorded install and the package manager's on-device facts.
 *
 * UNTRACKED is the honest third state: the platform's tester-facing
 * contracts do not carry the artifact's bundleId (the memo's gap
 * finding), so an app installed through the PORTAL (browser +
 * file manager) cannot be matched to its catalog card until the
 * companion installs it once — productionization closes this by
 * adding bundleId to the eligibility slot.
 */
enum class InstallState {
    /** No Android install path for this card (no authorized ANDROID version). */
    NOT_INSTALLABLE,

    /** The companion has no record: never installed through it, or installed via the portal. */
    UNTRACKED,

    /** The recorded package is gone from the device (uninstalled) — install again to re-track. */
    NOT_INSTALLED,

    /** Installed, and the installed version IS the newest authorized one. */
    UP_TO_DATE,

    /** Installed, and a strictly newer authorized version exists — the update badge. */
    UPDATE_AVAILABLE,
}

/** One catalog row's derived state: the card, the ledger join, and what to render. */
data class CatalogRow(
    val card: CatalogCard,
    val state: InstallState,
    /** The authorized Android version the install button targets (null when NOT_INSTALLABLE). */
    val installVersionName: String?,
    /** The installed version name when tracked (the package manager's own fact). */
    val installedVersionName: String?,
)

/**
 * Derives one catalog row's state. [installedVersionNameOf] answers
 * the on-device version name for a package (null = not installed) —
 * the package-manager seam the app supplies; the JVM suite drives it
 * with a map.
 */
fun deriveRow(
    card: CatalogCard,
    ledger: InstallLedger,
    installedVersionNameOf: (packageName: String) -> String?,
): CatalogRow {
    val authorized = card.androidVersionName
    if (authorized == null || card.androidVersionId == null) {
        return CatalogRow(card, InstallState.NOT_INSTALLABLE, null, null)
    }
    val entry = ledger.find(card.tenantId, card.applicationId)
        ?: return CatalogRow(card, InstallState.UNTRACKED, authorized, null)
    val installed = installedVersionNameOf(entry.packageName)
        ?: return CatalogRow(card, InstallState.NOT_INSTALLED, authorized, null)
    val state = if (VersionOrder.isNewer(authorized, installed)) {
        InstallState.UPDATE_AVAILABLE
    } else {
        InstallState.UP_TO_DATE
    }
    return CatalogRow(card, state, authorized, installed)
}

/** Derives the whole catalog (the catalog screen's model, newest-first server order preserved). */
fun deriveRows(
    cards: List<CatalogCard>,
    ledger: InstallLedger,
    installedVersionNameOf: (packageName: String) -> String?,
): List<CatalogRow> = cards.map { deriveRow(it, ledger, installedVersionNameOf) }
