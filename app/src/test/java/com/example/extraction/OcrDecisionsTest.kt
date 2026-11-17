package com.example.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which reading of a line is kept, which image rows were missed, and duplicate protection. */
class OcrDecisionsTest {

    private fun line(vararg words: Pair<String, Float>, top: Float = 0f): TextLine {
        var x = 1000f
        val ws = words.map { (t, c) -> TextWord(t, Box(x - t.length * 10f, top, x, top + 20f), c).also { x -= t.length * 10f + 10f } }
        return TextLine(ws, Box.of(ws.map { it.box })!!)
    }

    @Test
    fun arabicLineWithEnglishSubstitutions_isReadAgainAndTheArabicReadingKept() {
        // The multi-language pass replaced two Arabic words by Latin garbage with low confidence.
        val page = line("المياه" to 0.9f, "Ugall" to 0.41f, "مصدر" to 0.88f, "ylu" to 0.35f)
        assertTrue(OcrDecisions.needsArabicReread(page, pageModelArabicFirst = false))
        val arabic = line("المياه" to 0.91f, "الجوفية" to 0.87f, "مصدر" to 0.9f, "أساسي" to 0.86f)
        assertSame(arabic, OcrDecisions.choose(page, arabic, arabicExpected = true))
    }

    @Test
    fun realLatinTermsReadWithHighConfidence_areNotTradedAway() {
        val page = line("قياس" to 0.9f, "pH" to 0.95f, "العينات" to 0.9f)
        val arabicOnly = line("قياس" to 0.92f, "بح" to 0.6f, "العينات" to 0.91f)
        assertSame(page, OcrDecisions.choose(page, arabicOnly, arabicExpected = true))
    }

    @Test
    fun choice_isByConfidence_notByLength() {
        val good = line("Les" to 0.93f, "eaux" to 0.95f, "souterraines" to 0.9f)
        val longerButWorse = line("Les" to 0.5f, "eaux" to 0.4f, "sou" to 0.3f, "terr" to 0.3f, "aines" to 0.3f, "xx" to 0.2f)
        assertSame(good, OcrDecisions.choose(good, longerButWorse, arabicExpected = false))
        assertSame(good, OcrDecisions.choose(good, null, arabicExpected = false))
        // A weak Latin line is read again with French + English only.
        assertTrue(OcrDecisions.needsLatinReread(longerButWorse))
        assertFalse(OcrDecisions.needsLatinReread(good))
        // An Arabic line read Arabic-first with good confidence is not read again.
        assertFalse(OcrDecisions.needsArabicReread(line("المياه" to 0.92f, "الجوفية" to 0.9f), pageModelArabicFirst = true))
    }

    @Test
    fun missedBands_onlyRowsNoLineCovers_withoutPicturesOrSpecks() {
        val lines = listOf(Box(100f, 100f, 900f, 130f), Box(100f, 150f, 900f, 180f))
        val bands = listOf(100..130, 150..180, 200..230, 300..302, 400..600)
        // 200..230 was missed; 300..302 is a speck; 400..600 is a picture.
        assertEquals(listOf(200..230), OcrDecisions.missedBands(bands, lines))
        assertTrue(OcrDecisions.missedBands(emptyList(), lines).isEmpty())
    }

    @Test
    fun recoveredLinesThatRepeatText_areDropped_andInsertedInOrder() {
        val existing = listOf(line("المياه" to 0.9f, "الجوفية" to 0.9f, "مصدر" to 0.9f, top = 100f), line("أساسي" to 0.9f, top = 200f))
        assertTrue(OcrDecisions.isDuplicate("المياه الجوفية مصدر", existing))
        assertFalse(OcrDecisions.isDuplicate("الكبرى على الآبار العميقة", existing))
        assertEquals(1, OcrDecisions.insertionIndex(existing, 150f))
        assertEquals(2, OcrDecisions.insertionIndex(existing, 300f))
    }
}
