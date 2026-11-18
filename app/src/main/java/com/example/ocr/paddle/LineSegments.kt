package com.example.ocr.paddle

/**
 * Words of a line image found from its ink: runs of columns with dark pixels, split where a
 * blank gap is wide enough to be a space between words (never inside a word, so cursive Arabic
 * is never cut). Used to read again, on their own, the parts of an Arabic line the line reading
 * left empty or unsure: inside an Arabic line, both recognition models tend to drop digits, Latin
 * words and punctuation that each reads correctly alone.
 */
object LineSegments {

    /**
     * Ink segments of a grey line image ([gray] row-major, [width] x [height], 0 = black), as
     * column ranges, left to right. Gaps narrower than [minGap] columns join their neighbours.
     */
    fun segments(gray: IntArray, width: Int, height: Int, minGap: Int, dark: Int = 140): List<IntRange> {
        val ink = BooleanArray(width)
        for (x in 0 until width) {
            for (y in 0 until height) if (gray[y * width + x] < dark) { ink[x] = true; break }
        }
        val out = ArrayList<IntRange>()
        var x = 0
        while (x < width) {
            if (!ink[x]) { x++; continue }
            val start = x
            var end = x
            var gap = 0
            x++
            while (x < width) {
                if (ink[x]) { end = x; gap = 0 } else { gap++; if (gap >= minGap) break }
                x++
            }
            out += start..end
        }
        return out
    }

    /** The characters of [line] that fall on [segment] (pixel columns of a crop [width] wide). */
    fun charsOn(line: RecLine, segment: IntRange, width: Int, slack: Float): List<RecChar> =
        line.chars.filter { c -> c.text.isNotBlank() && c.x * width >= segment.first - slack && c.x * width <= segment.last + slack }

    /**
     * [line] with the characters on [segment] replaced by [reading] (read on that segment alone,
     * positions relative to the segment). Characters elsewhere are kept as they are.
     */
    fun replace(line: RecLine, segment: IntRange, width: Int, slack: Float, reading: RecLine): RecLine {
        val kept = line.chars.filterNot { c -> c.text.isNotBlank() && c.x * width >= segment.first - slack && c.x * width <= segment.last + slack }
        val span = (segment.last - segment.first + 1).toFloat()
        val placed = reading.chars.map { c -> c.copy(x = (segment.first + c.x * span) / width) }
        // Word gaps on both sides of the segment.
        val left = RecChar(" ", (segment.first - slack / 2) / width, 1f)
        val right = RecChar(" ", (segment.last + slack / 2) / width, 1f)
        val chars = (kept + left + placed + right).sortedBy { it.x }
        val text = chars.joinToString("") { it.text }.replace(Regex(" {2,}"), " ")
        val merged = chars.filterIndexed { i, c -> !(c.text == " " && i > 0 && chars[i - 1].text == " ") }
        return RecLine(text.trim(), merged.filter { it.text.isNotBlank() }.map { it.confidence }.average().toFloat(), merged, line.step, line.model)
    }

    /** Ink columns of [range] in a grey line image, trimmed to the dark part, or null. */
    private fun inkExtent(gray: IntArray, width: Int, height: Int, range: IntRange, dark: Int = 140): IntRange? {
        var first = -1
        var last = -1
        for (x in range.first.coerceAtLeast(0)..range.last.coerceAtMost(width - 1)) {
            for (y in 0 until height) if (gray[y * width + x] < dark) { if (first < 0) first = x; last = x; break }
        }
        return if (first < 0) null else first..last
    }

