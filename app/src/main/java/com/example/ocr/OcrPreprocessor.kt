package com.example.ocr

import android.graphics.Bitmap
import android.graphics.Matrix

/**
 * Image preparation before recognition: the page is converted to grey levels with its contrast
 * stretched (faint print, grey paper, uneven photos), and small images are enlarged so the text
 * has the size the recognizer expects. The original file is never changed.
 */
object OcrPreprocessor {

    /** Images whose long side is below this are enlarged (small photos, screenshots). */
    const val MIN_LONG_SIDE = 1600

    /** Grey level (0..255) of an ARGB pixel. */
    fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /**
     * Darkest and lightest levels to keep from a 256-bin [histogram]: the 1st and 99th
     * percentiles, so a few specks or a white border do not hide a low-contrast page.
     */
    fun stretchRange(histogram: IntArray, cut: Double = 0.01): Pair<Int, Int> {
        val total = histogram.sum().toLong()
        if (total == 0L) return 0 to 255
        val limit = (total * cut).toLong()
        var acc = 0L
        var lo = 0
        while (lo < 255) { acc += histogram[lo]; if (acc > limit) break; lo++ }
        acc = 0L
        var hi = 255
        while (hi > 0) { acc += histogram[hi]; if (acc > limit) break; hi-- }
        return if (hi - lo < 8) 0 to 255 else lo to hi
    }

    /** Level [v] mapped from [lo]..[hi] to 0..255. */
    fun stretch(v: Int, lo: Int, hi: Int): Int = ((v - lo) * 255 / (hi - lo).coerceAtLeast(1)).coerceIn(0, 255)

    /** True when the page needs enlarging before recognition. */
    fun needsUpscale(width: Int, height: Int): Boolean = maxOf(width, height) < MIN_LONG_SIDE

    /** A new grey, contrast-stretched (and enlarged if small) copy of [source]; [source] is not changed. */
    fun prepare(source: Bitmap): Bitmap {
        val scaled = if (needsUpscale(source.width, source.height)) {
            val f = MIN_LONG_SIDE.toFloat() / maxOf(source.width, source.height)
            Bitmap.createScaledBitmap(source, (source.width * f).toInt(), (source.height * f).toInt(), true)
        } else source
        val w = scaled.width
        val h = scaled.height
        val row = IntArray(w)
        val histogram = IntArray(256)
        for (y in 0 until h) {
            scaled.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) histogram[luminance(row[x])]++
        }
        val (lo, hi) = stretchRange(histogram)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            scaled.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val g = stretch(luminance(row[x]), lo, hi)
                row[x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        if (scaled !== source) scaled.recycle()
        return out
    }

    fun rotate(source: Bitmap, degrees: Int): Bitmap =
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    /**
     * Quality of a recognition, used to choose between orientations: mean confidence (0..1)
     * times the number of letters recognized.
     */
    fun score(text: String, confidence: Float): Double = confidence.toDouble() * text.count { it.isLetter() }

    /** Share of the letters of [text] that are Arabic. */
    fun arabicShare(text: String): Double {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return 0.0
        return letters.count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }.toDouble() / letters.length
    }

    /** A result too weak to trust the page's orientation (another rotation is tried). */
    fun isWeak(text: String, confidence: Float): Boolean = confidence < 0.55f || text.count { it.isLetter() } < 15
}
