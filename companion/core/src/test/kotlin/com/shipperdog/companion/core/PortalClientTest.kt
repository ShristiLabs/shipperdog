package com.shipperdog.companion.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The portal client's suite (issue #73, S15-02): every route's wire
 * shape against a fake transport, the problem envelope's stable-code
 * extraction (an opaque 404 stays opaque — the companion surfaces
 * the code verbatim, never a guess), and the relative-issuance-URL
 * resolution the emulator's same-origin proxy shape needs.
 */
class PortalClientTest {

    private class FakeHttp : CompanionHttp {
        var answer: CompanionHttp.Response = CompanionHttp.Response(200, "{}".toByteArray())
        val requests = mutableListOf<CompanionHttp.Request>()

        override fun exchange(request: CompanionHttp.Request): CompanionHttp.Response {
            requests.add(request)
            return answer
        }
    }

    private val http = FakeHttp()
    private val client = PortalClient(CompanionConfig("https://shipperdog.example.com"), http)

    @Test
    fun loginPostsTheSessionShape() {
        http.answer = CompanionHttp.Response(
            200,
            """{"accessToken":"at","refreshToken":"rt","tokenType":"Bearer","expiresInSeconds":900}"""
                .toByteArray(),
        )
        val pair = client.login("t@shipperdog.local", "secret")
        assertEquals("at", pair.accessToken)
        assertEquals("rt", pair.refreshToken)
        assertEquals(900L, pair.expiresInSeconds)
        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://shipperdog.example.com/api/v1/auth/login", request.url)
        assertEquals("""{"email":"t@shipperdog.local","password":"secret"}""", request.jsonBody)
        assertNull(request.bearerToken)
    }

    @Test
    fun refreshPostsTheRefreshToken() {
        http.answer = CompanionHttp.Response(
            200,
            """{"accessToken":"at2","refreshToken":"rt2","tokenType":"Bearer","expiresInSeconds":900}"""
                .toByteArray(),
        )
        client.refresh("rt")
        val request = http.requests.single()
        assertEquals("https://shipperdog.example.com/api/v1/auth/refresh", request.url)
        assertEquals("""{"refreshToken":"rt"}""", request.jsonBody)
    }

    @Test
    fun catalogCarriesTheBearerAndPages() {
        http.answer = CompanionHttp.Response(
            200,
            """
            {"effectiveAccess":true,"items":[
              {"tenantId":"t1","application":{"applicationId":"a1","name":"Field Ops","status":"ACTIVE"},
               "latestAuthorizedVersion":{"versionId":"v9","versionName":"2.1.0","status":"ACTIVE"},
               "platforms":[{"platform":"ANDROID","tenantId":"t1","versionId":"v9","versionName":"2.1.0"}]}
            ],"next_cursor":"c2"}
            """.trimIndent().toByteArray(),
        )
        val page = client.catalog(bearer = "at")
        val request = http.requests.single()
        assertEquals(
            "https://shipperdog.example.com/api/v1/authorization/decisions/applications?limit=20",
            request.url,
        )
        assertEquals("Bearer at", "Bearer " + request.bearerToken)
        assertEquals(1, page.cards.size)
        val card = page.cards[0]
        assertEquals("t1", card.tenantId)
        assertEquals("a1", card.applicationId)
        assertEquals("Field Ops", card.applicationName)
        assertEquals("v9", card.latestVersionId)
        assertEquals("2.1.0", card.androidVersionName)
        assertEquals("c2", page.nextCursor)

        client.catalog(cursor = "c2", bearer = "at")
        assertTrue(
            http.requests[1].url.endsWith("cursor=c2"),
            "the cursor pages: " + http.requests[1].url,
        )
    }

    @Test
    fun historyParsesTheEligibilitySlots() {
        http.answer = CompanionHttp.Response(
            200,
            """
            {"tenantId":"t1","applicationId":"a1","effectiveAccess":true,"resolvedAt":"2026-10-12T00:00:00Z",
             "entries":[
              {"versionId":"v9","versionName":"2.1.0","status":"ACTIVE","expiresAt":null,
               "platforms":[{"platform":"ANDROID","artifactId":"art9","buildNumber":21,"sizeBytes":2048,
                             "uploadedAt":"2026-10-01T00:00:00Z"}]},
              {"versionId":"v8","versionName":"2.0.0","status":"ACTIVE","expiresAt":null,
               "platforms":[{"platform":"IOS","artifactId":"art8","buildNumber":20,"sizeBytes":4096,
                             "uploadedAt":"2026-09-01T00:00:00Z"}]}
            ]}
            """.trimIndent().toByteArray(),
        )
        val entries = client.history("t1", "a1", bearer = "at")
        assertEquals(2, entries.size)
        assertEquals("art9", entries[0].androidArtifactId)
        assertEquals(21L, entries[0].androidBuildNumber)
        assertNull(entries[1].androidArtifactId)
        assertTrue(
            http.requests.single().url.contains("tenantId=t1&applicationId=a1"),
            "the eligibility addressing: " + http.requests.single().url,
        )
    }

