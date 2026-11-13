package com.example.thesis.core

import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisElement
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import java.util.UUID

/**
 * Words written INTO the thesis (section names, "Figure", "Table") in the thesis language. These
 * are document content, independent of the app's interface language.
 */
object ThesisLabels {
    private val labels = mapOf(
        "en" to mapOf(
            "COVER" to "Cover Page", "DEDICATION" to "Dedication", "ACKNOWLEDGEMENTS" to "Acknowledgements",
            "ABSTRACT" to "Abstract", "TABLE_OF_CONTENTS" to "Table of Contents", "LIST_OF_FIGURES" to "List of Figures",
            "LIST_OF_TABLES" to "List of Tables", "ABBREVIATIONS" to "List of Abbreviations", "INTRODUCTION" to "General Introduction",
            "CONCLUSION" to "General Conclusion", "REFERENCES" to "References", "APPENDIX" to "Appendices",
            "chapter" to "Chapter", "part" to "Part", "figure" to "Figure", "table" to "Table", "page" to "Page",
            "supervisor" to "Supervisor", "coSupervisor" to "Co-supervisor", "student" to "Presented by", "jury" to "Jury members",
            "year" to "Academic year", "LICENCE" to "Licence Thesis", "MASTER" to "Master's Thesis", "DOCTORATE" to "Doctoral Dissertation",
            "specialization" to "Specialization", "ABSTRACT_OTHER" to "Résumé / الملخص", "studentId" to "Student ID",
            "submitted" to "Submission date"
        ),
        "fr" to mapOf(
            "COVER" to "Page de garde", "DEDICATION" to "Dédicace", "ACKNOWLEDGEMENTS" to "Remerciements",
            "ABSTRACT" to "Résumé", "TABLE_OF_CONTENTS" to "Table des matières", "LIST_OF_FIGURES" to "Liste des figures",
            "LIST_OF_TABLES" to "Liste des tableaux", "ABBREVIATIONS" to "Liste des abréviations", "INTRODUCTION" to "Introduction générale",
            "CONCLUSION" to "Conclusion générale", "REFERENCES" to "Références bibliographiques", "APPENDIX" to "Annexes",
            "chapter" to "Chapitre", "part" to "Partie", "figure" to "Figure", "table" to "Tableau", "page" to "Page",
            "supervisor" to "Encadrant", "coSupervisor" to "Co-encadrant", "student" to "Présenté par", "jury" to "Membres du jury",
            "year" to "Année universitaire", "LICENCE" to "Mémoire de Licence", "MASTER" to "Mémoire de Master", "DOCTORATE" to "Thèse de Doctorat",
            "specialization" to "Spécialité", "ABSTRACT_OTHER" to "Abstract / الملخص", "studentId" to "Matricule",
            "submitted" to "Date de dépôt"
        ),
        "ar" to mapOf(
            "COVER" to "صفحة الغلاف", "DEDICATION" to "إهداء", "ACKNOWLEDGEMENTS" to "شكر وتقدير",
            "ABSTRACT" to "ملخص", "TABLE_OF_CONTENTS" to "فهرس المحتويات", "LIST_OF_FIGURES" to "قائمة الأشكال",
            "LIST_OF_TABLES" to "قائمة الجداول", "ABBREVIATIONS" to "قائمة المختصرات", "INTRODUCTION" to "مقدمة عامة",
            "CONCLUSION" to "خاتمة عامة", "REFERENCES" to "قائمة المراجع", "APPENDIX" to "الملاحق",
            "chapter" to "الفصل", "part" to "الجزء", "figure" to "الشكل", "table" to "الجدول", "page" to "الصفحة",
            "supervisor" to "إشراف", "coSupervisor" to "مشرف مساعد", "student" to "إعداد الطالب(ة)", "jury" to "أعضاء لجنة المناقشة",
            "year" to "السنة الجامعية", "LICENCE" to "مذكرة ليسانس", "MASTER" to "مذكرة ماستر", "DOCTORATE" to "أطروحة دكتوراه",
            "specialization" to "التخصص", "ABSTRACT_OTHER" to "Résumé / Abstract", "studentId" to "رقم التسجيل",
            "submitted" to "تاريخ الإيداع"
        )
    )

    fun get(language: String, key: String): String = labels[language]?.get(key) ?: labels.getValue("en")[key] ?: key
}

