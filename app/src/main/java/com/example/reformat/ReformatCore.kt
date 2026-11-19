package com.example.reformat

import com.example.agent.files.TextReplacement
import com.example.agent.office.BlockType
import com.example.agent.office.DocBlock
import com.example.ai.providers.Json

/** Where the workspace content came from. DOCX keeps its own package; PDF and IMAGE are reconstructed. */
enum class SourceKind { DOCX, PDF, IMAGE }

enum class ExportFormat(val extension: String) { PDF("pdf"), DOCX("docx") }

/** One editable block: its current text (or table cells) and what was imported, to reset and to export only changes. */
data class WorkBlock(
    val block: DocBlock,
    val text: String = block.text,
    val rows: List<List<String>> = block.rows
) {
    val edited: Boolean get() = text != block.text || rows != block.rows
}

data class WorkSource(val name: String, val kind: SourceKind, val pages: Int, val scanned: Boolean, val sha: String, val path: String)

/** The result of Create, before it is saved: the generated file read back and checked. */
data class ExportPreview(
    val path: String,
    val format: ExportFormat,
    val templateName: String?,
    val text: String,
    /** Problems that make the file unusable: it cannot be saved. */
    val issues: List<String>,
    /** Things the user should know (reconstruction, PDF text not verifiable word for word...). */
    val notes: List<String>,
    val savedDocumentId: String? = null
) {
    val valid: Boolean get() = issues.isEmpty()
}

data class ReformatState(
    val source: WorkSource? = null,
    val blocks: List<WorkBlock> = emptyList(),
    /** null: Keep Source Formatting; else the id of a Library template. */
    val templateId: String? = null,
    val busy: Boolean = false,
    /** Stable code of the last error, shown localized. */
    val error: String? = null,
    val preview: ExportPreview? = null
) {
    val hasEdits: Boolean get() = blocks.any { it.edited }
    val uncertainBlocks: Int get() = blocks.count { it.block.uncertain }
}

object ReformatCore {
    private fun isArabic(c: Char) = c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' || c in '\u08A0'..'\u08FF'

    /** Arabic presentation forms: glyph codes that look disconnected when written back as text. */
    fun isPresentationForm(c: Char) = c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF'

    fun arabicLetters(s: String) = s.filter { isArabic(it) }

    /**
     * The smallest replacement that turns [old] into [new] inside paragraph [paragraphIndex]: the
     * common beginning and end are left untouched (their runs and formatting stay as they are);
     * only the changed middle is replaced. A pure insertion is anchored on the neighbouring
     * character. Surrogate pairs are never split. Null when nothing changed or [old] is empty.
     */
    fun minimalReplacement(paragraphIndex: Int, old: String, new: String): TextReplacement? {
        if (old == new || old.isEmpty()) return null
        var p = 0
        while (p < old.length && p < new.length && old[p] == new[p]) p++
        var s = 0
        while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        var start = p
        var endOld = old.length - s
        if (start == endOld) {
            if (start > 0) start -= 1 else endOld += 1
        }
        if (start > 0 && old[start - 1].isHighSurrogate()) start -= 1
        if (endOld < old.length && old[endOld].isLowSurrogate()) endOld += 1
        val tail = old.length - endOld
        val oldMid = old.substring(start, endOld)
        val newMid = new.substring(start, new.length - tail)
        var occurrence = 0
        var at = old.indexOf(oldMid)
        while (at in 0 until start) { occurrence++; at = old.indexOf(oldMid, at + 1) }
        return TextReplacement(paragraphIndex, oldMid, newMid, occurrence)
    }

    /** The blocks as the export should contain them (edited text, edited cells). */
    fun current(blocks: List<WorkBlock>): List<DocBlock> = blocks.map { w ->
        w.block.copy(text = if (w.block.type == BlockType.TABLE) w.rows.joinToString("\n") { it.joinToString(" | ") } else w.text, rows = w.rows)
    }

    /** How the content is placed in a template: which kinds of blocks go to which template styles (shown before creation). */
    data class Mapping(val headings: Int, val paragraphs: Int, val listItems: Int, val tables: Int)

