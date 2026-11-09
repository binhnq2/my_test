package com.ts8.core.voice.offline

/**
 * A strict, dependency-free JSON reader for the two documents Voice Offline
 * owns, and for Vosk's own result strings.
 *
 * **Why not `org.json`.** `org.json` ships with Android, but every method of it
 * throws `Stub!` under a plain JVM unit test — the stub `android.jar` answers
 * with defaults, and this module's whole point is that its validation rules can
 * be asserted without a device or Robolectric. The build host for this project
 * cannot use Robolectric (its `android-all` jar is not in the offline cache; see
 * `app/build.gradle.kts`), so a model-pack validator written on `org.json`
 * would be untestable here — and an untestable checksum/ABI gate is exactly the
 * kind of thing that silently stops working.
 *
 * **What it is not.** It is not a general JSON library: no streaming, no writer,
 * no number formats beyond what these documents use. It parses the whole input
 * into memory, which is correct for a `metadata.json` of a few hundred bytes and
 * for a Vosk result of a few hundred more, and it refuses anything it does not
 * understand rather than guessing.
 *
 * **Why strictness matters here.** The documents it reads decide whether an
 * untrusted USB pack is allowed to become the recogniser's model. A lenient
 * parser that skipped a malformed field would let a pack whose checksum line is
 * garbage validate as READY; failing with [Malformed] instead is the difference
 * between "the driver is told the pack is corrupt" and "the driver gets a crash
 * inside the native loader".
 */
object MiniJson {

    /** A parsed value. Deliberately a closed set of the six JSON kinds. */
    sealed class Value {
        data class Obj(val entries: Map<String, Value>) : Value()
        data class Arr(val items: List<Value>) : Value()
        data class Str(val value: String) : Value()
        data class Num(val value: Double) : Value()
        data class Bool(val value: Boolean) : Value()
        object Null : Value()
    }

    /** The document could not be read. The message names the offset. */
    class Malformed(message: String) : Exception(message)

