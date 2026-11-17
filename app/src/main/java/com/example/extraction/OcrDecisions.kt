package com.example.extraction

/**
 * Decisions of the OCR pass that do not need the engine: which text rows of the image were
 * missed, which recovered lines repeat text already read, and which digit-only words another
 * reading found as letters.
 */
object OcrDecisions {

    /**
     * Rows of text on the image ([bands], from the ink profile) that no recognized line covers:
     * text the page pass missed. Rows much taller than a text line (pictures, table grids) and
     * specks are left out.
     */
    fun missedBands(bands: List<IntRange>, lineBoxes: List<Box>): List<IntRange> {
        if (bands.isEmpty()) return emptyList()
        val heights = (if (lineBoxes.isNotEmpty()) lineBoxes.map { it.height } else bands.map { (it.last - it.first + 1).toFloat() }).sorted()
        val lineH = heights[heights.size / 2].coerceAtLeast(4f)
        return bands.filter { b ->
            val h = (b.last - b.first + 1).toFloat()
            if (h < lineH * 0.4f || h > lineH * 3f) return@filter false
            val box = Box(0f, b.first.toFloat(), 1f, b.last.toFloat())
            lineBoxes.none { it.verticalOverlap(box) > 0.3f }
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** A recovered line that repeats a line already read (or most of its words) is dropped. */
    fun isDuplicate(text: String, existing: List<TextLine>): Boolean {
        val n = norm(text)
        if (n.length < 2) return true
        val words = text.split(Regex("\\s+")).map { norm(it) }.filter { it.length > 1 }.toSet()
        return existing.any { l ->
            val e = norm(l.text)
            e == n || (n.length > 8 && e.contains(n)) ||
                (words.isNotEmpty() && words.count { w -> l.words.any { norm(it.text) == w } } * 5 >= words.size * 4)
        }
    }

    /** Index at which a recovered line (top at [top]) goes to keep the reading order. */
    fun insertionIndex(lines: List<TextLine>, top: Float): Int {
        val i = lines.indexOfFirst { it.box.top > top }
        return if (i < 0) lines.size else i
    }

    private fun digitsOnly(t: String) = t.any { it.isDigit() } && t.none { it.isLetter() }

    /**
     * Word by word, a reading that found letters where the kept reading has only digits (an
     * Arabic word read "2890", a Latin term read "665") replaces that word: words are matched by
     * position on the page, and the replacing word must be confident enough. Digits read the same
     * way are never touched, nothing is added. Returns the line and the number of replaced words.
     */
    fun fuseWords(kept: TextLine, others: List<TextLine>, minConfidence: Float = 0.5f): Pair<TextLine, Int> {
        var replaced = 0
        val words = kept.words.map { w ->
            if (!digitsOnly(w.text)) return@map w
            val match = others.flatMap { it.words }.filter { o ->
                o.box.horizontalOverlap(w.box) > 0.5f && o.box.verticalOverlap(w.box) > 0.3f &&
                    o.text.any { it.isLetter() } && (o.confidence ?: 0f) >= minConfidence
            }.maxByOrNull { it.confidence ?: 0f }
            if (match == null) w else { replaced++; match.copy(box = w.box) }
        }
        return (if (replaced == 0) kept else kept.copy(words = words)) to replaced
    }
}
