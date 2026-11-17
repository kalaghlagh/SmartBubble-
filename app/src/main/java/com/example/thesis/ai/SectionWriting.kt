package com.example.thesis.ai

import com.example.thesis.core.EditMode
import com.example.thesis.core.ThesisLabels
import com.example.thesis.core.ThesisStructure
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import java.text.Normalizer

/**
 * What a section IS in the thesis, whatever its kind in the outline or the language of its title:
 * a "Dédicace" written as a custom section, a chapter called "Méthodologie" or a sub-section
 * "النتائج" each get the rules of that kind of text.
 */
enum class SectionType(val academic: Boolean = true, val frontMatter: Boolean = false) {
    COVER(academic = false, frontMatter = true),
    DEDICATION(academic = false, frontMatter = true),
    ACKNOWLEDGEMENTS(academic = false, frontMatter = true),
    ABSTRACT(frontMatter = true),
    ABBREVIATIONS(frontMatter = true),
    INTRODUCTION,
    LITERATURE_REVIEW,
    THEORETICAL_FRAMEWORK,
    METHODOLOGY,
    RESULTS,
    DISCUSSION,
    RESULTS_DISCUSSION,
    CONCLUSION,
    RECOMMENDATIONS,
    REFERENCES,
    APPENDIX,
    /** A chapter or section whose title says nothing about its role (e.g. "Study area"). */
    CHAPTER,
    /** A section the user created that matches no known kind. */
    CUSTOM
}

/** Result of classifying a section: its type and where the type came from. */
data class SectionClassification(
    val type: SectionType,
    /** "kind", "title" or "parent" (inherited from the chapter it belongs to). */
    val source: String
)

/** Classifies sections from their outline kind and their title in Arabic, French or English. */
object SectionClassifier {

