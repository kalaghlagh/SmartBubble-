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

    /** Wraps the selection in [marker] (or removes it when already wrapped); with no selection, inserts an empty pair. */
    fun toggleWrap(t: EditorText, marker: String): EditorText {
        val sel = t.selected
        val before = t.text.substring(0, t.start)
        val after = t.text.substring(t.end)
        if (before.endsWith(marker) && after.startsWith(marker) && sel.isNotEmpty()) {
            val text = before.removeSuffix(marker) + sel + after.removePrefix(marker)
            return EditorText(text, t.start - marker.length, t.end - marker.length)
        }
        if (sel.length >= 2 * marker.length && sel.startsWith(marker) && sel.endsWith(marker)) {
            val inner = sel.substring(marker.length, sel.length - marker.length)
            return EditorText(before + inner + after, t.start, t.start + inner.length)
        }
        // Keep spaces outside the markers ("**word** " not "**word **").
        val lead = sel.length - sel.trimStart().length
        val trail = sel.length - sel.trimEnd().length
        val core = sel.trim()
        val text = before + sel.substring(0, lead) + marker + core + marker + sel.substring(sel.length - trail) + after
        val s = t.start + lead + marker.length
        return EditorText(text, s, s + core.length)
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
    private val ALIGN = Regex("""^\{(center|end|justify)}\s?""")

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
            val heading = HEADING.find(l)?.value.orEmpty()
            val rest = l.substring(heading.length).replaceFirst(ALIGN, "")
            heading + (if (align.isEmpty()) "" else "{$align} ") + rest
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
