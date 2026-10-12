package com.shipperdog.companion.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The feedback handoff's suite (issue #73, S15-02): the portal's own
 * `?report=1` deep-link contract, version-addressed when the ledger
 * knows the version, application-addressed when it does not — and the
 * hint cap the telemetry SDK established (a URL parameter, not a log
 * shipper).
 */
class FeedbackLinkTest {

    @Test
    fun theVersionLinkCarriesTheReportTrigger() {
        assertEquals(
            "https://portal.example.com/applications/t1/a1/versions/v9?report=1",
            FeedbackLink.forVersion("https://portal.example.com/", "t1", "a1", "v9"),
        )
    }

    @Test
    fun theApplicationLinkIsTheUntrackedFallback() {
        assertEquals(
            "https://portal.example.com/applications/t1/a1?report=1",
            FeedbackLink.forApplication("https://portal.example.com", "t1", "a1"),
        )
    }

    @Test
    fun hintsArePrefilledCappedAndWhitespaceCollapsed() {
        val url = FeedbackLink.forVersion(
            "https://portal.example.com",
            "t1",
            "a1",
            "v9",
            hint = "crashed at boot\n\nwith IllegalStateException\tat onCreate",
        )
        assertEquals(
            "https://portal.example.com/applications/t1/a1/versions/v9" +
                "?report=1&hint=crashed+at+boot+with+IllegalStateException+at+onCreate",
            url,
        )
    }

    @Test
    fun theHintCapsAtTheSdksOwnBudget() {
        val long = "x".repeat(FeedbackLink.MAX_HINT_CHARS + 100)
        val url = FeedbackLink.forVersion("https://portal.example.com", "t1", "a1", "v9", hint = long)
        val hint = url.substringAfter("&hint=").decodePercent()
        assertEquals(FeedbackLink.MAX_HINT_CHARS, hint.length)
    }

    private fun String.decodePercent(): String =
        java.net.URLDecoder.decode(this, Charsets.UTF_8)
}
