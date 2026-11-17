package com.example.extraction

/**
 * Script of a text from its letters. Digits and punctuation never decide the script, so an
 * Arabic line with a few Latin terms ("pH", "GPS") or numbers stays Arabic.
 */
object ScriptDetector {

    /** Share of letters a script needs for a line to be classified as that script alone. */
    const val DOMINANT = 0.8

    fun isArabicLetter(c: Char): Boolean =
        (c in '؀'..'ۿ' && c !in '٠'..'٩' && c !in '۰'..'۹' && c.isLetter()) ||
            c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' || (c in 'ﭐ'..'﷿' && c.isLetter()) || (c in 'ﹰ'..'﻿' && c.isLetter())

    fun isLatinLetter(c: Char): Boolean = c.isLetter() && (c in 'A'..'Z' || c in 'a'..'z' || c in 'À'..'ɏ' || c in 'Ḁ'..'ỿ')

    fun isDigit(c: Char): Boolean = c in '0'..'9' || c in '٠'..'٩' || c in '۰'..'۹'

    data class Counts(val arabic: Int, val latin: Int, val digits: Int) {
        val letters: Int get() = arabic + latin
        val arabicShare: Double get() = if (letters == 0) 0.0 else arabic.toDouble() / letters
    }

    fun count(text: String): Counts {
        var a = 0
        var l = 0
        var d = 0
        for (c in text) when {
            isArabicLetter(c) -> a++
            isLatinLetter(c) -> l++
            isDigit(c) -> d++
        }
        return Counts(a, l, d)
    }

    fun classify(text: String): Script {
        val c = count(text)
        if (c.letters == 0) return if (c.digits > 0) Script.NUMERIC else Script.UNKNOWN
        return when {
            c.arabicShare >= DOMINANT -> Script.ARABIC
            c.arabicShare <= 1 - DOMINANT -> Script.LATIN
            else -> Script.MIXED
        }
    }

    /** Arabic letters are at least as many as Latin ones: the text reads right to left. */
    fun isRtl(text: String): Boolean {
        val c = count(text)
        return c.arabic > 0 && c.arabic >= c.latin
    }

    /** The scripts present in a page's lines (MIXED lines count for both). */
    fun scriptsOf(texts: Collection<String>): Set<Script> {
        val out = HashSet<Script>()
        for (t in texts) when (classify(t)) {
            Script.ARABIC -> out += Script.ARABIC
            Script.LATIN -> out += Script.LATIN
            Script.MIXED -> { out += Script.ARABIC; out += Script.LATIN }
            Script.NUMERIC -> out += Script.NUMERIC
            Script.UNKNOWN -> Unit
        }
        return out
    }

    /**
     * Latin words inside a line that is mostly Arabic, recognized with lower confidence than the
     * Arabic words around them: the usual sign that the English or French model replaced Arabic
     * words ("Arabic read as English"). Real Latin terms are recognized with high confidence.
     */
    fun suspiciousLatinInArabic(line: TextLine): Boolean {
        if (classify(line.text) == Script.LATIN) return false
        val counts = count(line.text)
        if (counts.arabic == 0) return false
        val latinWords = line.words.filter { w -> count(w.text).let { it.latin > 0 && it.arabic == 0 } }
        if (latinWords.isEmpty()) return false
        val arabicConf = line.words.filter { count(it.text).arabic > 0 }.mapNotNull { it.confidence }.average()
        val latinConf = latinWords.mapNotNull { it.confidence }.average()
        return latinConf.isNaN() || arabicConf.isNaN() || latinConf < 0.75 || latinConf < arabicConf - 0.1
    }
}
