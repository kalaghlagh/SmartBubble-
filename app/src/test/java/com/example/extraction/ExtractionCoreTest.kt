package com.example.extraction

import com.example.template.pdf.PdfContentScanner
import com.example.template.pdf.PdfDocumentReader
import com.example.template.pdf.TestPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document extraction core (pure): script detection, table reconstruction with and without
 * borders (left-to-right and Arabic right-to-left), structure (headings, lists with their own
 * markers, captions, page numbers, paragraphs) and direct reading of text PDFs.
 */
class ExtractionCoreTest {

    // ------------------------------------------------------------------ helpers: a fake page

    /** Words laid out from [x] at [y] (each char 10 px wide, line 20 px high), left to right. */
    private fun ltr(text: String, x: Float, y: Float, conf: Float = 0.9f): List<TextWord> {
        var cx = x
        return text.split(' ').map { w ->
            TextWord(w, Box(cx, y, cx + w.length * 10f, y + 20f), conf).also { cx += w.length * 10f + 10f }
        }
    }

    /** Words of an Arabic phrase laid out from the right edge [right], in reading order (right to left). */
    private fun rtl(text: String, right: Float, y: Float, conf: Float = 0.9f): List<TextWord> {
        var cx = right
        return text.split(' ').map { w ->
            TextWord(w, Box(cx - w.length * 10f, y, cx, y + 20f), conf).also { cx -= w.length * 10f + 10f }
        }
    }

    private fun line(words: List<TextWord>) = TextLine(words, Box.of(words.map { it.box })!!)

    /** A table row: cells starting at the given x positions (LTR). */
    private fun row(y: Float, vararg cells: Pair<Float, String>) = line(cells.flatMap { (x, t) -> ltr(t, x, y) })

    // ------------------------------------------------------------------ scripts

    @Test
    fun scripts_areDecidedByLetters_notDigitsOrAFewLatinTerms() {
        assertEquals(Script.ARABIC, ScriptDetector.classify("تم قياس درجة الحموضة pH في 12 بئرا"))
        assertEquals(Script.LATIN, ScriptDetector.classify("La teneur en nitrates est de 52 mg/l"))
        assertEquals(Script.NUMERIC, ScriptDetector.classify("12,5 – 2024 ٣٥"))
        assertEquals(Script.MIXED, ScriptDetector.classify("المياه الجوفية eaux souterraines"))
        assertTrue(ScriptDetector.isRtl("المياه water"))
        assertFalse(ScriptDetector.isRtl("Analyse des puits 2024"))
        assertEquals(setOf(Script.ARABIC, Script.LATIN), ScriptDetector.scriptsOf(listOf("نص عربي", "French text")))
    }

    @Test
    fun latinWordsWithLowConfidenceInAnArabicLine_areSuspicious() {
        val good = line(rtl("تم قياس", 1000f, 0f) + ltr("pH", 600f, 0f, 0.95f))
        assertFalse(ScriptDetector.suspiciousLatinInArabic(good))
        val bad = line(rtl("تم قياس", 1000f, 0f, 0.9f) + ltr("Ll9y", 600f, 0f, 0.4f))
        assertTrue(ScriptDetector.suspiciousLatinInArabic(bad))
        assertFalse(ScriptDetector.suspiciousLatinInArabic(line(ltr("Only latin here", 0f, 0f, 0.3f))))
    }

    // ------------------------------------------------------------------ tables