    @Test
    fun issuanceParsesTheTelemetryBlock() {
        http.answer = CompanionHttp.Response(
            200,
            """
            {"artifactId":"art9","versionId":"v9","platform":"ANDROID","channel":"DIRECT",
             "behavior":"OPEN_SIGNED_URL","url":"/api/v1/downloads/dlt1.abc","expiresAt":"2026-10-12T01:00:00Z",
             "fileName":"field-ops-2.1.0.apk","sizeBytes":2048,
             "telemetry":{"token":"sdt1.xyz","expiresAt":"2027-01-10T00:00:00Z","tenantId":"t1",
                          "applicationId":"a1","releaseId":"r1","versionId":"v9","deviceId":null}}
            """.trimIndent().toByteArray(),
        )
        val issuance = client.issuance("art9", "v9", "t1", bearer = "at")
        assertEquals("art9", issuance.artifactId)
        assertEquals("field-ops-2.1.0.apk", issuance.fileName)
        assertEquals(2048L, issuance.sizeBytes)
        assertEquals("sdt1.xyz", issuance.telemetry?.token)
        assertEquals("r1", issuance.telemetry?.releaseId)
        assertTrue(
            http.requests.single().url.endsWith("/api/v1/artifacts/art9/download?version_id=v9&tenant_id=t1"),
            "the issuance addressing: " + http.requests.single().url,
        )
    }

    @Test
    fun relativeIssuanceUrlsResolveAgainstTheConfiguredBase() {
        val relative = DownloadIssuance("a", "v", "/api/v1/downloads/dlt1.abc", null, null, 0, null)
        assertEquals(
            "https://shipperdog.example.com/api/v1/downloads/dlt1.abc",
            client.absoluteIssuanceUrl(relative),
        )
        val absolute = DownloadIssuance(
            "a", "v", "https://storage.googleapis.com/bucket/x.apk?sig=1", null, null, 0, null,
        )
        assertEquals(absolute.url, client.absoluteIssuanceUrl(absolute))
    }

    @Test
    fun refusalsCarryTheProblemEnvelopeCode() {
        http.answer = CompanionHttp.Response(
            404,
            """{"code":"RESOURCE_NOT_FOUND","message":"no hint which check failed"}""".toByteArray(),
        )
        val refused = assertFailsWith<PortalProblem> { client.catalog(bearer = "at") }
        assertEquals("RESOURCE_NOT_FOUND", refused.code)
    }

    @Test
    fun refusalsWithoutAnEnvelopeFallBackToTheStatus() {
        http.answer = CompanionHttp.Response(503, "gateway".toByteArray())
        val refused = assertFailsWith<PortalProblem> { client.history("t1", "a1", bearer = "at") }
        assertEquals("HTTP_503", refused.code)
    }

    @Test
    fun malformedAnswersRefuseLoudly() {
        http.answer = CompanionHttp.Response(200, "{\"items\":[{\"broken\":true}".toByteArray())
        assertFailsWith<Json.JsonException> { client.catalog(bearer = "at") }
        http.answer = CompanionHttp.Response(200, """{"items":[]}""".toByteArray())
        assertEquals(0, client.catalog(bearer = "at").cards.size)
    }

    @Test
    fun answersMissingRequiredMembersRefuseLoudly() {
        http.answer = CompanionHttp.Response(200, """{"accessToken":"at"}""".toByteArray())
        assertFailsWith<MalformedAnswer> { client.login("t", "s") }
        http.answer = CompanionHttp.Response(
            200,
            """{"entries":[{"versionName":"1.0.0"}]}""".toByteArray(),
        )
        assertFailsWith<MalformedAnswer> { client.history("t1", "a1", bearer = "at") }
        http.answer = CompanionHttp.Response(
            200,
            """{"artifactId":"a","versionId":"v"}""".toByteArray(),
        )
        assertFailsWith<MalformedAnswer> { client.issuance("a", "v", "t1", bearer = "at") }
    }

    @Test
    fun nonObjectRootsRefuseLoudly() {
        http.answer = CompanionHttp.Response(200, "[1,2]".toByteArray())
        assertFailsWith<MalformedAnswer> { client.catalog(bearer = "at") }
        http.answer = CompanionHttp.Response(200, "\"just a string\"".toByteArray())
        assertFailsWith<MalformedAnswer> { client.login("t", "s") }
    }
}
