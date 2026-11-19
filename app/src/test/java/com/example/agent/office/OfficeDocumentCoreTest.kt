package com.example.agent.office

import com.example.extraction.ExtractedBlock
import com.example.extraction.ExtractedDocument
import com.example.extraction.ExtractedPage
import com.example.extraction.PageSource
import com.example.extraction.RegionMeta
import com.example.extraction.Script
import com.example.extraction.TableCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Document Reformatting core: field detection (AR/FR/EN), change requests, placement and rebuild. Pure JVM. */
class OfficeDocumentCoreTest {

    private fun blocks(vararg texts: String) = texts.mapIndexed { i, t -> DocBlock(i, 1, BlockType.PARAGRAPH, t, paragraphIndex = i) }
    private fun field(a: List<ExtractedField>, key: FieldKey) = a.filter { it.key == key }.map { it.value }

    private val frenchLetter = blocks(
        "Ahmed Benali\nOuargla\nTél : 0661 23 45 67\nEmail : a.benali@mail.dz",
        "À Monsieur le Directeur du Bureau de distribution de carburant",
        "Objet : Demande d'autorisation d'approvisionnement",
        "Réf : REF-2026-015",
        "Ouargla, le 15/10/2026",
        "Je vous prie de bien vouloir m'accorder l'autorisation demandée.",
        "Signature"
    )

    @Test
    fun french_letter_fieldsWithTheirPlace() {
        val f = FieldDetector.detect(frenchLetter)
        assertEquals(listOf("Demande d'autorisation d'approvisionnement"), field(f, FieldKey.SUBJECT))
        assertEquals(listOf("REF-2026-015"), field(f, FieldKey.REFERENCE))
        assertEquals(listOf("15/10/2026"), field(f, FieldKey.DATE))
        assertEquals(listOf("0661 23 45 67"), field(f, FieldKey.PHONE))
        assertEquals(listOf("a.benali@mail.dz"), field(f, FieldKey.EMAIL))
        assertEquals(1, field(f, FieldKey.RECIPIENT).size)
        // Every value is exactly at its offset in its block.
        f.forEach { x -> assertEquals(x.value, frenchLetter[x.block].text.substring(x.start, x.start + x.value.length)) }
        val a = FieldDetector.analyze("DOCX", 1, frenchLetter, scanned = false)
        assertTrue(a.mentionsSignature)
        assertNull(a.protectedKind)
        assertFalse(a.rtl)
    }

    @Test
    fun arabic_and_english_fields() {
        val ar = FieldDetector.detect(blocks("الموضوع: طلب رخصة تموين", "التاريخ: 15/10/2026", "المرجع: REF-2026-015", "إلى السيد مدير مكتب توزيع الوقود", "الهاتف: 0661234567"))
        assertEquals(listOf("طلب رخصة تموين"), field(ar, FieldKey.SUBJECT))
        assertEquals(listOf("15/10/2026"), field(ar, FieldKey.DATE))
        assertEquals(listOf("REF-2026-015"), field(ar, FieldKey.REFERENCE))
        assertEquals(1, field(ar, FieldKey.RECIPIENT).size)
        assertEquals(listOf("0661234567"), field(ar, FieldKey.PHONE))
        val en = FieldDetector.detect(blocks("Subject: Request for fuel supply authorization", "To: Fuel Distribution Office", "Date: October 15, 2026", "Phone: +213 661 234 567", "Employee ID: 4521"))
        assertEquals(listOf("Request for fuel supply authorization"), field(en, FieldKey.SUBJECT))
        assertEquals(listOf("Fuel Distribution Office"), field(en, FieldKey.RECIPIENT))
        assertEquals(listOf("October 15, 2026"), field(en, FieldKey.DATE))
        assertEquals(listOf("+213 661 234 567"), field(en, FieldKey.PHONE))
        assertEquals(listOf("Employee ID" to "4521"), en.filter { it.key == FieldKey.OTHER }.map { it.label to it.value })
    }

    @Test
    fun protectedDocuments_andUncertainOcrValues() {
        assertEquals("identity", FieldDetector.protectedKind("RÉPUBLIQUE ... PASSEPORT N° 123"))
        assertEquals("identity", FieldDetector.protectedKind("بطاقة التعريف الوطنية"))
        assertEquals("diploma", FieldDetector.protectedKind("Diplôme de Master"))
        assertNull(FieldDetector.protectedKind("Demande d'autorisation d'approvisionnement"))
        val scanned = listOf(DocBlock(0, 1, BlockType.PARAGRAPH, "Objet : Demande", confidence = 0.4f))
        assertTrue(FieldDetector.detect(scanned).single().uncertain)
    }

