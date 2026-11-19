package com.example.agent.thesis

import com.example.extraction.ExtractedBlock
import com.example.extraction.ExtractedDocument
import com.example.extraction.ExtractionText
import com.example.thesis.core.ExtractionToThesis
import com.example.thesis.core.NewElement
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOperations
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import java.text.Normalizer

/** Where a section reference leads in a thesis. */
sealed class SectionMatch {
    data class Found(val section: ThesisSection) : SectionMatch()
    data class Ambiguous(val sections: List<ThesisSection>) : SectionMatch()
    /** Nothing matches; [available] are the writable sections the user can name instead. */
    data class NotFound(val available: List<ThesisSection>) : SectionMatch()
    /** The section exists but is generated (cover, table of contents...): nothing can be written into it. */
    data class NotWritable(val section: ThesisSection) : SectionMatch()
}

/** Finds the section a request names, by its stable id in the thesis structure (never by page). */
object ThesisSectionLocator {
    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun writable(snapshot: ThesisSnapshot) = snapshot.outline().map { it.first }.filter { it.enabled && it.kind.writable }

    fun locate(snapshot: ThesisSnapshot, ref: SectionRef): SectionMatch {
        val outline = snapshot.outline().map { it.first }.filter { it.enabled }
        val candidates: List<ThesisSection> = when {
            ref.chapterNumber != null -> listOfNotNull(outline.filter { it.kind == SectionKind.CHAPTER }.getOrNull(ref.chapterNumber - 1))
            ref.kind != null -> outline.filter { it.kind == ref.kind }
            !ref.title.isNullOrBlank() -> {
                val want = normalize(ref.title)
                val exact = outline.filter { normalize(it.title) == want }
                exact.ifEmpty { outline.filter { want.length >= 4 && normalize(it.title).contains(want) } }
            }
            else -> emptyList()
        }
        return when {
            candidates.size > 1 -> SectionMatch.Ambiguous(candidates)
            candidates.size == 1 && !candidates[0].kind.writable -> SectionMatch.NotWritable(candidates[0])
            candidates.size == 1 -> SectionMatch.Found(candidates[0])
            else -> SectionMatch.NotFound(writable(snapshot))
        }
    }

    /** Paragraph elements of [sectionId], in order. */
    fun paragraphs(snapshot: ThesisSnapshot, sectionId: String) = snapshot.elementsOf(sectionId).filter { it.type == ElementType.PARAGRAPH }

    /** The [n]-th paragraph (1-based; -1 = last) of [sectionId], or null. */
    fun paragraph(snapshot: ThesisSnapshot, sectionId: String, n: Int) = paragraphs(snapshot, sectionId).let { p ->
        if (n == -1) p.lastOrNull() else p.getOrNull(n - 1)
    }
}

/** A table found in a source document, with its caption and the page it is on (1-based). */
data class FoundTable(val page: Int, val caption: String?, val table: ExtractedBlock.Table)

sealed class TableSearch {
    data class Found(val table: FoundTable) : TableSearch()

    /** The number is captioned on several pages: the user says which. */
    data class Ambiguous(val pages: List<Int>) : TableSearch()

    /** A caption names the table but no table structure was found next to it. */
    data class NoStructure(val page: Int) : TableSearch()
    object NotFound : TableSearch()
}

/**
 * Finds "Table 03" / "Tableau 3" / "الجدول 3" in an extracted document: a caption that STARTS with
 * the label and number (a sentence such as "see Table 3" is not a caption), and the table block
 * right after it (or right before it, for captions placed under the table).
 */
object TableFinder {
    private fun captionRegex(number: String): Regex {
        val parts = number.split('.').joinToString("\\.") { "0*" + Regex.escape(it) }
        val arabicParts = number.split('.').joinToString("\\.") { p -> "0*(?:" + Regex.escape(p) + "|" + Regex.escape(p.map { c -> if (c.isDigit()) '٠' + (c - '0') else c }.joinToString("")) + ")" }
        return Regex("""(?i)^\s*(?:(?:table|tableau)\s*(?:n[°o]\.?\s*)?$parts|(?:الجدول|جدول)\s*(?:رقم\s*)?$arabicParts)(?![0-9.٠-٩])""")
    }

