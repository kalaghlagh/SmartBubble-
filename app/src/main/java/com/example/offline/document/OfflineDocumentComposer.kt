package com.example.offline.document

import com.example.document.model.PresentationModel
import com.example.document.model.SheetModel
import com.example.document.model.SlideModel
import com.example.document.model.SpreadsheetModel
import com.example.document.model.WordDocumentModel
import com.example.document.model.WordSection
import com.example.document.model.WordTable
import com.example.offline.FormattingResult
import com.example.offline.StructureBlock

/**
 * The formatting step: maps a [FormattingResult] onto the existing document engines' models.
 * Only text taken from the result is placed in the document. No organization name, date,
 * presenter, summary row or other content is added; the template supplies styles and layout.
 * Block order is kept (a new untitled section starts whenever the Word model's fixed
 * paragraph -> bullets -> table order would otherwise move content).
 */
object OfflineDocumentComposer {

    /** At most this many items per slide; longer sections continue on further slides. */
    const val SLIDE_ITEMS = 6

    /** Approximate text budget of one slide body. */
    const val SLIDE_CHARS = 700

    fun title(result: FormattingResult): String = result.title?.text.orEmpty()

    /** Name for the file and the Library entry (taken from the text; never invented). */
    fun fileTitle(result: FormattingResult): String {
        result.title?.let { return it.text }
        result.headings.firstOrNull()?.let { return it.text }
        val firstText = result.blocks.firstNotNullOfOrNull { b ->
            when (b) {
                is StructureBlock.Paragraph -> b.text
                is StructureBlock.ListBlock -> b.items.firstOrNull()?.text
                is StructureBlock.Table -> b.header.firstOrNull { it.isNotBlank() }
                is StructureBlock.Heading -> b.text
            }
        }.orEmpty()
        return firstText.split(' ').filter { it.isNotBlank() }.take(8).joinToString(" ").trimEnd(',', ';', ':', '،')
    }

    private fun body(result: FormattingResult): List<StructureBlock> =
        if (result.title != null) result.blocks.drop(1) else result.blocks

    fun toWord(result: FormattingResult): WordDocumentModel {
        val sections = ArrayList<WordSection>()
        var heading = ""
        var paragraphs = ArrayList<String>()
        var bullets = ArrayList<String>()
        var table: WordTable? = null

        fun flush() {
            if (heading.isNotEmpty() || paragraphs.isNotEmpty() || bullets.isNotEmpty() || table != null) {
                sections.add(WordSection(heading = heading, paragraphs = paragraphs, bulletPoints = bullets, table = table))
            }
            heading = ""
            paragraphs = ArrayList()
            bullets = ArrayList()
            table = null
        }

        for (block in body(result)) {
            when (block) {
                is StructureBlock.Heading -> { flush(); heading = block.text }
                is StructureBlock.Paragraph -> {
                    if (bullets.isNotEmpty() || table != null) flush()
                    paragraphs.add(block.text)
                }
                is StructureBlock.ListBlock -> if (block.ordered) {
                    // Numbers are content: numbered items keep their own number as text.
                    if (bullets.isNotEmpty() || table != null) flush()
                    block.items.forEach { paragraphs.add(it.displayText(true)) }
                } else {
                    if (table != null) flush()
                    block.items.forEach { bullets.add(it.text) }
                }
                is StructureBlock.Table -> {
                    if (table != null) flush()
                    table = WordTable(block.header, block.rows)
                }
            }
        }
        flush()
        return WordDocumentModel(
            title = title(result),
            subtitle = "",
            organization = "",
            date = "",
            sections = sections
        )
    }

