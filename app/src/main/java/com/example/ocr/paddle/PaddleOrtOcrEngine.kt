package com.example.ocr.paddle

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import com.example.extraction.Box
import com.example.extraction.OcrModel
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The app's OCR engine: PaddleOCR PP-OCRv5 mobile text detection and recognition (Arabic and
 * Latin models) run by ONNX Runtime on the CPU. XNNPACK and NNAPI are not enabled (reported to
 * abort on these graphs by other apps using them).
 *
 * Models are verified ([ModelInstaller]) and loaded on first use; at most [maxRecognizers]
 * recognition models are open at once (one on low-memory devices), the least recently used one
 * being closed first. [stop] aborts a run in progress (cancellation); [close] releases everything.
 * Pre- and post-processing follow MakeACopy's PaddleDetOrtRunner / PaddleRecOrtRunner
 * (Apache-2.0): detection input BGR normalized with ImageNet mean/std, longest side up to 1536
 * on a 32-pixel grid; recognition input 48 pixels high, RGB scaled to [-1, 1].
 */
class PaddleOrtOcrEngine(
    private val installer: ModelInstaller,
    private val maxRecognizers: Int = 2,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val lock = Any()
    private var detector: OrtSession? = null
    private val recognizers = LinkedHashMap<OcrModel, Pair<OrtSession, List<String>>>()
    @Volatile private var runOptions = OrtSession.RunOptions()
    @Volatile private var closed = false

    /** Loading time (ms) of each model opened by this engine. */
    val loadTimes = LinkedHashMap<String, Long>()

    private fun options() = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(threads)
    }

    private fun open(model: ModelFile): OrtSession {
        val file = installer.file(model)
        val start = SystemClock.elapsedRealtime()
        val session = try {
            options().use { env.createSession(file.absolutePath, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Session with full optimization failed for ${model.name}, retrying with defaults: ${e.message}")
            OrtSession.SessionOptions().use { env.createSession(file.absolutePath, it) }
        }
        loadTimes[model.name.substringBefore('.')] = SystemClock.elapsedRealtime() - start
        return session
    }

    private fun detectorSession(): OrtSession = synchronized(lock) {
        check(!closed) { "OCR engine closed" }
        detector ?: open(PaddleModels.DET).also { detector = it }
    }

    private fun recognizer(model: OcrModel): Pair<OrtSession, List<String>> = synchronized(lock) {
        check(!closed) { "OCR engine closed" }
        recognizers.remove(model)?.let { recognizers[model] = it; return it } // most recently used last
        while (recognizers.size >= maxRecognizers) {
            val oldest = recognizers.keys.first()
            recognizers.remove(oldest)?.first?.close()
        }
        val (file, dict) = when (model) {
            OcrModel.ARABIC -> PaddleModels.ARABIC to PaddleModels.ARABIC_DICT
            OcrModel.LATIN -> PaddleModels.LATIN to PaddleModels.LATIN_DICT
        }
        val session = open(file)
        val classes = try {
            (session.outputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape.last().toInt()
        } catch (e: Exception) { -1 }
        val lines = installer.file(dict).readLines(Charsets.UTF_8)
        val vocab = if (classes > 0) CtcDecoder.vocabulary(lines, classes) else listOf("") + lines.filter { it.isNotEmpty() }
        val entry = session to vocab
        recognizers[model] = entry
        entry
    }

    /** Loads the detection model now (to report a missing or damaged model before any page). */
    fun warmUp() { detectorSession() }

    /** Text regions of [page], in page pixels, top to bottom. */
    fun detect(page: Bitmap, maxSide: Int = 1536): List<Box> {
        val session = detectorSession()
        val sw = page.width; val sh = page.height
        val stride = 32
        val limit = max(stride, maxSide / stride * stride)
        val scale = min(1.0, limit.toDouble() / max(sw, sh))
        val scaledW = (sw * scale).roundToInt().coerceAtLeast(1)
        val scaledH = (sh * scale).roundToInt().coerceAtLeast(1)
        val dw = (max(stride, scaledW) + stride - 1) / stride * stride
        val dh = (max(stride, scaledH) + stride - 1) / stride * stride
        val padX = (dw - sw * scale) / 2.0
        val padY = (dh - sh * scale) / 2.0
        val target = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        val input: FloatArray
        try {
            val canvas = Canvas(target)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(page, Matrix().apply { postScale(scale.toFloat(), scale.toFloat()); postTranslate(padX.toFloat(), padY.toFloat()) }, Paint(Paint.FILTER_BITMAP_FLAG))
            input = bgrNormalized(target)
        } finally {
            target.recycle()
        }
        val prob = run(session, input, longArrayOf(1, 3, dh.toLong(), dw.toLong())).first
        return DbPostProcess.boxes(prob, dw, dh).map { b ->
            Box(
                ((b.left - padX) / scale).toFloat().coerceIn(0f, sw.toFloat()),
                ((b.top - padY) / scale).toFloat().coerceIn(0f, sh.toFloat()),
                ((b.right - padX) / scale).toFloat().coerceIn(0f, sw.toFloat()),
                ((b.bottom - padY) / scale).toFloat().coerceIn(0f, sh.toFloat())
            )
        }.filter { it.width >= 4f && it.height >= 4f }.sortedWith(compareBy({ it.top }, { it.left }))
    }

    /** One line crop read by [model]: raw CTC output in visual order. */
    fun recognize(crop: Bitmap, model: OcrModel): RecLine {
        val (session, vocab) = recognizer(model)
        val h = 48
        val scaledW = (crop.width * h.toDouble() / max(1, crop.height)).roundToInt().coerceIn(16, 4000)
        val paddedW = (scaledW + 7) / 8 * 8
        val target = Bitmap.createBitmap(paddedW, h, Bitmap.Config.ARGB_8888)
        val input: FloatArray
        try {
            val canvas = Canvas(target)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(crop, Matrix().apply { postScale(scaledW.toFloat() / crop.width, h.toFloat() / crop.height) }, Paint(Paint.FILTER_BITMAP_FLAG))
            input = rgbSigned(target)
        } finally {
            target.recycle()
        }
        val (logits, shape) = run(session, input, longArrayOf(1, 3, h.toLong(), paddedW.toLong()))
        val steps = shape.getOrElse(1) { 0L }.toInt()
        val classes = shape.getOrElse(2) { 0L }.toInt()
        return CtcDecoder.decode(logits, steps, classes, vocab, scaledW.toFloat() / paddedW, model.key)
    }

    private fun run(session: OrtSession, input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        check(!closed) { "OCR engine closed" }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor), runOptions).use { result ->
                val out = result.get(0) as OnnxTensor
                val buf = out.floatBuffer
                val arr = FloatArray(buf.remaining())
                buf.get(arr)
                return arr to out.info.shape
            }
        }
    }

    /** Aborts a recognition in progress; the engine stays usable for the next one. */
    fun stop() {
        val current = runOptions
        try { current.setTerminate(true) } catch (e: Exception) { /* finished */ }
        runOptions = OrtSession.RunOptions()
        try { current.close() } catch (e: Exception) { /* in use: released by GC */ }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try { runOptions.setTerminate(true) } catch (e: Exception) { }
            detector?.close(); detector = null
            recognizers.values.forEach { it.first.close() }
            recognizers.clear()
        }
    }

    private companion object {
        const val TAG = "PaddleOrtOcrEngine"
        val MEAN_BGR = floatArrayOf(0.406f * 255f, 0.456f * 255f, 0.485f * 255f)
        val STD_BGR = floatArrayOf(0.225f * 255f, 0.224f * 255f, 0.229f * 255f)

        fun bgrNormalized(bm: Bitmap): FloatArray {
            val w = bm.width; val h = bm.height
            val px = IntArray(w * h)
            bm.getPixels(px, 0, w, 0, 0, w, h)
            val plane = w * h
            val out = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = px[i]
                out[i] = ((p and 0xFF) - MEAN_BGR[0]) / STD_BGR[0]
                out[plane + i] = (((p shr 8) and 0xFF) - MEAN_BGR[1]) / STD_BGR[1]
                out[2 * plane + i] = (((p shr 16) and 0xFF) - MEAN_BGR[2]) / STD_BGR[2]
            }
            return out
        }

        fun rgbSigned(bm: Bitmap): FloatArray {
            val w = bm.width; val h = bm.height
            val px = IntArray(w * h)
            bm.getPixels(px, 0, w, 0, 0, w, h)
            val plane = w * h
            val out = FloatArray(3 * plane)
            for (i in 0 until plane) {
                val p = px[i]
                out[i] = ((p shr 16) and 0xFF) / 127.5f - 1f
                out[plane + i] = ((p shr 8) and 0xFF) / 127.5f - 1f
                out[2 * plane + i] = (p and 0xFF) / 127.5f - 1f
            }
            return out
        }
    }
}
