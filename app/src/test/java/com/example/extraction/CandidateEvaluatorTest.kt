package com.example.extraction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing and candidate choice, with the readings the device runs actually produced (runs #65 to
 * #69): an Arabic header read "sal" by the Latin model, "35 mg/l" read "35 اروم", "Product" read
 * "21001", "Price" read "محم / اب".
 */
class CandidateEvaluatorTest {

    private fun c(text: String, conf: Float?, engine: String, script: Script? = null) = RecognitionCandidate(text, conf, engine, script)

    @Test
    fun frenchCellReadAsArabic_loses_toTheLatinReading_inALatinRow() {
        val e = CandidateEvaluator.evaluate(listOf(
            c("35 اروم", 0.78f, "page"),
            c("35 اروم", 0.74f, "ara", Script.ARABIC),
            c("35 mg/l", 0.71f, "fra", Script.LATIN)
        ), expected = Script.LATIN)
        assertEquals("35 mg/l", e.chosen!!.text)
    }

    @Test
    fun arabicHeaderReadAsLatin_loses_inAnArabicRow_evenWithAHigherConfidence() {
        val e = CandidateEvaluator.evaluate(listOf(
            c("sal", 0.86f, "page"),
            c("sal", 0.88f, "fra+eng", Script.LATIN),
            c("البئر", 0.74f, "ara", Script.ARABIC)
        ), expected = Script.ARABIC)
        assertEquals("البئر", e.chosen!!.text)
    }

    @Test
    fun englishHeadersReadAsArabicOrDigits_lose() {
        assertEquals("Product", CandidateEvaluator.evaluate(listOf(
            c("21001", 0.9f, "ara", Script.ARABIC), c("Product", 0.86f, "eng", Script.LATIN), c("Product", 0.9f, "page")
        ), expected = Script.LATIN).chosen!!.text)
        assertEquals("Price", CandidateEvaluator.evaluate(listOf(
            c("محم / اب", 0.84f, "ara", Script.ARABIC), c("Price", 0.8f, "eng", Script.LATIN)
        ), expected = Script.LATIN).chosen!!.text)
    }

    @Test
    fun choice_isNotByLength_andGarbageIsImplausible() {
        val e = CandidateEvaluator.evaluate(listOf(
            c("Les eaux souterraines", 0.9f, "fra", Script.LATIN),
            c("Lés eaux sou terr aines xx TOOLICT", 0.7f, "page")
        ), expected = Script.LATIN)
        assertEquals("Les eaux souterraines", e.chosen!!.text)
        assertTrue(CandidateEvaluator.latinPlausibility("TOOLICT Àqqq bcdfgh") < 0.5)
        assertTrue(CandidateEvaluator.latinPlausibility("Les eaux de la région") > 0.9)
        assertTrue(CandidateEvaluator.arabicPlausibility("ااإحمممامءع") < 0.5)
        assertTrue(CandidateEvaluator.arabicPlausibility("المياه الجوفية في المنطقة") > 0.9)
    }

    @Test
    fun numbers_areVoted_andAnUnconfirmedNumberIsMarkedForReview() {
        val e = CandidateEvaluator.evaluate(listOf(c("120", 0.8f, "page"), c("120", 0.7f, "numeric", Script.NUMERIC), c("170", 0.95f, "ara", Script.ARABIC)), numericExpected = true)
        assertEquals("120", e.chosen!!.text)
        val alone = CandidateEvaluator.evaluate(listOf(c("85", 0.9f, "page"), c("65", 0.88f, "numeric", Script.NUMERIC)), numericExpected = true)
        assertTrue(alone.needsReview)
        assertEquals(listOf("12,5", "2024", "35"), CandidateEvaluator.numbers("12,5 % en 2024 ٣٥"))
    }

    @Test
    fun arabicLineWithLatinTerm_keepsTheTerm() {
        val e = CandidateEvaluator.evaluate(listOf(
            c("تم قياس درجة الحموضة pH في كل عينة", 0.86f, "page"),
            c("تم قياس درجة الحموضة بح في كل عينة", 0.84f, "ara", Script.ARABIC)
        ), expected = Script.ARABIC)
        assertTrue(e.chosen!!.text.contains("pH") || e.chosen!!.text.contains("بح"))
        // Both are Arabic lines: no script mismatch is reported for the Latin term.
        assertFalse(e.reasons.contains("script_mismatch"))
    }

    @Test
    fun nothingReliable_isKeptAndMarked_nothingInvented() {
        val none = CandidateEvaluator.evaluate(listOf(c("", 0.9f, "page"), c("  ", 0.5f, "ara")))
        assertEquals(null, none.chosen)
        assertTrue(none.needsReview)
        val weak = CandidateEvaluator.evaluate(listOf(c("xq", 0.3f, "page")))
        assertEquals("xq", weak.chosen!!.text)
        assertTrue(weak.needsReview)
    }

    @Test
    fun routing_byScriptAndLanguage() {
        assertEquals(OcrConfig.ARABIC, OcrRouting.plan("المياه الجوفية", null).first())
        assertEquals(OcrConfig.FRENCH, OcrRouting.plan("Les eaux de la région", null).first())
        assertEquals(OcrConfig.ENGLISH, OcrRouting.plan("The wells of the basin", null).first())
        assertEquals(OcrConfig.LATIN, OcrRouting.plan("Nitrates", null).first())
        // The context wins over a wrong first reading: a Latin row with an Arabic-looking hint.
        assertEquals(OcrConfig.LATIN, OcrRouting.plan("35 اروم", Script.LATIN).first())
        assertEquals(OcrConfig.NUMERIC, OcrRouting.plan("120", null, numericOnly = true).first())
        assertEquals(listOf(OcrConfig.ARABIC), OcrRouting.plan("١٢٠", null, numericOnly = true))
        assertTrue(OcrRouting.isNumeric("12,5 %"))
        assertFalse(OcrRouting.isNumeric("P1"))
        assertEquals("fr", LatinLanguage.detect("Les valeurs dépassent la norme"))
        assertEquals("en", LatinLanguage.detect("The values are above the limit"))
        assertEquals(null, LatinLanguage.detect("Nitrates"))
    }

    @Test
    fun errorRates() {
        assertEquals(0.0, TextMetrics.cer("المياه الجوفية", "المياه  الجوفية"), 1e-9)
        assertEquals(1.0 / 14, TextMetrics.cer("المياه الجوفية", "المياه الجوفيه"), 1e-9)
        assertEquals(0.5, TextMetrics.wer("eaux souterraines", "eaux soutteraines"), 1e-9)
        assertEquals(1.0, TextMetrics.wer("one", ""), 1e-9)
    }

    @Test
    fun tableCells_getTheScriptOfTheirRow() {
        fun w(t: String, x: Float, y: Float) = TextWord(t, Box(x, y, x + t.length * 10f, y + 20f), 0.9f)
        val rules = listOf(
            Ruling(true, 0f, 0f, 900f), Ruling(true, 40f, 0f, 900f), Ruling(true, 80f, 0f, 900f),
            Ruling(false, 0f, 0f, 80f), Ruling(false, 300f, 0f, 80f), Ruling(false, 600f, 0f, 80f), Ruling(false, 900f, 0f, 80f)
        )
        // Row 0 Arabic header with one cell misread as Latin; row 1 French with one cell read as Arabic.
        val l0 = listOf(w("النترات", 20f, 10f), w("العمق", 320f, 10f), w("sal", 620f, 10f))
        val l1 = listOf(w("P1", 20f, 50f), w("120m", 320f, 50f), w("اروم", 620f, 50f))
        val lines = listOf(TextLine(l0, Box.of(l0.map { it.box })!!), TextLine(l1, Box.of(l1.map { it.box })!!))
        val asked = ArrayList<Pair<String, Script?>>()
        TableReconstructor.fromRulings(rules, lines, cellReader = CellReader { _, words, _, expected ->
            asked += words.joinToString(" ") { it.text } to expected; null
        })
        assertEquals(Script.ARABIC, asked.first { it.first == "sal" }.second)
        assertEquals(Script.LATIN, asked.first { it.first == "اروم" }.second)
    }
}