    private fun textOf(b: ExtractedBlock): String? = when (b) {
        is ExtractedBlock.Caption -> b.text
        is ExtractedBlock.Paragraph -> b.text
        is ExtractedBlock.Heading -> b.text
        else -> null
    }

    fun find(doc: ExtractedDocument, number: String): TableSearch {
        val caption = captionRegex(ThesisRequestParser.normalizeNumber(number))
        val hits = ArrayList<FoundTable>()
        val captionOnly = ArrayList<Int>()
        for (page in doc.pages) {
            val pageNo = (page.sourcePage ?: page.index) + 1
            val blocks = page.blocks.filter { it !is ExtractedBlock.PageNumber }
            blocks.forEachIndexed { i, b ->
                val t = textOf(b) ?: return@forEachIndexed
                if (!caption.containsMatchIn(t)) return@forEachIndexed
                // The table after the caption (at most one block between), else the one just before.
                val after = blocks.drop(i + 1).take(2).firstOrNull { it is ExtractedBlock.Table } as? ExtractedBlock.Table
                val before = blocks.getOrNull(i - 1) as? ExtractedBlock.Table
                val table = after ?: before
                if (table != null) hits += FoundTable(pageNo, t.trim(), table) else captionOnly += pageNo
            }
        }
        return when {
            hits.size == 1 -> TableSearch.Found(hits[0])
            hits.size > 1 -> TableSearch.Ambiguous(hits.map { it.page }.distinct())
            captionOnly.isNotEmpty() -> TableSearch.NoStructure(captionOnly.first())
            else -> TableSearch.NotFound
        }
    }

    /** The caption text without its own label and number (the thesis numbers its tables itself). */
    fun captionText(caption: String?, number: String): String {
        if (caption == null) return ""
        val rest = captionRegex(ThesisRequestParser.normalizeNumber(number)).replace(caption, "")
        return rest.trim().trimStart(':', '-', '–', '—', '.', ' ').trim()
    }
}

