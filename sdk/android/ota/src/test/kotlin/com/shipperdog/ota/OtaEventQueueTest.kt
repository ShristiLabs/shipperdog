package com.shipperdog.ota

import com.shipperdog.ota.OtaJson.StringEvent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offline queue's behavior contract (issue #174, S16-12): the
 * telemetry `EventQueue` doctrine's own legs — bounded with
 * oldest-dropped-first, drain-without-removal, removal only after
 * confirmation, and persistence across instances (a process death
 * loses nothing the next flush cannot carry).
 */
class OtaEventQueueTest {

    private val directory: java.io.File = Files.createTempDirectory("sd-ota-queue").toFile()

    private fun event(n: Int) = StringEvent("""{"n":$n}""", "2026-10-17T12:00:0${n % 10}Z")

    @Test
    fun theBoundDropsTheOldestFirst() {
        val queue = OtaEventQueue(directory.resolve("bounded"), 3)
        queue.append((1..5).map { event(it) })
        assertEquals(3, queue.size())
        assertEquals(listOf("""{"n":3}""", """{"n":4}""", """{"n":5}"""), queue.drain(10).map { it.json })
    }

    @Test
    fun drainDoesNotRemoveAndRemovalNeedsItsCount() {
        val queue = OtaEventQueue(directory.resolve("drain"), 10)
        queue.append(listOf(event(1), event(2)))
        assertEquals(1, queue.drain(1).size)
        assertEquals(2, queue.size())
        queue.removeOldest(1)
        assertEquals(1, queue.size())
        assertEquals("""{"n":2}""", queue.drain(10)[0].json)
    }

    @Test
    fun aFreshInstanceResumesFromThePersistedFile() {
        val queue = OtaEventQueue(directory.resolve("persist"), 10)
        queue.append(listOf(event(1), event(2)))
        val next = OtaEventQueue(directory.resolve("persist"), 10)
        assertEquals(2, next.size())
        assertEquals("""{"n":1}""", next.drain(10)[0].json)
        assertEquals("2026-10-17T12:00:01Z", next.drain(10)[0].occurredAtIso)
    }

    @Test
    fun aNullDirectoryKeepsEverythingInMemory() {
        val queue = OtaEventQueue(null, 10)
        queue.append(listOf(event(1)))
        assertEquals(1, queue.size())
        assertTrue(!directory.resolve("memory").exists())
    }

    @Test
    fun clearDropsEverything() {
        val queue = OtaEventQueue(directory.resolve("clear"), 10)
        queue.append(listOf(event(1), event(2)))
        queue.clear()
        assertEquals(0, queue.size())
        assertEquals(0, OtaEventQueue(directory.resolve("clear"), 10).size())
    }
}
