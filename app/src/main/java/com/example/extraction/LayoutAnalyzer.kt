package com.example.extraction

import kotlin.math.abs

/**
 * Rebuilds the structure of a page from its text lines: tables (with or without borders),
 * headings, numbered and bulleted lists with their original markers, captions, page numbers
 * and paragraphs, in reading order, each with its direction. Nothing is renumbered or reworded.
 */
object LayoutAnalyzer {

    private val JUNK = Regex("""^[|\[\]_—–\-=~.,:;'"`]{1,3}$""")
    private val PAGE_NUMBER = Regex("""^[-–—(]?\s*([0-9]{1,4}|[٠-٩]{1,4}|[ivxlcIVXLC]{1,6})\s*[-–—)]?$""")
    private val CAPTION = Regex("""^(figure|fig\.|tableau|table|graphique|schéma|schema|carte|map|chart|الشكل|شكل|الجدول|جدول|المخطط|الخريطة)\s*(n°|no\.?|رقم)?\s*[0-9٠-٩]+""", RegexOption.IGNORE_CASE)
    /** List markers, kept exactly as written. */
    private val LIST_MARKER = Regex("""^([•●▪◦○■□➢►✓✔\-–*·]|[0-9]{1,3}[.)\-]|[٠-٩]{1,3}[.)\-]|[a-zA-Z][.)]|[ivxIVX]{1,4}[.)]|[أ-ي][.)\-])\s+(.+)$""")
    /** "1.2 Title", "3. Title", "2 Title" (not "1)" which is a list item). */
    private val HEADING_NUMBER = Regex("""^((?:[0-9]+|[٠-٩]+)(?:[.][0-9٠-٩]+)*)\.?\s+\S""")
    // Word ends are written out: \b only knows ASCII letters, not Arabic ones.
    private val HEADING_WORD = Regex("""^(chapitre|chapter|partie|part|section|annexe|appendix|introduction|conclusion|résumé|abstract|الفصل|المبحث|المطلب|الباب|الجزء|المحور|مقدمة|خاتمة|ملخص)(?=$|[\s:.\-–])""", RegexOption.IGNORE_CASE)

    /** Region of a block made of [lines] (first line index [first] makes the id). */
    private fun region(lines: List<TextLine>, first: Int): RegionMeta {
        val text = lines.joinToString(" ") { it.text }
        val confs = lines.flatMap { it.words }.mapNotNull { it.confidence }
        return RegionMeta(
            id = "r$first",
            box = Box.of(lines.map { it.box }),
            script = ScriptDetector.classify(text),
            confidence = confs.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            needsReview = lines.any { it.needsReview },
            engines = lines.flatMap { it.engines }.distinct()
        )
    }

    private fun median(values: List<Float>): Float = if (values.isEmpty()) 0f else values.sorted()[values.size / 2]

    /** Drops stray symbols left by borders and specks ("|", "_", "—"). */
    fun clean(lines: List<TextLine>): List<TextLine> = lines.mapNotNull { l ->
        // A dash or star opening a line followed by words is a list marker, not a stray mark.
        val words = l.words.filterIndexed { i, w ->
            w.text.isNotBlank() && (!JUNK.matches(w.text.trim()) || (i == 0 && l.words.size > 1 && w.text.trim() in setOf("-", "–", "*")))
        }
        if (words.isEmpty()) null else l.copy(words = words, box = Box.of(words.map { it.box }) ?: l.box)
    }

    /**
     * Two columns of running text read as single lines across the gutter: each such line is
     * split at the gutter and the page is put in reading order, the whole first column then the
     * second (the right one first for Arabic). Lines of one column only next to the run (a column
     * ending lower than the other) belong to it. Table rows (short cells) are left alone.
     */
    fun splitColumns(lines: List<TextLine>): List<TextLine> {
        val segs = lines.map { TableReconstructor.segments(it) }
        fun prose(s: List<List<TextWord>>) = s.size == 2 && s.all { it.size >= 3 }
        val out = ArrayList<TextLine>()
        var i = 0
        while (i < lines.size) {
            if (!prose(segs[i])) { out += lines[i]; i++; continue }
            var gl = segs[i][0].maxOf { it.box.right }
            var gr = segs[i][1].minOf { it.box.left }
            var j = i
            while (j + 1 < lines.size && prose(segs[j + 1])) {
                val l = segs[j + 1][0].maxOf { it.box.right }
                val r = segs[j + 1][1].minOf { it.box.left }
                if (r <= gl || l >= gr) break
                gl = maxOf(gl, l); gr = minOf(gr, r); j++
            }
            if (j == i) { out += lines[i]; i++; continue }
            val left = ArrayList<List<TextWord>>()
            val right = ArrayList<List<TextWord>>()
            for (k in i..j) { left += segs[k][0]; right += segs[k][1] }
            // A column that ends lower: the next lines lying on one side of the gutter only.
            var k = j
            while (k + 1 < lines.size && lines[k + 1].box.top - lines[k].box.bottom < lines[k].box.height &&
                (lines[k + 1].box.right <= gr || lines[k + 1].box.left >= gl)) {
                k++
                if (lines[k].box.right <= gr) left += lines[k].words else right += lines[k].words
            }
            fun line(ws: List<TextWord>) = TextLine(ws, Box.of(ws.map { it.box })!!, source = lines[i].source)
            val rtl = ScriptDetector.isRtl(lines.subList(i, k + 1).joinToString(" ") { it.text })
            val first = (if (rtl) right else left).map { line(it) }.sortedBy { it.box.top }
            val second = (if (rtl) left else right).map { line(it) }.sortedBy { it.box.top }
            out += first; out += second
            i = k + 1
        }
        return out
    }

