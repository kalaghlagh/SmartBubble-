package com.example.thesis.core

/** A run of text with its character formatting. */
data class FormattedRun(val text: String, val bold: Boolean = false, val italic: Boolean = false, val underline: Boolean = false)

/**
 * Character formatting typed in the section editor: **bold**, *italic* and __underline__ (they
 * can be combined, e.g. **__text__**). The markers are kept in the stored text, so formatting is
 * saved with the section; documents and the preview draw the runs without the markers.
 * A marker only counts when it encloses non-space text (so "5 * 3" stays as written).
 */
object InlineFormat {

    // ***x*** (bold and italic) comes first: with only ** and * the parser read it as bold "*x"
    // followed by a stray "*". The optional tail of each body is lazy ("??"): with a greedy "?" a
    // one-letter body ("**a** and **b**") ran on to a later marker.
    private val MARK = Regex("""\*\*\*(\S(?:.*?\S)??)\*\*\*|\*\*(\S(?:.*?\S)??)\*\*|__(\S(?:.*?\S)??)__|(?<![*\w])\*(\S(?:.*?\S)??)\*(?![*\w])""")

    /** A visible character of the stored text: its index in the stored text and its formatting. */
    data class Cell(val index: Int, val ch: Char, val bold: Boolean, val italic: Boolean, val underline: Boolean)

    /** Every visible character of [text] (markers removed) with its position in [text]. */
    fun cells(text: String): List<Cell> {
        val out = ArrayList<Cell>(text.length)
        cellsInto(text, 0, false, false, false, out, 0)
        return out
    }

    private fun plainCells(text: String, from: Int, to: Int, offset: Int, b: Boolean, i: Boolean, u: Boolean, out: MutableList<Cell>) {
        for (k in from until to) out.add(Cell(offset + k, text[k], b, i, u))
    }

    private fun cellsInto(text: String, offset: Int, bold: Boolean, italic: Boolean, underline: Boolean, out: MutableList<Cell>, depth: Int) {
        if (depth > 4) { plainCells(text, 0, text.length, offset, bold, italic, underline, out); return }
        var pos = 0
        for (m in MARK.findAll(text)) {
            if (m.range.first > pos) plainCells(text, pos, m.range.first, offset, bold, italic, underline, out)
            val g = (1..4).first { m.groups[it] != null }
            val inner = m.groups[g]!!
            val at = offset + inner.range.first
            when (g) {
                1 -> cellsInto(inner.value, at, true, true, underline, out, depth + 1)
                2 -> cellsInto(inner.value, at, true, italic, underline, out, depth + 1)
                3 -> cellsInto(inner.value, at, bold, italic, true, out, depth + 1)
                else -> cellsInto(inner.value, at, bold, true, underline, out, depth + 1)
            }
            pos = m.range.last + 1
        }
        if (pos < text.length) plainCells(text, pos, text.length, offset, bold, italic, underline, out)
    }

    fun parse(text: String): List<FormattedRun> {
        // Neighbours with the same formatting form one run.
        val merged = ArrayList<FormattedRun>()
        val sb = StringBuilder()
        var cur: Cell? = null
        for (c in cells(text)) {
            val p = cur
            if (p != null && (p.bold != c.bold || p.italic != c.italic || p.underline != c.underline)) {
                merged.add(FormattedRun(sb.toString(), p.bold, p.italic, p.underline)); sb.setLength(0)
            }
            sb.append(c.ch); cur = c
        }
        cur?.let { if (sb.isNotEmpty()) merged.add(FormattedRun(sb.toString(), it.bold, it.italic, it.underline)) }
        return merged
    }

    /**
     * Markup for [chars] with per-character formatting, written so that [cells] reads it back:
     * whitespace at the edges of a formatted run stays outside its markers, bold+italic is ***x***,
     * underline sits inside the stars (***__x__***).
     */
    fun serialize(chars: List<Cell>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            var j = i
            while (j < chars.size && chars[j].bold == c.bold && chars[j].italic == c.italic && chars[j].underline == c.underline) j++
            val run = chars.subList(i, j).map { it.ch }.joinToString("")
            if (!(c.bold || c.italic || c.underline) || run.isBlank()) sb.append(run)
            else {
                val core = run.trim()
                val lead = run.substring(0, run.indexOf(core))
                val trail = run.substring(lead.length + core.length)
                val stars = if (c.bold && c.italic) "***" else if (c.bold) "**" else if (c.italic) "*" else ""
                val under = if (c.underline) "__" else ""
                sb.append(lead).append(stars).append(under).append(core).append(under).append(stars).append(trail)
            }
            i = j
        }
        return sb.toString()
    }

    /** The text without formatting markers (titles, indexes, search). */
    fun plain(text: String): String = parse(text).joinToString("") { it.text }

    /** True when [text] contains formatting markers. */
    fun hasMarkers(text: String): Boolean = MARK.containsMatchIn(text)
}
