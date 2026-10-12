package com.shipperdog.telemetry

import com.shipperdog.telemetry.TelemetryJson.StringEvent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offline queue's contract (issue #72, S15-01, AC4): FIFO order,
 * the oldest-dropped-first bound, persistence across instances (the
 * offline batching the acceptance criterion names), and the clear of
 * the opt-out.
 */
class EventQueueTest {

    private val directory: java.io.File = Files.createTempDirectory("sd-queue").toFile()

    @Test
    fun drainsOldestFirstAndRemovesOnlyConfirmed() {
        val queue = EventQueue(directory, maxEvents = 100)
        queue.append(events("a", "b", "c"))
        assertEquals(listOf("a", "b"), queue.drain(2).map { it.json })
        assertEquals(3, queue.size())
        queue.removeOldest(2)
        assertEquals(listOf("c"), queue.drain(10).map { it.json })
    }

    @Test
    fun theBoundDropsTheOldestFirst() {
        val queue = EventQueue(directory, maxEvents = 3)
        queue.append(events("a", "b", "c"))
        queue.append(events("d"))
        assertEquals(listOf("b", "c", "d"), queue.drain(10).map { it.json })
    }

    @Test
    fun persistsAcrossInstances() {
        EventQueue(directory, maxEvents = 100).append(events("a", "b"))
        val reloaded = EventQueue(directory, maxEvents = 100)
        assertEquals(listOf("a", "b"), reloaded.drain(10).map { it.json })
        assertEquals(listOf("occurred-a", "occurred-b"), reloaded.drain(10).map { it.occurredAtIso })
    }

    @Test
    fun clearDropsEverythingForTheOptOut() {
        val queue = EventQueue(directory, maxEvents = 100)
        queue.append(events("a", "b"))
        queue.clear()
        assertEquals(0, queue.size())
        assertTrue(EventQueue(directory, maxEvents = 100).drain(10).isEmpty())
    }

    private fun events(vararg json: String): List<StringEvent> =
        json.map { StringEvent(it, "occurred-$it") }
}
