package com.example.extraction

import kotlin.math.cos
import kotlin.math.sin

/**
 * Skew of a page photographed or scanned at a slight angle: the angle at which the dark pixels
 * line up best into horizontal text rows (sharpest row profile).
 */
object Deskew {

    /**
     * Angle in degrees in [-maxAngle, maxAngle] (positive = text lines going down to the right,
     * in image coordinates); rotating the image by minus this angle straightens it. 0 when no
     * angle is clearly better than 0. [gray] is row-major, 0 = black.
     */
    fun estimate(gray: IntArray, width: Int, height: Int, maxAngle: Float = 5f, step: Float = 0.25f): Float {
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        val stride = maxOf(1, (width * height) / 60_000)
        var i = 0
        while (i < gray.size) {
            if (gray[i] < 110) { xs += i % width; ys += i / width }
            i += stride
        }
        if (xs.size < 200) return 0f
        fun score(deg: Float): Double {
            val r = Math.toRadians(deg.toDouble())
            val s = sin(r)
            val c = cos(r)
            val bins = HashMap<Int, Int>()
            for (k in xs.indices) {
                val y = (ys[k] * c - xs[k] * s).toInt()
                bins[y] = (bins[y] ?: 0) + 1
            }
            return bins.values.sumOf { it.toDouble() * it }
        }
        val base = score(0f)
        var best = 0f
        var bestScore = base
        var a = -maxAngle
        while (a <= maxAngle + 1e-3) {
            val sc = score(a)
            if (sc > bestScore) { bestScore = sc; best = a }
            a += step
        }
        return if (bestScore > base * 1.05) best else 0f
    }
}
