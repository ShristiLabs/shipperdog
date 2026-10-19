package com.shipperdog.ota

import com.shipperdog.ota.OtaJson.StringEvent
import java.io.File

/**
 * The offline lifecycle-event queue (issue #174, S16-12): the
 * telemetry SDK's `EventQueue` doctrine applied to the OTA family —
 * a bounded, file-backed FIFO of serialized events. Reports land on
 * the funnel transitions that observe them and leave in [drain]
 * batches the reporter POSTs; anything the POST does not confirm
 * (transport failure, 5xx) STAYS queued for the next flush —
 * at-least-once delivery, idempotent server-side by the client
 * event id (V54's dedup key). The bound drops the OLDEST first: the
 * freshest funnel report is always what survives.
 */
internal class OtaEventQueue(private val directory: File?, private val maxEvents: Int) {

    private val lock = Any()

    private val memory = ArrayDeque<StringEvent>()

    private val file: File?
        get() = directory?.resolve("ota-events.jsonl")

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

    /** Removes the oldest [count] events (only after their batch was accepted or permanently refused). */
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

    /** Drops everything. */
    fun clear() {
        synchronized(lock) {
            memory.clear()
            file?.delete()
        }
    }

    /** Reads the persisted lines once per instance (the memory mirror is authoritative afterwards). */
    private fun load() {
        if (file == null || loaded) {
            return
        }
        file!!.takeIf { it.exists() }?.readLines()?.forEach { line ->
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
                throw IllegalStateException("could not persist the OTA event queue")
            }
        }
    }

    private var loaded = false
}
