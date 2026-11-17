package com.example.ocr.paddle

import com.example.extraction.Box
import com.example.extraction.ExtractedBlock
import com.example.extraction.ExtractedDocument
import com.example.extraction.ExtractedPage
import com.example.extraction.OcrModel
import com.example.extraction.PageSource
import com.example.extraction.Script
import com.example.extraction.TableCell
import com.example.extraction.TextWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** The parts of the OCR engine that do not need a device: decoding, boxes, direction, merging, models. */
class PaddleCoreTest {

    // ------------------------------------------------------------------ CTC decoding

    /** Probabilities (softmax rows) for a sequence of class indices, one row per step. */
    private fun rows(classes: Int, vararg best: Int): FloatArray {
        val out = FloatArray(best.size * classes) { 0.01f / (classes - 1) }
        best.forEachIndexed { t, c ->
            for (k in 0 until classes) out[t * classes + k] = if (k == c) 0.99f else 0.01f / (classes - 1)
        }
        return out
    }

    @Test
    fun ctc_collapsesRepeats_dropsBlanks_reportsPositions() {
        val vocab = listOf("", "a", "b", " ")
        // a a _ a b _ b  ->  "aab b"? no: repeats collapse only when adjacent: a,(a),_,a,b,_,b -> "aabb"
        val line = CtcDecoder.decode(rows(4, 1, 1, 0, 1, 2, 0, 2), 7, 4, vocab)
        assertEquals("aabb", line.text)
        assertEquals(4, line.chars.size)
        assertTrue("positions increase", line.chars.zipWithNext().all { (x, y) -> y.x > x.x })
        assertEquals(0.99f, line.confidence, 0.001f)
        // Padding: positions are relative to the crop, not to the padded input.
        val padded = CtcDecoder.decode(rows(4, 1, 0, 0, 0), 4, 4, vocab, fill = 0.5f)
        assertEquals(0.25f, padded.chars.single().x, 0.001f)
    }

    @Test
    fun ctc_rawScores_areTurnedIntoProbabilities() {
        val vocab = listOf("", "x")
        val logits = floatArrayOf(0f, 5f) // not a softmax row
        val line = CtcDecoder.decode(logits, 1, 2, vocab)
        assertEquals("x", line.text)
        assertEquals((1 / (1 + Math.exp(-5.0))).toFloat(), line.confidence, 0.001f)
    }

    @Test
    fun vocabulary_matchesTheModel_orFails() {
        assertEquals(listOf("", "a", "b"), CtcDecoder.vocabulary(listOf("a", "b", ""), 3))
        assertEquals(listOf("", "a", "b", " "), CtcDecoder.vocabulary(listOf("a", "b"), 4))
        try {
            CtcDecoder.vocabulary(listOf("a", "b"), 9)
            fail("a dictionary that does not match the model must be refused")
        } catch (e: IllegalStateException) { /* expected */ }
    }

    // ------------------------------------------------------------------ detection boxes