/** Paragraph texts of extracted pages, in reading order (page numbers in margins left out). */
object PageText {
    fun paragraphs(doc: ExtractedDocument): List<String> = doc.pages.flatMap { p ->
        p.blocks.mapNotNull { b ->
            when (b) {
                is ExtractedBlock.PageNumber -> null
                is ExtractedBlock.Table -> null
                else -> ExtractionText.block(b)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
    }

    /** Pages with low-confidence reading (OCR) or recovered lines: shown as uncertain in the preview. */
    fun uncertainPages(doc: ExtractedDocument): List<Int> = doc.pages.filter { it.reviewRecommended }.map { (it.sourcePage ?: it.index) + 1 }
}

/**
 * A planned change to the active thesis: the exact operations, where they go, and fingerprints of
 * the thesis taken when the change was prepared (to refuse applying it to content that changed).
 */
data class PlannedThesisEdit(
    val projectId: String,
    val sectionId: String,
    val ops: List<ThesisOp>,
    /** Elements the change adds (ids chosen when planning, so a retry never adds them twice). */
    val newElementIds: List<String>,
    /** The paragraph it replaces, with its old text. */
    val replacedElementId: String? = null,
    val replacedText: String? = null,
    val sectionHash: String,
    val otherSectionsHash: String,
    /** Expected table shape (rows including the header, columns), when a table is inserted. */
    val tableShape: Pair<Int, Int>? = null
)

/** Builds thesis operations for the Agent and checks the result after they are applied. */
object ThesisEditPlanner {
    const val MAX_PARAGRAPHS = 200
    const val MAX_CHARS = 60_000

    /** Hash of every section except [sectionId]: proves nothing else in the thesis was touched. */
    fun otherSectionsHash(snapshot: ThesisSnapshot, sectionId: String): String =
        ThesisSnapshot.sha1(snapshot.sections.map { it.id }.filter { it != sectionId }.sorted().joinToString("\n") { "$it:${snapshot.sectionHash(it)}" } +
            "|meta:" + snapshot.meta.toString())

    private fun afterFor(snapshot: ThesisSnapshot, sectionId: String, atStart: Boolean): String? =
        if (atStart) ThesisOperations.AFTER_START else snapshot.elementsOf(sectionId).lastOrNull()?.id

    private fun plan(projectId: String, snapshot: ThesisSnapshot, sectionId: String, elements: List<NewElement>, atStart: Boolean, shape: Pair<Int, Int>? = null): PlannedThesisEdit {
        val ops = listOf(ThesisOp.AddElements(sectionId, afterFor(snapshot, sectionId, atStart), elements))
        ThesisOperations.apply(snapshot, ops) // throws on an invalid destination before anything is shown
        return PlannedThesisEdit(projectId, sectionId, ops, elements.map { it.id }, sectionHash = snapshot.sectionHash(sectionId),
            otherSectionsHash = otherSectionsHash(snapshot, sectionId), tableShape = shape)
    }

    fun insertParagraphs(projectId: String, snapshot: ThesisSnapshot, sectionId: String, paragraphs: List<String>, atStart: Boolean, newId: () -> String): PlannedThesisEdit {
        val clean = paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
        require(clean.isNotEmpty()) { "nothing_to_insert" }
        require(clean.size <= MAX_PARAGRAPHS && clean.sumOf { it.length } <= MAX_CHARS) { "content_too_large" }
        return plan(projectId, snapshot, sectionId, clean.map { NewElement(newId(), ElementType.PARAGRAPH, it) }, atStart)
    }

    fun insertTable(projectId: String, snapshot: ThesisSnapshot, sectionId: String, data: ElementData, atStart: Boolean, newId: () -> String): PlannedThesisEdit {
        require(data.tableHeader.isNotEmpty()) { "table_empty" }
        val shape = (data.tableRows.size + 1) to data.tableHeader.size
        return plan(projectId, snapshot, sectionId, listOf(NewElement(newId(), ElementType.TABLE, "", data)), atStart, shape)
    }

    fun replaceParagraph(projectId: String, snapshot: ThesisSnapshot, sectionId: String, paragraph: Int, text: String): PlannedThesisEdit? {
        val target = ThesisSectionLocator.paragraph(snapshot, sectionId, paragraph) ?: return null
        val ops = listOf(ThesisOp.ReplaceText(target.id, text.trim()))
        ThesisOperations.apply(snapshot, ops)
        return PlannedThesisEdit(projectId, sectionId, ops, emptyList(), target.id, target.text, snapshot.sectionHash(sectionId), otherSectionsHash(snapshot, sectionId))
    }

    /** Thesis table data of an extracted table (merged cells spread over the columns they cover). */
    fun tableData(found: FoundTable, number: String): ElementData =
        ExtractionToThesis.tableData(found.table).copy(caption = TableFinder.captionText(found.caption, number))

    /**
     * Checks the thesis after [plan] was applied. Empty when the change is exactly there: the new
     * elements in the target section with the planned content (a table with its rows, columns
     * and cells), the replaced paragraph with its new text, and every other section unchanged.
     */
    fun validate(after: ThesisSnapshot, plan: PlannedThesisEdit): List<String> {
        val issues = ArrayList<String>()
        if (after.section(plan.sectionId) == null) return listOf("section_missing")
        val expected = (plan.ops.firstOrNull() as? ThesisOp.AddElements)?.elements.orEmpty().associateBy { it.id }
        for (id in plan.newElementIds) {
            val e = after.element(id)
            val want = expected[id]
            when {
                e == null -> issues += "element_missing"
                e.sectionId != plan.sectionId -> issues += "wrong_section"
                want != null && e.text != want.text -> issues += "text_differs"
                want != null && e.type == ElementType.TABLE &&
                    (e.data.tableHeader != want.data.tableHeader || e.data.tableRows != want.data.tableRows) -> issues += "table_differs"
            }
        }
        plan.tableShape?.let { (rows, cols) ->
            val t = plan.newElementIds.mapNotNull { after.element(it) }.firstOrNull { it.type == ElementType.TABLE }
            if (t == null || t.data.tableRows.size + 1 != rows || t.data.tableHeader.size != cols || t.data.tableRows.any { it.size != cols }) issues += "table_shape"
        }
        plan.replacedElementId?.let { id ->
            val want = (plan.ops.firstOrNull() as? ThesisOp.ReplaceText)?.newText
            if (after.element(id)?.text != want) issues += "replacement_missing"
        }
        if (otherSectionsHash(after, plan.sectionId) != plan.otherSectionsHash) issues += "other_sections_changed"
        return issues.distinct()
    }
}
