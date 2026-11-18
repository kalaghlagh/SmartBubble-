package com.example.extraction

/** One reading of a region by one recognition configuration. */
data class RecognitionCandidate(
    val text: String,
    /** Mean confidence 0..1, or null when the engine gave none. */
    val confidence: Float?,
    /** Which configuration produced it ("page", "ara", "fra+eng", "numeric", "words"). */
    val engine: String,
    /** The script the configuration reads (null for the multilingual page reading). */
    val engineScript: Script? = null,
    /** Words with positions, when the engine gave them (lines). */
    val lines: List<TextLine> = emptyList()
)

/** The reading kept for a region, how sure it is, and why a review is advised. */
data class Evaluation(
    val chosen: RecognitionCandidate?,
    val score: Double,
    val needsReview: Boolean,
    val reasons: List<String> = emptyList()
)

/**
 * Chooses between readings of the same region. Never by length; by:
 * - confidence reported by the engine;
 * - plausibility of the text as a language of its script (words with vowels, no runs of one
 *   letter, sensible capitalisation, Arabic letter forms in valid positions, function words);
 * - consistency with the script the region's context expects (a lone Arabic word in a French
 *   table row, a Latin word in an Arabic sentence), so a wrong-script reading cannot win by a
 *   higher reported confidence alone;
 * - agreement with the other readings, and for numbers a vote between readings: digits two
 *   readings agree on win, digits nobody confirms mark the region for review.
 * Nothing is completed or invented: when no reading is reliable, the best one is kept and the
 * region is marked for review.
 */
object CandidateEvaluator {

    private val LATIN_VOWELS = "aeiouyàâäéèêëîïôöùûüÿœæAEIOUYÀÂÄÉÈÊËÎÏÔÖÙÛÜŸŒÆ"
    /** Short Latin tokens that legitimately have no vowel (units, abbreviations). */
    private val NO_VOWEL_OK = setOf("ph", "mg", "ml", "km", "cm", "mm", "kg", "g", "l", "m", "s", "h", "n", "nb", "st", "pp", "cf", "dr", "mr", "mrs", "ms",
        "gps", "sms", "pdf", "html", "xml", "tv", "dvd", "cd", "qcm", "tp", "td", "bts", "pcr", "dns", "rh", "pvc", "ttc", "ht", "kwh", "mw", "kw")

    private fun letters(w: String) = w.filter { it.isLetter() }

    /** 0..1: how much [text] looks like real Latin-script words (French/English). */
    fun latinPlausibility(text: String): Double {
        val words = text.split(Regex("[^\\p{L}\\p{N}'’-]+")).filter { letters(it).isNotEmpty() }
        if (words.isEmpty()) return if (text.any { it.isDigit() }) 0.8 else 0.0
        var good = 0.0
        for (w in words) {
            val l = letters(w)
            if (l.any { ScriptDetector.isArabicLetter(it) }) continue
            var s = 1.0
            val lower = l.lowercase()
            if (l.length >= 2 && l.none { it in LATIN_VOWELS } && lower !in NO_VOWEL_OK && !w.any { it.isDigit() }) s -= 0.7
            if (Regex("(.)\\1\\1").containsMatchIn(lower)) s -= 0.6
            // Capitals only at the start, or a short all-capitals acronym.
            val upperInside = l.drop(1).count { it.isUpperCase() }
            if (upperInside > 0 && !(l.all { it.isUpperCase() } && l.length <= 5)) s -= 0.5
            if (l.length >= 6 && l.count { it !in LATIN_VOWELS } >= l.length - 1) s -= 0.4
            good += s.coerceIn(0.0, 1.0)
        }
        val base = good / words.size
        return (base + LatinLanguage.functionWordShare(text) * 0.3).coerceIn(0.0, 1.0)
    }

    /** 0..1: how much [text] looks like real Arabic words. */
    fun arabicPlausibility(text: String): Double {
        val words = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { w -> w.any { ScriptDetector.isArabicLetter(it) } }
        if (words.isEmpty()) return if (text.any { it.isDigit() }) 0.8 else 0.0
        var good = 0.0
        for (w in words) {
            var s = 1.0
            val l = w.filter { ScriptDetector.isArabicLetter(it) }
            if (Regex("(.)\\1\\1").containsMatchIn(l)) s -= 0.7
            // Taa marbuta and alif maqsura only end words; hamza on the line rarely starts one.
            if (l.dropLast(1).any { it == 'ة' || it == 'ى' }) s -= 0.5
            if (l.startsWith("ء") || l.startsWith("ة")) s -= 0.4
            if (l.length == 1 && l !in setOf("و", "ب", "ل", "ك", "ف", "أ", "ج", "د", "ه")) s -= 0.4
            if (w.any { ScriptDetector.isLatinLetter(it) }) s -= 0.6
            good += s.coerceIn(0.0, 1.0)
        }
        val common = setOf("في", "من", "على", "إلى", "الى", "أن", "ان", "عن", "مع", "هذا", "هذه", "التي", "الذي", "كل", "و", "ما", "لا", "تم", "بين", "كان")
        val share = words.count { it in common }.toDouble() / words.size
        return (good / words.size + share * 0.3).coerceIn(0.0, 1.0)
    }

