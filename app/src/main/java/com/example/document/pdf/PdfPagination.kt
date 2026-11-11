package com.example.document.pdf

/**
 * Pure line-splitting logic used by [PdfEngine] so text taller than the remaining page space is
 * continued on following pages instead of being drawn past the page edge (and silently lost).
 * Free of Android types so it can be unit-tested on the JVM.
 */
object PdfPagination {

    /**
     * A run of consecutive lines drawn on one page.
     * @property startLine first line (inclusive)
     * @property endLine last line (exclusive)
     * @property startsNewPage the caller must start a new page before drawing this chunk
     */
    data class LineChunk(val startLine: Int, val endLine: Int, val startsNewPage: Boolean)

    /**
     * Splits lines into page-sized chunks.
     *
     * @param lineBoundaries y-offsets of every line top followed by the bottom of the last line
     *   (size = lineCount + 1, non-decreasing), e.g. from StaticLayout.getLineTop/getLineBottom.
     * @param availableOnCurrentPage vertical space left on the current page.
     * @param availableOnFreshPage vertical space on an empty page.
     *
     * Guarantees every line appears in exactly one chunk, in order. A single line taller than a
     * whole page is still emitted (alone, on its own page) rather than dropped.
     */
    fun splitLines(
        lineBoundaries: IntArray,
        availableOnCurrentPage: Float,
        availableOnFreshPage: Float
    ): List<LineChunk> {
        require(availableOnFreshPage > 0f) { "availableOnFreshPage must be positive" }
        val lineCount = lineBoundaries.size - 1
        if (lineCount <= 0) return emptyList()

        val chunks = mutableListOf<LineChunk>()
        var available = availableOnCurrentPage
        var atPageTop = availableOnCurrentPage >= availableOnFreshPage
        var pendingNewPage = false
        var line = 0

        while (line < lineCount) {
            val top = lineBoundaries[line]
            var end = line
            while (end < lineCount && lineBoundaries[end + 1] - top <= available) end++

            if (end == line) {
                if (!atPageTop) {
                    // Not even one line fits here: continue on a fresh page.
                    pendingNewPage = true
                    atPageTop = true
                    available = availableOnFreshPage
                    continue
                }
                end = line + 1 // a single line taller than a page: emit it anyway
            }

            chunks += LineChunk(line, end, pendingNewPage)
            line = end
            if (line < lineCount) {
                pendingNewPage = true
                atPageTop = true
                available = availableOnFreshPage
            }
        }
        return chunks
    }

    /**
     * Whether a block of [blockHeight] should move to a fresh page before being drawn: true when
     * it does not fit in the remaining space but would fit entirely on a fresh page (this keeps
     * the existing "don't split short paragraphs" behaviour). Taller blocks are split instead.
     */
    fun shouldMoveWholeBlockToNextPage(
        blockHeight: Float,
        availableOnCurrentPage: Float,
        availableOnFreshPage: Float
    ): Boolean = blockHeight > availableOnCurrentPage &&
        blockHeight <= availableOnFreshPage &&
        availableOnCurrentPage < availableOnFreshPage
}
