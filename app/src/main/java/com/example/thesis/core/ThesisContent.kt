package com.example.thesis.core

import com.example.offline.FormattingIntelligenceEngine
import com.example.offline.RuleBasedFormattingEngine
import com.example.offline.StructureBlock
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.ThesisSnapshot

/**
 * Turns pasted text into thesis elements with the Offline Formatting Agent (local rules only):
 * headings, paragraphs, list items and tables, word for word. Nothing is rewritten and nothing is
 * sent to an AI provider for this.
 */
class ThesisContentSplitter(private val engine: FormattingIntelligenceEngine = RuleBasedFormattingEngine()) {

    suspend fun split(text: String): List<NewElement> {
        if (text.isBlank()) return emptyList()
        val result = engine.analyze(text)
        val out = ArrayList<NewElement>()
        for (block in result.blocks) {
            when (block) {
                is StructureBlock.Heading -> out.add(
                    NewElement(ThesisStructure.newId("el"), ElementType.HEADING, block.text, ElementData(headingLevel = (block.level ?: 2).coerceIn(1, 4)))
                )
                is StructureBlock.Paragraph -> out.add(NewElement(ThesisStructure.newId("el"), ElementType.PARAGRAPH, block.text))
                is StructureBlock.ListBlock -> block.items.forEach { item ->
                    out.add(
                        NewElement(
                            ThesisStructure.newId("el"), ElementType.LIST_ITEM, item.text,
                            ElementData(ordered = block.ordered, marker = item.marker, headingLevel = item.level)
                        )
                    )
                }
                is StructureBlock.Table -> out.add(
                    NewElement(ThesisStructure.newId("el"), ElementType.TABLE, "", ElementData(tableHeader = block.header, tableRows = block.rows))
                )
            }
        }
        return out
    }
}

/** A new element that repeats content already in the thesis. */
data class DuplicateMatch(val newElementId: String, val existingElementId: String, val similarity: Double)

/**
 * Local duplicate detection: word 3-grams compared with every paragraph of the thesis (Jaccard
 * similarity). Exact repeats and near repeats (≥ [THRESHOLD]) are reported in the proposal.
 */
object ThesisDuplicates {
    const val THRESHOLD = 0.8

    private fun shingles(text: String): Set<String> {
        val words = text.lowercase().split(Regex("[\\s\\p{Punct}،؛؟«»]+")).filter { it.isNotEmpty() }
        if (words.size < 3) return if (words.isEmpty()) emptySet() else setOf(words.joinToString(" "))
        return (0..words.size - 3).map { words.subList(it, it + 3).joinToString(" ") }.toSet()
    }

    fun find(candidates: List<NewElement>, snapshot: ThesisSnapshot): List<DuplicateMatch> {
        val existing = snapshot.elements.filter { it.text.length >= 20 }.map { it.id to shingles(it.text) }
        val out = ArrayList<DuplicateMatch>()
        for (c in candidates) {
            if (c.text.length < 20) continue
            val s = shingles(c.text)
            if (s.isEmpty()) continue
            val best = existing.map { (id, e) ->
                val inter = s.count { it in e }
                id to inter.toDouble() / (s.size + e.size - inter).coerceAtLeast(1)
            }.maxByOrNull { it.second }
            if (best != null && best.second >= THRESHOLD) out.add(DuplicateMatch(c.id, best.first, best.second))
        }
        return out
    }
}

/**
 * Selects the minimum context for an AI request: the outline (ids, kinds, titles and sizes, no
 * content) and, for edits, only the target texts split into chunks.
 */
object ThesisContext {
    /** Maximum characters of thesis text sent in one edit request. */
    const val CHUNK_CHARS = 6000

    fun outline(snapshot: ThesisSnapshot): String {
        val numbering = ThesisStructure.numbering(snapshot)
        return snapshot.outline(includeDisabled = false).joinToString("\n") { (s, depth) ->
            val count = snapshot.elementsOf(s.id).size
            val first = snapshot.elementsOf(s.id).firstOrNull { it.text.isNotBlank() }?.text?.take(60)?.replace('\n', ' ')
            "  ".repeat(depth) + "- id=${s.id} kind=${s.kind} title=\"${ThesisStructure.displayTitle(s, numbering, snapshot.meta.language)}\" " +
                (if (s.kind.writable) "elements=$count" else "generated") + (first?.let { " starts=\"$it…\"" } ?: "")
        }
    }

    /** Splits (id, text) items into request-sized chunks; an item is never cut. */
    fun chunks(items: List<Pair<String, String>>, limit: Int = CHUNK_CHARS): List<List<Pair<String, String>>> {
        val out = ArrayList<List<Pair<String, String>>>()
        var current = ArrayList<Pair<String, String>>()
        var size = 0
        for (item in items) {
            if (current.isNotEmpty() && size + item.second.length > limit) { out.add(current); current = ArrayList(); size = 0 }
            current.add(item)
            size += item.second.length
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }
}
