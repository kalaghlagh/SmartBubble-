package com.example.offline

/**
 * Checks that a [FormattingResult] keeps every word of the source, in the same order, and adds
 * none. Words are compared exactly (no case folding, no Unicode normalization); only whitespace
 * and the recorded structure markers (bullet characters, Markdown "#", table separator rows)
 * may differ. Table cell separators ("|", tabs) are treated as whitespace on both sides.
 */
object ContentPreservation {

    private const val MAX_LISTED = 20

    fun tokens(text: CharSequence): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (c.isWhitespace() || c == '|') {
                if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
            } else sb.append(c)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Source text with the structure markers blanked out. */
    fun contentOf(source: String, removedMarkup: List<SourceSpan>): CharSequence {
        if (removedMarkup.isEmpty()) return source
        val chars = StringBuilder(source)
        for (span in removedMarkup) {
            for (i in span.start until span.end.coerceAtMost(chars.length)) chars.setCharAt(i, ' ')
        }
        return chars
    }

    /** The text of the blocks in reading order, as it will appear in the document. */
    fun blockTexts(blocks: List<StructureBlock>): Sequence<String> = sequence {
        for (block in blocks) {
            when (block) {
                is StructureBlock.Heading -> yield(block.text)
                is StructureBlock.Paragraph -> yield(block.text)
                is StructureBlock.ListBlock -> block.items.forEach { yield(it.displayText(block.ordered)) }
                is StructureBlock.Table -> {
                    yieldAll(block.header)
                    block.rows.forEach { yieldAll(it) }
                }
            }
        }
    }

    fun verify(source: String, removedMarkup: List<SourceSpan>, blocks: List<StructureBlock>): PreservationReport {
        val expected = tokens(contentOf(source, removedMarkup))
        val actual = blockTexts(blocks).flatMap { tokens(it).asSequence() }.toList()
        if (expected == actual) return PreservationReport(expected.size, emptyList(), emptyList(), numbersPreserved = true)
        return PreservationReport(
            sourceWordCount = expected.size,
            missing = multisetMinus(expected, actual),
            added = multisetMinus(actual, expected),
            numbersPreserved = numbers(expected) == numbers(actual)
        ).let {
            // Same words in a different order is also a failure.
            if (it.missing.isEmpty() && it.added.isEmpty()) it.copy(missing = listOf("(word order changed)")) else it
        }
    }

    /** Tokens holding a digit (numbers, dates, measurements, amounts), in order. */
    fun numbers(tokens: List<String>): List<String> = tokens.filter { t -> t.any { it.isDigit() } }

    private fun multisetMinus(a: List<String>, b: List<String>): List<String> {
        val counts = HashMap<String, Int>()
        b.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val out = ArrayList<String>()
        for (t in a) {
            val n = counts[t] ?: 0
            if (n > 0) counts[t] = n - 1 else if (out.size < MAX_LISTED) out.add(t)
        }
        return out
    }
}
