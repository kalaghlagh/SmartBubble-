package com.example.thesis

import com.example.thesis.ai.ThesisAiFormatException
import com.example.thesis.ai.ThesisAiProtocol
import com.example.thesis.core.ChangeKind
import com.example.thesis.core.EditMode
import com.example.thesis.core.NewElement
import com.example.thesis.core.SegmentKind
import com.example.thesis.core.ThesisCommandParser
import com.example.thesis.core.ThesisContentSplitter
import com.example.thesis.core.ThesisContext
import com.example.thesis.core.ThesisDiff
import com.example.thesis.core.ThesisDuplicates
import com.example.thesis.core.ThesisIntent
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOpException
import com.example.thesis.core.ThesisOperations
import com.example.thesis.core.ThesisStructure
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisCodec
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Thesis domain logic (pure JVM, no mocks): structure, operations, numbering, diff, parsing, AI validation. */
class ThesisCoreTest {

    private fun newThesis(language: String = "en", level: AcademicLevel = AcademicLevel.MASTER) =
        ThesisSnapshot(ThesisMeta("Groundwater quality", level, language), ThesisStructure.defaultSections(language, level), emptyList())

    private fun ThesisSnapshot.kind(k: SectionKind) = sections.first { it.kind == k }
    private fun ThesisSnapshot.chapters() = outline(false).map { it.first }.filter { it.kind == SectionKind.CHAPTER }

    private fun para(text: String) = NewElement(ThesisStructure.newId("el"), ElementType.PARAGRAPH, text)

    @Test
    fun defaultStructure_perLevelAndLanguage() {
        val master = newThesis()
        assertEquals(3, master.chapters().size)
        assertEquals(2, newThesis(level = AcademicLevel.LICENCE).chapters().size)
        assertEquals("General Introduction", master.kind(SectionKind.INTRODUCTION).title)
        assertEquals("Introduction générale", newThesis("fr").kind(SectionKind.INTRODUCTION).title)
        assertEquals("مقدمة عامة", newThesis("ar").kind(SectionKind.INTRODUCTION).title)
        assertFalse(master.kind(SectionKind.ABBREVIATIONS).enabled) // only when needed
        assertTrue(master.kind(SectionKind.TABLE_OF_CONTENTS).kind.generated)
    }

