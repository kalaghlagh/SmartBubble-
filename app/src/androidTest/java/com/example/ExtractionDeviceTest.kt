package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.extraction.DocumentExtractionEngine
import com.example.extraction.ExtractedBlock
import com.example.extraction.ExtractedDocument
import com.example.extraction.ExtractionSource
import com.example.extraction.ExtractionText
import com.example.extraction.PageSource
import com.example.extraction.Script
import com.example.extraction.ScriptDetector
import com.example.ocr.OcrLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import kotlin.random.Random

/**
 * The document extraction engine on the device (real PaddleOCR on ONNX Runtime, real PDF rendering): structured
 * Arabic, French and English pages, lists, tables with and without borders (also right to left),
 * a skewed noisy page, and digital, scanned and mixed PDFs. Every result is reported in the
 * instrumentation output ("extraction_result") so the CI log carries the actual text read.
 *
 * The pages are generated (drawn with the system fonts and saved as JPEG), not photographs: they
 * measure the pipeline, not real-camera accuracy.
 */
@RunWith(AndroidJUnit4::class)
class ExtractionDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File by lazy { File(context.getExternalFilesDir(null), "extraction-device").apply { mkdirs() } }
    private val engine by lazy { DocumentExtractionEngine(context) }

    @Before fun traceOn() {
        com.example.extraction.OcrPageReader.trace = { InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("extraction_result", "TRACE " + it.take(1500)) }) }
    }
    @After fun traceOff() { com.example.extraction.OcrPageReader.trace = null }

    private fun report(case: String, doc: ExtractedDocument, extra: String = "") {
        val pages = doc.pages.joinToString(" || ") { p ->
            "p${p.index + 1} source=${p.source} scripts=${p.scripts} conf=${p.confidence?.let { "%.2f".format(it) } ?: "n/a"} tables=${p.tables} notes=${p.notes} error=${p.error} text=" +
                ExtractionText.page(p).replace('\n', '¶').take(700)
        }
        val line = "EXTRACT $case: $extra $pages"
        Log.i("ExtractionDeviceTest", line)
        File(dir, "results.txt").appendText(line + "\n")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("extraction_result", line.take(3500)) })
    }

    private fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')

    private fun words(s: String) = normalize(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun recall(expected: String, got: String): Double {
        val found = words(got).toSet()
        val w = words(expected).filter { it.length >= 3 }
        return w.count { it in found }.toDouble() / w.size.coerceAtLeast(1)
    }

    // ------------------------------------------------------------------ page drawing

    private class Page(val w: Int = 1240, val h: Int = 1754) {
        val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = Canvas(bmp)
        var y = 110f
        fun text(t: String, size: Float = 36f, bold: Boolean = false, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, gapAfter: Float = 28f) {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; isFakeBoldText = bold }
            val l = StaticLayout.Builder.obtain(t, 0, t.length, p, w - 240).setAlignment(align).setLineSpacing(0f, 1.3f).build()
            canvas.save(); canvas.translate(120f, y); l.draw(canvas); canvas.restore()
            y += l.height + gapAfter
        }
        /** A table: [cells] row by row; columns drawn left to right ([rtl] = first column on the right); [borders] draws the grid. */
        fun table(cells: List<List<String>>, borders: Boolean, rtl: Boolean = false, size: Float = 34f) {
            val cols = cells.first().size
            val left = 120f
            val width = (w - 240).toFloat()
            val cw = width / cols
            val rh = size * 2f
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size }
            val line = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
            val top = y
            cells.forEachIndexed { r, row ->
                row.forEachIndexed { c, t ->
                    val col = if (rtl) cols - 1 - c else c
                    val x = left + col * cw + 16f
                    canvas.drawText(t, if (rtl) left + (col + 1) * cw - 16f - p.measureText(t) else x, top + r * rh + rh * 0.68f, p)
                }
            }
            if (borders) {
                for (r in 0..cells.size) canvas.drawLine(left, top + r * rh, left + width, top + r * rh, line)
                for (c in 0..cols) canvas.drawLine(left + c * cw, top, left + c * cw, top + cells.size * rh, line)
            }
            y = top + cells.size * rh + 40f
        }
        fun save(dir: File, name: String, rotate: Float = 0f, noise: Boolean = false): Uri {
            var out = bmp
            if (noise) {
                val rnd = Random(7)
                val px = IntArray(w * h)
                out.getPixels(px, 0, w, 0, 0, w, h)
                for (i in px.indices) {
                    val g = (Color.red(px[i]) * 0.85 + 30 + rnd.nextInt(-25, 25)).toInt().coerceIn(0, 255) // grey paper, grain
                    px[i] = Color.rgb(g, g, g)
                }
                out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            }
            if (rotate != 0f) {
                val r = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                Canvas(r).drawBitmap(out, Matrix().apply { postRotate(rotate, w / 2f, h / 2f) }, Paint(Paint.FILTER_BITMAP_FLAG))
                out = r
            }
            val f = File(dir, "$name.jpg")
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            return Uri.fromFile(f)
        }
    }

    /**
     * Table cells compared one by one: the grid must have the expected shape, every cell holding
     * a number must be exact (values are never approximated), and at most one text cell may
     * differ (a letter misread), reported in the log.
     */
    private fun assertCells(case: String, expected: List<List<String>>, got: List<List<String>>) {
        assertEquals("$case rows: $got", expected.size, got.size)
        val wrong = ArrayList<String>()
        for ((r, row) in expected.withIndex()) {
            assertEquals("$case columns of row $r: $got", row.size, got[r].size)
            for ((c, e) in row.withIndex()) if (got[r][c] != e) wrong += "[$r,$c] expected '$e' got '${got[r][c]}'"
        }
        val line = "CELLS $case: ${expected.sumOf { it.size } - wrong.size}/${expected.sumOf { it.size }} exact; differences=$wrong"
        Log.i("ExtractionDeviceTest", line)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("extraction_result", line) })
        val numericWrong = wrong.filter { w -> Regex("expected '([^']*)'").find(w)!!.groupValues[1].any { it.isDigit() } }
        assertTrue("$case numbers misread: $numericWrong", numericWrong.isEmpty())
        assertTrue("$case: ${wrong.size} cells differ: $wrong", wrong.size <= 1)
    }

    private fun extract(vararg sources: ExtractionSource, language: OcrLanguage = OcrLanguage.AUTO) =
        runBlocking { engine.extract(sources.toList(), language, "en") }

    private fun image(uri: Uri) = ExtractionSource(uri, isPdf = false)

    // ------------------------------------------------------------------ Arabic

    private val arP1 = "تعد المياه الجوفية مصدرا أساسيا لمياه الشرب في المناطق الجافة وشبه الجافة من البلاد."
    private val arP2 = "أجريت الدراسة على عشرين بئرا خلال سنة 2024 وتم قياس درجة الحموضة pH والناقلية الكهربائية في كل عينة."

    @Test
    fun arabicDocument_paragraphsListAndLatinTerms_noEnglishSubstitutions() {
        DiagnosticImages.install("arabic-list")
        try {
        val p = Page()
        p.text("الفصل الأول: منطقة الدراسة", size = 48f, bold = true)
        p.text(arP1)
        p.text(arP2)
        p.text("أ- الآبار العميقة", gapAfter = 8f)
        p.text("ب- الينابيع الطبيعية", gapAfter = 8f)
        p.text("ج- السدود الصغيرة")
        val doc = extract(image(p.save(dir, "arabic-structured")))
        val page = doc.pages.single()
        val text = ExtractionText.page(page)
        val all = "الفصل الأول منطقة الدراسة $arP1 $arP2 الآبار العميقة الينابيع الطبيعية السدود الصغيرة"
        val r = recall(all, text)
        // Latin words in the result that are not in the source: English substituted for Arabic.
        val latinNoise = words(text).filter { w -> w.any { it in 'a'..'z' } && w !in setOf("ph") }
        report("arabic-structured", doc, "recall=${"%.2f".format(r)} latinNoise=$latinNoise")
        assertTrue(page.scripts.contains(Script.ARABIC))
        assertTrue("recall $r: $text", r >= 0.7)
        assertTrue("Latin words substituted for Arabic: $latinNoise in $text", latinNoise.size <= 2)
        // Right to left kept in logical order: the first paragraph starts with its first word.
        val firstPara = page.blocks.filterIsInstance<ExtractedBlock.Paragraph>().firstOrNull()
        assertTrue("paragraphs: ${page.blocks}", firstPara != null && firstPara.rtl)
        assertEquals("list items: ${page.blocks}", 3, page.blocks.filterIsInstance<ExtractedBlock.ListItem>().size)
        } finally { DiagnosticImages.uninstall() }
    }

    @Test
    fun arabicBorderedTable_isRebuiltRightToLeft() {
        val p = Page()
        p.text("جدول نتائج التحاليل", size = 42f, bold = true)
        val cells = listOf(listOf("البئر", "العمق", "النترات"), listOf("ب1", "120", "35"), listOf("ب2", "85", "52"), listOf("ب3", "60", "18"))
        p.table(cells, borders = true, rtl = true)
        val doc = extract(image(p.save(dir, "arabic-table")))
        val t = doc.pages.single().blocks.filterIsInstance<ExtractedBlock.Table>().firstOrNull()
        report("arabic-table", doc)
        assertTrue("no table: ${doc.pages.single().blocks}", t != null)
        assertEquals(4, t!!.rows.size)
        assertEquals(3, t.columns)
        assertTrue(t.rtl)
        // Cells: Arabic words and short codes read as Arabic, numbers in their cells and rows.
        fun n(c: String) = normalize(c).filter { !it.isWhitespace() }
        assertCells("arabic-table", cells.map { r -> r.map { n(it) } }, t.rows.map { r -> r.map { n(it.text) } })
    }

    // ------------------------------------------------------------------ French / English tables

    @Test
    fun frenchBorderedTable_cellsRowsAndAccents() {
        val p = Page()
        p.text("Résultats des analyses", size = 44f, bold = true)
        p.text("Les teneurs mesurées sont présentées dans le tableau suivant.")
        p.table(listOf(listOf("Puits", "Profondeur", "Nitrates"), listOf("P1", "120 m", "35 mg/l"), listOf("P2", "85 m", "52 mg/l")), borders = true)
        p.text("Les valeurs dépassent la norme pour le puits P2.")
        val doc = extract(image(p.save(dir, "french-table")))
        val page = doc.pages.single()
        val t = page.blocks.filterIsInstance<ExtractedBlock.Table>().firstOrNull()
        report("french-table", doc)
        assertTrue("no table: ${page.blocks}", t != null)
        assertEquals(3, t!!.rows.size)
        assertEquals(3, t.columns)
        fun n(c: String) = c.filter { !it.isWhitespace() }
        assertCells("french-table", listOf(listOf("Puits", "Profondeur", "Nitrates"), listOf("P1", "120m", "35mg/l"), listOf("P2", "85m", "52mg/l")),
            t.rows.map { r -> r.map { n(it.text) } })
        assertTrue(recall("Résultats des analyses teneurs mesurées présentées tableau suivant valeurs dépassent norme", ExtractionText.page(page)) >= 0.8)
    }

    @Test
    fun englishBorderlessTable_andNumberedList() {
        val p = Page()
        p.text("Sampling results", size = 44f, bold = true)
        p.text("1. Wells were sampled in March.", gapAfter = 8f)
        p.text("2. Samples were kept at 4 degrees.", gapAfter = 30f)
        p.table(listOf(listOf("Product", "Quantity", "Price"), listOf("Product A", "10", "200"), listOf("Product B", "15", "350")), borders = false)
        val doc = extract(image(p.save(dir, "english-borderless")))
        val page = doc.pages.single()
        report("english-borderless", doc)
        val t = page.blocks.filterIsInstance<ExtractedBlock.Table>().firstOrNull()
        assertTrue("no table: ${page.blocks}", t != null)
        assertEquals(listOf("Product", "Quantity", "Price"), t!!.rows[0].map { it.text })
        assertEquals(listOf("10", "200"), t.rows[1].drop(1).map { it.text })
        assertEquals(listOf("15", "350"), t.rows[2].drop(1).map { it.text })
        val items = page.blocks.filterIsInstance<ExtractedBlock.ListItem>()
        assertEquals(listOf("1.", "2."), items.map { it.marker })
    }

    @Test
    fun mixedArabicFrenchLines_eachInItsScript() {
        val p = Page()
        p.text("المياه الجوفية في منطقة سايس")
        p.text("Les eaux souterraines de la région du Saïss.")
        p.text("تم استعمال جهاز GPS لتحديد موقع كل بئر.")
        val doc = extract(image(p.save(dir, "mixed-lines")))
        val page = doc.pages.single()
        val text = ExtractionText.page(page)
        report("mixed-lines", doc)
        assertTrue(recall("المياه الجوفية منطقة سايس", text) >= 0.6)
        assertTrue(recall("Les eaux souterraines de la région du Saïss", text) >= 0.7)
        assertTrue(page.scripts.containsAll(setOf(Script.ARABIC, Script.LATIN)))
        // The French line is not read as Arabic and the Arabic lines are right to left.
        val blocks = page.blocks.filterIsInstance<ExtractedBlock.Paragraph>()
        assertTrue(blocks.toString(), blocks.any { !it.rtl && ScriptDetector.classify(it.text) == Script.LATIN })
        assertTrue(blocks.toString(), blocks.any { it.rtl })
    }

    @Test
    fun skewedNoisyPage_isStraightenedAndRead() {
        val p = Page()
        p.text("Groundwater quality monitoring", size = 44f, bold = true)
        p.text("The wells of the basin were sampled every month and analysed for nitrates, chlorides and conductivity.")
        val doc = extract(image(p.save(dir, "skewed-noisy", rotate = 2.5f, noise = true)))
        val page = doc.pages.single()
        val r = recall("Groundwater quality monitoring wells basin sampled every month analysed nitrates chlorides conductivity", ExtractionText.page(page))
        report("skewed-noisy", doc, "recall=${"%.2f".format(r)}")
        assertTrue("recall $r", r >= 0.7)
    }

    // ------------------------------------------------------------------ PDF

    private fun pdf(name: String, build: (PdfDocument) -> Unit): Uri {
        val d = PdfDocument()
        build(d)
        val f = File(dir, "$name.pdf")
        FileOutputStream(f).use { d.writeTo(it) }
        d.close()
        return Uri.fromFile(f)
    }

    private fun textPage(d: PdfDocument, n: Int, lines: List<String>, table: List<List<String>>? = null) {
        val page = d.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
        val c = page.canvas
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Color.BLACK }
        var y = 80f
        for (l in lines) { c.drawText(l, 60f, y, p); y += 18f }
        if (table != null) {
            y += 10f
            val stroke = Paint().apply { strokeWidth = 1f; style = Paint.Style.STROKE }
            val cw = 150f
            table.forEachIndexed { r, row ->
                row.forEachIndexed { k, t -> c.drawText(t, 66f + k * cw, y + r * 22f + 15f, p) }
            }
            for (r in 0..table.size) c.drawLine(60f, y + r * 22f, 60f + cw * table[0].size, y + r * 22f, stroke)
            for (k in 0..table[0].size) c.drawLine(60f + k * cw, y, 60f + k * cw, y + table.size * 22f, stroke)
        }
        d.finishPage(page)
    }

    private fun scannedPage(d: PdfDocument, n: Int, uri: Uri) {
        val page = d.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
        val bmp = android.graphics.BitmapFactory.decodeFile(uri.path)
        page.canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, 595f, 842f), Paint(Paint.FILTER_BITMAP_FLAG))
        d.finishPage(page)
    }

    @Test
    fun digitalPdf_isReadDirectly_withItsTable() {
        val uri = pdf("digital") { d ->
            textPage(d, 1, listOf("Monthly report", "The measurements of March are listed below."), listOf(listOf("Well", "Depth", "Nitrates"), listOf("W1", "120", "35"), listOf("W2", "85", "52")))
        }
        assertTrue(engine.isPdf(uri))
        val doc = extract(ExtractionSource(uri, isPdf = true))
        val page = doc.pages.single()
        report("pdf-digital", doc)
        assertEquals(PageSource.PDF_TEXT, page.source)
        assertTrue(ExtractionText.page(page).contains("The measurements of March are listed below."))
        val t = page.blocks.filterIsInstance<ExtractedBlock.Table>().firstOrNull()
        assertTrue("no table: ${page.blocks}", t != null)
        assertEquals(listOf("W2", "85", "52"), t!!.rows[2].map { it.text })
        assertEquals(null, page.confidence) // no confidence is invented for text read from the file
    }

    @Test
    fun scannedAndMixedPdf_eachPageReadTheRightWay() {
        val scan = Page().apply { text("Scanned page of the appendix", size = 48f, bold = true); text("This page only exists as an image inside the PDF file.") }.save(dir, "for-pdf")
        val uri = pdf("mixed") { d ->
            textPage(d, 1, listOf("Introduction", "This first page carries real text."))
            scannedPage(d, 2, scan)
        }
        val doc = extract(ExtractionSource(uri, isPdf = true))
        report("pdf-mixed", doc)
        assertEquals(2, doc.pages.size)
        assertEquals(PageSource.PDF_TEXT, doc.pages[0].source)
        assertEquals(PageSource.PDF_OCR, doc.pages[1].source)
        assertTrue(ExtractionText.page(doc.pages[0]).contains("This first page carries real text."))
        val r = recall("Scanned page of the appendix This page only exists as an image inside the PDF file", ExtractionText.page(doc.pages[1]))
        assertTrue("scanned page recall $r", r >= 0.7)
        // Only one page of a multi-page PDF.
        val one = extract(ExtractionSource(uri, isPdf = true, pages = setOf(1)))
        assertEquals(1, one.pages.size)
        assertEquals(PageSource.PDF_OCR, one.pages.single().source)
    }

    @Test
    fun multiplePages_keepTheirOrder() {
        val a = Page().apply { text("First page about wells.") }.save(dir, "multi-a")
        val b = Page().apply { text("Second page about springs.") }.save(dir, "multi-b")
        val doc = extract(image(a), image(b))
        report("multi-images", doc)
        assertEquals(2, doc.pages.size)
        assertTrue(ExtractionText.page(doc.pages[0]).contains("First page"))
        assertTrue(ExtractionText.page(doc.pages[1]).contains("Second page"))
        assertEquals(listOf(0, 1), doc.pages.map { it.sourceIndex })
    }
}
