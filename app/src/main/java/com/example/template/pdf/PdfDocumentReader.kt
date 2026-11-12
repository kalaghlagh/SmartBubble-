package com.example.template.pdf

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/** One page with its inherited attributes resolved. */
class PdfPage(
    val index: Int,
    val dict: PdfValue.Dict,
    /** MediaBox (or CropBox) as [llx, lly, urx, ury]. */
    val box: DoubleArray,
    val rotate: Int,
    val resources: PdfValue.Dict?
) {
    val width: Double get() = box[2] - box[0]
    val height: Double get() = box[3] - box[1]
}

/**
 * Minimal, read-only PDF reader for template analysis. Objects are located by scanning the file
 * (so damaged cross-reference tables do not matter) plus compressed object streams; only the
 * Flate filter is decoded. Encrypted files are detected and refused. All work is bounded by
 * [PdfLimits].
 */
class PdfDocumentReader private constructor(private val src: ByteSource) {

    companion object {
        fun open(file: File): PdfDocumentReader {
            if (!file.isFile || file.length() == 0L) throw PdfReadException("Empty file")
            if (file.length() > PdfLimits.MAX_FILE_BYTES) throw PdfReadException("File too large")
            // Memory-mapped: the file is not copied into the heap.
            val buffer = RandomAccessFile(file, "r").use { raf ->
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            }
            return PdfDocumentReader(BufferByteSource(buffer)).also { it.index() }
        }

        fun open(bytes: ByteArray): PdfDocumentReader = PdfDocumentReader(ArrayByteSource(bytes)).also { it.index() }

        /** True when [file] starts like a PDF ("%PDF-" within the first 1 KB). */
        fun looksLikePdf(file: File): Boolean = try {
            file.inputStream().use { input ->
                val head = ByteArray(1024)
                val n = input.read(head)
                n > 5 && String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Object number -> offset just after "n g obj". The last definition wins (incremental updates). */
    private val offsets = HashMap<Int, Int>()
    private val cache = HashMap<Int, PdfValue>()
    private val trailers = ArrayList<PdfValue.Dict>()
    private var objectStreamsLoaded = false
    private var decodedTotal = 0L

    var encrypted = false
        private set

    private fun index() {
        val header = minOf(src.size, 1024)
        var hasHeader = false
        for (i in 0 until header - 4) {
            if (src[i] == '%'.code && src[i + 1] == 'P'.code && src[i + 2] == 'D'.code && src[i + 3] == 'F'.code) { hasHeader = true; break }
        }
        if (!hasHeader) throw PdfReadException("Not a PDF file")

        var i = 0
        val n = src.size
        while (i < n - 3) {
            val c = src[i]
            if (c == 'o'.code && src[i + 1] == 'b'.code && src[i + 2] == 'j'.code &&
                (i + 3 >= n || !PdfLexer.isRegular(src[i + 3])) && i > 0 && PdfLexer.isWhite(src[i - 1])
            ) {
                parseObjectHeader(i)?.let { num ->
                    offsets[num] = i + 3
                    if (offsets.size > PdfLimits.MAX_OBJECTS) throw PdfReadException("Too many objects")
                }
                i += 3
                continue
            }
            if (c == 't'.code && matches(i, "trailer")) {
                try {
                    val lexer = PdfLexer(src, i + 7)
                    (lexer.next() as? PdfValue.Dict)?.let { trailers.add(it) }
                } catch (_: PdfReadException) {
                }
            }
            if (c == 's'.code && matches(i, "stream") && (i == 0 || !PdfLexer.isRegular(src[i - 1]))) {
                // Skip stream data quickly: jump to "endstream".
                val e = indexOf("endstream", i + 6)
                if (e > 0) { i = e + 9; continue }
            }
            i++
        }
        // Cross-reference streams carry the trailer keys.
        for ((num, off) in offsets) {
            if (!containsNear(off, "/XRef")) continue
            val v = try { obj(num) } catch (_: PdfReadException) { null }
            if (v is PdfValue.Stream && (v.dict["Type"] as? PdfValue.Name)?.value == "XRef") trailers.add(v.dict)
        }
        encrypted = trailers.any { it["Encrypt"] != null }
    }

    /** True when [word] occurs in the first bytes of the object at [off] (cheap pre-filter). */
    private fun containsNear(off: Int, word: String): Boolean {
        val limit = minOf(src.size - word.length, off + 400)
        var i = off
        while (i <= limit) {
            if (src[i] == word[0].code && matches(i, word)) return true
            i++
        }
        return false
    }

    private fun matches(at: Int, word: String): Boolean {
        if (at + word.length > src.size) return false
        for (k in word.indices) if (src[at + k] != word[k].code) return false
        return true
    }

    private fun indexOf(word: String, from: Int): Int {
        var i = from
        while (i <= src.size - word.length) {
            if (src[i] == word[0].code && matches(i, word)) return i
            i++
        }
        return -1
    }

    /** Reads "num gen" backwards from the "obj" keyword at [objAt]. */
    private fun parseObjectHeader(objAt: Int): Int? {
        var p = objAt - 1
        fun skipWs() { while (p >= 0 && PdfLexer.isWhite(src[p])) p-- }
        fun digits(): Int? {
            val endD = p
            while (p >= 0 && src[p] in '0'.code..'9'.code) p--
            if (p == endD || endD - p > 10) return null
            var v = 0L
            for (k in p + 1..endD) v = v * 10 + (src[k] - '0'.code)
            return if (v > Int.MAX_VALUE) null else v.toInt()
        }
        skipWs()
        digits() ?: return null
        if (p < 0 || !PdfLexer.isWhite(src[p])) return null
        skipWs()
        val num = digits() ?: return null
        if (p >= 0 && PdfLexer.isRegular(src[p])) return null
        return num
    }

    /** The object [num], or null when it does not exist. */
    fun obj(num: Int): PdfValue? {
        cache[num]?.let { return it }
        val off = offsets[num]
        val value = if (off != null) parseAt(off) else {
            loadObjectStreams()
            cache[num]
        }
        if (value != null) cache[num] = value
        return value
    }

    private fun parseAt(off: Int): PdfValue? {
        val lexer = PdfLexer(src, off)
        val v = lexer.next() ?: return null
        if (v !is PdfValue.Dict) return v
        lexer.skipWhitespaceAndComments()
        if (!matches(lexer.pos, "stream")) return v
        var start = lexer.pos + 6
        if (start < src.size && src[start] == 13) start++
        if (start < src.size && src[start] == 10) start++
        val declared = (resolveDirect(v["Length"]) as? PdfValue.Num)?.value?.toInt()
        val endAt = if (declared != null && declared >= 0 && start + declared <= src.size &&
            indexOf("endstream", start + declared).let { it >= 0 && it - (start + declared) < 16 }
        ) start + declared else {
            val e = indexOf("endstream", start)
            if (e < 0) throw PdfReadException("Unterminated stream")
            var x = e
            if (x > start && src[x - 1] == 10) x--
            if (x > start && src[x - 1] == 13) x--
            x
        }
        if (endAt - start > PdfLimits.MAX_FILE_BYTES) throw PdfReadException("Stream too large")
        return PdfValue.Stream(v, src.copy(start, endAt))
    }

    /** Resolves a reference without loading object streams recursively (used for /Length). */
    private fun resolveDirect(v: PdfValue?): PdfValue? =
        if (v is PdfValue.Ref) offsets[v.num]?.let { off -> PdfLexer(src, off).next() } else v

    fun resolve(v: PdfValue?, depth: Int = 0): PdfValue? {
        if (v !is PdfValue.Ref) return v
        if (depth > PdfLimits.MAX_DEPTH) return null
        return resolve(obj(v.num), depth + 1)
    }

    /**
     * The dictionary of [v] (a stream's dictionary without copying its data, which matters for
     * large images).
     */
    fun dict(v: PdfValue?, depth: Int = 0): PdfValue.Dict? {
        if (v is PdfValue.Ref && !cache.containsKey(v.num) && depth < PdfLimits.MAX_DEPTH) {
            val off = offsets[v.num]
            if (off != null) {
                return when (val head = try { PdfLexer(src, off).next() } catch (_: PdfReadException) { null }) {
                    is PdfValue.Dict -> head
                    is PdfValue.Ref -> dict(head, depth + 1)
                    else -> null
                }
            }
        }
        return when (val r = resolve(v)) {
            is PdfValue.Dict -> r
            is PdfValue.Stream -> r.dict
            else -> null
        }
    }

    private fun loadObjectStreams() {
        if (objectStreamsLoaded) return
        objectStreamsLoaded = true
        for ((num, off) in offsets.entries.toList()) {
            if (!containsNear(off, "/ObjStm")) continue
            val s = (cache[num] ?: try { parseAt(off) } catch (_: PdfReadException) { null }) as? PdfValue.Stream ?: continue
            if ((s.dict["Type"] as? PdfValue.Name)?.value != "ObjStm") continue
            val data = try { decode(s) } catch (_: PdfReadException) { null } ?: continue
            val count = (s.dict["N"] as? PdfValue.Num)?.value?.toInt() ?: continue
            val first = (s.dict["First"] as? PdfValue.Num)?.value?.toInt() ?: continue
            val source = ArrayByteSource(data)
            val head = PdfLexer(source, 0, minOf(first, data.size))
            val pairs = ArrayList<Pair<Int, Int>>()
            repeat(count.coerceAtMost(100_000)) {
                val n = head.next(allowRefs = false) as? PdfValue.Num ?: return@repeat
                val o = head.next(allowRefs = false) as? PdfValue.Num ?: return@repeat
                pairs.add(n.value.toInt() to o.value.toInt())
            }
            for ((objNum, rel) in pairs) {
                if (offsets.containsKey(objNum) || cache.containsKey(objNum)) continue
                val at = first + rel
                if (at < 0 || at >= data.size) continue
                try {
                    PdfLexer(source, at).next()?.let { cache[objNum] = it }
                } catch (_: PdfReadException) {
                }
            }
        }
    }

    /** Decoded stream data, or null when it uses a filter other than Flate. */
    fun decode(stream: PdfValue.Stream): ByteArray? {
        val filters = when (val f = resolve(stream.dict["Filter"])) {
            null -> emptyList()
            is PdfValue.Name -> listOf(f.value)
            is PdfValue.Arr -> f.items.mapNotNull { (resolve(it) as? PdfValue.Name)?.value }
            else -> return null
        }
        var data = stream.raw
        for (filter in filters) {
            data = when (filter) {
                "FlateDecode", "Fl" -> inflate(data)
                else -> return null
            }
        }
        decodedTotal += data.size
        if (decodedTotal > PdfLimits.MAX_TOTAL_DECODED) throw PdfReadException("PDF too large to analyze")
        return data
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(minOf(data.size * 4, PdfLimits.MAX_STREAM_BYTES))
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = try { inflater.inflate(buffer) } catch (e: Exception) { throw PdfReadException("Damaged compressed stream") }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, n)
                if (out.size() > PdfLimits.MAX_STREAM_BYTES) throw PdfReadException("Stream too large")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun rootDict(): PdfValue.Dict? =
        trailers.asReversed().firstNotNullOfOrNull { dict(it["Root"]) }

    /** All pages in document order (inherited attributes resolved). */
    fun pages(limit: Int = Int.MAX_VALUE): List<PdfPage> {
        val out = ArrayList<PdfPage>()
        val visitedRefs = HashSet<Int>()
        var visits = 0
        fun walk(ref: PdfValue?, box: DoubleArray?, rotate: Int, resources: PdfValue.Dict?, depth: Int) {
            if (depth > PdfLimits.MAX_DEPTH || out.size >= limit || ++visits > 100_000) return
            if (ref is PdfValue.Ref && !visitedRefs.add(ref.num)) return // cycle or shared node
            val node = dict(ref) ?: return
            val nodeBox = boxOf(node["CropBox"]) ?: boxOf(node["MediaBox"]) ?: box
            val nodeRotate = (resolve(node["Rotate"]) as? PdfValue.Num)?.value?.toInt() ?: rotate
            val nodeResources = dict(node["Resources"]) ?: resources
            val kids = resolve(node["Kids"]) as? PdfValue.Arr
            val type = (node["Type"] as? PdfValue.Name)?.value
            if (kids != null && type != "Page") {
                for (kid in kids.items) walk(kid, nodeBox, nodeRotate, nodeResources, depth + 1)
            } else {
                out.add(PdfPage(out.size, node, nodeBox ?: doubleArrayOf(0.0, 0.0, 612.0, 792.0), ((nodeRotate % 360) + 360) % 360, nodeResources))
            }
        }
        val root = trailers.asReversed().firstNotNullOfOrNull { t -> dict(t["Root"])?.let { it["Pages"] } }
        if (root != null) walk(root, null, 0, null, 0)
        if (out.isEmpty()) {
            // No usable page tree: fall back to page objects in file order.
            for (num in offsets.keys.sorted()) {
                if (out.size >= limit) break
                if (!containsNear(offsets[num]!!, "/Page")) continue
                val d = dict(PdfValue.Ref(num, 0)) ?: continue
                if ((d["Type"] as? PdfValue.Name)?.value == "Page") walk(PdfValue.Ref(num, 0), null, 0, null, 0)
            }
        }
        return out
    }

    /** Total page count from the page tree (/Count), or the number of pages found. */
    fun pageCount(): Int {
        val count = rootDict()?.let { dict(it["Pages"]) }?.let { (resolve(it["Count"]) as? PdfValue.Num)?.value?.toInt() }
        return count?.takeIf { it > 0 } ?: pages().size
    }

    private fun boxOf(v: PdfValue?): DoubleArray? {
        val arr = resolve(v) as? PdfValue.Arr ?: return null
        val nums = arr.items.mapNotNull { (resolve(it) as? PdfValue.Num)?.value }
        if (nums.size != 4) return null
        val box = doubleArrayOf(minOf(nums[0], nums[2]), minOf(nums[1], nums[3]), maxOf(nums[0], nums[2]), maxOf(nums[1], nums[3]))
        return if (box[2] - box[0] >= 1 && box[3] - box[1] >= 1) box else null
    }

    /** Decoded content stream(s) of [page], concatenated, or null when none could be decoded. */
    fun contents(page: PdfPage): ContentData {
        val parts = when (val c = resolve(page.dict["Contents"])) {
            is PdfValue.Stream -> listOf(c)
            is PdfValue.Arr -> c.items.mapNotNull { resolve(it) as? PdfValue.Stream }
            else -> emptyList()
        }
        val out = ByteArrayOutputStream()
        var unsupported = false
        for (p in parts) {
            val data = decode(p)
            if (data == null) unsupported = true else { out.write(data); out.write(10) }
        }
        return ContentData(out.toByteArray(), unsupported)
    }

    class ContentData(val bytes: ByteArray, val unsupportedFilter: Boolean)
}
