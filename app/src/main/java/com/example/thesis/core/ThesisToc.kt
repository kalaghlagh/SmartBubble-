package com.example.thesis.core

import com.example.thesis.model.ElementType
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSnapshot

/** One line of the table of contents. */
data class TocEntry(
    /** Stable key: "s:<section id>" for a section title, "e:<element id>" for an inline heading. */
    val key: String,
    /** 1 = chapter level, 2 = section, 3 = sub-section, 4 = deeper. */
    val level: Int,
    /** The heading as printed in the document, with its number ("2.1 Data", "Chapter 1: ..."). */
    val text: String,
    val sectionId: String,
    val elementId: String? = null
)

/**
 * The table of contents shared by the PDF export, the preview (rendered from the PDF) and the
 * DOCX export: every enabled section title and every inline heading (chapter, section,
 * sub-section and the headings written in the text), in reading order, down to the depth the
 * user chose. Keys are the ids of the sections and heading elements, so page numbers are looked
 * up by key and never by position in the list.
 */
object ThesisToc {

    fun entries(snapshot: ThesisSnapshot, numbering: ThesisNumbering, maxLevel: Int = snapshot.meta.tocDepth): List<TocEntry> {
        val depth = maxLevel.coerceIn(1, 4)
        val lang = snapshot.meta.language
        val out = ArrayList<TocEntry>()
        for ((section, _) in snapshot.outline(includeDisabled = false)) {
            if (section.kind.generated) continue
            val level = ThesisStructure.headingLevel(snapshot, section)
            if (level <= depth) out.add(TocEntry("s:${section.id}", level, ThesisStructure.displayTitle(section, numbering, lang), section.id))
            for (e in snapshot.elementsOf(section.id)) {
                if (e.type != ElementType.HEADING || e.text.isBlank()) continue
                val l = headingLevel(level, e.data.headingLevel)
                if (l <= depth) out.add(TocEntry("e:${e.id}", l, ThesisStructure.headingText(e, numbering), section.id, e.id))
            }
        }
        return out
    }

    /** Document level of an inline heading of [headingLevel] (1..4) written in a section of [sectionLevel]. */
    fun headingLevel(sectionLevel: Int, headingLevel: Int): Int = (sectionLevel + headingLevel.coerceAtLeast(1)).coerceAtMost(4)

    /**
     * Label printed for physical page [page] (1 = cover). With roman front matter, pages before
     * [mainStart] (the first page of the main text) are i, ii, iii and the main text restarts at
     * [ThesisMeta.mainPageStart]; otherwise pages are counted from [ThesisMeta.mainPageStart].
     */
    fun pageLabel(page: Int, mainStart: Int, meta: ThesisMeta): String {
        val start = meta.mainPageStart.coerceAtLeast(1)
        if (meta.frontMatterRoman && mainStart > 0) {
            return if (page < mainStart) roman(page).lowercase() else (page - mainStart + start).toString()
        }
        return (page - 1 + start).toString()
    }

    /** True when the thesis options change the default page numbering (1, 2, 3 from the cover). */
    fun customNumbering(meta: ThesisMeta): Boolean = meta.frontMatterRoman || meta.mainPageStart != 1

    fun roman(n: Int): String {
        if (n <= 0) return n.toString()
        val values = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var x = n
        val sb = StringBuilder()
        for ((v, s) in values) while (x >= v) { sb.append(s); x -= v }
        return sb.toString()
    }
}