    /**
     * Parts of a line image to read again: word segments with no character read on them, with
     * one character only or with unsure characters, and stretches of ink between two read
     * characters far apart (a word the line reading skipped inside a segment).
     */
    fun toReread(line: RecLine, gray: IntArray, width: Int, height: Int): List<IntRange> {
        val minGap = (height * 0.16f).toInt().coerceAtLeast(3)
        val slack = height * 0.15f
        val out = ArrayList<IntRange>()
        for (seg in segments(gray, width, height, minGap)) {
            if (seg.last - seg.first + 1 < height * 0.15f) continue // a speck or a dot
            val on = charsOn(line, seg, width, slack)
            val conf = if (on.isEmpty()) 0f else on.map { it.confidence }.average().toFloat()
            if (on.size <= 1 || conf < 0.9f) { out += seg; continue }
            val xs = on.map { it.x * width }.sorted()
            for ((a, b) in xs.zipWithNext()) {
                if (b - a <= height * 1.2f) continue
                // The word(s) between: ink inside the stretch, widened to the whole word (until a
                // word gap), without reaching the characters already read on either side.
                val inner = inkExtent(gray, width, height, (a + height * 0.35f).toInt()..(b - height * 0.35f).toInt()) ?: continue
                val word = segments(gray, width, height, minGap).filter { it.last >= inner.first && it.first <= inner.last }
                val sub = segments(subGray(gray, width, height, seg), seg.last - seg.first + 1, height, (minGap * 0.6f).toInt().coerceAtLeast(2))
                    .map { (it.first + seg.first)..(it.last + seg.first) }
                    .filter { it.last >= inner.first && it.first <= inner.last && it.first > a && it.last < b }
                val range = if (sub.isNotEmpty()) sub.minOf { it.first }..sub.maxOf { it.last } else word.firstOrNull()?.let { inner } ?: inner
                if (range.last - range.first + 1 >= height * 0.3f) out += range
            }
        }
        // Overlapping or close parts (the letters of one Latin word, a number and its unit) are
        // read once, together.
        val merged = ArrayList<IntRange>()
        for (r in out.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + height * 0.45f) merged[merged.size - 1] = last.first..maxOf(last.last, r.last) else merged += r
        }
        return merged
    }

    private fun subGray(gray: IntArray, width: Int, height: Int, cols: IntRange): IntArray {
        val w = cols.last - cols.first + 1
        return IntArray(w * height) { i -> gray[(i / w) * width + cols.first + i % w] }
    }

    private fun stripPunct(s: String) = s.filter { it.isLetterOrDigit() }
    private fun punct(s: String) = s.count { !it.isLetterOrDigit() && !it.isWhitespace() }

    /**
     * The reading kept for a part read again: the part's own reading in the line ([lineText],
     * [lineConf]) or the Arabic or Latin model's reading of the part alone, compared by
     * [com.example.extraction.CandidateEvaluator]; a reading that only adds punctuation the line
     * reading dropped ("أ-" read "أ") is taken when it is confident. Null keeps the line reading.
     */
    fun choose(lineText: String, lineConf: Float, arabic: RecLine?, latin: RecLine?): RecLine? {
        val readings = listOfNotNull(arabic, latin).filter { it.text.isNotBlank() }
        if (readings.isEmpty()) return null
        val candidates = ArrayList<com.example.extraction.RecognitionCandidate>()
        if (lineText.isNotBlank()) candidates += com.example.extraction.RecognitionCandidate(lineText, lineConf, "line")
        readings.forEach {
            candidates += com.example.extraction.RecognitionCandidate(it.text, it.confidence, it.model,
                if (it.model == com.example.extraction.OcrModel.ARABIC.key) com.example.extraction.Script.ARABIC else com.example.extraction.Script.LATIN)
        }
        val e = com.example.extraction.CandidateEvaluator.evaluate(candidates)
        val pick = readings.firstOrNull { it.model == e.chosen?.engine }
        if (pick != null) return pick.takeIf { it.confidence >= 0.6f }
        return readings.firstOrNull {
            it.confidence >= 0.7f && stripPunct(it.text) == stripPunct(lineText) && punct(it.text) > punct(lineText)
        }
    }

    /**
     * [line] (read on a crop [width] x [height], grey pixels [gray]) with the parts the line
     * reading dropped or was unsure of read again on their own by [read], which gets the column
     * range of the sub-crop and returns the Arabic and Latin readings of it (positions relative to
     * the sub-crop). Returns the repaired line and the number of parts replaced.
     */
    fun repair(line: RecLine, gray: IntArray, width: Int, height: Int, read: (IntRange) -> Pair<RecLine?, RecLine?>): Pair<RecLine, Int> {
        val slack = height * 0.15f
        val pad = (height * 0.15f).toInt()
        var current = line
        var replaced = 0
        for (seg in toReread(line, gray, width, height)) {
            val on = charsOn(current, seg, width, slack)
            val lineText = on.joinToString("") { it.text }
            val lineConf = if (on.isEmpty()) 0f else on.map { it.confidence }.average().toFloat()
            val x0 = (seg.first - pad).coerceAtLeast(0)
            val x1 = (seg.last + pad).coerceAtMost(width - 1)
            val (arabic, latin) = read(x0..x1)
            val pick = choose(lineText, lineConf, arabic, latin) ?: continue
            // The same text as the line reading: nothing to change.
            if (pick.text.filter { !it.isWhitespace() } == lineText.filter { !it.isWhitespace() }) continue
            // Positions from the sub-crop to the segment.
            val sub = (x1 - x0 + 1).toFloat()
            val span = (seg.last - seg.first + 1).toFloat()
            val shifted = pick.copy(chars = pick.chars.map { c -> c.copy(x = ((x0 + c.x * sub) - seg.first) / span) })
            current = replace(current, seg, width, slack, shifted)
            replaced++
        }
        return current to replaced
    }
}
