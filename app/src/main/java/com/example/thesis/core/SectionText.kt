package com.example.thesis.core

import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.ThesisElement
import com.example.thesis.model.ThesisSnapshot

/** A figure or table of the section being edited, shown in the text as a placeholder line. */
data class DraftMedia(
    /** Existing element, or null for an image inserted since the section was opened. */
    val elementId: String?,
    val type: ElementType,
    val data: ElementData
)

/** Text of one section as the user edits it in the section editor. */
data class SectionDraft(
    val sectionId: String,
    val text: String,
    /** Placeholder line (for example "[Figure 1]") -> figure or table it stands for. */
    val media: Map<String, DraftMedia>,
    /** Text when the section was opened or last saved (to show "unsaved changes"). */
    val savedText: String,
    /** A figure was inserted or its caption changed since the last save. */
    val mediaChanged: Boolean = false,
    /** Section title and note as edited in the editor, and as saved. */
    val title: String = "",
    val description: String = "",
    val savedTitle: String = "",
    val savedDescription: String = ""
) {
    val infoChanged: Boolean get() = title != savedTitle || description != savedDescription
    val dirty: Boolean get() = text != savedText || mediaChanged || infoChanged
}

/**
 * Converts a section to plain text for the editor and back. Paragraphs are separated by a blank
 * line, headings start with "#", list items keep their marker and figures/tables become a
 * placeholder line such as "[Figure 1]" so they keep their place in the text.
 *
 * Saving is word for word: text is split into elements by the Offline Formatting Agent, elements
 * whose text did not change keep their id (so history shows only real changes) and nothing is
 * rewritten.
 */
object SectionText {

    private val TOKEN = Regex("""^\[[^\[\]\n]{1,40}\s\d{1,4}]$""")

    /** Page break line, in the three content languages (any of them is understood). */
    val PAGE_BREAKS = setOf("[Page break]", "[Saut de page]", "[فاصل صفحة]")
    fun pageBreakToken(language: String) = when (language) { "fr" -> "[Saut de page]"; "ar" -> "[فاصل صفحة]"; else -> "[Page break]" }

    /** Paragraph alignment typed at the start of a paragraph (or after a heading's #). */
    val ALIGN_PREFIXES = mapOf("center" to "{center} ", "end" to "{end} ", "justify" to "{justify} ")

    /** Removes an alignment prefix: returns the text and the alignment ("" when none). */
    fun splitAlign(text: String): Pair<String, String> {
        for ((align, prefix) in ALIGN_PREFIXES) if (text.startsWith(prefix)) return text.removePrefix(prefix) to align
        for ((align, prefix) in ALIGN_PREFIXES) if (text.startsWith(prefix.trimEnd())) return text.removePrefix(prefix.trimEnd()).trimStart() to align
        return text to ""
    }

    private fun alignPrefix(align: String) = ALIGN_PREFIXES[align].orEmpty()

    fun mediaToken(language: String, type: ElementType, n: Int): String =
        "[" + ThesisLabels.get(language, if (type == ElementType.TABLE) "table" else "figure") + " " + n + "]"

    fun load(snapshot: ThesisSnapshot, sectionId: String): SectionDraft {
        val language = snapshot.meta.language
        val media = LinkedHashMap<String, DraftMedia>()
        var figures = 0
        var tables = 0
        val sb = StringBuilder()
        var previous: ThesisElement? = null
        for (e in snapshot.elementsOf(sectionId)) {
            if (e.data.pageBreakBefore) {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(pageBreakToken(language))
            }
            val line = when (e.type) {
                ElementType.IMAGE, ElementType.TABLE -> {
                    val token = if (e.type == ElementType.TABLE) mediaToken(language, e.type, ++tables) else mediaToken(language, e.type, ++figures)
                    media[token] = DraftMedia(e.id, e.type, e.data)
                    token
                }
                ElementType.HEADING -> "#".repeat(e.data.headingLevel.coerceIn(1, 4)) + " " + alignPrefix(e.data.textAlign) + e.text
                ElementType.LIST_ITEM -> (e.data.marker.ifBlank { if (e.data.ordered) "1." else "-" }) + " " + alignPrefix(e.data.textAlign) + e.text
                else -> alignPrefix(e.data.textAlign) + e.text
            }
            if (sb.isNotEmpty()) sb.append(if (!e.data.pageBreakBefore && previous?.type == ElementType.LIST_ITEM && e.type == ElementType.LIST_ITEM) "\n" else "\n\n")
            sb.append(line)
            previous = e
        }
        val text = sb.toString()
        val section = snapshot.section(sectionId)
        val title = section?.title.orEmpty()
        val description = section?.description.orEmpty()
        return SectionDraft(sectionId, text, media, text, title = title, description = description, savedTitle = title, savedDescription = description)
    }

    /** Next free placeholder for a new figure or table in [draft]. */
    fun nextToken(draft: SectionDraft, language: String, type: ElementType): String {
        var n = 1
        while (true) {
            val t = mediaToken(language, type, n)
            if (t !in draft.media && !draft.text.lines().any { it.trim() == t }) return t
            n++
        }
    }