    fun toPresentation(result: FormattingResult): PresentationModel {
        val slides = ArrayList<SlideModel>()
        var heading = ""
        var items = ArrayList<String>()
        var chars = 0

        fun flush() {
            if (items.isNotEmpty() || heading.isNotEmpty()) slides.add(SlideModel(title = heading, bulletPoints = items))
            items = ArrayList()
            chars = 0
        }

        fun add(text: String) {
            if (items.size >= SLIDE_ITEMS || (items.isNotEmpty() && chars + text.length > SLIDE_CHARS)) flush()
            items.add(text)
            chars += text.length
        }

        for (block in body(result)) {
            when (block) {
                is StructureBlock.Heading -> { flush(); heading = block.text }
                is StructureBlock.Paragraph -> add(block.text)
                is StructureBlock.ListBlock -> block.items.forEach { add(it.displayText(block.ordered)) }
                is StructureBlock.Table -> {
                    add(block.header.joinToString(" | "))
                    block.rows.forEach { add(it.joinToString(" | ")) }
                }
            }
        }
        if (items.isNotEmpty()) flush()
        return PresentationModel(title = title(result), subtitle = "", presenter = "", slides = slides)
    }

    /**
     * One sheet per table (named after the heading above it when there is one) and, when the
     * text has more than tables, a "Text" sheet with the other blocks one per row. Cell values
     * are kept exactly as written ("1,250.00", "007" are not converted to numbers).
     */
    fun toSpreadsheet(result: FormattingResult): SpreadsheetModel {
        val sheets = ArrayList<SheetModel>()
        val usedNames = HashSet<String>()
        val textRows = ArrayList<List<Any>>()
        var lastHeading: String? = null
        var tableNumber = 0

        for (block in result.blocks) {
            when (block) {
                is StructureBlock.Heading -> { lastHeading = block.text; textRows.add(listOf(block.text)) }
                is StructureBlock.Paragraph -> textRows.add(listOf(block.text))
                is StructureBlock.ListBlock -> block.items.forEach { textRows.add(listOf(it.displayText(block.ordered))) }
                is StructureBlock.Table -> {
                    tableNumber++
                    val name = uniqueSheetName(lastHeading ?: "Table $tableNumber", usedNames, tableNumber)
                    sheets.add(SheetModel(name = name, headers = block.header, rows = block.rows, keepValuesAsText = true))
                    lastHeading = null
                }
            }
        }
        if (textRows.isNotEmpty()) {
            // The first text row is the sheet's header row so no label has to be invented.
            val name = uniqueSheetName("Text", usedNames, 0)
            sheets.add(0, SheetModel(name = name, headers = listOf(textRows.first()[0].toString()), rows = textRows.drop(1), keepValuesAsText = true))
        }
        return SpreadsheetModel(workbookTitle = fileTitle(result), sheets = sheets)
    }

    /** Excel sheet names: at most 31 characters, none of []:*?/\ and unique in the workbook. */
    internal fun uniqueSheetName(wanted: String, used: MutableSet<String>, number: Int): String {
        var base = wanted.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().trim('\'').take(31).trim()
        if (base.isEmpty()) base = "Sheet ${number.coerceAtLeast(1)}"
        var name = base
        var n = 2
        while (name.lowercase() in used) {
            val suffix = " ($n)"
            name = base.take(31 - suffix.length).trim() + suffix
            n++
        }
        used.add(name.lowercase())
        return name
    }

    /** Plain text of the document as stored in the Library (for search and later AI use). */
    fun plainText(result: FormattingResult): String = buildString {
        for (block in result.blocks) {
            when (block) {
                is StructureBlock.Heading -> append(block.text).append("\n\n")
                is StructureBlock.Paragraph -> append(block.text).append("\n\n")
                is StructureBlock.ListBlock -> {
                    block.items.forEach { append(if (block.ordered) it.displayText(true) else "• " + it.text).append('\n') }
                    append('\n')
                }
                is StructureBlock.Table -> {
                    append(block.header.joinToString("\t")).append('\n')
                    block.rows.forEach { append(it.joinToString("\t")).append('\n') }
                    append('\n')
                }
            }
        }
    }.trim()
}