    @Test
    fun borderlessTable_keepsRowsColumnsAndEmptyCells() {
        val lines = listOf(
            line(ltr("Results of the analysis", 50f, 10f)),
            row(60f, 50f to "Product", 400f to "Quantity", 700f to "Price"),
            row(90f, 50f to "Product A", 400f to "10", 700f to "200"),
            row(120f, 50f to "Product B", 400f to "15", 700f to "350"),
            row(150f, 50f to "Product C", 700f to "90"), // missing quantity
            line(ltr("The prices are in euros.", 50f, 210f))
        )
        val blocks = LayoutAnalyzer.analyze(lines, 1000f, 1400f)
        val table = blocks.filterIsInstance<ExtractedBlock.Table>().single()
        assertEquals(listOf("Product", "Quantity", "Price"), table.rows[0].map { it.text })
        assertEquals(listOf("Product A", "10", "200"), table.rows[1].map { it.text })
        assertEquals(listOf("Product B", "15", "350"), table.rows[2].map { it.text })
        // Nothing invented: the missing value stays empty.
        assertEquals(listOf("Product C", "", "90"), table.rows[3].map { it.text })
        assertTrue(table.header)
        assertEquals("layout", table.detectedBy)
        // Reading order: text, table, text.
        assertTrue(blocks.first() is ExtractedBlock.Heading || blocks.first() is ExtractedBlock.Paragraph)
        assertTrue(blocks.last() is ExtractedBlock.Paragraph)
        assertEquals("Product | Quantity | Price\nProduct A | 10 | 200", ExtractionText.block(table)!!.lines().take(2).joinToString("\n"))
    }

    @Test
    fun arabicTable_columnsInRightToLeftOrder() {
        // Columns drawn right to left: البئر (right), العمق, النترات (left).
        fun arRow(y: Float, a: String, b: String, c: String) = line(rtl(a, 950f, y) + rtl(b, 600f, y) + rtl(c, 250f, y))
        val lines = listOf(arRow(60f, "البئر", "العمق", "النترات"), arRow(90f, "ب1", "120", "35"), arRow(120f, "ب2", "85", "52"))
        val table = LayoutAnalyzer.analyze(lines, 1000f, 1400f).filterIsInstance<ExtractedBlock.Table>().single()
        assertTrue(table.rtl)
        assertEquals(listOf("البئر", "العمق", "النترات"), table.rows[0].map { it.text })
        assertEquals(listOf("ب2", "85", "52"), table.rows[2].map { it.text })
    }

    @Test
    fun borderedTable_fromRules_withAMergedCell() {
        // Grid: x = 0, 300, 600, 900 ; y = 0, 40, 80, 120. The rule x=600 is missing in row 2.
        val rules = listOf(
            Ruling(true, 0f, 0f, 900f), Ruling(true, 40f, 0f, 900f), Ruling(true, 80f, 0f, 900f), Ruling(true, 120f, 0f, 900f),
            Ruling(false, 0f, 0f, 120f), Ruling(false, 300f, 0f, 120f), Ruling(false, 600f, 0f, 80f), Ruling(false, 600f, 80f, 80f),
            Ruling(false, 900f, 0f, 120f)
        )
        val lines = listOf(
            row(10f, 20f to "Ion", 320f to "Min", 620f to "Max"),
            row(50f, 20f to "Ca", 320f to "12", 620f to "40"),
            row(90f, 20f to "Mg", 320f to "Not measured")
        )
        val found = TableReconstructor.fromRulings(rules, lines).single()
        val t = found.table
        assertEquals("rules", t.detectedBy)
        assertEquals(listOf("Ion", "Min", "Max"), t.rows[0].map { it.text })
        assertEquals(listOf("Ca", "12", "40"), t.rows[1].map { it.text })
        assertEquals(listOf("Mg", "Not measured"), t.rows[2].map { it.text })
        assertEquals(2, t.rows[2][1].colSpan)
        assertEquals(3, t.columns)
        assertEquals(setOf(0, 1, 2), found.consumedLines)
    }

    @Test
    fun rulesAreFoundInAGreyImage() {
        val w = 400
        val h = 200
        val gray = IntArray(w * h) { 255 }
        fun hline(y: Int) { for (x in 20 until 380) gray[y * w + x] = 0; for (x in 20 until 380) gray[(y + 1) * w + x] = 0 }
        fun vline(x: Int) { for (y in 20 until 180) gray[y * w + x] = 0 }
        hline(20); hline(100); hline(178)
        vline(20); vline(200); vline(379)
        // A short dark mark (a letter) is not a rule.
        for (x in 50 until 60) gray[50 * w + x] = 0
        val r = TableReconstructor.detectRulings(gray, w, h)
        assertEquals(3, r.count { it.horizontal })
        assertEquals(3, r.count { !it.horizontal })
    }