    private sealed class Piece {
        data class Text(val text: String) : Piece()
        data class Media(val token: String) : Piece()
        object Break : Piece()
    }

    private fun pieces(text: String, media: Map<String, DraftMedia>): List<Piece> {
        val out = ArrayList<Piece>()
        val buf = StringBuilder()
        fun flush() { if (buf.isNotBlank()) out.add(Piece.Text(buf.toString())); buf.setLength(0) }
        for (line in text.replace("\r\n", "\n").split('\n')) {
            val t = line.trim()
            when {
                t in media && TOKEN.matches(t) -> { flush(); out.add(Piece.Media(t)) }
                t in PAGE_BREAKS -> { flush(); out.add(Piece.Break) }
                else -> buf.append(line).append('\n')
            }
        }
        flush()
        return out
    }

    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")

    private sealed class Item {
        data class Keep(val id: String) : Item()
        data class Add(val element: NewElement) : Item()
    }

    /**
     * Operations that turn the section's current elements into [draft]. [split] structures typed
     * text (paragraphs, headings, lists, tables). Returns an empty list when nothing changed.
     */
    suspend fun plan(snapshot: ThesisSnapshot, draft: SectionDraft, split: suspend (String) -> List<NewElement>): List<ThesisOp> {
        val sectionId = draft.sectionId
        val old = snapshot.elementsOf(sectionId)
        val unusedText = old.filter { it.type != ElementType.IMAGE && it.type != ElementType.TABLE }.toMutableList()
        val items = ArrayList<Item>()
        val dataUpdates = ArrayList<ThesisOp>()
        var breakPending = false
        for (piece in pieces(draft.text, draft.media)) {
            when (piece) {
                is Piece.Break -> breakPending = true
                is Piece.Media -> {
                    val m = draft.media.getValue(piece.token)
                    val data = m.data.copy(pageBreakBefore = breakPending)
                    breakPending = false
                    val existing = m.elementId?.let { id -> old.firstOrNull { it.id == id } }
                    if (existing != null && items.none { it is Item.Keep && it.id == existing.id }) {
                        items.add(Item.Keep(existing.id))
                        if (existing.data != data) dataUpdates.add(ThesisOp.UpdateData(existing.id, data))
                    } else if (existing == null) {
                        items.add(Item.Add(NewElement(ThesisStructure.newId("el"), m.type, "", data)))
                    }
                }
                is Piece.Text -> for (raw in split(piece.text)) {
                    // "{center} text": the alignment is stored with the element, not in its text.
                    val (text, align) = if (raw.type == ElementType.TABLE) raw.text to "" else splitAlign(raw.text)
                    val n = raw.copy(text = text, data = raw.data.copy(textAlign = align, pageBreakBefore = breakPending))
                    breakPending = false
                    // Unchanged text keeps its element (and its type, id and history).
                    val same = if (n.type == ElementType.TABLE) null else unusedText.firstOrNull { norm(it.text) == norm(n.text) }
                    if (same != null) {
                        unusedText.remove(same)
                        items.add(Item.Keep(same.id))
                        val wanted = same.data.copy(textAlign = align, pageBreakBefore = n.data.pageBreakBefore)
                        if (wanted != same.data) dataUpdates.add(ThesisOp.UpdateData(same.id, wanted))
                    } else items.add(Item.Add(n))
                }
            }
        }
        val kept = items.filterIsInstance<Item.Keep>().map { it.id }.toSet()
        val ops = ArrayList<ThesisOp>()
        old.filter { it.id !in kept }.forEach { ops.add(ThesisOp.DeleteElement(it.id)) }
        val keptOrderOld = old.map { it.id }.filter { it in kept }
        val keptOrderNew = items.filterIsInstance<Item.Keep>().map { it.id }
        val reorder = keptOrderOld != keptOrderNew
        var prev: String = ThesisOperations.AFTER_START
        var run = ArrayList<NewElement>()
        var runAfter = prev
        fun flushRun() {
            if (run.isNotEmpty()) { ops.add(ThesisOp.AddElements(sectionId, runAfter, run)); run = ArrayList() }
        }
        for (item in items) {
            when (item) {
                is Item.Keep -> {
                    flushRun()
                    if (reorder) ops.add(ThesisOp.MoveElement(item.id, sectionId, prev))
                    prev = item.id
                }
                is Item.Add -> {
                    if (run.isEmpty()) runAfter = prev
                    run.add(item.element)
                    prev = item.element.id
                }
            }
        }
        flushRun()
        ops.addAll(dataUpdates)
        if (draft.infoChanged && snapshot.section(sectionId) != null) {
            ops.add(ThesisOp.UpdateSection(sectionId, title = draft.title.trim().takeIf { it != draft.savedTitle }, description = draft.description.takeIf { it != draft.savedDescription }))
        }
        return ops
    }
}
