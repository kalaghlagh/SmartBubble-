package com.example.extraction

/**
 * Decisions of the OCR pass that do not need the engine: which reading of a line to keep, which
 * text rows of the image were missed, and which recovered lines repeat text already read.
 */
object OcrDecisions {

    /** The line should be read again with the Arabic model alone. */
    fun needsArabicReread(line: TextLine, pageModelArabicFirst: Boolean): Boolean {
        val script = ScriptDetector.classify(line.text)
        if (script != Script.ARABIC && script != Script.MIXED) return false
        if (ScriptDetector.suspiciousLatinInArabic(line)) return true
        // With another model first, every Arabic line is checked; with Arabic first, only weak ones.
        return !pageModelArabicFirst || (line.confidence ?: 0f) < 0.8f
    }

    /** A Latin line read with low confidence by the multi-language pass is read again with French and English only. */
    fun needsLatinReread(line: TextLine): Boolean =
        ScriptDetector.classify(line.text) == Script.LATIN && (line.confidence ?: 0f) < 0.7f

    private fun hasTrustedLatin(line: TextLine): Boolean =
        line.words.any { w -> ScriptDetector.count(w.text).let { it.latin >= 2 && it.arabic == 0 } && (w.confidence ?: 0f) >= 0.85f } &&
            !ScriptDetector.suspiciousLatinInArabic(line)

    /**
     * Keeps [candidate] (the line read again) or [original]. Decided by confidence and script
     * consistency, never by length: a longer reading is not a better one.
     */
    fun choose(original: TextLine, candidate: TextLine?, arabicExpected: Boolean): TextLine {
        if (candidate == null || candidate.text.isBlank()) return original
        val oc = original.confidence ?: 0f
        val cc = candidate.confidence ?: 0f
        if (arabicExpected) {
            val oShare = ScriptDetector.count(original.text).arabicShare
            val cShare = ScriptDetector.count(candidate.text).arabicShare
            // Real Latin terms read with high confidence are not traded for an all-Arabic reading.
            if (hasTrustedLatin(original) && ScriptDetector.count(candidate.text).latin == 0) return original
            if (cc >= oc - 0.03f && cShare >= oShare - 0.05) return candidate
            if (ScriptDetector.suspiciousLatinInArabic(original) && cc >= 0.55f && cShare >= oShare) return candidate
            return original
        }
        return if (cc > oc + 0.05f) candidate else original
    }

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
}
