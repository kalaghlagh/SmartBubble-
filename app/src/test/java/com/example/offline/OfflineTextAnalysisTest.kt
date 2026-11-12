package com.example.offline

import com.example.offline.analysis.DocumentTypeClassifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests (pure JVM, no Android, no mocks) of the rule-based text analysis: normalization,
 * headings, lists, tables, paragraph boundaries, document type, in English, French and Arabic.
 */
class OfflineTextAnalysisTest {

    private val engine = RuleBasedFormattingEngine()

    private fun analyze(text: String): FormattingResult = runBlocking { engine.analyze(text) }

    private inline fun <reified T : StructureBlock> FormattingResult.only(): List<T> = blocks.filterIsInstance<T>()

    // ------------------------------------------------------------------ normalization

    @Test
    fun normalization_changesOnlyWhitespaceAndLineEndings() {
        val raw = "﻿Title  \r\n\r\n\r\n\r\nLine one\t\r\nLine two\rEnd last   "
        val normalized = TextNormalizationEngine.normalize(raw)
        assertEquals("Title\n\nLine one\nLine two\nEnd\nlast", normalized)
    }

    @Test
    fun normalization_keepsArabicAndFrenchCharactersExactly() {
        // Decomposed "é" (e + U+0301) must not be composed; Arabic diacritics must stay.
        val raw = "Café déjà vu — l’été\nمُحَمَّد يكتب «تقريرًا» ١٢٣"
        assertEquals(raw, TextNormalizationEngine.normalize(raw))
    }

    // ------------------------------------------------------------------ headings

    @Test
    fun headings_englishMarkdownAndInferred() {
        val text = """
            # Quarterly Report

            Revenue grew by 12% in Q3 2024 compared with the previous quarter, reaching 4.5 million.

            Key Findings

            The team delivered three projects on time and reduced costs by 8%.

            ## Next Steps
            We will hire two engineers before 15 January 2025.
        """.trimIndent()
        val r = analyze(text)
        val headings = r.only<StructureBlock.Heading>()
        assertEquals(listOf("Quarterly Report", "Key Findings", "Next Steps"), headings.map { it.text })
        assertTrue(headings[0].isTitle)
        assertEquals(StructureOrigin.EXPLICIT, headings[0].origin)
        assertEquals(StructureOrigin.INFERRED, headings[1].origin)
        assertEquals(2, headings[2].level)
        assertTrue(r.preservation.isLossless)
    }

    @Test
    fun headings_frenchNumberedSections() {
        val text = """
            1. Introduction
            Ce rapport présente les résultats de l’enquête menée en mars 2024 auprès de 250 clients.

            2. Méthodologie
            Les entretiens ont duré en moyenne 35 minutes et ont été réalisés à Lyon et à Paris.

            2.1 Échantillon
            L’échantillon comprend 120 femmes et 130 hommes âgés de 18 à 65 ans.
        """.trimIndent()
        val r = analyze(text)
        val headings = r.only<StructureBlock.Heading>()
        assertEquals(listOf("1. Introduction", "2. Méthodologie", "2.1 Échantillon"), headings.map { it.text })
        assertTrue(headings.none { it.isTitle })
        assertEquals(3, r.paragraphs.size)
        assertTrue(r.preservation.isLossless)
    }

    @Test
    fun headings_arabicStandaloneLines() {
        val text = """
            تقرير الاجتماع الشهري

            ناقش الفريق خطة العمل للربع القادم وتم الاتفاق على تخصيص ميزانية قدرها ٥٠٠٠ دينار.

            التوصيات:
            يوصي الفريق بزيادة عدد الموظفين في قسم الدعم الفني إلى ١٢ موظفا قبل نهاية السنة.
        """.trimIndent()
        val r = analyze(text)
        val headings = r.only<StructureBlock.Heading>()
        assertEquals(listOf("تقرير الاجتماع الشهري", "التوصيات:"), headings.map { it.text })
        assertTrue(headings[0].isTitle)
        assertTrue(r.preservation.isLossless)
    }

    @Test
    fun headings_sentencesAndLastLineAreNotHeadings() {
        val text = "This is a full sentence that ends with a period.\n\nShort line without period\n\nAnother complete sentence here, with a comma."
        val r = analyze(text)
        // "Short line without period" is not title case, so it stays a paragraph.
        assertTrue(r.only<StructureBlock.Heading>().none { it.text.endsWith(".") })
        assertTrue(r.only<StructureBlock.Heading>().none { it.text.startsWith("Another") })
    }

