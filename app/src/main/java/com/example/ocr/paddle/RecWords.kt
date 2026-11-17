package com.example.ocr.paddle

import com.example.extraction.Box
import com.example.extraction.TextLine
import com.example.extraction.TextWord

/**
 * Words with page positions from a recognized line, and lines of the page from the detected
 * regions.
 */
object RecWords {

    /**
     * The words of [line] (read on the crop [crop], in page coordinates) in reading order. The
     * characters are split at spaces in visual order; each word gets the box its characters
     * cover, its text in logical order, and the mean confidence of its characters. Arabic words
     * whose space the model dropped are separated again ([RtlText.recoverWordSpaces]).
     */
    fun words(line: RecLine, crop: Box): List<TextWord> {
        if (line.chars.isEmpty()) return emptyList()
        val chars = RtlText.recoverWordSpaces(line.chars)
        val half = (if (line.step > 0f) line.step else 0.02f) / 2f
        val groups = ArrayList<MutableList<RecChar>>()
        var cur = ArrayList<RecChar>()
        for (c in chars) {
            if (c.text.isBlank()) { if (cur.isNotEmpty()) { groups += cur; cur = ArrayList() }; continue }
            // A token can carry its own spaces (a merged Latin run "Version 2.5"): split it.
            if (c.text.contains(' ')) {
                if (cur.isNotEmpty()) { groups += cur; cur = ArrayList() }
                val parts = c.text.split(' ').filter { it.isNotEmpty() }
                parts.forEachIndexed { k, p -> groups += mutableListOf(RecChar(p, c.x + k * half * 2 * p.length, c.confidence)) }
                continue
            }
            cur += c
        }
        if (cur.isNotEmpty()) groups += cur
        val rtl = RtlText.isRtlDominant(line.text)
        val visual = groups.map { g ->
            val text = ArabicLetters.clean(RtlText.wordToLogical(g.joinToString("") { it.text }, rtl))
            val left = crop.left + (g.first().x - half).coerceAtLeast(0f) * crop.width
            val right = crop.left + (g.last().x + half).coerceAtMost(1f) * crop.width
            TextWord(text, Box(left, crop.top, maxOf(right, left + 1f), crop.bottom), g.map { it.confidence }.average().toFloat())
        }
        return RtlText.logicalWordOrder(visual.map { it.text }, rtl).map { visual[it] }
    }

    /**
     * Text lines of the page from the detected regions: regions side by side on the same row
     * (a table row, two columns) form one line, as the PDF reader does, ordered right to left
     * when the row is Arabic. [regions] are the words of each region in reading order.
     */
    fun lines(regions: List<Pair<Box, List<TextWord>>>, source: (Int) -> LineInfo): List<TextLine> {
        val items = regions.withIndex().filter { it.value.second.isNotEmpty() }.sortedBy { it.value.first.centerY }
        val rows = ArrayList<MutableList<IndexedValue<Pair<Box, List<TextWord>>>>>()
        for (it in items) {
            val row = rows.lastOrNull()
            val box = it.value.first
            val joins = row != null && row.any { r ->
                val o = r.value.first
                o.verticalOverlap(box) > 0.5f && kotlin.math.abs(o.centerY - box.centerY) < minOf(o.height, box.height) * 0.5f &&
                    o.horizontalOverlap(box) < 0.3f
            }
            if (joins) row!! += it else rows += mutableListOf(it)
        }
        return rows.map { row ->
            val text = row.flatMap { it.value.second }.joinToString(" ") { it.text }
            val rtl = RtlText.isRtlDominant(text)
            val ordered = if (rtl) row.sortedByDescending { it.value.first.left } else row.sortedBy { it.value.first.left }
            val infos = ordered.map { source(it.index) }
            TextLine(
                words = ordered.flatMap { it.value.second },
                box = Box.of(ordered.map { it.value.first })!!,
                source = infos.map { it.source }.distinct().singleOrNull() ?: infos.joinToString("+") { it.source },
                needsReview = infos.any { it.needsReview },
                engines = infos.flatMap { it.engines }.distinct()
            )
        }
    }

    /** How a region was read. */
    data class LineInfo(val source: String, val needsReview: Boolean, val engines: List<String>)
}

/**
 * Letters the recognition model can produce that never belong to the app's Arabic text, fixed
 * where their shape is the same: the Arabic model also reads Persian, and Persian keheh (ک) and
 * yeh (ی) are drawn exactly like Arabic kaf (ك) and yeh (ي) at the start and in the middle of a
 * word. A tatweel (ـ, a stretching stroke) at the edge of a word, next to punctuation, is an
 * artefact of the line image, not text. Nothing else is changed.
 */
object ArabicLetters {
    private const val TATWEEL = 'ـ'

    private fun isArabicLetter(c: Char) = c.code in 0x0620..0x064A || c.code in 0x066E..0x06D3

    fun clean(word: String): String {
        if (word.none { it.code in 0x0600..0x06FF }) return word
        val sb = StringBuilder(word.length)
        for (i in word.indices) {
            val c = word[i]
            val prev = word.getOrNull(i - 1)
            val next = word.getOrNull(i + 1)
            when {
                c == TATWEEL && (prev == null || next == null || !isArabicLetter(prev) || !isArabicLetter(next)) -> {}
                c == 'ک' -> sb.append('ك')
                // Final yeh without dots is alif maqsura in Arabic.
                c == 'ی' -> sb.append(if (next == null || !isArabicLetter(next)) 'ى' else 'ي')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
