package com.example.template.pdf

/** A PDF could not be read as a template (damaged, unsupported or beyond the safety limits). */
open class PdfReadException(message: String) : Exception(message)

/** The PDF is encrypted or password-protected; its content cannot be analyzed. */
class PdfEncryptedException : PdfReadException("The PDF is encrypted or password-protected")

/** Safety limits: a crafted or very large PDF cannot exhaust memory or CPU. */
object PdfLimits {
    /** Largest PDF accepted as a template (same limit as Office templates). */
    const val MAX_FILE_BYTES = 50L * 1024 * 1024

    /** Largest decoded size of one stream. */
    const val MAX_STREAM_BYTES = 8 * 1024 * 1024

    /** Total decoded bytes per analysis. */
    const val MAX_TOTAL_DECODED = 48L * 1024 * 1024

    /** Only the first pages are analyzed for styles (a template's look is set by its first pages). */
    const val MAX_PAGES_ANALYZED = 3

    /** Content operators interpreted per page. */
    const val MAX_OPERATIONS_PER_PAGE = 400_000

    const val MAX_OBJECTS = 500_000
    const val MAX_DEPTH = 32
}

/** PDF object model (only what template analysis needs). */
sealed class PdfValue {
    object Null : PdfValue()
    data class Bool(val value: Boolean) : PdfValue()
    data class Num(val value: Double) : PdfValue()
    data class Name(val value: String) : PdfValue()
    class Str(val bytes: ByteArray) : PdfValue()
    data class Arr(val items: List<PdfValue>) : PdfValue()
    data class Dict(val map: Map<String, PdfValue>) : PdfValue() {
        operator fun get(key: String): PdfValue? = map[key]
    }
    data class Ref(val num: Int, val gen: Int) : PdfValue()

    /** A stream: its dictionary and where its raw (encoded) bytes are. */
    class Stream(val dict: Dict, val raw: ByteArray) : PdfValue()

    /** An operator inside a content stream (e.g. "Tj"). */
    data class Op(val name: String) : PdfValue()
}

/** Random access to the file bytes (a memory-mapped file on the device, a byte array in tests). */
interface ByteSource {
    val size: Int
    operator fun get(index: Int): Int
    fun copy(from: Int, to: Int): ByteArray
}

class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override val size: Int get() = bytes.size
    override fun get(index: Int): Int = bytes[index].toInt() and 0xFF
    override fun copy(from: Int, to: Int): ByteArray = bytes.copyOfRange(from, to)
}

class BufferByteSource(private val buffer: java.nio.ByteBuffer) : ByteSource {
    override val size: Int get() = buffer.limit()
    override fun get(index: Int): Int = buffer.get(index).toInt() and 0xFF
    override fun copy(from: Int, to: Int): ByteArray {
        val out = ByteArray(to - from)
        for (i in out.indices) out[i] = buffer.get(from + i)
        return out
    }
}

/**
 * Tokenizer and object parser for PDF syntax (objects and content streams). Invalid input
 * raises [PdfReadException]; nothing is evaluated or executed.
 */
class PdfLexer(private val src: ByteSource, var pos: Int = 0, private val end: Int = src.size) {