    @Test
    fun operations_addMoveReplaceDeleteAndSections() {
        var s = newThesis()
        val intro = s.kind(SectionKind.INTRODUCTION).id
        val a = para("First paragraph of the introduction.")
        val b = para("Second paragraph.")
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddElements(intro, null, listOf(a, b))))
        assertEquals(listOf(a.id, b.id), s.elementsOf(intro).map { it.id })
        val c = para("Inserted at the start.")
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddElements(intro, ThesisOperations.AFTER_START, listOf(c))))
        assertEquals(listOf(c.id, a.id, b.id), s.elementsOf(intro).map { it.id })

        val ch1 = s.chapters()[0].id
        s = ThesisOperations.apply(s, listOf(ThesisOp.MoveElement(b.id, ch1, null), ThesisOp.ReplaceText(a.id, "Edited.")))
        assertEquals(ch1, s.element(b.id)!!.sectionId)
        assertEquals("Edited.", s.element(a.id)!!.text)

        val sec = ThesisSection(ThesisStructure.newId("sec"), ch1, SectionKind.SECTION, "Study area", 0)
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddSection(sec, null)))
        val ch2 = s.chapters()[1].id
        s = ThesisOperations.apply(s, listOf(ThesisOp.MoveSection(sec.id, ch2, null)))
        assertEquals(ch2, s.section(sec.id)!!.parentId)
        try {
            ThesisOperations.apply(s, listOf(ThesisOp.MoveSection(ch2, sec.id, null)))
            fail("a section cannot move inside itself")
        } catch (e: ThesisOpException) {
        }
        // Generated sections never receive content.
        try {
            ThesisOperations.apply(s, listOf(ThesisOp.AddElements(s.kind(SectionKind.TABLE_OF_CONTENTS).id, null, listOf(para("x")))))
            fail("expected refusal")
        } catch (e: ThesisOpException) {
        }
        s = ThesisOperations.apply(s, listOf(ThesisOp.DeleteSection(ch2)))
        assertNull(s.section(sec.id))
        // The input snapshot is never modified.
        assertEquals(0, newThesis().elements.size)
    }

    @Test
    fun operationsAndSnapshots_roundTripThroughJson() {
        var s = newThesis("ar")
        val ops = listOf(
            ThesisOp.AddElements(s.kind(SectionKind.INTRODUCTION).id, null, listOf(para("نص الفقرة الأولى."),
                NewElement("el-t", ElementType.TABLE, "", ElementData(tableHeader = listOf("Ion", "mg/L"), tableRows = listOf(listOf("Ca", "12.5")), caption = "Analyses")))),
            ThesisOp.UpdateSection(s.chapters()[0].id, title = "الإطار النظري")
        )
        assertEquals(ops, ThesisOperations.decode(ThesisOperations.encode(ops)))
        s = ThesisOperations.apply(s, ops)
        assertEquals(s, ThesisCodec.decodeSnapshot(ThesisCodec.encodeSnapshot(s)))
    }

    @Test
    fun numbering_chaptersSectionsFiguresTablesAndCrossReferences() {
        var s = newThesis()
        val (c1, c2) = s.chapters()
        val sec = ThesisSection("sec-x", c2.id, SectionKind.SECTION, "Methods", 0)
        val fig1 = NewElement("fig-1", ElementType.IMAGE, "", ElementData(assetId = "a1", caption = "Map"))
        val fig2 = NewElement("fig-2", ElementType.IMAGE, "", ElementData(assetId = "a2", caption = "Profile"))
        val ref = para("As shown in [[fig:fig-2]] and [[tab:none]].")
        s = ThesisOperations.apply(s, listOf(
            ThesisOp.UpdateSection(c1.id, title = "Context"), ThesisOp.AddSection(sec, null),
            ThesisOp.AddElements(c1.id, null, listOf(fig1)), ThesisOp.AddElements(sec.id, null, listOf(fig2, ref))
        ))
        var n = ThesisStructure.numbering(s)
        assertEquals("Chapter 1: Context", ThesisStructure.displayTitle(s.section(c1.id)!!, n, "en"))
        assertEquals("2.1 Methods", ThesisStructure.displayTitle(s.section(sec.id)!!, n, "en"))
        assertEquals("Figure 2: Profile", ThesisStructure.figureCaption(s.element("fig-2")!!, n, "en"))
        val (text, missing) = ThesisStructure.resolveCrossReferences(ref.text, n, "fr")
        assertEquals("As shown in Figure 2 and Tableau ?.", text)
        assertEquals(listOf("none"), missing)
        // Reordering renumbers: moving figure 2 before figure 1.
        s = ThesisOperations.apply(s, listOf(ThesisOp.MoveElement("fig-2", c1.id, ThesisOperations.AFTER_START)))
        n = ThesisStructure.numbering(s)
        assertEquals(1, n.figures["fig-2"])
        assertEquals(2, n.figures["fig-1"])
        // Deleting renumbers too.
        s = ThesisOperations.apply(s, listOf(ThesisOp.DeleteElement("fig-2")))
        assertEquals(1, ThesisStructure.numbering(s).figures["fig-1"])
        assertEquals("الشكل 1: Map", ThesisStructure.figureCaption(s.element("fig-1")!!, ThesisStructure.numbering(s), "ar"))
    }

    @Test
    fun diff_addedDeletedModifiedMovedAndFormatOnly() {
        var a = newThesis()
        val intro = a.kind(SectionKind.INTRODUCTION).id
        val p1 = para("Water samples were collected in 2023 from 12 wells.")
        val p2 = para("The aquifer is shallow.")
        val img = NewElement("img", ElementType.IMAGE, "", ElementData(assetId = "x", caption = "Wells", widthPercent = 80))
        a = ThesisOperations.apply(a, listOf(ThesisOp.AddElements(intro, null, listOf(p1, p2, img))))
        val p3 = para("A new paragraph.")
        val b = ThesisOperations.apply(a, listOf(
            ThesisOp.ReplaceText(p1.id, "Water samples were collected in 2023 from twelve wells."),
            ThesisOp.DeleteElement(p2.id),
            ThesisOp.AddElements(intro, null, listOf(p3)),
            ThesisOp.UpdateData("img", img.data.copy(widthPercent = 60))
        ))
        val d = ThesisDiff.compare(a, b)
        val kinds = d.elements.associate { it.elementId to it.kind }
        assertEquals(ChangeKind.MODIFIED, kinds[p1.id])
        assertEquals(ChangeKind.DELETED, kinds[p2.id])
        assertEquals(ChangeKind.ADDED, kinds[p3.id])
        assertEquals(ChangeKind.FORMAT_ONLY, kinds["img"])
        val segments = d.elements.first { it.elementId == p1.id }.segments
        assertTrue(segments.any { it.kind == SegmentKind.DELETED && it.text == "12" })
        assertTrue(segments.any { it.kind == SegmentKind.INSERTED && it.text == "twelve" })
        // Whitespace only: formatting, not a text change.
        val ws = ThesisOperations.apply(a, listOf(ThesisOp.ReplaceText(p2.id, "The  aquifer is\nshallow.")))
        assertEquals(ChangeKind.FORMAT_ONLY, ThesisDiff.compare(a, ws).elements.single().kind)
        // Moving to another chapter.
        val moved = ThesisOperations.apply(a, listOf(ThesisOp.MoveElement(p2.id, a.chapters()[0].id, null)))
        assertEquals(ChangeKind.MOVED, ThesisDiff.compare(a, moved).elements.single().kind)
        assertTrue(ThesisDiff.compare(a, a).isEmpty)
    }

    @Test
    fun parser_destinationsAndCommands_inThreeLanguages() {
        var s = newThesis()
        val ch = s.chapters()
        val sec = ThesisSection("sec-12", ch[0].id, SectionKind.SECTION, "Geology", 0)
        val sec2 = ThesisSection("sec-13", ch[0].id, SectionKind.SECTION, "Hydrology", 1)
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddSection(sec, null), ThesisOp.AddSection(sec2, sec.id)))
        val content = "Long pasted paragraph about the study area and its geological context in detail."

        val add = ThesisCommandParser.parse("I want to add this content to Chapter 1, Section 2.\n$content", s) as ThesisIntent.AddContent
        assertEquals("sec-13", add.target!!.sectionId)
        assertEquals(content, add.content)
        assertEquals("sec-12", (ThesisCommandParser.parse("Ajoute ceci au chapitre 1 section 1 :\n$content", s) as ThesisIntent.AddContent).target!!.sectionId)
        assertEquals(s.kind(SectionKind.INTRODUCTION).id, (ThesisCommandParser.parse("أضف هذا إلى المقدمة\n$content", s) as ThesisIntent.AddContent).target!!.sectionId)
        assertEquals(ch[1].id, (ThesisCommandParser.parse("أضف هذا إلى الفصل الثاني\n$content", s) as ThesisIntent.AddContent).target!!.sectionId)
        assertEquals(s.kind(SectionKind.CONCLUSION).id, (ThesisCommandParser.parse("Add this to the conclusion\n$content", s) as ThesisIntent.AddContent).target!!.sectionId)

        // No destination named: content only, the destination is left open (never guessed here).
        val plain = ThesisCommandParser.parse(content, s) as ThesisIntent.AddContent
        assertNull(plain.target)
        // A command word inside a sentence is prose, not an instruction.
        val prose = "In summary, the results show that nitrate levels exceed the limit in three wells."
        assertEquals(prose, (ThesisCommandParser.parse(prose, s) as ThesisIntent.AddContent).content)

        val replace = ThesisCommandParser.parse("Replace the second paragraph in the introduction\nNew text for it.", s) as ThesisIntent.ReplaceParagraph
        assertEquals(2, replace.target.paragraph)
        val edit = ThesisCommandParser.parse("Correct the grammar of chapter 2 without changing the meaning", s) as ThesisIntent.Edit
        assertEquals(EditMode.CORRECT, edit.mode)
        assertEquals(ch[1].id, edit.target!!.sectionId)
        assertEquals(EditMode.IMPROVE, (ThesisCommandParser.parse("Améliore le style académique de l'introduction", s) as ThesisIntent.Edit).mode)
        assertEquals(EditMode.SHORTEN, (ThesisCommandParser.parse("اختصر الخاتمة", s) as ThesisIntent.Edit).mode)
        assertTrue(ThesisCommandParser.parse("Restore the previous version", s) is ThesisIntent.RestorePrevious)
        assertTrue(ThesisCommandParser.parse("Show me all changes made today", s) is ThesisIntent.ShowHistory)
        val move = ThesisCommandParser.parse("Move section 1.2 to chapter 3", s) as ThesisIntent.MoveSection
        assertEquals("sec-13", move.target.sectionId)
        assertEquals(ch[2].id, move.destination.sectionId)
        assertEquals("Results", (ThesisCommandParser.parse("Add a new chapter named \"Results\"", s) as ThesisIntent.AddChapter).title)
        val image = ThesisCommandParser.resolveTarget("Insert this image after paragraph 3 of chapter 2", s)!!
        assertEquals(3, image.afterParagraph)
    }

    @Test
    fun splitter_keepsWordsAndDetectsStructure() = runBlocking {
        val text = "Context\n\nGroundwater supplies 70% of the region (Smith, 2019).\n\n- Wells: 12\n- Springs: 4\n\n| Ion | mg/L |\n|---|---|\n| Ca | 12.5 |"
        val els = ThesisContentSplitter().split(text)
        assertEquals(listOf(ElementType.HEADING, ElementType.PARAGRAPH, ElementType.LIST_ITEM, ElementType.LIST_ITEM, ElementType.TABLE), els.map { it.type })
        assertEquals("Groundwater supplies 70% of the region (Smith, 2019).", els[1].text)
        assertEquals(listOf(listOf("Ca", "12.5")), els[4].data.tableRows)
    }

    @Test
    fun duplicates_detectRepeatedParagraphs() {
        var s = newThesis()
        val existing = para("Groundwater in the basin is mainly recharged by winter rainfall infiltrating the limestone outcrops.")
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddElements(s.kind(SectionKind.INTRODUCTION).id, null, listOf(existing))))
        val again = para("Groundwater in the basin is mainly recharged by winter rainfall infiltrating the limestone outcrops.")
        val other = para("Nitrate concentrations exceed the guideline in three wells near the farms.")
        val found = ThesisDuplicates.find(listOf(again, other), s)
        assertEquals(listOf(again.id), found.map { it.newElementId })
        assertEquals(existing.id, found.single().existingElementId)
    }

    @Test
    fun aiProtocol_rejectsUnknownIdsAndFlagsIntegrityProblems() {
        val sent = mapOf("e1" to "Rainfall was 320 mm in 2021 (Ali, 2020).", "e2" to "The method is good.")
        val response = """```json
            {"edits": [
              {"id": "e1", "text": "Rainfall reached 350 mm in 2021."},
              {"id": "e2", "text": "The method is appropriate [to verify]."},
              {"id": "e9", "text": "Invented paragraph."}
            ], "notes": "Style improved."}```"""
        val r = ThesisAiProtocol.parseEdits(response, sent, EditMode.IMPROVE)
        assertEquals(setOf("e1", "e2"), r.edits.keys)
        assertTrue("citation_removed:e1" in r.warnings)
        assertTrue("number_changed:e1" in r.warnings)
        assertTrue("number_added:e1" in r.warnings)
        assertTrue("to_verify:e2" in r.warnings)
        assertTrue("ignored:e9" in r.warnings)

        val s = newThesis()
        val ok = ThesisAiProtocol.parseDestination("""{"sectionId": "${s.kind(SectionKind.INTRODUCTION).id}", "confidence": 0.9, "reason": "general context"}""", s)
        assertEquals(0.9, ok.confidence, 0.001)
        // A generated section or an unknown id is never accepted as a destination.
        assertNull(ThesisAiProtocol.parseDestination("""{"sectionId": "${s.kind(SectionKind.TABLE_OF_CONTENTS).id}", "confidence": 1}""", s).sectionId)
        assertNull(ThesisAiProtocol.parseDestination("""{"sectionId": "made-up", "confidence": 1}""", s).sectionId)

        val data = "Calcium 12.5 mg/L, Magnesium 4.1 mg/L"
        val table = ThesisAiProtocol.parseTable("""{"header": ["Ion", "mg/L"], "rows": [["Calcium", "12.5"], ["Magnesium", "4.1"]]}""", "Ion mg/L $data")
        assertEquals(2, table.rows.size)
        try {
            ThesisAiProtocol.parseTable("""{"header": ["Ion", "mg/L"], "rows": [["Sodium", "9.9"]]}""", data)
            fail("invented values must be refused")
        } catch (e: ThesisAiFormatException) {
        }
    }

    @Test
    fun context_sendsOutlineWithoutContentAndChunksEdits() {
        var s = newThesis()
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddElements(s.kind(SectionKind.INTRODUCTION).id, null, listOf(para("Secret long body text ".repeat(20))))))
        val outline = ThesisContext.outline(s)
        assertTrue(outline.contains("kind=INTRODUCTION"))
        assertFalse(outline.contains("Secret long body text ".repeat(4))) // at most a short start
        val chunks = ThesisContext.chunks((1..10).map { "e$it" to "x".repeat(2500) }, 6000)
        assertEquals(5, chunks.size)
        assertTrue(chunks.all { c -> c.sumOf { it.second.length } <= 6000 })
    }

    @Test
    fun sectionHash_changesOnlyWhenTheSectionChanges() {
        var s = newThesis()
        val intro = s.kind(SectionKind.INTRODUCTION).id
        val concl = s.kind(SectionKind.CONCLUSION).id
        val h1 = s.sectionHash(intro)
        val h2 = s.sectionHash(concl)
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddElements(intro, null, listOf(para("Text.")))))
        assertNotEquals(h1, s.sectionHash(intro))
        assertEquals(h2, s.sectionHash(concl))
        assertEquals(0, ThesisStructure.completion(newThesis()))
        assertTrue(ThesisStructure.completion(s) > 0)
    }

    // ------------------------------------------------------------------ section editor text

    private val splitter = ThesisContentSplitter()

    private fun applyDraft(s: ThesisSnapshot, draft: com.example.thesis.core.SectionDraft) = runBlocking {
        val ops = com.example.thesis.core.SectionText.plan(s, draft) { splitter.split(it) }
        ThesisOperations.apply(s, ops) to ops
    }

    @Test
    fun sectionText_roundTripKeepsElementsAndFigurePlaces() {
        var s = newThesis()
        val intro = s.kind(SectionKind.INTRODUCTION).id
        val p1 = para("Groundwater is the main source of drinking water in the basin (Smith, 2019).")
        val h = NewElement("h1", ElementType.HEADING, "Study area", ElementData(headingLevel = 2))
        val img = NewElement("img", ElementType.IMAGE, "", ElementData(assetId = "a1", caption = "Map"))
        val p2 = para("Recharge occurs mainly in winter through limestone outcrops.")
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddElements(intro, null, listOf(p1, h, img, p2))))

        val draft = com.example.thesis.core.SectionText.load(s, intro)
        assertTrue(draft.text.contains("## Study area"))
        assertTrue(draft.text.lines().contains("[Figure 1]"))
        assertFalse(draft.dirty)
        // Saving unchanged text changes nothing.
        assertTrue(applyDraft(s, draft).second.isEmpty())

        // Edit one paragraph and add one: the other elements keep their ids, the figure its place.
        val edited = draft.copy(text = draft.text.replace("mainly in winter", "mostly in winter") + "\n\nA new closing paragraph about nitrate monitoring in wells.")
        val (after, ops) = applyDraft(s, edited)
        val ids = after.elementsOf(intro).map { it.id }
        assertEquals(listOf(p1.id, "h1", "img"), ids.take(3))
        assertEquals(ElementType.HEADING, after.element("h1")!!.type)
        assertEquals(5, ids.size)
        assertTrue(after.elementsOf(intro)[3].text.contains("mostly in winter"))
        assertTrue(ops.none { it is ThesisOp.DeleteElement && it.elementId == p1.id })

        // Moving the placeholder line moves the figure; deleting it removes the figure.
        val moved = draft.copy(text = "[Figure 1]\n\n" + draft.text.replace("[Figure 1]", ""))
        assertEquals("img", applyDraft(s, moved).first.elementsOf(intro).first().id)
        val removed = draft.copy(text = draft.text.replace("[Figure 1]", ""))
        assertNull(applyDraft(s, removed).first.element("img"))
    }

    @Test
    fun sectionText_newFigureInsertedAtPlaceholder_arabicToken() {
        var s = newThesis("ar")
        val intro = s.kind(SectionKind.INTRODUCTION).id
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddElements(intro, null, listOf(para("فقرة أولى عن المياه الجوفية في الحوض.")))))
        val draft = com.example.thesis.core.SectionText.load(s, intro)
        val token = com.example.thesis.core.SectionText.nextToken(draft, "ar", ElementType.IMAGE)
        assertEquals("[الشكل 1]", token)
        val withImage = draft.copy(
            text = token + "\n\n" + draft.text,
            media = draft.media + (token to com.example.thesis.core.DraftMedia(null, ElementType.IMAGE, ElementData(assetId = "a9", caption = "خريطة"))),
            mediaChanged = true
        )
        assertTrue(withImage.dirty)
        val after = applyDraft(s, withImage).first.elementsOf(intro)
        assertEquals(listOf(ElementType.IMAGE, ElementType.PARAGRAPH), after.map { it.type })
        assertEquals("a9", after[0].data.assetId)
        assertEquals("خريطة", after[0].data.caption)
    }

    @Test
    fun sectionStatus_andProgress() {
        var s = newThesis()
        val intro = s.kind(SectionKind.INTRODUCTION)
        assertEquals(ThesisStructure.Status.NOT_STARTED, ThesisStructure.status(s, intro))
        assertEquals(ThesisStructure.Status.AUTOMATIC, ThesisStructure.status(s, s.kind(SectionKind.TABLE_OF_CONTENTS)))
        val start = ThesisStructure.progress(s)
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddElements(intro.id, null, listOf(para("Some text.")))))
        assertEquals(ThesisStructure.Status.IN_PROGRESS, ThesisStructure.status(s, s.section(intro.id)!!))
        val mid = ThesisStructure.progress(s)
        s = ThesisOperations.apply(s, listOf(ThesisOp.UpdateSection(intro.id, completed = true)))
        assertEquals(ThesisStructure.Status.COMPLETED, ThesisStructure.status(s, s.section(intro.id)!!))
        assertTrue(start < mid && mid < ThesisStructure.progress(s))
        // A chapter is in progress when one of its sub-sections has content.
        val ch = s.chapters().first()
        val sub = ThesisSection("sub1", ch.id, SectionKind.SECTION, "Methods", 0)
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddSection(sub, null), ThesisOp.AddElements("sub1", null, listOf(para("Method text.")))))
        assertEquals(ThesisStructure.Status.IN_PROGRESS, ThesisStructure.status(s, s.section(ch.id)!!))
        // The flag survives encoding (snapshot and operation).
        val decoded = ThesisCodec.decodeSnapshot(ThesisCodec.encodeSnapshot(s))
        assertTrue(decoded.section(intro.id)!!.completed)
        val op = ThesisOperations.decode(ThesisOperations.encode(listOf(ThesisOp.UpdateSection(intro.id, completed = true))))
        assertEquals(ThesisOp.UpdateSection(intro.id, completed = true), op.single())
    }
}
