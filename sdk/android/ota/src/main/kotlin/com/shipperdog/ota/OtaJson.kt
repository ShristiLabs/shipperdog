package com.shipperdog.ota

/**
 * The OTA module's minimal JSON (issue #174, S16-12): hand-rolled
 * reader and writer, exactly the telemetry SDK's zero-dependency
 * doctrine — the < 200 KB budget is structural. The vocabulary is
 * closed on both sides: the reader parses ONE check-response object
 * (strings, numbers, booleans, nulls — the manifest's own shapes),
 * and the writer emits ONE lifecycle-event shape against the frozen
 * S16-08 ingest contract (docs/analytics.md): `type`, the optional
 * `result`, `releaseId`, the client `eventId` (the dedup key),
 * `occurredAt`, `otaBundleId`, and the optional `otaPayload` object
 * (string members only — `device_id` plus app detail words, at most
 * 32 members and 4096 serialized characters server-side).
 */
internal object OtaJson {

    /** One queue entry: the event's JSON beside its persisted occurredAt (the wire instant never re-mints). */
    data class StringEvent(val json: String, val occurredAtIso: String)

    /** The lifecycle event body (the ingest contract's OTA family shape, member order fixed). */
    fun event(
        type: String,
        releaseId: String,
        eventId: String,
        occurredAtIso: String,
        bundleId: String,
        result: String? = null,
        payload: Map<String, String>? = null,
    ): String {
        val out = StringBuilder(192)
        out.append('{')
        string(out, "type", type)
        out.append(',')
        if (result != null) {
            string(out, "result", result)
            out.append(',')
        }
        string(out, "releaseId", releaseId)
        out.append(',')
        string(out, "eventId", eventId)
        out.append(',')
        string(out, "occurredAt", occurredAtIso)
        out.append(',')
        string(out, "otaBundleId", bundleId)
        if (payload != null && payload.isNotEmpty()) {
            out.append(',')
            quote(out, "otaPayload")
            out.append(":{")
            var first = true
            payload.forEach { (name, value) ->
                if (!first) {
                    out.append(',')
                }
                first = false
                string(out, name, value)
            }
            out.append('}')
        }
        out.append('}')
        return out.toString()
    }

    /** The batch body: `{"events":[...]}` — at most [limit] events. */
    fun batch(events: List<StringEvent>, limit: Int): String {
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

    /** Parses one JSON object into a [JsonObject] view (the manifest reader; no arrays on the wire). */
    fun parseObject(body: String): JsonObject {
        val parser = Parser(body)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (parser.hasMore() || value !is Map<*, *>) {
            throw IllegalArgumentException("the body is not one JSON object")
        }
        @Suppress("UNCHECKED_CAST")
        return JsonObject(value as Map<String, Any?>)
    }

    /** A parsed object view: typed accessors that treat absent and null alike. */
    class JsonObject internal constructor(private val members: Map<String, Any?>) {
        fun string(name: String): String? = members[name] as? String
        fun number(name: String): Double? = members[name] as? Double
        fun boolean(name: String): Boolean? = members[name] as? Boolean
    }

    private class Parser(private val body: String) {
        private var position: Int = 0

        fun hasMore(): Boolean = position < body.length

        fun skipWhitespace() {
            while (position < body.length && body[position].isWhitespace()) {
                position++
            }
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (position >= body.length) {
                throw IllegalArgumentException("unexpected end of JSON")
            }
            return when (body[position]) {
                '{' -> parseObjectMembers()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!body.startsWith(word, position)) {
                throw IllegalArgumentException("malformed JSON literal at $position")
            }
            position += word.length
            return value
        }

        private fun parseObjectMembers(): Map<String, Any?> {
            position++ // '{'
            val members = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (hasMore() && body[position] == '}') {
                position++
                return members
            }
            while (true) {
                skipWhitespace()
                if (position >= body.length || body[position] != '"') {
                    throw IllegalArgumentException("malformed JSON object at $position")
                }
                val name = parseString()
                skipWhitespace()
                if (position >= body.length || body[position] != ':') {
                    throw IllegalArgumentException("malformed JSON object at $position")
                }
                position++
                members[name] = parseValue()
                skipWhitespace()
                if (position >= body.length) {
                    throw IllegalArgumentException("unterminated JSON object")
                }
                when (body[position]) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return members
                    }
                    else -> throw IllegalArgumentException("malformed JSON object at $position")
                }
            }
        }

        private fun parseString(): String {
            position++ // opening quote
            val out = StringBuilder()
            while (position < body.length) {
                val char = body[position]
                when {
                    char == '"' -> {
                        position++
                        return out.toString()
                    }
                    char == '\\' -> {
                        position++
                        if (position >= body.length) {
                            throw IllegalArgumentException("unterminated JSON escape")
                        }
                        when (val escaped = body[position]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (position + 4 >= body.length) {
                                    throw IllegalArgumentException("truncated unicode escape")
                                }
                                val hex = body.substring(position + 1, position + 5)
                                out.append(hex.toInt(16).toChar())
                                position += 4
                            }
                            else -> throw IllegalArgumentException("unknown JSON escape \\$escaped")
                        }
                        position++
                    }
                    else -> {
                        out.append(char)
                        position++
                    }
                }
            }
            throw IllegalArgumentException("unterminated JSON string")
        }

        private fun parseNumber(): Double {
            val start = position
            if (hasMore() && (body[position] == '-' || body[position] == '+')) {
                position++
            }
            while (position < body.length && (body[position].isDigit() || body[position] == '.' ||
                    body[position] == 'e' || body[position] == 'E' || body[position] == '-' || body[position] == '+')
            ) {
                position++
            }
            val token = body.substring(start, position)
            return token.toDoubleOrNull() ?: throw IllegalArgumentException("malformed JSON number at $start")
        }
    }

    private fun string(out: StringBuilder, name: String, value: String) {
        quote(out, name)
        out.append(':')
        quote(out, value)
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
