/*
 * Adapted from Scanly (https://github.com/Azyrn/Scanly), core/ocr/paddle/ScriptMerge.kt,
 * Copyright 2026 skeler (Azyrn), Apache License 2.0 (see third_party_licenses/scanly_LICENSE.txt
 * and scanly_NOTICE.txt). Changes: package and types of Smart Bubble; the merged line records
 * how many runs were replaced ([Merged]); a run is never taken when the primary model is sure of
 * its own script under it; comments.
 */
package com.example.ocr.paddle

/**
 * Repairs the Latin words (and numbers) inside an Arabic line.
 *
 * The Arabic model reads Arabic well and Latin badly ("Original" can come back as Arabic letters).
 * Cutting the crop into words to send each to another model would cut through Arabic ligatures,
 * so nothing is cut: both models read the same whole-line crop, and because CTC scans it left to
 * right each reports *where* it read every character. A stretch the Latin model reads
 * confidently, and the Arabic model does not, is a Latin word: the Latin reading of that stretch
 * replaces the Arabic reading there. Everything else stays as the Arabic model read it; no
 * character is added outside a replaced stretch, so text is neither invented nor duplicated.
 *
 * Both readings must be raw CTC output in visual order of the same crop.
 */
object ScriptMerge {

    /** The Latin model must be this sure before it may overrule the Arabic model. */
    private const val LATIN_MIN_CONFIDENCE = 0.85f
    /** A single character is never worth overruling for (lone letters are Latin-model noise). */
    private const val MIN_RUN = 2
    /** Where the Arabic model really reads Arabic, it is this sure of most characters. */
    private const val PRIMARY_SURE = 0.9f
    private const val PRIMARY_SURE_SHARE = 0.5f
    /** A run breaks at a gap of this many of its own character pitches. */
    private const val GAP_PITCH_FACTOR = 3f
    private const val MIN_GAP = 0.035f
    private const val DEFAULT_PITCH = 0.03f
    /** The primary model's own reading of a run's edge letter lands this close to it. */
    private const val LATIN_ECHO_MARGIN = 0.035f
    private val DOUBLE_SPACE = Regex(" {2,}")

    data class Merged(val line: RecLine, val replacedRuns: Int)

    fun merge(primary: RecLine, latin: RecLine): Merged {
        if (primary.chars.isEmpty() || latin.chars.isEmpty()) return Merged(primary, 0)
        val runs = latinRuns(latin.chars).filter { overrules(it, primary) }
        if (runs.isEmpty()) return Merged(primary, 0)
        val kept = primary.chars.filterNot { c ->
            when {
                c.text.isBlank() -> runs.any { it.coversCore(c.x) }
                isLatin(c.text) -> runs.any { it.coversEcho(c.x) }
                else -> runs.any { it.covers(c.x) }
            }
        }
        val merged = (kept + runs.map { it.asChar(latin.chars) }).sortedBy { it.x }
        val text = merged.joinToString("") { it.text }.replace(DOUBLE_SPACE, " ")
        return Merged(
            RecLine(text, merged.map { it.confidence }.average().toFloat(), merged, primary.step, "merged"),
            runs.size
        )
    }

    private class Run(val chars: List<RecChar>) {
        val start get() = chars.first().x - MIN_GAP / 2f
        val end get() = chars.last().x + MIN_GAP / 2f
        fun covers(x: Float) = x in start..end
        fun coversCore(x: Float) = x in chars.first().x..chars.last().x
        fun coversEcho(x: Float) = x in (chars.first().x - LATIN_ECHO_MARGIN)..(chars.last().x + LATIN_ECHO_MARGIN)

        /** The run's text with the spaces the model itself read inside it (none invented). */
        fun asChar(all: List<RecChar>): RecChar {
            val span = all.filter { it.x >= chars.first().x && it.x <= chars.last().x }
            return RecChar(span.joinToString("") { it.text }.trim(), chars.first().x, chars.map { it.confidence }.average().toFloat())
        }
    }

    private fun latinRuns(chars: List<RecChar>): List<Run> {
        val candidates = chars.filter { it.text.isNotBlank() && isLatin(it.text) && it.confidence >= 0.4f }
        if (candidates.size < MIN_RUN) return emptyList()
        val steps = candidates.zipWithNext { a, b -> b.x - a.x }
        val pitch = steps.sorted().getOrElse(steps.size / 2) { DEFAULT_PITCH }
        val limit = maxOf(MIN_GAP, GAP_PITCH_FACTOR * pitch)
        val runs = mutableListOf(mutableListOf(candidates.first()))
        for ((a, b) in candidates.zipWithNext()) {
            if (b.x - a.x > limit) runs.add(mutableListOf())
            runs.last().add(b)
        }
        return runs.filter { it.size >= MIN_RUN }.map { Run(it) }
    }

    private fun overrules(run: Run, primary: RecLine): Boolean {
        if (run.chars.map { it.confidence }.average() < LATIN_MIN_CONFIDENCE) return false
        val under = primary.chars.filter { run.covers(it.x) && it.text.isNotBlank() }
        if (under.isEmpty()) return true
        // Already read as Latin letters or digits by the primary model: nothing to repair.
        val meaningful = under.filter { c -> c.text.any { it.isLetterOrDigit() } }
        if (meaningful.isNotEmpty() && meaningful.all { isLatin(it.text) }) return false
        val sure = under.count { isScript(it.text) && it.confidence >= PRIMARY_SURE }
        return sure.toFloat() / under.size < PRIMARY_SURE_SHARE
    }

    /** Latin, digits and the punctuation that binds them ("Version 2.5", "GPT-5", "pH"). */
    fun isLatin(text: String): Boolean = text.all { it.code < 0x0590 } && text.any { it.isLetterOrDigit() }

    private fun isScript(text: String): Boolean = text.any { it.code >= 0x0590 }
}
