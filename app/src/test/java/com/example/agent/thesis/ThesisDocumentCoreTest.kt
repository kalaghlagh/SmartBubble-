package com.example.agent.thesis

import com.example.agent.core.FileSource
import com.example.agent.core.LibraryRef
import com.example.extraction.ExtractedBlock
import com.example.extraction.ExtractedDocument
import com.example.extraction.ExtractedPage
import com.example.extraction.PageSource
import com.example.extraction.TableCell
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOperations
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisElement
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thesis document requests: parsing (AR/FR/EN), routing, section and table lookup, planning and checks. Pure JVM. */
class ThesisDocumentCoreTest {

    private fun ref(title: String, type: String, id: String = title) = LibraryRef(id, title, type, FileSource.LIBRARY)

    // ------------------------------------------------------------------ parsing

    @Test
    fun english_pageText_intoAcknowledgements_ofANamedThesis() {
        val r = ThesisRequestParser.parse(
            "I have a PDF named Resources minérales. Extract the Remerciements text from page 2 and copy it into the Remerciements section of my thesis named Mémoire géologique.",
            listOf("Resources minérales")
        )!!
        assertEquals(ThesisEditKind.INSERT_PAGE_TEXT, r.kind)
        assertEquals(listOf(2), r.pages)
        assertEquals(SectionKind.ACKNOWLEDGEMENTS, r.section?.kind)
        assertEquals("Mémoire géologique", r.thesisTitle)
        assertNull(r.pageHint)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun english_numberedTable_intoChapter_pageIsOnlyAHint() {
        val r = ThesisRequestParser.parse("Extract table 03 from Resources minérales and add it to my thesis in Chapter 2, page 14.", listOf("Resources minérales"))!!
        assertEquals(ThesisEditKind.INSERT_TABLE, r.kind)
        assertEquals("3", r.tableNumber)
        assertEquals(2, r.section?.chapterNumber)
        assertEquals(14, r.pageHint)
        assertTrue(r.pages.isEmpty())
    }

    @Test
    fun french_and_arabic_tableRequests() {
        val fr = ThesisRequestParser.parse("Extrais le tableau n° 03 de Resources minérales et ajoute-le dans le chapitre 2 de mon mémoire", listOf("Resources minérales"))!!
        assertEquals(ThesisEditKind.INSERT_TABLE, fr.kind)
        assertEquals("3", fr.tableNumber)
        assertEquals(2, fr.section?.chapterNumber)
        val ar = ThesisRequestParser.parse("استخرج الجدول ٣ من Resources minérales وأضفه إلى الفصل الثاني من مذكرتي", listOf("Resources minérales"))!!
        assertEquals(ThesisEditKind.INSERT_TABLE, ar.kind)
        assertEquals("3", ar.tableNumber)
        assertEquals(2, ar.section?.chapterNumber)
    }

    @Test
    fun arabic_pageRange_intoAcknowledgements() {
        val r = ThesisRequestParser.parse("انسخ نص الصفحات 3-5 من Resources minérales إلى قسم الشكر والتقدير في مذكرتي", listOf("Resources minérales"))!!
        assertEquals(ThesisEditKind.INSERT_PAGE_TEXT, r.kind)
        assertEquals(listOf(3, 4, 5), r.pages)
        assertEquals(SectionKind.ACKNOWLEDGEMENTS, r.section?.kind)
    }

    @Test
    fun appendAndReplace_keepTheGivenText_andTheParagraphNumber() {
        val add = ThesisRequestParser.parse("Add this paragraph at the end of the Introduction section of my thesis: Groundwater is the main resource of the region.")!!
        assertEquals(ThesisEditKind.APPEND_TEXT, add.kind)
        assertEquals(SectionKind.INTRODUCTION, add.section?.kind)
        assertEquals("Groundwater is the main resource of the region.", add.text)
        val replace = ThesisRequestParser.parse("Replace the second paragraph of the Methodology section of my thesis with \"Samples were taken monthly.\"")!!
        assertEquals(ThesisEditKind.REPLACE_PARAGRAPH, replace.kind)
        assertEquals(2, replace.paragraph)
        assertEquals("Methodology", replace.section?.title)
        assertEquals("Samples were taken monthly.", replace.text)
        val fr = ThesisRequestParser.parse("Remplace le deuxième paragraphe de la section Méthodologie de mon mémoire par « Les échantillons ont été prélevés chaque mois. »")!!
        assertEquals(ThesisEditKind.REPLACE_PARAGRAPH, fr.kind)
        assertEquals(2, fr.paragraph)
        assertEquals("Méthodologie", fr.section?.title)
        val ar = ThesisRequestParser.parse("استبدل الفقرة الأخيرة من المقدمة في مذكرتي بـ «نص جديد للفقرة.»")!!
        assertEquals(ThesisEditKind.REPLACE_PARAGRAPH, ar.kind)
        assertEquals(-1, ar.paragraph)
        assertEquals(SectionKind.INTRODUCTION, ar.section?.kind)
    }

    @Test
    fun missingPieces_areListed_neverGuessed() {
        val noSection = ThesisRequestParser.parse("Add \"A new sentence.\" to my thesis")!!
        assertEquals(listOf("section"), noSection.missing)
        val noText = ThesisRequestParser.parse("Add a paragraph at the end of the Conclusion of my thesis")!!
        assertEquals(listOf("text"), noText.missing)
    }

    @Test
    fun extractOnly_andUnrelatedRequests() {
        val r = ThesisRequestParser.parse("Extract page 3 of Resources minérales", listOf("Resources minérales"))!!
        assertEquals(ThesisEditKind.EXTRACT_ONLY, r.kind)
        assertEquals(listOf(3), r.pages)
        assertNull(ThesisRequestParser.parse("Write a letter to my landlord about the heating"))
        assertNull(ThesisRequestParser.parse("Summarize Rapport annuel.docx", listOf("Rapport annuel.docx")))
        assertNull(ThesisRequestParser.parse("Help me plan my thesis defense"))
    }

    // ------------------------------------------------------------------ routing

    @Test
    fun router_usesTheExactlyNamedSource_asksOtherwise() {
        val files = listOf(ref("Resources minérales.pdf", "PDF"), ref("Rapport.docx", "DOCX"), ref("Mémoire géologique.docx", "DOCX"))
        val plan = ThesisRequestRouter.decide("Extract table 3 from Resources minérales.pdf into Chapter 2 of my thesis", files)
        assertTrue(plan is ThesisDecision.Plan)
        assertEquals("Resources minérales.pdf", (plan as ThesisDecision.Plan).source?.title)
        // Only a Word file named: tables and pages come from PDF and images.
        assertEquals(ThesisDecision.Ask("source_format"), ThesisRequestRouter.decide("Extract page 2 of Rapport.docx into the Introduction of my thesis", files))
        // No file named: candidates to pick from (or the question).
        val ask = ThesisRequestRouter.decide("Extract page 2 of the minérales report into the Introduction of my thesis", files) as ThesisDecision.Ask
        assertEquals("source", ask.what)
        assertEquals(listOf("Resources minérales.pdf"), ask.candidates.map { it.title })
        // Text from the message: no source needed.
        assertTrue(ThesisRequestRouter.decide("Add \"Hello.\" at the end of the Conclusion of my thesis", files) is ThesisDecision.Plan)
        // A source named without a page: which page.
        assertEquals(ThesisDecision.Ask("pages"), ThesisRequestRouter.decide("Copy the text of Resources minérales.pdf into the Introduction of my thesis", files))
        // A file named like the target thesis is never the source.
        assertEquals(ThesisDecision.Ask("source_is_target"),
            ThesisRequestRouter.decide("Extract page 2 of Mémoire géologique.docx into the Introduction of my thesis named Mémoire géologique", files))
    }

    // ------------------------------------------------------------------ thesis structure

    private fun thesis(): ThesisSnapshot {
        val sections = listOf(
            ThesisSection("cover", null, SectionKind.COVER, "Cover", 0),
            ThesisSection("ack", null, SectionKind.ACKNOWLEDGEMENTS, "Remerciements", 1),
            ThesisSection("toc", null, SectionKind.TABLE_OF_CONTENTS, "Sommaire", 2),
            ThesisSection("intro", null, SectionKind.INTRODUCTION, "Introduction générale", 3),
            ThesisSection("ch1", null, SectionKind.CHAPTER, "Chapitre 1 : Contexte", 4),
            ThesisSection("meth", "ch1", SectionKind.SECTION, "Méthodologie", 0),
            ThesisSection("ch2", null, SectionKind.CHAPTER, "Chapitre 2 : Résultats", 5),
            ThesisSection("concl", null, SectionKind.CONCLUSION, "Conclusion", 6)
        )
        val elements = listOf(
            ThesisElement("i1", "intro", ElementType.PARAGRAPH, 0, "First intro paragraph."),
            ThesisElement("i2", "intro", ElementType.PARAGRAPH, 1, "Second intro paragraph."),
            ThesisElement("m1", "meth", ElementType.HEADING, 0, "Echantillonnage"),
            ThesisElement("m2", "meth", ElementType.PARAGRAPH, 1, "Old method one."),
            ThesisElement("m3", "meth", ElementType.PARAGRAPH, 2, "Old method two."),
            ThesisElement("c1", "ch2", ElementType.PARAGRAPH, 0, "Results text.")
        )
        return ThesisSnapshot(ThesisMeta("Mémoire géologique", language = "fr"), sections, elements)
    }

    @Test
    fun sectionLocator_byKindChapterAndTitle_withAccentsAndGeneratedSections() {
        val s = thesis()
        assertEquals("ack", (ThesisSectionLocator.locate(s, SectionRef(kind = SectionKind.ACKNOWLEDGEMENTS)) as SectionMatch.Found).section.id)
        assertEquals("ch2", (ThesisSectionLocator.locate(s, SectionRef(chapterNumber = 2)) as SectionMatch.Found).section.id)
        assertEquals("meth", (ThesisSectionLocator.locate(s, SectionRef(title = "methodologie")) as SectionMatch.Found).section.id)
        assertTrue(ThesisSectionLocator.locate(s, SectionRef(chapterNumber = 3)) is SectionMatch.NotFound)
        assertTrue(ThesisSectionLocator.locate(s, SectionRef(title = "Sommaire")) is SectionMatch.NotWritable)
        val missing = ThesisSectionLocator.locate(s, SectionRef(kind = SectionKind.ABSTRACT)) as SectionMatch.NotFound
        assertTrue(missing.available.none { !it.kind.writable })
        val twoChapters = ThesisSectionLocator.locate(s, SectionRef(title = "Chapitre")) as SectionMatch.Ambiguous
        assertEquals(listOf("ch1", "ch2"), twoChapters.sections.map { it.id })
        assertEquals("m3", ThesisSectionLocator.paragraph(s, "meth", 2)?.id)
        assertEquals("m3", ThesisSectionLocator.paragraph(s, "meth", -1)?.id)
        assertNull(ThesisSectionLocator.paragraph(s, "meth", 3))
    }

    // ------------------------------------------------------------------ tables

    private fun cells(vararg row: String) = row.map { TableCell(it) }
    private fun table(uncertain: Boolean = false) = ExtractedBlock.Table(
        listOf(cells("Minéral", "Tonnes"), cells("Fer", "1 200"), cells("Zinc", "300")), header = true, rtl = false, uncertain = uncertain
    )
    private fun page(i: Int, vararg blocks: ExtractedBlock) = ExtractedPage(i, PageSource.PDF_TEXT, blocks.toList(), sourcePage = i)

    @Test
    fun tableFinder_captionStartsTheBlock_tableNextToIt_numbersNormalized() {
        val doc = ExtractedDocument(listOf(
            page(0, ExtractedBlock.Paragraph("Voir le Tableau 03 plus loin.", false)),
            page(1, ExtractedBlock.Caption("Tableau 03 : Production minière", false), table()),
            page(2, table(), ExtractedBlock.Caption("Tableau 4 - Réserves", false))
        ))
        val found = (TableFinder.find(doc, "03") as TableSearch.Found).table
        assertEquals(2, found.page)
        assertEquals("Production minière", TableFinder.captionText(found.caption, "3"))
        // A caption under its table is found too.
        assertEquals(3, (TableFinder.find(doc, "4") as TableSearch.Found).table.page)
        assertTrue(TableFinder.find(doc, "7") is TableSearch.NotFound)
        assertTrue(TableFinder.find(doc, "30") is TableSearch.NotFound)
        // Arabic caption with Arabic digits.
        val ar = ExtractedDocument(listOf(page(0, ExtractedBlock.Caption("الجدول ٣: الإنتاج", true), table())))
        assertTrue(TableFinder.find(ar, "3") is TableSearch.Found)
    }

    @Test
    fun tableFinder_twoCaptions_askWhichPage_captionWithoutStructure_isReported() {
        val twice = ExtractedDocument(listOf(
            page(0, ExtractedBlock.Caption("Table 3: Annual output", false), table()),
            page(5, ExtractedBlock.Caption("Table 3: Annual output (continued)", false), table())
        ))
        assertEquals(listOf(1, 6), (TableFinder.find(twice, "3") as TableSearch.Ambiguous).pages)
        val noRows = ExtractedDocument(listOf(page(1, ExtractedBlock.Caption("Table 3: Output", false), ExtractedBlock.Paragraph("Iron 1200 Zinc 300", false))))
        assertEquals(2, (TableFinder.find(noRows, "3") as TableSearch.NoStructure).page)
    }

    // ------------------------------------------------------------------ planning and checks

    private var n = 0
    private val newId = { "new-${n++}" }

    @Test
    fun insertParagraphs_atTheEndOfTheSection_onlyThatSectionChanges() {
        val s = thesis()
        val plan = ThesisEditPlanner.insertParagraphs("p", s, "intro", listOf("Added one.", " ", "Added two."), atStart = false, newId = newId)
        val after = ThesisOperations.apply(s, plan.ops)
        assertEquals(listOf("i1", "i2") + plan.newElementIds, after.elementsOf("intro").map { it.id })
        assertEquals(emptyList<String>(), ThesisEditPlanner.validate(after, plan))
        // Anything else changed too: reported.
        val tampered = ThesisOperations.apply(after, listOf(ThesisOp.ReplaceText("c1", "changed")))
        assertTrue(ThesisEditPlanner.validate(tampered, plan).contains("other_sections_changed"))
        // Not applied: reported.
        assertTrue(ThesisEditPlanner.validate(s, plan).contains("element_missing"))
    }

    @Test
    fun insertTable_keepsRowsColumnsAndCaption_andAtStart() {
        val s = thesis()
        val found = FoundTable(2, "Tableau 03 : Production minière", table())
        val data = ThesisEditPlanner.tableData(found, "3")
        assertEquals("Production minière", data.caption)
        assertEquals(listOf("Minéral", "Tonnes"), data.tableHeader)
        val plan = ThesisEditPlanner.insertTable("p", s, "ch2", data, atStart = true, newId = newId)
        assertEquals(3 to 2, plan.tableShape)
        val after = ThesisOperations.apply(s, plan.ops)
        assertEquals(plan.newElementIds.single(), after.elementsOf("ch2").first().id)
        assertEquals(emptyList<String>(), ThesisEditPlanner.validate(after, plan))
        val broken = ThesisOperations.apply(after, listOf(ThesisOp.UpdateData(plan.newElementIds.single(), data.copy(tableRows = data.tableRows.dropLast(1)))))
        assertTrue(ThesisEditPlanner.validate(broken, plan).containsAll(listOf("table_differs", "table_shape")))
    }

    @Test
    fun replaceParagraph_onlyThatParagraph_andThePlanSurvivesItsJsonForm() {
        val s = thesis()
        val plan = ThesisEditPlanner.replaceParagraph("p", s, "meth", 2, "New method.")!!
        assertEquals("m3", plan.replacedElementId)
        assertEquals("Old method two.", plan.replacedText)
        assertNull(ThesisEditPlanner.replaceParagraph("p", s, "meth", 5, "x"))
        val decoded = PlanCodec.decode(PlanCodec.encode(plan))
        assertEquals(plan, decoded)
        val after = ThesisOperations.apply(s, decoded.ops)
        assertEquals("New method.", after.element("m3")?.text)
        assertEquals("Old method one.", after.element("m2")?.text)
        assertEquals(emptyList<String>(), ThesisEditPlanner.validate(after, decoded))
        assertNotNull(ThesisEditPlanner.otherSectionsHash(s, "meth"))
    }

    @Test
    fun generatedSections_refuseContent() {
        val s = thesis()
        val failed = runCatching { ThesisEditPlanner.insertParagraphs("p", s, "toc", listOf("x"), false, newId) }
        assertTrue(failed.isFailure)
    }
}
