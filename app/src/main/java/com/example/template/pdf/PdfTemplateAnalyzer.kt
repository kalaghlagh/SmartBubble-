package com.example.template.pdf

import com.example.template.domain.TemplateProfile
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Whether the template's text could be measured. */
enum class PdfTextStatus { TEXT, PARTIAL, NO_TEXT }

/** One visual line of text (runs on the same baseline). */
internal data class PdfTextLine(
    val page: Int,
    val x: Double,
    val right: Double,
    val y: Double,
    val size: Double,
    val family: String,
    val bold: Boolean,
    val colorHex: String,
    val text: String,
    val glyphs: Int
) {
    val width: Double get() = right - x
    val center: Double get() = (x + right) / 2
}

/** Everything measured on a PDF template. Values are null/empty when not found. */
data class PdfTemplateAnalysis(
    val pageCount: Int,
    val pageWidthPt: Double,
    val pageHeightPt: Double,
    val status: PdfTextStatus,
    /** Image-only (scanned) pages: images cover the page and there is no text. */
    val imageOnly: Boolean,
    val textDecoded: Boolean,
    val marginTop: Double,
    val marginBottom: Double,
    val marginLeft: Double,
    val marginRight: Double,
    val bodyFont: String?,
    val bodySize: Double?,
    val bodyColorHex: String?,
    val headingFont: String?,
    val headingSizes: List<Double>,
    val headingColorHex: String?,
    val headingBold: Boolean,
    val bodyAlignment: String,
    val headingAlignment: String,
    val lineSpacing: Double?,
    val paragraphSpacing: Double?,
    val headerText: String,
    val footerText: String,
    val headerSize: Double?,
    val footerSize: Double?,
    val headerAlignment: String,
    val footerAlignment: String,
    val headerOffset: Double?,
    val footerOffset: Double?,
    val pageNumberPosition: String,
    val listMarker: String,
    val listIndent: Double?,
    val tableDetected: Boolean,
    val tableHeaderFillHex: String?,
    val tableBorderHex: String?,
    val tableCellAlignment: String,
    val pageBackgroundHex: String?,
    /** Colored shapes (fractions of the page, top-left origin). */
    val decorations: List<Decoration>,
    val rtl: Boolean
) {
    data class Decoration(val left: Double, val top: Double, val width: Double, val height: Double, val colorHex: String)

    val landscape: Boolean get() = pageWidthPt > pageHeightPt

    /** The measured values as a template profile for the document engines. */
    fun toProfile(templateId: String, name: String, sourceFileName: String): TemplateProfile {
        val base = TemplateProfile.defaultProfile()
        val heading1 = headingSizes.getOrNull(0)
        val heading2 = headingSizes.getOrNull(1) ?: heading1
        val primary = listOfNotNull(
            headingColorHex?.takeIf { PdfColors.isColorful(it) },
            decorations.firstOrNull()?.colorHex,
            tableHeaderFillHex?.takeIf { PdfColors.isColorful(it) },
            headingColorHex
        ).firstOrNull() ?: "#1F3A5F"
        fun pt(v: Double?) = v?.roundToInt()
        return base.copy(
            templateId = templateId,
            name = name,
            originalFileType = "PDF",
            supportedOutputTypes = listOfNotNull("DOCX", "PDF", if (tableDetected) "XLSX" else null),
            primaryColorHex = primary,
            secondaryColorHex = tableHeaderFillHex ?: decorations.getOrNull(1)?.colorHex ?: primary,
            accentColorHex = headingColorHex ?: base.accentColorHex,
            backgroundColorHex = pageBackgroundHex ?: "#FFFFFF",
            textColorHex = bodyColorHex ?: "#000000",
            headingFont = headingFont ?: bodyFont ?: "Helvetica",
            bodyFont = bodyFont ?: "Helvetica",
            baseFontSizePt = pt(bodySize) ?: 11,
            heading1SizePt = pt(heading1) ?: ((bodySize ?: 11.0) * 1.6).roundToInt(),
            heading2SizePt = pt(heading2) ?: ((bodySize ?: 11.0) * 1.3).roundToInt(),
            marginTopPt = marginTop.roundToInt(),
            marginBottomPt = marginBottom.roundToInt(),
            marginLeftPt = marginLeft.roundToInt(),
            marginRightPt = marginRight.roundToInt(),
            // Only text the template itself carries in its header/footer; never invented.
            headerText = headerText,
            footerText = footerText,
            showPageNumbers = pageNumberPosition.isNotEmpty(),
            tableHeaderBgHex = tableHeaderFillHex ?: primary,
            tableAlternateRowBgHex = "#FFFFFF",
            tableBorderHex = tableBorderHex ?: "#A0A0A0",
            spreadsheetDefaultFont = bodyFont ?: "Calibri",
            isAnalysisComplete = status == PdfTextStatus.TEXT,
            extractedPropertiesSummary = summary(),
            useTemplateLayout = false,
            keepTemplateContent = false,
            layoutSource = "PDF",
            pageWidthPt = pageWidthPt.roundToInt(),
            pageHeightPt = pageHeightPt.roundToInt(),
            headingColorHex = headingColorHex ?: "",
            headingBold = headingBold,
            bodyAlignment = bodyAlignment,
            headingAlignment = headingAlignment,
            lineSpacing = lineSpacing?.let { (it * 100).roundToInt() / 100f } ?: 0f,
            paragraphSpacingPt = pt(paragraphSpacing) ?: -1,
            headerFontSizePt = pt(headerSize) ?: 0,
            footerFontSizePt = pt(footerSize) ?: 0,
            headerAlignment = headerAlignment,
            footerAlignment = footerAlignment,
            headerOffsetPt = pt(headerOffset) ?: 0,
            footerOffsetPt = pt(footerOffset) ?: 0,
            pageNumberPosition = pageNumberPosition,
            tableDetected = tableDetected,
            tableCellAlignment = tableCellAlignment,
            listMarker = listMarker,
            listIndentPt = pt(listIndent) ?: 0,
            decorations = decorations.joinToString(";") {
                String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%s", it.left, it.top, it.width, it.height, it.colorHex)
            },
            pdfTextStatus = status.name,
            pdfPageCount = pageCount,
            rtl = rtl,
            sourceFileName = sourceFileName
        )
    }

    /** Short English summary stored in the profile (the UI shows localized details). */
    fun summary(): String = buildString {
        append("PDF ${pageWidthPt.roundToInt()}×${pageHeightPt.roundToInt()} pt")
        if (status == PdfTextStatus.NO_TEXT) { append(", no extractable text"); return@buildString }
        bodyFont?.let { append(", body $it ${bodySize?.roundToInt()} pt") }
        headingSizes.firstOrNull()?.let { append(", headings ${it.roundToInt()} pt") }
        if (headerText.isNotEmpty() || footerText.isNotEmpty() || pageNumberPosition.isNotEmpty()) append(", header/footer")
        if (tableDetected) append(", table")
    }
}