/** Number labels computed from the current order (never stored, so they follow every change). */
data class ThesisNumbering(
    /** Section id -> "1", "1.2", "1.2.3" (chapters, sections, subsections, parts as "I"). */
    val sectionNumbers: Map<String, String>,
    /** Image element id -> figure number (1, 2, ...). */
    val figures: Map<String, Int>,
    /** Table element id -> table number. */
    val tables: Map<String, Int>
)

object ThesisStructure {

    fun newId(prefix: String) = prefix + "-" + UUID.randomUUID().toString().substring(0, 13)

    /**
     * Suggested outline for a new thesis: preliminary pages, introduction, chapters (2 for a
     * Licence, 3 otherwise), conclusion, references and appendices. Everything can be renamed,
     * reordered, disabled or removed later.
     */
    fun defaultSections(language: String, level: AcademicLevel): List<ThesisSection> {
        val kinds = listOf(
            SectionKind.COVER, SectionKind.DEDICATION, SectionKind.ACKNOWLEDGEMENTS, SectionKind.ABSTRACT, SectionKind.ABSTRACT,
            SectionKind.TABLE_OF_CONTENTS, SectionKind.LIST_OF_FIGURES, SectionKind.LIST_OF_TABLES, SectionKind.ABBREVIATIONS,
            SectionKind.INTRODUCTION
        )
        val out = ArrayList<ThesisSection>()
        kinds.forEach { k ->
            // The second abstract is the one in the other languages (Résumé / الملخص).
            val title = if (k == SectionKind.ABSTRACT && out.any { it.kind == SectionKind.ABSTRACT }) ThesisLabels.get(language, "ABSTRACT_OTHER") else ThesisLabels.get(language, k.name)
            out.add(ThesisSection(newId("sec"), null, k, title, out.size, enabled = k != SectionKind.ABBREVIATIONS))
        }
        val chapters = if (level == AcademicLevel.LICENCE) 2 else 3
        repeat(chapters) { out.add(ThesisSection(newId("sec"), null, SectionKind.CHAPTER, "", out.size)) }
        for (k in listOf(SectionKind.CONCLUSION, SectionKind.REFERENCES, SectionKind.APPENDIX)) {
            out.add(ThesisSection(newId("sec"), null, k, ThesisLabels.get(language, k.name), out.size, enabled = k != SectionKind.APPENDIX))
        }
        return out
    }

    fun numbering(snapshot: ThesisSnapshot): ThesisNumbering {
        val numbers = HashMap<String, String>()
        var part = 0
        var chapter = 0
        fun walk(parentId: String?, prefix: String?) {
            var counter = 0
            for (s in snapshot.children(parentId)) {
                if (!s.enabled) continue
                when (s.kind) {
                    SectionKind.PART -> { part++; numbers[s.id] = roman(part); walk(s.id, null) }
                    SectionKind.CHAPTER -> { chapter++; numbers[s.id] = chapter.toString(); walk(s.id, chapter.toString()) }
                    SectionKind.SECTION, SectionKind.SUBSECTION -> {
                        counter++
                        val n = if (prefix != null) "$prefix.$counter" else counter.toString()
                        numbers[s.id] = n
                        walk(s.id, n)
                    }
                    else -> walk(s.id, prefix)
                }
            }
        }
        walk(null, null)
        val figures = HashMap<String, Int>()
        val tables = HashMap<String, Int>()
        for (e in snapshot.orderedElements()) {
            when (e.type) {
                ElementType.IMAGE -> figures[e.id] = figures.size + 1
                ElementType.TABLE -> tables[e.id] = tables.size + 1
                else -> Unit
            }
        }
        return ThesisNumbering(numbers, figures, tables)
    }

