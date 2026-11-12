package com.example.offline.analysis

/** One line of the normalized source with its absolute offsets ([start], [end] exclusive). */
data class SourceLine(val index: Int, val start: Int, val end: Int, val text: String) {
    val trimmed: String = text.trim()
    val isBlank: Boolean get() = trimmed.isEmpty()

    /** Leading indentation in columns (a tab counts as 4). */
    val indent: Int = run {
        var n = 0
        for (c in text) {
            when (c) {
                ' ' -> n++
                '\t' -> n += 4
                else -> return@run n
            }
        }
        n
    }

    /** Absolute offset of the first non-whitespace character. */
    val contentStart: Int get() = start + (text.length - text.trimStart().length)

    /** Absolute offset after the last non-whitespace character. */
    val contentEnd: Int get() = start + text.trimEnd().length
}

object SourceLines {
    fun split(text: String): List<SourceLine> {
        val lines = ArrayList<SourceLine>()
        var start = 0
        var index = 0
        while (start <= text.length) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            lines.add(SourceLine(index++, start, end, text.substring(start, end)))
            if (nl < 0) break
            start = nl + 1
        }
        return lines
    }
}

/** Character classes shared by the detection rules (Arabic, French and English). */
internal object TextTraits {
    /** Sentence-final punctuation in Latin and Arabic script. */
    const val TERMINAL = ".!?؟…;؛,،"

    fun isArabicLetter(c: Char) = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' ||
        c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'

    fun hasArabic(text: String) = text.any { isArabicLetter(it) }

    fun isLatinLetter(c: Char) = c.isLetter() && !isArabicLetter(c) && c.code < 0x0250

    fun words(text: String): List<String> = text.split(' ', '\t').filter { it.isNotEmpty() }

    fun endsWithTerminal(text: String): Boolean {
        val t = text.trimEnd().trimEnd('"', '”', '»', ')', '\'', '’')
        return t.isNotEmpty() && t.last() in TERMINAL
    }

    fun startsLowercaseLatin(text: String): Boolean {
        val c = text.firstOrNull { it.isLetterOrDigit() } ?: return false
        return isLatinLetter(c) && c.isLowerCase()
    }
}
