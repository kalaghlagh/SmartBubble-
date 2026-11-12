package com.example.offline

import kotlin.math.roundToInt

/** One row of the reviewable preview (flat, so a lazy list can show long texts). */
sealed class PreviewItem {
    abstract val key: String

    /** @property inferred structure guessed by the rules (shown with its confidence) */
    data class Heading(
        override val key: String,
        val text: String,
        val level: Int?,
        val isTitle: Boolean,
        val inferred: Boolean,
        val confidencePercent: Int
    ) : PreviewItem()

    data class Paragraph(override val key: String, val text: String) : PreviewItem()

    /** @property startsList first item of its list (the list label is shown above it) */
    data class ListEntry(
        override val key: String,
        val marker: String,
        val text: String,
        val ordered: Boolean,
        val level: Int,
        val startsList: Boolean,
        val confidencePercent: Int
    ) : PreviewItem()

    data class Table(
        override val key: String,
        val header: List<String>,
        val rows: List<List<String>>,
        val confidencePercent: Int
    ) : PreviewItem()
}

/** What the user reviews before anything is generated. */
data class FormattingPreview(
    val result: FormattingResult,
    val items: List<PreviewItem>,
    val headingCount: Int,
    val listCount: Int,
    val tableCount: Int,
    val paragraphCount: Int,
    /** Structural changes guessed by the rules (inferred headings), highlighted in the preview. */
    val inferredCount: Int
)

/** Builds the preview from a result; inferred structure is marked so it can be reviewed. */
object PreviewBuilder {

    fun build(result: FormattingResult): FormattingPreview {
        val items = ArrayList<PreviewItem>(result.blocks.size + 8)
        result.blocks.forEachIndexed { b, block ->
            val pct = (block.confidence * 100).roundToInt().coerceIn(0, 100)
            when (block) {
                is StructureBlock.Heading -> items.add(
                    PreviewItem.Heading("h$b", block.text, block.level, block.isTitle, block.origin == StructureOrigin.INFERRED, pct)
                )
                is StructureBlock.Paragraph -> items.add(PreviewItem.Paragraph("p$b", block.text))
                is StructureBlock.ListBlock -> block.items.forEachIndexed { i, item ->
                    items.add(
                        PreviewItem.ListEntry(
                            "l$b.$i", if (block.ordered) item.marker else "•", item.text, block.ordered,
                            item.level, i == 0, pct
                        )
                    )
                }
                is StructureBlock.Table -> items.add(PreviewItem.Table("t$b", block.header, block.rows, pct))
            }
        }
        return FormattingPreview(
            result = result,
            items = items,
            headingCount = result.headings.size,
            listCount = result.lists.size,
            tableCount = result.tables.size,
            paragraphCount = result.paragraphs.size,
            inferredCount = result.headings.count { it.origin == StructureOrigin.INFERRED }
        )
    }
}