    @Test
    fun extractedPdfBlocks_keepStructureAndCertainty() {
        val page = ExtractedPage(0, PageSource.PDF_OCR, listOf(
            ExtractedBlock.Heading("Demande", 1, false),
            ExtractedBlock.Paragraph("Objet : Test", false, region = RegionMeta("r", null, Script.LATIN, 0.5f, needsReview = true)),
            ExtractedBlock.Table(listOf(listOf(TableCell("A"), TableCell("B")), listOf(TableCell("1", colSpan = 2))), header = true, rtl = false),
            ExtractedBlock.PageNumber("1")
        ), sourcePage = 2)
        val b = blocksOf(ExtractedDocument(listOf(page)))
        assertEquals(listOf(BlockType.HEADING, BlockType.PARAGRAPH, BlockType.TABLE), b.map { it.type })
        assertEquals(3, b[0].page)
        assertTrue(b[1].uncertain)
        assertEquals(listOf(listOf("A", "B"), listOf("1", "")), b[2].rows)
    }

    // ------------------------------------------------------------------ change requests

    @Test
    fun changeRequests_inThreeLanguages_severalAtOnce_exactTexts_andFormat() {
        val en = ChangeRequestParser.parse("change the date to 20/10/2026 and the reference to REF-2026-020, in PDF")
        assertEquals(listOf(FieldKey.DATE to "20/10/2026", FieldKey.REFERENCE to "REF-2026-020"), en.changes.map { it.key to it.newValue })
        assertEquals("PDF", en.format)
        val fr = ChangeRequestParser.parse("remplace l'objet par « Demande de renouvellement »")
        assertEquals(listOf(FieldKey.SUBJECT to "Demande de renouvellement"), fr.changes.map { it.key to it.newValue })
        assertNull(fr.format)
        val ar = ChangeRequestParser.parse("غيّر التاريخ إلى 20/10/2026")
        assertEquals(listOf(FieldKey.DATE to "20/10/2026"), ar.changes.map { it.key to it.newValue })
        val exact = ChangeRequestParser.parse("replace \"Ouargla\" with \"Alger\"")
        assertEquals(listOf("Ouargla" to "Alger"), exact.changes.map { it.oldText to it.newValue })
        assertTrue(exact.unreadable.toString(), exact.unreadable.isEmpty())
        val lines = ChangeRequestParser.parse("Date: 01/01/2027\nTéléphone : 0770 00 00 00")
        assertEquals(listOf(FieldKey.DATE to "01/01/2027", FieldKey.PHONE to "0770 00 00 00"), lines.changes.map { it.key to it.newValue })
        // A label the document itself uses.
        val custom = ChangeRequestParser.parse("change Employee ID to 9999", listOf(ExtractedField(FieldKey.OTHER, "Employee ID", "4521", 0, 13)))
        assertEquals(listOf("Employee ID" to "9999"), custom.changes.map { it.label to it.newValue })
        assertTrue(ChangeRequestParser.parse("make it nicer please").changes.isEmpty())
    }

    // ------------------------------------------------------------------ placement and rebuild

    @Test
    fun plan_onlyTheRequestedTexts_reportsMissingAmbiguousAndConflicts() {
        val a = FieldDetector.analyze("DOCX", 1, frenchLetter, false)
        val changes = DocumentRebuilder.plan(a, listOf(Modification(FieldKey.DATE, newValue = "20/10/2026"), Modification(oldText = "Ouargla", newValue = "Alger")))
        val after = DocumentRebuilder.apply(a, changes)
        assertEquals("Ouargla, le 15/10/2026".replace("15/10/2026", "20/10/2026").replace("Ouargla", "Alger"), after[4].text)
        assertTrue(after[0].text.contains("Alger"))
        // Everything else is word for word the same.
        listOf(1, 2, 3, 5, 6).forEach { assertEquals(frenchLetter[it].text, after[it].text) }
        fun code(block: () -> Unit) = try { block(); fail(); "" } catch (e: ChangeException) { e.code }
        assertEquals("field_not_found:address", code { DocumentRebuilder.plan(a, listOf(Modification(FieldKey.ADDRESS, newValue = "x"))) })
        assertTrue(code { DocumentRebuilder.plan(a, listOf(Modification(oldText = "Paris", newValue = "x"))) }.startsWith("text_not_found"))
        val twoDates = FieldDetector.analyze("DOCX", 1, blocks("Date : 15/10/2026", "Valable jusqu'au 30/11/2026"), false)
        assertTrue(code { DocumentRebuilder.plan(twoDates, listOf(Modification(FieldKey.DATE, newValue = "1/1/2027"))) }.startsWith("field_ambiguous:date:"))
        assertTrue(code { DocumentRebuilder.plan(a, listOf(Modification(FieldKey.DATE, newValue = "1"), Modification(oldText = "15/10/2026", newValue = "2"))) }.startsWith("conflicting_changes"))
        assertEquals("no_changes", code { DocumentRebuilder.plan(a, emptyList()) })
    }

