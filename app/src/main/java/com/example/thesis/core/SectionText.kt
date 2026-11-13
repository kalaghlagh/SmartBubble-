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
    val mediaChanged: Boolean = false
) {
    val dirty: Boolean get() = text != savedText || mediaChanged
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
            val line = when (e.type) {
                ElementType.IMAGE, ElementType.TABLE -> {
                    val token = if (e.type == ElementType.TABLE) mediaToken(language, e.type, ++tables) else mediaToken(language, e.type, ++figures)
                    media[token] = DraftMedia(e.id, e.type, e.data)
                    token
                }
                ElementType.HEADING -> "#".repeat(e.data.headingLevel.coerceIn(1, 4)) + " " + e.text
                ElementType.LIST_ITEM -> (e.data.marker.ifBlank { if (e.data.ordered) "1." else "-" }) + " " + e.text
                else -> e.text
            }
            if (sb.isNotEmpty()) sb.append(if (previous?.type == ElementType.LIST_ITEM && e.type == ElementType.LIST_ITEM) "\n" else "\n\n")
            sb.append(line)
            previous = e
        }
        val text = sb.toString()
        return SectionDraft(sectionId, text, media, text)
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
    }

    private fun pieces(text: String, media: Map<String, DraftMedia>): List<Piece> {
        val out = ArrayList<Piece>()
        val buf = StringBuilder()
        fun flush() { if (buf.isNotBlank()) out.add(Piece.Text(buf.toString())); buf.setLength(0) }
        for (line in text.replace("\r\n", "\n").split('\n')) {
            val t = line.trim()
            if (t in media && TOKEN.matches(t)) { flush(); out.add(Piece.Media(t)) } else buf.append(line).append('\n')
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
        for (piece in pieces(draft.text, draft.media)) {
            when (piece) {
                is Piece.Media -> {
                    val m = draft.media.getValue(piece.token)
                    val existing = m.elementId?.let { id -> old.firstOrNull { it.id == id } }
                    if (existing != null && items.none { it is Item.Keep && it.id == existing.id }) {
                        items.add(Item.Keep(existing.id))
                        if (existing.data != m.data) dataUpdates.add(ThesisOp.UpdateData(existing.id, m.data))
                    } else if (existing == null) {
                        items.add(Item.Add(NewElement(ThesisStructure.newId("el"), m.type, "", m.data)))
                    }
                }
                is Piece.Text -> for (n in split(piece.text)) {
                    // Unchanged text keeps its element (and its type, id and history).
                    val same = if (n.type == ElementType.TABLE) null else unusedText.firstOrNull { norm(it.text) == norm(n.text) }
                    if (same != null) { unusedText.remove(same); items.add(Item.Keep(same.id)) } else items.add(Item.Add(n))
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
        return ops
    }
}
