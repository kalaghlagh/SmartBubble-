package com.example

import com.example.document.pdf.PdfPagination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PdfPaginationTest {

    /** Boundaries for [count] lines of equal [height]: 0, h, 2h, ... count*h. */
    private fun uniform(count: Int, height: Int) = IntArray(count + 1) { it * height }

    private fun assertCoversAllLinesOnce(chunks: List<PdfPagination.LineChunk>, lineCount: Int) {
        var expected = 0
        for (c in chunks) {
            assertEquals("chunks must be contiguous", expected, c.startLine)
            assertTrue("chunk must be non-empty", c.endLine > c.startLine)
            expected = c.endLine
        }
        assertEquals("every line must be drawn exactly once", lineCount, expected)
    }

    @Test
    fun shortTextFitsOnCurrentPage() {
        val chunks = PdfPagination.splitLines(uniform(5, 14), availableOnCurrentPage = 300f, availableOnFreshPage = 700f)
        assertEquals(listOf(PdfPagination.LineChunk(0, 5, startsNewPage = false)), chunks)
    }

    @Test
    fun textTallerThanAPageIsSplitAcrossPagesWithoutLoss() {
        // 500 lines x 14pt = 7000pt of text; a fresh page holds 700pt -> 50 lines per page.
        val lines = 500
        val chunks = PdfPagination.splitLines(uniform(lines, 14), availableOnCurrentPage = 140f, availableOnFreshPage = 700f)

        assertCoversAllLinesOnce(chunks, lines)
        assertFalse(chunks.first().startsNewPage)
        assertEquals(10, chunks.first().endLine) // fills the remaining 140pt first
        chunks.drop(1).forEach { assertTrue(it.startsNewPage) }
        chunks.drop(1).dropLast(1).forEach { assertEquals(50, it.endLine - it.startLine) }
        assertEquals(1 + 490 / 50 + 1, chunks.size) // 10 + 9*50 + 40
    }

    @Test
    fun noSpaceLeftStartsOnFreshPage() {
        val chunks = PdfPagination.splitLines(uniform(3, 14), availableOnCurrentPage = 5f, availableOnFreshPage = 700f)
        assertEquals(listOf(PdfPagination.LineChunk(0, 3, startsNewPage = true)), chunks)
    }

    @Test
    fun singleLineTallerThanPageIsStillEmitted() {
        val boundaries = intArrayOf(0, 10, 2_000, 2_010) // middle line is 1990pt tall
        val chunks = PdfPagination.splitLines(boundaries, availableOnCurrentPage = 100f, availableOnFreshPage = 700f)
        assertCoversAllLinesOnce(chunks, 3)
        assertTrue(chunks.any { it.startLine == 1 && it.endLine == 2 })
    }

    @Test
    fun randomizedLayoutsNeverLoseOrOverflowLines() {
        val rnd = Random(42)
        repeat(500) {
            val count = rnd.nextInt(1, 400)
            val b = IntArray(count + 1)
            for (i in 1..count) b[i] = b[i - 1] + rnd.nextInt(8, 40)
            val fresh = rnd.nextInt(200, 800).toFloat()
            val current = rnd.nextInt(0, fresh.toInt() + 1).toFloat()

            val chunks = PdfPagination.splitLines(b, current, fresh)
            assertCoversAllLinesOnce(chunks, count)

            chunks.forEachIndexed { idx, c ->
                val height = b[c.endLine] - b[c.startLine]
                val limit = if (idx == 0 && !c.startsNewPage) current else fresh
                val singleOversizedLine = c.endLine - c.startLine == 1 && height > fresh
                assertTrue("chunk $idx ($height) exceeds page space $limit", height <= limit || singleOversizedLine)
                if (idx > 0) assertTrue(c.startsNewPage)
            }
        }
    }

    @Test
    fun emptyLayoutProducesNoChunks() {
        assertTrue(PdfPagination.splitLines(IntArray(1), 100f, 700f).isEmpty())
    }

    @Test
    fun shortBlocksMoveWholeButOversizedBlocksSplit() {
        assertTrue(PdfPagination.shouldMoveWholeBlockToNextPage(200f, availableOnCurrentPage = 100f, availableOnFreshPage = 700f))
        assertFalse(PdfPagination.shouldMoveWholeBlockToNextPage(50f, availableOnCurrentPage = 100f, availableOnFreshPage = 700f))
        assertFalse(PdfPagination.shouldMoveWholeBlockToNextPage(5_000f, availableOnCurrentPage = 100f, availableOnFreshPage = 700f))
        // Already at the top of a fresh page: never move (would loop forever).
        assertFalse(PdfPagination.shouldMoveWholeBlockToNextPage(800f, availableOnCurrentPage = 700f, availableOnFreshPage = 700f))
    }
}
