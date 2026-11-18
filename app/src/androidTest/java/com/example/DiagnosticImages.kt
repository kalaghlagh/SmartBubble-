package com.example

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream

/**
 * Diagnostic crops of the OCR pipeline (list-marker lines, table cells, whole pages scaled down)
 * written to the instrumentation output as base64 PNG in chunks ("IMG <name> <i>/<n> <data>"), so
 * they can be decoded from the CI log and read again off the device with the same models.
 */
object DiagnosticImages {
    private var sent = 0

    fun install(prefix: String) {
        sent = 0
        com.example.extraction.OcrPageReader.traceImage = { name, bmp -> emit("$prefix/$name", bmp) }
    }

    fun uninstall() { com.example.extraction.OcrPageReader.traceImage = null }

    private fun emit(name: String, bmp: Bitmap) {
        if (sent >= 60) return
        sent++
        val maxSide = when { name.contains("turn-") -> 1240; name.contains("page-") -> 500; else -> 900 }
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val img = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream()
        img.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (img !== bmp) img.recycle()
        val data = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val chunks = data.chunked(3000)
        chunks.forEachIndexed { i, c ->
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("img", "IMG $name ${i + 1}/${chunks.size} $c") })
        }
    }
}
