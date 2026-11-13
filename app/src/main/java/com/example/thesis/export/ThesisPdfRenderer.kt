package com.example.thesis.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.TextPaint
import com.example.document.pdf.PdfPagination
import com.example.document.pdf.PdfProfileLayout
import com.example.template.domain.TemplateProfile
import com.example.thesis.core.InlineFormat
import com.example.thesis.core.ThesisLabels
import com.example.thesis.core.ThesisNumbering
import com.example.thesis.core.ThesisStructure
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisAsset
import com.example.thesis.model.ThesisElement
import com.example.thesis.model.ThesisSnapshot
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.CoroutineContext

/**
 * Lays out and draws a thesis as PDF with the template's page size, margins, fonts, colors and
 * spacing. Pages are computed from the structured content (nothing is stored per page). Two
 * passes are made: the first measures on which page every heading, figure and table falls, the
 * second draws the document with a table of contents and lists of figures/tables that show
 * those real page numbers. Original images are decoded at a reduced size for drawing only.
 */
class ThesisPdfRenderer(private val profile: TemplateProfile, private val coroutineContext: CoroutineContext? = null) {

    private val layout = PdfProfileLayout.from(profile)

    private fun color(hex: String, fallback: Int = Color.BLACK): Int = try {
        Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
    } catch (e: Exception) {
        fallback
    }

    private fun paint(size: Float, font: String, bold: Boolean, colorHex: String, italic: Boolean = false) = TextPaint().apply {
        isAntiAlias = true
        textSize = size
        val style = when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
        typeface = Typeface.create(Typeface.create(PdfProfileLayout.genericFamily(font), Typeface.NORMAL), style)
        color = color(colorHex)
    }

