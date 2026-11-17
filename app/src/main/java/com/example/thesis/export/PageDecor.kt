package com.example.thesis.export

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import com.example.document.pdf.PdfProfileLayout
import com.example.template.domain.TemplateProfile
import java.io.File

/**
 * What a template puts on every page around the text: header and footer text (with the page
 * number where the template has a PAGE field), a header image (a university logo), page borders
 * and the page number position and format. The built-in profile has none of these except a
 * centered page number, left off the cover.
 */
internal class PageDecor private constructor(
    private val profile: TemplateProfile,
    private val layout: PdfProfileLayout,
    private val borders: List<Border>,
    private val image: HeaderImage?
) {
    class Border(val side: String, val style: String, val width: Float, val space: Float, val color: Int)
    class HeaderImage(val file: File, val width: Float, val height: Float, val align: String)

    /** Overrides the template's numbering (the thesis page numbering options). */
    var labeler: ((Int) -> String)? = null

    private val top: Float get() = (profile.headerOffsetPt - 9).coerceAtLeast(12).toFloat()

    /** Lowest point of the default header (image and text lines), for the start of the text area. */
    fun headerBottom(paint: TextPaint): Float {
        if (profile.layoutSource != "DOCX") return 0f
        val lines = profile.headerText.lines().count { it.isNotBlank() }
        val text = if (lines > 0) textTop(paint) + lines * paint.fontSpacing else 0f
        return maxOf(text, image?.let { top + it.height } ?: 0f)
    }

    private fun textTop(paint: TextPaint): Float {
        val i = image
        // Text and an image on the same side: the text goes below the image.
        return if (i != null && i.align == profile.headerAlignment) top + i.height + 2f else top
    }

    fun draw(c: Canvas, pageNo: Int, pageCount: Int, cover: Boolean, headerPaint: TextPaint, numberPaint: TextPaint) {
        drawBorders(c)
        if (profile.layoutSource != "DOCX") {
            // Built-in formatting and PDF-measured profiles (unchanged): a header line, the page
            // number centered at the bottom except on the cover.
            if (layout.headerText.isNotBlank()) {
                val w = numberPaint.measureText(layout.headerText)
                c.drawText(layout.headerText, PdfProfileLayout.xFor(layout.headerAlignment, w, layout), layout.headerBaseline, numberPaint)
            }
            if (!cover) drawAt(c, "footer-center", number(pageNo), numberPaint)
            return
        }
        val first = pageNo == 1 && profile.differentFirstPage
        val header = if (first) profile.firstHeaderText else profile.headerText
        val footer = if (first) profile.firstFooterText else profile.footerText
        val n = number(pageNo)
        fun fill(t: String) = t.replace("{PAGE}", n).replace("{NUMPAGES}", if (pageCount > 0) pageCount.toString() else "")
        val img = image
        if (img != null && (!first || profile.firstPageHasHeaderImage)) {
            BitmapFactory.decodeFile(img.file.path)?.let { bmp ->
                val x = PdfProfileLayout.xFor(img.align, img.width, layout)
                c.drawBitmap(bmp, null, RectF(x, top, x + img.width, top + img.height), Paint(Paint.FILTER_BITMAP_FLAG))
                bmp.recycle()
            }
        }
        var y = textTop(headerPaint) - headerPaint.fontMetrics.ascent
        for (line in header.lines().filter { it.isNotBlank() }) {
            val t = fill(line)
            c.drawText(t, PdfProfileLayout.xFor(profile.headerAlignment, headerPaint.measureText(t), layout), y, headerPaint)
            y += headerPaint.fontSpacing
        }
        val footerLines = footer.lines().filter { it.isNotBlank() }
        var fy = layout.pageHeight - layout.footerBaseline - (footerLines.size - 1).coerceAtLeast(0) * headerPaint.fontSpacing
        for (line in footerLines) {
            val t = fill(line)
            c.drawText(t, PdfProfileLayout.xFor(profile.footerAlignment, headerPaint.measureText(t), layout), fy, headerPaint)
            fy += headerPaint.fontSpacing
        }
        // A page number position without a PAGE field in the text (a template without a footer).
        if (!(header + footer).contains("{PAGE}") && layout.pageNumberPosition.isNotEmpty() && !cover) drawAt(c, layout.pageNumberPosition, n, numberPaint)
    }

    private fun drawAt(c: Canvas, position: String, text: String, paint: TextPaint) {
        val w = paint.measureText(text)
        val align = position.substringAfter('-', "center")
        val y = if (position.startsWith("header")) layout.headerBaseline else layout.pageHeight - layout.footerBaseline
        c.drawText(text, PdfProfileLayout.xFor(align, w, layout), y, paint)
    }

    /** Page number in the template's format (decimal, roman or letters), counted from its start number. */
    fun number(pageNo: Int): String {
        labeler?.let { return it(pageNo) }
        val n = pageNo - 1 + profile.pageNumberStart.coerceAtLeast(1)
        return when (profile.pageNumberFormat) {
            "upperRoman" -> roman(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperLetter" -> letter(n)
            "lowerLetter" -> letter(n).lowercase()
            else -> n.toString()
        }
    }

    private fun drawBorders(c: Canvas) {
        if (borders.isEmpty()) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val fromPage = profile.pageBorderOffsetFrom == "page"
        val w = layout.pageWidth.toFloat()
        val h = layout.pageHeight.toFloat()
        for (b in borders) {
            p.color = b.color
            p.strokeWidth = b.width.coerceIn(0.25f, 6f)
            when (b.style) {
                "dotted" -> p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(p.strokeWidth, p.strokeWidth * 2), 0f)
                "dashed", "dashSmallGap", "dotDash", "dotDotDash", "dashDotStroked" -> p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(p.strokeWidth * 4, p.strokeWidth * 2), 0f)
                else -> p.pathEffect = null
            }
            // Word measures the border from the page edge or from the text area.
            val pos = when (b.side) {
                "top" -> if (fromPage) b.space else layout.marginTop - b.space
                "bottom" -> if (fromPage) h - b.space else layout.contentBottom + b.space
                "left" -> if (fromPage) b.space else layout.marginLeft - b.space
                else -> if (fromPage) w - b.space else w - layout.marginRight + b.space
            }.coerceIn(2f, maxOf(w, h))
            val lines = when {
                b.style == "double" || b.style.contains("Gap") -> listOf(0f, p.strokeWidth * 3)
                b.style == "triple" -> listOf(0f, p.strokeWidth * 3, p.strokeWidth * 6)
                else -> listOf(0f)
            }
            for (o in lines) {
                // Extra lines are drawn toward the inside of the page.
                when (b.side) {
                    "top" -> c.drawLine(edge("left", fromPage), pos + o, edge("right", fromPage), pos + o, p)
                    "bottom" -> c.drawLine(edge("left", fromPage), pos - o, edge("right", fromPage), pos - o, p)
                    "left" -> c.drawLine(pos + o, edge("top", fromPage), pos + o, edge("bottom", fromPage), p)
                    else -> c.drawLine(pos - o, edge("top", fromPage), pos - o, edge("bottom", fromPage), p)
                }
            }
        }
    }

    /** Where a border line starts/ends: at the neighbouring border, or at the page/text corner. */
    private fun edge(side: String, fromPage: Boolean): Float {
        val b = borders.firstOrNull { it.side == side }
        val w = layout.pageWidth.toFloat()
        val h = layout.pageHeight.toFloat()
        val space = b?.space ?: 24f
        return when (side) {
            "top" -> if (fromPage) space else layout.marginTop - space
            "bottom" -> if (fromPage) h - space else layout.contentBottom + space
            "left" -> if (fromPage) space else layout.marginLeft - space
            else -> if (fromPage) w - space else w - layout.marginRight + space
        }.coerceIn(2f, maxOf(w, h))
    }

    companion object {
        fun from(profile: TemplateProfile, layout: PdfProfileLayout): PageDecor {
            val borders = profile.pageBorders.split(';').mapNotNull { part ->
                val f = part.split(':')
                if (f.size != 5) return@mapNotNull null
                val color = try { Color.parseColor(f[4]) } catch (e: Exception) { Color.BLACK }
                Border(f[0], f[1], f[2].toFloatOrNull() ?: 0.5f, (f[3].toFloatOrNull() ?: 0f).coerceIn(0f, 31f), color)
            }
            val img = profile.headerImage.split('|').takeIf { it.size == 4 && it[0].startsWith("/") }?.let { f ->
                val file = File(f[0])
                if (!file.isFile) return@let null
                var w = (f[1].toFloatOrNull() ?: 0f)
                var h = (f[2].toFloatOrNull() ?: 0f)
                if (w <= 0f || h <= 0f) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, o)
                    if (o.outWidth <= 0) return@let null
                    w = 60f; h = 60f * o.outHeight / o.outWidth
                }
                // Within the page width and a header band of at most a third of the page.
                val scale = minOf(1f, layout.contentWidth / w, layout.pageHeight / 3f / h)
                HeaderImage(file, w * scale, h * scale, f[3])
            }
            return PageDecor(profile, layout, borders, img)
        }

        fun roman(value: Int): String {
            var n = value.coerceIn(1, 3999)
            val sb = StringBuilder()
            for ((v, s) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
                while (n >= v) { sb.append(s); n -= v }
            }
            return sb.toString()
        }

        fun letter(value: Int): String {
            val n = value.coerceAtLeast(1) - 1
            return (('A' + n % 26).toString()).repeat(n / 26 + 1)
        }
    }
}