    // ------------------------------------------------------------------ lists

    @Test
    fun lists_numberedKeepNumbersAndOrder() {
        val text = "Steps to install:\n1. Download the file (25 MB).\n2. Open Settings.\n3. Restart the phone at 10:30."
        val r = analyze(text)
        val list = r.only<StructureBlock.ListBlock>().single()
        assertTrue(list.ordered)
        assertEquals(listOf("1.", "2.", "3."), list.items.map { it.marker })
        assertEquals("1. Download the file (25 MB).", list.items[0].displayText(true))
        assertEquals("Steps to install:", r.only<StructureBlock.Heading>().single().text)
        assertTrue(r.preservation.isLossless)
    }

    @Test
    fun lists_bulletsRemoveOnlyTheBulletCharacter() {
        val text = "Participants :\n- Marie Dupont\n- Jean-Luc Martin\n• Ahmed Ben Ali\n* Sophie"
        val r = analyze(text)
        val lists = r.only<StructureBlock.ListBlock>()
        // "-" and "•" are different markers: two lists, item order kept.
        assertEquals(listOf("Marie Dupont", "Jean-Luc Martin"), lists[0].items.map { it.text })
        assertFalse(lists[0].ordered)
        assertTrue(r.preservation.isLossless)
        assertTrue(r.removedMarkup.isNotEmpty())
    }

    @Test
    fun lists_arabicNumbersAndContinuationLines() {
        val text = "الأهداف\n١- تحسين جودة الخدمة\n٢- خفض التكاليف بنسبة ١٠٪\n   خلال السنة الأولى\n٣- تدريب الموظفين"
        val r = analyze(text)
        val list = r.only<StructureBlock.ListBlock>().single()
        assertEquals(3, list.items.size)
        assertEquals("خفض التكاليف بنسبة ١٠٪ خلال السنة الأولى", list.items[1].text)
        assertTrue(r.preservation.isLossless)
    }

    @Test
    fun lists_singleNumberedLineIsNotAList() {
        val r = analyze("1. Introduction\nThis document explains the new travel policy for 2025 in detail.")
        assertTrue(r.only<StructureBlock.ListBlock>().isEmpty())
    }

    // ------------------------------------------------------------------ tables

    @Test
    fun tables_markdownPipesWithSeparator() {
        val text = "| Product | Price | Qty |\n|---|---:|---|\n| Pens | 1,250.00 | 10 |\n| Paper | 3.5 | 007 |"
        val r = analyze(text)
        val table = r.only<StructureBlock.Table>().single()
        assertEquals(listOf("Product", "Price", "Qty"), table.header)
        assertEquals(listOf(listOf("Pens", "1,250.00", "10"), listOf("Paper", "3.5", "007")), table.rows)
        assertEquals(TableKind.PIPES, table.kind)
        assertTrue(r.preservation.isLossless)
        assertEquals(DocumentType.STRUCTURED_DATA, r.documentType.type)
    }

    @Test
    fun tables_tabSeparatedAndAlignedColumns() {
        val tsv = "Nom\tVille\tÂge\nAlice\tParis\t34\nBob\tLyon\t41"
        assertEquals(TableKind.TABS, analyze(tsv).only<StructureBlock.Table>().single().kind)

        val aligned = "Name      City      Score\nAlice     Paris     12\nBob       Lyon      9\nCarla     Nice      15"
        val t = analyze(aligned).only<StructureBlock.Table>().single()
        assertEquals(TableKind.ALIGNED_COLUMNS, t.kind)
        assertEquals(3, t.rows.size)
    }

    @Test
    fun tables_proseIsNeverATable() {
        val text = "We met  on Monday.  The weather was fine and everyone arrived early to the office.\n" +
            "Then we discussed  the budget for the next year, which remains a difficult topic.\n" +
            "Finally the team  agreed to meet again next week to finish the remaining items."
        assertTrue(analyze(text).only<StructureBlock.Table>().isEmpty())
    }

    // ------------------------------------------------------------------ paragraphs

    @Test
    fun paragraphs_blankLinesAlwaysSeparate_wrappedLinesJoin() {
        val text = "This paragraph was copied from a PDF and is wrapped at a fixed width\n" +
            "so its second line continues the same sentence.\n\nA second paragraph."
        val r = analyze(text)
        assertEquals(2, r.paragraphs.size)
        assertEquals(
            "This paragraph was copied from a PDF and is wrapped at a fixed width so its second line continues the same sentence.",
            r.paragraphs[0].text
        )
    }