    /** Draws **bold**, *italic* and __underline__ runs of the section editor without the markers. */
    private fun styled(text: String): CharSequence {
        if (!InlineFormat.hasMarkers(text)) return text
        val sb = SpannableStringBuilder()
        for (r in InlineFormat.parse(text)) {
            val start = sb.length
            sb.append(r.text)
            val style = when { r.bold && r.italic -> Typeface.BOLD_ITALIC; r.bold -> Typeface.BOLD; r.italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
            if (style != Typeface.NORMAL) sb.setSpan(StyleSpan(style), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.underline) sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private fun staticLayout(text: CharSequence, paint: TextPaint, width: Int, align: String): StaticLayout {
        val natural = (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(1f)
        val multiplier = (layout.lineSpacing * paint.textSize / natural).coerceIn(0.8f, 3f)
        val b = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(when (align) { "center" -> Layout.Alignment.ALIGN_CENTER; "end" -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL })
            .setLineSpacing(0f, multiplier)
            .setIncludePad(false)
        if (align == "justify" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build()
    }

    /** Pages of headings, figures and tables found by the measuring pass. */
    private class Positions(val headings: MutableList<Pair<String, Int>> = ArrayList(), val figures: MutableList<Pair<String, Int>> = ArrayList(), val tables: MutableList<Pair<String, Int>> = ArrayList())

    /** @return number of pages */
    fun render(snapshot: ThesisSnapshot, assets: List<ThesisAsset>, output: File): Int {
        val numbering = ThesisStructure.numbering(snapshot)
        val measured = Positions()
        pass(snapshot, assets.associateBy { it.id }, numbering, null, measured, null)
        val doc = PdfDocument()
        try {
            val pages = pass(snapshot, assets.associateBy { it.id }, numbering, doc, Positions(), measured)
            FileOutputStream(output).use { doc.writeTo(it) }
            return pages
        } finally {
            doc.close()
        }
    }

    private fun pass(
        snapshot: ThesisSnapshot,
        assets: Map<String, ThesisAsset>,
        numbering: ThesisNumbering,
        doc: PdfDocument?,
        record: Positions,
        known: Positions?
    ): Int {
        val lang = snapshot.meta.language
        val rtl = snapshot.meta.rtl
        val body = paint(layout.bodySize, layout.bodyFont, false, layout.textColor)
        val title = paint(layout.titleSize + 6, layout.headingFont, true, layout.headingColor)
        val headings = (0..3).map { paint((layout.titleSize - it * 2.5f).coerceAtLeast(layout.bodySize + 1), layout.headingFont, layout.headingBold, layout.headingColor) }
        val caption = paint(layout.bodySize - 1, layout.bodyFont, false, layout.textColor, italic = true)
        val cell = paint(layout.bodySize * 0.92f, layout.bodyFont, false, layout.textColor)
        val cellBold = paint(layout.bodySize * 0.92f, layout.bodyFont, true, layout.textColor)
        val footer = paint(9f, layout.bodyFont, false, layout.textColor)
        val fill = Paint().apply { style = Paint.Style.FILL }
        val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.75f; color = color(layout.tableBorder, Color.GRAY) }
        val width = layout.contentWidth.toInt()
        val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        var coverPage = false

        fun finish() {
            val c = canvas
            if (c != null && !coverPage) {
                val n = pageNo.toString()
                val w = footer.measureText(n)
                c.drawText(n, (layout.pageWidth - w) / 2, layout.pageHeight - layout.footerBaseline, footer)
            }
            page?.let { doc?.finishPage(it) }
        }

        fun newPage(cover: Boolean = false) {
            coroutineContext?.ensureActive()
            finish()
            pageNo++
            coverPage = cover
            if (doc != null) {
                page = doc.startPage(PdfDocument.PageInfo.Builder(layout.pageWidth, layout.pageHeight, pageNo).create())
                canvas = page!!.canvas
                layout.backgroundColor?.let { bg -> fill.color = color(bg, Color.WHITE); canvas!!.drawRect(0f, 0f, layout.pageWidth.toFloat(), layout.pageHeight.toFloat(), fill) }
                if (layout.headerText.isNotBlank()) {
                    val w = footer.measureText(layout.headerText)
                    canvas!!.drawText(layout.headerText, PdfProfileLayout.xFor(layout.headerAlignment, w, layout), layout.headerBaseline, footer)
                }
            }
            y = layout.marginTop
        }

        fun block(sl: StaticLayout, x: Float, after: Float) {
            val fresh = layout.contentBottom - layout.marginTop
            if (PdfPagination.shouldMoveWholeBlockToNextPage(sl.height.toFloat(), layout.contentBottom - y, fresh)) newPage()
            val count = sl.lineCount
            if (count > 0) {
                val bounds = IntArray(count + 1) { i -> if (i < count) sl.getLineTop(i) else sl.getLineBottom(count - 1) }
                for (chunk in PdfPagination.splitLines(bounds, layout.contentBottom - y, fresh)) {
                    if (chunk.startsNewPage) newPage()
                    val top = bounds[chunk.startLine]
                    val bottom = bounds[chunk.endLine]
                    canvas?.let { c ->
                        c.save(); c.translate(x, y - top); c.clipRect(0f, top.toFloat(), sl.width.toFloat(), bottom.toFloat()); sl.draw(c); c.restore()
                    }
                    y += bottom - top
                }
            }
            y += after
        }

        fun text(t: String, p: TextPaint, align: String = layout.bodyAlignment, after: Float = layout.paragraphSpacing, indent: Float = 0f) =
            block(staticLayout(styled(t), p, (width - indent).toInt(), align), if (rtl) layout.marginLeft else layout.marginLeft + indent, after)

        fun pageOf(list: List<Pair<String, Int>>?, index: Int): String = list?.getOrNull(index)?.second?.toString() ?: "000"

        fun indexLines(lines: List<String>, pages: List<Pair<String, Int>>?) {
            lines.forEachIndexed { i, line ->
                val n = pageOf(pages, i)
                val sl = staticLayout(line, body, width - 40, "start")
                if (y + sl.height > layout.contentBottom) newPage()
                val top = y
                block(sl, if (rtl) layout.marginLeft + 40 else layout.marginLeft, 4f)
                canvas?.let { c ->
                    val w = body.measureText(n)
                    val x = if (rtl) layout.marginLeft else layout.marginLeft + width - w
                    c.drawText(n, x, top + sl.getLineBaseline(sl.lineCount - 1), body)
                }
            }
        }

        fun image(e: ThesisElement) {
            val asset = e.data.assetId?.let { assets[it] }
            val w = width * e.data.widthPercent.coerceIn(10, 100) / 100f
            val ratio = if (asset != null && asset.width > 0) asset.height.toFloat() / asset.width else 0.75f
            val h = (w * ratio).coerceAtMost(layout.contentBottom - layout.marginTop - 40f)
            val drawW = if (w * ratio > h) h / ratio else w
            if (y + h > layout.contentBottom) newPage()
            val x = when (e.data.align) {
                "start" -> if (rtl) layout.marginLeft + width - drawW else layout.marginLeft
                "end" -> if (rtl) layout.marginLeft else layout.marginLeft + width - drawW
                else -> layout.marginLeft + (width - drawW) / 2
            }
            canvas?.let { c ->
                val file = asset?.path?.let(::File)
                val bitmap = file?.takeIf { it.isFile }?.let { decodeScaled(it, (drawW * 2).toInt()) }
                if (bitmap != null) {
                    c.drawBitmap(bitmap, null, RectF(x, y, x + drawW, y + h), bitmapPaint)
                    bitmap.recycle()
                } else {
                    c.drawRect(x, y, x + drawW, y + h, stroke)
                }
            }
            y += h + 6f
        }

        fun table(e: ThesisElement) {
            val header = e.data.tableHeader
            if (header.isEmpty()) return
            val cols = header.size
            val colW = layout.contentWidth / cols
            val pad = 4f
            fun cells(values: List<String>, p: TextPaint) = (0 until cols).map { staticLayout(values.getOrElse(it) { "" }, p, (colW - 2 * pad).toInt(), "start") }
            fun row(ls: List<StaticLayout>, isHeader: Boolean) {
                val h = ls.maxOf { it.height } + 2 * pad
                if (y + h > layout.contentBottom) newPage()
                val order = if (rtl) ls.indices.reversed().toList() else ls.indices.toList()
                order.forEachIndexed { slot, i ->
                    val x = layout.marginLeft + slot * colW
                    canvas?.let { c ->
                        if (isHeader) { fill.color = color(layout.tableHeaderFill, Color.LTGRAY); c.drawRect(x, y, x + colW, y + h, fill) }
                        c.drawRect(x, y, x + colW, y + h, stroke)
                        c.save(); c.translate(x + pad, y + pad); ls[i].draw(c); c.restore()
                    }
                }
                y += h
            }
            val head = cells(header, cellBold)
            row(head, true)
            for (r in e.data.tableRows) {
                val ls = cells(r, cell)
                if (y + ls.maxOf { it.height } + 8 > layout.contentBottom) { newPage(); row(head, true) }
                row(ls, false)
            }
            y += layout.paragraphSpacing
        }

        for ((section, _) in snapshot.outline(includeDisabled = false)) {
            // Every top-level section and every chapter starts on a new page.
            if (section.parentId == null || section.kind == SectionKind.CHAPTER) newPage(cover = section.kind == SectionKind.COVER)
            when (section.kind) {
                SectionKind.COVER -> {
                    val m = snapshot.meta
                    y = layout.marginTop + 20f
                    listOf(m.university, m.faculty, m.department).filter { it.isNotBlank() }.forEach { text(it, headings[3], "center", 4f) }
                    y += 60f
                    text(ThesisLabels.get(lang, m.level.name), body, "center", 6f)
                    if (m.specialization.isNotBlank()) text("${ThesisLabels.get(lang, "specialization")}: ${m.specialization}", body, "center", 6f)
                    y += 40f
                    text(m.title, title, "center", if (m.subtitle.isBlank()) 40f else 8f)
                    if (m.subtitle.isNotBlank()) text(m.subtitle, body, "center", 32f)
                    if (m.studentName.isNotBlank()) text("${ThesisLabels.get(lang, "student")}: ${m.studentName}", body, "center", 6f)
                    if (m.studentId.isNotBlank()) text("${ThesisLabels.get(lang, "studentId")}: ${m.studentId}", body, "center", 6f)
                    if (m.supervisor.isNotBlank()) text("${ThesisLabels.get(lang, "supervisor")}: ${m.supervisor}", body, "center", 6f)
                    if (m.coSupervisor.isNotBlank()) text("${ThesisLabels.get(lang, "coSupervisor")}: ${m.coSupervisor}", body, "center", 6f)
                    val jury = m.juryMembers.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (jury.isNotEmpty()) { y += 16f; text(ThesisLabels.get(lang, "jury"), headings[3], "center", 4f); jury.forEach { text(it, body, "center", 2f) } }
                    m.extraMetadata.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { text(it, body, "center", 2f) }
                    if (m.academicYear.isNotBlank()) { y += 20f; text("${ThesisLabels.get(lang, "year")}: ${m.academicYear}", body, "center", 0f) }
                    if (m.submissionDate.isNotBlank()) { y += 6f; text("${ThesisLabels.get(lang, "submitted")}: ${m.submissionDate}", body, "center", 0f) }
                }
                SectionKind.TABLE_OF_CONTENTS -> {
                    text(ThesisStructure.displayTitle(section, numbering, lang), headings[0], "center", 16f)
                    val entries = snapshot.outline(includeDisabled = false).filter { !it.first.kind.generated }
                    indexLines(entries.map { (s, d) -> "    ".repeat(d) + ThesisStructure.displayTitle(s, numbering, lang) }, known?.headings)
                }
                SectionKind.LIST_OF_FIGURES -> {
                    text(ThesisStructure.displayTitle(section, numbering, lang), headings[0], "center", 16f)
                    indexLines(snapshot.orderedElements().filter { it.type == ElementType.IMAGE }.map { ThesisStructure.figureCaption(it, numbering, lang) }, known?.figures)
                }
                SectionKind.LIST_OF_TABLES -> {
                    text(ThesisStructure.displayTitle(section, numbering, lang), headings[0], "center", 16f)
                    indexLines(snapshot.orderedElements().filter { it.type == ElementType.TABLE }.map { ThesisStructure.tableCaption(it, numbering, lang) }, known?.tables)
                }
                else -> {
                    val level = ThesisStructure.headingLevel(snapshot, section)
                    val heading = staticLayout(ThesisStructure.displayTitle(section, numbering, lang), headings[(level - 1).coerceIn(0, 3)], width, if (level == 1) "center" else "start")
                    if (y + heading.height + layout.bodySize * 3 > layout.contentBottom) newPage()
                    record.headings.add(section.id to pageNo)
                    block(heading, layout.marginLeft, 12f)
                    for (e in snapshot.elementsOf(section.id)) {
                        val (t, _) = ThesisStructure.resolveCrossReferences(e.text, numbering, lang)
                        if (e.data.pageBreakBefore) newPage()
                        val ta = e.data.textAlign
                        when (e.type) {
                            ElementType.HEADING -> text(t, headings[(level + e.data.headingLevel.coerceAtLeast(1) - 1).coerceIn(0, 3)], ta.ifEmpty { "start" }, 8f)
                            ElementType.PARAGRAPH -> text(t, body, ta.ifEmpty { layout.bodyAlignment })
                            ElementType.REFERENCE -> text(t, body, ta.ifEmpty { "start" }, 6f)
                            ElementType.LIST_ITEM -> text((if (e.data.ordered) e.data.marker.ifBlank { "•" } else "•") + "  " + t, body, "start", 3f, layout.listIndent * (1 + e.data.headingLevel.coerceIn(0, 3)))
                            ElementType.IMAGE -> {
                                image(e)
                                record.figures.add(e.id to pageNo)
                                text(ThesisStructure.figureCaption(e, numbering, lang), caption, "center", if (e.data.source.isBlank()) 12f else 2f)
                                if (e.data.source.isNotBlank()) text(e.data.source, caption, "center", 12f)
                            }
                            ElementType.TABLE -> {
                                text(ThesisStructure.tableCaption(e, numbering, lang), caption, "center", 4f)
                                record.tables.add(e.id to pageNo)
                                table(e)
                            }
                        }
                    }
                }
            }
        }
        if (pageNo == 0) newPage()
        finish()
        return pageNo
    }

    /** Decodes an image no wider than [targetWidth] pixels (the original file is untouched). */
    private fun decodeScaled(file: File, targetWidth: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth.coerceAtLeast(1)) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: OutOfMemoryError) {
        null
    }
}
