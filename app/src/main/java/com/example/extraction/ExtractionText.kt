package com.example.extraction

/**
 * Text form of an extracted document, with the conventions the review editor, the Chat document
 * generator and the thesis editor read back: "#" headings, list items with their own markers,
 * table rows as "cell | cell", blank lines between blocks. Page numbers are left out.
 */
object ExtractionText {

    fun block(b: ExtractedBlock): String? = when (b) {
        is ExtractedBlock.Heading -> "#".repeat(b.level.coerceIn(1, 4)) + " " + b.text
        is ExtractedBlock.Paragraph -> b.text
        is ExtractedBlock.ListItem -> "  ".repeat(b.level) + b.marker + " " + b.text
        is ExtractedBlock.Table -> b.rows.joinToString("\n") { row -> row.joinToString(" | ") { c -> c.text.replace("|", "/").ifBlank { " " } } }
        is ExtractedBlock.Caption -> b.text
        is ExtractedBlock.PageNumber -> null
    }

    fun page(p: ExtractedPage): String {
        val sb = StringBuilder()
        var previous: ExtractedBlock? = null
        for (b in p.blocks) {
            val t = block(b) ?: continue
            if (sb.isNotEmpty()) sb.append(if (previous is ExtractedBlock.ListItem && b is ExtractedBlock.ListItem) "\n" else "\n\n")
            sb.append(t)
            previous = b
        }
        return sb.toString()
    }

    /** All pages, in order, separated by a blank line. */
    fun document(d: ExtractedDocument): String = d.pages.map { page(it) }.filter { it.isNotBlank() }.joinToString("\n\n")

    /** Plain text without markup (for copying): headings and list markers kept as written, tables tab-separated. */
    fun plain(d: ExtractedDocument): String = d.pages.joinToString("\n\n") { p ->
        p.blocks.mapNotNull { b ->
            when (b) {
                is ExtractedBlock.Heading -> b.text
                is ExtractedBlock.Table -> b.rows.joinToString("\n") { r -> r.joinToString("\t") { it.text } }
                is ExtractedBlock.ListItem -> b.marker + " " + b.text
                is ExtractedBlock.PageNumber -> null
                else -> block(b)
            }
        }.joinToString("\n\n")
    }.trim()
}
