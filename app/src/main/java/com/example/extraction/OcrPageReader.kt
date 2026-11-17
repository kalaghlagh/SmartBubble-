package com.example.extraction

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import com.example.ocr.OcrPreprocessor
import com.example.ocr.paddle.LineReading
import com.example.ocr.paddle.PaddleOrtOcrEngine
import com.example.ocr.paddle.RecLine
import com.example.ocr.paddle.RecWords
import com.example.ocr.paddle.RtlText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads one page image into an [ExtractedPage], region by region (PaddleOCR on ONNX Runtime):
 *
 * A. The page is straightened (skew), made grey with its contrast stretched, and turned upright
 *    when it was photographed sideways.
 * B. Detection: every text region of the page gets a box (the detector does not drop short
 *    lines the way a layout pass can).
 * C. Recognition: each region is read by the model the page prefers ([preferred]); the other
 *    model reads it too when needed ([LineReading.needsSecond]). An Arabic line keeps the Arabic
 *    reading with its Latin words repaired from the Latin reading of the same crop
 *    ([com.example.ocr.paddle.ScriptMerge]); a Latin line keeps the better of both readings.
 *    Right-to-left text is put back in reading order ([RtlText]); words keep their boxes.
 * D. Rows of the image that no region covers are read on their own and marked for review.
 * E. Structure: [LayoutAnalyzer] rebuilds headings, lists, tables, paragraphs; table cells are
 *    read again in the script their row and column expect, compared by [CandidateEvaluator].
 *
 * Page text is never written to the log (only counts and timings).
 */
