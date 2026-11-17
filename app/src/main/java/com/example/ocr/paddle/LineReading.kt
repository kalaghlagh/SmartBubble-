package com.example.ocr.paddle

import com.example.extraction.CandidateEvaluator
import com.example.extraction.OcrModel
import com.example.extraction.RecognitionCandidate
import com.example.extraction.Script
import com.example.extraction.ScriptDetector

/**
 * Decides, for one text line, which model's reading is kept:
 *
 * - A line the Arabic model reads with Arabic letters is an Arabic line. If it may hide Latin
 *   words (low-confidence characters, or Latin characters read by the Arabic model), the Latin
 *   model's reading of the same crop repairs them ([ScriptMerge]).
 * - Otherwise the line is Latin (or numbers): the Latin and Arabic models' readings are compared
 *   by [CandidateEvaluator] (confidence, plausibility, a model producing another script than its
 *   own is penalized).
 *
 * The first model read is the one the page prefers; the second is read only when [needsSecond].
 */
object LineReading {

    /** A character below this confidence may be a Latin letter the Arabic model guessed. */
    private const val SUSPECT_CONFIDENCE = 0.8f

    data class Result(
        val line: RecLine,
        val script: Script,
        /** Latin runs taken from the Latin reading into an Arabic line. */
        val mergedRuns: Int,
        val needsReview: Boolean,
        val models: List<String>,
        val reasons: List<String> = emptyList(),
        /** The Arabic model's own reading when Latin runs were merged into it. */
        val primary: RecLine? = null
    )

    fun arabicLetters(text: String): Int = ScriptDetector.count(text).arabic
    private fun latinLetters(text: String): Int = ScriptDetector.count(text).latin

    /** The line holds Arabic: enough Arabic letters among its letters. */
    fun isArabic(line: RecLine): Boolean {
        val ar = arabicLetters(line.text)
        return ar >= 2 && ar * 10 >= (ar + latinLetters(line.text)) * 3
    }

    private fun mayHideLatin(line: RecLine): Boolean = line.chars.any { c ->
        c.text.isNotBlank() && (c.confidence < SUSPECT_CONFIDENCE || ScriptMerge.isLatin(c.text))
    }

    /** Whether the other model must read the line too. */
    fun needsSecond(first: RecLine): Boolean = when (first.model) {
        OcrModel.ARABIC.key -> !isArabic(first) || mayHideLatin(first)
        else -> first.text.isBlank() || first.confidence < 0.85f || CandidateEvaluator.plausibility(first.text) < 0.6 ||
            first.chars.count { it.confidence < 0.6f } * 5 > first.chars.size
    }

    fun combine(first: RecLine, second: RecLine?): Result {
        val readings = listOfNotNull(first, second)
        val arabic = readings.firstOrNull { it.model == OcrModel.ARABIC.key }
        val latin = readings.firstOrNull { it.model == OcrModel.LATIN.key }
        val models = readings.map { it.model }
        if (arabic != null && isArabic(arabic)) {
            val merged = if (latin != null) ScriptMerge.merge(arabic, latin) else ScriptMerge.Merged(arabic, 0)
            val low = merged.line.confidence < 0.6f
            return Result(merged.line, Script.ARABIC, merged.replacedRuns, low || merged.replacedRuns > 0, models,
                listOfNotNull("low_confidence".takeIf { low }, "latin_merged".takeIf { merged.replacedRuns > 0 }), primary = arabic)
        }
        if (readings.size == 1) {
            val r = readings.single()
            val low = r.confidence < 0.6f
            return Result(r, ScriptDetector.classify(r.text), 0, low, models, listOfNotNull("low_confidence".takeIf { low }))
        }
        val candidates = readings.map {
            RecognitionCandidate(it.text, it.confidence, it.model, if (it.model == OcrModel.ARABIC.key) Script.ARABIC else Script.LATIN)
        }
        val e = CandidateEvaluator.evaluate(candidates)
        val chosen = readings.firstOrNull { it.model == e.chosen?.engine } ?: first
        return Result(chosen, ScriptDetector.classify(chosen.text), 0, e.needsReview, models, e.reasons)
    }
}
