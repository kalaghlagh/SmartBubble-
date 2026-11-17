package com.example.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.ocr.paddle.LineReading
import com.example.ocr.paddle.RecChar
import com.example.ocr.paddle.RecLine
import com.example.ocr.paddle.RecWords

/** Which reading of a line is kept, which image rows were missed, and duplicate protection. */
class OcrDecisionsTest {

    private fun line(vararg words: Pair<String, Float>, top: Float = 0f): TextLine {
        var x = 1000f
        val ws = words.map { (t, c) -> TextWord(t, Box(x - t.length * 10f, top, x, top + 20f), c).also { x -= t.length * 10f + 10f } }
        return TextLine(ws, Box.of(ws.map { it.box })!!)
    }

    /** A reading by one model: characters spread evenly over the crop, in visual order. */
    private fun reading(model: OcrModel, visual: String, conf: Float, confOf: (Int) -> Float = { conf }) = RecLine(
        visual, conf, visual.mapIndexed { i, c -> RecChar(c.toString(), (i + 0.5f) / visual.length, confOf(i)) }, 1f / visual.length, model.key
    )

    @Test
    fun arabicLineWithEnglishSubstitutions_isReadAgainAndTheArabicReadingKept() {
        // The Latin model turns an Arabic line into Latin garbage with low confidence.
        val latin = reading(OcrModel.LATIN, "Jo'll Loail dual yall", 0.31f)
        assertTrue(LineReading.needsSecond(latin))
        val arabic = reading(OcrModel.ARABIC, "ةساردلا ةقطنم", 0.95f)
        val r = LineReading.combine(latin, arabic)
        assertEquals(Script.ARABIC, r.script)
        assertEquals(OcrModel.ARABIC.key, r.line.model)
    }

    @Test
    fun realLatinTermsReadWithHighConfidence_areNotTradedAway() {
        // The Arabic model itself read "pH" (visual order "سايق pH ةنيعلا"), confidently.
        val arabic = reading(OcrModel.ARABIC, "ةنيعلا pH سايق", 0.95f)
        val latin = reading(OcrModel.LATIN, "  pH  ", 0.9f)
        val r = LineReading.combine(arabic, latin)
        val words = RecWords.words(r.line, Box(0f, 0f, 1000f, 40f)).map { it.text }
        assertEquals(listOf("قياس", "pH", "العينة"), words)
        assertEquals(0, r.mergedRuns)
    }

    @Test
    fun choice_isByConfidence_notByLength() {
        val good = reading(OcrModel.LATIN, "Les eaux souterraines", 0.95f)
        val longerButWorse = reading(OcrModel.ARABIC, "Les eaux sou terr aines xx", 0.4f)
        assertEquals("Les eaux souterraines", LineReading.combine(good, longerButWorse).line.text)
        assertEquals("Les eaux souterraines", LineReading.combine(longerButWorse, good).line.text)
        // A confident Latin reading needs no second model; a weak one does.
        assertFalse(LineReading.needsSecond(good))
        assertTrue(LineReading.needsSecond(reading(OcrModel.LATIN, "Les eaux sou terr", 0.4f)))
        // An Arabic line read confidently by the Arabic model is not read again.
        assertFalse(LineReading.needsSecond(reading(OcrModel.ARABIC, "ةيفوجلا هايملا", 0.95f)))
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

    @Test
    fun fuseWords_restoresLettersReadAsDigits_neverTouchesRealNumbers() {
        // "موقع" came back as "2890" in one reading; another reading has the letters there.
        val kept = line("تم" to 0.9f, "2890" to 0.6f, "كل" to 0.9f, "2024" to 0.95f)
        val other = TextLine(kept.words.mapIndexed { i, w ->
            when (i) { 1 -> w.copy(text = "موقع", confidence = 0.93f); 3 -> w.copy(text = "2024"); else -> w }
        }, kept.box)
        val (fused, replaced) = OcrDecisions.fuseWords(kept, listOf(other), minConfidence = 0.9f)
        assertEquals(1, replaced)
        assertEquals(listOf("تم", "موقع", "كل", "2024"), fused.words.map { it.text })
        // Below the confidence asked for, nothing is replaced.
        assertEquals(0, OcrDecisions.fuseWords(kept, listOf(other.copy(words = other.words.map { it.copy(confidence = 0.5f) })), minConfidence = 0.9f).second)
    }
}
