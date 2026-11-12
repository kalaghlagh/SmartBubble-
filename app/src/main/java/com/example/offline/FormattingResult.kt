package com.example.offline

/**
 * Intermediate representation produced by a [FormattingIntelligenceEngine].
 *
 * The source text and the formatting decisions are kept apart: [sourceText] is the normalized
 * pasted text (only whitespace and line endings differ from what the user pasted), and every
 * block points back into it with a [SourceSpan]. Block texts are copied from the source; the
 * only characters an engine may leave out are structural markers (bullet characters, Markdown
 * "#" and table pipes), which are recorded in [removedMarkup] so the result can be checked
 * against the source by [ContentPreservation].
 */
data class FormattingResult(
    /** Normalized source text; every [SourceSpan] refers to offsets in this string. */
    val sourceText: String,
    /** Blocks in source order. The first block may be a [StructureBlock.Heading] marked as title. */
    val blocks: List<StructureBlock>,
    val documentType: DocumentTypeGuess,
    /** Source ranges that are pure structure markers (not content) and were not copied. */
    val removedMarkup: List<SourceSpan>,
    val preservation: PreservationReport,
    /** True when the inferred structure failed verification and the original paragraphs were kept. */
    val usedFallback: Boolean,
    /** Identifier of the engine that produced the result (e.g. "rule-based"). */
    val engineId: String
) {
    val title: StructureBlock.Heading?
        get() = (blocks.firstOrNull() as? StructureBlock.Heading)?.takeIf { it.isTitle }

    val headings: List<StructureBlock.Heading> get() = blocks.filterIsInstance<StructureBlock.Heading>()
    val lists: List<StructureBlock.ListBlock> get() = blocks.filterIsInstance<StructureBlock.ListBlock>()
    val tables: List<StructureBlock.Table> get() = blocks.filterIsInstance<StructureBlock.Table>()
    val paragraphs: List<StructureBlock.Paragraph> get() = blocks.filterIsInstance<StructureBlock.Paragraph>()

    /** True when at least one table was detected with enough confidence for a spreadsheet. */
    val hasTabularData: Boolean get() = tables.isNotEmpty()
}

/** Half-open range [start, end) in [FormattingResult.sourceText]. */
data class SourceSpan(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "Invalid span $start..$end" }
    }
}

/** How a block's structure was obtained. */
enum class StructureOrigin {
    /** The source marked it explicitly (Markdown heading, bullet character, table pipes or tabs). */
    EXPLICIT,

    /** A heuristic inferred it (e.g. a short standalone line treated as a heading). */
    INFERRED,

    /** No structure was inferred: the source paragraph is kept as it is. */
    ORIGINAL
}

sealed class StructureBlock {
    abstract val span: SourceSpan

    /** 0..1: how sure the rules are about this block's structure (1 for original paragraphs). */
    abstract val confidence: Float
    abstract val origin: StructureOrigin

    /**
     * @property level 1 (top) .. 3, or null when the rules were not confident enough to assign
     *   a level (the heading is then rendered with the document's normal heading style)
     * @property isTitle the first heading of the text, used as the document title
     */
    data class Heading(
        val text: String,
        val level: Int?,
        override val span: SourceSpan,
        override val confidence: Float,
        override val origin: StructureOrigin,
        val isTitle: Boolean = false
    ) : StructureBlock()

    data class Paragraph(
        val text: String,
        override val span: SourceSpan,
        override val confidence: Float = 1f,
        override val origin: StructureOrigin = StructureOrigin.ORIGINAL
    ) : StructureBlock()

    data class ListBlock(
        val ordered: Boolean,
        val items: List<ListItem>,
        override val span: SourceSpan,
        override val confidence: Float,
        override val origin: StructureOrigin
    ) : StructureBlock()

    /** Rows are padded to the header width with empty cells; no cell text is dropped. */
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val kind: TableKind,
        override val span: SourceSpan,
        override val confidence: Float,
        override val origin: StructureOrigin
    ) : StructureBlock()
}

/**
 * @property marker the item marker exactly as written in the source ("-", "•", "1.", "(2)", "a)")
 * @property text the item text without the marker
 * @property level nesting depth from indentation (0 = top level)
 */
data class ListItem(
    val marker: String,
    val text: String,
    val level: Int,
    val span: SourceSpan
) {
    /** Text shown for an ordered item: the original number is part of the content and kept. */
    fun displayText(ordered: Boolean): String = if (ordered) "$marker $text" else text
}

enum class TableKind { PIPES, TABS, ALIGNED_COLUMNS }

enum class DocumentType { REPORT, LETTER, PROPOSAL, MEETING_NOTES, STRUCTURED_DATA, GENERAL }

/**
 * Result of the local, rule-based classification. This is keyword and layout matching, not
 * language understanding; low confidence always gives [DocumentType.GENERAL].
 */
data class DocumentTypeGuess(
    val type: DocumentType,
    val confidence: Float,
    /** The rules that matched (keywords or layout features), for transparency in the preview. */
    val signals: List<String>
)

/**
 * Comparison of the source words with the words of the result.
 * @property sourceWordCount words in the source, structure markers excluded
 * @property missing source words that are not in the result (must be empty)
 * @property added result words that are not in the source (must be empty)
 * @property numbersPreserved every number/date/measure token of the source is in the result, in order
 */
data class PreservationReport(
    val sourceWordCount: Int,
    val missing: List<String>,
    val added: List<String>,
    val numbersPreserved: Boolean
) {
    val isLossless: Boolean get() = missing.isEmpty() && added.isEmpty() && numbersPreserved
}
