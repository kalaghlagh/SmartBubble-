package com.example.document.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.document.model.WordDocumentModel
import com.example.document.model.WordTable
import com.example.template.domain.TemplateProfile
import java.io.File
import java.io.FileOutputStream

/**
 * Draws a PDF that follows a PDF template's measured layout ([TemplateProfile.isPdfLayout]):
 * its page size, margins, background, colored shapes, header/footer text and page numbers at
 * their positions, fonts (mapped to the closest Android family), sizes, colors, alignment and
 * spacing. Only the template's own header/footer text is drawn; no app branding is added.
 */
internal class PdfProfileRenderer(private val profile: TemplateProfile) {

    private val layout = PdfProfileLayout.from(profile)

    private fun color(hex: String, fallback: Int = Color.BLACK): Int = try {
        Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
    } catch (e: Exception) {
        fallback
    }

    private fun typeface(name: String, bold: Boolean): Typeface =
        Typeface.create(Typeface.create(PdfProfileLayout.genericFamily(name), Typeface.NORMAL), if (bold) Typeface.BOLD else Typeface.NORMAL)

    private fun paint(size: Float, font: String, bold: Boolean, colorHex: String) = TextPaint().apply {
        isAntiAlias = true
        textSize = size
        typeface = typeface(font, bold)
        color = color(colorHex)
    }

    private fun alignment(value: String): Layout.Alignment = when (value) {
        "center" -> Layout.Alignment.ALIGN_CENTER
        "end" -> Layout.Alignment.ALIGN_OPPOSITE
        else -> Layout.Alignment.ALIGN_NORMAL
    }