    fun mapping(blocks: List<DocBlock>) = Mapping(
        headings = blocks.count { it.type == BlockType.HEADING },
        paragraphs = blocks.count { it.type == BlockType.PARAGRAPH || it.type == BlockType.CAPTION },
        listItems = blocks.count { it.type == BlockType.LIST_ITEM },
        tables = blocks.count { it.type == BlockType.TABLE }
    )

    private fun squash(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /**
     * Checks the text read back from a generated file against what it must contain. [exact]: a
     * Word file, whose text must be there word for word (Unicode included); for a PDF the text
     * read back can differ in spacing or shaping, so a missing passage is a note, not a failure.
     * Arabic letters must come back as letters (not presentation-form glyphs) and in the same order.
     */
    fun check(expected: List<DocBlock>, outputText: String, exact: Boolean): Pair<List<String>, List<String>> {
        val issues = ArrayList<String>()
        val notes = ArrayList<String>()
        if (outputText.isBlank()) {
            if (exact) issues += "output_unreadable" else notes += "text_not_verifiable"
            return issues to notes
        }
        val flat = squash(outputText)
        val missing = expected.filter { it.type != BlockType.TABLE && it.text.isNotBlank() }.count { squash(it.text) !in flat } +
            expected.filter { it.type == BlockType.TABLE }.sumOf { t -> t.rows.flatten().count { c -> c.isNotBlank() && squash(c) !in flat } }
        if (missing > 0) (if (exact) issues else notes) += "content_missing:$missing"
        if (outputText.any(::isPresentationForm) && expected.none { b -> b.text.any(::isPresentationForm) }) issues += "arabic_glyph_forms"
        val wantArabic = arabicLetters(expected.joinToString("") { if (it.type == BlockType.TABLE) it.rows.flatten().joinToString("") else it.text })
        if (wantArabic.isNotEmpty() && exact && !arabicLetters(outputText).contains(wantArabic.take(200))) issues += "arabic_order"
        return issues to notes
    }

    // ------------------------------------------------------------------ persistence (recovery after a restart)

    fun encode(s: ReformatState): String = Json.write(
        mapOf(
            "v" to 1,
            "source" to s.source?.let { mapOf("name" to it.name, "kind" to it.kind.name, "pages" to it.pages, "scanned" to it.scanned, "sha" to it.sha, "path" to it.path) },
            "templateId" to s.templateId,
            "blocks" to s.blocks.map { w ->
                val b = w.block
                mapOf(
                    "i" to b.index, "p" to b.page, "t" to b.type.name, "x" to b.text, "rtl" to b.rtl, "a" to b.align, "b" to b.bold,
                    "u" to b.uncertain, "pi" to b.paragraphIndex, "rows" to b.rows, "now" to w.text, "nowRows" to w.rows
                )
            },
            "saved" to s.preview?.savedDocumentId
        )
    )

    fun decode(text: String): Pair<ReformatState, String?>? = try {
        val m = Json.obj(Json.parse(text))!!
        val src = Json.obj(m["source"])?.let {
            WorkSource(it["name"] as String, SourceKind.valueOf(it["kind"] as String), (it["pages"] as Number).toInt(), it["scanned"] == true, it["sha"] as String, it["path"] as String)
        }
        fun rows(v: Any?) = Json.arr(v).orEmpty().map { r -> Json.arr(r).orEmpty().map { it as? String ?: "" } }
        val blocks = Json.arr(m["blocks"]).orEmpty().mapNotNull { Json.obj(it) }.map { b ->
            val block = DocBlock(
                index = (b["i"] as Number).toInt(), page = (b["p"] as Number).toInt(), type = BlockType.valueOf(b["t"] as String),
                text = b["x"] as String, rtl = b["rtl"] == true, align = b["a"] as? String ?: "", bold = b["b"] == true,
                uncertain = b["u"] == true, paragraphIndex = (b["pi"] as? Number)?.toInt(), rows = rows(b["rows"])
            )
            WorkBlock(block, b["now"] as? String ?: block.text, rows(b["nowRows"]).ifEmpty { block.rows })
        }
        ReformatState(source = src, blocks = blocks, templateId = m["templateId"] as? String) to (m["saved"] as? String)
    } catch (e: Exception) { null }
}
