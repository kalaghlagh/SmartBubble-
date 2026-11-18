package com.example.ocr

/**
 * Whether the text of a page runs vertically (a page photographed or scanned turned by 90 or 270
 * degrees), from its ink alone. Horizontal text lines leave empty rows between them and few
 * empty columns inside the text block; turned by a quarter, the same page leaves empty columns
 * and few empty rows. Only a hint: the page is turned only when the reading of the turned page
 * is clearly better (see [com.example.extraction.OcrPageReader]).
 */
object OrientationProfile {

    /** Share of empty rows and of empty columns inside the ink's bounding box of a grey page. */
    private fun emptyShares(gray: IntArray, width: Int, height: Int, dark: Int = 128): Pair<Double, Double>? {
        val step = maxOf(1, maxOf(width, height) / 800)
        val w = width / step
        val h = height / step
        if (w < 8 || h < 8) return null
        val rows = IntArray(h)
        val cols = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) if (gray[(y * step) * width + x * step] < dark) { rows[y]++; cols[x]++ }
        val top = rows.indexOfFirst { it > 0 }
        val bottom = rows.indexOfLast { it > 0 }
        val left = cols.indexOfFirst { it > 0 }
        val right = cols.indexOfLast { it > 0 }
        if (top < 0 || left < 0 || bottom - top < 8 || right - left < 8) return null
        val boxW = right - left + 1
        val boxH = bottom - top + 1
        // One line of text: its shape says the direction.
        if (boxW > boxH * 4) return 0.5 to 0.0
        if (boxH > boxW * 4) return 0.0 to 0.5
        // Rows/columns crossed by a ruling or a picture count as non-empty; a speck does not
        // make a row non-empty.
        val emptyRows = (top..bottom).count { rows[it] <= 1 }.toDouble() / (bottom - top + 1)
        val emptyCols = (left..right).count { cols[it] <= 1 }.toDouble() / (right - left + 1)
        return emptyRows to emptyCols
    }

    /** Empty columns over empty rows (above 1: more column gaps than line gaps). */
    fun ratio(gray: IntArray, width: Int, height: Int): Double {
        val (r, c) = emptyShares(gray, width, height) ?: return 0.0
        return (c + 0.01) / (r + 0.01)
    }

    /** The text seems to run vertically: try the page turned by a quarter. */
    fun verticalText(gray: IntArray, width: Int, height: Int): Boolean {
        val (r, c) = emptyShares(gray, width, height) ?: return false
        return c >= 0.15 && c > r * 1.5
    }
}
