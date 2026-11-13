package com.example.thesis.core

import com.example.thesis.model.ThesisElement
import com.example.thesis.model.ThesisSnapshot

enum class ChangeKind { ADDED, DELETED, MODIFIED, MOVED, FORMAT_ONLY }

enum class SegmentKind { EQUAL, INSERTED, DELETED }

/** A run of words in a word-level comparison. */
data class DiffSegment(val kind: SegmentKind, val text: String)

/** One difference between two versions of the thesis. */
data class ElementChange(
    val kind: ChangeKind,
    val elementId: String,
    val sectionId: String,
    /** Old and new element (null when added / deleted). */
    val before: ThesisElement?,
    val after: ThesisElement?,
    /** Word-level comparison for modified text. */
    val segments: List<DiffSegment> = emptyList(),
    /** The element also moved to another section or position. */
    val moved: Boolean = false
)

data class SectionChange(val sectionId: String, val description: String)

data class ThesisDiffResult(val elements: List<ElementChange>, val sections: List<SectionChange>, val metaChanged: Boolean) {
    val isEmpty: Boolean get() = elements.isEmpty() && sections.isEmpty() && !metaChanged
    val counts: Map<ChangeKind, Int> get() = elements.groupingBy { it.kind }.eachCount()
}

/**
 * Compares two versions element by element (by stable id) and word by word inside changed
 * paragraphs. Whitespace-only and layout-only differences (image size, alignment) are reported
 * as formatting changes, never as text changes.
 */
object ThesisDiff {

    private const val MAX_TOKENS = 4000

    fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    fun compare(old: ThesisSnapshot, new: ThesisSnapshot): ThesisDiffResult {
        val changes = ArrayList<ElementChange>()
        val oldOrder = old.orderedElements().withIndex().associate { it.value.id to it.index }
        val newOrder = new.orderedElements().withIndex().associate { it.value.id to it.index }
        for (e in new.elements) {
            val before = old.element(e.id)
            if (before == null) {
                changes.add(ElementChange(ChangeKind.ADDED, e.id, e.sectionId, null, e))
                continue
            }
            val moved = before.sectionId != e.sectionId || relativeOrderChanged(e.id, oldOrder, newOrder, old, new)
            val textChanged = normalize(before.text) != normalize(e.text) || normalize(before.data.caption) != normalize(e.data.caption) ||
                before.data.tableHeader != e.data.tableHeader || before.data.tableRows != e.data.tableRows || before.data.assetId != e.data.assetId
            val formatChanged = before.text != e.text || before.data != e.data
            when {
                textChanged -> changes.add(ElementChange(ChangeKind.MODIFIED, e.id, e.sectionId, before, e, words(before.text, e.text), moved))
                moved -> changes.add(ElementChange(ChangeKind.MOVED, e.id, e.sectionId, before, e, moved = true))
                formatChanged -> changes.add(ElementChange(ChangeKind.FORMAT_ONLY, e.id, e.sectionId, before, e))
            }
        }
        for (e in old.elements) if (new.element(e.id) == null) changes.add(ElementChange(ChangeKind.DELETED, e.id, e.sectionId, e, null))
        changes.sortBy { newOrder[it.elementId] ?: (oldOrder[it.elementId] ?: Int.MAX_VALUE) }

        val sections = ArrayList<SectionChange>()
        for (s in new.sections) {
            val b = old.section(s.id)
            when {
                b == null -> sections.add(SectionChange(s.id, "added"))
                b.title != s.title -> sections.add(SectionChange(s.id, "renamed"))
                b.enabled != s.enabled -> sections.add(SectionChange(s.id, if (s.enabled) "enabled" else "disabled"))
                b.parentId != s.parentId || b.position != s.position -> sections.add(SectionChange(s.id, "moved"))
            }
        }
        for (s in old.sections) if (new.section(s.id) == null) sections.add(SectionChange(s.id, "removed"))
        return ThesisDiffResult(changes, sections, old.meta != new.meta)
    }

    /** True when the element's neighbours changed because the element itself moved. */
    private fun relativeOrderChanged(id: String, oldOrder: Map<String, Int>, newOrder: Map<String, Int>, old: ThesisSnapshot, new: ThesisSnapshot): Boolean {
        val oldSection = old.element(id)!!.sectionId
        val oldSiblings = old.elementsOf(oldSection).map { it.id }.filter { new.element(it) != null }
        val newSiblings = new.elementsOf(new.element(id)!!.sectionId).map { it.id }.filter { old.element(it) != null }
        val oldIdx = oldSiblings.indexOf(id)
        val newIdx = newSiblings.indexOf(id)
        if (oldIdx < 0 || newIdx < 0) return false
        return oldSiblings.getOrNull(oldIdx - 1) != newSiblings.getOrNull(newIdx - 1) &&
            oldSiblings.getOrNull(oldIdx + 1) != newSiblings.getOrNull(newIdx + 1)
    }

    /** Word-level diff (longest common subsequence). Very long texts are compared as a whole. */
    fun words(old: String, new: String): List<DiffSegment> {
        val a = tokens(old)
        val b = tokens(new)
        if (a.size > MAX_TOKENS || b.size > MAX_TOKENS) {
            return listOfNotNull(
                old.takeIf { it.isNotEmpty() }?.let { DiffSegment(SegmentKind.DELETED, it) },
                new.takeIf { it.isNotEmpty() }?.let { DiffSegment(SegmentKind.INSERTED, it) }
            )
        }
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<DiffSegment>()
        fun push(kind: SegmentKind, t: String) {
            val last = out.lastOrNull()
            if (last != null && last.kind == kind) out[out.lastIndex] = last.copy(text = last.text + " " + t) else out.add(DiffSegment(kind, t))
        }
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { push(SegmentKind.EQUAL, a[i]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { push(SegmentKind.DELETED, a[i]); i++ }
                else -> { push(SegmentKind.INSERTED, b[j]); j++ }
            }
        }
        while (i < a.size) push(SegmentKind.DELETED, a[i++])
        while (j < b.size) push(SegmentKind.INSERTED, b[j++])
        return out
    }

    private fun tokens(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
}
