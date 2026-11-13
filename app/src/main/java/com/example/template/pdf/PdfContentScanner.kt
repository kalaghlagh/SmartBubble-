package com.example.template.pdf

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A piece of text drawn on a page, in page coordinates (origin bottom-left, points). */
data class PdfTextRun(
    val x: Double,
    /** Baseline. */
    val y: Double,
    val width: Double,
    val fontSize: Double,
    val fontFamily: String,
    val bold: Boolean,
    val italic: Boolean,
    val colorHex: String,
    /** Decoded text when the font allows it ("" otherwise); [glyphs] is always the glyph count. */
    val text: String,
    val glyphs: Int
) {
    val right: Double get() = x + width
}

/** A filled rectangle (backgrounds, color bands, table cell fills). */
data class PdfRect(val x: Double, val y: Double, val w: Double, val h: Double, val colorHex: String) {
    val area: Double get() = w * h
}

/** A stroked line segment (rules, table borders). */
data class PdfLine(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val colorHex: String, val width: Double) {
    val horizontal: Boolean get() = abs(y1 - y2) < 0.5
    val vertical: Boolean get() = abs(x1 - x2) < 0.5
    val length: Double get() = hypot(x2 - x1, y2 - y1)
}

/** What was drawn on one page. */
class PdfPageScan(
    val width: Double,
    val height: Double,
    val texts: List<PdfTextRun>,
    val rects: List<PdfRect>,
    val lines: List<PdfLine>,
    /** Bounding boxes of drawn images. */
    val images: List<PdfRect>,
    /** Some content could not be decoded (unsupported compression). */
    val incomplete: Boolean
)

/** A font as far as layout analysis needs it. */
internal class PdfFontInfo(
    val family: String,
    val bold: Boolean,
    val italic: Boolean,
    val twoByte: Boolean,
    private val toUnicode: Map<Int, String>,
    private val widths: Map<Int, Double>,
    private val defaultWidth: Double
) {
    fun codes(bytes: ByteArray): IntArray =
        if (twoByte) IntArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 0xFF) shl 8) or (bytes[2 * it + 1].toInt() and 0xFF) }
        else IntArray(bytes.size) { bytes[it].toInt() and 0xFF }

    fun text(code: Int): String = toUnicode[code] ?: if (!twoByte && toUnicode.isEmpty()) winAnsi(code) else ""

    /** Glyph width in thousandths of the font size. */
    fun width(code: Int): Double = widths[code] ?: defaultWidth

    companion object {
        /** Splits "ABCDEF+Calibri-BoldItalic" into the family and style. */
        fun parseName(baseFont: String): Triple<String, Boolean, Boolean> {
            val name = baseFont.substringAfter('+')
            val lower = name.lowercase()
            val bold = listOf("bold", "black", "heavy", "semibold", "demi").any { it in lower }
            val italic = "italic" in lower || "oblique" in lower
            var family = name.split('-', ',').first()
            // "TimesNewRomanPSMT" -> "Times New Roman"; "ArialMT" -> "Arial"
            family = family.removeSuffix("PSMT").removeSuffix("MT").removeSuffix("PS")
            family = family.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").trim()
            return Triple(family.ifEmpty { "Unknown" }, bold, italic)
        }

        private val WIN_ANSI_HIGH = mapOf(
            0x80 to "€", 0x82 to "‚", 0x84 to "„", 0x85 to "…", 0x91 to "‘", 0x92 to "’", 0x93 to "“",
            0x94 to "”", 0x95 to "•", 0x96 to "–", 0x97 to "—", 0x99 to "™"
        )

        fun winAnsi(code: Int): String = when {
            code in 0x20..0x7E -> code.toChar().toString()
            code >= 0xA0 -> code.toChar().toString()
            else -> WIN_ANSI_HIGH[code] ?: ""
        }
    }
}

/**
 * Interprets page content streams (text, colors, paths, images, form XObjects) to record where
 * and how text and shapes are drawn. Nothing is rendered; the work per page is bounded.
 */
class PdfContentScanner(private val reader: PdfDocumentReader) {

    private class Matrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
        fun times(o: Matrix) = Matrix(
            a * o.a + b * o.c, a * o.b + b * o.d,
            c * o.a + d * o.c, c * o.b + d * o.d,
            e * o.a + f * o.c + o.e, e * o.b + f * o.d + o.f
        )
        fun x(px: Double, py: Double) = a * px + c * py + e
        fun y(px: Double, py: Double) = b * px + d * py + f
        val scale: Double get() = sqrt(abs(a * d - b * c)).coerceAtLeast(1e-6)

