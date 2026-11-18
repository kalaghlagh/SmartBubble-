package com.example.ui.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Size of the drawn PDF pages and of their cache. */
class PdfPageGeometryTest {

    private fun ratio(p: Pair<Int, Int>) = p.first.toDouble() / p.second

    @Test
    fun portraitPage_fitsTheWidth_keepsItsProportions() {
        val s = PdfPageGeometry.renderSize(595, 842, 1000)
        assertEquals(1000, s.first)
        assertEquals(1415, s.second)
        assertTrue(abs(ratio(s) - 595.0 / 842) < 0.002)
    }

    @Test
    fun landscapePage_fitsTheWidth_keepsItsProportions() {
        val s = PdfPageGeometry.renderSize(842, 595, 1000)
        assertEquals(1000, s.first)
        assertTrue(abs(ratio(s) - 842.0 / 595) < 0.002)
    }

    @Test
    fun longestSide_isCapped_proportionsKept() {
        // A very tall page (receipt) at a wide screen: the height is capped, not the proportions lost.
        val tall = PdfPageGeometry.renderSize(200, 4000, 1440)
        assertEquals(PdfPageGeometry.MAX_SIDE, tall.second)
        assertTrue(abs(ratio(tall) - 200.0 / 4000) < 0.01)
        val wide = PdfPageGeometry.renderSize(842, 595, 5000)
        assertEquals(PdfPageGeometry.MAX_SIDE, wide.first)
        assertTrue(abs(ratio(wide) - 842.0 / 595) < 0.002)
        // One page bitmap stays under 16 MB (ARGB_8888).
        assertTrue(wide.first.toLong() * wide.second * 4 <= 16L * 1024 * 1024)
    }

    @Test
    fun tinyOrZeroWidth_neverGivesAnEmptyBitmap() {
        val s = PdfPageGeometry.renderSize(595, 842, 0)
        assertTrue(s.first >= 1 && s.second >= 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyPage_isRejected() { PdfPageGeometry.renderSize(0, 842, 100) }

    @Test
    fun cache_isAnEighthOfTheHeap_between8And64MB() {
        val mb = 1024L * 1024
        assertEquals((256 * mb / 8).toInt(), PdfPageGeometry.cacheBytes(256 * mb))
        assertEquals((8 * mb).toInt(), PdfPageGeometry.cacheBytes(32 * mb))
        assertEquals((64 * mb).toInt(), PdfPageGeometry.cacheBytes(4096 * mb))
    }
}
