package com.example.offline.analysis

import com.example.offline.ListItem
import com.example.offline.SourceSpan
import com.example.offline.TextNormalizationEngine

/** A list marker found at the start of a line. */
data class ListMarker(
    val ordered: Boolean,
    /** The marker exactly as written ("-", "•", "1.", "(2)", "b)", "٣-"). */
    val marker: String,
    /** Same family of markers continues the same list ("bullet:-", "num:.", "lower:)"...). */
    val style: String,
    /** 1-based position for ordered markers (a=1, b=2 ...), null for bullets. */
    val ordinal: Int?,
    val markerStart: Int,
    val markerEnd: Int,
    val contentStart: Int,
    val indent: Int
)

/**
 * A run of consecutive list items.
 * @property bulletMarkers source spans of bullet characters (structure markers, not content)
 */
data class ListRun(
    val ordered: Boolean,
    val items: List<ListItem>,
    val endLine: Int,
    val sequential: Boolean,
    val bulletMarkers: List<SourceSpan>
)

/**
 * Recognizes bulleted and numbered lists. Item order and wording are kept; bullet characters
 * are structure (the document shows its own bullet), while the numbers of numbered items are
 * kept in the item text exactly as written. A single marked line is not a list.
 */
object ListDetectionEngine {

    private val BULLET = Regex("""^([-*+•‣◦▪●·–—])[ \t]+\S""")
    private val NUMBERED = Regex("""^(\d{1,3}|[٠-٩]{1,3})([.)]|[ \t]*[-–])[ \t]+\S""")
    private val PAREN_NUMBERED = Regex("""^\((\d{1,3}|[a-zA-Z])\)[ \t]+\S""")
    private val LETTERED = Regex("""^([a-zA-Z])([.)])[ \t]+\S""")

    fun match(line: SourceLine): ListMarker? {
        if (line.isBlank) return null
        val body = line.text.substring(line.text.length - line.text.trimStart().length)
        val offset = line.contentStart

        BULLET.find(body)?.let { m ->
            val marker = m.groupValues[1]
            return ListMarker(false, marker, "bullet:$marker", null, offset, offset + marker.length,
                contentAfter(body, marker.length, offset), line.indent)
        }
        NUMBERED.find(body)?.let { m ->
            val number = m.groupValues[1]
            val sep = m.groupValues[2]
            val marker = (number + sep).trimEnd()
            return ListMarker(true, marker, "num:${sep.trim()}", toInt(number), offset, offset + marker.length,
                contentAfter(body, marker.length, offset), line.indent)
        }
        PAREN_NUMBERED.find(body)?.let { m ->
            val v = m.groupValues[1]
            val marker = "($v)"
            val ordinal = v.toIntOrNull() ?: letterOrdinal(v[0])
            val style = if (v[0].isDigit()) "paren:num" else if (v[0].isUpperCase()) "paren:upper" else "paren:lower"
            return ListMarker(true, marker, style, ordinal, offset, offset + marker.length,
                contentAfter(body, marker.length, offset), line.indent)
        }
        LETTERED.find(body)?.let { m ->
            val letter = m.groupValues[1][0]
            val marker = m.groupValues[1] + m.groupValues[2]
            val style = (if (letter.isUpperCase()) "upper" else "lower") + ":" + m.groupValues[2]
            return ListMarker(true, marker, style, letterOrdinal(letter), offset, offset + marker.length,
                contentAfter(body, marker.length, offset), line.indent)
        }
        return null
    }

    private fun contentAfter(body: String, markerLength: Int, offset: Int): Int {
        var i = markerLength
        while (i < body.length && (body[i] == ' ' || body[i] == '\t')) i++
        return offset + i
    }

    private fun letterOrdinal(c: Char) = c.lowercaseChar() - 'a' + 1

    private fun toInt(number: String): Int? =
        number.map { if (it in '٠'..'٩') '0' + (it - '٠') else it }.joinToString("").toIntOrNull()

    /**
     * Collects the list starting at [from] (which must hold a list marker). Indented lines
     * without a marker continue the previous item; a blank line ends the list unless the next
     * line continues it with the same kind of marker.
     */
    fun collect(lines: List<SourceLine>, from: Int, source: String): ListRun? {
        val first = match(lines[from]) ?: return null
        val baseIndent = first.indent
        data class Building(val marker: ListMarker, val level: Int, var endOffset: Int, val parts: MutableList<String>)

        val items = ArrayList<Building>()
        var i = from
        var lastTopOrdinal: Int? = null
        var sequential = true
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank) {
                // A blank line ends the list unless an item of the same list follows.
                val next = lines.getOrNull(i + 1)?.let { match(it) }
                if (next != null && (next.style == first.style || next.indent > baseIndent + 1)) {
                    i++
                    continue
                }
                break
            }
            val marker = match(line)
            if (marker != null) {
                val nested = marker.indent > baseIndent + 1
                if (!nested && marker.style != first.style) break
                if (!nested && first.ordered) {
                    val expected = lastTopOrdinal?.plus(1)
                    if (expected != null && marker.ordinal != expected) sequential = false
                    lastTopOrdinal = marker.ordinal
                }
                val level = if (nested) ((marker.indent - baseIndent) / 2).coerceIn(1, 3) else 0
                items.add(Building(marker, level, line.contentEnd, mutableListOf(source.substring(marker.contentStart, line.contentEnd))))
            } else if (items.isNotEmpty() && line.indent > baseIndent && lines[i - 1].isBlank.not()) {
                // Indented continuation of the previous item.
                items.last().parts.add(line.trimmed)
                items.last().endOffset = line.contentEnd
            } else {
                break
            }
            i++
        }
        // Do not swallow trailing blank lines.
        var end = i
        while (end > from && lines[end - 1].isBlank) end--
        if (items.size < 2) return null
        val listItems = items.map { b ->
            ListItem(
                marker = b.marker.marker,
                text = TextNormalizationEngine.collapseSpaces(b.parts.joinToString(" ")),
                level = b.level,
                span = SourceSpan(b.marker.markerStart, b.endOffset)
            )
        }
        val bulletMarkers = items.filter { !it.marker.ordered }.map { SourceSpan(it.marker.markerStart, it.marker.markerEnd) }
        return ListRun(first.ordered, listItems, end, sequential, bulletMarkers)
    }

    /** Confidence of a detected run. */
    fun confidence(run: ListRun): Float = when {
        !run.ordered -> 0.95f
        run.sequential -> 0.95f
        else -> 0.7f
    }
}
