package com.example.extraction

/** Writing system of a piece of text, decided from its letters (digits and punctuation are neutral). */
enum class Script { ARABIC, LATIN, NUMERIC, MIXED, UNKNOWN }

/** An axis-aligned box in page coordinates (origin top-left, y downwards). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    fun union(o: Box) = Box(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

    /** Share of the shorter box's height that overlaps [o] vertically. */
    fun verticalOverlap(o: Box): Float {
        val overlap = minOf(bottom, o.bottom) - maxOf(top, o.top)
        val h = minOf(height, o.height)
        return if (overlap <= 0f || h <= 0f) 0f else overlap / h
    }

    fun horizontalOverlap(o: Box): Float {
        val overlap = minOf(right, o.right) - maxOf(left, o.left)
        val w = minOf(width, o.width)
        return if (overlap <= 0f || w <= 0f) 0f else overlap / w
    }

    companion object {
        fun of(items: Collection<Box>): Box? = items.reduceOrNull { a, b -> a.union(b) }
    }
}

/** A recognized (or read) word with its position. */
data class TextWord(
    val text: String,
    val box: Box,
    /** Recognition confidence 0..1, or null when the source has none (text read from a PDF). */
    val confidence: Float? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Font size in points when known (PDF text). */
    val fontSize: Float? = null
)

/**
 * One text line: its words in reading order (right to left for Arabic lines, as Tesseract and
 * the PDF reader give them after ordering), its box and its script.
 */
data class TextLine(
    val words: List<TextWord>,
    val box: Box,
    /** The engine marked the line as the start of a paragraph (when it says so). */
    val startsParagraph: Boolean = false,
    /** How the line was obtained: null for the page pass, "ara" / "fra+eng" for a line read again, "band" for a missed line. */
    val source: String? = null
) {
    val text: String get() = words.joinToString(" ") { it.text }.trim()
    val script: Script get() = ScriptDetector.classify(text)
    val rtl: Boolean get() = ScriptDetector.isRtl(text)
    val confidence: Float? get() = words.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val bold: Boolean get() = words.isNotEmpty() && words.count { it.bold } * 2 > words.size
    val italic: Boolean get() = words.isNotEmpty() && words.count { it.italic } * 2 > words.size
    val fontSize: Float? get() = words.mapNotNull { it.fontSize }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
}

/** A cell of an extracted table (text exactly as read; empty when the cell is empty). */
data class TableCell(val text: String, val colSpan: Int = 1)

/** Paragraph alignment detected on the page. */
enum class Alignment { START, CENTER, END, JUSTIFIED }

/** One element of the reconstructed document, in reading order. */
sealed class ExtractedBlock {
    abstract val rtl: Boolean

    data class Heading(val text: String, val level: Int, override val rtl: Boolean, val bold: Boolean = false) : ExtractedBlock()
    data class Paragraph(
        val text: String,
        override val rtl: Boolean,
        val alignment: Alignment = Alignment.START,
        val bold: Boolean = false,
        val italic: Boolean = false,
        /** Words recognized with low confidence (the user should check them). */
        val uncertainWords: List<String> = emptyList()
    ) : ExtractedBlock()

    /** A list item with its original marker ("1.", "a)", "-", "•", "أ-"...), kept as written. */
    data class ListItem(val text: String, val marker: String, val ordered: Boolean, val level: Int, override val rtl: Boolean) : ExtractedBlock()

    data class Table(
        val rows: List<List<TableCell>>,
        /** The first row is a header row. */
        val header: Boolean,
        override val rtl: Boolean,
        /** The structure is a guess (no ruling lines and an irregular layout): to be checked. */
        val uncertain: Boolean = false,
        /** "rules" (drawn borders) or "layout" (aligned columns without borders). */
        val detectedBy: String = "layout"
    ) : ExtractedBlock() {
        val columns: Int get() = rows.maxOfOrNull { r -> r.sumOf { it.colSpan } } ?: 0
    }

    data class Caption(val text: String, override val rtl: Boolean) : ExtractedBlock()

    /** A page number found in a margin (kept apart from the content). */
    data class PageNumber(val text: String) : ExtractedBlock() { override val rtl: Boolean get() = false }
}

/** How the content of a page was obtained. */
enum class PageSource { IMAGE_OCR, PDF_TEXT, PDF_OCR }

/**
 * How much of the original was rebuilt: the text only, its structure (headings, lists, tables,
 * reading order), or also its formatting (bold, italic, alignment) when the source tells it.
 */
enum class ReconstructionLevel { NONE, TEXT, STRUCTURE, FORMATTING }

/** One page of an extracted document with what is known about how well it was read. */
data class ExtractedPage(
    val index: Int,
    val source: PageSource,
    val blocks: List<ExtractedBlock>,
    val scripts: Set<Script> = emptySet(),
    val level: ReconstructionLevel = ReconstructionLevel.NONE,
    /** Mean word confidence 0..1, null when the source gives none (PDF text). */
    val confidence: Float? = null,
    /** Words recognized with low confidence. */
    val uncertainWords: Int = 0,
    /** Diagnostic codes: "lines_recovered:2", "arabic_lines_reread:5", "deskewed:1.5", "table_uncertain"... */
    val notes: List<String> = emptyList(),
    /** Why the page could not be read, or null. */
    val error: String? = null,
    /** Image of the page (picked image, or a rendered PDF page) for comparison in the review. */
    val imageUri: String? = null,
    /** Which of the extracted files the page comes from, and its page number in that PDF. */
    val sourceIndex: Int = 0,
    val sourcePage: Int? = null
) {
    val tables: Int get() = blocks.count { it is ExtractedBlock.Table }
    val isEmpty: Boolean get() = blocks.none { it !is ExtractedBlock.PageNumber }

    /** Manual review is advised: an error, low confidence, recovered lines or an uncertain table. */
    val reviewRecommended: Boolean
        get() = error != null || isEmpty || (confidence != null && confidence < 0.7f) || uncertainWords > 5 ||
            notes.any { it.startsWith("lines_recovered") || it == "table_uncertain" }
}

/** The result of extracting one or more images or a PDF. */
data class ExtractedDocument(val pages: List<ExtractedPage>, val title: String = "") {
    val scripts: Set<Script> get() = pages.flatMap { it.scripts }.toSet()
    val failedPages: Int get() = pages.count { it.error != null }
}
