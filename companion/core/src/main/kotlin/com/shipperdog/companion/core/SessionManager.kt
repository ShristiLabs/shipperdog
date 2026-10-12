package com.shipperdog.companion.core

import java.time.Clock
import java.time.Instant

/**
 * The companion's session state machine (issue #73, S15-02): the
 * portal session machinery reused verbatim — login answers the token
 * pair, the access token rides every API call, and a 401 mid-flight
 * triggers exactly ONE refresh-and-retry before the tester is routed
 * back to login (the portal client's `ensureFreshTokens` semantics,
 * one-shot so a dead refresh token never loops). The token pair
 * persists through [TokenStore] — the app supplies the
 * SharedPreferences-backed store, the JVM suite an in-memory one.
 */
class SessionManager(
    private val client: PortalClient,
    private val store: TokenStore,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Where the session pair persists (the app: SharedPreferences; the
     * suite: memory). The tester email is the login screen's own
     * prefill convenience — the token pair is the only credential.
     */
    interface TokenStore {
        fun load(): TokenPair?

        fun save(pair: TokenPair)

        fun clear()

        fun saveTesterEmail(email: String)

        fun loadTesterEmail(): String?
    }

    /** The login screen's prefill (the last successful login's email). */
    fun storedTesterEmail(): String? = store.loadTesterEmail()

    private var current: TokenPair? = store.load()

    /** True when a persisted session exists (the app goes straight to the catalog). */
    fun hasSession(): Boolean = current != null

    /** The access token's honest expiry — the app refreshes eagerly at resume when it has passed. */
    fun accessExpiresAt(): Instant? = current?.let { clock.instant().plusSeconds(it.expiresInSeconds) }

    /** Logs the tester in; persists the pair (and the email for the next prefill). */
    @Synchronized
    fun login(email: String, password: String): TokenPair {
        val pair = client.login(email, password)
        current = pair
        store.save(pair)
        store.saveTesterEmail(email)
        return pair
    }

    /** Refreshes eagerly (resume-time); false when the tester must log in again. */
    @Synchronized
    fun refreshNow(): Boolean {
        val pair = current ?: return false
        val fresh = try {
            client.refresh(pair.refreshToken)
        } catch (refused: PortalProblem) {
            return false
        }
        current = fresh
        store.save(fresh)
        return true
    }

    /**
     * Runs one API call with the session's access token, retrying
     * exactly once through refresh on a 401 — the one-retry policy
     * that keeps a dead session from looping. Throws
     * [SessionExpiredException] when even the refreshed call refuses
     * auth (the app routes to login, clearing the store).
     */
    @Synchronized
    fun <T> withSession(call: (bearer: String) -> T): T {
        val pair = current ?: throw SessionExpiredException("no session")
        return try {
            call(pair.accessToken)
        } catch (expired: PortalProblem) {
            if (expired.code != "HTTP_401") {
                throw expired
            }
            val refreshed = try {
                client.refresh(pair.refreshToken)
            } catch (refused: PortalProblem) {
                clear()
                throw SessionExpiredException("refresh refused")
            }
            current = refreshed
            store.save(refreshed)
            try {
                call(refreshed.accessToken)
            } catch (stillRefused: PortalProblem) {
                if (stillRefused.code == "HTTP_401") {
                    clear()
                    throw SessionExpiredException("access refused after refresh")
                }
                throw stillRefused
            }
        }
    }

    /** Drops the session (tester-initiated sign-out or server-side death). */
    @Synchronized
    fun clear() {
        current = null
        store.clear()
    }
}

/** The session is gone; the app routes to the login screen. */
class SessionExpiredException(message: String) : RuntimeException(message)

/** The in-memory store of the JVM suite. */
class MemoryTokenStore : SessionManager.TokenStore {
    private var pair: TokenPair? = null
    private var email: String? = null

    override fun load(): TokenPair? = pair

    override fun save(pair: TokenPair) {
        this.pair = pair
    }

    override fun clear() {
        pair = null
        email = null
    }

    override fun saveTesterEmail(value: String) {
        email = value
    }

    override fun loadTesterEmail(): String? = email
}