        companion object {
            val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
    }

    private class GState(
        var ctm: Matrix = Matrix.IDENTITY,
        var fill: String = "#000000",
        var stroke: String = "#000000",
        var lineWidth: Double = 1.0,
        var font: PdfFontInfo? = null,
        var fontSize: Double = 12.0,
        var charSpacing: Double = 0.0,
        var wordSpacing: Double = 0.0,
        var hScale: Double = 1.0,
        var leading: Double = 0.0,
        var rise: Double = 0.0
    ) {
        fun copy() = GState(ctm, fill, stroke, lineWidth, font, fontSize, charSpacing, wordSpacing, hScale, leading, rise)
    }

    private val fontCache = HashMap<PdfValue.Dict, PdfFontInfo>()

    fun scan(page: PdfPage): PdfPageScan {
        val texts = ArrayList<PdfTextRun>()
        val rects = ArrayList<PdfRect>()
        val lines = ArrayList<PdfLine>()
        val images = ArrayList<PdfRect>()
        var incomplete = false
        // Page coordinates relative to the box origin.
        val base = Matrix(1.0, 0.0, 0.0, 1.0, -page.box[0], -page.box[1])
        val budget = intArrayOf(PdfLimits.MAX_OPERATIONS_PER_PAGE)
        try {
            val content = reader.contents(page)
            incomplete = content.unsupportedFilter
            run(content.bytes, page.resources, GState(ctm = base), texts, rects, lines, images, budget, 0)
        } catch (e: PdfReadException) {
            incomplete = true
        }
        var w = page.width
        var h = page.height
        if (page.rotate == 90 || page.rotate == 270) { val t = w; w = h; h = t }
        return PdfPageScan(w, h, texts, rects, lines, images, incomplete)
    }

