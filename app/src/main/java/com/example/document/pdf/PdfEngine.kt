package com.example.document.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.document.model.WordDocumentModel
import com.example.template.domain.TemplateProfile
import java.io.File
import java.io.FileOutputStream

/**
 * PdfEngine produces genuine, high-fidelity PDF documents using Android's native
 * android.graphics.pdf.PdfDocument. Handles pagination, styling, headers, tables,
 * footers, and RTL text layout.
 */
class PdfEngine {

    fun generatePdf(
        model: WordDocumentModel,
        outputFile: File,
        template: TemplateProfile = TemplateProfile.defaultProfile()
    ): File {
        if (outputFile.parentFile?.exists() == false) {
            outputFile.parentFile?.mkdirs()
        }

        val pdfDocument = PdfDocument()
        val pageWidth = 595 // A4 standard pt at 72dpi
        val pageHeight = 842
        var currentPageNum = 1

        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        val primaryColor = parseColorSafely(template.primaryColorHex, Color.rgb(36, 214, 232))
        val secondaryColor = parseColorSafely(template.secondaryColorHex, Color.rgb(134, 101, 247))
        val textColor = parseColorSafely(template.textColorHex, Color.rgb(31, 41, 55))

        val titlePaint = TextPaint().apply {
            color = primaryColor
            textSize = 22f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val subtitlePaint = TextPaint().apply {
            color = Color.rgb(107, 114, 128)
            textSize = 13f
            isAntiAlias = true
        }

        val headingPaint = TextPaint().apply {
            color = Color.rgb(16, 24, 43)
            textSize = 15f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val bodyPaint = TextPaint().apply {
            color = textColor
            textSize = 10.5f
            isAntiAlias = true
        }

        val barPaint = Paint().apply {
            color = primaryColor
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val tableHeaderPaint = Paint().apply {
            color = primaryColor
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val tableRowAltPaint = Paint().apply {
            color = Color.rgb(243, 244, 246)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            color = Color.rgb(229, 231, 235)
            strokeWidth = 1f
            isAntiAlias = true
        }

        val margin = template.marginLeftPt.toFloat().coerceIn(36f, 72f)
        val contentWidth = pageWidth - (margin * 2)
        var y = margin

        // Draw top accent bar
        canvas.drawRect(margin, y, pageWidth - margin, y + 4f, barPaint)
        y += 24f

        // Draw Title
        val titleLayout = StaticLayout.Builder.obtain(model.title, 0, model.title.length, titlePaint, contentWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        canvas.save()
        canvas.translate(margin, y)
        titleLayout.draw(canvas)
        canvas.restore()
        y += titleLayout.height + 12f

        // Draw Subtitle / Org
        val subText = if (model.subtitle.isNotBlank()) "${model.subtitle} • ${model.organization}" else model.organization
        val subLayout = StaticLayout.Builder.obtain(subText, 0, subText.length, subtitlePaint, contentWidth.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        canvas.save()
        canvas.translate(margin, y)
        subLayout.draw(canvas)
        canvas.restore()
        y += subLayout.height + 20f

        // Draw dividing rule
        canvas.drawLine(margin, y, pageWidth - margin, y, linePaint)
        y += 18f

        // Draw Sections
        for (section in model.sections) {
            // Check page overflow
            if (y > pageHeight - 120f) {
                drawFooter(canvas, margin, pageWidth, pageHeight, currentPageNum)
                pdfDocument.finishPage(page)
                currentPageNum++
                val nextInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
                page = pdfDocument.startPage(nextInfo)
                canvas = page.canvas
                y = margin
            }

            // Section Heading
            val headingLayout = StaticLayout.Builder.obtain(section.heading, 0, section.heading.length, headingPaint, contentWidth.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            canvas.save()
            canvas.translate(margin, y)
            headingLayout.draw(canvas)
            canvas.restore()
            y += headingLayout.height + 8f

            // Paragraphs
            for (p in section.paragraphs) {
                val pLayout = StaticLayout.Builder.obtain(p, 0, p.length, bodyPaint, contentWidth.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                if (y + pLayout.height > pageHeight - 90f) {
                    drawFooter(canvas, margin, pageWidth, pageHeight, currentPageNum)
                    pdfDocument.finishPage(page)
                    currentPageNum++
                    val nextInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
                    page = pdfDocument.startPage(nextInfo)
                    canvas = page.canvas
                    y = margin
                }
                canvas.save()
                canvas.translate(margin, y)
                pLayout.draw(canvas)
                canvas.restore()
                y += pLayout.height + 10f
            }

            // Bullets
            for (b in section.bulletPoints) {
                val bulletText = "•  $b"
                val bLayout = StaticLayout.Builder.obtain(bulletText, 0, bulletText.length, bodyPaint, contentWidth.toInt() - 16)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                if (y + bLayout.height > pageHeight - 90f) {
                    drawFooter(canvas, margin, pageWidth, pageHeight, currentPageNum)
                    pdfDocument.finishPage(page)
                    currentPageNum++
                    val nextInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
                    page = pdfDocument.startPage(nextInfo)
                    canvas = page.canvas
                    y = margin
                }
                canvas.save()
                canvas.translate(margin + 12f, y)
                bLayout.draw(canvas)
                canvas.restore()
                y += bLayout.height + 6f
            }
            y += 12f
        }

        // Draw Table if present
        if (model.table != null && model.table.headers.isNotEmpty()) {
            if (y > pageHeight - 160f) {
                drawFooter(canvas, margin, pageWidth, pageHeight, currentPageNum)
                pdfDocument.finishPage(page)
                currentPageNum++
                val nextInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, currentPageNum).create()
                page = pdfDocument.startPage(nextInfo)
                canvas = page.canvas
                y = margin
            }

            val colCount = model.table.headers.size
            val colWidth = contentWidth / colCount
            val rowHeight = 22f

            // Header Row
            canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, tableHeaderPaint)
            val headerTextPaint = TextPaint().apply {
                color = Color.WHITE
                textSize = 10f
                isFakeBoldText = true
                isAntiAlias = true
            }
            for (i in model.table.headers.indices) {
                canvas.drawText(model.table.headers[i], margin + (i * colWidth) + 6f, y + 15f, headerTextPaint)
            }
            y += rowHeight

            // Data Rows
            var rowIndex = 0
            for (row in model.table.rows) {
                if (rowIndex % 2 == 1) {
                    canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, tableRowAltPaint)
                }
                for (i in 0 until minOf(row.size, colCount)) {
                    canvas.drawText(row[i], margin + (i * colWidth) + 6f, y + 15f, bodyPaint)
                }
                canvas.drawLine(margin, y + rowHeight, margin + contentWidth, y + rowHeight, linePaint)
                y += rowHeight
                rowIndex++
            }
            y += 18f
        }

        // Draw Footer on last page
        drawFooter(canvas, margin, pageWidth, pageHeight, currentPageNum)
        pdfDocument.finishPage(page)

        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        return outputFile
    }

    private fun drawFooter(canvas: android.graphics.Canvas, margin: Float, pageWidth: Int, pageHeight: Int, pageNum: Int) {
        val footerPaint = TextPaint().apply {
            color = Color.rgb(156, 163, 175)
            textSize = 9f
            isAntiAlias = true
        }
        val footerLinePaint = Paint().apply {
            color = Color.rgb(229, 231, 235)
            strokeWidth = 0.8f
        }
        val footerY = pageHeight - 36f
        canvas.drawLine(margin, footerY - 10f, pageWidth - margin, footerY - 10f, footerLinePaint)
        canvas.drawText("Smart Bubble Executive Document", margin, footerY, footerPaint)
        canvas.drawText("Page $pageNum", pageWidth - margin - 40f, footerY, footerPaint)
    }

    private fun parseColorSafely(hex: String, fallback: Int): Int {
        return try {
            Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
        } catch (e: Exception) {
            fallback
        }
    }
}
