package com.example.thesis.core

/** Text of the section editor with its selection ([start] <= [end]). */
data class EditorText(val text: String, val start: Int, val end: Int) {
    init { require(start in 0..end && end <= text.length) { "Bad selection" } }
    val selected: String get() = text.substring(start, end)

    companion object {
        fun of(text: String, a: Int, b: Int): EditorText {
            val s = minOf(a, b).coerceIn(0, text.length)
            val e = maxOf(a, b).coerceIn(0, text.length)
            return EditorText(text, s, e)
        }
    }
}

/**
 * Formatting commands of the section editor toolbar. Formatting is stored as light markup that the
 * Word and PDF exports draw: **bold**, *italic*, __underline__, "#" headings, "- " / "1. " lists,
 * "{center}" / "{end}" / "{justify}" alignment and "[Page break]" lines. Pure functions, so every
 * button can be tested without a device.
 */
object EditorOps {

    /** Character formatting of the toolbar. */
    enum class Format { BOLD, ITALIC, UNDERLINE }

    /** Toolbar marker ("**", "*", "__") to its format; kept for callers that name the marker. */
    fun toggleWrap(t: EditorText, marker: String): EditorText = toggleFormat(t, when (marker) {
        "**" -> Format.BOLD
        "*" -> Format.ITALIC
        "__" -> Format.UNDERLINE
        else -> throw IllegalArgumentException("Unknown marker $marker")
    })

    // Start of a line that formatting never touches: heading, alignment and list markers.
    private val LINE_PREFIX = Regex("""^(#{1,6}\s+)?([-•*]\s+|\d{1,3}[.)]\s+)?(\{(center|end|justify)\}\s?)?([-•*]\s+|\d{1,3}[.)]\s+)?""")
    // Lines that stand for a figure, table or page break ("[Figure 1]", "[Page break]").
    private val PLACEHOLDER = Regex("""^\[[^\[\]\n]{1,60}]$""")

    private fun has(c: InlineFormat.Cell, f: Format) = when (f) { Format.BOLD -> c.bold; Format.ITALIC -> c.italic; Format.UNDERLINE -> c.underline }
    private fun with(c: InlineFormat.Cell, f: Format, on: Boolean) = when (f) {
        Format.BOLD -> c.copy(bold = on); Format.ITALIC -> c.copy(italic = on); Format.UNDERLINE -> c.copy(underline = on)
    }

    /**
     * Bold, italic or underline on the selected words, worked out on the visible characters (not on
     * the markers next to the selection), so formats combine (***__word__***) and toggle off one by
     * one. Whole words are formatted: the stored markup cannot mark part of a word in every
     * combination. With only a caret, the word under the caret is formatted; outside any word
     * nothing changes. Heading, alignment and list markers at the start of a line and placeholder
     * lines are never formatted. The new markup is read back before it is used: if it would not
     * give exactly the intended formatting, IllegalStateException is thrown and the caller keeps
     * the text unchanged.
     */
    fun toggleFormat(t: EditorText, format: Format): EditorText {
        val text = t.text
        val r = lineRange(text, t.start, t.end)
        val rangeEnd = r.last + 1
        // Touched lines: [start, end) in text, end of the untouchable prefix, global cell indexes.
        class Line(val start: Int, val end: Int, val prefixEnd: Int, val from: Int, val to: Int)
        val cells = ArrayList<InlineFormat.Cell>()
        val lines = ArrayList<Line>()
        var ls = r.first
        while (true) {
            val le = text.indexOf('\n', ls).let { if (it < 0 || it > rangeEnd) rangeEnd else it }
            val raw = text.substring(ls, le)
            val prefix = if (PLACEHOLDER.matches(raw.trim())) raw.length else LINE_PREFIX.find(raw)?.value?.length ?: 0
            val from = cells.size
            InlineFormat.cells(raw.substring(prefix)).forEach { cells.add(it.copy(index = it.index + ls + prefix)) }
            lines.add(Line(ls, le, ls + prefix, from, cells.size))
            if (le >= rangeEnd) break
            ls = le + 1
        }
        fun ws(k: Int) = cells[k].ch.isWhitespace()
        val chosen = BooleanArray(cells.size)
        for (l in lines) {
            val picked = if (t.start == t.end) {
                // The word under the caret, on the caret's line: the visible character after the
                // caret, else the one before it.
                if (t.start < l.start || t.start > l.end) emptyList() else {
                    val k = (l.from until l.to).firstOrNull { cells[it].index >= t.start } ?: l.to
                    listOf(k, k - 1).filter { it in l.from until l.to && !ws(it) }.take(1)
                }
            } else (l.from until l.to).filter { cells[it].index >= t.start && cells[it].index < t.end && !ws(it) }
            if (picked.isEmpty()) continue
            // Grow to whole words, then join the words of this line into one range.
            var a = picked.first(); while (a - 1 >= l.from && !ws(a - 1)) a--
            var b = picked.last(); while (b + 1 < l.to && !ws(b + 1)) b++
            for (k in a..b) chosen[k] = true
        }
        if (chosen.none { it }) return t
        val turnOn = !cells.indices.filter { chosen[it] && !ws(it) }.all { has(cells[it], format) }
        val target = cells.mapIndexed { k, c -> if (chosen[k]) with(c, format, turnOn) else c }
        // Rebuild the touched lines; other lines and the prefixes are copied as they are.
        val sb = StringBuilder(text.substring(0, r.first))
        var selStart = -1
        var selEnd = -1
        lines.forEachIndexed { li, l ->
            if (li > 0) sb.append('\n')
            sb.append(text, l.start, l.prefixEnd)
            val mine = target.subList(l.from, l.to)
            val body = InlineFormat.serialize(mine)
            // Read back: the same characters, and every visible letter with the intended format
            // (spaces at the edge of a run are written outside its markers).
            val back = InlineFormat.cells(body)
            val ok = back.size == mine.size && back.indices.all { k ->
                back[k].ch == mine[k].ch && (mine[k].ch.isWhitespace() ||
                    (back[k].bold == mine[k].bold && back[k].italic == mine[k].italic && back[k].underline == mine[k].underline))
            }
            check(ok) { "Formatting could not be written for this text" }
            val base = sb.length
            for (k in mine.indices) if (chosen[l.from + k]) {
                val pos = base + back[k].index
                if (selStart < 0) selStart = pos
                selEnd = pos + 1
            }
            sb.append(body)
        }
        sb.append(text, rangeEnd, text.length)
        val out = sb.toString()
        return if (selStart < 0) EditorText.of(out, t.start, t.start) else EditorText(out, selStart, selEnd)
    }