internal object PdfColors {
    fun rgb(hex: String): IntArray? {
        val h = hex.removePrefix("#")
        if (h.length != 6) return null
        val v = h.toIntOrNull(16) ?: return null
        return intArrayOf((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }

    /** Not white, black or gray. */
    fun isColorful(hex: String): Boolean {
        val c = rgb(hex) ?: return false
        return c.max() - c.min() > 40
    }

    fun isNearWhite(hex: String): Boolean = rgb(hex)?.all { it > 240 } ?: false
}

/**
 * Measures the visual style of a PDF template on the device: page size, margins, header and
 * footer, fonts, sizes, colors, alignment, spacing, lists, tables, background and colored
 * shapes. Only the first [PdfLimits.MAX_PAGES_ANALYZED] pages are read. The original file is
 * only read. Values that cannot be measured are left empty rather than guessed.
 */
object PdfTemplateAnalyzer {

    private val PAGE_NUMBER = Regex("""^(page|p\.|seite|صفحة|الصفحة)?\s*[\d٠-٩]+(\s*(/|of|sur|de|من)\s*[\d٠-٩]+)?$""", RegexOption.IGNORE_CASE)
    private val BULLETS = setOf('•', '▪', '◦', '●', '■', '–', '-', '*', '·', '➢', '✓')
    private val NUMBERED = Regex("""^([\d٠-٩]{1,3}|[a-zA-Z])[.)]\s""")

    fun analyze(file: File): PdfTemplateAnalysis = analyze(PdfDocumentReader.open(file))

    fun analyze(reader: PdfDocumentReader): PdfTemplateAnalysis {
        if (reader.encrypted) throw PdfEncryptedException()
        val pages = reader.pages(PdfLimits.MAX_PAGES_ANALYZED)
        if (pages.isEmpty()) throw PdfReadException("The PDF has no readable pages")
        val pageCount = reader.pageCount().coerceAtLeast(pages.size)
        val scanner = PdfContentScanner(reader)
        val scans = pages.map { scanner.scan(it) }
        return measure(scans, pageCount)
    }

    internal fun measure(scans: List<PdfPageScan>, pageCount: Int): PdfTemplateAnalysis {
        val first = scans.first()
        val w = first.width
        val h = first.height
        val incomplete = scans.any { it.incomplete }

        val lines = scans.flatMapIndexed { index, scan -> linesOf(index, scan) }
        val totalGlyphs = lines.sumOf { it.glyphs }
        val imageArea = scans.sumOf { s -> s.images.sumOf { it.area.coerceAtMost(s.width * s.height) } / (s.width * s.height) } / scans.size
        if (totalGlyphs == 0) {
            return empty(pageCount, w, h, imageOnly = imageArea > 0.3, scans)
        }
        val decoded = lines.any { l -> l.text.any { it.isLetter() } && !l.text.contains('�') }
        val rtl = lines.sumOf { l -> l.text.count { it in '؀'..'ۿ' } } > lines.sumOf { l -> l.text.count { it in 'A'..'z' } }

        // Body text: the size that carries most glyphs.
        val bodySize = weightedMode(lines.map { (it.size * 2).roundToInt() / 2.0 to it.glyphs })!!
        val bodyLines = lines.filter { abs(it.size - bodySize) < 0.6 }
        val bodyFont = weightedMode(bodyLines.map { it.family to it.glyphs })
        val bodyBold = bodyLines.sumOf { if (it.bold) it.glyphs else 0 } * 2 > bodyLines.sumOf { it.glyphs }
        val bodyColor = weightedMode(bodyLines.map { it.colorHex to it.glyphs })

        // Header / footer zones and repeated lines.
        val zone = 0.12
        fun inHeader(l: PdfTextLine) = l.y > h * (1 - zone)
        fun inFooter(l: PdfTextLine) = l.y < h * zone
        fun key(l: PdfTextLine) = l.text.trim().replace(Regex("[\\d٠-٩]+"), "#")
        val multiPage = scans.size > 1
        fun chrome(candidates: List<PdfTextLine>, others: List<PdfTextLine>): List<PdfTextLine> =
            candidates.filter { l ->
                if (l.size > bodySize * 1.15) return@filter false
                if (multiPage) {
                    candidates.any { o -> o.page != l.page && key(o) == key(l) } || PAGE_NUMBER.matches(l.text.trim())
                } else {
                    val nearest = others.filter { it !== l }.minOfOrNull { abs(it.y - l.y) } ?: Double.MAX_VALUE
                    nearest > bodySize * 1.6 || PAGE_NUMBER.matches(l.text.trim())
                }
            }
        val headerLines = chrome(lines.filter(::inHeader), lines.filterNot(::inHeader))
        val footerLines = chrome(lines.filter(::inFooter), lines.filterNot(::inFooter))
        val chromeSet = (headerLines + footerLines).toSet()
        val content = lines.filter { it !in chromeSet }.ifEmpty { lines }

        fun alignmentOnPage(l: PdfTextLine): String {
            val c = l.center / w
            return when {
                c < 0.38 -> if (rtl) "end" else "start"
                c > 0.62 -> if (rtl) "start" else "end"
                else -> "center"
            }
        }
        fun chromeText(ls: List<PdfTextLine>): String =
            if (!decoded) "" else ls.filter { it.page == 0 && !PAGE_NUMBER.matches(it.text.trim()) }
                .sortedByDescending { it.y }.joinToString("  ") { it.text.trim() }.take(200)
        val pageNumberLine = (footerLines + headerLines).firstOrNull { PAGE_NUMBER.matches(it.text.trim()) }
        val pageNumberPosition = pageNumberLine?.let { (if (it in headerLines) "header-" else "footer-") + alignmentOnPage(it) } ?: ""
        val header0 = headerLines.filter { it.page == 0 }
        val footer0 = footerLines.filter { it.page == 0 }

        // Margins: the box around the content (tables included).
        val contentFirstPage = content.filter { it.page == 0 }.ifEmpty { content }
        val strokes = (first.lines + first.rects.filter { it.h < 1.6 || it.w < 1.6 }.map { PdfLine(it.x, it.y, it.x + it.w, it.y + it.h, it.colorHex, minOf(it.w, it.h)) })
            .filter { it.y1 > h * zone && it.y1 < h * (1 - zone) }
        val left = (contentFirstPage.map { it.x } + strokes.map { minOf(it.x1, it.x2) }).minOrNull() ?: 72.0
        val right = (contentFirstPage.map { it.right } + strokes.map { maxOf(it.x1, it.x2) }).maxOrNull() ?: (w - 72.0)
        val topY = contentFirstPage.maxOf { it.y + it.size * 0.8 }
        val bottomY = contentFirstPage.minOf { it.y - it.size * 0.25 }
        val marginLeft = left.coerceIn(0.0, w / 3)
        val marginRight = (w - right).coerceIn(0.0, w / 3)
        val marginTop = (h - topY).coerceIn(0.0, h / 3)
        val marginBottom = bottomY.coerceIn(0.0, h / 3)

        // Headings: clearly larger, or bold where body text is not.
        val headingLines = content.filter { l ->
            l.glyphs <= 120 && (l.size >= bodySize * 1.12 || (l.bold && !bodyBold && l.glyphs <= 80))
        }
        val headingSizes = headingLines.map { (it.size * 2).roundToInt() / 2.0 }.distinct().sortedDescending()
            .fold(mutableListOf<Double>()) { acc, s -> if (acc.none { abs(it - s) < 0.75 }) acc.add(s); acc }.take(3)
        val headingFont = weightedMode(headingLines.map { it.family to it.glyphs })
        val headingColor = weightedMode(headingLines.map { it.colorHex to it.glyphs })
        val headingBold = headingLines.isEmpty() || headingLines.count { it.bold } * 2 >= headingLines.size

        val textWidth = (right - left).coerceAtLeast(1.0)
        val bodyAlignment = alignment(bodyLines.filter { it !in chromeSet && it.glyphs >= 15 }, left, right, textWidth, rtl)
        val headingAlignment = headingLines.takeIf { it.isNotEmpty() }?.let { hs ->
            val centered = hs.count { abs(it.center - (left + right) / 2) < textWidth * 0.04 && it.width < textWidth * 0.9 }
            when {
                centered * 2 > hs.size -> "center"
                hs.count { abs(it.right - right) < 3 } * 2 > hs.size && hs.count { abs(it.x - left) < 3 } * 2 <= hs.size -> if (rtl) "start" else "end"
                else -> if (rtl && hs.count { abs(it.x - left) < 3 } * 2 > hs.size) "end" else "start"
            }
        } ?: "start"

        // Line pitch and paragraph gaps between consecutive body lines.
        val gaps = ArrayList<Double>()
        for (page in content.groupBy { it.page }.values) {
            val sorted = page.filter { abs(it.size - bodySize) < 0.6 }.sortedByDescending { it.y }
            for (k in 1 until sorted.size) gaps.add(sorted[k - 1].y - sorted[k].y)
        }
        val pitch = median(gaps.filter { it in bodySize * 0.95..bodySize * 2.2 })
        val paragraphGap = pitch?.let { p -> median(gaps.filter { it > p * 1.35 && it < p * 4 })?.minus(p) }

        // Lists.
        val listLines = content.filter { l ->
            val t = l.text.trimStart()
            t.isNotEmpty() && ((t[0] in BULLETS && t.length > 1 && t[1] == ' ') || NUMBERED.containsMatchIn(t) || (t[0] in BULLETS && l.glyphs > 2))
        }
        val listMarker = weightedMode(listLines.map { l ->
            val t = l.text.trimStart()
            (if (t[0] in BULLETS) t[0].toString() else if (t[0].isLetter()) "a." else "1.") to 1
        }) ?: ""
        val listIndent = median(listLines.map { it.x - left }.filter { it >= 0 })

        // Tables.
        val table = tableOf(first, content.filter { it.page == 0 }, h, zone)

        // Background and colored shapes.
        val pageArea = w * h
        val background = first.rects.firstOrNull { it.area >= pageArea * 0.9 && !PdfColors.isNearWhite(it.colorHex) }?.colorHex
        val decorations = first.rects
            .filter { r ->
                r.area >= pageArea * 0.004 && r.area < pageArea * 0.6 && !PdfColors.isNearWhite(r.colorHex) &&
                    r.colorHex != background && (table == null || !table.contains(r))
            }
            .sortedByDescending { it.area }.take(6)
            .map { r -> PdfTemplateAnalysis.Decoration(r.x / w, (h - r.y - r.h) / h, r.w / w, r.h / h, r.colorHex) } +
            first.lines.filter { it.horizontal && it.length >= w * 0.4 && (it.y1 > h * (1 - zone) || it.y1 < h * zone) }
                .take(2).map { l ->
                    PdfTemplateAnalysis.Decoration(minOf(l.x1, l.x2) / w, (h - l.y1 - l.width / 2) / h, l.length / w, l.width.coerceAtLeast(0.5) / h, l.colorHex)
                }

        return PdfTemplateAnalysis(
            pageCount = pageCount,
            pageWidthPt = w,
            pageHeightPt = h,
            status = if (incomplete) PdfTextStatus.PARTIAL else PdfTextStatus.TEXT,
            imageOnly = false,
            textDecoded = decoded,
            marginTop = marginTop,
            marginBottom = marginBottom,
            marginLeft = marginLeft,
            marginRight = marginRight,
            bodyFont = bodyFont,
            bodySize = bodySize,
            bodyColorHex = bodyColor,
            headingFont = headingFont,
            headingSizes = headingSizes,
            headingColorHex = headingColor,
            headingBold = headingBold,
            bodyAlignment = bodyAlignment,
            headingAlignment = headingAlignment,
            lineSpacing = pitch?.let { it / bodySize },
            paragraphSpacing = paragraphGap?.coerceAtLeast(0.0),
            headerText = chromeText(header0),
            footerText = chromeText(footer0),
            headerSize = header0.maxOfOrNull { it.size },
            footerSize = footer0.maxOfOrNull { it.size },
            headerAlignment = header0.firstOrNull { !PAGE_NUMBER.matches(it.text.trim()) }?.let(::alignmentOnPage) ?: "start",
            footerAlignment = footer0.firstOrNull { !PAGE_NUMBER.matches(it.text.trim()) }?.let(::alignmentOnPage) ?: "start",
            headerOffset = header0.maxOfOrNull { it.y }?.let { h - it },
            footerOffset = footer0.minOfOrNull { it.y },
            pageNumberPosition = pageNumberPosition,
            listMarker = listMarker,
            listIndent = listIndent,
            tableDetected = table != null,
            tableHeaderFillHex = table?.headerFill,
            tableBorderHex = table?.borderColor,
            tableCellAlignment = table?.cellAlignment ?: "start",
            pageBackgroundHex = background,
            decorations = decorations,
            rtl = rtl
        )
    }

    private fun empty(pageCount: Int, w: Double, h: Double, imageOnly: Boolean, scans: List<PdfPageScan>) = PdfTemplateAnalysis(
        pageCount = pageCount, pageWidthPt = w, pageHeightPt = h, status = PdfTextStatus.NO_TEXT, imageOnly = imageOnly,
        textDecoded = false, marginTop = 0.0, marginBottom = 0.0, marginLeft = 0.0, marginRight = 0.0,
        bodyFont = null, bodySize = null, bodyColorHex = null, headingFont = null, headingSizes = emptyList(),
        headingColorHex = null, headingBold = true, bodyAlignment = "start", headingAlignment = "start",
        lineSpacing = null, paragraphSpacing = null, headerText = "", footerText = "", headerSize = null, footerSize = null,
        headerAlignment = "start", footerAlignment = "start", headerOffset = null, footerOffset = null,
        pageNumberPosition = "", listMarker = "", listIndent = null, tableDetected = false, tableHeaderFillHex = null,
        tableBorderHex = null, tableCellAlignment = "start",
        pageBackgroundHex = scans.first().rects.firstOrNull { it.area >= w * h * 0.9 && !PdfColors.isNearWhite(it.colorHex) }?.colorHex,
        decorations = emptyList(), rtl = false
    )

    /** Groups text runs into visual lines. */
    internal fun linesOf(page: Int, scan: PdfPageScan): List<PdfTextLine> {
        val runs = scan.texts.filter { it.glyphs > 0 && !(it.text.isNotEmpty() && it.text.isBlank()) && it.fontSize > 0.5 }
            .filter { it.y > -1 && it.y < scan.height + 1 && it.x < scan.width + 1 && it.right > -1 }
            .sortedWith(compareByDescending<PdfTextRun> { it.y }.thenBy { it.x })
        val out = ArrayList<PdfTextLine>()
        var group = ArrayList<PdfTextRun>()
        fun flush() {
            if (group.isEmpty()) return
            val sorted = group.sortedBy { it.x }
            val sb = StringBuilder()
            var lastRight = Double.NaN
            for (r in sorted) {
                if (!lastRight.isNaN() && r.x - lastRight > r.fontSize * 0.2 && sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(' ')
                sb.append(r.text)
                lastRight = r.right
            }
            val glyphs = sorted.sumOf { it.glyphs }
            out.add(
                PdfTextLine(
                    page = page,
                    x = sorted.minOf { it.x },
                    right = sorted.maxOf { it.right },
                    y = sorted.maxOf { it.y },
                    size = sorted.sumOf { it.fontSize * it.glyphs } / glyphs,
                    family = weightedMode(sorted.map { it.fontFamily to it.glyphs })!!,
                    bold = sorted.sumOf { if (it.bold) it.glyphs else 0 } * 2 > glyphs,
                    colorHex = weightedMode(sorted.map { it.colorHex to it.glyphs })!!,
                    text = sb.toString(),
                    glyphs = glyphs
                )
            )
            group = ArrayList()
        }
        for (r in runs) {
            val anchor = group.firstOrNull()
            if (anchor != null && abs(anchor.y - r.y) > maxOf(anchor.fontSize, r.fontSize) * 0.35) flush()
            group.add(r)
        }
        flush()
        return out
    }

    private fun alignment(lines: List<PdfTextLine>, left: Double, right: Double, width: Double, rtl: Boolean): String {
        if (lines.size < 3) return "start"
        val atLeft = lines.count { abs(it.x - left) < 2.5 }
        val atRight = lines.count { abs(it.right - right) < 2.5 }
        val both = lines.count { abs(it.x - left) < 2.5 && abs(it.right - right) < 2.5 }
        val centered = lines.count { abs(it.center - (left + right) / 2) < width * 0.02 }
        val n = lines.size.toDouble()
        return when {
            both / n >= 0.5 && atLeft / n >= 0.7 && atRight / n >= 0.7 && both < lines.size -> "justify"
            both / n >= 0.5 && rtl && atRight / n >= 0.8 -> "start"
            centered / n >= 0.7 && atLeft / n < 0.5 -> "center"
            atRight / n >= 0.7 && atLeft / n < 0.5 -> if (rtl) "start" else "end"
            atLeft / n >= 0.7 && atRight / n < 0.5 -> if (rtl) "end" else "start"
            both / n >= 0.5 -> "justify"
            else -> "start"
        }
    }

    private class TableInfo(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val headerFill: String?, val borderColor: String?, val cellAlignment: String) {
        fun contains(r: PdfRect) = r.x >= x1 - 2 && r.x + r.w <= x2 + 2 && r.y >= y1 - 2 && r.y + r.h <= y2 + 2
    }

    /** A grid of ruled lines (or of filled cells) holding text. */
    private fun tableOf(scan: PdfPageScan, lines: List<PdfTextLine>, h: Double, zone: Double): TableInfo? {
        val segs = scan.lines + scan.rects.filter { it.h < 1.6 || it.w < 1.6 }.map { PdfLine(it.x, it.y, it.x + it.w, it.y + it.h, it.colorHex, minOf(it.w, it.h)) }
        val horizontals = segs.filter { it.horizontal && it.length >= 40 && it.y1 > h * zone && it.y1 < h * (1 - zone) }
        val groups = horizontals.groupBy { (minOf(it.x1, it.x2) / 3).roundToInt() to (maxOf(it.x1, it.x2) / 3).roundToInt() }
        val grid = groups.values.filter { g -> g.map { (it.y1 * 2).roundToInt() }.distinct().size >= 3 }.maxByOrNull { it.size }
        var info: TableInfo? = null
        if (grid != null) {
            val x1 = grid.minOf { minOf(it.x1, it.x2) }
            val x2 = grid.maxOf { maxOf(it.x1, it.x2) }
            val y1 = grid.minOf { it.y1 }
            val y2 = grid.maxOf { it.y1 }
            val verticals = segs.filter { it.vertical && it.length > 5 && minOf(it.x1, it.x2) >= x1 - 3 && maxOf(it.x1, it.x2) <= x2 + 3 && maxOf(it.y1, it.y2) >= y1 - 3 && minOf(it.y1, it.y2) <= y2 + 3 }
            val textInside = lines.count { it.y in y1..y2 && it.x >= x1 - 2 && it.right <= x2 + 2 }
            if (textInside >= 2 && (verticals.map { (it.x1 / 3).roundToInt() }.distinct().size >= 2 || grid.size >= 4)) {
                info = TableInfo(x1, y1, x2, y2, null, weightedMode(grid.map { it.colorHex to 1 }), "start")
            }
        }
        if (info == null) {
            // Filled cells in rows and columns (tables without ruled borders).
            val cells = scan.rects.filter { it.h in 8.0..60.0 && it.w >= 20 && it.area < scan.width * scan.height * 0.3 }
            val rows = cells.groupBy { (it.y / 2).roundToInt() }.filter { it.value.size >= 2 }
            if (rows.size >= 2) {
                val all = rows.values.flatten()
                info = TableInfo(all.minOf { it.x }, all.minOf { it.y }, all.maxOf { it.x + it.w }, all.maxOf { it.y + it.h }, null, null, "start")
            } else return null
        }
        // Header fill: a colored rectangle on the top row.
        val t = info
        val headerFill = scan.rects.filter { r -> r.y + r.h >= t.y2 - 30 && r.y <= t.y2 + 2 && r.x >= t.x1 - 3 && r.x + r.w <= t.x2 + 3 && !PdfColors.isNearWhite(r.colorHex) && r.h >= 6 }
            .maxByOrNull { it.area }?.colorHex
        val inside = lines.filter { it.y in t.y1..t.y2 && it.x >= t.x1 - 2 && it.right <= t.x2 + 2 }
        val verticalXs = (listOf(t.x1, t.x2) + segs.filter { it.vertical && minOf(it.x1, it.x2) > t.x1 + 3 && maxOf(it.x1, it.x2) < t.x2 - 3 }.map { it.x1 }).distinct().sorted()
        val alignment = if (verticalXs.size >= 3 && inside.isNotEmpty()) {
            val votes = inside.mapNotNull { l ->
                val k = verticalXs.indexOfFirst { it > l.center }
                if (k <= 0) null else {
                    val cellL = verticalXs[k - 1]; val cellR = verticalXs[k]
                    when {
                        abs(l.center - (cellL + cellR) / 2) < (cellR - cellL) * 0.08 && l.x - cellL > 6 -> "center"
                        cellR - l.right < 8 && l.x - cellL > 12 -> "end"
                        else -> "start"
                    }
                }
            }
            weightedMode(votes.map { it to 1 }) ?: "start"
        } else "start"
        return TableInfo(t.x1, t.y1, t.x2, t.y2, headerFill, t.borderColor, alignment)
    }

    private fun <T> weightedMode(items: List<Pair<T, Int>>): T? =
        items.groupBy({ it.first }, { it.second }).maxByOrNull { it.value.sum() }?.key

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
