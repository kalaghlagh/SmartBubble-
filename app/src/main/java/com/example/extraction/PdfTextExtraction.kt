package com.example.extraction

import com.example.template.pdf.PdfContentScanner
import com.example.template.pdf.PdfDocumentReader
import com.example.template.pdf.PdfPageScan
import java.text.Normalizer
import kotlin.math.abs

/** How one PDF page should be read. */
enum class PdfPageKind {
    /** The page carries its text: read it directly (no OCR). */
    TEXT,
    /** The page is an image (a scan): render it and run OCR. */
    SCANNED
}

/**
 * Text of digitally generated PDF pages, read directly from the file (positions, fonts, bold and
 * italic, drawn borders) and handed to the same layout analysis as OCR results. Scanned pages
 * are recognized as such so that only they are rendered and sent to OCR.
 */
object PdfTextExtraction {

    /** Letters a page needs, read from its text, to be treated as a text page. */
    const val MIN_LETTERS = 20

    fun classify(scan: PdfPageScan): PdfPageKind {
        val letters = scan.texts.sumOf { r -> r.text.count { it.isLetter() } }
        val area = scan.width * scan.height
        val imageArea = scan.images.sumOf { it.area }
        // A page covered by an image with almost no readable text is a scan; pages whose text
        // cannot be decoded (fonts without a character map) are read by OCR too.
        if (letters < MIN_LETTERS) return PdfPageKind.SCANNED
        if (area > 0 && imageArea / area > 0.6 && letters < 200) return PdfPageKind.SCANNED
        return PdfPageKind.TEXT
    }

    /**
     * Arabic drawn with presentation forms (U+FB50-U+FEFF) is stored in visual order by many PDF
     * producers: it is turned back into normal letters in reading order. Text already made of
     * normal Arabic letters is in reading order and is not touched.
     */
    fun logicalText(run: String): String {
        val presentation = run.any { it in 'ﭐ'..'﷿' || it in 'ﹰ'..'\uFEFF' }
        val normalized = Normalizer.normalize(run, Normalizer.Form.NFKC)
        return if (presentation && ScriptDetector.count(normalized).arabic > 0) reverseVisual(normalized) else normalized
    }

    /** Reverses visual-order text while keeping runs of digits and Latin letters readable. */
    private fun reverseVisual(s: String): String {
        val parts = Regex("""[0-9A-Za-z.,%/:\-]+|.""").findAll(s).map { it.value }.toList()
        return parts.reversed().joinToString("")
    }

    /** Helvetica advance widths (1/1000 em) of ASCII characters, for runs whose font has no widths. */
    private val HELVETICA: Map<Char, Int> = run {
        val m = HashMap<Char, Int>()
        val table = " 278!278\"355#556$556%889&667'191(333)333*389+584,278-333.278/278:278;278<584=584>584?556@1015[278\\278]278^469_556`333"
        var i = 0
        while (i < table.length) {
            val c = table[i]; i++
            val start = i
            while (i < table.length && table[i].isDigit()) i++
            m[c] = table.substring(start, i).toInt()
        }
        for (c in '0'..'9') m[c] = 556
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toList().zip(listOf(667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611)).forEach { (c, w) -> m[c] = w }
        "abcdefghijklmnopqrstuvwxyz".toList().zip(listOf(556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500)).forEach { (c, w) -> m[c] = w }
        m
    }

    /**
     * Width of [text] at [fontSize] when the font gave none (the reader then assumes 1000 units
     * per glyph, as wide as the font size, and words could not be told apart): standard Latin
     * metrics, an average width for other characters.
     */
    private fun estimatedWidth(text: String, fontSize: Double): Double = text.sumOf { ch ->
        val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull() ?: ch
        (HELVETICA[base] ?: 556).toDouble()
    } * fontSize / 1000.0

    /** A piece of a run between spaces, in page coordinates (y downwards). */
    private class Piece(val text: String, val box: Box, val run: com.example.template.pdf.PdfTextRun, val spaceBefore: Boolean)

