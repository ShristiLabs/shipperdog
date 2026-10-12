package com.shipperdog.telemetry

/**
 * The minimal JSON writer of the telemetry SDK (issue #72, S15-01):
 * hand-rolled because the SDK carries ZERO runtime dependencies (the
 * < 200 KB budget is structural, not aspirational) and the payload
 * vocabulary is closed — one batch shape, one event shape, strings
 * that need escaping and nothing else. The writer produces exactly
 * the frozen S11-04 events contract's field names
 * (docs/analytics.md): camelCase wire names, the optional client
 * `eventId` (the at-least-once dedup key S15-01 added), and nothing
 * beyond the contract.
 */
internal object TelemetryJson {

    /** One serialized event of the frozen contract's batch body. */
    fun event(config: TelemetryConfig, event: TelemetryEvent, occurredAtIso: String): String {
        val out = StringBuilder(192)
        out.append('{')
        string(out, "type", event.type.name)
        out.append(',')
        if (event.result != null) {
            string(out, "result", event.result.name)
            out.append(',')
        }
        string(out, "releaseId", config.releaseId)
        out.append(',')
        if (config.deviceId != null || event.deviceId != null) {
            string(out, "deviceId", event.deviceId ?: config.deviceId!!)
            out.append(',')
        }
        string(out, "eventId", event.eventId)
        out.append(',')
        string(out, "occurredAt", occurredAtIso)
        if (event.type == TelemetryEvent.Type.SESSION) {
            out.append(',')
            number(out, "sessions", event.sessions)
            out.append(',')
            number(out, "crashSessions", event.crashSessions)
            out.append(',')
            number(out, "anrSessions", event.anrSessions)
        }
        out.append('}')
        return out.toString()
    }

    /** The batch body: `{"events":[...]}` — at most [limit] events, one release. */
    fun batch(config: TelemetryConfig, events: List<StringEvent>, limit: Int): String {
        val take = events.take(limit)
        val out = StringBuilder(64 + take.sumOf { it.json.length + 1 })
        out.append("{\"events\":[")
        take.forEachIndexed { index, entry ->
            if (index > 0) {
                out.append(',')
            }
            out.append(entry.json)
        }
        out.append("]}")
        return out.toString()
    }

    /** One queue entry: the event's JSON beside its persisted occurredAt. */
    data class StringEvent(val json: String, val occurredAtIso: String)

    private fun string(out: StringBuilder, name: String, value: String) {
        quote(out, name)
        out.append(':')
        quote(out, value)
    }

    private fun number(out: StringBuilder, name: String, value: Int) {
        quote(out, name)
        out.append(':').append(value)
    }

    private fun quote(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else ->
                    if (char < ' ') {
                        out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", char.code))
                    } else {
                        out.append(char)
                    }
            }
        }
        out.append('"')
    }
}