    @Test
    fun twoColumnsOfRunningText_areNotATable() {
        val lines = (0 until 4).map { i ->
            line(ltr("the quick brown fox jumps over the dog", 20f, 20f + i * 30f) + ltr("une phrase assez longue pour une colonne", 560f, 20f + i * 30f))
        }
        assertTrue(LayoutAnalyzer.analyze(lines, 1000f, 1400f).none { it is ExtractedBlock.Table })
    }

    // ------------------------------------------------------------------ structure

    @Test
    fun structure_headingsListsCaptionsPageNumbersAndParagraphs() {
        val lines = listOf(
            TextLine(ltr("1. Introduction", 50f, 40f).map { it.copy(box = it.box.copy(bottom = it.box.top + 30f)) }, Box(50f, 40f, 210f, 70f)),
            line(ltr("Groundwater is an essential resource for the", 50f, 100f)),
            line(ltr("population of the region.", 50f, 122f)),
            line(ltr("1.1 Study area", 50f, 170f)),
            line(ltr("1) first well", 50f, 210f)),
            line(ltr("2) second well", 50f, 232f)),
            line(ltr("- a bullet", 80f, 254f)),
            line(ltr("Figure 3: location map", 50f, 300f)),
            line(ltr("12", 480f, 1360f))
        )
        val b = LayoutAnalyzer.analyze(lines, 1000f, 1400f)
        val h1 = b[0] as ExtractedBlock.Heading
        assertEquals("1. Introduction", h1.text) // numbering kept as written
        assertEquals(1, h1.level)
        assertEquals("Groundwater is an essential resource for the population of the region.", (b[1] as ExtractedBlock.Paragraph).text)
        val h2 = b[2] as ExtractedBlock.Heading
        assertEquals("1.1 Study area", h2.text)
        assertEquals(2, h2.level)
        val items = b.filterIsInstance<ExtractedBlock.ListItem>()
        assertEquals(listOf("1)", "2)", "-"), items.map { it.marker })
        assertTrue(items[0].ordered)
        assertFalse(items[2].ordered)
        assertTrue(items[2].level > items[0].level)
        assertEquals("Figure 3: location map", (b.single { it is ExtractedBlock.Caption } as ExtractedBlock.Caption).text)
        assertEquals("12", (b.last() as ExtractedBlock.PageNumber).text)
    }

    @Test
    fun arabicStructure_rtlParagraphsAndArabicListMarkers() {
        val lines = listOf(
            line(rtl("الفصل الأول", 950f, 40f)),
            line(rtl("تعتمد المنطقة على المياه الجوفية في الشرب", 950f, 100f)),
            line(rtl("والري منذ عقود طويلة", 950f, 122f)),
            line(rtl("أ- الآبار العميقة", 950f, 170f)),
            line(rtl("ب- الينابيع", 950f, 192f))
        )
        val b = LayoutAnalyzer.analyze(lines, 1000f, 1400f)
        assertEquals("الفصل الأول", (b[0] as ExtractedBlock.Heading).text)
        val p = b[1] as ExtractedBlock.Paragraph
        assertTrue(p.rtl)
        // Logical order kept: words in reading order, lines joined, nothing reversed.
        assertEquals("تعتمد المنطقة على المياه الجوفية في الشرب والري منذ عقود طويلة", p.text)
        val items = b.filterIsInstance<ExtractedBlock.ListItem>()
        assertEquals(listOf("أ-", "ب-"), items.map { it.marker })
        assertEquals("الآبار العميقة", items[0].text)
        assertTrue(items.all { it.rtl })
    }

    @Test
    fun lowConfidenceWords_areReportedForReview() {
        val l = line(ltr("La teneur", 50f, 100f) + ltr("en", 150f, 100f, 0.3f) + ltr("nitrates augmente.", 190f, 100f))
        val p = LayoutAnalyzer.analyze(listOf(l), 1000f, 1400f).single() as ExtractedBlock.Paragraph
        assertEquals(listOf("en"), p.uncertainWords)
    }

    // ------------------------------------------------------------------ PDF text

