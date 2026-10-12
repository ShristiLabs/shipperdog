package com.shipperdog.telemetry

import com.shipperdog.telemetry.TelemetryJson.StringEvent
import java.io.File

/**
 * The offline event queue (issue #72, S15-01): a bounded, file-backed
 * FIFO of serialized events — the offline batching acceptance
 * criterion's entire mechanism. Events land here on the calls that
 * observe them (first launch, session end, crash fold) and leave in
 * [drain] batches the flush loop POSTs; anything the POST does not
 * confirm (transport failure, 5xx) STAYS queued for the next flush —
 * at-least-once delivery, made idempotent server-side by the client
 * event id (the (device, release, event id) dedup key).
 *
 * The bound is the oldest-dropped-first discipline: a device offline
 * for weeks never grows an unbounded disk liability, and the OLDEST
 * events are the ones whose windows the platform's recompute already
 * healed — the freshest telemetry is always what survives.
 *
 * Thread-safe: the app's threads (main, crash fold, flush) all touch
 * one queue.
 */
internal class EventQueue(private val directory: File?, private val maxEvents: Int) {

    private val lock = Any()

    private val memory = ArrayDeque<StringEvent>()

    private val file: File?
        get() = directory?.resolve("events.jsonl")

    init {
        directory?.mkdirs()
    }

    /** The queue's depth (memory + persisted). */
    fun size(): Int = synchronized(lock) {
        load()
        memory.size
    }

    /** Appends events, dropping the OLDEST first when the bound is hit. */
    fun append(events: List<StringEvent>) {
        if (events.isEmpty()) {
            return
        }
        synchronized(lock) {
            load()
            memory.addAll(events)
            while (memory.size > maxEvents) {
                memory.removeFirst()
            }
            persist()
        }
    }

    /** The next [count] events, oldest first — nothing is removed (flush confirms before removal). */
    fun drain(count: Int): List<StringEvent> = synchronized(lock) {
        load()
        memory.take(count)
    }

    /** Removes the oldest [count] events (called only after their batch was accepted or permanently refused). */
    fun removeOldest(count: Int) {
        synchronized(lock) {
            load()
            repeat(count) {
                if (memory.isEmpty()) {
                    return@synchronized
                }
                memory.removeFirst()
            }
            persist()
        }
    }

    /** Drops everything (the opt-out's data hygiene). */
    fun clear() {
        synchronized(lock) {
            memory.clear()
            file?.delete()
        }
    }

    /** Reads the persisted lines once per instance (the memory mirror is authoritative afterwards). */
    private fun load() {
        if (file == null) {
            return
        }
        if (loaded) {
            return
        }
        val lines = file!!.takeIf { it.exists() }?.readLines().orEmpty()
        lines.forEach { line ->
            // One queue line: the JSON payload, a unit separator, the
            // persisted occurredAt (the wire instant never re-mints).
            val separator = line.indexOf('\u001F')
            if (separator > 0) {
                memory.add(StringEvent(line.substring(0, separator), line.substring(separator + 1)))
            }
        }
        loaded = true
    }

    private fun persist() {
        val target = file ?: return
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.bufferedWriter().use { writer ->
            memory.forEach { entry ->
                writer.write(entry.json)
                writer.write("\u001F")
                writer.write(entry.occurredAtIso)
                writer.write("\n")
            }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                throw IllegalStateException("could not persist the telemetry queue")
            }
        }
    }

    private var loaded = false
}
