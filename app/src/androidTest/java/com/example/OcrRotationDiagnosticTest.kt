package com.example

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.extraction.OcrModel
import com.example.extraction.OcrPageReader
import com.example.ocr.DocumentOcrEngine
import com.example.ocr.OcrLanguage
import com.example.ocr.OcrPreprocessor
import com.example.ocr.PageImageLoader
import com.example.ocr.paddle.ModelInstaller
import com.example.ocr.paddle.PaddleModels
import com.example.ocr.paddle.PaddleOrtOcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

/**
 * Stage by stage, on the exact fixture of OcrDeviceTest's rotated pages (90 and 270 degrees, with
 * 0 and 180 as controls): the source bitmap, the skew the reader measures on it, the page after
 * that deskew and after preparation, each turn and its size, the detector's input image, its raw
 * boxes and scores, the crops of the boxes, the exact 48-pixel recognizer inputs and the raw
 * recognitions. Every stage is run twice: as the reader did before the fix (deskew on the page as
 * given) and without that deskew. Images go to the instrumentation output (DiagnosticImages),
 * values as "DIAG" lines. Then the time and memory of the whole pipeline for each orientation.
 */
@RunWith(AndroidJUnit4::class)
class OcrRotationDiagnosticTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File by lazy { File(context.getExternalFilesDir(null), "ocr-rotation").apply { mkdirs() } }

    private fun log(line: String) =
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("diag", "DIAG ${line.take(3000)}") })

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).filter { it.isLetterOrDigit() || it.isWhitespace() }

    private fun recall(expected: String, got: String): Double {
        val found = normalize(got).split(Regex("\\s+")).toSet()
        val words = normalize(expected).split(Regex("\\s+")).filter { it.length >= 3 }
        return words.count { it in found }.toDouble() / words.size.coerceAtLeast(1)
    }

    private fun engine() = PaddleOrtOcrEngine(ModelInstaller(File(context.noBackupFilesDir, PaddleModels.DIR)) { context.assets.open(it) })

    /** The turn that makes a page drawn with [rotation] upright. */
    private fun upright(rotation: Int) = (360 - rotation) % 360

    private fun stages(rotation: Int, text: String) {
        val uri = OcrFixtures.page(dir, "diag-$rotation", listOf(text), rotate = rotation.toFloat())
        val src = PageImageLoader.load(context, uri)
        val tag = "rot$rotation"
        log("$tag source ${src.width}x${src.height} config=${src.config} hasAlpha=${src.hasAlpha()}")
        DiagnosticImages.emit("$tag/full-source", src)
        engine().use { e ->
            val reader = OcrPageReader(e, OcrModel.LATIN)
            val skew = reader.skewOf(src)
            log("$tag skew measured on the page as given=$skew verticalText=${reader.verticalText(src)}")
            for (preDeskew in listOf(true, false)) {
                val mode = if (preDeskew) "deskew-before-turn" else "no-deskew-before-turn"
                val straight = if (preDeskew && skew != 0f) reader.unskew(src, skew) else src
                val prepared = OcrPreprocessor.prepare(straight)
                if (straight !== src) straight.recycle()
                val turn = upright(rotation)
                val turned = if (turn == 0) prepared else OcrPreprocessor.rotate(prepared, turn)
                log("$tag $mode prepared ${prepared.width}x${prepared.height} turn=$turn turned ${turned.width}x${turned.height} " +
                    "skew of the turned page=${reader.skewOf(turned)}")
                DiagnosticImages.emit("$tag/$mode/full-turned", turned)
                e.onDetectInput = { input -> log("$tag $mode detector input ${input.width}x${input.height}"); DiagnosticImages.emit("$tag/$mode/full-detinput", input) }
                val boxes = e.detectScored(turned)
                e.onDetectInput = null
                log("$tag $mode boxes ${boxes.map { (b, sc) -> "[${b.left.toInt()},${b.top.toInt()} ${b.width.toInt()}x${b.height.toInt()} p=${"%.2f".format(sc)}]" }}")
                val texts = ArrayList<String>()
                for ((k, pair) in boxes.withIndex()) {
                    val b = pair.first
                    val l = b.left.toInt().coerceAtLeast(0)
                    val t = b.top.toInt().coerceAtLeast(0)
                    val crop = Bitmap.createBitmap(turned, l, t, b.right.toInt().coerceAtMost(turned.width) - l, b.bottom.toInt().coerceAtMost(turned.height) - t)
                    if (k < 3) DiagnosticImages.emit("$tag/$mode/crop-$k", crop)
                    e.onRecognizeInput = { input, line ->
                        log("$tag $mode box $k crop ${crop.width}x${crop.height} -> recognizer input ${input.width}x${input.height} raw '${line.text}' conf=${"%.2f".format(line.confidence)} chars=${line.chars.size}")
                        if (k < 3) DiagnosticImages.emit("$tag/$mode/recinput-$k", input)
                    }
                    texts += e.recognize(crop, OcrModel.LATIN).text
                    e.onRecognizeInput = null
                    crop.recycle()
                }
                log("$tag $mode recall=${"%.2f".format(recall(text, texts.joinToString(" ")))} text=${texts.joinToString(" | ")}")
                if (turned !== prepared) turned.recycle()
                prepared.recycle()
            }
        }
        src.recycle()
    }

    @Test
    fun rotatedPages_stageByStage() {
        DiagnosticImages.limit = 160
        try {
            // Width and height swap with a quarter turn, as the fixture says.
            stages(90, OcrFixtures.ENGLISH)
            stages(270, OcrFixtures.FRENCH)
            stages(0, OcrFixtures.ENGLISH)
            stages(180, OcrFixtures.ENGLISH)
            val a = OcrFixtures.page(dir, "dims", listOf(OcrFixtures.ENGLISH), rotate = 90f)
            val b = PageImageLoader.load(context, a)
            assertEquals(1754, b.width); assertEquals(1240, b.height)
            b.recycle()
        } finally {
            DiagnosticImages.limit = 60
        }
    }

    /** Time and peak memory of the whole pipeline (DocumentOcrEngine) for each orientation. */
    @Test
    fun timeAndMemory_byOrientation() {
        val ocr = DocumentOcrEngine(context)
        for (rotation in listOf(0, 90, 180, 270)) {
            val text = if (rotation == 270) OcrFixtures.FRENCH else OcrFixtures.ENGLISH
            val uri = OcrFixtures.page(dir, "perf-$rotation", listOf(text), rotate = rotation.toFloat())
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
            val r = runBlocking { ocr.processPages(listOf(uri), OcrLanguage.AUTO, "en") }
            val ms = SystemClock.elapsedRealtime() - t
            sampler.interrupt(); sampler.join()
            val page = r.pages.single()
            log("perf rot$rotation ms=$ms peakMB=${"%.1f".format((peak - before) / 1048576.0)} recall=${"%.2f".format(recall(text, page.rawText))} " +
                "notes=${page.extraction?.notes} loads=${ocr.lastLoadTimes}")
        }
    }
}
