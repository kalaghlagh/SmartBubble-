package com.example.offline.analysis

import com.example.offline.SourceSpan
import com.example.offline.TableKind

/** A table found in consecutive lines [startLine, endLine). */
data class TableMatch(
    val startLine: Int,
    val endLine: Int,
    val header: List<String>,
    val rows: List<List<String>>,
    val kind: TableKind,
    val confidence: Float,
    /** Markdown separator rows ("|---|---|"): structure, not content. */
    val markup: List<SourceSpan>
)

/**
 * Detects tables only when the structure is clear: Markdown pipe tables, tab-separated rows, or
 * at least three rows aligned in columns with two or more spaces. Every row must have the same
 * number of cells (short rows are padded with empty cells, never with invented text). Ordinary
 * paragraphs are never turned into tables.
 */
object TableDetectionEngine {

    const val ACCEPT = 0.7f

    private val SEPARATOR = Regex("""^\|?[ \t]*:?-{3,}:?[ \t]*(\|[ \t]*:?-{3,}:?[ \t]*)*\|?$""")
    private val COLUMN_GAP = Regex("""[ \t]{2,}|\t""")

    fun detect(lines: List<SourceLine>, from: Int): TableMatch? {
        val first = lines.getOrNull(from) ?: return null
        if (first.isBlank) return null
        val candidates = listOfNotNull(
            if ('|' in first.trimmed) pipes(lines, from) else null,
            if ('\t' in first.trimmed) tabs(lines, from) else null,
            if (first.trimmed.contains("  ")) aligned(lines, from) else null
        )
        return candidates.filter { it.confidence >= ACCEPT }.maxByOrNull { it.endLine - it.startLine }
    }

    private fun pipeCells(text: String): List<String>? {
        var t = text.trim()
        if (!t.contains('|')) return null
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.dropLast(1)
        val cells = t.split('|').map { it.trim() }
        return if (cells.size >= 2) cells else null
    }

    private fun pipes(lines: List<SourceLine>, from: Int): TableMatch? {
        val rows = ArrayList<List<String>>()
        val markup = ArrayList<SourceSpan>()
        var separator = false
        var i = from
        while (i < lines.size && !lines[i].isBlank) {
            val line = lines[i]
            if (SEPARATOR.matches(line.trimmed) && line.trimmed.contains('-')) {
                // Only the row right after the header is a separator.
                if (rows.size != 1 || separator) break
                separator = true
                markup.add(SourceSpan(line.contentStart, line.contentEnd))
            } else {
                val cells = pipeCells(line.text) ?: break
                rows.add(cells)
            }
            i++
        }
        val width = rows.maxOfOrNull { it.size } ?: return null
        if (rows.any { it.size != width && it.size != width - 1 }) return null
        val confidence = when {
            separator && rows.size >= 2 -> 0.95f
            rows.size >= 3 && rows.all { it.size == width } -> 0.8f
            else -> 0f
        }
        return build(from, i, rows, TableKind.PIPES, confidence, markup)
    }

    private fun tabs(lines: List<SourceLine>, from: Int): TableMatch? {
        val rows = ArrayList<List<String>>()
        var i = from
        while (i < lines.size && !lines[i].isBlank && lines[i].trimmed.contains('\t')) {
            rows.add(lines[i].trimmed.split('\t').map { it.trim() })
            i++
        }
        if (rows.size < 2) return null
        val width = rows[0].size
        if (width < 2 || rows.any { it.size != width }) return null
        return build(from, i, rows, TableKind.TABS, if (rows.size >= 3) 0.9f else 0.8f, emptyList())
    }

    private fun aligned(lines: List<SourceLine>, from: Int): TableMatch? {
        val rows = ArrayList<List<String>>()
        var i = from
        while (i < lines.size && !lines[i].isBlank) {
            val cells = lines[i].trimmed.split(COLUMN_GAP).map { it.trim() }.filter { it.isNotEmpty() }
            if (cells.size < 2) break
            if (rows.isNotEmpty() && cells.size != rows[0].size) break
            rows.add(cells)
            i++
        }
        if (rows.size < 3) return null
        val all = rows.flatten()
        // Prose separated by double spaces has long cells; table cells are short.
        if (all.any { it.length > 60 } || all.sumOf { it.length } > all.size * 25) return null
        if (all.any { TextTraits.endsWithTerminal(it) && it.length > 30 }) return null
        return build(from, i, rows, TableKind.ALIGNED_COLUMNS, 0.75f, emptyList())
    }

    private fun build(
        from: Int,
        end: Int,
        rows: List<List<String>>,
        kind: TableKind,
        confidence: Float,
        markup: List<SourceSpan>
    ): TableMatch? {
        if (rows.size < 2 || confidence <= 0f) return null
        val width = rows.maxOf { it.size }
        val padded = rows.map { r -> if (r.size < width) r + List(width - r.size) { "" } else r }
        return TableMatch(from, end, padded.first(), padded.drop(1), kind, confidence, markup)
    }
}
