package com.shipperdog.companion.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.shipperdog.companion.core.CatalogCard
import com.shipperdog.companion.core.CatalogRow
import com.shipperdog.companion.core.InstallState
import com.shipperdog.companion.core.PortalProblem
import com.shipperdog.companion.core.SessionExpiredException
import com.shipperdog.companion.core.deriveRows

/**
 * The catalog screen (issue #73, S15-02): the tester's authorized
 * applications — the S7 decision path's LIVE answer every resume
 * (never a cached list, the story's own criterion) — each row with
 * its honest install state and the one-tap install/update button,
 * plus the feedback deep link into the portal's report flow.
 */
class CatalogActivity : Activity() {

    private lateinit var list: ListView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var adapter: RowAdapter

    private var rows: List<CatalogRow> = emptyList()
    private val installer by lazy { Installer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Runtime.init(this)
        setContentView(buildUi())
        Runtime.onInstallSettled = {
            Runtime.main.post { refresh() }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        installer.dismissProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        Runtime.onInstallSettled = null
    }

    private fun buildUi(): View {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val padding = dp(12)
        header.setPadding(padding, dp(8), padding, dp(8))

        val title = TextView(this)
        title.text = "Authorized builds"
        title.textSize = 18f
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        header.addView(title)

        val signOut = Button(this)
        signOut.text = "Sign out"
        signOut.setOnClickListener { signOut() }
        header.addView(signOut)
        column.addView(header)

        progress = ProgressBar(this)
        progress.setPadding(0, dp(8), 0, dp(8))
        column.addView(progress)

        status = TextView(this)
        status.setPadding(padding, 0, padding, dp(8))
        column.addView(status)

        adapter = RowAdapter()
        list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> rows.getOrNull(position)?.let(::report) }
        column.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return column
    }

    private fun refresh() {
        progress.visibility = View.VISIBLE
        status.text = ""
        Runtime.background.execute {
            try {
                val page = Runtime.session.withSession { bearer -> Runtime.client.catalog(bearer = bearer) }
                val cards = page.cards.toMutableList()
                var cursor = page.nextCursor
                var pages = 1
                while (cursor != null && pages < MAX_CATALOG_PAGES) {
                    val next = Runtime.session.withSession { bearer ->
                        Runtime.client.catalog(cursor = cursor, bearer = bearer)
                    }
                    cards.addAll(next.cards)
                    cursor = next.nextCursor
                    pages++
                }
                val derived = deriveRows(cards, Runtime.ledger) { packageName ->
                    installedVersionNameOf(packageName)
                }
                Runtime.main.post {
                    rows = derived
                    adapter.notifyDataSetChanged()
                    progress.visibility = View.GONE
                    if (rows.isEmpty()) {
                        status.text = "No authorized applications yet."
                    }
                }
            } catch (expired: SessionExpiredException) {
                Runtime.main.post { routeToLogin() }
            } catch (refused: PortalProblem) {
                Runtime.main.post {
                    progress.visibility = View.GONE
                    status.text = "Catalog refused: " + refused.code
                }
            } catch (failure: Exception) {
                Runtime.main.post {
                    progress.visibility = View.GONE
                    status.text = "Network trouble: " + (failure.message ?: "unknown")
                }
            }
        }
    }

    private fun installedVersionNameOf(packageName: String): String? = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (absent: Exception) {
        null
    }

    private fun install(row: CatalogRow) {
        installer.install(row) { refresh() }
    }

    /** The feedback handoff: the portal's version page with ?report=1 (the S11-01 deep-link contract). */
    private fun report(row: CatalogRow) {
        val url = row.card.let { card ->
            val version = Runtime.ledger.find(card.tenantId, card.applicationId)?.versionId
                ?: card.androidVersionId
            if (version != null) {
                com.shipperdog.companion.core.FeedbackLink.forVersion(
                    portalBase(),
                    card.tenantId,
                    card.applicationId,
                    version,
                )
            } else {
                com.shipperdog.companion.core.FeedbackLink.forApplication(
                    portalBase(),
                    card.tenantId,
                    card.applicationId,
                )
            }
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** The portal hosting of the configured API base (same origin in every deployment shape). */
    private fun portalBase(): String = Runtime.configuredBaseUrl()

    private fun signOut() {
        Runtime.background.execute {
            Runtime.session.clear()
            Runtime.main.post { routeToLogin() }
        }
    }

    private fun routeToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** The catalog join walks every page — bounded, so a broken cursor cannot spin the fetch forever. */
        const val MAX_CATALOG_PAGES: Int = 50
    }

    /** One authorized application row: name, state, and the actions the state allows. */
    private inner class RowAdapter : BaseAdapter() {

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): CatalogRow = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = getItem(position)
            val card = row.card
            val padding = dp(12)
            val column = LinearLayout(this@CatalogActivity)
            column.orientation = LinearLayout.VERTICAL
            column.setPadding(padding, padding, padding, padding)

            val name = TextView(this@CatalogActivity)
            name.text = card.applicationName
            name.textSize = 17f
            column.addView(name)

            val facts = TextView(this@CatalogActivity)
            facts.text = factsOf(row)
            facts.setPadding(0, dp(2), 0, 0)
            column.addView(facts)

            val actions = LinearLayout(this@CatalogActivity)
            actions.orientation = LinearLayout.HORIZONTAL
            val installButton = Button(this@CatalogActivity)
            installButton.text = installLabelOf(row.state)
            installButton.isEnabled = row.state != InstallState.NOT_INSTALLABLE
            installButton.setOnClickListener { install(row) }
            actions.addView(installButton)
            val reportButton = Button(this@CatalogActivity)
            reportButton.text = "Report"
            reportButton.setOnClickListener { report(row) }
            actions.addView(reportButton)
            column.addView(actions)
            return column
        }

        private fun factsOf(row: CatalogRow): String = when (row.state) {
            InstallState.NOT_INSTALLABLE -> "No Android build authorized (iOS-only card)"
            InstallState.UNTRACKED ->
                "Android " + row.installVersionName + " available — install through the companion to track updates"
            InstallState.NOT_INSTALLED ->
                "Android " + row.installVersionName + " available (recorded install gone from this device)"
            InstallState.UP_TO_DATE ->
                "Installed " + row.installedVersionName + " — newest authorized Android build"
            InstallState.UPDATE_AVAILABLE ->
                "Installed " + row.installedVersionName + " — Android " + row.installVersionName + " available"
        }

        private fun installLabelOf(state: InstallState): String = when (state) {
            InstallState.UPDATE_AVAILABLE -> "Update"
            else -> "Install"
        }
    }
}
