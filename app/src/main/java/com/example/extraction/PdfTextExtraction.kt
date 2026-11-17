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
        val presentation = run.any { it in 'ﭐ'..'﷿' || it in 'ﹰ'..'﻿' }
        val normalized = Normalizer.normalize(run, Normalizer.Form.NFKC)
        return if (presentation && ScriptDetector.count(normalized).arabic > 0) reverseVisual(normalized) else normalized
    }

    /** Reverses visual-order text while keeping runs of digits and Latin letters readable. */
    private fun reverseVisual(s: String): String {
        val parts = Regex("""[0-9A-Za-z.,%/:\-]+|.""").findAll(s).map { it.value }.toList()
        return parts.reversed().joinToString("")
    }

    /** Words of the runs, in page coordinates with y downwards. */
    fun words(scan: PdfPageScan): List<TextWord> {
        val out = ArrayList<TextWord>()
        for (r in scan.texts) {
            val text = logicalText(r.text)
            if (text.isBlank()) continue
            val top = (scan.height - r.y - r.fontSize * 0.8).toFloat()
            val bottom = (scan.height - r.y + r.fontSize * 0.2).toFloat()
            val pieces = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (pieces.isEmpty()) continue
            // Each word gets its share of the run's width (by characters).
            val total = text.length.coerceAtLeast(1)
            var x = r.x
            val rtl = ScriptDetector.isRtl(text)
            val ordered = if (rtl) pieces.reversed() else pieces // visual left-to-right order for boxes
            for (p in ordered) {
                val w = r.width * (p.length + 1) / total
                out += TextWord(p, Box(x.toFloat(), top, (x + w).toFloat(), bottom), null, r.bold, r.italic, r.fontSize.toFloat())
                x += w
            }
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