    @Test
    fun wordReplacements_targetTheRightParagraphAndOccurrence() {
        val b = blocks("Intro", "Ouargla et Ouargla", "Fin")
        val a = FieldDetector.analyze("DOCX", 1, b, false)
        val changes = listOf(PlannedChange(1, 11, "Ouargla", "Alger", "x"))
        val r = DocumentRebuilder.docxReplacements(a, changes).single()
        assertEquals(1, r.index)
        assertEquals(1, r.occurrence)
        assertEquals("Ouargla et Alger", DocumentRebuilder.apply(a, changes)[1].text)
    }

    @Test
    fun wordModel_headingsParagraphsListsTablesAndDirection() {
        val m = DocumentRebuilder.wordModel(listOf(
            DocBlock(0, 1, BlockType.PARAGRAPH, "Préambule"),
            DocBlock(1, 1, BlockType.HEADING, "الموضوع", rtl = true),
            DocBlock(2, 1, BlockType.PARAGRAPH, "نص الطلب", rtl = true),
            DocBlock(3, 1, BlockType.LIST_ITEM, "- point"),
            DocBlock(4, 1, BlockType.TABLE, "", rows = listOf(listOf("A", "B"), listOf("1", "2")))
        ))
        assertEquals("", m.title)
        assertEquals(listOf("", "الموضوع", ""), m.sections.map { it.heading })
        assertEquals(listOf("نص الطلب"), m.sections[1].paragraphs)
        assertEquals(true, m.sections[1].isRtl)
        assertEquals(listOf("A", "B"), m.sections[2].table?.headers)
    }

    @Test
    fun fileRequests_libraryOnly_verbMustActOnTheFile_generalChatUntouched() {
        val lib = listOf(
            com.example.agent.core.LibraryRef("1", "Rapport annuel.pdf", "PDF"),
            com.example.agent.core.LibraryRef("2", "Lettre.docx", "DOCX"),
            com.example.agent.core.LibraryRef("t", "Modèle lettre.docx", "DOCX", com.example.agent.core.FileSource.MY_TEMPLATE)
        )
        fun p(t: String) = FileRequestParser.parse(t, lib)
        assertEquals(FileRequest(FileOp.LIST, type = "PDF"), p("List my PDF files"))
        assertEquals(FileOp.LIST, p("Affiche mes documents")?.op)
        assertEquals(FileOp.LIST, p("اعرض ملفاتي")?.op)
        assertEquals(FileRequest(FileOp.FIND, query = "budget 2025"), p("Find budget 2025 in my library"))
        assertEquals("Bilan 2025", p("Rename Rapport annuel.pdf to Bilan 2025")?.newName)
        assertEquals(FileOp.RENAME, p("renomme Lettre.docx en Courrier final")?.op)
        assertEquals(FileOp.DELETE, p("supprime le fichier Lettre.docx")?.op)
        assertEquals(FileOp.DELETE, p("احذف Lettre.docx")?.op)
        assertEquals(FileOp.COPY, p("duplicate Rapport annuel.pdf")?.op)
        assertEquals(FileRequest(FileOp.UNSUPPORTED, what = "merge"), p("Merge Rapport annuel.pdf with another PDF"))
        // Not a file operation on the file itself, or not about files at all.
        assertNull(p("remove the date from Lettre.docx"))
        assertNull(p("Show me how to write a CV document"))
        assertNull(p("Find a good title for my essay"))
        assertNull(p("Split the text into short paragraphs"))
        // Templates are not Library files here.
        assertNull(p("delete Modèle lettre.docx"))
    }

    @Test
    fun analysisSurvivesItsJsonForm_andFieldNamesAreLocalized() {
        val a = FieldDetector.analyze("PDF", 2, frenchLetter, scanned = true)
        assertEquals(a, OfficeCodec.decode(OfficeCodec.encode(a)))
        val changes = listOf(PlannedChange(4, 12, "15/10/2026", "20/10/2026", "date"))
        assertEquals(changes, OfficeCodec.decodeChanges(OfficeCodec.encodeChanges(changes)))
        assertEquals("• Date: 1\nObjet: 2", localizeFieldNames("• date: 1\nObjet: 2") { if (it == FieldKey.DATE) "Date" else "?" })
    }
}