    /**
     * Words of the page, y downwards. Producers draw text in runs of any size, often one glyph
     * per run (Android's PdfDocument does): pieces on the same baseline that touch are joined
     * into one word, a space or a gap wider than a fraction of the font size separates words.
     */
    fun words(scan: PdfPageScan): List<TextWord> {
        val pieces = ArrayList<Piece>()
        for (r in scan.texts) {
            val text = logicalText(r.text)
            if (text.isBlank()) continue
            val top = (scan.height - r.y - r.fontSize * 0.8).toFloat()
            val bottom = (scan.height - r.y + r.fontSize * 0.2).toFloat()
            val total = text.length.coerceAtLeast(1)
            val rtl = ScriptDetector.isRtl(text)
            // Widths of 0.95 em per glyph or more mean the font had no widths (1000 units assumed).
            val runWidth = if (!rtl && r.width >= r.fontSize * 0.95 * text.count { !it.isWhitespace() }) estimatedWidth(text, r.fontSize) else r.width
            // Segments between spaces with their character positions (visual left-to-right for boxes).
            var i = 0
            while (i < text.length) {
                if (text[i].isWhitespace()) { i++; continue }
                val start = i
                while (i < text.length && !text[i].isWhitespace()) i++
                val (a, b) = if (rtl) (total - i) to (total - start) else start to i
                val x0 = r.x + runWidth * a / total
                val x1 = r.x + runWidth * b / total
                pieces += Piece(text.substring(start, i), Box(x0.toFloat(), top, x1.toFloat(), bottom), r, spaceBefore = start > 0)
            }
        }
        // Same baseline, left to right; touching pieces form one word.
        val lines = ArrayList<MutableList<Piece>>()
        for (p in pieces.sortedWith(compareBy({ it.box.bottom }, { it.box.left }))) {
            val l = lines.lastOrNull()
            if (l != null && kotlin.math.abs(l.last().box.bottom - p.box.bottom) < p.box.height * 0.5f) l += p else lines += mutableListOf(p)
        }
        val out = ArrayList<TextWord>()
        for (line in lines) {
            val sorted = line.sortedBy { it.box.left }
            val rtl = ScriptDetector.isRtl(sorted.joinToString("") { it.text })
            var cur: Piece? = null
            var text = StringBuilder()
            var box: Box? = null
            fun emit() {
                val c = cur ?: return
                out += TextWord(text.toString(), box!!, null, c.run.bold, c.run.italic, c.run.fontSize.toFloat())
            }
            for (p in sorted) {
                val c = cur
                val gap = if (c == null) Float.MAX_VALUE else p.box.left - box!!.right
                val join = c != null && !p.spaceBefore && gap < (p.run.fontSize * 0.22).toFloat()
                if (join) {
                    // Visual order is reading order for Latin; for Arabic the left piece comes last.
                    if (rtl && ScriptDetector.count(p.text).arabic > 0) text.insert(0, p.text) else text.append(p.text)
                    box = box!!.union(p.box)
                } else {
                    emit()
                    cur = p; text = StringBuilder(p.text); box = p.box
                }
                cur = cur ?: p
            }
            emit()
        }
        return out
    }

    /** Words grouped into lines (same baseline), ordered right to left for Arabic lines. */
    fun lines(words: List<TextWord>): List<TextLine> {
        val sorted = words.sortedWith(compareBy({ it.box.bottom }, { it.box.left }))
        val groups = ArrayList<MutableList<TextWord>>()
        for (w in sorted) {
            val g = groups.lastOrNull()
            if (g != null && abs(g.last().box.bottom - w.box.bottom) < w.box.height * 0.5f) g += w else groups += mutableListOf(w)
        }
        return groups.map { g ->
            val byX = g.sortedBy { it.box.left }
            // A line split by a wide gap into a left and right part stays one line (table rows).
            val rtl = ScriptDetector.isRtl(g.joinToString(" ") { it.text })
            val ordered = if (rtl) byX.reversed() else byX
            TextLine(ordered, Box.of(g.map { it.box })!!, source = "pdf")
        }
    }

    /** Drawn borders (stroked lines and thin filled rectangles), y downwards. */
    fun rulings(scan: PdfPageScan): List<Ruling> {
        val out = ArrayList<Ruling>()
        val h = scan.height.toFloat()
        for (l in scan.lines) {
            if (l.horizontal && l.length > 8) out += Ruling(true, h - l.y1.toFloat(), minOf(l.x1, l.x2).toFloat(), maxOf(l.x1, l.x2).toFloat())
            else if (l.vertical && l.length > 8) out += Ruling(false, l.x1.toFloat(), h - maxOf(l.y1, l.y2).toFloat(), h - minOf(l.y1, l.y2).toFloat())
        }
        for (r in scan.rects) {
            if (r.h <= 2.0 && r.w > 8) out += Ruling(true, (h - r.y - r.h / 2).toFloat(), r.x.toFloat(), (r.x + r.w).toFloat())
            else if (r.w <= 2.0 && r.h > 8) out += Ruling(false, (r.x + r.w / 2).toFloat(), (h - r.y - r.h).toFloat(), (h - r.y).toFloat())
        }
        return TableReconstructor.mergeRulings(out, 1.5f)
    }

    /** One text page reconstructed: structure plus bold/italic and alignment from the file. */
    fun page(index: Int, scan: PdfPageScan): ExtractedPage {
        val lines = lines(words(scan))
        val blocks = LayoutAnalyzer.analyze(lines, scan.width.toFloat(), scan.height.toFloat(), rulings(scan))
        val notes = ArrayList<String>()
        if (scan.incomplete) notes += "pdf_incomplete"
        if (blocks.any { it is ExtractedBlock.Table && it.uncertain }) notes += "table_uncertain"
        return ExtractedPage(
            index = index,
            source = PageSource.PDF_TEXT,
            blocks = blocks,
            scripts = ScriptDetector.scriptsOf(lines.map { it.text }),
            level = if (blocks.isEmpty()) ReconstructionLevel.NONE else ReconstructionLevel.FORMATTING,
            confidence = null,
            notes = notes
        )
    }

    /** Pages of a PDF: read directly when they carry text, or marked for OCR (null) when scanned. */
    fun readPages(reader: PdfDocumentReader, maxPages: Int = 200): List<Pair<Int, ExtractedPage?>> {
        val scanner = PdfContentScanner(reader)
        return reader.pages(maxPages).map { p ->
            val scan = scanner.scan(p)
            p.index to (if (classify(scan) == PdfPageKind.TEXT) page(p.index, scan) else null)
        }
    }
}
