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
 * app's OCR engine (Tesseract with the bundled models). Each case reports the share of the
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
        runBlocking { DocumentOcrEngine(context).processPages(uris.toList(), language, "en") }

    private val results = StringBuilder()

    private fun check(case: String, expected: String, text: String, minimum: Double) {
        val r = recall(expected, text)
        val line = "OCR $case: recall=${"%.2f".format(r)} text=${text.replace('\n', ' ').take(300)}"
        Log.i("OcrDeviceTest", line)
        results.append(line).append('\n')
        File(dir, "results.txt").appendText(line + "\n")
        assertTrue("$case: only ${"%.0f".format(r * 100)}% of the words recognized: $text", r >= minimum)
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

    @Test fun rotatedPage() { val r = ocr(page("rotated", listOf(english), rotate = 90f)); check("rotated-90", english, r.pages.single().rawText, 0.7) }

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