    private fun run(
        bytes: ByteArray,
        resources: PdfValue.Dict?,
        initial: GState,
        texts: MutableList<PdfTextRun>,
        rects: MutableList<PdfRect>,
        lines: MutableList<PdfLine>,
        images: MutableList<PdfRect>,
        budget: IntArray,
        depth: Int
    ) {
        if (depth > 4) return
        val lexer = PdfLexer(ArrayByteSource(bytes))
        val operands = ArrayList<PdfValue>()
        val stack = ArrayList<GState>()
        var gs = initial.copy()
        var tm = Matrix.IDENTITY
        var tlm = Matrix.IDENTITY
        // Current path: rectangles and line segments, painted (or discarded) by the next paint operator.
        val pathRects = ArrayList<DoubleArray>()
        val pathLines = ArrayList<DoubleArray>()
        var cx = 0.0
        var cy = 0.0

        fun num(i: Int): Double = (operands.getOrNull(i) as? PdfValue.Num)?.value ?: 0.0

        fun color(): String? {
            val nums = operands.mapNotNull { (it as? PdfValue.Num)?.value }
            return when (nums.size) {
                1 -> gray(nums[0])
                3 -> rgb(nums[0], nums[1], nums[2])
                4 -> cmyk(nums[0], nums[1], nums[2], nums[3])
                else -> null
            }
        }

        fun showText(bytes: ByteArray) {
            val font = gs.font ?: return
            val codes = font.codes(bytes)
            if (codes.isEmpty()) return
            val sb = StringBuilder()
            var advance = 0.0
            for (code in codes) {
                sb.append(font.text(code))
                var w = font.width(code) / 1000.0 * gs.fontSize + gs.charSpacing
                if (!font.twoByte && code == 32) w += gs.wordSpacing
                advance += w * gs.hScale
            }
            val m = tm.times(gs.ctm)
            val x = m.x(0.0, gs.rise)
            val y = m.y(0.0, gs.rise)
            val endX = m.x(advance, gs.rise)
            val size = gs.fontSize * m.scale
            texts.add(
                PdfTextRun(
                    x = min(x, endX), y = y, width = abs(endX - x), fontSize = size,
                    fontFamily = font.family, bold = font.bold, italic = font.italic, colorHex = gs.fill,
                    text = sb.toString(), glyphs = codes.size
                )
            )
            tm = Matrix(1.0, 0.0, 0.0, 1.0, advance, 0.0).times(tm)
        }

        fun paint(fill: Boolean, stroke: Boolean) {
            val ctm = gs.ctm
            for (r in pathRects) {
                val x1 = ctm.x(r[0], r[1]); val y1 = ctm.y(r[0], r[1])
                val x2 = ctm.x(r[0] + r[2], r[1] + r[3]); val y2 = ctm.y(r[0] + r[2], r[1] + r[3])
                val rx = min(x1, x2); val ry = min(y1, y2); val rw = abs(x2 - x1); val rh = abs(y2 - y1)
                if (fill) rects.add(PdfRect(rx, ry, rw, rh, gs.fill))
                if (stroke) {
                    val lw = gs.lineWidth * ctm.scale
                    lines.add(PdfLine(rx, ry, rx + rw, ry, gs.stroke, lw))
                    lines.add(PdfLine(rx, ry + rh, rx + rw, ry + rh, gs.stroke, lw))
                    lines.add(PdfLine(rx, ry, rx, ry + rh, gs.stroke, lw))
                    lines.add(PdfLine(rx + rw, ry, rx + rw, ry + rh, gs.stroke, lw))
                }
            }
            if (stroke) {
                for (l in pathLines) {
                    lines.add(PdfLine(ctm.x(l[0], l[1]), ctm.y(l[0], l[1]), ctm.x(l[2], l[3]), ctm.y(l[2], l[3]), gs.stroke, gs.lineWidth * ctm.scale))
                }
            }
            pathRects.clear()
            pathLines.clear()
        }

        while (true) {
            val token = lexer.next(allowRefs = false) ?: break
            if (token !is PdfValue.Op) {
                operands.add(token)
                if (operands.size > 64) operands.removeAt(0)
                continue
            }
            if (--budget[0] < 0) throw PdfReadException("Too many drawing operations")
            when (token.name) {
                "q" -> { stack.add(gs.copy()); if (stack.size > 64) stack.removeAt(0) }
                "Q" -> if (stack.isNotEmpty()) gs = stack.removeAt(stack.lastIndex)
                "cm" -> gs.ctm = Matrix(num(0), num(1), num(2), num(3), num(4), num(5)).times(gs.ctm)
                "w" -> gs.lineWidth = num(0)
                "rg", "g", "k", "sc", "scn" -> color()?.let { gs.fill = it }
                "RG", "G", "K", "SC", "SCN" -> color()?.let { gs.stroke = it }
                "re" -> pathRects.add(doubleArrayOf(num(0), num(1), num(2), num(3)))
                "m" -> { cx = num(0); cy = num(1) }
                "l" -> { pathLines.add(doubleArrayOf(cx, cy, num(0), num(1))); cx = num(0); cy = num(1) }
                "c", "v", "y", "h" -> Unit
                "f", "F", "f*" -> paint(fill = true, stroke = false)
                "S", "s" -> paint(fill = false, stroke = true)
                "B", "B*", "b", "b*" -> paint(fill = true, stroke = true)
                "n" -> { pathRects.clear(); pathLines.clear() }
                "BT" -> { tm = Matrix.IDENTITY; tlm = Matrix.IDENTITY }
                "ET" -> Unit
                "Tf" -> {
                    val name = (operands.getOrNull(0) as? PdfValue.Name)?.value
                    gs.font = name?.let { font(resources, it) }
                    gs.fontSize = num(1)
                }
                "Tc" -> gs.charSpacing = num(0)
                "Tw" -> gs.wordSpacing = num(0)
                "Tz" -> gs.hScale = num(0) / 100.0
                "TL" -> gs.leading = num(0)
                "Ts" -> gs.rise = num(0)
                "Td" -> { tlm = Matrix(1.0, 0.0, 0.0, 1.0, num(0), num(1)).times(tlm); tm = tlm }
                "TD" -> { gs.leading = -num(1); tlm = Matrix(1.0, 0.0, 0.0, 1.0, num(0), num(1)).times(tlm); tm = tlm }
                "Tm" -> { tlm = Matrix(num(0), num(1), num(2), num(3), num(4), num(5)); tm = tlm }
                "T*" -> { tlm = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, -gs.leading).times(tlm); tm = tlm }
                "Tj" -> (operands.lastOrNull() as? PdfValue.Str)?.let { showText(it.bytes) }
                "'" -> {
                    tlm = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, -gs.leading).times(tlm); tm = tlm
                    (operands.lastOrNull() as? PdfValue.Str)?.let { showText(it.bytes) }
                }
                "\"" -> {
                    gs.wordSpacing = num(0); gs.charSpacing = num(1)
                    tlm = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, -gs.leading).times(tlm); tm = tlm
                    (operands.lastOrNull() as? PdfValue.Str)?.let { showText(it.bytes) }
                }
                "TJ" -> (operands.lastOrNull() as? PdfValue.Arr)?.let { arr ->
                    // Glyph runs and kerning of one text array form one run.
                    val joined = java.io.ByteArrayOutputStream()
                    var kern = 0.0
                    for (item in arr.items) {
                        when (item) {
                            is PdfValue.Str -> joined.write(item.bytes)
                            is PdfValue.Num -> {
                                kern += item.value
                                // A large gap is a word space in fonts that do not draw spaces.
                                if (item.value < -200 && gs.font?.twoByte == false) joined.write(32)
                            }
                            else -> Unit
                        }
                    }
                    val start = tm
                    showText(joined.toByteArray())
                    if (gs.font != null) {
                        tm = Matrix(1.0, 0.0, 0.0, 1.0, -kern / 1000.0 * gs.fontSize * gs.hScale, 0.0).times(tm)
                    } else tm = start
                }
                "Do" -> (operands.lastOrNull() as? PdfValue.Name)?.let { name ->
                    val xobjects = reader.dict(resources?.get("XObject"))
                    val ref = xobjects?.get(name.value)
                    val xdict = reader.dict(ref)
                    when ((xdict?.get("Subtype") as? PdfValue.Name)?.value) {
                        "Image" -> {
                            val m = gs.ctm
                            val xs = listOf(m.x(0.0, 0.0), m.x(1.0, 0.0), m.x(0.0, 1.0), m.x(1.0, 1.0))
                            val ys = listOf(m.y(0.0, 0.0), m.y(1.0, 0.0), m.y(0.0, 1.0), m.y(1.0, 1.0))
                            images.add(PdfRect(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min(), ""))
                        }
                        "Form" -> {
                            val stream = reader.resolve(ref) as? PdfValue.Stream
                            val data = stream?.let { reader.decode(it) }
                            if (data != null) {
                                val mArr = (reader.resolve(xdict["Matrix"]) as? PdfValue.Arr)?.items?.mapNotNull { (it as? PdfValue.Num)?.value }
                                val formMatrix = if (mArr != null && mArr.size == 6) Matrix(mArr[0], mArr[1], mArr[2], mArr[3], mArr[4], mArr[5]) else Matrix.IDENTITY
                                val inner = gs.copy().also { it.ctm = formMatrix.times(gs.ctm) }
                                val formResources = reader.dict(xdict["Resources"]) ?: resources
                                run(data, formResources, inner, texts, rects, lines, images, budget, depth + 1)
                            }
                        }
                    }
                }
                "BI" -> {
                    // Inline image: skip its dictionary and binary data.
                    while (true) {
                        val t = lexer.next(allowRefs = false) ?: break
                        if (t is PdfValue.Op && t.name == "ID") break
                    }
                    lexer.skipInlineImage()
                    val m = gs.ctm
                    images.add(PdfRect(min(m.e, m.e + m.a), min(m.f, m.f + m.d), abs(m.a), abs(m.d), ""))
                }
            }
            operands.clear()
        }
    }

    private fun font(resources: PdfValue.Dict?, name: String): PdfFontInfo? {
        val fonts = reader.dict(resources?.get("Font")) ?: return null
        val dict = reader.dict(fonts[name]) ?: return null
        fontCache[dict]?.let { return it }
        val subtype = (dict["Subtype"] as? PdfValue.Name)?.value
        val twoByte = subtype == "Type0"
        val descendant = if (twoByte) (reader.resolve(dict["DescendantFonts"]) as? PdfValue.Arr)?.items?.firstOrNull()?.let { reader.dict(it) } else null
        val baseFont = (dict["BaseFont"] as? PdfValue.Name)?.value
            ?: (descendant?.get("BaseFont") as? PdfValue.Name)?.value ?: "Unknown"
        val (family, boldName, italicName) = PdfFontInfo.parseName(baseFont)
        val descriptor = reader.dict(dict["FontDescriptor"] ?: descendant?.get("FontDescriptor"))
        val flags = (reader.resolve(descriptor?.get("Flags")) as? PdfValue.Num)?.value?.toInt() ?: 0
        val weight = (reader.resolve(descriptor?.get("FontWeight")) as? PdfValue.Num)?.value ?: 0.0
        val bold = boldName || weight >= 600 || (flags and (1 shl 18)) != 0
        val italic = italicName || (flags and (1 shl 6)) != 0

        val widths = HashMap<Int, Double>()
        var defaultWidth = 500.0
        if (twoByte) {
            defaultWidth = (reader.resolve(descendant?.get("DW")) as? PdfValue.Num)?.value ?: 1000.0
            val w = (reader.resolve(descendant?.get("W")) as? PdfValue.Arr)?.items?.map { reader.resolve(it) } ?: emptyList()
            var i = 0
            while (i < w.size && widths.size < 70_000) {
                val first = (w[i] as? PdfValue.Num)?.value?.toInt() ?: break
                val next = w.getOrNull(i + 1)
                if (next is PdfValue.Arr) {
                    next.items.forEachIndexed { k, v -> (reader.resolve(v) as? PdfValue.Num)?.let { widths[first + k] = it.value } }
                    i += 2
                } else {
                    val last = (next as? PdfValue.Num)?.value?.toInt() ?: break
                    val value = (w.getOrNull(i + 2) as? PdfValue.Num)?.value ?: break
                    if (last - first in 0..70_000) for (c in first..last) widths[c] = value
                    i += 3
                }
            }
        } else {
            val firstChar = (reader.resolve(dict["FirstChar"]) as? PdfValue.Num)?.value?.toInt() ?: 0
            (reader.resolve(dict["Widths"]) as? PdfValue.Arr)?.items?.forEachIndexed { k, v ->
                (reader.resolve(v) as? PdfValue.Num)?.let { widths[firstChar + k] = it.value }
            }
            (reader.resolve(descriptor?.get("MissingWidth")) as? PdfValue.Num)?.let { defaultWidth = it.value }
            if (widths.isEmpty()) defaultWidth = 520.0 // standard 14 fonts: average Helvetica width
        }
        val toUnicode = (reader.resolve(dict["ToUnicode"]) as? PdfValue.Stream)?.let { s ->
            try { reader.decode(s)?.let { ToUnicodeCMap.parse(it) } } catch (_: PdfReadException) { null }
        } ?: emptyMap()
        return PdfFontInfo(family, bold, italic, twoByte, toUnicode, widths, defaultWidth).also { fontCache[dict] = it }
    }

    companion object {
        private fun hex(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        fun rgb(r: Double, g: Double, b: Double) = String.format(java.util.Locale.ROOT, "#%02X%02X%02X", hex(r), hex(g), hex(b))
        fun gray(v: Double) = rgb(v, v, v)
        fun cmyk(c: Double, m: Double, y: Double, k: Double) = rgb((1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k))
    }
}

