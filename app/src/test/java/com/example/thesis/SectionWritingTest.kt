package com.example.thesis

import com.example.ai.orchestrator.AiProgressListener
import com.example.thesis.ai.OrchestratorThesisAi
import com.example.thesis.ai.SectionClassifier
import com.example.thesis.ai.SectionContextBuilder
import com.example.thesis.ai.SectionType
import com.example.thesis.ai.SectionWriteContext
import com.example.thesis.ai.SectionWritingProtocol
import com.example.thesis.core.EditMode
import com.example.thesis.core.NewElement
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOperations
import com.example.thesis.core.ThesisStructure
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Context-aware writing in the Thesis Builder, checked against a mock provider that records what
 * it receives: the section's identity, its rules and its context reach the provider; personal
 * sections are not written as research; invalid answers are retried once; AR/FR/EN and custom
 * sections are classified from their titles. (The quality of a real provider's text is not
 * something these tests can verify.)
 */
class SectionWritingTest {

    private val noProgress = object : AiProgressListener {}

    /** Mock provider: answers in turn, records every (system, user) prompt. */
    private class MockProvider(vararg answers: String) {
        private val queue = ArrayDeque(answers.toList())
        val prompts = ArrayList<Pair<String, String>>()
        val run: suspend (String, String, AiProgressListener) -> String = { system, user, _ ->
            prompts.add(system to user)
            queue.removeFirstOrNull() ?: "{\"text\": \"\"}"
        }
    }

    private fun answer(text: String) = "{\"text\": ${com.example.ai.providers.Json.write(text)}, \"notes\": \"ok\"}"

    private fun thesis(language: String = "fr"): ThesisSnapshot {
        var s = ThesisSnapshot(
            ThesisMeta(
                if (language == "ar") "جودة المياه الجوفية في حوض سايس" else "Qualité des eaux souterraines du bassin du Saïss",
                AcademicLevel.MASTER, language, studentName = "Amina B.", supervisor = "Pr. Karim Idrissi", specialization = "Hydrogéologie"
            ),
            ThesisStructure.defaultSections(language, AcademicLevel.MASTER), emptyList()
        )
        val (c1, c2, c3) = s.sections.filter { it.kind == SectionKind.CHAPTER }.map { it.id }
        val intro = s.sections.first { it.kind == SectionKind.INTRODUCTION }.id
        s = ThesisOperations.apply(s, listOf(
            ThesisOp.UpdateSection(c1, title = if (language == "ar") "المنهجية" else "Méthodologie"),
            ThesisOp.UpdateSection(c2, title = if (language == "ar") "النتائج" else "Résultats"),
            ThesisOp.UpdateSection(c3, title = if (language == "ar") "المناقشة" else "Discussion"),
            ThesisOp.AddElements(intro, null, listOf(NewElement("pi", ElementType.PARAGRAPH, "La région dépend des eaux souterraines."))),
            ThesisOp.AddElements(c1, null, listOf(NewElement("pm", ElementType.PARAGRAPH, "Vingt puits ont été échantillonnés en 2024."))),
            ThesisOp.AddElements(c2, null, listOf(NewElement("pr", ElementType.PARAGRAPH, "La teneur moyenne en nitrates est de 52 mg/l.")))
        ))
        return s
    }

    private fun section(s: ThesisSnapshot, kind: SectionKind, n: Int = 0) = s.sections.filter { it.kind == kind }[n]

    private fun ctx(s: ThesisSnapshot, id: String, mode: EditMode = EditMode.GENERATE, draft: String = "", selection: String = "", instruction: String = "") =
        SectionContextBuilder.build(s, id, mode, draft, selection, instruction)

    private fun write(s: ThesisSnapshot, c: SectionWriteContext, provider: MockProvider) =
        runBlocking { OrchestratorThesisAi(provider.run) { "Mock" }.write(s, c, noProgress) }

    // ------------------------------------------------------------------ classification