    companion object {
        fun isWhite(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
        fun isDelimiter(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
        fun isRegular(c: Int) = !isWhite(c) && !isDelimiter(c)
    }

    val atEnd: Boolean get() = pos >= end

    fun peek(): Int = if (pos < end) src[pos] else -1

    fun skipWhitespaceAndComments() {
        while (pos < end) {
            val c = src[pos]
            if (isWhite(c)) pos++
            else if (c == '%'.code) {
                while (pos < end && src[pos] != 10 && src[pos] != 13) pos++
            } else return
        }
    }

    private fun readRegular(): String {
        val sb = StringBuilder()
        while (pos < end && isRegular(src[pos])) {
            sb.append(src[pos].toChar())
            pos++
            if (sb.length > 256) throw PdfReadException("Token too long")
        }
        return sb.toString()
    }

    /**
     * Next value or operator. Arrays and dictionaries are parsed recursively; an integer pair
     * followed by "R" becomes a [PdfValue.Ref] when [allowRefs] is true (objects, not content).
     */
    fun next(allowRefs: Boolean = true, depth: Int = 0): PdfValue? {
        if (depth > PdfLimits.MAX_DEPTH) throw PdfReadException("Nesting too deep")
        skipWhitespaceAndComments()
        if (pos >= end) return null
        val c = src[pos]
        return when {
            c == '/'.code -> { pos++; PdfValue.Name(decodeName(readRegular())) }
            c == '('.code -> PdfValue.Str(readLiteralString())
            c == '<'.code && pos + 1 < end && src[pos + 1] == '<'.code -> {
                pos += 2
                readDict(allowRefs, depth)
            }
            c == '<'.code -> PdfValue.Str(readHexString())
            c == '['.code -> {
                pos++
                val items = ArrayList<PdfValue>()
                while (true) {
                    skipWhitespaceAndComments()
                    if (pos >= end) throw PdfReadException("Unterminated array")
                    if (src[pos] == ']'.code) { pos++; break }
                    items.add(next(allowRefs, depth + 1) ?: throw PdfReadException("Unterminated array"))
                }
                PdfValue.Arr(withRefs(items, allowRefs))
            }
            c == ']'.code || c == '>'.code || c == ')'.code || c == '{'.code || c == '}'.code -> {
                pos++
                PdfValue.Op(c.toChar().toString())
            }
            else -> {
                val token = readRegular()
                if (token.isEmpty()) { pos++; return PdfValue.Op("?") }
                when {
                    token == "true" -> PdfValue.Bool(true)
                    token == "false" -> PdfValue.Bool(false)
                    token == "null" -> PdfValue.Null
                    token[0].isDigit() || token[0] == '-' || token[0] == '+' || token[0] == '.' ->
                        token.toDoubleOrNull()?.let { PdfValue.Num(it) } ?: PdfValue.Op(token)
                    else -> PdfValue.Op(token)
                }
            }
        }
    }

    /** Folds "n g R" sequences into references. */
    private fun withRefs(items: List<PdfValue>, allowRefs: Boolean): List<PdfValue> {
        if (!allowRefs || items.size < 3) return items
        val out = ArrayList<PdfValue>(items.size)
        var i = 0
        while (i < items.size) {
            val a = items[i]
            val b = items.getOrNull(i + 1)
            val r = items.getOrNull(i + 2)
            if (a is PdfValue.Num && b is PdfValue.Num && r is PdfValue.Op && r.name == "R") {
                out.add(PdfValue.Ref(a.value.toInt(), b.value.toInt()))
                i += 3
            } else {
                out.add(a)
                i++
            }
        }
        return out
    }

    private fun readDict(allowRefs: Boolean, depth: Int): PdfValue.Dict {
        val raw = ArrayList<PdfValue>()
        while (true) {
            skipWhitespaceAndComments()
            if (pos >= end) throw PdfReadException("Unterminated dictionary")
            if (src[pos] == '>'.code && pos + 1 < end && src[pos + 1] == '>'.code) { pos += 2; break }
            raw.add(next(allowRefs, depth + 1) ?: throw PdfReadException("Unterminated dictionary"))
        }
        val items = withRefs(raw, allowRefs)
        val map = LinkedHashMap<String, PdfValue>()
        var i = 0
        while (i + 1 < items.size) {
            val key = items[i] as? PdfValue.Name
            if (key == null) { i++; continue }
            map[key.value] = items[i + 1]
            i += 2
        }
        return PdfValue.Dict(map)
    }

    private fun decodeName(raw: String): String {
        if (!raw.contains('#')) return raw
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val code = if (raw[i] == '#' && i + 2 < raw.length) raw.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (code != null) {
                sb.append(code.toChar())
                i += 3
                continue
            }
            sb.append(raw[i]); i++
        }
        return sb.toString()
    }

    private fun readLiteralString(): ByteArray {
        pos++ // (
        val out = java.io.ByteArrayOutputStream()
        var depth = 1
        while (pos < end) {
            val c = src[pos++]
            when (c) {
                '\\'.code -> {
                    if (pos >= end) break
                    val e = src[pos++]
                    when (e) {
                        'n'.code -> out.write(10)
                        'r'.code -> out.write(13)
                        't'.code -> out.write(9)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        10 -> Unit
                        13 -> if (pos < end && src[pos] == 10) pos++
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            repeat(2) {
                                if (pos < end && src[pos] in '0'.code..'7'.code) v = v * 8 + (src[pos++] - '0'.code)
                            }
                            out.write(v and 0xFF)
                        }
                        else -> out.write(e)
                    }
                }
                '('.code -> { depth++; out.write(c) }
                ')'.code -> { depth--; if (depth == 0) return out.toByteArray(); out.write(c) }
                else -> out.write(c)
            }
            if (out.size() > 1_000_000) throw PdfReadException("String too long")
        }
        throw PdfReadException("Unterminated string")
    }

    private fun readHexString(): ByteArray {
        pos++ // <
        val out = java.io.ByteArrayOutputStream()
        var high = -1
        while (pos < end) {
            val c = src[pos++]
            if (c == '>'.code) {
                if (high >= 0) out.write(high shl 4)
                return out.toByteArray()
            }
            val v = Character.digit(c, 16)
            if (v < 0) continue
            if (high < 0) high = v else { out.write((high shl 4) or v); high = -1 }
        }
        throw PdfReadException("Unterminated hex string")
    }

    /** Skips the binary data of an inline image (after "ID") up to its "EI". */
    fun skipInlineImage() {
        if (pos < end && isWhite(src[pos])) pos++
        while (pos + 2 < end) {
            if (isWhite(src[pos]) && src[pos + 1] == 'E'.code && src[pos + 2] == 'I'.code &&
                (pos + 3 >= end || !isRegular(src[pos + 3]))
            ) {
                pos += 3
                return
            }
            pos++
        }
        pos = end
    }
}