    @Test
    fun db_findsOneBoxPerTextRegion_enlargedByTheUnclipRatio() {
        val w = 100; val h = 40
        val prob = FloatArray(w * h)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, p: Float) { for (y in y0 until y1) for (x in x0 until x1) prob[y * w + x] = p }
        fill(5, 5, 45, 15, 0.9f)   // a line
        fill(55, 5, 95, 15, 0.9f)  // a second region on the same row
        fill(5, 25, 15, 28, 0.4f)  // weak noise: below the box threshold
        val boxes = DbPostProcess.boxes(prob, w, h).sortedBy { it.left }
        assertEquals(2, boxes.size)
        val b = boxes[0]
        // Offset = length x thickness x 1.5 / (2 x (length + thickness)) = 40 x 10 x 1.5 / 100 = 6 px
        // (PaddleOCR's unclip); the engine clamps boxes to the page afterwards.
        assertEquals(5f - 6f, b.left, 0.01f)
        assertEquals(45f + 6f, b.right, 0.01f)
        assertEquals(5f - 6f, b.top, 0.01f)
        assertEquals(15f + 6f, b.bottom, 0.01f)
    }

    // ------------------------------------------------------------------ right to left

    @Test
    fun rtl_visualToLogical_keepsNumbersAndLatinRuns() {
        // "العمق 120" is read by CTC in visual order: "120 قمعلا".
        assertEquals("العمق 120", RtlText.visualToLogical("120 قمعلا"))
        // A label with a digit: "ب1" appears as "1ب".
        assertEquals("ب1", RtlText.visualToLogical("1ب"))
        // A Latin phrase inside Arabic keeps its order.
        assertEquals("جهاز GPS Pro لتحديد", RtlText.visualToLogical("ديدحتل GPS Pro زاهج"))
        // Decimal separators and brackets.
        assertEquals("النسبة (12,5)", RtlText.visualToLogical("(12,5) ةبسنلا"))
        // Latin text is untouched.
        assertEquals("Product A 10", RtlText.visualToLogical("Product A 10"))
    }

    @Test
    fun rtl_wordOrder_keepsLatinRunsInOrder() {
        val visual = listOf("ديدحتل", "GPS", "Pro", "زاهج")
        assertEquals(listOf(3, 1, 2, 0), RtlText.logicalWordOrder(visual, rtlLine = true))
        assertEquals(listOf(0, 1, 2, 3), RtlText.logicalWordOrder(visual, rtlLine = false))
    }

    @Test
    fun rtl_numbersBetweenArabicWords_areSeparateUnits_afterLatinTheyJoinIt() {
        // Seen with the real Arabic model: "ب1 120 35" is drawn "35 120 1ب".
        assertEquals(listOf(2, 1, 0), RtlText.logicalWordOrder(listOf("35", "120", "ب1"), rtlLine = true))
        // "Version 2.5" inside Arabic stays "Version 2.5".
        assertEquals(listOf(3, 1, 2, 0), RtlText.logicalWordOrder(listOf("قبل", "Version", "2.5", "بعد"), rtlLine = true))
        // Punctuation around a number changes side in a right-to-left line: "2." is drawn ".2".
        assertEquals("2.", RtlText.wordToLogical(".2", rtlLine = true))
        assertEquals("(12,5)", RtlText.wordToLogical("(12,5)", rtlLine = true))
        assertEquals(".2", RtlText.wordToLogical(".2", rtlLine = false))
        assertEquals("ب1", RtlText.wordToLogical("1ب", rtlLine = true))
    }

    @Test
    fun arabicLetters_persianFormsAndStrayTatweel_only() {
        assertEquals("كل", ArabicLetters.clean("کل"))
        assertEquals("على", ArabicLetters.clean("علی"))
        assertEquals("يكتب", ArabicLetters.clean("یکتب"))
        assertEquals("أ-", ArabicLetters.clean("أـ-"))
        // A tatweel between two letters is kept (it is written that way), Latin is untouched.
        assertEquals("كـتاب", ArabicLetters.clean("كـتاب"))
        assertEquals("Version", ArabicLetters.clean("Version"))
    }

    @Test
    fun rtl_recoversDroppedArabicWordSpaces_only() {
        val chars = listOf(RecChar("ب", 0.10f, 0.9f), RecChar("ي", 0.12f, 0.9f), RecChar("ت", 0.14f, 0.9f),
            RecChar("ك", 0.30f, 0.9f), RecChar("ل", 0.32f, 0.9f), RecChar("ا", 0.34f, 0.9f))
        val out = RtlText.recoverWordSpaces(chars)
        assertEquals("بيت كلا", out.joinToString("") { it.text })
        // Latin letters spaced apart are left alone.
        val latin = listOf(RecChar("O", 0.1f, 0.9f), RecChar("C", 0.12f, 0.9f), RecChar("R", 0.14f, 0.9f), RecChar("X", 0.4f, 0.9f))
        assertEquals("OCRX", RtlText.recoverWordSpaces(latin).joinToString("") { it.text })
    }

    // ------------------------------------------------------------------ mixed Arabic / Latin lines

    private fun arabicLine(vararg c: Triple<String, Float, Float>) =
        RecLine(c.joinToString("") { it.first }, c.map { it.third }.average().toFloat(), c.map { RecChar(it.first, it.second, it.third) }, 0.02f, "arabic")

    @Test
    fun merge_replacesAnUnsureStretchWithConfidentLatin_andNothingElse() {
        // Visual order: Arabic word, the term "GPS" misread with low confidence, Arabic word.
        val primary = arabicLine(
            Triple("ز", 0.05f, 0.97f), Triple("ا", 0.07f, 0.97f), Triple("ه", 0.09f, 0.97f), Triple("ج", 0.11f, 0.97f), Triple(" ", 0.15f, 0.9f),
            Triple("ح", 0.40f, 0.55f), Triple("ب", 0.43f, 0.5f), Triple("س", 0.46f, 0.5f), Triple(" ", 0.55f, 0.9f),
            Triple("ل", 0.70f, 0.96f), Triple("ك", 0.72f, 0.96f)
        )
        val latin = RecLine("GPS", 0.95f, listOf(RecChar("G", 0.40f, 0.95f), RecChar("P", 0.43f, 0.96f), RecChar("S", 0.46f, 0.95f)), 0.02f, "latin")
        val merged = ScriptMerge.merge(primary, latin)
        assertEquals(1, merged.replacedRuns)
        assertEquals("زاهج GPS لك", merged.line.text)
        // A Latin reading of confident Arabic is ignored.
        val sure = arabicLine(Triple("ح", 0.40f, 0.97f), Triple("ب", 0.43f, 0.97f), Triple("س", 0.46f, 0.97f))
        assertEquals(0, ScriptMerge.merge(sure, latin).replacedRuns)
    }

    @Test
    fun lineReading_arabicLineMergesLatin_latinLineKeepsTheLatinModel() {
        val arabic = arabicLine(Triple("ز", 0.05f, 0.97f), Triple("ا", 0.07f, 0.97f), Triple("ه", 0.09f, 0.97f), Triple("ج", 0.11f, 0.97f))
        assertFalse(LineReading.needsSecond(arabic))
        val r = LineReading.combine(arabic, null)
        assertEquals(Script.ARABIC, r.script)
        // A Latin line: the Arabic model's reading has no Arabic letters, the Latin one wins.
        val viaArabic = RecLine("Groundwatcr is", 0.7f, emptyList(), 0f, "arabic")
        assertTrue(LineReading.needsSecond(viaArabic))
        val viaLatin = RecLine("Groundwater is", 0.97f, emptyList(), 0f, "latin")
        val c = LineReading.combine(viaArabic, viaLatin)
        assertEquals("latin", c.line.model)
        assertEquals(listOf("arabic", "latin"), c.models)
        // A confident Latin reading needs no second model.
        assertFalse(LineReading.needsSecond(RecLine("The wells were sampled", 0.96f, listOf(RecChar("T", 0.1f, 0.96f)), 0f, OcrModel.LATIN.key)))
    }

    // ------------------------------------------------------------------ words and lines

    @Test
    fun words_haveBoxes_andArabicReadingOrder() {
        // Visual: "120" (left) then "قمعلا" (right) for the logical "العمق 120".
        val chars = listOf(RecChar("1", 0.05f, 0.9f), RecChar("2", 0.08f, 0.9f), RecChar("0", 0.11f, 0.9f), RecChar(" ", 0.3f, 0.9f),
            RecChar("ق", 0.6f, 0.9f), RecChar("م", 0.63f, 0.9f), RecChar("ع", 0.66f, 0.9f), RecChar("ل", 0.69f, 0.9f), RecChar("ا", 0.72f, 0.9f))
        val words = RecWords.words(RecLine("120 قمعلا", 0.9f, chars, 0.02f, "arabic"), Box(100f, 50f, 1100f, 90f))
        assertEquals(listOf("العمق", "120"), words.map { it.text })
        assertTrue("the Arabic word is on the right", words[0].box.left > words[1].box.right)
        assertEquals(50f, words[0].box.top, 0f)
    }

    @Test
    fun regionsOnOneRow_formOneLine_inTheRowsDirection() {
        fun w(t: String, x: Float, y: Float) = TextWord(t, Box(x, y, x + 60f, y + 30f), 0.9f)
        val regions = listOf(
            Box(700f, 100f, 900f, 130f) to listOf(w("العمق", 700f, 100f)),
            Box(100f, 102f, 300f, 132f) to listOf(w("120", 100f, 102f)),
            Box(100f, 200f, 300f, 230f) to listOf(w("Next", 100f, 200f))
        )
        val lines = RecWords.lines(regions) { RecWords.LineInfo("arabic", it == 1, listOf("arabic")) }
        assertEquals(2, lines.size)
        assertEquals("العمق 120", lines[0].text)
        assertTrue(lines[0].needsReview)
        assertEquals("Next", lines[1].text)
    }

    // ------------------------------------------------------------------ model files

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun installer_verifiesInstallsAndRepairs() {
        val dir = Files.createTempDirectory("models").toFile()
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        val model = ModelFile("paddleocr/v5/m.ort", bytes.size.toLong(), sha(bytes))
        var opened = 0
        val installer = ModelInstaller(dir) { opened++; ByteArrayInputStream(bytes) }
        val f = installer.file(model)
        assertEquals(sha(bytes), ModelInstaller.sha256(f))
        installer.file(model)
        assertEquals("verified once per process", 1, opened)
        // Damaged on disk: replaced from the assets by a new installer (new process).
        f.writeBytes(bytes.copyOf(1000))
        val again = ModelInstaller(dir) { opened++; ByteArrayInputStream(bytes) }.file(model)
        assertEquals(bytes.size.toLong(), again.length())
        assertEquals(2, opened)
    }

    @Test
    fun installer_refusesDamagedOrTruncatedAssets_andLeavesNoFile() {
        val dir = Files.createTempDirectory("models").toFile()
        val bytes = ByteArray(10_000) { it.toByte() }
        val model = ModelFile("paddleocr/v5/det.ort", bytes.size.toLong(), sha(bytes))
        val damaged = bytes.copyOf().also { it[5000] = (it[5000] + 1).toByte() }
        for (asset in listOf(damaged, bytes.copyOf(9_000))) {
            try {
                ModelInstaller(dir) { ByteArrayInputStream(asset) }.file(model)
                fail("a damaged model must be refused")
            } catch (e: ModelIntegrityException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("det.ort"))
            }
            assertTrue("no file left: ${dir.list()?.toList()}", dir.listFiles().orEmpty().isEmpty())
        }
        // Unreadable asset (missing from the app).
        try {
            ModelInstaller(dir) { throw java.io.FileNotFoundException(it) }.file(model)
            fail("a missing model must be reported")
        } catch (e: ModelIntegrityException) { /* expected */ }
    }

    @Test
    fun bundledModels_matchTheirChecksums() {
        // The files in assets/paddleocr/v5 are the ones the engine expects.
        val root = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        for (m in PaddleModels.ALL) {
            val f = File(root, m.asset)
            assertTrue("missing ${m.asset}", f.isFile)
            assertEquals(m.asset, m.bytes, f.length())
            assertEquals(m.asset, m.sha256, ModelInstaller.sha256(f))
        }
        // The dictionaries fit their recognition models' class count once a blank is added.
        val arabic = File(root, PaddleModels.ARABIC_DICT.asset).readLines()
        assertTrue(arabic.size > 200)
    }

    // ------------------------------------------------------------------ templates

    @Test
    fun scannedTemplate_getsItsStructure_notItsText() {
        val profile = com.example.template.domain.TemplateProfile(templateId = "t", name = "Scan", originalFileType = "PDF",
            layoutSource = "PDF", pdfTextStatus = "NO_TEXT", extractedPropertiesSummary = "Page size")
        val doc = ExtractedDocument(listOf(ExtractedPage(0, PageSource.PDF_OCR, listOf(
            ExtractedBlock.Heading("المقدمة", 1, rtl = true),
            ExtractedBlock.ListItem("الآبار", "أ-", ordered = true, level = 0, rtl = true),
            ExtractedBlock.ListItem("الينابيع", "أ-", ordered = true, level = 0, rtl = true),
            ExtractedBlock.Table(listOf(listOf(TableCell("a"), TableCell("b"))), header = false, rtl = true, uncertain = false, detectedBy = "rules")
        ))))
        val p = com.example.template.analysis.ScannedTemplateStructure.apply(profile, doc)
        assertTrue(p.rtl)
        assertTrue(p.tableDetected)
        assertEquals("أ-", p.listMarker)
        assertTrue(p.extractedPropertiesSummary, p.extractedPropertiesSummary.contains("1 headings, 1 tables, 2 list items"))
        assertEquals("NO_TEXT", p.pdfTextStatus)
        assertFalse("no recognized text in the profile", p.toJson().contains("الآبار"))
    }
}
