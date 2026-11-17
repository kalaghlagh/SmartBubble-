/*
 * Adapted from Scanly (https://github.com/Azyrn/Scanly), core/ocr/paddle/RtlText.kt,
 * Copyright 2026 skeler (Azyrn), Apache License 2.0 (see third_party_licenses/scanly_LICENSE.txt
 * and scanly_NOTICE.txt). Changes: package, RecChar type of Smart Bubble, word-level ordering
 * ([logicalWordOrder]) added, comments.
 */
package com.example.ocr.paddle

/**
 * CTC recognition scans the crop left to right, so right-to-left lines come out in visual
 * order: the logical string reversed, with embedded Latin words and numbers still left to right.
 * These functions turn that back into reading (logical) order without changing any character
 * other than mirrored brackets.
 */
object RtlText {

    /** A gap between two Arabic letters wider than this many letter pitches is a word gap. */
    private const val WORD_GAP_FACTOR = 2.3f

    private val MIRROR = mapOf(
        '(' to ')', ')' to '(', '[' to ']', ']' to '[',
        '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«'
    )

    fun isRtl(c: Char): Boolean {
        val d = Character.getDirectionality(c)
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }

    /** Latin letters and digits (Western and Arabic-Indic) keep their left-to-right order. */
    private fun isLtrStrong(c: Char): Boolean =
        Character.getDirectionality(c) == Character.DIRECTIONALITY_LEFT_TO_RIGHT || c.isDigit()

    /** Joins two LTR characters into one run ("3.14", "A-4471", "Version 2.5"). */
    private fun isRunJoiner(c: Char): Boolean = c in ".,:/-+ "

    /** The line reads right to left: more right-to-left letters than Latin ones (digits do not count). */
    fun isRtlDominant(s: String): Boolean {
        var rtl = 0
        var ltr = 0
        for (c in s) {
            if (isRtl(c)) rtl++ else if (Character.getDirectionality(c) == Character.DIRECTIONALITY_LEFT_TO_RIGHT) ltr++
        }
        return rtl > ltr
    }

    private fun isArabicChar(c: Char): Boolean = c.code in 0x0600..0x06FF

    /**
     * Restores the word spaces the recognizer dropped between Arabic words: a gap between two
     * Arabic letters wider than [WORD_GAP_FACTOR] letter pitches. Never beside an existing space
     * or a Latin run. Returns the characters with the inserted spaces (visual order).
     */
    fun recoverWordSpaces(chars: List<RecChar>, factor: Float = WORD_GAP_FACTOR): List<RecChar> {
        if (chars.size < 3) return chars
        val ordered = chars.sortedBy { it.x }
        val gaps = ordered.zipWithNext { a, b -> b.x - a.x }.filter { it > 0f }.sorted()
        if (gaps.isEmpty()) return ordered
        val limit = gaps[gaps.size / 2] * factor
        val out = ArrayList<RecChar>(ordered.size + 8)
        for (i in ordered.indices) {
            val c = ordered[i]
            if (i > 0) {
                val p = ordered[i - 1]
                if (c.x - p.x > limit && p.text.isNotBlank() && c.text.isNotBlank() &&
                    p.text.all { isArabicChar(it) } && c.text.all { isArabicChar(it) }
                ) out += RecChar(" ", (p.x + c.x) / 2f, minOf(p.confidence, c.confidence))
            }
            out += c
        }
        return out
    }

    /** Visual-order text to logical order: reversed, LTR runs restored, brackets mirrored. */
    fun visualToLogical(s: String): String {
        if (s.none { isRtl(it) }) return s
        val out = CharArray(s.length) { i ->
            val c = s[s.length - 1 - i]
            MIRROR[c] ?: c
        }
        var i = 0
        while (i < out.size) {
            if (!isLtrStrong(out[i])) { i++; continue }
            var end = i
            var j = i + 1
            while (j < out.size) {
                if (isLtrStrong(out[j])) { end = j; j++ }
                else if (isRunJoiner(out[j]) && j + 1 < out.size && isLtrStrong(out[j + 1])) j++
                else break
            }
            var lo = i
            var hi = end
            while (lo < hi) { val t = out[lo]; out[lo] = out[hi]; out[hi] = t; lo++; hi-- }
            i = end + 1
        }
        return String(out)
    }

    private fun hasLatinLetter(w: String) = w.any { it.isLetter() && Character.getDirectionality(it) == Character.DIRECTIONALITY_LEFT_TO_RIGHT }
    private fun isNumber(w: String) = w.any { it.isDigit() } && w.none { it.isLetter() }

    /**
     * Reading order of a line's words given in visual (left-to-right) order: for a right-to-left
     * line the words are taken from right to left, except that a Latin phrase keeps its own order
     * ("GPS Pro"), with the numbers that follow it ("Version 2.5", as the Unicode bidirectional
     * algorithm does: a number after Latin text is left to right). Numbers between Arabic words
     * ("ب1 120 35") are separate units and keep the right-to-left order. Returns indices into [visual].
     */
    fun logicalWordOrder(visual: List<String>, rtlLine: Boolean): List<Int> {
        if (!rtlLine) return visual.indices.toList()
        val out = ArrayList<Int>(visual.size)
        var i = visual.size - 1
        while (i >= 0) {
            // A Latin run: its last word (rightmost) is Latin or a number, and it holds a Latin word.
            var start = i
            if (hasLatinLetter(visual[i]) || isNumber(visual[i])) {
                while (start - 1 >= 0 && (hasLatinLetter(visual[start - 1]) || isNumber(visual[start - 1]))) start--
                // Leading numbers (visually left of the first Latin word) are not part of the run.
                while (start <= i && !hasLatinLetter(visual[start])) start++
            }
            if (start > i || start == i) { out += i; i--; continue }
            for (k in start..i) out += k
            i = start - 1
        }
        return out
    }

    private fun isNeutral(c: Char) = !isRtl(c) && !isLtrStrong(c)

    /**
     * One word of a line in reading order. A word with right-to-left letters is reversed (its
     * number and Latin runs kept). A number or Latin word in a right-to-left line keeps its core
     * but the punctuation around it changes side: "2." is drawn ".2", "(12)" keeps its brackets.
     */
    fun wordToLogical(word: String, rtlLine: Boolean): String {
        if (word.any { isRtl(it) }) return visualToLogical(word)
        if (!rtlLine) return word
        val first = word.indexOfFirst { !isNeutral(it) }
        if (first < 0) return String(word.reversed().map { MIRROR[it] ?: it }.toCharArray())
        val last = word.indexOfLast { !isNeutral(it) }
        val lead = word.substring(0, first)
        val trail = word.substring(last + 1)
        fun flip(s: String) = String(s.reversed().map { MIRROR[it] ?: it }.toCharArray())
        return flip(trail) + word.substring(first, last + 1) + flip(lead)
    }
}