    private fun roman(n: Int): String {
        val values = listOf(10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var x = n
        val sb = StringBuilder()
        for ((v, s) in values) while (x >= v) { sb.append(s); x -= v }
        return sb.toString()
    }

    /** Heading shown for a section: "Chapter 2: Methods", "1.2 Data", "General Introduction". */
    fun displayTitle(section: ThesisSection, numbering: ThesisNumbering, language: String): String {
        val n = numbering.sectionNumbers[section.id]
        return when (section.kind) {
            SectionKind.CHAPTER -> {
                val label = "${ThesisLabels.get(language, "chapter")} ${n ?: ""}".trim()
                if (section.title.isBlank()) label else "$label: ${section.title}"
            }
            SectionKind.PART -> {
                val label = "${ThesisLabels.get(language, "part")} ${n ?: ""}".trim()
                if (section.title.isBlank()) label else "$label: ${section.title}"
            }
            SectionKind.SECTION, SectionKind.SUBSECTION -> listOfNotNull(n, section.title.ifBlank { null }).joinToString(" ")
            else -> section.title.ifBlank { ThesisLabels.get(language, section.kind.name) }
        }
    }

    /** Heading level of a section in the document (1 = chapter level). */
    fun headingLevel(snapshot: ThesisSnapshot, section: ThesisSection): Int {
        var depth = 1
        var p = section.parentId
        while (p != null && depth < 6) { depth++; p = snapshot.section(p)?.parentId }
        return depth.coerceAtMost(4)
    }

    /** "Figure 3: caption" in the thesis language. */
    fun figureCaption(element: ThesisElement, numbering: ThesisNumbering, language: String): String {
        val n = numbering.figures[element.id] ?: return element.data.caption
        val label = "${ThesisLabels.get(language, "figure")} $n"
        return if (element.data.caption.isBlank()) label else "$label: ${element.data.caption}"
    }

    fun tableCaption(element: ThesisElement, numbering: ThesisNumbering, language: String): String {
        val n = numbering.tables[element.id] ?: return element.data.caption
        val label = "${ThesisLabels.get(language, "table")} $n"
        return if (element.data.caption.isBlank()) label else "$label: ${element.data.caption}"
    }

    private val CROSS_REF = Regex("""\[\[(fig|tab):([A-Za-z0-9-]+)]]""")

    /**
     * Replaces cross-reference tokens ([[fig:id]], [[tab:id]]) with the current numbers, so
     * references follow reordering. Returns the text and the ids that no longer exist.
     */
    fun resolveCrossReferences(text: String, numbering: ThesisNumbering, language: String): Pair<String, List<String>> {
        if (!text.contains("[[")) return text to emptyList()
        val missing = ArrayList<String>()
        val out = CROSS_REF.replace(text) { m ->
            val (kind, id) = m.destructured
            val n = if (kind == "fig") numbering.figures[id] else numbering.tables[id]
            if (n == null) missing.add(id)
            "${ThesisLabels.get(language, if (kind == "fig") "figure" else "table")} ${n ?: "?"}"
        }
        return out to missing
    }

    /** Workspace status of a section. */
    enum class Status { NOT_STARTED, IN_PROGRESS, COMPLETED, AUTOMATIC }

    /**
     * Not started (no content), in progress (content, not marked finished) or completed (marked
     * finished by the user). The cover is complete once title and student are filled; indexes
     * are generated on export ([Status.AUTOMATIC]). A chapter counts its sub-sections.
     */
    fun status(snapshot: ThesisSnapshot, section: ThesisSection): Status {
        when (section.kind) {
            SectionKind.COVER -> return if (snapshot.meta.title.isNotBlank() && snapshot.meta.studentName.isNotBlank()) Status.COMPLETED else Status.IN_PROGRESS
            SectionKind.TABLE_OF_CONTENTS, SectionKind.LIST_OF_FIGURES, SectionKind.LIST_OF_TABLES -> return Status.AUTOMATIC
            else -> {}
        }
        if (section.completed) return Status.COMPLETED
        fun hasContent(id: String): Boolean = snapshot.elementsOf(id).isNotEmpty() || snapshot.children(id).any { it.enabled && hasContent(it.id) }
        return if (hasContent(section.id)) Status.IN_PROGRESS else Status.NOT_STARTED
    }

    /**
     * Overall progress shown in the workspace (0..100): completed sections count fully, sections in
     * progress half. Top-level enabled sections are counted; generated indexes are not.
     */
    fun progress(snapshot: ThesisSnapshot): Int {
        val units = snapshot.children(null).filter { it.enabled }.map { status(snapshot, it) }.filter { it != Status.AUTOMATIC }
        if (units.isEmpty()) return 0
        val score = units.sumOf { when (it) { Status.COMPLETED -> 2; Status.IN_PROGRESS -> 1; else -> 0 }.toInt() }
        return score * 100 / (units.size * 2)
    }

    /** Share of writable, enabled sections that have content (0..100). */
    fun completion(snapshot: ThesisSnapshot): Int {
        val writable = snapshot.outline(includeDisabled = false).map { it.first }.filter { it.kind.writable && !it.kind.container }
        val containers = snapshot.outline(includeDisabled = false).map { it.first }.filter { it.kind.container }
        val units = writable + containers.filter { c -> snapshot.children(c.id).none { it.enabled } }
        if (units.isEmpty()) return 0
        val filled = units.count { snapshot.elementsOf(it.id).isNotEmpty() }
        return filled * 100 / units.size
    }
}
