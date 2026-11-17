package com.example.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contrast stretching, enlarging and orientation scoring used before recognition. */
class OcrPreprocessorTest {

    @Test
    fun luminance_ofBlackWhiteAndGrey() {
        assertEquals(0, OcrPreprocessor.luminance(0xFF000000.toInt()))
        assertEquals(255, OcrPreprocessor.luminance(0xFFFFFFFF.toInt()))
        assertEquals(150, OcrPreprocessor.luminance(0xFF969696.toInt()))
    }

    @Test
    fun lowContrastPage_isStretchedToFullRange() {
        // Grey text (150) on grey paper (205), as in a faded photocopy.
        val h = IntArray(256)
        h[150] = 5_000
        h[205] = 95_000
        val (lo, hi) = OcrPreprocessor.stretchRange(h)
        assertEquals(150, lo)
        assertEquals(205, hi)
        assertEquals(0, OcrPreprocessor.stretch(150, lo, hi))
        assertEquals(255, OcrPreprocessor.stretch(205, lo, hi))
        // A few specks darker than the text do not decide the range.
        h[0] = 10
        assertEquals(150, OcrPreprocessor.stretchRange(h).first)
    }

    @Test
    fun uniformImage_isLeftAsIs() {
        val h = IntArray(256)
        h[200] = 1000
        assertEquals(0 to 255, OcrPreprocessor.stretchRange(h))
        assertEquals(0 to 255, OcrPreprocessor.stretchRange(IntArray(256)))
    }

    @Test
    fun smallImagesAreEnlarged() {
        assertTrue(OcrPreprocessor.needsUpscale(800, 1100))
        assertTrue(OcrPreprocessor.needsUpscale(1000, 1500))
        assertFalse(OcrPreprocessor.needsUpscale(1240, 1754))
        assertFalse(OcrPreprocessor.needsUpscale(3000, 2000))
    }

    @Test
    fun arabicShare_decidesTheArabicFirstPass() {
        assertTrue(OcrPreprocessor.arabicShare("المياه الجوفية مصدر أساسي") > 0.9)
        assertTrue(OcrPreprocessor.arabicShare("Les eaux souterraines") == 0.0)
        assertTrue(OcrPreprocessor.arabicShare("المياه الجوفية eau") > 0.3)
        assertEquals(0.0, OcrPreprocessor.arabicShare("123 ..."), 0.0)
    }

    @Test
    fun weakResults_triggerAnotherOrientation() {
        assertTrue(OcrPreprocessor.isWeak("", 0.9f))
        assertTrue(OcrPreprocessor.isWeak("x.;- ,", 0.2f))
        assertTrue(OcrPreprocessor.isWeak("Groundwater is essential for the population", 0.3f))
        assertFalse(OcrPreprocessor.isWeak("Groundwater is essential for the population", 0.85f))
        assertTrue(OcrPreprocessor.score("Groundwater resources", 0.9f) > OcrPreprocessor.score("Gr0und", 0.4f))
    }
}