    private fun lineRange(text: String, start: Int, end: Int): IntRange {
        val ls = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0 || it < 0) 0 else it + 1 }
        val le = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        return ls until le
    }

    /** Applies [f] to every line touched by the selection; the selection covers the changed lines. */
    private fun mapLines(t: EditorText, f: (index: Int, line: String) -> String): EditorText {
        val r = lineRange(t.text, t.start, t.end)
        val lines = t.text.substring(r.first, r.last + 1).split('\n')
        val changed = lines.mapIndexed { i, l -> f(i, l) }.joinToString("\n")
        val text = t.text.substring(0, r.first) + changed + t.text.substring(r.last + 1)
        return if (t.start == t.end) {
            val caret = (r.first + changed.length).coerceAtMost(text.length)
            EditorText(text, caret, caret)
        } else EditorText(text, r.first, r.first + changed.length)
    }

    private val HEADING = Regex("""^#{1,6}\s+""")
    private val BULLET = Regex("""^[-•*]\s+""")
    private val NUMBER = Regex("""^\d{1,3}[.)]\s+""")
    // "}" is escaped: Android's ICU regex engine rejects a bare "}" (the desktop JVM accepts it).
    private val ALIGN = Regex("""^\{(center|end|justify)\}\s?""")

    private fun stripList(l: String) = l.replaceFirst(BULLET, "").replaceFirst(NUMBER, "")

    /** "- " on each selected line (removed when every line already has it). */
    fun toggleBullets(t: EditorText): EditorText {
        val r = lineRange(t.text, t.start, t.end)
        val lines = t.text.substring(r.first, r.last + 1).split('\n')
        val all = lines.filter { it.isNotBlank() }.let { ls -> ls.isNotEmpty() && ls.all { BULLET.containsMatchIn(it) } }
        return mapLines(t) { _, l -> if (l.isBlank()) l else if (all) l.replaceFirst(BULLET, "") else "- " + stripList(l) }
    }

    /** "1. ", "2. "... on the selected lines (removed when they are already numbered). */
    fun toggleNumbers(t: EditorText): EditorText {
        val r = lineRange(t.text, t.start, t.end)
        val lines = t.text.substring(r.first, r.last + 1).split('\n')
        val all = lines.filter { it.isNotBlank() }.let { ls -> ls.isNotEmpty() && ls.all { NUMBER.containsMatchIn(it) } }
        var n = 0
        return mapLines(t) { _, l -> if (l.isBlank()) l else if (all) l.replaceFirst(NUMBER, "") else { n++; "$n. " + stripList(l) } }
    }

    /** Heading level 1..3 for the current line; level 0 makes it a normal paragraph again. */
    fun setHeading(t: EditorText, level: Int): EditorText = mapLines(t) { _, l ->
        if (l.isBlank()) l else {
            val body = l.replaceFirst(HEADING, "")
            if (level <= 0) body else "#".repeat(level.coerceIn(1, 4)) + " " + body
        }
    }

    /** Alignment of the current paragraph(s): "" (default), "center", "end" or "justify". */
    fun setAlign(t: EditorText, align: String): EditorText = mapLines(t) { _, l ->
        if (l.isBlank()) l else {
            // Heading and list markers stay first ("## {center} Title", "- {end} item"), the form
            // SectionText reads; an older "{end} - item" is put back in that order.
            val heading = HEADING.find(l)?.value.orEmpty()
            var rest = l.substring(heading.length).replaceFirst(ALIGN, "")
            val list = (BULLET.find(rest) ?: NUMBER.find(rest))?.value.orEmpty()
            rest = rest.substring(list.length).replaceFirst(ALIGN, "")
            heading + list + (if (align.isEmpty()) "" else "{$align} ") + rest
        }
    }

    /** Inserts [block] as its own paragraph at the cursor (replacing the selection). */
    fun insertBlock(t: EditorText, block: String): EditorText {
        val before = t.text.substring(0, t.start)
        val after = t.text.substring(t.end)
        val sepBefore = if (before.isEmpty() || before.endsWith("\n\n")) "" else if (before.endsWith("\n")) "\n" else "\n\n"
        val sepAfter = if (after.isEmpty() || after.startsWith("\n\n")) "" else if (after.startsWith("\n")) "\n" else "\n\n"
        val inserted = sepBefore + block.trim() + sepAfter
        val caret = before.length + inserted.length
        return EditorText(before + inserted + after, caret, caret)
    }
}
