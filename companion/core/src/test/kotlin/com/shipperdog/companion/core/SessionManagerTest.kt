package com.shipperdog.companion.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session state machine's suite (issue #73, S15-02): the portal
 * session machinery's exact semantics — one refresh-and-retry on a
 * mid-flight 401, session death when the refresh itself refuses, and
 * the persisted-session resume the app opens straight to the catalog
 * with.
 */
class SessionManagerTest {

    /** A transport that answers the queued responses in order (the sequence IS the scenario). */
    private class FakeHttp(vararg queued: CompanionHttp.Response) : CompanionHttp {
        private val pending = queued.toMutableList()
        val requests = mutableListOf<CompanionHttp.Request>()

        override fun exchange(request: CompanionHttp.Request): CompanionHttp.Response {
            requests.add(request)
            return pending.removeFirstOrNull() ?: CompanionHttp.Response(500, ByteArray(0))
        }
    }

    private fun pair(access: String, refresh: String) =
        CompanionHttp.Response(
            200,
            """{"accessToken":"$access","refreshToken":"$refresh","tokenType":"Bearer","expiresInSeconds":900}"""
                .toByteArray(),
        )

    private val config = CompanionConfig("https://shipperdog.example.com")

    @Test
    fun loginPersistsThePairAndPrefill() {
        val http = FakeHttp(
            pair("at", "rt"),
        )
        val store = MemoryTokenStore()
        val manager = SessionManager(PortalClient(config, http), store)
        manager.login("t@shipperdog.local", "secret")
        assertTrue(manager.hasSession())
        assertEquals("t@shipperdog.local", manager.storedTesterEmail())
        assertEquals("rt", store.load()?.refreshToken)
    }

    @Test
    fun aPersistedSessionResumes() {
        val store = MemoryTokenStore()
        store.save(TokenPair("at", "rt", 900))
        val manager = SessionManager(PortalClient(config, FakeHttp()), store)
        assertTrue(manager.hasSession())
    }

    @Test
    fun aMidFlight401RefreshesOnceAndRetries() {
        val catalogUrl = "https://shipperdog.example.com/api/v1/authorization/decisions/applications?limit=20"
        val http = FakeHttp(
            CompanionHttp.Response(401, ByteArray(0)),
            pair("at2", "rt2"),
            CompanionHttp.Response(200, """{"items":[]}""".toByteArray()),
        )
        val store = MemoryTokenStore()
        store.save(TokenPair("at", "rt", 900))
        val client = PortalClient(config, http)
        val resumed = SessionManager(client, store)
        val page = resumed.withSession { bearer -> client.catalog(bearer = bearer) }
        assertEquals(0, page.cards.size)
        assertEquals("rt2", store.load()?.refreshToken)
    }

    @Test
    fun aDeadRefreshTokenEndsTheSession() {
        val http = FakeHttp(
            CompanionHttp.Response(401, ByteArray(0)),
            CompanionHttp.Response(401, ByteArray(0)),
        )
        val store = MemoryTokenStore()
        store.save(TokenPair("at", "rt", 900))
        val client = PortalClient(config, http)
        val manager = SessionManager(client, store)
        assertFailsWith<SessionExpiredException> {
            manager.withSession { bearer -> client.catalog(bearer = bearer) }
        }
        assertFalse(manager.hasSession())
        assertNull(store.load())
    }

    @Test
    fun aSecond401AfterRefreshAlsoEndsTheSession() {
        val http = FakeHttp(
            CompanionHttp.Response(401, ByteArray(0)),
            pair("at2", "rt2"),
            CompanionHttp.Response(401, ByteArray(0)),
        )
        val store = MemoryTokenStore()
        store.save(TokenPair("at", "rt", 900))
        val client = PortalClient(config, http)
        val manager = SessionManager(client, store)
        assertFailsWith<SessionExpiredException> {
            manager.withSession { bearer -> client.catalog(bearer = bearer) }
        }
        assertFalse(manager.hasSession())
    }

    @Test
    fun nonAuthRefusalsPassThroughUntouched() {
        val http = FakeHttp(
            CompanionHttp.Response(404, """{"code":"RESOURCE_NOT_FOUND"}""".toByteArray()),
        )
        val store = MemoryTokenStore()
        store.save(TokenPair("at", "rt", 900))
        val client = PortalClient(config, http)
        val manager = SessionManager(client, store)
        val refused = assertFailsWith<PortalProblem> {
            manager.withSession { bearer -> client.catalog(bearer = bearer) }
        }
        assertEquals("RESOURCE_NOT_FOUND", refused.code)
        assertTrue(manager.hasSession(), "a 404 is not session death")
    }

    @Test
    fun eagerRefreshReportsHonestFailure() {
        val http = FakeHttp(
            pair("at2", "rt2"),
        )
        val store = MemoryTokenStore()
        store.save(TokenPair("at", "rt", 900))
        val manager = SessionManager(PortalClient(config, http), store)
        assertTrue(manager.refreshNow())
        assertEquals("at2", store.load()?.accessToken)

        val dead = FakeHttp(
            CompanionHttp.Response(401, ByteArray(0)),
        )
        val dying = SessionManager(PortalClient(config, dead), store)
        assertFalse(dying.refreshNow())
    }

    @Test
    fun noSessionAtAllRefusesLoudly() {
        val manager = SessionManager(PortalClient(config, FakeHttp()), MemoryTokenStore())
        assertFailsWith<SessionExpiredException> {
            manager.withSession { _ -> "unreachable" }
        }
        assertFalse(manager.hasSession())
    }

    @Test
    fun aNonAuthFailureAfterTheRefreshPassesThroughAndKeepsTheSession() {
        val http = FakeHttp(
            CompanionHttp.Response(401, ByteArray(0)),
            pair("at2", "rt2"),
            CompanionHttp.Response(503, ByteArray(0)),
        )
        val store = MemoryTokenStore()
        store.save(TokenPair("at", "rt", 900))
        val client = PortalClient(config, http)
        val manager = SessionManager(client, store)
        val refused = assertFailsWith<PortalProblem> {
            manager.withSession { bearer -> client.catalog(bearer = bearer) }
        }
        assertEquals("HTTP_503", refused.code)
        assertTrue(manager.hasSession(), "a 503 after a refresh is not session death")
        assertEquals("rt2", store.load()?.refreshToken)
    }

    @Test
    fun accessExpiryIsAnsweredHonestly() {
        val store = MemoryTokenStore()
        store.save(TokenPair("at", "rt", 900))
        val noon = java.time.Instant.parse("2026-10-12T12:00:00Z")
        val manager = SessionManager(
            PortalClient(config, FakeHttp()),
            store,
            java.time.Clock.fixed(noon, java.time.ZoneOffset.UTC),
        )
        assertEquals(noon.plusSeconds(900), manager.accessExpiresAt())
    }
}
