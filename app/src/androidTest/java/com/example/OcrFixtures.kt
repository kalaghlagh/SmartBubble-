package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream

/** The printed-page fixture of the OCR device tests (shared so diagnostics use the exact same image). */
object OcrFixtures {
    const val ENGLISH = "Groundwater is an essential resource for the population. This study analyses the quality of wells in the region."
    const val FRENCH = "Les eaux souterraines constituent une ressource essentielle pour la population. Cette étude analyse la qualité des puits de la région."

    /** A page with [blocks] of text (each in its own direction), drawn like a printed document. */
    fun page(dir: File, name: String, blocks: List<String>, textColor: Int = Color.BLACK, background: Int = Color.WHITE, rotate: Float = 0f, textSize: Float = 38f): Uri {
        val w = 1240
        val h = 1754
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(background)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; this.textSize = textSize }
        var y = 120f
        for (b in blocks) {
            val layout = StaticLayout.Builder.obtain(b, 0, b.length, paint, w - 240).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.3f).build()
            canvas.save(); canvas.translate(120f, y); layout.draw(canvas); canvas.restore()
            y += layout.height + 60f
        }
        val out = if (rotate != 0f) Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(rotate) }, true) else bmp
        val file = File(dir, "$name.jpg")
        FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return Uri.fromFile(file)
    }
}
