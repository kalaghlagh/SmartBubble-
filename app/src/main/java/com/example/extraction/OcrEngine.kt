package com.example.extraction

import android.graphics.Bitmap
import android.os.SystemClock
import com.googlecode.tesseract.android.TessBaseAPI

/** How an image region is segmented by the recognizer. */
enum class Segmentation(val psm: Int) {
    PAGE(TessBaseAPI.PageSegMode.PSM_AUTO),
    BLOCK(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK),
    LINE(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE)
}

/**
 * One recognition configuration (models + options) behind a common interface, so regions can be
 * routed to the configuration that suits their script and another backend can be added later.
 */
interface OcrEngine {
    val config: OcrConfig
    /** Words with positions and confidence, grouped in lines, offset by ([dx], [dy]). */
    fun lines(image: Bitmap, mode: Segmentation, dx: Int = 0, dy: Int = 0, source: String? = null): List<TextLine>
    /** Plain text and mean confidence (0..1) of [image]. */
    fun text(image: Bitmap, mode: Segmentation): Pair<String, Float>
    /** Interrupts a recognition in progress (cancellation). */
    fun stop()
    fun close()
}

/** Tesseract (Tesseract4Android) with one set of bundled models. */
class TesseractOcrEngine private constructor(override val config: OcrConfig, private val tess: TessBaseAPI) : OcrEngine {

    companion object {
        /** Opens the models [languages] from [dataPath]; null when they cannot be loaded. */
        fun open(config: OcrConfig, dataPath: String, languages: String): TesseractOcrEngine? {
            val t = try { TessBaseAPI() } catch (e: Throwable) { return null }
            val ok = try { t.init(dataPath, languages, TessBaseAPI.OEM_LSTM_ONLY) } catch (e: Throwable) { false }
            if (!ok) { try { t.recycle() } catch (e: Throwable) { }; return null }
            t.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            // Scanned pages are about 300 dpi; without this Tesseract assumes 70 dpi for bitmaps.
            t.setVariable("user_defined_dpi", "300")
            if (config.numeric) t.setVariable("tessedit_char_whitelist", "0123456789.,%/-+:()°")
            return TesseractOcrEngine(config, t)
        }
    }

    private fun <T> withMode(mode: Segmentation, block: () -> T): T {
        tess.setPageSegMode(mode.psm)
        try { return block() } finally { tess.clear() }
    }

    override fun lines(image: Bitmap, mode: Segmentation, dx: Int, dy: Int, source: String?): List<TextLine> = withMode(mode) {
        tess.setImage(image)
        // getHOCRText runs the recognition in the mode stop() can interrupt.
        tess.getHOCRText(0)
        val it = tess.resultIterator ?: return@withMode emptyList()
        val out = ArrayList<TextLine>()
        var words = ArrayList<TextWord>()
        var startsPara = false
        fun flush() {
            if (words.isNotEmpty()) out += TextLine(words, Box.of(words.map { w -> w.box })!!, startsPara, source)
            words = ArrayList()
        }
        try {
            val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
            it.begin()
            do {
                if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) {
                    flush()
                    startsPara = it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA)
                }
                val text = it.getUTF8Text(level)?.trim()
                if (text.isNullOrEmpty()) continue
                val r = it.getBoundingRect(level)
                words += TextWord(text, Box((r.left + dx).toFloat(), (r.top + dy).toFloat(), (r.right + dx).toFloat(), (r.bottom + dy).toFloat()),
                    (it.confidence(level) / 100f).coerceIn(0f, 1f))
            } while (it.next(level))
            flush()
        } finally {
            it.delete()
        }
        out
    }

    override fun text(image: Bitmap, mode: Segmentation): Pair<String, Float> = withMode(mode) {
        tess.setImage(image)
        tess.getHOCRText(0)
        val t = tess.getUTF8Text().orEmpty().trim().replace(Regex("""\s*\n\s*"""), " ")
        t to (tess.meanConfidence() / 100f).coerceIn(0f, 1f)
    }

    override fun stop() { try { tess.stop() } catch (e: Throwable) { /* finished */ } }
    override fun close() { try { tess.recycle() } catch (e: Throwable) { /* released */ } }
}

/**
 * The recognition configurations of one OCR session, each opened on first use only (so models
 * that a document does not need are never loaded) and reused for every page. Load times are
 * recorded for the performance report.
 */
class OcrEngineRegistry(
    private val dataPath: String,
    /** Models of the page configuration, in priority order ("eng+ara+fra"). */
    private val pageLanguages: String,
    /** Models installed for this session. */
    private val models: Set<String>
) {
    private val lock = Any()
    private val engines = HashMap<OcrConfig, OcrEngine?>()
    private var closed = false

    /** Milliseconds spent loading each configuration. */
    val loadTimes = LinkedHashMap<OcrConfig, Long>()

    private fun languages(config: OcrConfig): String? = when (config) {
        OcrConfig.PAGE -> pageLanguages
        OcrConfig.ARABIC -> "ara".takeIf { it in models }
        OcrConfig.FRENCH -> "fra".takeIf { it in models }
        OcrConfig.ENGLISH -> "eng".takeIf { it in models }
        OcrConfig.LATIN -> listOf("fra", "eng").filter { it in models }.joinToString("+").ifEmpty { null }
        OcrConfig.NUMERIC -> "eng".takeIf { it in models } ?: models.firstOrNull()
    }

    /** The engine for [config], or null when its models are not available. */
    fun get(config: OcrConfig): OcrEngine? {
        synchronized(lock) {
            if (closed) return null
            if (engines.containsKey(config)) return engines[config]
        }
        val langs = languages(config)
        val start = SystemClock.elapsedRealtime()
        val engine = langs?.let { TesseractOcrEngine.open(config, dataPath, it) }
        synchronized(lock) {
            if (closed) { engine?.close(); return null }
            loadTimes[config] = SystemClock.elapsedRealtime() - start
            engines[config] = engine
        }
        return engine
    }

    val pageArabicFirst: Boolean get() = pageLanguages.startsWith("ara")

    fun stopAll() = synchronized(lock) { engines.values.forEach { it?.stop() } }

    fun closeAll() = synchronized(lock) {
        closed = true
        engines.values.forEach { it?.close() }
        engines.clear()
    }
}
