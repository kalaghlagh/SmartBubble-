package com.example.extraction

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import com.example.ocr.OcrPreprocessor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Reads one page image into an [ExtractedPage], region by region:
 *
 * A. The page is straightened (skew), made grey with its contrast stretched, and turned upright
 *    when it was photographed sideways.
 * B. Layout: a first reading with all models gives the text lines and their boxes; the image's
 *    ink profile gives the text rows; drawn borders give the tables.
 * C. Script: each line's script is decided from its letters and its confident words.
 * D. Routing: each region is read again with the configuration of its script ([OcrRouting]):
 *    Arabic model for Arabic, French or English (or both) for Latin, digits for numbers.
 * E. Evaluation: the readings are compared by [CandidateEvaluator] (confidence, plausibility,
 *    script expected by the context, agreement, number vote); uncertain regions are marked for
 *    review. Rows of the image that no line covers are read on their own; table cells are read
 *    with the script of their row. [LayoutAnalyzer] rebuilds headings, lists, tables, paragraphs.
 *
 * Page text is never written to the log (only counts and timings).
 */
class OcrPageReader(private val engines: OcrEngineRegistry) {

    /** Time spent on the last page, by stage (ms), for the performance report. */
    val timings = LinkedHashMap<String, Long>()

    private fun gray(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val px = IntArray(w * bitmap.height)
        bitmap.getPixels(px, 0, w, 0, 0, w, bitmap.height)
        for (i in px.indices) px[i] = px[i] and 0xFF // prepared pages are grey: one channel is enough
        return px
    }

