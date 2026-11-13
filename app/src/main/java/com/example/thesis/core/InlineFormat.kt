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

    private val MARK = Regex("""\*\*(\S(?:.*?\S)?)\*\*|__(\S(?:.*?\S)?)__|(?<![*\w])\*(\S(?:.*?\S)?)\*(?![*\w])""")

    fun parse(text: String): List<FormattedRun> {
        val out = ArrayList<FormattedRun>()
        parseInto(text, false, false, false, out, 0)
        // Merge neighbours with the same formatting.
        val merged = ArrayList<FormattedRun>()
        for (r in out) {
            if (r.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.bold == r.bold && last.italic == r.italic && last.underline == r.underline) merged[merged.lastIndex] = last.copy(text = last.text + r.text)
            else merged.add(r)
        }
        return merged
    }

    private fun parseInto(text: String, bold: Boolean, italic: Boolean, underline: Boolean, out: MutableList<FormattedRun>, depth: Int) {
        if (depth > 4) { out.add(FormattedRun(text, bold, italic, underline)); return }
        var pos = 0
        for (m in MARK.findAll(text)) {
            if (m.range.first > pos) out.add(FormattedRun(text.substring(pos, m.range.first), bold, italic, underline))
            when {
                m.groups[1] != null -> parseInto(m.groupValues[1], true, italic, underline, out, depth + 1)
                m.groups[2] != null -> parseInto(m.groupValues[2], bold, italic, true, out, depth + 1)
                else -> parseInto(m.groupValues[3], bold, true, underline, out, depth + 1)
            }
            pos = m.range.last + 1
        }
        if (pos < text.length) out.add(FormattedRun(text.substring(pos), bold, italic, underline))
    }

    /** The text without formatting markers (titles, indexes, search). */
    fun plain(text: String): String = parse(text).joinToString("") { it.text }

    /** True when [text] contains formatting markers. */
    fun hasMarkers(text: String): Boolean = MARK.containsMatchIn(text)
}