    /** Page borders of the profile ("side:style:widthPt:spacePt:#color"), from the page edge or around the text. */
    private fun drawBorders(canvas: Canvas) {
        val sides = profile.pageBorders.split(';').mapNotNull { part -> part.split(':').takeIf { it.size == 5 } }
        if (sides.isEmpty()) return
        val fromPage = profile.pageBorderOffsetFrom == "page"
        val w = layout.pageWidth.toFloat()
        val h = layout.pageHeight.toFloat()
        fun pos(side: String, space: Float): Float = when (side) {
            "top" -> if (fromPage) space else layout.marginTop - space
            "bottom" -> if (fromPage) h - space else layout.contentBottom + space
            "left" -> if (fromPage) space else layout.marginLeft - space
            else -> if (fromPage) w - space else w - layout.marginRight + space
        }.coerceIn(1f, maxOf(w, h) - 1f)
        val spaces = sides.associate { it[0] to (it[3].toFloatOrNull() ?: 24f) }
        fun edge(side: String) = pos(side, spaces[side] ?: 24f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        for (f in sides) {
            paint.color = color(f[4], Color.BLACK)
            paint.strokeWidth = (f[2].toFloatOrNull() ?: 0.5f).coerceIn(0.25f, 12f)
            val at = pos(f[0], spaces[f[0]] ?: 24f)
            val offsets = if (f[1] == "double") listOf(0f, paint.strokeWidth + 2f) else listOf(0f)
            // A side without a neighbour border runs from page edge to page edge.
            val start = if (f[0] == "top" || f[0] == "bottom") (if ("left" in spaces) edge("left") else 0f) else (if ("top" in spaces) edge("top") else 0f)
            val end = if (f[0] == "top" || f[0] == "bottom") (if ("right" in spaces) edge("right") else w) else (if ("bottom" in spaces) edge("bottom") else h)
            for (o in offsets) when (f[0]) {
                "top" -> canvas.drawLine(start, at + o + paint.strokeWidth / 2, end, at + o + paint.strokeWidth / 2, paint)
                "bottom" -> canvas.drawLine(start, at - o, end, at - o, paint)
                "left" -> canvas.drawLine(at + o, start, at + o, end, paint)
                else -> canvas.drawLine(at - o, start, at - o, end, paint)
            }
        }
    }

    private fun build(text: CharSequence, paint: TextPaint, width: Int, align: String): StaticLayout {
        val metrics = paint.fontMetrics
        val natural = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        val multiplier = (layout.lineSpacing * paint.textSize / natural).coerceIn(0.8f, 3f)
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(alignment(align))
            .setLineSpacing(0f, multiplier)
            .setIncludePad(false)
        if (align == "justify" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setJustificationMode(interWordJustification())
        }
        return builder.build()
    }

    fun render(model: WordDocumentModel, output: File) {
        val doc = PdfDocument()
        val bodyPaint = paint(layout.bodySize, layout.bodyFont, false, layout.textColor)
        val titlePaint = paint(layout.titleSize, layout.headingFont, layout.headingBold, layout.headingColor)
        val headingPaint = paint(layout.headingSize, layout.headingFont, layout.headingBold, layout.headingColor)
        val headerPaint = paint(layout.headerSize, layout.bodyFont, false, layout.textColor)
        val footerPaint = paint(layout.footerSize, layout.bodyFont, false, layout.textColor)
        val cellPaint = paint(layout.bodySize * 0.92f, layout.bodyFont, false, layout.textColor)
        val cellHeaderPaint = paint(layout.bodySize * 0.92f, layout.bodyFont, true, layout.textColor)
        val fill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        val stroke = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.75f; color = color(layout.tableBorder, Color.GRAY); isAntiAlias = true }
        val width = layout.contentWidth.toInt()

        var pageNumber = 0
        lateinit var page: PdfDocument.Page
        lateinit var canvas: Canvas
        var y = 0f

        fun drawChrome() {
            layout.backgroundColor?.let {
                fill.color = color(it, Color.WHITE)
                canvas.drawRect(0f, 0f, layout.pageWidth.toFloat(), layout.pageHeight.toFloat(), fill)
            }
            for (s in layout.decorations) {
                fill.color = color(s.colorHex, Color.TRANSPARENT)
                canvas.drawRect(s.left, s.top, s.left + s.width, s.top + s.height, fill)
            }
            fun line(text: String, p: TextPaint, align: String, baselineFromTop: Float) {
                val w = p.measureText(text)
                canvas.drawText(text, PdfProfileLayout.xFor(align, w, layout), baselineFromTop, p)
            }
            drawBorders(canvas)
            // "{PAGE}" / "{NUMPAGES}" fields of a Word template's header and footer.
            fun fields(t: String) = t.replace("{PAGE}", pageNumber.toString()).replace("{NUMPAGES}", "")
            if (layout.headerText.isNotBlank()) line(fields(layout.headerText.lines().first { it.isNotBlank() }), headerPaint, layout.headerAlignment, layout.headerBaseline)
            if (layout.footerText.isNotBlank()) line(fields(layout.footerText.lines().first { it.isNotBlank() }), footerPaint, layout.footerAlignment, layout.pageHeight - layout.footerBaseline)
            val pageField = (layout.headerText + layout.footerText).contains("{PAGE}")
            if (layout.pageNumberPosition.isNotEmpty() && !pageField) {
                val inHeader = layout.pageNumberPosition.startsWith("header-")
                val align = layout.pageNumberPosition.substringAfter('-')
                val baseline = if (inHeader) layout.headerBaseline + (if (layout.headerText.isNotBlank()) layout.headerSize * 1.4f else 0f)
                else layout.pageHeight - layout.footerBaseline + (if (layout.footerText.isNotBlank()) layout.footerSize * 1.4f else 0f)
                line(pageNumber.toString(), if (inHeader) headerPaint else footerPaint, align, baseline.coerceAtMost(layout.pageHeight - 4f))
            }
        }

        fun newPage() {
            if (pageNumber > 0) doc.finishPage(page)
            pageNumber++
            page = doc.startPage(PdfDocument.PageInfo.Builder(layout.pageWidth, layout.pageHeight, pageNumber).create())
            canvas = page.canvas
            drawChrome()
            y = layout.marginTop
        }

        /** Draws a text block, moving to a new page or splitting it across pages as needed. */
        fun block(sl: StaticLayout, x: Float, spaceAfter: Float) {
            val available = layout.contentBottom - y
            val fresh = layout.contentBottom - layout.marginTop
            if (PdfPagination.shouldMoveWholeBlockToNextPage(sl.height.toFloat(), available, fresh)) newPage()
            val count = sl.lineCount
            if (count > 0) {
                val bounds = IntArray(count + 1) { i -> if (i < count) sl.getLineTop(i) else sl.getLineBottom(count - 1) }
                for (chunk in PdfPagination.splitLines(bounds, layout.contentBottom - y, fresh)) {
                    if (chunk.startsNewPage) newPage()
                    val top = bounds[chunk.startLine]
                    val bottom = bounds[chunk.endLine]
                    canvas.save()
                    canvas.translate(x, y - top)
                    canvas.clipRect(0f, top.toFloat(), sl.width.toFloat(), bottom.toFloat())
                    sl.draw(canvas)
                    canvas.restore()
                    y += (bottom - top)
                }
            }
            y += spaceAfter
        }

        fun table(t: WordTable) {
            if (t.headers.isEmpty()) return
            val cols = t.headers.size
            val colWidth = layout.contentWidth / cols
            val pad = 4f
            fun cells(values: List<String>, p: TextPaint) = (0 until cols).map { build(values.getOrElse(it) { "" }, p, (colWidth - 2 * pad).toInt(), layout.tableCellAlignment) }
            fun height(ls: List<StaticLayout>) = ls.maxOf { it.height } + 2 * pad
            fun row(ls: List<StaticLayout>, header: Boolean) {
                val h = height(ls)
                if (y + h > layout.contentBottom) newPage()
                val order = if (layout.rtl) ls.indices.reversed() else ls.indices
                order.forEachIndexed { slot, i ->
                    val x = layout.marginLeft + slot * colWidth
                    if (header) {
                        fill.color = color(layout.tableHeaderFill, Color.LTGRAY)
                        canvas.drawRect(x, y, x + colWidth, y + h, fill)
                    }
                    canvas.drawRect(x, y, x + colWidth, y + h, stroke)
                    canvas.save()
                    canvas.translate(x + pad, y + pad)
                    ls[i].draw(canvas)
                    canvas.restore()
                }
                y += h
            }
            val header = cells(t.headers, cellHeaderPaint)
            row(header, true)
            for (r in t.rows) {
                val ls = cells(r, cellPaint)
                if (y + height(ls) > layout.contentBottom) {
                    newPage()
                    row(header, true)
                }
                row(ls, false)
            }
            y += layout.paragraphSpacing
        }

        val boldBody = paint(layout.bodySize, layout.bodyFont, true, layout.textColor)
        fun paragraph(text: String) {
            val indent = profile.firstLineIndentPt.coerceIn(0, 144)
            val content: CharSequence = if (indent > 0 && layout.bodyAlignment != "center")
                android.text.SpannableString(text).apply { setSpan(android.text.style.LeadingMarginSpan.Standard(indent, 0), 0, length, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
            else text
            block(build(content, bodyPaint, width, layout.bodyAlignment), layout.marginLeft, layout.paragraphSpacing)
        }
        val letter = model.letter

        try {
            newPage()
            if (letter != null) {
                // Letter / request: sender, place and date, recipient, title of a request, subject, salutation.
                letter.sender.filter { it.isNotBlank() }.forEach { block(build(it, bodyPaint, width, "start"), layout.marginLeft, 0f) }
                val dateLine = listOf(letter.place, letter.date.ifBlank { model.date }).filter { it.isNotBlank() }.joinToString(", ")
                if (dateLine.isNotBlank()) { y += layout.bodySize; block(build(dateLine, bodyPaint, width, "end"), layout.marginLeft, layout.bodySize) }
                letter.recipient.filter { it.isNotBlank() }.forEach { block(build(it, bodyPaint, width, "start"), layout.marginLeft, 0f) }
                if (letter.recipient.isNotEmpty()) y += layout.bodySize
                if (letter.showTitle && model.title.isNotBlank()) block(build(model.title, titlePaint, width, "center"), layout.marginLeft, layout.titleSize * 0.6f)
                if (letter.subject.isNotBlank()) block(build(letter.subject, boldBody, width, "start"), layout.marginLeft, layout.paragraphSpacing + layout.bodySize * 0.5f)
                if (letter.salutation.isNotBlank()) block(build(letter.salutation, bodyPaint, width, "start"), layout.marginLeft, layout.paragraphSpacing)
            } else {
                if (model.title.isNotBlank()) block(build(model.title, titlePaint, width, layout.headingAlignment), layout.marginLeft, layout.titleSize * 0.6f)
                if (model.subtitle.isNotBlank()) block(build(model.subtitle, bodyPaint, width, layout.headingAlignment), layout.marginLeft, layout.paragraphSpacing)
            }
            for (section in model.sections) {
                if (section.isPageBreakBefore) newPage()
                if (section.heading.isNotBlank()) {
                    val sl = build(section.heading, headingPaint, width, layout.headingAlignment)
                    // Keep a heading with the text that follows it.
                    if (y + sl.height + layout.bodySize * 2.5f > layout.contentBottom) newPage()
                    block(sl, layout.marginLeft, layout.headingSize * 0.4f)
                }
                section.paragraphs.forEach { paragraph(it) }
                section.bulletPoints.forEach {
                    val indent = layout.listIndent
                    val x = if (layout.rtl) layout.marginLeft else layout.marginLeft + indent
                    block(build("${layout.listMarker}  $it", bodyPaint, (width - indent).toInt(), "start"), x, layout.bodySize * 0.3f)
                }
                section.table?.let { table(it) }
            }
            model.table?.let { table(it) }
            if (letter != null) {
                if (letter.closing.isNotBlank()) { y += layout.bodySize * 0.5f; block(build(letter.closing, bodyPaint, width, "start"), layout.marginLeft, layout.paragraphSpacing) }
                if (letter.signatureName.isNotBlank()) { y += layout.bodySize * 2f; block(build(letter.signatureName, boldBody, width, "end"), layout.marginLeft, 0f) }
                if (letter.signatureTitle.isNotBlank()) block(build(letter.signatureTitle, bodyPaint, width, "end"), layout.marginLeft, 0f)
            }
            doc.finishPage(page)
            FileOutputStream(output).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }
}

/**
 * Inter-word justification. Lint expects the LineBreaker constant (API 29); on API 26-28 only
 * Layout's exists. Both are 1, so this is the same value on every version.
 */
@android.annotation.SuppressLint("WrongConstant")
internal fun interWordJustification(): Int =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD
    else android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
