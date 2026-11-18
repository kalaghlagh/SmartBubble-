package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ocr.DocumentOcrEngine
import com.example.ocr.OcrLanguage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer

/**
 * Real OCR on the device: printed pages are drawn (Arabic, French, English, mixed, rotated, low
 * contrast, a table, two pages), saved as JPEG like the document scanner does, and read by the
 * app's OCR engine (PaddleOCR PP-OCRv5 on ONNX Runtime, bundled models). Each case reports the share of the
 * expected words found; the images and results go to the device-test evidence.
 */
@RunWith(AndroidJUnit4::class)
class OcrDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File by lazy { File(context.getExternalFilesDir(null), "ocr-device").apply { mkdirs() } }

    private val arabic = "المياه الجوفية مصدر أساسي لمياه الشرب في المناطق الجافة. تعتمد المدن الكبرى على الآبار العميقة لتلبية حاجات السكان."
    private val french = "Les eaux souterraines constituent une ressource essentielle pour la population. Cette étude analyse la qualité des puits de la région."
    private val english = "Groundwater is an essential resource for the population. This study analyses the quality of wells in the region."

    /** A page with [blocks] of text (each in its own direction), drawn like a printed document. */
    private fun page(name: String, blocks: List<String>, textColor: Int = Color.BLACK, background: Int = Color.WHITE, rotate: Float = 0f, textSize: Float = 38f): Uri {
        val w = 1240
        val h = 1754
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(background)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; this.textSize = textSize }
        var y = 120f
        for (b in blocks) {
            val layout = StaticLayout.Builder.obtain(b, 0, b.length, paint, w - 240).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.3f).build()
            canvas.save(); canvas.translate(120f, y); layout.draw(canvas); canvas.restore()
            y += layout.height + 60f
        }
        val out = if (rotate != 0f) Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(rotate) }, true) else bmp
        val file = File(dir, "$name.jpg")
        FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return Uri.fromFile(file)
    }

    private fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي')

    /** Share of the expected words (3+ letters) found in [recognized]. */
    private fun recall(expected: String, recognized: String): Double {
        val found = normalize(recognized).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
        val words = normalize(expected).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }
        return words.count { it in found }.toDouble() / words.size.coerceAtLeast(1)
    }

    private fun ocr(vararg uris: Uri, language: OcrLanguage = OcrLanguage.AUTO) =
        runBlocking { DocumentOcrEngine(context).processPages(uris.toList(), language, "en") }.also { r ->
            if (r.failedPages > 0) {
                val line = "OCR errors: ${r.errors}"
                Log.e("OcrDeviceTest", line)
                File(dir, "results.txt").appendText(line + "\n")
            }
            assertEquals("pages that failed: ${r.errors}", 0, r.failedPages)
        }

    private val results = StringBuilder()

    private fun check(case: String, expected: String, text: String, minimum: Double) {
        val r = recall(expected, text)
        val line = "OCR $case: recall=${"%.2f".format(r)} text=${text.replace('\n', ' ').take(300)}"
        Log.i("OcrDeviceTest", line)
        // Also in the instrumentation output, so the CI log carries the measured results.
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("ocr_result", line) })
        results.append(line).append('\n')
        File(dir, "results.txt").appendText(line + "\n")
        assertTrue("$case: only ${"%.0f".format(r * 100)}% of the words recognized: $text", r >= minimum)
    }

    private fun engine() = com.example.ocr.paddle.PaddleOrtOcrEngine(
        com.example.ocr.paddle.ModelInstaller(File(context.noBackupFilesDir, com.example.ocr.paddle.PaddleModels.DIR)) { context.assets.open(it) }
    )

    /** The text of the detected regions of [bmp], each read by [model], in reading order. */
    private fun readAll(e: com.example.ocr.paddle.PaddleOrtOcrEngine, bmp: Bitmap, model: com.example.extraction.OcrModel): String =
        e.detect(bmp).joinToString(" ") { box ->
            val crop = Bitmap.createBitmap(bmp, box.left.toInt(), box.top.toInt(), (box.right - box.left).toInt().coerceAtLeast(1).coerceAtMost(bmp.width - box.left.toInt()),
                (box.bottom - box.top).toInt().coerceAtLeast(1).coerceAtMost(bmp.height - box.top.toInt()))
            try { com.example.ocr.paddle.RtlText.visualToLogical(e.recognize(crop, model).text) } finally { crop.recycle() }
        }

    /**
     * The recognizer itself, without the app's pipeline: the bundled detection and Latin models
     * read a clean page. Isolates the native engine and model files from image loading and assembly.
     */
    @Test fun engineReadsAPlainBitmap() {
        val bmp = Bitmap.createBitmap(1240, 600, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
            drawText("Groundwater quality of wells", 80f, 200f, paint)
            drawText("in the region was analysed", 80f, 300f, paint)
        }
        engine().use { e ->
            val text = readAll(e, bmp, com.example.extraction.OcrModel.LATIN)
            val line = "OCR raw-engine: loads=${e.loadTimes} text=$text"
            Log.i("OcrDeviceTest", line)
            File(dir, "results.txt").appendText(line + "\n")
            check("raw-engine", "Groundwater quality of wells in the region was analysed", text, 0.8)
        }
    }

    /**
     * The Arabic model alone on an Arabic page: every text line is detected, and the second line
     * is read correctly (the line the previous engine dropped on full pages).
     */
    @Test fun engineReadsArabic() {
        val uri = page("arabic-raw", listOf(arabic))
        val bmp = android.graphics.BitmapFactory.decodeFile(uri.path)
        val gray = com.example.ocr.OcrPreprocessor.prepare(bmp)
        val bands = com.example.ocr.OcrPreprocessor.textLineBands(gray)
        engine().use { e ->
            val boxes = e.detect(gray)
            val text = readAll(e, gray, com.example.extraction.OcrModel.ARABIC)
            val line = "OCR raw-arabic ink lines=${bands.size} regions=${boxes.size} recall=${"%.2f".format(recall(arabic, text))} text=$text"
            Log.i("OcrDeviceTest", line)
            File(dir, "results.txt").appendText(line + "\n")
            assertTrue("text lines on the image: ${bands.size}", bands.size >= 2)
            val b = bands[1]
            val top = (b.first - 10).coerceAtLeast(0)
            val crop = Bitmap.createBitmap(gray, 0, top, gray.width, (b.last + 10).coerceAtMost(gray.height - 1) - top + 1)
            val ink = e.detect(crop).maxByOrNull { it.width }!!
            val lineCrop = Bitmap.createBitmap(crop, ink.left.toInt(), ink.top.toInt(), ink.width.toInt().coerceAtMost(crop.width - ink.left.toInt()), ink.height.toInt().coerceAtMost(crop.height - ink.top.toInt()))
            val secondLine = com.example.ocr.paddle.RtlText.visualToLogical(e.recognize(lineCrop, com.example.extraction.OcrModel.ARABIC).text)
            File(dir, "results.txt").appendText("second line alone=$secondLine\n")
            val expectedSecond = "الكبرى على الآبار العميقة لتلبية حاجات السكان"
            assertTrue("the Arabic model does not read the second line alone: $secondLine | page: $text", recall(expectedSecond, secondLine) >= 0.8)
        }
    }

    /** A damaged model is refused (SHA-256) and reported as unavailable OCR, without a crash. */
    @Test fun damagedModelIsRefused() {
        val dir = File(context.cacheDir, "paddle-damaged").apply { deleteRecursively(); mkdirs() }
        val installer = com.example.ocr.paddle.ModelInstaller(dir) { path ->
            val bytes = context.assets.open(path).use { it.readBytes() }
            if (path.endsWith("det.ort")) bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
            bytes.inputStream()
        }
        val e = com.example.ocr.paddle.PaddleOrtOcrEngine(installer)
        try {
            e.warmUp()
            throw AssertionError("a damaged model was loaded")
        } catch (x: com.example.ocr.paddle.ModelIntegrityException) {
            assertTrue(x.message.orEmpty(), x.message.orEmpty().contains("det.ort"))
        } finally {
            e.close()
        }
        assertTrue("no partial file left", dir.listFiles().orEmpty().none { it.name.endsWith(".part") || it.name == "det.ort" })
    }

    /** Cancelling an extraction stops it; the engine works again for the next one. */
    @Test fun cancellationStopsAndEngineRecovers() = kotlinx.coroutines.runBlocking {
        val uris = (1..3).map { page("cancel-$it", listOf(english, french)) }
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            com.example.extraction.DocumentExtractionEngine(context).extract(uris.map { com.example.extraction.ExtractionSource(it, false) },
                OcrLanguage.AUTO, "en")
        }
        kotlinx.coroutines.delay(1500)
        job.cancel()
        kotlinx.coroutines.withTimeout(20_000) { job.join() }
        assertTrue("cancelled", job.isCancelled)
        val r = ocr(page("after-cancel", listOf(english)))
        check("after-cancel", english, r.pages.single().rawText, 0.8)
    }

    @Test fun arabicPage() { val r = ocr(page("arabic", listOf(arabic))); check("arabic", arabic, r.pages.single().rawText, 0.6) }

    @Test fun frenchPage() { val r = ocr(page("french", listOf(french))); check("french", french, r.pages.single().rawText, 0.8) }

    @Test fun englishPage() { val r = ocr(page("english", listOf(english))); check("english", english, r.pages.single().rawText, 0.8) }

    @Test fun mixedArabicFrenchPage() {
        val r = ocr(page("mixed", listOf(arabic, french)))
        val text = r.pages.single().rawText
        check("mixed-ar", arabic, text, 0.5)
        check("mixed-fr", french, text, 0.7)
        // Reading order: the Arabic block (first on the page) comes before the French one.
        val n = normalize(text)
        assertTrue("reading order", n.indexOf("ressource").let { it < 0 || n.indexOf(normalize("الجوفيه").take(4)) < it })
    }

    /** Orientation decisions and the page as read, in the instrumentation output (diagnostics). */
    private fun <T> diagnosed(name: String, block: () -> T): T {
        com.example.extraction.OcrPageReader.trace = { if (it.startsWith("orientation") || it.startsWith("region") || it.startsWith("page lines")) InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply { putString("ocr_result", "TRACE $name ${it.take(800)}") }) }
        DiagnosticImages.install(name)
        try { return block() } finally { DiagnosticImages.uninstall(); com.example.extraction.OcrPageReader.trace = null }
    }

    private fun notes(r: com.example.ocr.OcrBatchResult) = r.pages.single().extraction?.notes.orEmpty()

    @Test fun rotatedPage() = diagnosed("rotated-90") {
        val r = ocr(page("rotated", listOf(english), rotate = 90f))
        check("rotated-90", english, r.pages.single().rawText, 0.7)
        assertTrue("turned upright: ${notes(r)}", notes(r).any { it.startsWith("rotated:") })
    }

    @Test fun rotatedPage270() = diagnosed("rotated-270") {
        val r = ocr(page("rotated-270", listOf(french), rotate = 270f))
        check("rotated-270", french, r.pages.single().rawText, 0.7)
    }

    @Test fun upsideDownPage() = diagnosed("rotated-180") {
        val r = ocr(page("rotated-180", listOf(english), rotate = 180f))
        check("rotated-180", english, r.pages.single().rawText, 0.7)
    }

    /** An upright page is never turned (no blind rotation). */
    @Test fun uprightPage_isNotTurned() = diagnosed("upright") {
        val r = ocr(page("upright", listOf(english, french)))
        check("upright", english, r.pages.single().rawText, 0.8)
        assertTrue("not turned: ${notes(r)}", notes(r).none { it.startsWith("rotated:") })
    }

    @Test fun lowContrastPage() {
        val r = ocr(page("low-contrast", listOf(french), textColor = Color.rgb(150, 150, 150), background = Color.rgb(205, 205, 205)))
        check("low-contrast", french, r.pages.single().rawText, 0.7)
    }

    @Test fun tableAndParagraphs() {
        val table = "Puits        Profondeur        Nitrates\nP1        120 m        35 mg/l\nP2        85 m        52 mg/l"
        val r = ocr(page("table", listOf("Résultats des analyses", table, english)))
        val text = r.pages.single().rawText
        check("table-text", "Résultats analyses Puits Profondeur Nitrates", text, 0.6)
        check("table-paragraph", english, text, 0.7)
    }

    @Test fun twoPages() {
        val r = ocr(page("multi-1", listOf(english)), page("multi-2", listOf(french)))
        assertEquals(2, r.pages.size)
        check("multi-page-1", english, r.pages[0].rawText, 0.8)
        check("multi-page-2", french, r.pages[1].rawText, 0.8)
    }
}