    @Test
    fun titles_areClassifiedInArabicFrenchAndEnglish() {
        val cases = mapOf(
            "Dédicace" to SectionType.DEDICATION, "Dedication" to SectionType.DEDICATION, "إهداء" to SectionType.DEDICATION,
            "Remerciements" to SectionType.ACKNOWLEDGEMENTS, "Acknowledgements" to SectionType.ACKNOWLEDGEMENTS, "شكر وتقدير" to SectionType.ACKNOWLEDGEMENTS,
            "Résumé" to SectionType.ABSTRACT, "Abstract" to SectionType.ABSTRACT, "الملخص" to SectionType.ABSTRACT,
            "Introduction générale" to SectionType.INTRODUCTION, "مقدمة عامة" to SectionType.INTRODUCTION,
            "Revue de la littérature" to SectionType.LITERATURE_REVIEW, "Literature review" to SectionType.LITERATURE_REVIEW, "الدراسات السابقة" to SectionType.LITERATURE_REVIEW,
            "Cadre théorique" to SectionType.THEORETICAL_FRAMEWORK, "الإطار النظري" to SectionType.THEORETICAL_FRAMEWORK,
            "Méthodologie" to SectionType.METHODOLOGY, "Materials and methods" to SectionType.METHODOLOGY, "المنهجية" to SectionType.METHODOLOGY,
            "Résultats" to SectionType.RESULTS, "Results" to SectionType.RESULTS, "النتائج" to SectionType.RESULTS,
            "Discussion" to SectionType.DISCUSSION, "المناقشة" to SectionType.DISCUSSION,
            "Résultats et discussion" to SectionType.RESULTS_DISCUSSION, "Results and discussion" to SectionType.RESULTS_DISCUSSION, "النتائج والمناقشة" to SectionType.RESULTS_DISCUSSION,
            "Conclusion générale" to SectionType.CONCLUSION, "خاتمة عامة" to SectionType.CONCLUSION,
            "Recommandations" to SectionType.RECOMMENDATIONS, "توصيات" to SectionType.RECOMMENDATIONS,
            "Références bibliographiques" to SectionType.REFERENCES, "قائمة المراجع" to SectionType.REFERENCES,
            "Annexes" to SectionType.APPENDIX, "الملاحق" to SectionType.APPENDIX
        )
        for ((title, type) in cases) assertEquals(title, type, SectionClassifier.fromTitle(title))
        assertEquals(null, SectionClassifier.fromTitle("Zone d'étude"))
    }

    @Test
    fun customSections_andSubSections_getTheirType() {
        var s = thesis()
        val c1 = section(s, SectionKind.CHAPTER).id
        s = ThesisOperations.apply(s, listOf(
            // A custom section the user called "Dédicace" is a dedication.
            ThesisOp.AddSection(ThesisSection("custom-ded", null, SectionKind.CUSTOM, "Dédicace", 0), null),
            // A custom section with an unknown title stays custom.
            ThesisOp.AddSection(ThesisSection("custom-x", null, SectionKind.CUSTOM, "Glossaire des oueds", 1), null),
            // A sub-section of the methodology chapter is methodology.
            ThesisOp.AddSection(ThesisSection("sub", c1, SectionKind.SECTION, "Échantillonnage", 0), null)
        ))
        assertEquals(SectionType.DEDICATION, SectionClassifier.classify(s, s.section("custom-ded")!!).type)
        assertEquals(SectionType.CUSTOM, SectionClassifier.classify(s, s.section("custom-x")!!).type)
        val sub = SectionClassifier.classify(s, s.section("sub")!!)
        assertEquals(SectionType.METHODOLOGY, sub.type)
        assertEquals("parent", sub.source)
        // A chapter titled by its subject only is a generic chapter.
        s = ThesisOperations.apply(s, listOf(ThesisOp.UpdateSection(c1, title = "Zone d'étude")))
        assertEquals(SectionType.CHAPTER, SectionClassifier.classify(s, s.section(c1)!!).type)
    }

    // ------------------------------------------------------------------ what reaches the provider

