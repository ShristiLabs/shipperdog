package com.shipperdog.companion.core

/**
 * The minimal JSON reader of the companion core (issue #73, S15-02):
 * hand-rolled because the core carries ZERO runtime dependencies (the
 * sdk/android doctrine — the telemetry SDK hand-rolls its writer for
 * the same reason) and the response vocabulary it parses is closed:
 * the portal session, catalog, eligibility, and download-issuance
 * shapes of docs/openapi/baseline.yaml, nothing else. Unknown members
 * are ignored by construction (the parser answers plain Kotlin
 * structures; the model parsers pick the members they know), so a
 * server adding fields never breaks the companion.
 *
 * Parsed values map to Kotlin types: objects to
 * `Map<String, Any?>` (insertion-ordered), arrays to `List<Any?>`,
 * strings to String, numbers to Long or Double (integral values stay
 * Long — artifact sizes and token TTLs are honest integers), true and
 * false to Boolean, null to null.
 */
internal object Json {

    /** Parses one JSON document; throws [JsonException] on malformed input. */
    fun parse(text: String): Any? = Parser(text).run {
        val value = parseValue()
        skipWhitespace()
        requireEnd()
        value
    }

    class JsonException(message: String) : RuntimeException(message)

    private class Parser(private val text: String) {
        private var index: Int = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (index >= text.length) {
                throw JsonException("unexpected end of JSON input")
            }
            return when (val char = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else ->
                    if (char == '-' || char in '0'..'9') {
                        parseNumber()
                    } else {
                        throw JsonException("unexpected character '$char' at $index")
                    }
            }
        }

        fun requireEnd() {
            if (index != text.length) {
                throw JsonException("trailing content at $index")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            index++ // '{'
            val members = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (index < text.length && text[index] == '}') {
                index++
                return members
            }
            while (true) {
                skipWhitespace()
                if (index >= text.length || text[index] != '"') {
                    throw JsonException("expected member name at $index")
                }
                val name = parseString()
                skipWhitespace()
                if (index >= text.length || text[index] != ':') {
                    throw JsonException("expected ':' at $index")
                }
                index++
                members[name] = parseValue()
                skipWhitespace()
                if (index >= text.length) {
                    throw JsonException("unterminated object")
                }
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return members
                    }
                    else -> throw JsonException("expected ',' or '}' at $index")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            index++ // '['
            val elements = ArrayList<Any?>()
            skipWhitespace()
            if (index < text.length && text[index] == ']') {
                index++
                return elements
            }
            while (true) {
                elements.add(parseValue())
                skipWhitespace()
                if (index >= text.length) {
                    throw JsonException("unterminated array")
                }
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return elements
                    }
                    else -> throw JsonException("expected ',' or ']' at $index")
                }
            }
        }

        private fun parseString(): String {
            index++ // opening '"'
            val out = StringBuilder()
            while (index < text.length) {
                when (val char = text[index]) {
                    '"' -> {
                        index++
                        return out.toString()
                    }
                    '\\' -> {
                        index++
                        if (index >= text.length) {
                            throw JsonException("unterminated escape")
                        }
                        when (val escaped = text[index]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 >= text.length) {
                                    throw JsonException("truncated unicode escape")
                                }
                                val hex = text.substring(index + 1, index + 5)
                                val code = hex.toIntOrNull(16) ?: throw JsonException("bad unicode escape")
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> throw JsonException("bad escape '\\$escaped'")
                        }
                        index++
                    }
                    else -> {
                        if (char < ' ') {
                            throw JsonException("unescaped control character at $index")
                        }
                        out.append(char)
                        index++
                    }
                }
            }
            throw JsonException("unterminated string")
        }

        private fun parseNumber(): Any {
            val start = index
            if (index < text.length && text[index] == '-') {
                index++
            }
            while (index < text.length && text[index] in '0'..'9') {
                index++
            }
            var integral = true
            if (index < text.length && text[index] == '.') {
                integral = false
                index++
                while (index < text.length && text[index] in '0'..'9') {
                    index++
                }
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                integral = false
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) {
                    index++
                }
                while (index < text.length && text[index] in '0'..'9') {
                    index++
                }
            }
            val raw = text.substring(start, index)
            if (raw.isEmpty() || raw == "-") {
                throw JsonException("malformed number at $start")
            }
            return if (integral) {
                raw.toLongOrNull() ?: raw.toDouble()
            } else {
                raw.toDouble()
            }
        }

        private fun parseLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, index)) {
                throw JsonException("bad literal at $index")
            }
            index += word.length
            return value
        }

        fun skipWhitespace() {
            while (index < text.length && text[index].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) {
                index++
            }
        }
    }

    /** Serializes one string for a JSON request body (the login/refresh posts — the writer's whole job here). */
    fun write(body: Map<String, String>): String {
        val out = StringBuilder()
        out.append('{')
        body.entries.forEachIndexed { position, (name, value) ->
            if (position > 0) {
                out.append(',')
            }
            quote(out, name)
            out.append(':')
            quote(out, value)
        }
        out.append('}')
        return out.toString()
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