    @Test
    fun paragraphs_ambiguousContentKeepsOriginalParagraphs() {
        val text = "hello there\nok\n\nsee you at 5\nbye"
        val r = analyze(text)
        assertTrue(r.only<StructureBlock.Heading>().isEmpty())
        assertTrue(r.only<StructureBlock.ListBlock>().isEmpty())
        assertTrue(r.only<StructureBlock.Table>().isEmpty())
        assertEquals(DocumentType.GENERAL, r.documentType.type)
        assertTrue(r.preservation.isLossless)
    }

    @Test
    fun mixedLanguages_keepEachScriptUnchanged() {
        val text = "Project Update / Mise à jour\n\nThe launch is planned for 3 March. Le lancement est prévu le 3 mars. الإطلاق مقرر في ٣ مارس.\n\n- English item\n- Élément français\n- عنصر عربي"
        val r = analyze(text)
        assertTrue(r.preservation.isLossless)
        assertEquals(3, r.only<StructureBlock.ListBlock>().single().items.size)
        assertTrue(r.paragraphs.single().text.contains("الإطلاق مقرر في ٣ مارس."))
    }

    // ------------------------------------------------------------------ document type

    @Test
    fun documentType_lettersMeetingsProposalsAndGeneral() {
        val letter = "Dear Ms. Smith,\n\nThank you for your offer dated 2 May.\n\nSincerely,\nJohn"
        assertEquals(DocumentType.LETTER, analyze(letter).documentType.type)

        val meeting = "Compte rendu de réunion\n\nParticipants : Marie, Paul\n\nOrdre du jour\n- Budget\n- Planning"
        assertEquals(DocumentType.MEETING_NOTES, analyze(meeting).documentType.type)

        val proposal = "مقترح مشروع\n\nنقترح إنشاء مركز تدريب. الأهداف: تدريب ٢٠٠ متدرب. الميزانية: ٣٠٠٠٠ دينار."
        assertEquals(DocumentType.PROPOSAL, analyze(proposal).documentType.type)

        val general = "I went to the market and bought apples. They were fresh."
        assertEquals(DocumentType.GENERAL, analyze(general).documentType.type)
        assertEquals(DocumentType.GENERAL, DocumentTypeClassifier.classify("", emptyList()).type)
    }

    // ------------------------------------------------------------------ preservation

    @Test
    fun preservation_detectsMissingChangedAndAddedWords() {
        val source = "Pay 1,200 EUR before 15/03/2025."
        val ok = listOf(StructureBlock.Paragraph(source, SourceSpan(0, source.length)))
        assertTrue(ContentPreservation.verify(source, emptyList(), ok).isLossless)

        val changed = listOf(StructureBlock.Paragraph("Pay 1200 EUR before 15/03/2025.", SourceSpan(0, source.length)))
        val report = ContentPreservation.verify(source, emptyList(), changed)
        assertFalse(report.isLossless)
        assertFalse(report.numbersPreserved)
        assertEquals(listOf("1,200"), report.missing)

        val added = listOf(StructureBlock.Paragraph("$source Thanks!", SourceSpan(0, source.length)))
        assertEquals(listOf("Thanks!"), ContentPreservation.verify(source, emptyList(), added).added)
    }

    @Test
    fun preservation_everySourceSentenceIsInTheResult() {
        val text = """
            ANNUAL REVIEW 2024

            Sales reached 1.2M USD. Costs fell by 5%.

            Highlights:
            - New office opened in Casablanca on 12/09/2024
            - 14 new clients

            | Region | Q1 | Q2 |
            |---|---|---|
            | North | 120 | 135 |
            | South | 98 | 101 |

            Prepared by the finance team.
        """.trimIndent()
        val r = analyze(text)
        assertTrue(r.preservation.isLossless)
        assertFalse(r.usedFallback)
        val output = ContentPreservation.blockTexts(r.blocks).joinToString(" ")
        listOf("Sales reached 1.2M USD.", "Costs fell by 5%.", "12/09/2024", "Prepared by the finance team.").forEach {
            assertTrue("missing: $it", output.contains(it))
        }
    }

    @Test
    fun originalPastedTextIsNotModified() {
        val pasted = "Title\r\n\r\n- a  item\r\n- b item\r\n"
        val copy = String(pasted.toCharArray())
        analyze(pasted)
        assertEquals(copy, pasted)
    }

    @Test
    fun emptyTextGivesNoBlocks() {
        val r = analyze("   \n\n  ")
        assertTrue(r.blocks.isEmpty())
        assertNull(r.title)
    }
}