    /** Parses [text] as one JSON document. Throws [Malformed] on any error. */
    fun parse(text: String): Value {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) {
            throw Malformed("trailing content at ${reader.offset}")
        }
        return value
    }

    /** Convenience for the common "root must be an object" case. */
    fun parseObject(text: String): Map<String, Value> =
        (parse(text) as? Value.Obj)?.entries
            ?: throw Malformed("root is not an object")

    // -- typed accessors ----------------------------------------------------
    //
    // Written as *file-level* extensions rather than members of [MiniJson], so
    // a caller in another file of this module needs no receiver to reach them.
    // They return null rather than throwing: a *missing* field is a validation
    // verdict (CORRUPT), not a parser failure, and the two need different
    // messages in the diagnostics.

    // -- parser -------------------------------------------------------------
    private class Reader(private val text: String) {
        var offset: Int = 0
            private set

        fun atEnd(): Boolean = offset >= text.length

        fun skipWhitespace() {
            while (offset < text.length && text[offset].isJsonWhitespace()) offset++
        }

        fun readValue(): Value {
            skipWhitespace()
            if (atEnd()) throw Malformed("unexpected end of input")
            return when (val c = text[offset]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> Value.Str(readString())
                't' -> { expectWord("true"); Value.Bool(true) }
                'f' -> { expectWord("false"); Value.Bool(false) }
                'n' -> { expectWord("null"); Value.Null }
                else -> {
                    if (c == '-' || c in '0'..'9') readNumber()
                    else throw Malformed("unexpected '$c' at $offset")
                }
            }
        }

        private fun readObject(): Value.Obj {
            offset++ // '{'
            skipWhitespace()
            if (peek() == '}') { offset++; return Value.Obj(emptyMap()) }

            val entries = LinkedHashMap<String, Value>()
            while (true) {
                skipWhitespace()
                if (peek() != '"') {
                    throw Malformed("object key must be a string at $offset")
                }
                val key = readString()
                skipWhitespace()
                if (peek() != ':') throw Malformed("expected ':' at $offset")
                offset++
                // A duplicate key is an error rather than "last wins": two
                // checksum lines with different values is a pack that must not
                // validate, and silently keeping one of them hides the fact.
                if (entries.containsKey(key)) {
                    throw Malformed("duplicate key '$key' at $offset")
                }
                entries[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { offset++ }
                    '}' -> { offset++; return Value.Obj(entries) }
                    else -> throw Malformed("expected ',' or '}' at $offset")
                }
            }
        }

        private fun readArray(): Value.Arr {
            offset++ // '['
            skipWhitespace()
            if (peek() == ']') { offset++; return Value.Arr(emptyList()) }

            val items = ArrayList<Value>()
            while (true) {
                items.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> { offset++ }
                    ']' -> { offset++; return Value.Arr(items) }
                    else -> throw Malformed("expected ',' or ']' at $offset")
                }
            }
        }

        private fun readString(): String {
            offset++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw Malformed("unterminated string at $offset")
                when (val c = text[offset]) {
                    '"' -> { offset++; return sb.toString() }
                    '\\' -> {
                        offset++
                        if (atEnd()) throw Malformed("unterminated escape at $offset")
                        when (val e = text[offset]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (offset + 4 >= text.length) {
                                    throw Malformed("short \\u escape at $offset")
                                }
                                val hex = text.substring(offset + 1, offset + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw Malformed("bad \\u escape '$hex' at $offset")
                                sb.append(code.toChar())
                                offset += 4
                            }
                            else -> throw Malformed("bad escape '\\$e' at $offset")
                        }
                        offset++
                    }
                    else -> {
                        if (c < ' ') throw Malformed("control character in string at $offset")
                        sb.append(c)
                        offset++
                    }
                }
            }
        }

        private fun readNumber(): Value.Num {
            val start = offset
            if (peek() == '-') offset++
            while (!atEnd() && (text[offset].isDigit() || text[offset] in ".eE+-")) offset++
            val raw = text.substring(start, offset)
            val parsed = raw.toDoubleOrNull()
                ?: throw Malformed("bad number '$raw' at $start")
            return Value.Num(parsed)
        }

        private fun expectWord(word: String) {
            if (!text.startsWith(word, offset)) {
                throw Malformed("expected '$word' at $offset")
            }
            offset += word.length
        }

        private fun peek(): Char = if (atEnd()) throw Malformed("unexpected end of input") else text[offset]

        private fun Char.isJsonWhitespace(): Boolean =
            this == ' ' || this == '\t' || this == '\n' || this == '\r'
    }
}

// -- typed accessors --------------------------------------------------------
//
// File-level extensions rather than members of [MiniJson], so a caller in
// another file of this module needs no receiver to reach them. Each returns
// null rather than throwing: a *missing* field is a validation verdict
// (CORRUPT), not a parser failure, and the diagnostics need to tell them apart.

fun MiniJson.Value?.asObj(): Map<String, MiniJson.Value>? = (this as? MiniJson.Value.Obj)?.entries

fun MiniJson.Value?.asArr(): List<MiniJson.Value>? = (this as? MiniJson.Value.Arr)?.items

fun MiniJson.Value?.asString(): String? = (this as? MiniJson.Value.Str)?.value

fun MiniJson.Value?.asLong(): Long? = (this as? MiniJson.Value.Num)?.value?.toLong()

fun MiniJson.Value?.asInt(): Int? = (this as? MiniJson.Value.Num)?.value?.toInt()

fun MiniJson.Value?.asBool(): Boolean? = (this as? MiniJson.Value.Bool)?.value

fun Map<String, MiniJson.Value>.str(key: String): String? = this[key].asString()

fun Map<String, MiniJson.Value>.integer(key: String): Int? = this[key].asInt()

fun Map<String, MiniJson.Value>.long(key: String): Long? = this[key].asLong()

fun Map<String, MiniJson.Value>.strings(key: String): List<String>? =
    this[key].asArr()?.mapNotNull { it.asString() }