    private fun crop(page: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Pair<Bitmap, IntArray>? {
        val l = left.toInt().coerceAtLeast(0)
        val t = top.toInt().coerceAtLeast(0)
        val r = right.toInt().coerceAtMost(page.width)
        val b = bottom.toInt().coerceAtMost(page.height)
        if (r - l < 8 || b - t < 8) return null
        return Bitmap.createBitmap(page, l, t, r - l, b - t) to intArrayOf(l, t)
    }

    /**
     * One region read by [config] (whole words: margins of a line height around the box, and
     * [vMargin] above and below, a third of the height by default).
     */
    private fun readRegion(config: OcrConfig, page: Bitmap, box: Box, mode: Segmentation, vMargin: Float? = null): RecognitionCandidate? {
        val engine = engines.get(config) ?: return null
        val h = box.height.coerceAtLeast(8f)
        val v = vMargin ?: (h * 0.35f)
        val cropped = crop(page, box.left - h, box.top - v, box.right + h, box.bottom + v)
        val (img, origin) = cropped ?: return null
        return try {
            val got = engine.lines(img, mode, origin[0], origin[1], config.languages)
            val words = got.flatMap { it.words }
            if (words.isEmpty()) null
            else {
                val line = TextLine(words, Box.of(words.map { it.box })!!, source = config.languages)
                RecognitionCandidate(line.text, line.confidence, config.languages, config.script, listOf(line))
            }
        } finally {
            img.recycle()
        }
    }

    private fun pageCandidate(line: TextLine) = RecognitionCandidate(line.text, line.confidence, "page", null, listOf(line))

    /** The script a line's confident words show (null when they do not decide). */
    private fun confidentScript(line: TextLine): Script? {
        val sure = line.words.filter { (it.confidence ?: 0f) >= 0.8f }.joinToString(" ") { it.text }
        return ScriptDetector.classify(sure).takeIf { it == Script.ARABIC || it == Script.LATIN }
    }

    /** The line read again by the configurations of its script, the best reading kept. */
    private fun routeLine(page: Bitmap, line: TextLine): TextLine {
        val expected = confidentScript(line)
        val script = ScriptDetector.classify(line.text)
        val conf = line.confidence ?: 0f
        // A confident line already in the script its context shows, read Arabic-first when
        // Arabic, is kept as is (fewer recognitions on long documents).
        // Every word must be confident too: one weak word ("2890" for an Arabic word) is enough
        // to read the line again.
        val weakest = line.words.minOfOrNull { it.confidence ?: 0f } ?: 0f
        val settled = conf >= 0.88f && weakest >= 0.75f && expected != null && script == expected && !ScriptDetector.suspiciousLatinInArabic(line) &&
            (script != Script.ARABIC || engines.pageArabicFirst)
        if (settled) return line.copy(engines = listOf("page"))
        val configs = OcrRouting.plan(line.text, expected, OcrRouting.isNumeric(line.text))
        val candidates = listOf(pageCandidate(line)) + configs.mapNotNull { readRegion(it, page, line.box, Segmentation.LINE) }
        val e = CandidateEvaluator.evaluate(candidates, expected, OcrRouting.isNumeric(line.text))
        trace { "line expected=$expected candidates=${candidates.map { "${it.engine}:${it.confidence}:${it.text}" }} chosen=${e.chosen?.engine} review=${e.reasons}" }
        val chosen = e.chosen ?: return line.copy(needsReview = true, engines = candidates.map { it.engine })
        val base = chosen.lines.firstOrNull() ?: line
        return base.copy(needsReview = e.needsReview, engines = candidates.map { it.engine }, source = chosen.engine.takeIf { it != "page" })
    }

    /**
     * A table cell read on its own by the configurations its row expects, compared with what the
     * page reading found there. Bordered cells are read inside their borders; cells of tables
     * without borders get a margin around their words so no letter is cut.
     */
    private fun readCell(page: Bitmap, box: Box, words: List<TextWord>, bordered: Boolean, expected: Script?, column: List<String>): TableCell? {
        val pageText = words.joinToString(" ") { it.text }
        val pageConf = words.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val numeric = OcrRouting.isNumeric(pageText)
        val region = if (bordered) Box(box.left + 5, box.top + 5, box.right - 5, box.bottom - 5)
        else box.height.coerceAtLeast(8f).let { h -> Box(box.left - h * 0.6f, box.top - h * 0.4f, box.right + h * 0.6f, box.bottom + h * 0.4f) }
        val candidates = ArrayList<RecognitionCandidate>()
        if (pageText.isNotBlank()) candidates += RecognitionCandidate(pageText, pageConf, "page")
        for (config in OcrRouting.plan(pageText, expected, numeric)) {
            val engine = engines.get(config) ?: continue
            val (img, _) = crop(page, region.left, region.top, region.right, region.bottom) ?: continue
            try {
                val (t, c) = engine.text(img, Segmentation.BLOCK)
                if (t.isNotBlank()) candidates += RecognitionCandidate(t, c, config.languages, config.script)
            } finally {
                img.recycle()
            }
        }
        val e = CandidateEvaluator.evaluate(candidates, expected, numeric, column)
        trace { "cell expected=$expected column=$column candidates=${candidates.map { "${it.engine}:${it.confidence}:${it.text}" }} chosen=${e.chosen?.text} review=${e.reasons}" }
        val chosen = e.chosen ?: return null
        return TableCell(chosen.text, confidence = chosen.confidence, needsReview = e.needsReview)
    }

    /**
     * A row of the image no line covers, read by every configuration and checked. The crop keeps
     * only a few pixels above and below the row so that the neighbouring lines (list items set
     * close together) are not read with it. [blockLines] are the lines of a whole-page reading
     * in uniform-block mode, which does not drop short lines.
     */
    private fun recoverBand(page: Bitmap, g: IntArray, band: IntRange, existing: List<TextLine>, blockLines: List<TextLine>): TextLine? {
        // Horizontal extent of the ink in the band (not the whole page width).
        var minX = page.width
        var maxX = 0
        for (y in band) for (x in 0 until page.width) if (g[y * page.width + x] < 110) { if (x < minX) minX = x; if (x > maxX) maxX = x }
        if (maxX <= minX) return null
        val box = Box(minX.toFloat(), band.first.toFloat(), maxX.toFloat(), band.last.toFloat())
        val bandBox = Box(0f, band.first.toFloat(), page.width.toFloat(), band.last.toFloat())
        val fromBlock = blockLines.filter { it.box.verticalOverlap(bandBox) > 0.5f }.map {
            RecognitionCandidate(it.text, it.confidence, "page-block", null, listOf(it))
        }
        val candidates = fromBlock + listOf(OcrConfig.PAGE, OcrConfig.ARABIC, OcrConfig.LATIN).flatMap { c ->
            listOfNotNull(readRegion(c, page, box, Segmentation.LINE, vMargin = 4f), readRegion(c, page, box, Segmentation.BLOCK, vMargin = 4f))
        }
        val e = CandidateEvaluator.evaluate(candidates, expected = null)
        trace { "band ${band.first}-${band.last} candidates=${candidates.map { "${it.engine}:${it.confidence}:${it.text}" }} chosen=${e.chosen?.text} review=${e.reasons}" }
        val chosen = e.chosen ?: return null
        val line = chosen.lines.firstOrNull() ?: return null
        val plausible = CandidateEvaluator.plausibility(line.text) >= 0.6
        if (((line.confidence ?: 0f) < 0.3f && !plausible) || OcrDecisions.isDuplicate(line.text, existing)) return null
        // A recovered line is always shown for review.
        return line.copy(source = "band", needsReview = true, engines = candidates.map { it.engine }.distinct())
    }

    private fun mean(lines: List<TextLine>): Float? = lines.flatMap { it.words }.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    suspend fun read(original: Bitmap, index: Int, source: PageSource, imageUri: String?): ExtractedPage {
        val notes = ArrayList<String>()
        timings.clear()
        var t0 = SystemClock.elapsedRealtime()
        fun lap(stage: String) { val now = SystemClock.elapsedRealtime(); timings[stage] = now - t0; t0 = now }
        val pageEngine = engines.get(OcrConfig.PAGE) ?: throw IllegalStateException("OCR engine could not be initialized")
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
            // B. Layout pass.
            var lines = pageEngine.lines(page, Segmentation.PAGE)
            trace { "page lines=${lines.map { "${it.box.top.toInt()}-${it.box.bottom.toInt()}:${it.confidence}:${it.text}" }}" }
            if (OcrPreprocessor.isWeak(lines.joinToString(" ") { it.text }, mean(lines) ?: 0f)) {
                for (deg in listOf(90, 270, 180)) {
                    currentCoroutineContext().ensureActive()
                    val rotated = OcrPreprocessor.rotate(page, deg)
                    val r = pageEngine.lines(rotated, Segmentation.PAGE)
                    val better = OcrPreprocessor.score(r.joinToString(" ") { it.text }, mean(r) ?: 0f) >
                        OcrPreprocessor.score(lines.joinToString(" ") { it.text }, mean(lines) ?: 0f) * 1.2
                    if (better) { page.recycle(); page = rotated; lines = r; notes += "rotated:$deg"; break } else rotated.recycle()
                }
            }
            lap("layout")

            // C-E. Each line routed to the configuration of its script and checked.
            var rerouted = 0
            lines = lines.map { line ->
                currentCoroutineContext().ensureActive()
                routeLine(page, line).also { if (it.source != null) rerouted++ }
            }
            if (rerouted > 0) notes += "lines_rerouted:$rerouted"
            lap("lines")

            // Rows of the image no line covers (Tesseract's layout analysis can drop lines).
            val g = gray(page)
            val allBands = OcrPreprocessor.textLineBands(page)
            val missed = OcrDecisions.missedBands(allBands, lines.map { it.box })
            trace { "bands=${allBands.map { "${it.first}-${it.last}" }} missed=${missed.map { "${it.first}-${it.last}" }}" }
            // One reading of the whole page as a uniform block (short lines are not dropped), by
            // the Arabic model on Arabic pages, only when rows were missed.
            val blockLines = if (missed.isEmpty()) emptyList() else {
                val arabicPage = ScriptDetector.classify(lines.joinToString(" ") { it.text }) == Script.ARABIC
                val engine = (if (arabicPage) engines.get(OcrConfig.ARABIC) else null) ?: pageEngine
                engine.lines(page, Segmentation.BLOCK)
            }
            var recovered = 0
            for (band in missed) {
                currentCoroutineContext().ensureActive()
                val line = recoverBand(page, g, band, lines, blockLines) ?: continue
                lines = lines.toMutableList().apply { add(OcrDecisions.insertionIndex(this, line.box.top), line) }
                recovered++
            }
            notes += "ink_lines:${allBands.size}/read:${lines.size - recovered}/missed:${missed.size}"
            if (recovered > 0) notes += "lines_recovered:$recovered"
            if (missed.size > recovered) notes += "possible_missing_lines:${missed.size - recovered}"
            lap("recovery")

            // Structure, with table cells read in the script of their row.
            val rulings = TableReconstructor.detectRulings(g, page.width, page.height)
            val blocks = LayoutAnalyzer.analyze(lines, page.width.toFloat(), page.height.toFloat(), rulings,
                CellReader { box, ws, bordered, expected, column -> readCell(page, box, ws, bordered, expected, column) })
            lap("structure")
            if (blocks.any { it is ExtractedBlock.Table && it.uncertain }) notes += "table_uncertain"
            val words = lines.flatMap { it.words }
            Log.i(TAG, "Page ${index + 1}: ${lines.size} lines, ${words.size} words, ${blocks.size} blocks, notes=$notes, ms=$timings, loads=${engines.loadTimes}")
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
         * Receives the readings considered for each line, row and cell, for the device tests'
         * reports only. Null in the app: page text is never logged.
         */
        @Volatile var trace: ((String) -> Unit)? = null
    }
}
