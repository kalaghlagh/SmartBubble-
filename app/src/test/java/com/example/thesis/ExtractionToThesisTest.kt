package com.example.thesis

import com.example.extraction.Alignment
import com.example.extraction.ExtractedBlock
import com.example.extraction.ExtractedDocument
import com.example.extraction.ExtractedPage
import com.example.extraction.PageSource
import com.example.extraction.TableCell
import com.example.thesis.core.ExtractionInsertMode
import com.example.thesis.core.ExtractionToThesis
import com.example.thesis.model.ElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Extracted content turned into section editor text, exactly as extracted, with real tables. */
class ExtractionToThesisTest {

    private fun cells(vararg t: String) = t.map { TableCell(it) }

    private val doc = ExtractedDocument(listOf(
        ExtractedPage(0, PageSource.IMAGE_OCR, listOf(
            ExtractedBlock.Heading("2.1 Zone d'étude", 2, rtl = false),
            ExtractedBlock.Paragraph("La région dépend des eaux souterraines.", rtl = false),
            ExtractedBlock.ListItem("premier puits", "1)", ordered = true, level = 0, rtl = false),
            ExtractedBlock.ListItem("second puits", "2)", ordered = true, level = 0, rtl = false),
            ExtractedBlock.PageNumber("12")
        )),
        ExtractedPage(1, PageSource.PDF_TEXT, listOf(
            ExtractedBlock.Table(listOf(cells("Produit", "Quantité", "Prix"), cells("Produit A", "10", "200"), listOf(TableCell("Total", 2), TableCell("200"))), header = true, rtl = false),
            ExtractedBlock.Paragraph("Résumé centré", rtl = false, alignment = Alignment.CENTER),
            ExtractedBlock.Paragraph("تعتمد المنطقة على المياه الجوفية", rtl = true)
        ))
    ))

    private fun token(n: Int) = "[Tableau $n]"

    @Test
    fun atCursor_keepsHeadingsNumberingListsAlignmentAndTables() {
        val ins = ExtractionToThesis.build(doc, ExtractionInsertMode.AT_CURSOR, ::token)
        assertEquals(
            "## 2.1 Zone d'étude\n\nLa région dépend des eaux souterraines.\n\n1) premier puits\n2) second puits\n\n[Tableau 1]\n\n{center} Résumé centré\n\nتعتمد المنطقة على المياه الجوفية",
            ins.text
        )
        assertEquals(1, ins.tables)
        val t = ins.media.getValue("[Tableau 1]")
        assertEquals(ElementType.TABLE, t.type)
        assertEquals(listOf("Produit", "Quantité", "Prix"), t.data.tableHeader)
        assertEquals(listOf("Produit A", "10", "200"), t.data.tableRows[0])
        // A merged cell keeps its text in the first column it covers; nothing is invented.
        assertEquals(listOf("Total", "", "200"), t.data.tableRows[1])
        // Page numbers of the source are not content.
        assertFalse(ins.text.contains("12"))
    }

    @Test
    fun otherModes_paragraphsHeadingAndTablesOnly() {
        val p = ExtractionToThesis.build(doc, ExtractionInsertMode.AS_PARAGRAPHS, ::token).text
        assertTrue(p.startsWith("2.1 Zone d'étude\n\nLa région"))
        assertFalse(p.contains("#"))
        assertTrue(p.contains("[Tableau 1]"))

        val h = ExtractionToThesis.build(doc, ExtractionInsertMode.HEADING_AND_CONTENT, ::token).text
        assertTrue(h.startsWith("# 2.1 Zone d'étude\n\nLa région"))

        val t = ExtractionToThesis.build(doc, ExtractionInsertMode.TABLES_ONLY, ::token)
        assertEquals("[Tableau 1]", t.text)
        assertEquals(1, t.media.size)

        val none = ExtractionToThesis.build(ExtractedDocument(listOf(doc.pages[0])), ExtractionInsertMode.TABLES_ONLY, ::token)
        assertTrue(none.text.isBlank())
    }

    @Test
    fun placement_atTheCursorLine_orAtTheEnd_keepingExistingText() {
        val ins = ExtractionToThesis.Insertion("NEW", emptyMap(), 0)
        val draft = "First paragraph.\n\nSecond paragraph."
        // Cursor inside the first paragraph: after its line, never in the middle of a word.
        assertEquals("First paragraph.\n\nNEW\n\nSecond paragraph.", ExtractionToThesis.place(draft, 5, ins, ExtractionInsertMode.AT_CURSOR))
        assertEquals("NEW\n\nFirst paragraph.\n\nSecond paragraph.", ExtractionToThesis.place(draft, 0, ins, ExtractionInsertMode.AT_CURSOR))
        assertEquals("First paragraph.\n\nSecond paragraph.\n\nNEW", ExtractionToThesis.place(draft, 5, ins, ExtractionInsertMode.APPEND))
        assertEquals("NEW", ExtractionToThesis.place("", 0, ins, ExtractionInsertMode.AT_CURSOR))
    }
}
