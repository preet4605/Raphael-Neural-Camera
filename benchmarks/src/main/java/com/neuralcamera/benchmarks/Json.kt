package com.neuralcamera.benchmarks

/**
 * Minimal JSON writer and parser with no external dependency, so evidence reports can be produced and re-read in JVM
 * unit tests and on Android alike. Objects parse to [LinkedHashMap], arrays to [ArrayList], integers to [Long],
 * other numbers to [Double].
 */
object Json {

    fun stringify(value: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "Trailing characters at ${parser.position}" }
        return value
    }

    private fun write(sb: StringBuilder, value: Any?, pretty: Boolean, depth: Int) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Byte, is Short, is Int, is Long -> sb.append(value.toString())
            is Float -> writeDouble(sb, value.toDouble())
            is Double -> writeDouble(sb, value)
            is Number -> writeDouble(sb, value.toDouble())
            is CharSequence -> writeString(sb, value.toString())
            is Map<*, *> -> writeObject(sb, value, pretty, depth)
            is Iterable<*> -> writeArray(sb, value.iterator(), pretty, depth)
            is Array<*> -> writeArray(sb, value.iterator(), pretty, depth)
            is IntArray -> writeArray(sb, value.iterator(), pretty, depth)
            is LongArray -> writeArray(sb, value.iterator(), pretty, depth)
            is FloatArray -> writeArray(sb, value.iterator(), pretty, depth)
            is DoubleArray -> writeArray(sb, value.iterator(), pretty, depth)
            is BooleanArray -> writeArray(sb, value.iterator(), pretty, depth)
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeDouble(sb: StringBuilder, v: Double) {
        if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toString())
    }

    private fun writeObject(sb: StringBuilder, map: Map<*, *>, pretty: Boolean, depth: Int) {
        if (map.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            newline(sb, pretty, depth + 1)
            writeString(sb, k.toString())
            sb.append(if (pretty) ": " else ":")
            write(sb, v, pretty, depth + 1)
        }
        newline(sb, pretty, depth)
        sb.append('}')
    }

    private fun writeArray(sb: StringBuilder, it: Iterator<*>, pretty: Boolean, depth: Int) {
        if (!it.hasNext()) {
            sb.append("[]")
            return
        }
        sb.append('[')
        var first = true
        while (it.hasNext()) {
            if (!first) sb.append(',')
            first = false
            newline(sb, pretty, depth + 1)
            write(sb, it.next(), pretty, depth + 1)
        }
        newline(sb, pretty, depth)
        sb.append(']')
    }

    private fun newline(sb: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        var position = 0

        fun atEnd() = position >= s.length

        fun skipWhitespace() {
            while (position < s.length && s[position].isWhitespace()) position++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            require(!atEnd()) { "Unexpected end of input" }
            return when (val c = s[position]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) parseNumber() else fail("Unexpected character '$c'")
            }
        }

        private fun fail(message: String): Nothing = throw IllegalArgumentException("$message at $position")

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, position)) fail("Expected $word")
            position += word.length
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            position++ // {
            skipWhitespace()
            if (peek() == '}') {
                position++
                return map
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("Expected object key")
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') fail("Expected ':'")
                position++
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return map
                    }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            position++ // [
            skipWhitespace()
            if (peek() == ']') {
                position++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return list
                    }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun peek(): Char {
            if (atEnd()) fail("Unexpected end of input")
            return s[position]
        }

        private fun parseString(): String {
            position++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("Unterminated string")
                val c = s[position++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("Unterminated escape")
                        when (val e = s[position++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (position + 4 > s.length) fail("Bad unicode escape")
                                sb.append(s.substring(position, position + 4).toInt(16).toChar())
                                position += 4
                            }
                            else -> fail("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = position
            if (peek() == '-') position++
            while (!atEnd() && s[position].isDigit()) position++
            var integral = true
            if (!atEnd() && s[position] == '.') {
                integral = false
                position++
                while (!atEnd() && s[position].isDigit()) position++
            }
            if (!atEnd() && (s[position] == 'e' || s[position] == 'E')) {
                integral = false
                position++
                if (!atEnd() && (s[position] == '+' || s[position] == '-')) position++
                while (!atEnd() && s[position].isDigit()) position++
            }
            val text = s.substring(start, position)
            if (text.isEmpty() || text == "-") fail("Bad number")
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDoubleOrNull() ?: fail("Bad number '$text'")
        }
    }
}