    /** Lower case, accents and Arabic marks (hamza, short vowels) removed, letter variants unified. */
    private fun fold(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ة', 'ه').replace('ى', 'ي').replace('ئ', 'ي').replace('ؤ', 'و')
        .replace('’', '\'')

    /** [fold]ed, with punctuation turned to spaces. */
    fun normalize(text: String): String = fold(text)
        .replace(Regex("""[^\p{L}\p{N}' ]+"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    /** Title patterns per type, checked in this order (the most specific first). */
    private val patterns: List<Pair<SectionType, Regex>> = listOf(
        SectionType.DEDICATION to "\\bdedicace|\\bdedicat|اهداء",
        SectionType.ACKNOWLEDGEMENTS to "remerciement|acknowledg|\\bthanks\\b|شكر|عرفان",
        SectionType.ABBREVIATIONS to "abreviation|abbreviation|acronym|\\bsigles\\b|مختصرات|اختصارات",
        SectionType.ABSTRACT to "\\bresume\\b|\\babstract\\b|\\bsummary\\b|ملخص",
        SectionType.RESULTS_DISCUSSION to "(resultat|result|نتائج).*(discussion|مناقشه)|(discussion|مناقشه).*(resultat|result|نتائج)",
        SectionType.LITERATURE_REVIEW to "revue de (la )?litterature|etat de l ?art|literature review|state of the art|related work|previous studies|travaux anterieurs|الدراسات السابقه|مراجعه الادبيات|الادبيات",
        SectionType.THEORETICAL_FRAMEWORK to "cadre theorique|cadre conceptuel|theoretical framework|conceptual framework|theoretical background|الاطار النظري|الاطار المفاهيمي|الخلفيه النظريه",
        SectionType.METHODOLOGY to "methodolog|\\bmethods?\\b|materiel et methodes|materiels et methodes|materials and methods|\\bmethodes?\\b|منهجيه|المنهج|الطريقه والادوات|ادوات الدراسه|اجراءات الدراسه",
        SectionType.RESULTS to "resultat|\\bresults?\\b|\\bfindings\\b|نتائج",
        SectionType.DISCUSSION to "\\bdiscussion|مناقشه",
        SectionType.RECOMMENDATIONS to "recommandation|recommendation|perspectives|توصيات|اقتراحات",
        SectionType.CONCLUSION to "\\bconclusion|خاتمه|الخلاصه",
        SectionType.INTRODUCTION to "\\bintroduction|مقدمه|تمهيد",
        SectionType.REFERENCES to "bibliograph|\\breferences\\b|المراجع|المصادر",
        SectionType.APPENDIX to "\\bannexes?\\b|\\bappendi(x|ces)\\b|ملاحق|ملحق"
    ).map { (t, p) -> t to Regex(fold(p)) } // patterns folded like the titles (ئ -> ي ...)

    /** The type named by a title, or null when the title names none. */
    fun fromTitle(title: String): SectionType? {
        val n = normalize(title)
        if (n.isEmpty()) return null
        return patterns.firstOrNull { it.second.containsMatchIn(n) }?.first
    }

    private fun fromKind(kind: SectionKind): SectionType? = when (kind) {
        SectionKind.COVER -> SectionType.COVER
        SectionKind.DEDICATION -> SectionType.DEDICATION
        SectionKind.ACKNOWLEDGEMENTS -> SectionType.ACKNOWLEDGEMENTS
        SectionKind.ABSTRACT -> SectionType.ABSTRACT
        SectionKind.ABBREVIATIONS -> SectionType.ABBREVIATIONS
        SectionKind.INTRODUCTION -> SectionType.INTRODUCTION
        SectionKind.CONCLUSION -> SectionType.CONCLUSION
        SectionKind.REFERENCES -> SectionType.REFERENCES
        SectionKind.APPENDIX -> SectionType.APPENDIX
        else -> null
    }

    fun classify(snapshot: ThesisSnapshot, section: ThesisSection): SectionClassification {
        fromKind(section.kind)?.let { return SectionClassification(it, "kind") }
        fromTitle(section.title)?.let { return SectionClassification(it, "title") }
        // A sub-section without a telling title belongs to its chapter's kind of text.
        var p = snapshot.section(section.parentId)
        var guard = 0
        while (p != null && guard++ < 8) {
            (fromKind(p.kind) ?: fromTitle(p.title))?.let { return SectionClassification(it, "parent") }
            p = snapshot.section(p.parentId)
        }
        return SectionClassification(if (section.kind == SectionKind.CUSTOM) SectionType.CUSTOM else SectionType.CHAPTER, "kind")
    }
}

/** Writing rules of one type of section (sent with every request about that section). */
data class SectionRules(
    val purpose: String,
    val guidance: List<String>,
    /** Suggested length of a generated draft, in words. */
    val words: IntRange,
    /** Thesis content from other sections that helps (types whose text is excerpted). */
    val related: List<SectionType>
)

/** The writing rules of every section type. */
object SectionPromptRegistry {

    private val integrity = listOf(
        "Never invent results, measurements, statistics, quotations, references, DOIs, dates or names of people or institutions.",
        "Use only facts present in the thesis information, the existing text or the user's instruction; mark any statement that needs a source with [to verify]."
    )

    fun rules(type: SectionType): SectionRules = when (type) {
        SectionType.DEDICATION -> SectionRules(
            "A dedication: a short, personal and emotional text in which the author dedicates the work to people who matter to them.",
            listOf(
                "Personal and warm tone, not academic. No research content, no methodology, no results, no citations, no headings.",
                "A few short lines (typically 3 to 8), for example: to my parents, to my family, to my friends.",
                "Do not invent names of people: write generic relations (my mother, my father) or a placeholder such as [name], unless the user gave the names.",
                "Do not mention the thesis topic in detail."
            ), 20..120, emptyList()
        )
        SectionType.ACKNOWLEDGEMENTS -> SectionRules(
            "Acknowledgements: the author thanks the people and institutions who helped with the thesis.",
            listOf(
                "Grateful, formal but personal tone. No research content, no results, no citations.",
                "Thank the supervisor, co-supervisor, jury members and institution only by the names given in the thesis information; otherwise use placeholders such as [name].",
                "Do not invent names, institutions, grants or laboratories.",
                "One to three short paragraphs."
            ), 60..250, emptyList()
        )
        SectionType.ABSTRACT -> SectionRules(
            "An abstract: a single dense paragraph summarizing context, objective, methods, main results and conclusion of the thesis.",
            listOf(
                "Summarize only what the thesis text says; if results are not written yet, use [results to add] instead of inventing them.",
                "No citations, no references, no headings. End with a line of keywords only if the user asks.",
                "Write in the language of this abstract's title (an abstract titled \"Résumé\" in French, \"Abstract\" in English, \"ملخص\" in Arabic)."
            ), 150..300, listOf(SectionType.INTRODUCTION, SectionType.METHODOLOGY, SectionType.RESULTS, SectionType.RESULTS_DISCUSSION, SectionType.CONCLUSION)
        )
        SectionType.ABBREVIATIONS -> SectionRules(
            "A list of abbreviations used in the thesis, one per line as \"ABBR: meaning\".",
            listOf("List only abbreviations that appear in the thesis text or in the instruction. Do not invent any."), 10..200, emptyList()
        )
        SectionType.INTRODUCTION -> SectionRules(
            "An introduction: context of the topic, the problem, the research question(s), objectives, and the plan of the work (or, inside a chapter, what the chapter covers).",
            listOf("Academic register. Move from the general context to the specific problem.", "Announce the structure using the actual chapter titles of the outline.") + integrity,
            250..700, listOf(SectionType.METHODOLOGY, SectionType.CONCLUSION)
        )
        SectionType.LITERATURE_REVIEW -> SectionRules(
            "A literature review: what previous studies say about the topic, organized by themes, with their limits and the gap this thesis addresses.",
            listOf("Do not invent authors, studies, years or citations: use [author, year] placeholders and [to verify] where a source is needed.") + integrity,
            300..900, listOf(SectionType.INTRODUCTION)
        )
        SectionType.THEORETICAL_FRAMEWORK -> SectionRules(
            "A theoretical or conceptual framework: definitions of the key concepts, the theories or models used and how they relate to the research question.",
            listOf("Define concepts precisely; attribute theories only with [to verify] when no source is given.") + integrity,
            300..900, listOf(SectionType.INTRODUCTION)
        )
        SectionType.METHODOLOGY -> SectionRules(
            "A methodology: study design, study area or population, data collection, tools, procedures and analysis methods, with justification.",
            listOf("Describe methods precisely and impersonally. Do not invent sample sizes, dates, instruments or software versions: use [to complete].") + integrity,
            300..900, listOf(SectionType.INTRODUCTION)
        )
        SectionType.RESULTS -> SectionRules(
            "Results: an objective presentation of what was found, referring to the figures and tables, without interpretation.",
            listOf(
                "Report only results present in the existing text, tables or the instruction. Never create numbers or findings; if none are given, write a structure with [result to add] placeholders.",
                "No interpretation or comparison with the literature (that belongs to the discussion)."
            ) + integrity, 200..800, listOf(SectionType.METHODOLOGY)
        )
        SectionType.DISCUSSION -> SectionRules(
            "A discussion: interpretation of the results, comparison with previous studies, explanations, limits and implications.",
            listOf("Interpret only the results written in the thesis. Comparisons with other studies need [author, year] placeholders or [to verify].") + integrity,
            300..900, listOf(SectionType.RESULTS, SectionType.INTRODUCTION)
        )
        SectionType.RESULTS_DISCUSSION -> SectionRules(
            "Results and discussion: the findings presented objectively, each followed by its interpretation.",
            listOf("Use only results written in the thesis or the instruction; never create numbers; use [result to add] placeholders.") + integrity,
            300..1000, listOf(SectionType.METHODOLOGY, SectionType.INTRODUCTION)
        )
        SectionType.CONCLUSION -> SectionRules(
            "A conclusion: a synthesis answering the research question, the main contributions, the limits and perspectives (or, inside a chapter, what the chapter established).",
            listOf("No new results, no new citations. Summarize only what the thesis says.") + integrity,
            200..600, listOf(SectionType.INTRODUCTION, SectionType.RESULTS, SectionType.RESULTS_DISCUSSION, SectionType.DISCUSSION)
        )
        SectionType.RECOMMENDATIONS -> SectionRules(
            "Recommendations and perspectives that follow from the conclusions of the thesis.",
            listOf("Each recommendation must follow from what the thesis found; no new facts.") + integrity,
            100..500, listOf(SectionType.CONCLUSION, SectionType.RESULTS, SectionType.DISCUSSION)
        )
        SectionType.REFERENCES -> SectionRules(
            "A reference list.",
            listOf("Never create references. Only reformat references given by the user or present in the text, in one consistent style."), 0..2000, emptyList()
        )
        SectionType.APPENDIX -> SectionRules(
            "An appendix: supporting material (questionnaires, raw data, extra tables) referred to in the thesis.",
            listOf("Only organize material the user provided; do not create data.") + integrity, 50..800, emptyList()
        )
        SectionType.COVER -> SectionRules("The cover page (filled from the thesis information).", listOf("Do not write cover text."), 0..0, emptyList())
        SectionType.CHAPTER, SectionType.CUSTOM -> SectionRules(
            "Academic content for this part of the thesis, following its title and its place in the outline.",
            listOf("Stay strictly on the subject given by the section title and its chapter.") + integrity,
            200..800, listOf(SectionType.INTRODUCTION)
        )
    }
}

/** Everything the provider needs to write for one section, and nothing more. */
data class SectionWriteContext(
    val mode: EditMode,
    val sectionId: String,
    /** The heading as printed ("Chapitre 2 : Méthodologie", "Dédicace"). */
    val sectionTitle: String,
    val classification: SectionClassification,
    val parentTitle: String?,
    val thesisTitle: String,
    val subtitle: String,
    val level: String,
    val specialization: String,
    /** Language the text is written in (ar, fr, en). */
    val language: String,
    /** People named in the thesis information (supervisor, jury...), the only names that may be used. */
    val namedPeople: List<String>,
    val previousSection: String?,
    val nextSection: String?,
    /** Titles of the outline, so the introduction can announce the real plan. */
    val outlineTitles: List<String>,
    /** Excerpts of related sections ("Results" for a discussion...), focused and truncated. */
    val related: List<Pair<String, String>>,
    /** Current text of the section (the editor draft), truncated. */
    val existingText: String,
    /** The selected text the action applies to (empty when the whole section). */
    val selection: String,
    val instruction: String,
    /** The full text the task applies to (the selection, or the whole draft), never truncated. */
    val target: String = selection.ifBlank { existingText }
) {
    val type: SectionType get() = classification.type
    val rules: SectionRules get() = SectionPromptRegistry.rules(type)
}

/** Builds the [SectionWriteContext] of a section from the thesis and the editor draft. */
object SectionContextBuilder {

    private const val MAX_EXISTING = 6000
    private const val MAX_RELATED = 900

    /** Plain text of a section and its sub-sections (headings, paragraphs, list items, table captions). */
    fun sectionText(snapshot: ThesisSnapshot, sectionId: String): String {
        val sb = StringBuilder()
        fun walk(id: String, depth: Int) {
            for (e in snapshot.elementsOf(id)) when (e.type) {
                ElementType.HEADING, ElementType.PARAGRAPH, ElementType.LIST_ITEM, ElementType.REFERENCE -> if (e.text.isNotBlank()) sb.append(e.text.trim()).append('\n')
                ElementType.TABLE -> sb.append("[Table: ").append(e.data.caption).append("] ").append(e.data.tableHeader.joinToString(" | ")).append('\n')
                ElementType.IMAGE -> sb.append("[Figure: ").append(e.data.caption).append("]\n")
            }
            if (depth < 6) snapshot.children(id).filter { it.enabled }.forEach { c -> sb.append(c.title).append('\n'); walk(c.id, depth + 1) }
        }
        walk(sectionId, 0)
        return sb.toString().trim()
    }

    /** Long texts keep their beginning and end (the provider sees how the text starts and where it stops). */
    fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max * 2 / 3) + "\n[…]\n" + text.takeLast(max / 3)

    fun build(
        snapshot: ThesisSnapshot,
        sectionId: String,
        mode: EditMode,
        draftText: String,
        selection: String,
        instruction: String
    ): SectionWriteContext {
        val section = snapshot.section(sectionId) ?: throw IllegalArgumentException("Unknown section $sectionId")
        val numbering = ThesisStructure.numbering(snapshot)
        val lang = snapshot.meta.language
        fun title(s: ThesisSection) = ThesisStructure.displayTitle(s, numbering, lang)
        val classification = SectionClassifier.classify(snapshot, section)
        val rules = SectionPromptRegistry.rules(classification.type)
        val outline = snapshot.outline(includeDisabled = false).map { it.first }.filter { !it.kind.generated }
        val index = outline.indexOfFirst { it.id == sectionId }
        // Focused retrieval: only the sections whose content this kind of text builds on.
        val related = if (classification.type.academic) {
            outline.filter { it.id != sectionId }
                .mapNotNull { s -> SectionClassifier.classify(snapshot, s).takeIf { it.type in rules.related && it.source != "parent" }?.let { s } }
                .map { s -> title(s) to truncate(sectionText(snapshot, s.id), MAX_RELATED) }
                .filter { it.second.isNotBlank() }
                .take(4)
        } else emptyList()
        val m = snapshot.meta
        return SectionWriteContext(
            mode = mode,
            sectionId = sectionId,
            sectionTitle = title(section),
            classification = classification,
            parentTitle = snapshot.section(section.parentId)?.let { title(it) },
            thesisTitle = m.title,
            subtitle = m.subtitle,
            level = ThesisLabels.get("en", m.level.name),
            specialization = m.specialization,
            language = lang,
            namedPeople = (listOf(m.supervisor, m.coSupervisor) + m.juryMembers.lines()).map { it.trim() }.filter { it.isNotEmpty() },
            previousSection = outline.getOrNull(index - 1)?.let { title(it) },
            nextSection = outline.getOrNull(index + 1)?.let { title(it) },
            outlineTitles = outline.filter { !it.kind.preliminary }.map { title(it) },
            related = related,
            existingText = truncate(draftText, MAX_EXISTING),
            selection = selection,
            instruction = instruction,
            target = selection.ifBlank { draftText }
        )
    }
}
