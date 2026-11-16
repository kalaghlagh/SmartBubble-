package com.example.core.voice

import java.util.Locale

/**
 * Pure text helpers for voice conversation (no Android types, unit-testable):
 * preparing AI replies for speech and choosing recognition/speech languages.
 */
object VoiceText {

    /** Languages the app supports for voice. */
    val SUPPORTED_LANGUAGES = listOf("ar", "en", "fr")

    /** Value of the voice-language setting that means "follow the app language". */
    const val AUTO = "auto"

    private val FRENCH_WORDS = setOf(
        "le", "la", "les", "des", "est", "et", "une", "un", "pour", "dans", "que", "qui", "vous",
        "nous", "avec", "sur", "pas", "ce", "cette", "du", "au", "sont", "votre", "je", "il", "elle"
    )
    private val ENGLISH_WORDS = setOf(
        "the", "and", "is", "of", "to", "you", "for", "with", "this", "that", "are", "it", "in",
        "your", "have", "was", "can", "will", "not", "on", "as", "be", "here", "i", "we"
    )
    private val FRENCH_ACCENTS = "éèêëàâçùûîïôœ"

    private fun isArabicLetter(c: Char) =
        c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'\uFEFF'

    /**
     * Detects whether [text] is Arabic, French or English so it is read with a matching voice.
     * [fallback] (the app language) is used when French and English cannot be told apart.
     */
    fun detectLanguage(text: String, fallback: String): String {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return normalize(fallback)
        val arabic = text.count { isArabicLetter(it) }
        if (arabic * 100 / letters >= 30) return "ar"

        val lower = text.lowercase(Locale.ROOT)
        val words = lower.split(Regex("""[^\p{L}']+""")).filter { it.isNotEmpty() }
        var french = words.count { it in FRENCH_WORDS } + lower.count { it in FRENCH_ACCENTS }
        val english = words.count { it in ENGLISH_WORDS }
        // Elisions such as l'…, d'…, qu'… are strong French signals.
        french += words.count { it.length > 2 && it[1] == '\'' && it[0] in "ldjqnmsc" }
        return when {
            french > english -> "fr"
            english > french -> "en"
            else -> normalize(fallback).takeIf { it != "ar" } ?: "en"
        }
    }

    private fun normalize(code: String): String =
        code.lowercase(Locale.ROOT).take(2).takeIf { it in SUPPORTED_LANGUAGES } ?: "en"

    /** Resolves the voice-language setting ("auto" follows the app language). */
    fun effectiveLanguage(voiceSetting: String, appLanguage: String): String =
        if (voiceSetting == AUTO || voiceSetting.isBlank()) normalize(appLanguage) else normalize(voiceSetting)

    /**
     * BCP-47 tag for speech recognition in [language]. The device's own regional variant is
     * used when it has the same language (e.g. ar-MA, fr-CA, en-GB), otherwise a common default.
     */
    fun recognitionTag(language: String, deviceLocale: Locale): String {
        val lang = normalize(language)
        if (deviceLocale.language == lang && deviceLocale.country.isNotEmpty()) {
            return "$lang-${deviceLocale.country}"
        }
        return when (lang) {
            "ar" -> "ar-SA"
            "fr" -> "fr-FR"
            else -> "en-US"
        }
    }

    /** Locales to try for text-to-speech in [language], most specific first. */
    fun speechLocales(language: String, deviceLocale: Locale): List<Locale> {
        val lang = normalize(language)
        val candidates = mutableListOf<Locale>()
        if (deviceLocale.language == lang) candidates += deviceLocale
        candidates += when (lang) {
            "ar" -> listOf(Locale("ar", "SA"), Locale("ar"))
            "fr" -> listOf(Locale.FRANCE, Locale.FRENCH)
            else -> listOf(Locale.US, Locale.UK, Locale.ENGLISH)
        }
        return candidates.distinct()
    }

    /** Removes Markdown syntax so symbols such as ** or # are not read aloud. */
    fun stripMarkdown(text: String): String {
        var t = text.replace("\r\n", "\n")
        t = t.replace(Regex("""```[a-zA-Z0-9]*\n?"""), "")                 // code fences
        t = t.replace(Regex("""!\[([^\]]*)]\([^)]*\)"""), "$1")             // images
        t = t.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")              // links
        t = t.replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")               // headings
        t = t.replace(Regex("""(?m)^\s*>\s?"""), "")                        // block quotes
        t = t.replace(Regex("""(?m)^\s*[-*+•]\s+"""), "")                   // bullets
        t = t.replace(Regex("""(?m)^\s*\|?[\s:|-]*-{3,}[\s:|-]*\|?\s*$"""), "") // table rules
        t = t.replace(Regex("""\s*\|\s*"""), ", ")                          // table cells
        t = t.replace(Regex("""(\*\*|__|~~|`)"""), "")                      // bold, strike, code
        t = t.replace(Regex("""(?<![\p{L}\p{N}])[*_](?=\S)|(?<=\S)[*_](?![\p{L}\p{N}])"""), "") // emphasis
        t = t.replace(Regex("""(?m)^[,\s]+|[,\s]+$"""), "")                 // leftovers at line edges
        t = t.replace(Regex("""[ \t]{2,}"""), " ")
        t = t.replace(Regex("""\n{3,}"""), "\n\n")
        return t.trim()
    }

    /**
     * Splits [text] into sentence-sized chunks of at most [maxLength] characters. Each chunk is
     * one utterance, which is what makes pause/resume possible (resume restarts the chunk that
     * was being spoken). No text is dropped.
     */
    fun splitForSpeech(text: String, maxLength: Int): List<String> {
        require(maxLength > 0)
        val sentences = text.split(Regex("""(?<=[.!?؟…。:;])\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        for (sentence in sentences) {
            var rest = sentence
            while (rest.length > maxLength) {
                val cut = rest.lastIndexOf(' ', maxLength).takeIf { it > maxLength / 2 } ?: maxLength
                chunks += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) chunks += rest
        }
        return chunks
    }
}
