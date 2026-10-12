package com.shipperdog.telemetry

import java.nio.file.Files
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The crash chain (issue #72, S15-01, AC5): the installed handler
 * writes the durable marker AND delegates to the handler installed
 * before it — the no-swallow contract the acceptance criterion pins
 * (the customer's own crash reporting keeps every crash).
 */
class CrashHandlerTest {

    @Test
    fun chainsThePreviousHandlerAndRecordsTheMarker() {
        val directory = Files.createTempDirectory("sd-crash").toFile()
        val sdk = ShipperDog.forTesting(
            TelemetryConfig(
                baseUrl = "https://shipperdog.example.com",
                token = "sdt1.fixture",
                tenantId = "11111111-1111-1111-1111-111111111111",
                applicationId = "22222222-2222-2222-2222-222222222222",
                releaseId = "33333333-3333-3333-3333-333333333333",
                queueDirectory = directory,
            ),
            HttpTransport { _, _, _ -> 200 },
            Clock.systemUTC(),
        )
        val delegated = ArrayDeque<Pair<Thread, Throwable>>()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable -> delegated.addLast(thread to throwable) }
        try {
            CrashHandler.install(sdk)
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            val thread = Thread.currentThread()
            val boom = RuntimeException("the fixture crash")
            handler.uncaughtException(thread, boom)
            assertTrue(sdk.hasPendingCrashMarker(), "the marker is written on the crashing thread")
            assertEquals(1, delegated.size, "the previous handler saw the crash")
            assertEquals(thread, delegated.first().first)
            assertEquals(boom, delegated.first().second)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(null)
        }
    }
}