    fun analyze(
        rawLines: List<TextLine>,
        pageWidth: Float,
        pageHeight: Float,
        rulings: List<Ruling> = emptyList(),
        /** Reads the text inside a table cell on its own (OCR), or null to use the page words. */
        cellReader: CellReader? = null
    ): List<ExtractedBlock> {
        val lines = splitColumns(clean(rawLines))
        if (lines.isEmpty()) return emptyList()
        val lineH = median(lines.map { it.box.height }).coerceAtLeast(1f)
        val fontSize = median(lines.mapNotNull { it.fontSize })
        val textLeft = lines.minOf { it.box.left }
        val textRight = lines.maxOf { it.box.right }
        val textWidth = (textRight - textLeft).coerceAtLeast(1f)

        // Tables first: drawn borders, then aligned columns in the remaining lines.
        val bordered = TableReconstructor.fromRulings(rulings, lines, cellReader = cellReader)
        val used = bordered.flatMap { it.consumedLines }.toSet()
        val layout = TableReconstructor.fromLayout(lines, used, cellReader)
        val tables = (bordered + layout).sortedBy { it.region.top }
        val tableAt = HashMap<Int, ExtractedBlock.Table>()
        val consumed = HashSet<Int>()
        for (t in tables) {
            val first = t.consumedLines.minOrNull()
            if (first == null) {
                // A bordered table whose cells are empty still keeps its place.
                val after = lines.indexOfLast { it.box.bottom <= t.region.top }
                tableAt[after + 1] = t.table
            } else tableAt[first] = t.table
            consumed += t.consumedLines
        }

        val out = ArrayList<ExtractedBlock>()
        var para: MutableList<TextLine>? = null
        fun flush() {
            val p = para ?: return
            para = null
            out += paragraph(p, textLeft, textRight, pageWidth).copy(region = region(p, lines.indexOf(p.first())))
        }
        for ((i, line) in lines.withIndex()) {
            tableAt[i]?.let { flush(); out += it.copy(region = RegionMeta("t$i", tables.firstOrNull { t -> t.table === it }?.region, ScriptDetector.classify(it.rows.flatten().joinToString(" ") { c -> c.text }),
                needsReview = it.uncertain || it.cellsToReview > 0)) }
            if (i in consumed) continue
            val text = line.text
            val rtl = line.rtl
            // Page numbers in the top or bottom margin.
            if (PAGE_NUMBER.matches(text) && (line.box.top < pageHeight * 0.08f || line.box.bottom > pageHeight * 0.92f)) {
                flush(); out += ExtractedBlock.PageNumber(text); continue
            }
            if (CAPTION.containsMatchIn(text) && text.length < 160) { flush(); out += ExtractedBlock.Caption(text, rtl, region(listOf(line), i)); continue }
            val previous = lines.getOrNull(i - 1)?.takeIf { i - 1 !in consumed }
            val next = lines.getOrNull(i + 1)?.takeIf { i + 1 !in consumed }
            val gapBefore = if (previous == null) Float.MAX_VALUE else line.box.top - previous.box.bottom
            val gapAfter = if (next == null) Float.MAX_VALUE else next.box.top - line.box.bottom
            // Heading: set apart from the text around it, and larger (font size in PDFs; for OCR
            // the line box, which varies with accents and dots, so a clear margin is required).
            val alone = gapBefore > lineH * 0.9f || gapAfter > lineH * 0.9f || next == null
            val larger = if (fontSize > 0f) (line.fontSize ?: 0f) >= fontSize * 1.15f else line.box.height >= lineH * 1.4f
            // "1. Item" followed by "2. Item" is a numbered list; "1.2 Title" is always a heading.
            val number = HEADING_NUMBER.find(text)?.groupValues?.get(1)
            val nextSameStyle = next != null && Regex("""^[0-9٠-٩]+\.\s""").containsMatchIn(next.text)
            val numbered = number != null && text.length <= 70 && gapBefore > lineH * 0.6f && text.split(' ').size <= 8 &&
                (number.contains('.') || !nextSameStyle)
            val headingLike = text.length <= 90 && !text.trimEnd().let { it.endsWith(".") || it.endsWith(",") || it.endsWith(";") || it.endsWith("،") || it.endsWith("؛") } &&
                alone && (larger || line.bold || HEADING_WORD.containsMatchIn(text) ||
                    numbered ||
                    (text.count { it.isLetter() } >= 4 && text.filter { it.isLetter() }.all { it.isUpperCase() } && ScriptDetector.classify(text) == Script.LATIN))
            if (headingLike) {
                flush()
                out += ExtractedBlock.Heading(text, headingLevel(text, line, lineH, fontSize), rtl, line.bold, region(listOf(line), i))
                continue
            }
            val marker = LIST_MARKER.find(text)
            if (marker != null) {
                flush()
                val m = marker.groupValues[1]
                val indent = if (rtl) textRight - line.box.right else line.box.left - textLeft
                val level = (indent / (lineH * 1.5f)).toInt().coerceIn(0, 3)
                out += ExtractedBlock.ListItem(marker.groupValues[2].trim(), m, ordered = m.any { it.isLetterOrDigit() }, level = level, rtl = rtl, region = region(listOf(line), i))
                continue
            }
            // Paragraphs: a wide gap, a change of direction, or an indented first line starts a new one.
            val cur = para
            if (cur != null) {
                val last = cur.last()
                // Indented against the previous line (columns do not start at the page margin).
                val indented = if (rtl) last.box.right - line.box.right > lineH * 1.2f else line.box.left - last.box.left > lineH * 1.2f
                // The next column starts higher up the page.
                val newColumn = gapBefore < -lineH
                val lastShort = last.box.width < textWidth * 0.7f && last.text.trimEnd().lastOrNull()?.let { it in ".!?:؟。" } == true
                val sameDir = last.rtl == rtl
                if (gapBefore > lineH * 0.75f || !sameDir || indented || lastShort || newColumn) flush()
            }
            (para ?: mutableListOf<TextLine>().also { para = it }) += line
        }
        flush()
        tableAt[lines.size]?.let { out += it }
        return out
    }

