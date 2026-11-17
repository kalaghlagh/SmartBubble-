package com.example.extraction

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import com.example.ocr.OcrPreprocessor
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads one page image into an [ExtractedPage], with the Tesseract engines of an OCR session:
 *
 * 1. The page is straightened (skew), turned grey with its contrast stretched, and read once
 *    with all the chosen models (words with positions and confidence).
 * 2. Each line is classified by script. Arabic and mixed lines are read again with the Arabic
 *    model alone, and weak Latin lines with French and English alone; the reading kept is
 *    decided by confidence and script ([OcrDecisions.choose]), never by length.
 * 3. Text rows of the image that no recognized line covers are read on their own and inserted
 *    in reading order, unless they repeat a line already read.
 * 4. Drawn table borders are detected, and [LayoutAnalyzer] rebuilds headings, lists, tables
 *    and paragraphs.
 *
 * Page text is never written to the log (only counts).
 */
class OcrPageReader(
    /** The page engine (all models, the app language first). */
    private val pageEngine: TessBaseAPI,
    /** The page engine puts the Arabic model first. */
    private val pageArabicFirst: Boolean,
    /** Arabic model alone, created on first use (null when Arabic is not among the models). */
    private val arabicEngine: () -> TessBaseAPI?,
    /** French + English models alone, created on first use (null when not available). */
    private val latinEngine: () -> TessBaseAPI?
) {

    private fun gray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val px = IntArray(w * bitmap.height)
        bitmap.getPixels(px, 0, w, 0, 0, w, bitmap.height)
        for (i in px.indices) px[i] = px[i] and 0xFF // prepared pages are grey: one channel is enough
        return px
    }

    /** Words of the engine's current result as lines, offset by ([dx], [dy]). */
    private fun lines(tess: TessBaseAPI, dx: Int, dy: Int, source: String?): List<TextLine> {
        val it = tess.resultIterator ?: return emptyList()
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
                words += TextWord(
                    text, Box((r.left + dx).toFloat(), (r.top + dy).toFloat(), (r.right + dx).toFloat(), (r.bottom + dy).toFloat()),
                    (it.confidence(level) / 100f).coerceIn(0f, 1f)
                )
            } while (it.next(level))
            flush()
        } finally {
            it.delete()
        }
        return out
    }

    private fun read(tess: TessBaseAPI, bitmap: Bitmap, dx: Int = 0, dy: Int = 0, mode: Int? = null, source: String? = null): List<TextLine> {
        if (mode != null) tess.setPageSegMode(mode)
        try {
            tess.setImage(bitmap)
            // getHOCRText runs the recognition in the mode that stop() can interrupt.
            tess.getHOCRText(0)
            return lines(tess, dx, dy, source)
        } finally {
            tess.clear()
            if (mode != null) tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        }
    }

    /** One line cut from the page with margins (whole words, accents and dots included) and read alone. */
    private fun readLine(tess: TessBaseAPI, page: Bitmap, box: Box, source: String): TextLine? {
        val h = box.height.coerceAtLeast(8f)
        val left = (box.left - h).toInt().coerceAtLeast(0)
        val top = (box.top - h * 0.35f).toInt().coerceAtLeast(0)
        val right = (box.right + h).toInt().coerceAtMost(page.width)
        val bottom = (box.bottom + h * 0.35f).toInt().coerceAtMost(page.height)
        if (right - left < 4 || bottom - top < 4) return null
        val crop = Bitmap.createBitmap(page, left, top, right - left, bottom - top)
        return try {
            val got = read(tess, crop, left, top, TessBaseAPI.PageSegMode.PSM_SINGLE_LINE, source)
            val words = got.flatMap { it.words }
            if (words.isEmpty()) null else TextLine(words, Box.of(words.map { it.box })!!, source = source)
        } finally {
            crop.recycle()
        }
    }

    /** Text and mean confidence of [tess] reading [crop] as one block. */
    private fun readBlock(tess: TessBaseAPI, crop: Bitmap): Pair<String, Float> {
        tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
        try {
            tess.setImage(crop)
            val t = tess.getUTF8Text().orEmpty().trim().replace(Regex("""\s*\n\s*"""), " ")
            return t to (tess.meanConfidence() / 100f).coerceIn(0f, 1f)
        } finally {
            tess.clear()
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        }
    }

    /**
     * One table cell read inside its borders, by the page model and by the Arabic and the Latin
     * models alone; the reading with the highest confidence is kept (a short Arabic word read by
     * the Latin model, or "mg/l" read by the Arabic one, comes out with a low confidence).
     */
    private fun readCell(page: Bitmap, box: Box): String? {
        val inset = 5
        val left = (box.left + inset).toInt().coerceAtLeast(0)
        val top = (box.top + inset).toInt().coerceAtLeast(0)
        val right = (box.right - inset).toInt().coerceAtMost(page.width)
        val bottom = (box.bottom - inset).toInt().coerceAtMost(page.height)
        if (right - left < 8 || bottom - top < 8) return null
        val crop = Bitmap.createBitmap(page, left, top, right - left, bottom - top)
        return try {
            listOfNotNull(pageEngine, arabicEngine(), latinEngine()).distinct()
                .map { readBlock(it, crop) }
                .filter { it.first.isNotBlank() }
                .maxByOrNull { it.second }?.first
        } finally {
            crop.recycle()
        }
    }

    private fun mean(lines: List<TextLine>): Float? = lines.flatMap { it.words }.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    suspend fun read(original: Bitmap, index: Int, source: PageSource, imageUri: String?): ExtractedPage {
        val notes = ArrayList<String>()
        // 1. Straighten a slightly skewed page (measured on a reduced copy).
        val angle = try {
            val small = Bitmap.createScaledBitmap(original, (original.width / 2).coerceAtLeast(1), (original.height / 2).coerceAtLeast(1), true)
            val prepared = OcrPreprocessor.prepare(small)
            if (small !== original) small.recycle()
            try { Deskew.estimate(gray(prepared), prepared.width, prepared.height) } finally { prepared.recycle() }
        } catch (e: Exception) { 0f }
        val straight = if (angle != 0f) {
            notes += "deskewed:$angle"
            Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888).also { out ->
                val c = android.graphics.Canvas(out)
                c.drawColor(Color.WHITE)
                c.drawBitmap(original, Matrix().apply { postRotate(-angle, original.width / 2f, original.height / 2f) }, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
        } else original
        var page = OcrPreprocessor.prepare(straight)
        if (straight !== original) straight.recycle()
        try {
            currentCoroutineContext().ensureActive()
            var lines = read(pageEngine, page)
            // A weak result may come from a page photographed sideways or upside down.
            if (OcrPreprocessor.isWeak(lines.joinToString(" ") { it.text }, mean(lines) ?: 0f)) {
                for (deg in listOf(90, 270, 180)) {
                    currentCoroutineContext().ensureActive()
                    val rotated = OcrPreprocessor.rotate(page, deg)
                    val r = read(pageEngine, rotated)
                    val better = OcrPreprocessor.score(r.joinToString(" ") { it.text }, mean(r) ?: 0f) >
                        OcrPreprocessor.score(lines.joinToString(" ") { it.text }, mean(lines) ?: 0f) * 1.2
                    if (better) { page.recycle(); page = rotated; lines = r; notes += "rotated:$deg"; break } else rotated.recycle()
                }
            }

            // 2. Script-aware re-reading of lines.
            var arabicReread = 0
            var latinReread = 0
            lines = lines.map { line ->
                currentCoroutineContext().ensureActive()
                when {
                    OcrDecisions.needsArabicReread(line, pageArabicFirst) -> arabicEngine()?.let { ar ->
                        var chosen = OcrDecisions.choose(line, readLine(ar, page, line.box, "ara"), arabicExpected = true)
                        if (chosen !== line) arabicReread++
                        // A short line with few letters ("P1  120 m  35 mg/l") can be Latin read as
                        // Arabic: the Latin models alone are tried too, kept only if clearly surer.
                        if (chosen.words.size <= 4) latinEngine()?.let { lat ->
                            val l = readLine(lat, page, line.box, "lat")
                            if (l != null && (l.confidence ?: 0f) > (chosen.confidence ?: 0f) + 0.1f) chosen = l
                        }
                        chosen
                    } ?: line
                    OcrDecisions.needsLatinReread(line) -> latinEngine()?.let { lat ->
                        val chosen = OcrDecisions.choose(line, readLine(lat, page, line.box, "lat"), arabicExpected = false)
                        if (chosen !== line) latinReread++
                        chosen
                    } ?: line
                    else -> line
                }
            }
            if (arabicReread > 0) notes += "arabic_lines_reread:$arabicReread"
            if (latinReread > 0) notes += "latin_lines_reread:$latinReread"

            // 3. Text rows nobody read (Tesseract's layout analysis can drop whole lines).
            val g = gray(page)
            val missed = OcrDecisions.missedBands(OcrPreprocessor.textLineBands(page), lines.map { it.box })
            var recovered = 0
            for (band in missed) {
                currentCoroutineContext().ensureActive()
                val box = Box(0f, band.first.toFloat(), page.width.toFloat(), band.last.toFloat())
                var line = readLine(pageEngine, page, box, "band") ?: continue
                if (ScriptDetector.isRtl(line.text)) arabicEngine()?.let { ar -> line = OcrDecisions.choose(line, readLine(ar, page, box, "band"), true) }
                if ((line.confidence ?: 0f) < 0.3f || OcrDecisions.isDuplicate(line.text, lines)) continue
                lines = lines.toMutableList().apply { add(OcrDecisions.insertionIndex(this, line.box.top), line) }
                recovered++
            }
            if (recovered > 0) notes += "lines_recovered:$recovered"

            // 4. Structure.
            val rulings = TableReconstructor.detectRulings(g, page.width, page.height)
            val blocks = LayoutAnalyzer.analyze(lines, page.width.toFloat(), page.height.toFloat(), rulings) { box -> readCell(page, box) }
            if (blocks.any { it is ExtractedBlock.Table && it.uncertain }) notes += "table_uncertain"
            val words = lines.flatMap { it.words }
            Log.i(TAG, "Page ${index + 1}: ${lines.size} lines, ${words.size} words, ${blocks.size} blocks, notes=$notes")
            return ExtractedPage(
                index = index,
                source = source,
                blocks = blocks,
                scripts = ScriptDetector.scriptsOf(lines.map { it.text }),
                level = if (blocks.isEmpty()) ReconstructionLevel.NONE else ReconstructionLevel.STRUCTURE,
                confidence = mean(lines),
                uncertainWords = words.count { (it.confidence ?: 1f) < 0.6f },
                notes = notes,
                error = if (blocks.isEmpty()) "no_text_found" else null,
                imageUri = imageUri
            )
        } finally {
            page.recycle()
        }
    }

    private companion object { const val TAG = "OcrPageReader" }
}