class OcrPageReader(
    private val engine: PaddleOrtOcrEngine,
    /** The model read first; the other one is read only where needed. */
    private val preferred: OcrModel = OcrModel.ARABIC
) {

    /** Time spent on the last page, by stage (ms), for the performance report. */
    val timings = LinkedHashMap<String, Long>()

    private fun gray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val px = IntArray(w * bitmap.height)
        bitmap.getPixels(px, 0, w, 0, 0, w, bitmap.height)
        for (i in px.indices) px[i] = px[i] and 0xFF // prepared pages are grey: one channel is enough
        return px
    }

    private fun crop(page: Bitmap, box: Box): Pair<Bitmap, Box>? {
        val l = box.left.toInt().coerceAtLeast(0)
        val t = box.top.toInt().coerceAtLeast(0)
        val r = box.right.toInt().coerceAtMost(page.width)
        val b = box.bottom.toInt().coerceAtMost(page.height)
        if (r - l < 4 || b - t < 4) return null
        return Bitmap.createBitmap(page, l, t, r - l, b - t) to Box(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
    }

    private fun other(model: OcrModel) = if (model == OcrModel.ARABIC) OcrModel.LATIN else OcrModel.ARABIC

    /** A region read by the first model, and by the second one when needed. */
    private suspend fun readRegion(page: Bitmap, box: Box, first: OcrModel = preferred): Pair<LineReading.Result, Box>? {
        val (img, cropBox) = crop(page, box) ?: return null
        try {
            val a = engine.recognize(img, first)
            currentCoroutineContext().ensureActive()
            val b = if (LineReading.needsSecond(a)) engine.recognize(img, other(first)) else null
            val result = LineReading.combine(a, b)
            trace { "region ${cropBox.top.toInt()}-${cropBox.bottom.toInt()} readings=${listOfNotNull(a, b).map { "${it.model}:${it.confidence}:${it.text}" }} kept=${result.line.model}:${logical(result.line)} review=${result.reasons}" }
            return result to cropBox
        } finally {
            img.recycle()
        }
    }

    /** Words of a reading in reading order, on the page. */
    private fun words(result: LineReading.Result, cropBox: Box, region: Box): List<TextWord> {
        val box = Box(cropBox.left, maxOf(cropBox.top, region.top), cropBox.right, minOf(cropBox.bottom, region.bottom))
        return RecWords.words(result.line, box)
    }

    private fun logical(line: RecLine) = RtlText.visualToLogical(line.text)

    /**
     * Bounding box of the dark pixels inside [area] (the ink of a table cell or of a row of the
     * image), or null when there is none.
     */
    private fun ink(g: IntArray, width: Int, height: Int, area: Box): Box? {
        val l = area.left.toInt().coerceIn(0, width - 1)
        val r = area.right.toInt().coerceIn(0, width - 1)
        val t = area.top.toInt().coerceIn(0, height - 1)
        val b = area.bottom.toInt().coerceIn(0, height - 1)
        var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
        for (y in t..b) for (x in l..r) if (g[y * width + x] < 110) {
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < minX || maxY < minY) return null
        return Box(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
    }

    /**
     * A table cell read on its own by the models its row and column expect, compared with what
     * the page reading found there. The crop is the ink inside the cell (inside its borders when
     * it has some) with a small margin; a cell holding several lines keeps the page reading.
     */
    private fun readCell(ctx: kotlin.coroutines.CoroutineContext, page: Bitmap, g: IntArray, lineH: Float, box: Box, words: List<TextWord>, bordered: Boolean,
                                 expected: Script?, column: List<String>): TableCell? {
        val pageText = words.joinToString(" ") { it.text }
        val pageConf = words.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val numeric = OcrRouting.isNumeric(pageText)
        val inside = if (bordered) Box(box.left + 5, box.top + 5, box.right - 5, box.bottom - 5) else box
        val inkBox = ink(g, page.width, page.height, inside) ?: return null
        if (inkBox.height > lineH * 1.8f) return null
        val pad = (inkBox.height * 0.25f).coerceIn(3f, 12f)
        val region = Box(inkBox.left - pad, inkBox.top - pad, inkBox.right + pad, inkBox.bottom + pad)
        val candidates = ArrayList<RecognitionCandidate>()
        if (pageText.isNotBlank()) candidates += RecognitionCandidate(pageText, pageConf, "page")
        val (img, cropBox) = crop(page, region) ?: return null
        try {
            for (model in OcrRouting.plan(pageText, expected, numeric)) {
                ctx.ensureActive()
                val line = engine.recognize(img, model)
                val text = RecWords.words(line, cropBox).joinToString(" ") { it.text }
                if (text.isNotBlank()) candidates += RecognitionCandidate(text, line.confidence, model.key, model.script)
            }
        } finally {
            img.recycle()
        }
        val e = CandidateEvaluator.evaluate(candidates, expected, numeric, column)
        trace { "cell expected=$expected column=$column candidates=${candidates.map { "${it.engine}:${it.confidence}:${it.text}" }} chosen=${e.chosen?.text} review=${e.reasons}" }
        val chosen = e.chosen ?: return null
        return TableCell(chosen.text, confidence = chosen.confidence, needsReview = e.needsReview)
    }

    /** A row of the image no region covers, read by both models and checked. */
    private suspend fun recoverBand(page: Bitmap, g: IntArray, band: IntRange, existing: List<TextLine>): TextLine? {
        val box = ink(g, page.width, page.height, Box(0f, band.first.toFloat(), (page.width - 1).toFloat(), band.last.toFloat())) ?: return null
        val pad = (box.height * 0.2f).coerceIn(2f, 10f)
        val region = Box(box.left - pad * 2, box.top - pad, box.right + pad * 2, box.bottom + pad)
        val (img, cropBox) = crop(page, region) ?: return null
        val readings = try {
            listOf(engine.recognize(img, OcrModel.ARABIC), engine.recognize(img, OcrModel.LATIN))
        } finally {
            img.recycle()
        }
        val result = LineReading.combine(readings[0], readings[1])
        val words = RecWords.words(result.line, cropBox)
        val text = words.joinToString(" ") { it.text }
        trace { "band ${band.first}-${band.last} readings=${readings.map { "${it.model}:${it.confidence}:${it.text}" }} kept=$text" }
        val plausible = CandidateEvaluator.plausibility(text) >= 0.6
        if (words.isEmpty() || (result.line.confidence < 0.3f && !plausible) || OcrDecisions.isDuplicate(text, existing)) return null
        // A recovered line is always shown for review.
        return TextLine(words, Box.of(words.map { it.box })!!, source = "band", needsReview = true, engines = result.models)
    }

    private fun mean(lines: List<TextLine>): Float? = lines.flatMap { it.words }.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    /** Detection and recognition of the whole page: its text lines. */
    private suspend fun readLines(page: Bitmap, notes: MutableList<String>): List<TextLine> {
        val boxes = engine.detect(page)
        currentCoroutineContext().ensureActive()
        // The page's own script decides which model reads first: after a few regions, the
        // script most regions turned out to be.
        var arabicRegions = 0
        var latinRegions = 0
        var merged = 0
        val regions = ArrayList<Pair<Box, List<TextWord>>>()
        val infos = ArrayList<RecWords.LineInfo>()
        for (box in boxes) {
            currentCoroutineContext().ensureActive()
            val first = when {
                arabicRegions + latinRegions < 3 -> preferred
                arabicRegions >= latinRegions -> OcrModel.ARABIC
                else -> OcrModel.LATIN
            }
            val (result, cropBox) = readRegion(page, box, first) ?: continue
            if (result.script == Script.ARABIC) arabicRegions++ else if (result.script == Script.LATIN) latinRegions++
            merged += result.mergedRuns
            var ws = words(result, cropBox, box)
            // Safety net for the merge: a word the Arabic model read as letters with high
            // confidence is not replaced by digits from the Latin reading.
            val primary = result.primary
            if (result.mergedRuns > 0 && primary != null) {
                val arabicOnly = RecWords.words(primary, Box(cropBox.left, box.top, cropBox.right, box.bottom))
                ws = OcrDecisions.fuseWords(TextLine(ws, box), listOf(TextLine(arabicOnly, box)), minConfidence = 0.9f).first.words
            }
            regions += box to ws
            infos += RecWords.LineInfo(result.line.model, result.needsReview, result.models)
        }
        if (merged > 0) notes += "latin_merged:$merged"
        return RecWords.lines(regions) { infos[it] }
    }

    suspend fun read(original: Bitmap, index: Int, source: PageSource, imageUri: String?): ExtractedPage {
        val notes = ArrayList<String>()
        timings.clear()
        var t0 = SystemClock.elapsedRealtime()
        fun lap(stage: String) { val now = SystemClock.elapsedRealtime(); timings[stage] = now - t0; t0 = now }
        // A. Straighten a slightly skewed page (measured on a reduced copy).
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
        lap("prepare")
        try {
            currentCoroutineContext().ensureActive()
            // B-C. Detection and recognition; a page photographed sideways is turned upright.
            var lines = readLines(page, notes)
            if (OcrPreprocessor.isWeak(lines.joinToString(" ") { it.text }, mean(lines) ?: 0f)) {
                for (deg in listOf(90, 270, 180)) {
                    currentCoroutineContext().ensureActive()
                    val rotated = OcrPreprocessor.rotate(page, deg)
                    val r = readLines(rotated, ArrayList())
                    val better = OcrPreprocessor.score(r.joinToString(" ") { it.text }, mean(r) ?: 0f) >
                        OcrPreprocessor.score(lines.joinToString(" ") { it.text }, mean(lines) ?: 0f) * 1.2
                    if (better) { page.recycle(); page = rotated; lines = r; notes += "rotated:$deg"; break } else rotated.recycle()
                }
            }
            trace { "page lines=${lines.map { "${it.box.top.toInt()}-${it.box.bottom.toInt()}:${it.confidence}:${it.text}" }}" }
            lap("lines")

            // D. Rows of the image no region covers.
            val g = gray(page)
            val allBands = OcrPreprocessor.textLineBands(page)
            val missed = OcrDecisions.missedBands(allBands, lines.map { it.box })
            trace { "bands=${allBands.map { "${it.first}-${it.last}" }} missed=${missed.map { "${it.first}-${it.last}" }}" }
            var recovered = 0
            for (band in missed) {
                currentCoroutineContext().ensureActive()
                val line = recoverBand(page, g, band, lines) ?: continue
                lines = lines.toMutableList().apply { add(OcrDecisions.insertionIndex(this, line.box.top), line) }
                recovered++
            }
            notes += "ink_lines:${allBands.size}/read:${lines.size - recovered}/missed:${missed.size}"
            if (recovered > 0) notes += "lines_recovered:$recovered"
            if (missed.size > recovered) notes += "possible_missing_lines:${missed.size - recovered}"
            lap("recovery")

            // E. Structure, with table cells read in the script of their row and column.
            val rulings = TableReconstructor.detectRulings(g, page.width, page.height)
            val lineH = lines.map { it.box.height }.sorted().let { if (it.isEmpty()) 40f else it[it.size / 2] }
            val pageBitmap = page
            val ctx = currentCoroutineContext()
            val blocks = LayoutAnalyzer.analyze(lines, page.width.toFloat(), page.height.toFloat(), rulings,
                CellReader { box, ws, bordered, expected, column -> readCell(ctx, pageBitmap, g, lineH, box, ws, bordered, expected, column) })
            lap("structure")
            if (blocks.any { it is ExtractedBlock.Table && it.uncertain }) notes += "table_uncertain"
            val words = lines.flatMap { it.words }
            Log.i(TAG, "Page ${index + 1}: ${lines.size} lines, ${words.size} words, ${blocks.size} blocks, notes=$notes, ms=$timings, loads=${engine.loadTimes}")
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
                imageUri = imageUri,
                timingsMs = LinkedHashMap(timings)
            )
        } finally {
            page.recycle()
        }
    }

    private fun trace(message: () -> String) { trace?.invoke(message()) }

    companion object {
        private const val TAG = "OcrPageReader"

        /**
         * Receives the readings considered for each region, row and cell, for the device tests'
         * reports only. Null in the app: page text is never logged.
         */
        @Volatile var trace: ((String) -> Unit)? = null
    }
}