    /** Plausibility of the text in its own script (numbers count as plausible). */
    fun plausibility(text: String): Double = when (ScriptDetector.classify(text)) {
        Script.ARABIC -> arabicPlausibility(text)
        Script.LATIN -> latinPlausibility(text)
        Script.MIXED -> (arabicPlausibility(text) + latinPlausibility(text)) / 2
        Script.NUMERIC -> 0.9
        Script.UNKNOWN -> 0.0
    }

    /** Digit groups of a text ("35", "12,5", "2024"), Arabic-Indic digits mapped to Western ones. */
    fun numbers(text: String): List<String> = Regex("[0-9٠-٩۰-۹]+(?:[.,][0-9٠-٩۰-۹]+)*").findAll(text).map { m ->
        m.value.map { c -> when (c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); else -> c } }.joinToString("")
    }.toList()

    /** Pattern of a short text: capital, small letter, digit, other ("P1" gives "Ad"); empty for long texts. */
    fun shape(text: String): String {
        val t = text.filter { !it.isWhitespace() }
        if (t.isEmpty() || t.length > 12) return ""
        return t.map { c -> when { c.isDigit() -> 'd'; c.isUpperCase() -> 'A'; c.isLowerCase() -> 'a'; c.isLetter() -> 'L'; else -> c } }
            .joinToString("").replace(Regex("d+"), "d").replace(Regex("a+"), "a").replace(Regex("L+"), "L")
    }

    /** A text with letters that look like digits replaced by them (alef and 1), spaces removed. */
    fun confusableKey(text: String): String = text.filter { !it.isWhitespace() }.map { if (it == 'ا') '1' else it }.joinToString("")

    private fun similarity(a: String, b: String): Double {
        val x = a.lowercase().filter { !it.isWhitespace() }
        val y = b.lowercase().filter { !it.isWhitespace() }
        if (x.isEmpty() && y.isEmpty()) return 1.0
        val d = TextMetrics.levenshtein(x, y)
        return 1.0 - d.toDouble() / maxOf(x.length, y.length)
    }

    /**
     * Picks the reading of a region. [expected] is the script the region's context expects
     * (null when unknown); [numericExpected] marks a region that should hold a number.
     */
    fun evaluate(
        candidates: List<RecognitionCandidate>,
        expected: Script? = null,
        numericExpected: Boolean = false,
        /** Texts of neighbouring regions of the same kind (the other cells of a table column). */
        context: List<String> = emptyList()
    ): Evaluation {
        val usable = candidates.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return Evaluation(null, 0.0, needsReview = true, reasons = listOf("no_text"))
        // Only readings by configurations able to read the expected script vote on numbers: a
        // digits-only or Latin model agreeing on "15" does not confirm it against the Arabic
        // model's "ب1".
        fun canRead(c: RecognitionCandidate) = c.engineScript == null || expected == null || expected == Script.MIXED || expected == Script.UNKNOWN ||
            c.engineScript == expected || (expected == Script.LATIN && c.engineScript == Script.NUMERIC) || expected == Script.NUMERIC
        val voters = usable.filter { canRead(it) }
        val numberVotes = voters.map { numbers(it.text).joinToString("|") }.groupingBy { it }.eachCount()
        // Letters of the expected script found by that script's own model with good confidence:
        // digit-only readings of the same region by other models replaced them.
        val scriptLetters = expected == Script.ARABIC && usable.any { (it.engineScript == Script.ARABIC || it.engineScript == null) &&
            (it.confidence ?: 0f) >= 0.55f && ScriptDetector.count(it.text).arabic > 0 } ||
            expected == Script.LATIN && usable.any { it.engineScript == Script.LATIN && (it.confidence ?: 0f) >= 0.7f && ScriptDetector.count(it.text).latin > 0 }
        val shapes = context.map { shape(it) }.filter { it.isNotEmpty() }
        val agreedNumbers = numberVotes.filter { it.value >= 2 }.maxByOrNull { it.value }?.key
        val scored = usable.map { c ->
            val script = ScriptDetector.classify(c.text)
            val conf = (c.confidence ?: 0.6f).toDouble()
            val plaus = plausibility(c.text)
            var s = conf + 0.45 * plaus
            val reasons = ArrayList<String>()
            // A reading in another script than the context expects needs much more than a higher confidence.
            val mismatch = expected != null && expected != Script.MIXED && expected != Script.UNKNOWN &&
                script != expected && script != Script.NUMERIC && script != Script.UNKNOWN &&
                !(expected == Script.NUMERIC && script == Script.LATIN && c.text.count { it.isLetter() } <= 3)
            if (mismatch) { s -= 0.7; reasons += "script_mismatch" }
            // A configuration that reads one script producing the other script is guessing.
            if (c.engineScript == Script.ARABIC && script == Script.LATIN || c.engineScript == Script.LATIN && script == Script.ARABIC) s -= 0.3
            if (numericExpected && !OcrRouting.isNumeric(c.text) && c.text.count { it.isLetter() } > 2) s -= 0.3
            if (scriptLetters && OcrRouting.isNumeric(c.text) && c.text.none { it.isLetter() }) { s -= 0.3; reasons += "letters_read_as_digits" }
            // Same pattern of capitals, small letters and digits as the other cells of the column ("P1" beside "P2").
            if (shapes.isNotEmpty() && shape(c.text) in shapes) s += 0.1
            // Agreement with the other readings.
            val others = usable.filter { it !== c }
            if (others.isNotEmpty()) s += 0.2 * others.maxOf { similarity(c.text, it.text) }
            val nums = numbers(c.text).joinToString("|")
            if (agreedNumbers != null && nums.isNotEmpty() && nums != agreedNumbers) { s -= 0.5; reasons += "number_disagreement" }
            Triple(c, s, reasons)
        }.sortedByDescending { it.second }
        var (best, score, reasons) = scored.first()
        // A digit read as the letter of the same shape (digit 1 / alef "ا") in an Arabic context:
        // when a Latin reading of the same region confirms the digit there, the digit is kept
        // ("با" -> "ب1"). Only that letter changes, and only where another reading has the digit.
        if (expected == Script.ARABIC && best.text.contains('ا')) {
            val latinNumbers = usable.filter { it.engineScript == Script.LATIN || it.engineScript == Script.NUMERIC }.flatMap { numbers(it.text) }.toSet()
            val key = confusableKey(best.text)
            val withDigit = usable.firstOrNull { c -> c !== best && numbers(c.text).isNotEmpty() && confusableKey(c.text) == key &&
                numbers(c.text).all { it in latinNumbers } && ScriptDetector.count(c.text).arabic > 0 }
            if (withDigit != null) {
                val digits = withDigit.text.filter { !it.isWhitespace() }
                val fixed = best.text.filter { !it.isWhitespace() }.mapIndexed { i, ch -> if (ch == 'ا' && digits.getOrNull(i) == '1') '1' else ch }.joinToString("")
                best = best.copy(text = fixed, confidence = maxOf(best.confidence ?: 0f, withDigit.confidence ?: 0f))
                reasons = ArrayList(reasons + "digit_confirmed")
            }
        }
        // Readings that differ only by case ("p1", "P1"): the letters agree, the column's pattern
        // decides the case.
        if (shapes.isNotEmpty() && shape(best.text) !in shapes) {
            scored.drop(1).firstOrNull { it.first.text.equals(best.text, ignoreCase = true) && shape(it.first.text) in shapes }?.let {
                best = it.first; score = it.second; reasons = it.third
            }
        }
        val margin = if (scored.size > 1) score - scored[1].second else 1.0
        val review = ArrayList(reasons)
        if ((best.confidence ?: 1f) < 0.6f) review += "low_confidence"
        if (plausibility(best.text) < 0.5) review += "implausible"
        if (margin < 0.05 && scored.size > 1 && similarity(best.text, scored[1].first.text) < 0.8) review += "close_alternatives"
        val bestNums = numbers(best.text).joinToString("|")
        if (bestNums.isNotEmpty() && voters.size > 1 && (numberVotes[bestNums] ?: 0) <= 1 && numberVotes.size > 1) review += "number_unconfirmed"
        return Evaluation(best, score, needsReview = review.isNotEmpty(), reasons = review)
    }
}

/** Error rates between an expected text and a recognized one. */
object TextMetrics {

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun <T> levenshtein(a: List<T>, b: List<T>): Int {
        var prev = IntArray(b.size + 1) { it }
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            val t = prev; prev = cur; cur = t
        }
        return prev[b.size]
    }

    /** Spaces collapsed, nothing else changed (diacritics, case and letter forms count). */
    fun normalizeSpaces(s: String) = s.trim().replace(Regex("\\s+"), " ")

    /** Character error rate: edits / expected characters. */
    fun cer(expected: String, actual: String): Double {
        val e = normalizeSpaces(expected)
        val a = normalizeSpaces(actual)
        return if (e.isEmpty()) (if (a.isEmpty()) 0.0 else 1.0) else levenshtein(e, a).toDouble() / e.length
    }

    /** Word error rate: word edits / expected words. */
    fun wer(expected: String, actual: String): Double {
        val e = normalizeSpaces(expected).split(' ').filter { it.isNotEmpty() }
        val a = normalizeSpaces(actual).split(' ').filter { it.isNotEmpty() }
        return if (e.isEmpty()) (if (a.isEmpty()) 0.0 else 1.0) else levenshtein(e, a).toDouble() / e.size
    }
}
