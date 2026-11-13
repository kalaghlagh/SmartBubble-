package com.example.template.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * First-page images of PDF templates, rendered on the device with Android's PdfRenderer and kept
 * next to the templates (private storage). Rendering fails gracefully (null) for damaged or
 * password-protected files.
 */
object TemplateThumbnails {

    /** Width of the stored image in pixels (enough for the preview, small on disk). */
    const val WIDTH_PX = 720

    fun fileFor(templatesDir: File, templateId: String): File = File(File(templatesDir, "thumbs"), "$templateId.png")

    /** Renders page 1 of [pdf] into [dest]; returns false when the PDF cannot be rendered. */
    fun renderPdfFirstPage(pdf: File, dest: File, widthPx: Int = WIDTH_PX): Boolean {
        val bitmap = renderPage(pdf, 0, widthPx) ?: return false
        return try {
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Exception) {
            dest.delete()
            false
        } finally {
            bitmap.recycle()
        }
    }

    /** Page [index] of [pdf] as a bitmap [widthPx] wide, or null. */
    fun renderPage(pdf: File, index: Int, widthPx: Int = WIDTH_PX): Bitmap? = try {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                if (index !in 0 until renderer.pageCount) return null
                renderer.openPage(index).use { page ->
                    val height = (widthPx.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, widthPx * 4)
                    val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    } catch (e: Exception) {
        null // damaged, encrypted, or rendering not available
    } catch (e: OutOfMemoryError) {
        null
    }

    /** The stored thumbnail, rendering it first if needed. */
    fun load(templatesDir: File, templateId: String, pdf: File): Bitmap? {
        val file = fileFor(templatesDir, templateId)
        if (!file.isFile && (!pdf.isFile || !renderPdfFirstPage(pdf, file))) return null
        return try { BitmapFactory.decodeFile(file.path) } catch (e: Exception) { null }
    }
}