    @Test
    fun dedication_promptSaysDedication_personalTone_noThesisContent() {
        val s = thesis()
        val p = MockProvider(answer("À mes parents, pour leur amour et leur soutien.\nÀ ma famille et à mes amis."))
        val r = write(s, ctx(s, section(s, SectionKind.DEDICATION).id), p)
        val (system, user) = p.prompts.single()
        assertTrue(system.contains("\"Dédicace\""))
        assertTrue(system.contains("section type: DEDICATION"))
        assertTrue(system.contains("personal and emotional"))
        assertTrue(system.contains("not academic"))
        assertTrue(system.contains("Do not invent names"))
        assertTrue(system.contains("Write in French"))
        // No research content is sent for a dedication.
        assertFalse(user.contains("nitrates"))
        assertFalse(user.contains("Excerpt of"))
        assertEquals("À mes parents, pour leur amour et leur soutien.\nÀ ma famille et à mes amis.", r.edits["text"])
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun dedication_writtenLikeResearch_isRetriedOnceWithTheReasons() {
        val s = thesis()
        val p = MockProvider(
            answer("Cette étude analyse la méthodologie d'échantillonnage (Smith, 2019) et les résultats sur 52 puits."),
            answer("À mes chers parents, à mes sœurs et à tous ceux qui m'ont soutenu.")
        )
        val r = write(s, ctx(s, section(s, SectionKind.DEDICATION).id), p)
        assertEquals(2, p.prompts.size)
        val retry = p.prompts[1].second
        assertTrue(retry.contains("previous answer was rejected"))
        assertTrue(retry.contains("no research content"))
        assertTrue(retry.contains("No citations"))
        assertEquals("À mes chers parents, à mes sœurs et à tous ceux qui m'ont soutenu.", r.edits["text"])
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun stillInvalidAfterTheRetry_isShownWithWarnings_notRetriedAgain() {
        val s = thesis()
        val bad = answer("Cette étude présente la méthodologie et les résultats (Smith, 2019).")
        val p = MockProvider(bad, bad, bad)
        val r = write(s, ctx(s, section(s, SectionKind.DEDICATION).id), p)
        assertEquals(2, p.prompts.size) // one retry at most
        assertTrue(r.warnings.contains("check_not_personal"))
        assertTrue(r.warnings.contains("check_citation"))
    }

    @Test
    fun acknowledgements_useOnlyTheNamesGiven() {
        val s = thesis()
        val c = ctx(s, section(s, SectionKind.ACKNOWLEDGEMENTS).id)
        assertEquals(SectionType.ACKNOWLEDGEMENTS, c.type)
        val p = MockProvider(
            answer("Je remercie le Dr. Martin pour son aide précieuse."),
            answer("Je remercie mon encadrant, Pr. Karim Idrissi, pour ses conseils et sa disponibilité.")
        )
        val r = write(s, c, p)
        val (system, user) = p.prompts[0]
        assertTrue(system.contains("section type: ACKNOWLEDGEMENTS"))
        assertTrue(user.contains("the only names you may use): Pr. Karim Idrissi"))
        // "Dr. Martin" was invented: rejected, then the answer with the real supervisor is kept.
        assertTrue(p.prompts[1].second.contains("Do not invent names"))
        assertEquals("Je remercie mon encadrant, Pr. Karim Idrissi, pour ses conseils et sa disponibilité.", r.edits["text"])
    }

    @Test
    fun abstract_getsFocusedExcerpts_andTheLanguageOfItsTitle() {
        val s = thesis("en")
        val abstracts = s.sections.filter { it.kind == SectionKind.ABSTRACT }
        val c = ctx(s, abstracts[0].id)
        assertEquals(SectionType.ABSTRACT, c.type)
        assertEquals("en", SectionWritingProtocol.expectedLanguage(c))
        val p = MockProvider(answer("This thesis studies groundwater quality in the Saïss basin. [results to add]"))
        write(s, c, p)
        val (system, user) = p.prompts.single()
        assertTrue(system.contains("section type: ABSTRACT"))
        assertTrue(system.contains("single dense paragraph"))
        // Introduction, methodology and results are excerpted for an abstract.
        assertTrue(user.contains("Excerpt of \"General Introduction\""))
        assertTrue(user.contains("Excerpt of \"Chapter 1: Méthodologie\""))
        assertTrue(user.contains("52 mg/l"))
        // An abstract titled in French inside an English thesis is written in French.
        val fr = s.copy(sections = s.sections.map { if (it.id == abstracts[1].id) it.copy(title = "Résumé") else it })
        assertEquals("fr", SectionWritingProtocol.expectedLanguage(ctx(fr, abstracts[1].id)))
    }

    @Test
    fun introduction_methodology_results_discussion_eachGetTheirRules() {
        val s = thesis()
        val expectations = listOf(
            section(s, SectionKind.INTRODUCTION).id to listOf("section type: INTRODUCTION", "research question", "Outline of the thesis: "),
            section(s, SectionKind.CHAPTER, 0).id to listOf("section type: METHODOLOGY", "data collection", "Do not invent sample sizes"),
            section(s, SectionKind.CHAPTER, 1).id to listOf("section type: RESULTS", "without interpretation", "Never create numbers"),
            section(s, SectionKind.CHAPTER, 2).id to listOf("section type: DISCUSSION", "interpretation of the results", "Excerpt of \"Chapitre 2: Résultats\"")
        )
        for ((id, needles) in expectations) {
            val p = MockProvider(answer("Texte de la section [to verify]."))
            write(s, ctx(s, id), p)
            val all = p.prompts.first().first + "\n" + p.prompts.first().second
            for (n in needles) assertTrue("$id: missing \"$n\"", all.contains(n))
        }
    }

    @Test
    fun results_inventedNumbersAreRejected() {
        val s = thesis()
        val results = section(s, SectionKind.CHAPTER, 1).id
        val p = MockProvider(
            answer("La teneur moyenne en nitrates est de 52 mg/l et 87% des puits dépassent la norme."),
            answer("La teneur moyenne en nitrates est de 52 mg/l. [résultat à compléter]")
        )
        val r = write(s, ctx(s, results, draft = "La teneur moyenne en nitrates est de 52 mg/l."), p)
        assertTrue(p.prompts[1].second.contains("Do not add numbers"))
        assertEquals("La teneur moyenne en nitrates est de 52 mg/l. [résultat à compléter]", r.edits["text"])
    }

    @Test
    fun continue_sendsTheExistingTextAndAsksOnlyForTheContinuation() {
        val s = thesis()
        val intro = section(s, SectionKind.INTRODUCTION).id
        val existing = "La région dépend des eaux souterraines pour l'eau potable et l'irrigation."
        val p = MockProvider(answer("Cette dépendance rend la qualité de la ressource essentielle."))
        val r = write(s, ctx(s, intro, EditMode.CONTINUE, draft = existing), p)
        val (system, user) = p.prompts.single()
        assertTrue(system.contains("Continue the existing text"))
        assertTrue(system.contains("Return ONLY the new continuation"))
        assertTrue(user.contains("Current text of this section:\n$existing"))
        assertEquals("Cette dépendance rend la qualité de la ressource essentielle.", r.edits["text"])
    }

    @Test
    fun improve_onASelection_sendsTheSelectionAsTarget_andKeepsCitationChecks() {
        val s = thesis()
        val intro = section(s, SectionKind.INTRODUCTION).id
        val draft = "Premier paragraphe.\n\nLa nappe est polluée (Smith, 2019) a cause des engrais."
        val sel = "La nappe est polluée (Smith, 2019) a cause des engrais."
        val p = MockProvider(answer("La nappe est polluée à cause des engrais agricoles."))
        val r = write(s, ctx(s, intro, EditMode.IMPROVE, draft = draft, selection = sel), p)
        val (system, user) = p.prompts.single()
        assertTrue(system.contains("Task: Improve the target text"))
        assertTrue(system.contains("section type: INTRODUCTION"))
        assertTrue(user.contains("Target text (the selection the task applies to):\n$sel"))
        // The citation dropped by the provider is reported.
        assertTrue(r.warnings.contains("citation_removed:text"))
    }

    @Test
    fun arabicThesis_contextAndLanguageCheck() {
        val s = thesis("ar")
        val ded = section(s, SectionKind.DEDICATION).id
        val c = ctx(s, ded)
        assertEquals(SectionType.DEDICATION, c.type)
        val p = MockProvider(answer("To my parents and my family."), answer("إلى والديّ الكريمين، وإلى عائلتي وأصدقائي."))
        val r = write(s, c, p)
        assertTrue(p.prompts[0].first.contains("Write in Arabic"))
        assertTrue(p.prompts[0].first.contains("\"إهداء\""))
        assertTrue(p.prompts[1].second.contains("must be written in Arabic"))
        assertEquals("إلى والديّ الكريمين، وإلى عائلتي وأصدقائي.", r.edits["text"])
        // The Arabic methodology chapter is classified from its title.
        assertEquals(SectionType.METHODOLOGY, ctx(s, section(s, SectionKind.CHAPTER).id).type)
    }

    @Test
    fun customSection_isWrittenFromItsTitleAndPlace() {
        var s = thesis("en")
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddSection(ThesisSection("cx", null, SectionKind.CUSTOM, "Field trip diary", 99), null)))
        val c = ctx(s, "cx", instruction = "Describe the visits")
        assertEquals(SectionType.CUSTOM, c.type)
        val p = MockProvider(answer("The visits covered the wells of the basin. [to complete]"))
        write(s, c, p)
        val (system, user) = p.prompts.single()
        assertTrue(system.contains("\"Field trip diary\""))
        assertTrue(system.contains("section type: CUSTOM"))
        assertTrue(system.contains("Stay strictly on the subject given by the section title"))
        assertTrue(user.contains("User instruction: Describe the visits"))
        assertTrue(user.contains("Previous section: "))
    }
}
