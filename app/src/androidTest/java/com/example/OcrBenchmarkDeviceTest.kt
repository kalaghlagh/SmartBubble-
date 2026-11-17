package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
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
import com.example.extraction.Script
import com.example.extraction.ScriptDetector
import com.example.extraction.TextMetrics
import com.example.ocr.DocumentOcrEngine
import com.example.ocr.OcrLanguage
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * OCR benchmark on the device with a corpus of generated documents (Arabic, French, English,
 * mixed; paragraphs, numbered sections, lists, bordered/borderless/RTL/merged tables, two columns,
 * digital and mixed PDFs). For each case it measures character and word error rates, script
 * classification accuracy, table cell / numeric cell / shape accuracy, list-item recall, reading
 * order, time per page and memory, and reports them in the instrumentation output ("bench").
 * ML Kit's Latin recognizer is run on the Latin image cases for comparison (not used by the app).
 *
 * The documents are drawn with the system fonts, not photographed: these numbers measure the
 * pipeline on clean prints, not real-camera accuracy.
 */
@RunWith(AndroidJUnit4::class)
class OcrBenchmarkDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File by lazy { File(context.getExternalFilesDir(null), "ocr-bench").apply { mkdirs() } }
    private val ocr by lazy { DocumentOcrEngine(context) }
    private val engine by lazy { DocumentExtractionEngine(context, ocr) }

    private fun emit(line: String) {
        Log.i("OcrBenchmark", line)
        File(dir, "bench.txt").appendText(line + "\n")
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("bench", line.take(3800)) })
    }

    // ------------------------------------------------------------------ fixtures

    private class Fixture(val w: Int = 1240, val h: Int = 1754) {
        val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = Canvas(bmp)
        var y = 110f
        fun text(t: String, size: Float = 36f, bold: Boolean = false, left: Float = 120f, width: Int = w - 240, gapAfter: Float = 28f, advance: Boolean = true): Float {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; isFakeBoldText = bold }
            val l = StaticLayout.Builder.obtain(t, 0, t.length, p, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.3f).build()
            canvas.save(); canvas.translate(left, y); l.draw(canvas); canvas.restore()
            val hgt = l.height + gapAfter
            if (advance) y += hgt
            return hgt
        }
        /** [spans]: per row, the number of columns each cell covers (null = all 1). */
        fun table(cells: List<List<String>>, rtl: Boolean, borders: Boolean, spans: List<List<Int>>? = null, size: Float = 34f) {
            val cols = cells.first().size
            val left = 120f
            val width = (w - 240).toFloat()
            val cw = width / cols
            val rh = size * 2f
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size }
            val line = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
            val top = y
            cells.forEachIndexed { r, row ->
                val sp = spans?.getOrNull(r) ?: List(row.size) { 1 }
                var col = 0
                row.forEachIndexed { c, t ->
                    val n = sp[c]
                    val vis = if (rtl) cols - col - n else col
                    val x0 = left + vis * cw
                    val x = if (rtl) x0 + n * cw - 16f - p.measureText(t) else x0 + 16f
                    canvas.drawText(t, x, top + r * rh + rh * 0.68f, p)
                    // Inner vertical borders of this row, except inside a merged cell.
                    if (borders && c > 0) {
                        val bx = if (rtl) left + (cols - col) * cw else left + col * cw
                        canvas.drawLine(bx, top + r * rh, bx, top + (r + 1) * rh, line)
                    }
                    col += n
                }
            }
            if (borders) {
                for (r in 0..cells.size) canvas.drawLine(left, top + r * rh, left + width, top + r * rh, line)
                canvas.drawLine(left, top, left, top + cells.size * rh, line)
                canvas.drawLine(left + width, top, left + width, top + cells.size * rh, line)
            }
            y = top + cells.size * rh + 40f
        }
        fun save(dir: File, name: String): Uri {
            val f = File(dir, "$name.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            return Uri.fromFile(f)
        }
    }

    /** What a document should give back. */
    private class Expect(
        val lines: List<Pair<String, Script>>,
        val tables: List<List<List<String>>> = emptyList(),
        val items: List<Pair<String, String>> = emptyList(),
        val ordered: Boolean = true
    ) {
        val text: String get() = lines.joinToString(" ") { it.first }
    }

    private class Result(val name: String, val group: String, val cer: Double, val wer: Double, val scriptAcc: Double, val cells: Pair<Int, Int>,
                         val numeric: Pair<Int, Int>, val shapeOk: Boolean, val items: Pair<Int, Int>, val order: Double, val ms: Long, val memMb: Double)

    private val results = ArrayList<Result>()

    // ------------------------------------------------------------------ metrics

    private fun norm(s: String) = TextMetrics.normalizeSpaces(s)

    /** Text of the document without markup: blocks in order, list markers kept, table cells separated by spaces. */
    private fun plain(d: ExtractedDocument): String = d.pages.flatMap { it.blocks }.mapNotNull { b ->
        when (b) {
            is ExtractedBlock.Heading -> b.text
            is ExtractedBlock.Paragraph -> b.text
            is ExtractedBlock.ListItem -> b.marker + " " + b.text
            is ExtractedBlock.Caption -> b.text
            is ExtractedBlock.Table -> b.rows.joinToString(" ") { r -> r.joinToString(" ") { it.text } }
            is ExtractedBlock.PageNumber -> null
        }
    }.joinToString(" ")

    private fun sim(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        return 1.0 - TextMetrics.levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
    }

    /** Best-matching window of [text] for [needle] (words), with its start position. */
    private fun locate(needle: String, text: String): Pair<Int, Double> {
        val words = norm(text).split(' ')
        val n = norm(needle).split(' ').size
        var best = -1 to 0.0
        for (i in 0..(words.size - n).coerceAtLeast(0)) {
            val window = words.subList(i, (i + n).coerceAtMost(words.size)).joinToString(" ")
            val s = sim(needle, window)
            if (s > best.second) best = i to s
        }
        return best
    }

    private fun measure(name: String, group: String, expect: Expect, vararg sources: ExtractionSource): ExtractedDocument {
        val rt = Runtime.getRuntime()
        System.gc()
        val before = Debug.getNativeHeapAllocatedSize() + (rt.totalMemory() - rt.freeMemory())
        var peak = before
        val sampler = Thread {
            while (!Thread.currentThread().isInterrupted) {
                peak = maxOf(peak, Debug.getNativeHeapAllocatedSize() + (rt.totalMemory() - rt.freeMemory()))
                try { Thread.sleep(40) } catch (e: InterruptedException) { break }
            }
        }.apply { start() }
        val t = SystemClock.elapsedRealtime()
        val doc = runBlocking { engine.extract(sources.toList(), OcrLanguage.AUTO, "en") }
        val ms = (SystemClock.elapsedRealtime() - t) / doc.pages.size.coerceAtLeast(1)
        sampler.interrupt(); sampler.join()
        val text = plain(doc)
        val cer = TextMetrics.cer(expect.text, text)
        val wer = TextMetrics.wer(expect.text, text)
        // Script of each expected line, from the best-matching block.
        val blocks = doc.pages.flatMap { it.blocks }.map { b ->
            when (b) {
                is ExtractedBlock.Heading -> b.text; is ExtractedBlock.Paragraph -> b.text; is ExtractedBlock.ListItem -> b.text
                is ExtractedBlock.Caption -> b.text; else -> ""
            }
        }.filter { it.isNotBlank() }
        val scriptOk = expect.lines.count { (line, script) ->
            val found = blocks.maxByOrNull { b -> maxOf(sim(line, b), locate(line, b).second) } ?: ""
            val got = ScriptDetector.classify(locateText(line, found))
            got == script || (script == Script.MIXED && got != Script.UNKNOWN)
        }
        val scriptAcc = if (expect.lines.isEmpty()) 1.0 else scriptOk.toDouble() / expect.lines.size
        // Tables.
        val got = doc.pages.flatMap { it.blocks }.filterIsInstance<ExtractedBlock.Table>()
        var cellOk = 0; var cellAll = 0; var numOk = 0; var numAll = 0; var shape = true
        for ((k, exp) in expect.tables.withIndex()) {
            val t2 = got.getOrNull(k)
            val grid = t2?.rows?.map { r -> r.flatMap { c -> listOf(c.text) + List(c.colSpan - 1) { "" } } }
            if (grid == null || grid.size != exp.size || grid.zip(exp).any { (a, b) -> a.size != b.size }) shape = false
            for ((r, row) in exp.withIndex()) for ((c, e) in row.withIndex()) {
                if (e.isEmpty()) continue
                val a = grid?.getOrNull(r)?.getOrNull(c).orEmpty()
                val okCell = a.filter { !it.isWhitespace() } == e.filter { !it.isWhitespace() }
                cellAll++; if (okCell) cellOk++
                if (e.any { it.isDigit() }) { numAll++; if (okCell) numOk++ }
            }
        }
        // Lists.
        val items = doc.pages.flatMap { it.blocks }.filterIsInstance<ExtractedBlock.ListItem>()
        val itemOk = expect.items.count { (marker, text) -> items.any { it.marker.replace(" ", "") == marker && sim(it.text, text) >= 0.8 } }
        // Reading order: expected lines appear in the output in the same order.
        val positions = expect.lines.map { locate(it.first, text).first }
        val order = if (positions.size < 2 || !expect.ordered) 1.0 else positions.zipWithNext().count { (a, b) -> b >= a }.toDouble() / (positions.size - 1)
        val mem = (peak - before) / 1048576.0
        val r = Result(name, group, cer, wer, scriptAcc, cellOk to cellAll, numOk to numAll, shape, itemOk to expect.items.size, order, ms, mem)
        results += r
        emit("BENCH $name [$group]: cer=${"%.3f".format(cer)} wer=${"%.3f".format(wer)} script=${"%.2f".format(scriptAcc)} cells=$cellOk/$cellAll " +
            "numeric=$numOk/$numAll shape=$shape lists=$itemOk/${expect.items.size} order=${"%.2f".format(order)} ms/page=$ms peakMB=${"%.1f".format(mem)} " +
            "loads=${ocr.lastLoadTimes} stages=${doc.pages.map { it.timingsMs }} notes=${doc.pages.map { it.notes }}")
        emit("BENCH $name expected: ${expect.text.take(600)}")
        emit("BENCH $name actual:   ${text.take(600)}")
        return doc
    }

    private fun locateText(line: String, block: String): String {
        val words = norm(block).split(' ')
        val (i, _) = locate(line, block)
        val n = norm(line).split(' ').size
        return words.subList(i.coerceAtLeast(0), (i.coerceAtLeast(0) + n).coerceAtMost(words.size)).joinToString(" ")
    }

    /** Strict checks: structure and numbers exact, every list item found, error rates under the limit. */
    private fun check(r: Result, maxCer: Double) {
        assertTrue("${r.name}: table shape wrong", r.shapeOk)
        assertEquals("${r.name}: numeric cells", r.numeric.second, r.numeric.first)
        assertEquals("${r.name}: list items", r.items.second, r.items.first)
        assertTrue("${r.name}: reading order ${r.order}", r.order >= 0.99)
        assertTrue("${r.name}: script accuracy ${r.scriptAcc}", r.scriptAcc >= 0.9)
        assertTrue("${r.name}: CER ${r.cer} > $maxCer", r.cer <= maxCer)
    }

    private fun image(uri: Uri) = ExtractionSource(uri, isPdf = false)

    // ------------------------------------------------------------------ ML Kit comparison

    private fun mlkit(name: String, uri: Uri, expected: String) {
        try {
            val bmp = BitmapFactory.decodeFile(uri.path)
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val t = SystemClock.elapsedRealtime()
            val res = Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)))
            val ms = SystemClock.elapsedRealtime() - t
            client.close()
            emit("MLKIT $name: cer=${"%.3f".format(TextMetrics.cer(expected, res.text.replace('\n', ' ')))} wer=${"%.3f".format(TextMetrics.wer(expected, res.text.replace('\n', ' ')))} ms=$ms text=${res.text.replace('\n', ' ').take(300)}")
        } catch (e: Throwable) {
            emit("MLKIT $name: not available (${e.javaClass.simpleName})")
        }
    }

    // ------------------------------------------------------------------ corpus

    @Test
    fun arabic() {
        val p1 = "تعد المياه الجوفية مصدرا أساسيا لمياه الشرب في المناطق الجافة."
        val p2 = "أجريت الدراسة على عشرين بئرا خلال سنة 2024 وتم قياس درجة الحموضة pH والناقلية."
        val f = Fixture()
        f.text("1. منطقة الدراسة", size = 44f, bold = true)
        f.text(p1); f.text(p2)
        f.text("أ- الآبار العميقة", gapAfter = 8f); f.text("ب- الينابيع الطبيعية", gapAfter = 8f); f.text("ج- السدود الصغيرة", gapAfter = 30f)
        f.text("2. النتائج", size = 44f, bold = true)
        f.table(listOf(listOf("البئر", "العمق", "النترات"), listOf("ب1", "120", "35"), listOf("ب2", "85", "52")), rtl = true, borders = true)
        val e = Expect(
            lines = listOf("1. منطقة الدراسة" to Script.ARABIC, p1 to Script.ARABIC, p2 to Script.ARABIC, "أ- الآبار العميقة" to Script.ARABIC,
                "ب- الينابيع الطبيعية" to Script.ARABIC, "ج- السدود الصغيرة" to Script.ARABIC, "2. النتائج" to Script.ARABIC,
                "البئر العمق النترات ب1 120 35 ب2 85 52" to Script.ARABIC),
            tables = listOf(listOf(listOf("البئر", "العمق", "النترات"), listOf("ب1", "120", "35"), listOf("ب2", "85", "52"))),
            items = listOf("أ-" to "الآبار العميقة", "ب-" to "الينابيع الطبيعية", "ج-" to "السدود الصغيرة")
        )
        measure("arabic-doc", "arabic", e, image(f.save(dir, "bench-arabic")))
        check(results.last(), 0.10)
    }

    @Test
    fun french() {
        val p = "Les eaux souterraines constituent une ressource essentielle. Les teneurs mesurées dépassent la norme."
        val f = Fixture()
        f.text("Résultats des analyses", size = 44f, bold = true)
        f.text(p)
        val cells = listOf(listOf("Puits", "Profondeur", "Nitrates"), listOf("P1", "120 m", "35 mg/l"), listOf("P2", "85 m", "52 mg/l"))
        f.table(cells, rtl = false, borders = true)
        val e = Expect(listOf("Résultats des analyses" to Script.LATIN, p to Script.LATIN, cells.flatten().joinToString(" ") to Script.LATIN), tables = listOf(cells))
        val uri = f.save(dir, "bench-french")
        measure("french-doc", "latin", e, image(uri))
        mlkit("french-doc", uri, e.text)
        check(results.last(), 0.05)
    }

    @Test
    fun english() {
        val p = "Groundwater is an essential resource. The wells were sampled every month."
        val f = Fixture()
        f.text("Sampling results", size = 44f, bold = true)
        f.text(p)
        f.text("1. Wells were sampled in March.", gapAfter = 8f)
        f.text("2. Samples were kept at 4 degrees.", gapAfter = 30f)
        val cells = listOf(listOf("Product", "Quantity", "Price"), listOf("Product A", "10", "200"), listOf("Product B", "15", "350"))
        f.table(cells, rtl = false, borders = false)
        val e = Expect(listOf("Sampling results" to Script.LATIN, p to Script.LATIN, "1. Wells were sampled in March." to Script.LATIN,
            "2. Samples were kept at 4 degrees." to Script.LATIN, cells.flatten().joinToString(" ") to Script.LATIN),
            tables = listOf(cells), items = listOf("1." to "Wells were sampled in March.", "2." to "Samples were kept at 4 degrees."))
        val uri = f.save(dir, "bench-english")
        measure("english-doc", "latin", e, image(uri))
        mlkit("english-doc", uri, e.text)
        check(results.last(), 0.05)
    }

    @Test
    fun mixed() {
        val a = "المياه الجوفية في منطقة سايس"
        val b = "Les eaux souterraines de la région du Saïss."
        val c = "تم استعمال جهاز GPS لتحديد موقع كل بئر."
        val d = "The pH was measured with a WTW meter."
        val f = Fixture()
        f.text(a); f.text(b); f.text(c); f.text(d)
        val e = Expect(listOf(a to Script.ARABIC, b to Script.LATIN, c to Script.ARABIC, d to Script.LATIN))
        measure("mixed-doc", "mixed", e, image(f.save(dir, "bench-mixed")))
        check(results.last(), 0.10)
    }

    @Test
    fun twoColumnsAndMergedCells() {
        val left = "The first column describes the study area and its climate."
        val right = "The second column presents the sampling of the wells."
        val f = Fixture()
        f.text("Two columns", size = 44f, bold = true)
        val h = f.text(left, left = 120f, width = 480, advance = false)
        f.text(right, left = 640f, width = 480, advance = false)
        f.y += h + 20f
        val cells = listOf(listOf("Ion", "Min", "Max"), listOf("Ca", "12", "40"), listOf("Mg", "Not measured"))
        f.table(cells, rtl = false, borders = true, spans = listOf(listOf(1, 1, 1), listOf(1, 1, 1), listOf(1, 2)))
        val e = Expect(listOf("Two columns" to Script.LATIN, left to Script.LATIN, right to Script.LATIN),
            tables = listOf(listOf(listOf("Ion", "Min", "Max"), listOf("Ca", "12", "40"), listOf("Mg", "Not measured", ""))))
        val doc = measure("columns-merged", "latin", e, image(f.save(dir, "bench-columns")))
        val t = doc.pages.single().blocks.filterIsInstance<ExtractedBlock.Table>().first()
        assertEquals("merged cell", 2, t.rows[2].last().colSpan)
        check(results.last(), 0.05)
    }

    @Test
    fun pdfDigitalAndMixed() {
        val d = PdfDocument()
        val page = d.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = Color.BLACK }
        page.canvas.drawText("Monthly report", 60f, 80f, paint)
        page.canvas.drawText("The measurements of March are listed below.", 60f, 100f, paint)
        d.finishPage(page)
        val scan = Fixture().apply { text("Scanned page of the appendix", size = 48f, bold = true) }.save(dir, "bench-scan")
        val p2 = d.startPage(PdfDocument.PageInfo.Builder(595, 842, 2).create())
        p2.canvas.drawBitmap(BitmapFactory.decodeFile(scan.path), null, android.graphics.RectF(0f, 0f, 595f, 842f), Paint(Paint.FILTER_BITMAP_FLAG))
        d.finishPage(p2)
        val file = File(dir, "bench.pdf")
        FileOutputStream(file).use { d.writeTo(it) }
        d.close()
        val e = Expect(listOf("Monthly report" to Script.LATIN, "The measurements of March are listed below." to Script.LATIN, "Scanned page of the appendix" to Script.LATIN))
        measure("pdf-mixed", "pdf", e, ExtractionSource(Uri.fromFile(file), isPdf = true))
        check(results.last(), 0.03)
    }
}