    private fun headingLevel(text: String, line: TextLine, lineH: Float, fontSize: Float): Int {
        HEADING_NUMBER.find(text)?.let { m -> return m.groupValues[1].count { it == '.' }.plus(1).coerceIn(1, 4) }
        if (Regex("""^(chapitre|chapter|partie|part|الفصل|الباب|الجزء)(?=$|[\s:.\-–])""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return 1
        val size = line.fontSize
        val ratio = if (fontSize > 0f && size != null) size / fontSize else line.box.height / lineH
        return when {
            ratio >= 1.6f -> 1
            ratio >= 1.25f -> 2
            else -> 3
        }
    }

    private fun paragraph(lines: List<TextLine>, textLeft: Float, textRight: Float, pageWidth: Float): ExtractedBlock.Paragraph {
        val rtl = ScriptDetector.isRtl(lines.joinToString(" ") { it.text })
        val sb = StringBuilder()
        for (l in lines) {
            val t = l.text
            if (sb.isEmpty()) { sb.append(t); continue }
            // A Latin word cut by a hyphen at the end of a line is joined again.
            if (sb.endsWith("-") && sb.length > 1 && sb[sb.length - 2].isLetter() && t.firstOrNull()?.isLowerCase() == true) {
                sb.setLength(sb.length - 1); sb.append(t)
            } else sb.append(' ').append(t)
        }
        val center = (textLeft + textRight) / 2
        val centered = lines.all { abs(it.box.centerX - center) < pageWidth * 0.03f && it.box.width < (textRight - textLeft) * 0.85f }
        val endAligned = lines.size > 1 && lines.all { abs(if (rtl) it.box.left - textLeft else it.box.right - textRight) < pageWidth * 0.02f } &&
            lines.any { abs(if (rtl) it.box.right - textRight else it.box.left - textLeft) > pageWidth * 0.1f }
        val alignment = when {
            centered -> Alignment.CENTER
            endAligned -> Alignment.END
            else -> Alignment.START
        }
        val uncertain = lines.flatMap { it.words }.filter { (it.confidence ?: 1f) < 0.6f }.map { it.text }
        return ExtractedBlock.Paragraph(sb.toString(), rtl, alignment, bold = lines.all { it.bold }, italic = lines.all { it.italic }, uncertainWords = uncertain)
    }
}
