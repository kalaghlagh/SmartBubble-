package com.example.extraction

/** A recognition model of the OCR engine (PaddleOCR PP-OCRv5 mobile). */
enum class OcrModel(val script: Script, val key: String) {
    /** Arabic script; its dictionary also holds Latin letters and both digit sets. */
    ARABIC(Script.ARABIC, "arabic"),
    /** Latin-script languages (French, English and others), digits and punctuation. */
    LATIN(Script.LATIN, "latin")
}

/** French or English, from function words and accents (null when the text does not say). */
object LatinLanguage {
    private val FR = setOf("le", "la", "les", "de", "des", "du", "et", "en", "un", "une", "est", "sont", "dans", "pour", "par", "sur", "au", "aux",
        "que", "qui", "pas", "avec", "ce", "cette", "ces", "été", "être", "leur", "nous", "vous", "il", "elle", "ils", "ont", "plus", "son", "sa", "ses")
    private val EN = setOf("the", "of", "and", "to", "in", "is", "are", "was", "were", "for", "with", "on", "by", "this", "that", "these", "from",
        "at", "as", "an", "be", "been", "it", "its", "or", "which", "have", "has", "not", "their", "we", "our")

    fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }

    /** "fr", "en" or null. */
    fun detect(text: String): String? {
        val w = words(text)
        if (w.isEmpty()) return null
        var fr = w.count { it in FR } + text.count { it in "éèêàâçùûôîïëœ" } * 0.5
        var en = w.count { it in EN }.toDouble()
        if (w.any { it.endsWith("tion") && it.length > 6 }) { fr += 0.2; en += 0.2 }
        return when {
            fr >= 1 && fr >= en * 1.5 -> "fr"
            en >= 1 && en >= fr * 1.5 -> "en"
            else -> null
        }
    }

    /** Share of the text's words that are common function words of French or English. */
    fun functionWordShare(text: String): Double {
        val w = words(text)
        if (w.isEmpty()) return 0.0
        return w.count { it in FR || it in EN }.toDouble() / w.size
    }
}

/**
 * Which recognition models a text region is read with. The expected script comes from the
 * region's context (other cells of the table row and column, the line's confident words); when
 * it does not decide, the first reading of the region (the hint) does. Readings of several models
 * are compared by [CandidateEvaluator].
 */
object OcrRouting {

    fun plan(hint: String, expected: Script?, numericOnly: Boolean = false): List<OcrModel> {
        if (numericOnly) {
            val easternDigits = hint.any { it in '٠'..'٩' || it in '۰'..'۹' }
            // A cell read as digits in an Arabic column may be an Arabic label ("ب1" read "15").
            return if (easternDigits || expected == Script.ARABIC) listOf(OcrModel.ARABIC, OcrModel.LATIN) else listOf(OcrModel.LATIN, OcrModel.ARABIC)
        }
        val script = when {
            expected == Script.ARABIC || expected == Script.LATIN -> expected
            else -> ScriptDetector.classify(hint)
        }
        return when (script) {
            Script.LATIN -> listOf(OcrModel.LATIN, OcrModel.ARABIC)
            Script.NUMERIC -> listOf(OcrModel.LATIN, OcrModel.ARABIC)
            Script.ARABIC, Script.MIXED, Script.UNKNOWN -> listOf(OcrModel.ARABIC, OcrModel.LATIN)
        }
    }

    /** True when a region's text is made of digits, number signs and units only ("35", "12,5 %", "2024"). */
    fun isNumeric(text: String): Boolean {
        val t = text.filter { !it.isWhitespace() }
        if (t.isEmpty()) return false
        val digits = t.count { ScriptDetector.isDigit(it) }
        return digits > 0 && digits * 2 >= t.length && t.all { ScriptDetector.isDigit(it) || it in ".,%/-+:()°±" }
    }
}
