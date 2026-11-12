package com.sojibhuiya.jarves.apiproviders

/**
 * Minimal, dependency-free JSON reader/writer.
 *
 * The API provider store only needs a very small, well-known JSON shape, so a tiny
 * implementation is preferable to adding a serialization dependency (or relying on
 * `org.json`, which is only stubbed in JVM unit tests).
 */
internal object MiniJson {

    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> out.append(if (value.isFinite()) value.toString() else "0")
            is Float -> out.append(if (value.isFinite()) value.toString() else "0")
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, entryValue) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key as? String ?: key.toString())
                    out.append(':')
                    write(out, entryValue)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, item)
                }
                out.append(']')
            }
            else -> writeString(out, value.toString())
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (character < ' ') {
                    out.append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(character)
                }
            }
        }
        out.append('"')
    }

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.exhausted()) throw IllegalArgumentException("Unexpected trailing JSON content")
        return value
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun exhausted(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (exhausted()) throw IllegalArgumentException("Unexpected end of JSON input")
            return when (val character = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    if (character == '-' || character.isDigit()) readNumber()
                    else throw IllegalArgumentException("Unexpected character '$character' in JSON")
                }
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, index)) throw IllegalArgumentException("Invalid JSON literal")
            index += literal.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            index++ // '{'
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!exhausted() && text[index] == '}') { index++; return result }
            while (true) {
                skipWhitespace()
                if (exhausted() || text[index] != '"') throw IllegalArgumentException("Expected JSON object key")
                val key = readString()
                skipWhitespace()
                if (exhausted() || text[index] != ':') throw IllegalArgumentException("Expected ':' in JSON object")
                index++
                result[key] = readValue()
                skipWhitespace()
                if (exhausted()) throw IllegalArgumentException("Unterminated JSON object")
                when (text[index]) {
                    ',' -> index++
                    '}' -> { index++; return result }
                    else -> throw IllegalArgumentException("Expected ',' or '}' in JSON object")
                }
            }
        }

        private fun readArray(): List<Any?> {
            index++ // '['
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (!exhausted() && text[index] == ']') { index++; return result }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                if (exhausted()) throw IllegalArgumentException("Unterminated JSON array")
                when (text[index]) {
                    ',' -> index++
                    ']' -> { index++; return result }
                    else -> throw IllegalArgumentException("Expected ',' or ']' in JSON array")
                }
            }
        }

        private fun readString(): String {
            index++ // opening quote
            val builder = StringBuilder()
            while (true) {
                if (exhausted()) throw IllegalArgumentException("Unterminated JSON string")
                when (val character = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (exhausted()) throw IllegalArgumentException("Unterminated JSON escape")
                        when (val escape = text[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) throw IllegalArgumentException("Truncated \\u escape")
                                val hex = text.substring(index, index + 4)
                                index += 4
                                builder.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape '\\$escape'")
                        }
                    }
                    else -> builder.append(character)
                }
            }
        }

        private fun readNumber(): Number {
            val start = index
            if (!exhausted() && text[index] == '-') index++
            while (!exhausted() && (text[index].isDigit() || text[index] == '.' || text[index] == 'e' || text[index] == 'E' || text[index] == '+' || text[index] == '-')) index++
            val raw = text.substring(start, index)
            if (raw.isEmpty() || raw == "-") throw IllegalArgumentException("Invalid JSON number")
            return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) raw.toDouble() else raw.toLong()
        }
    }
}
