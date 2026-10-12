package com.shipperdog.companion.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.shipperdog.companion.core.CompanionConfig
import com.shipperdog.companion.core.InstallLedger
import com.shipperdog.companion.core.PortalClient
import com.shipperdog.companion.core.SessionManager
import com.shipperdog.companion.core.TokenPair
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The companion's process-wide runtime (issue #73, S15-02): the one
 * place the core's collaborators get built and shared — the portal
 * client, the session, and the install ledger — over the app's own
 * persistence (SharedPreferences for the session, filesDir for the
 * ledger). A deliberate singleton for the spike: two activities and
 * a receiver need the same objects, and dependency injection
 * frameworks are exactly the gold-plating the story refuses.
 */
object Runtime {

    /** One install in flight: what the session is installing and where the answer lands. */
    data class PendingInstall(
        val tenantId: String,
        val applicationId: String,
        val versionId: String,
        val versionName: String,
        val telemetryToken: String?,
        val telemetryReleaseId: String?,
        val sessionId: Int,
        val packagesBefore: Set<String>,
    )

    private lateinit var appContext: Context

    /** The one background executor (network + APK staging never block the main thread). */
    val background: ExecutorService = Executors.newSingleThreadExecutor()

    /** The main-thread post (every UI mutation hops through here). */
    val main: Handler = Handler(Looper.getMainLooper())

    lateinit var client: PortalClient
        private set

    lateinit var session: SessionManager
        private set

    lateinit var ledger: InstallLedger
        private set

    /** The install awaiting its PackageInstaller status callback, keyed by session id. */
    val pendingInstalls = mutableMapOf<Int, PendingInstall>()

    /** The catalog screen's refresh hook — the receiver pings it after an install lands. */
    @Volatile
    var onInstallSettled: (() -> Unit)? = null

    private lateinit var preferences: SharedPreferences

    fun init(context: Context) {
        if (::appContext.isInitialized) {
            return
        }
        appContext = context.applicationContext
        preferences = appContext.getSharedPreferences("companion", Context.MODE_PRIVATE)
        val base = preferences.getString("baseUrl", null) ?: DEFAULT_BASE
        rebuild(base)
    }

    /** Rebuilds the collaborators around a (new) server base URL. */
    fun rebuild(baseUrl: String) {
        preferences.edit().putString("baseUrl", baseUrl).apply()
        client = PortalClient.production(CompanionConfig(baseUrl))
        session = SessionManager(client, AndroidTokenStore(preferences))
        ledger = InstallLedger(File(appContext.filesDir, "installs.tsv"))
    }

    /** The configured server base (the login screen's prefilled field). */
    fun configuredBaseUrl(): String = preferences.getString("baseUrl", null) ?: DEFAULT_BASE

    /** The last tester email (the login screen's prefilled field). */
    fun lastEmail(): String? = session.storedTesterEmail()

    /** The application context (the installer's staging and ledger paths). */
    fun context(): Context = appContext

    /** The emulator local stack's default (10.0.2.2 is the host loopback). */
    const val DEFAULT_BASE: String = "http://10.0.2.2:8080"

    /** The SharedPreferences-backed session store. */
    private class AndroidTokenStore(private val preferences: SharedPreferences) : SessionManager.TokenStore {

        override fun load(): TokenPair? {
            val access = preferences.getString("accessToken", null) ?: return null
            val refresh = preferences.getString("refreshToken", null) ?: return null
            val expires = preferences.getLong("expiresInSeconds", 0L)
            return TokenPair(access, refresh, expires)
        }

        override fun save(pair: TokenPair) {
            preferences.edit()
                .putString("accessToken", pair.accessToken)
                .putString("refreshToken", pair.refreshToken)
                .putLong("expiresInSeconds", pair.expiresInSeconds)
                .apply()
        }

        override fun clear() {
            preferences.edit()
                .remove("accessToken")
                .remove("refreshToken")
                .remove("expiresInSeconds")
                .remove("testerEmail")
                .apply()
        }

        override fun saveTesterEmail(email: String) {
            preferences.edit().putString("testerEmail", email).apply()
        }

        override fun loadTesterEmail(): String? = preferences.getString("testerEmail", null)
    }
}
