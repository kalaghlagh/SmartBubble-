package com.example

import com.example.core.voice.VoiceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceTextTest {

    @Test
    fun detectsArabicFrenchAndEnglishReplies() {
        assertEquals("ar", VoiceText.detectLanguage("الذكاء الاصطناعي هو مجال من علوم الحاسوب.", "en"))
        assertEquals("fr", VoiceText.detectLanguage("Voici le résumé que vous avez demandé pour la réunion.", "en"))
        assertEquals("fr", VoiceText.detectLanguage("L'intelligence artificielle est un domaine de l'informatique.", "ar"))
        assertEquals("en", VoiceText.detectLanguage("Here is the summary you asked for.", "fr"))
        // Mostly Arabic with a few Latin terms is still Arabic.
        assertEquals("ar", VoiceText.detectLanguage("تم إنشاء ملف Word بنجاح وحفظه في المكتبة", "en"))
    }

    @Test
    fun ambiguousTextFallsBackToAppLanguageButNeverArabicForLatinText() {
        assertEquals("fr", VoiceText.detectLanguage("OK", "fr"))
        assertEquals("en", VoiceText.detectLanguage("OK", "ar"))
        assertEquals("en", VoiceText.detectLanguage("12345", "en"))
    }

    @Test
    fun voiceLanguageSettingResolves() {
        assertEquals("ar", VoiceText.effectiveLanguage("auto", "ar"))
        assertEquals("fr", VoiceText.effectiveLanguage("fr", "ar"))
        assertEquals("en", VoiceText.effectiveLanguage("", "xx"))
    }

    @Test
    fun recognitionTagsUseDeviceRegionWhenLanguageMatches() {
        assertEquals("ar-SA", VoiceText.recognitionTag("ar", Locale.US))
        assertEquals("fr-FR", VoiceText.recognitionTag("fr", Locale.US))
        assertEquals("en-US", VoiceText.recognitionTag("en", Locale.FRANCE))
        assertEquals("ar-MA", VoiceText.recognitionTag("ar", Locale("ar", "MA")))
        assertEquals("fr-CA", VoiceText.recognitionTag("fr", Locale.CANADA_FRENCH))
        assertEquals("en-GB", VoiceText.recognitionTag("en", Locale.UK))
    }

    @Test
    fun speechLocalesAreInTheRequestedLanguage() {
        for (lang in VoiceText.SUPPORTED_LANGUAGES) {
            val locales = VoiceText.speechLocales(lang, Locale.GERMANY)
            assertTrue(locales.isNotEmpty())
            assertTrue(locales.all { it.language == lang })
        }
        assertEquals(Locale("ar", "MA"), VoiceText.speechLocales("ar", Locale("ar", "MA")).first())
    }

    @Test
    fun markdownSymbolsAreNotSpoken() {
        val md = """
            ## Summary
            Here is **the report** with `code` and a [link](https://example.com).

            - First point
            * Second *point*

            | Item | Qty |
            |------|-----|
            | Pens | 3 |
        """.trimIndent()
        val spoken = VoiceText.stripMarkdown(md)
        listOf("#", "*", "`", "|", "](", "https://", "---").forEach { assertFalse("contains $it", spoken.contains(it)) }
        assertTrue(spoken.contains("Summary"))
        assertTrue(spoken.contains("the report"))
        assertTrue(spoken.contains("link"))
        assertTrue(spoken.contains("First point"))
        assertTrue(spoken.contains("Second point"))
        assertTrue(spoken.contains("Pens, 3"))
    }

    @Test
    fun markdownStrippingKeepsArabicAndOrdinaryPunctuation() {
        assertEquals("تقرير المبيعات: 3 * 4 = 12", VoiceText.stripMarkdown("**تقرير المبيعات**: 3 * 4 = 12"))
        assertEquals("snake_case stays", VoiceText.stripMarkdown("snake_case stays"))
    }

    @Test
    fun splitKeepsAllTextInOrderWithinTheLimit() {
        val text = "First sentence. Second one! هل هذا سؤال؟ نعم.\nNew line here " + "word ".repeat(100)
        val chunks = VoiceText.splitForSpeech(text, 80)
        assertTrue(chunks.all { it.isNotBlank() && it.length <= 80 })
        assertEquals("First sentence.", chunks[0])
        assertEquals("Second one!", chunks[1])
        assertEquals("هل هذا سؤال؟", chunks[2])
        // Nothing is lost: all words survive, in order.
        val words = { s: String -> s.split(Regex("\\s+")).filter { it.isNotEmpty() } }
        assertEquals(words(text), words(chunks.joinToString(" ")))
    }

    @Test
    fun splitHandlesTextWithoutSpacesAndEmptyText() {
        val chunks = VoiceText.splitForSpeech("x".repeat(250), 100)
        assertEquals(listOf(100, 100, 50), chunks.map { it.length })
        assertTrue(VoiceText.splitForSpeech("  \n ", 100).isEmpty())
    }
}
