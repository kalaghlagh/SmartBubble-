package com.example.thesis.core

import com.example.extraction.Alignment
import com.example.extraction.ExtractedBlock
import com.example.extraction.ExtractedDocument
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType

/** How extracted content goes into the section being edited. */
enum class ExtractionInsertMode {
    /** Everything (headings, paragraphs, lists, tables) at the cursor. */
    AT_CURSOR,
    /** Everything at the end of the section. */
    APPEND,
    /** The text as plain paragraphs (headings become paragraphs; tables stay tables), at the cursor. */
    AS_PARAGRAPHS,
    /** A heading (the first heading found, or the first line) followed by the rest, at the cursor. */
    HEADING_AND_CONTENT,
    /** Only the tables, as editable tables, at the cursor. */
    TABLES_ONLY
}

/**
 * Turns an extracted document into section editor text: headings as "#", paragraphs (with their
 * alignment), list items with their original markers, captions, and tables as editable thesis
 * tables (placeholder lines such as "[Table 2]" bound to table data). Text is inserted exactly as
 * extracted: nothing is reworded, renumbered or translated.
 */
object ExtractionToThesis {

    /** Editor text of the content and the tables it refers to (placeholder -> table). */
    data class Insertion(val text: String, val media: Map<String, DraftMedia>, val tables: Int)

    fun tableData(t: ExtractedBlock.Table): ElementData {
        // Merged cells are spread over the columns they cover (text in the first, the rest empty).
        val width = t.columns
        val grid = t.rows.map { row ->
            val cells = ArrayList<String>()
            for (c in row) { cells += c.text; repeat(c.colSpan - 1) { cells += "" } }
            (cells + List((width - cells.size).coerceAtLeast(0)) { "" }).take(width)
        }
        // The thesis table model has a header row: the first row (a header when one was detected).
        return ElementData(tableHeader = grid.first(), tableRows = grid.drop(1))
    }

    private fun alignPrefix(a: Alignment) = when (a) {
        Alignment.CENTER -> SectionText.ALIGN_PREFIXES.getValue("center")
        Alignment.END -> SectionText.ALIGN_PREFIXES.getValue("end")
        else -> ""
    }

    /**
     * Builds the insertion for [doc] with [mode]. [nextToken] gives a free placeholder for the
     * n-th new table (it must not collide with the draft's own placeholders).
     */
    fun build(doc: ExtractedDocument, mode: ExtractionInsertMode, nextToken: (Int) -> String): Insertion {
        val blocks = doc.pages.flatMap { it.blocks }.filter { it !is ExtractedBlock.PageNumber }
        val parts = ArrayList<String>()
        val media = LinkedHashMap<String, DraftMedia>()
        var tables = 0
        var previousWasItem = false
        fun add(text: String, item: Boolean = false) {
            if (text.isBlank()) return
            if (parts.isNotEmpty()) parts += if (item && previousWasItem) "\n" else "\n\n"
            parts += text
            previousWasItem = item
        }
        fun table(t: ExtractedBlock.Table) {
            if (t.rows.isEmpty()) return
            val token = nextToken(tables + 1)
            tables++
            media[token] = DraftMedia(null, ElementType.TABLE, tableData(t))
            add(token)
        }
        var headingDone = false
        for (b in blocks) when (mode) {
            ExtractionInsertMode.TABLES_ONLY -> if (b is ExtractedBlock.Table) table(b)
            ExtractionInsertMode.AS_PARAGRAPHS -> when (b) {
                is ExtractedBlock.Table -> table(b)
                is ExtractedBlock.ListItem -> add(b.marker + " " + b.text)
                is ExtractedBlock.Heading -> add(b.text)
                is ExtractedBlock.Paragraph -> add(alignPrefix(b.alignment) + b.text)
                is ExtractedBlock.Caption -> add(b.text)
                is ExtractedBlock.PageNumber -> Unit
            }
            else -> {
                if (mode == ExtractionInsertMode.HEADING_AND_CONTENT && !headingDone) {
                    headingDone = true
                    val title = when (b) {
                        is ExtractedBlock.Heading -> b.text
                        is ExtractedBlock.Paragraph -> b.text.takeIf { it.length <= 120 }
                        else -> null
                    }
                    if (title != null) { add("# $title"); continue }
                    // No short first line to use as heading: nothing is invented, the content follows as is.
                }
                when (b) {
                    is ExtractedBlock.Heading -> add("#".repeat((b.level + if (mode == ExtractionInsertMode.HEADING_AND_CONTENT) 1 else 0).coerceIn(1, 4)) + " " + b.text)
                    is ExtractedBlock.Paragraph -> add(alignPrefix(b.alignment) + b.text)
                    is ExtractedBlock.ListItem -> add("  ".repeat(b.level) + b.marker + " " + b.text, item = true)
                    is ExtractedBlock.Caption -> add(b.text)
                    is ExtractedBlock.Table -> table(b)
                    is ExtractedBlock.PageNumber -> Unit
                }
            }
        }
        return Insertion(parts.joinToString(""), media, tables)
    }

    /**
     * [insertion] placed into [draftText] at [at] (whole lines, separated by blank lines), or at
     * the end for [ExtractionInsertMode.APPEND].
     */
    fun place(draftText: String, at: Int, insertion: Insertion, mode: ExtractionInsertMode): String {
        if (insertion.text.isBlank()) return draftText
        val pos = if (mode == ExtractionInsertMode.APPEND) draftText.length else {
            // Never in the middle of a line: after the line where the cursor is.
            val c = at.coerceIn(0, draftText.length)
            if (c == 0) 0 else draftText.indexOf('\n', c).let { if (it < 0) draftText.length else it }
        }
        val before = draftText.substring(0, pos).trimEnd('\n', ' ')
        val after = draftText.substring(pos).trimStart('\n', ' ')
        return listOf(before, insertion.text, after).filter { it.isNotEmpty() }.joinToString("\n\n")
    }
}