/** Parser of ToUnicode CMaps (bfchar and bfrange) used to read the text of embedded fonts. */
object ToUnicodeCMap {
    fun parse(data: ByteArray): Map<Int, String> {
        val map = HashMap<Int, String>()
        val lexer = PdfLexer(ArrayByteSource(data))
        val tokens = ArrayList<PdfValue>()
        var mode = ""
        fun code(v: PdfValue?): Int? = (v as? PdfValue.Str)?.bytes?.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        fun unicode(v: PdfValue?): String? = (v as? PdfValue.Str)?.bytes?.let { String(it, Charsets.UTF_16BE) }
        while (true) {
            val t = try { lexer.next(allowRefs = false) } catch (_: PdfReadException) { null } ?: break
            if (t is PdfValue.Op) {
                when (t.name) {
                    "beginbfchar" -> { mode = "char"; tokens.clear() }
                    "beginbfrange" -> { mode = "range"; tokens.clear() }
                    "endbfchar" -> {
                        var i = 0
                        while (i + 1 < tokens.size) {
                            val c = code(tokens[i]); val u = unicode(tokens[i + 1])
                            if (c != null && u != null) map[c] = u
                            i += 2
                        }
                        mode = ""
                    }
                    "endbfrange" -> {
                        var i = 0
                        while (i + 2 < tokens.size) {
                            val lo = code(tokens[i]); val hi = code(tokens[i + 1]); val dst = tokens[i + 2]
                            if (lo != null && hi != null && hi >= lo && hi - lo < 65_536) {
                                when (dst) {
                                    is PdfValue.Str -> {
                                        val base = String(dst.bytes, Charsets.UTF_16BE)
                                        if (base.isNotEmpty()) {
                                            for (c in lo..hi) map[c] = base.dropLast(1) + (base.last() + (c - lo))
                                        }
                                    }
                                    is PdfValue.Arr -> dst.items.forEachIndexed { k, v -> unicode(v)?.let { map[lo + k] = it } }
                                    else -> Unit
                                }
                            }
                            i += 3
                        }
                        mode = ""
                    }
                }
                if (map.size > 100_000) break
                continue
            }
            if (mode.isNotEmpty()) tokens.add(t)
        }
        return map
    }
}