    @Test
    fun textPdf_isReadDirectly_withHeadingsListAndRuledTable() {
        val reader = PdfDocumentReader.open(TestPdf.corporateTemplate())
        val pages = PdfTextExtraction.readPages(reader)
        val page = pages.single().second!!
        assertEquals(PageSource.PDF_TEXT, page.source)
        assertEquals(ReconstructionLevel.FORMATTING, page.level)
        val headings = page.blocks.filterIsInstance<ExtractedBlock.Heading>().map { it.text }
        assertTrue(headings.toString(), headings.contains("Quarterly Review"))
        assertTrue(headings.toString(), headings.contains("Key Points"))
        val table = page.blocks.filterIsInstance<ExtractedBlock.Table>().single()
        assertEquals("rules", table.detectedBy)
        assertEquals(listOf("Cell 00", "Cell 01", "Cell 02"), table.rows[0].map { it.text })
        assertEquals(listOf("Cell 20", "Cell 21", "Cell 22"), table.rows[2].map { it.text })
        assertTrue(page.blocks.any { it is ExtractedBlock.PageNumber })
        assertTrue(page.scripts.contains(Script.LATIN))
    }

    @Test
    fun scannedPdfPage_isSentToOcr_andArabicTextPdfIsReadInOrder() {
        val scanned = PdfTextExtraction.readPages(PdfDocumentReader.open(TestPdf.scannedPage()))
        assertEquals(null, scanned.single().second)
        val reader = PdfDocumentReader.open(TestPdf.arabicTemplate())
        val scan = PdfContentScanner(reader).scan(reader.pages().single())
        assertEquals(PdfPageKind.TEXT, PdfTextExtraction.classify(scan))
        val page = PdfTextExtraction.page(0, scan)
        assertTrue(page.scripts.contains(Script.ARABIC))
        val text = ExtractionText.page(page)
        assertTrue(text, text.contains("التقرير الشهري"))
        assertTrue(text, text.contains("نفذ الفريق الخطة حسب المطلوب"))
    }

    @Test
    fun presentationFormsInVisualOrder_becomeLogicalArabic() {
        // "سلام" drawn with presentation forms in visual (left-to-right) order: ﻡ ﺎ ﻠ ﺳ
        assertEquals("سلام", PdfTextExtraction.logicalText("ﻡﺎﻠﺳ"))
        assertEquals("نص عادي", PdfTextExtraction.logicalText("نص عادي"))
    }

    @Test
    fun skewIsMeasuredFromTheTextRows() {
        val w = 800
        val h = 600
        fun page(deg: Double): IntArray {
            val g = IntArray(w * h) { 255 }
            val t = Math.tan(Math.toRadians(deg))
            for (row in 0 until 12) for (x in 40 until 760) {
                val y0 = 60 + row * 40
                for (dy in 0 until 8) {
                    val y = (y0 + dy + x * t).toInt()
                    if (y in 0 until h && (x / 9) % 4 != 3) g[y * w + x] = 0 // words with gaps
                }
            }
            return g
        }
        assertEquals(0f, Deskew.estimate(page(0.0), w, h), 0.01f)
        assertEquals(2f, Deskew.estimate(page(2.0), w, h), 0.3f)
        assertEquals(-3f, Deskew.estimate(page(-3.0), w, h), 0.3f)
    }

    @Test
    fun pdfDrawnOneGlyphPerRun_isReadAsWords() {
        // Android's PdfDocument (Skia) writes one positioned glyph per text run.
        val pdf = TestPdf()
        val c = StringBuilder()
        fun glyphs(text: String, x0: Double, y: Int) {
            var x = x0
            for (ch in text) {
                if (ch != ' ') c.append("BT /F3 12 Tf $x $y Td ${TestPdf.lit(ch.toString())} Tj ET\n")
                x += if (ch == ' ') 3.5 else 6.6
            }
        }
        glyphs("Monthly report", 60.0, 760)
        glyphs("The measurements of March.", 60.0, 740)
        pdf.page(c.toString())
        val reader = PdfDocumentReader.open(pdf.build())
        val scan = PdfContentScanner(reader).scan(reader.pages().single())
        val text = ExtractionText.page(PdfTextExtraction.page(0, scan))
        assertTrue(text, text.contains("Monthly report"))
        assertTrue(text, text.contains("The measurements of March."))
    }
}
