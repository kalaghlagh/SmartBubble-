package com.example.document.pdf

import com.example.template.domain.TemplateProfile

/**
 * Page geometry and styles for a PDF generated from a PDF template's measured profile. Pure
 * Kotlin so it can be unit-tested; [PdfProfileRenderer] draws with it.
 */
data class PdfProfileLayout(
    val pageWidth: Int,
    val pageHeight: Int,
    val marginTop: Float,
    val marginBottom: Float,
    val marginLeft: Float,
    val marginRight: Float,
    val bodySize: Float,
    val titleSize: Float,
    val headingSize: Float,
    val bodyFont: String,
    val headingFont: String,
    val headingBold: Boolean,
    val textColor: String,
    val headingColor: String,
    val backgroundColor: String?,
    val bodyAlignment: String,
    val headingAlignment: String,
    /** Line height as a multiple of the font size. */
    val lineSpacing: Float,
    val paragraphSpacing: Float,
    val headerText: String,
    val footerText: String,
    val headerSize: Float,
    val footerSize: Float,
    val headerAlignment: String,
    val footerAlignment: String,
    /** Header baseline from the top edge; footer baseline from the bottom edge. */
    val headerBaseline: Float,
    val footerBaseline: Float,
    /** "header-start" ... "footer-end", or "" for no page numbers. */
    val pageNumberPosition: String,
    val listMarker: String,
    val listIndent: Float,
    val tableHeaderFill: String,
    val tableBorder: String,
    val tableCellAlignment: String,
    val decorations: List<Shape>,
    val rtl: Boolean
) {
    /** A colored rectangle in points, top-left origin. */
    data class Shape(val left: Float, val top: Float, val width: Float, val height: Float, val colorHex: String)

    val contentWidth: Float get() = pageWidth - marginLeft - marginRight
    val contentBottom: Float get() = pageHeight - marginBottom

    companion object {
        fun from(p: TemplateProfile): PdfProfileLayout {
            val w = (if (p.pageWidthPt > 0) p.pageWidthPt else 595).coerceIn(144, 5000)
            val h = (if (p.pageHeightPt > 0) p.pageHeightPt else 842).coerceIn(144, 5000)
            // Keep a usable text area even if a template's margins were measured very wide.
            fun margin(v: Int, size: Int) = v.toFloat().coerceIn(18f, size * 0.3f)
            val body = p.baseFontSizePt.toFloat().coerceIn(6f, 48f)
            val shapes = p.decorations.split(';').mapNotNull { part ->
                val f = part.split(',')
                if (f.size != 5) return@mapNotNull null
                val nums = f.take(4).map { it.toFloatOrNull() ?: return@mapNotNull null }
                Shape(nums[0] * w, nums[1] * h, nums[2] * w, nums[3] * h, f[4])
            }
            return PdfProfileLayout(
                pageWidth = w,
                pageHeight = h,
                marginTop = margin(p.marginTopPt, h),
                marginBottom = margin(p.marginBottomPt, h),
                marginLeft = margin(p.marginLeftPt, w),
                marginRight = margin(p.marginRightPt, w),
                bodySize = body,
                titleSize = p.heading1SizePt.toFloat().coerceIn(body, 96f),
                headingSize = p.heading2SizePt.toFloat().coerceIn(body, 96f),
                bodyFont = p.bodyFont,
                headingFont = p.headingFont,
                headingBold = p.headingBold,
                textColor = p.textColorHex,
                headingColor = p.headingColorHex.ifEmpty { p.primaryColorHex },
                backgroundColor = p.backgroundColorHex.takeIf { !it.equals("#FFFFFF", ignoreCase = true) },
                bodyAlignment = p.bodyAlignment,
                headingAlignment = p.headingAlignment,
                lineSpacing = if (p.lineSpacing > 0f) p.lineSpacing.coerceIn(0.9f, 3f) else 1.2f,
                paragraphSpacing = if (p.paragraphSpacingPt >= 0) p.paragraphSpacingPt.toFloat() else body * 0.8f,
                headerText = p.headerText,
                footerText = p.footerText,
                headerSize = (if (p.headerFontSizePt > 0) p.headerFontSizePt else 9).toFloat(),
                footerSize = (if (p.footerFontSizePt > 0) p.footerFontSizePt else 9).toFloat(),
                headerAlignment = p.headerAlignment,
                footerAlignment = p.footerAlignment,
                headerBaseline = (if (p.headerOffsetPt > 0) p.headerOffsetPt else 36).toFloat(),
                footerBaseline = (if (p.footerOffsetPt > 0) p.footerOffsetPt else 30).toFloat(),
                pageNumberPosition = p.pageNumberPosition,
                listMarker = p.listMarker.takeIf { it.isNotBlank() && it[0] !in "1a" } ?: "•",
                listIndent = (if (p.listIndentPt > 0) p.listIndentPt else 18).toFloat(),
                tableHeaderFill = p.tableHeaderBgHex,
                tableBorder = p.tableBorderHex,
                tableCellAlignment = p.tableCellAlignment,
                decorations = shapes,
                rtl = p.rtl
            )
        }

        /** Android typeface family for a font name found in the PDF. */
        fun genericFamily(name: String): String {
            val n = name.lowercase()
            return when {
                listOf("times", "serif", "georgia", "garamond", "cambria", "book", "naskh", "amiri").any { it in n } && "sans" !in n -> "serif"
                listOf("courier", "mono", "consol").any { it in n } -> "monospace"
                else -> "sans-serif"
            }
        }

        /** Horizontal position of a header/footer/page-number text of [textWidth]. */
        fun xFor(alignment: String, textWidth: Float, layout: PdfProfileLayout): Float = when (alignment) {
            "center" -> (layout.pageWidth - textWidth) / 2
            "end" -> if (layout.rtl) layout.marginLeft else layout.pageWidth - layout.marginRight - textWidth
            else -> if (layout.rtl) layout.pageWidth - layout.marginRight - textWidth else layout.marginLeft
        }
    }
}
