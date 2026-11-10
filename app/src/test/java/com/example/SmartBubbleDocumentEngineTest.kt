package com.example

import com.example.document.docx.DocxEngine
import com.example.document.model.*
import com.example.document.pptx.PptxEngine
import com.example.document.xlsx.XlsxEngine
import com.example.template.analysis.TemplateAnalysisEngine
import com.example.template.domain.TemplateProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

class SmartBubbleDocumentEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDocxGenerationAndOoxmlPackageStructure() {
        val engine = DocxEngine()
        val outputFile = tempFolder.newFile("test_contract.docx")

        val model = WordDocumentModel(
            title = "Test Partnership Agreement",
            subtitle = "Strategic Operations",
            sections = listOf(
                WordSection(
                    heading = "Section 1. Terms",
                    paragraphs = listOf("This is a verified test paragraph."),
                    bulletPoints = listOf("Bullet item A", "Bullet item B")
                )
            ),
            table = WordTable(
                headers = listOf("Col A", "Col B"),
                rows = listOf(listOf("Val 1", "Val 2"))
            )
        )

        val result = engine.generateDocx(model, outputFile)
        assertTrue(result.exists())
        assertTrue(result.length() > 0)

        // Verify OOXML zip entries
        val entries = mutableListOf<String>()
        ZipInputStream(FileInputStream(result)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        assertTrue(entries.contains("[Content_Types].xml"))
        assertTrue(entries.contains("_rels/.rels"))
        assertTrue(entries.contains("word/document.xml"))
        assertTrue(entries.contains("word/styles.xml"))
    }

    @Test
    fun testPptxGenerationAndSlideStructure() {
        val engine = PptxEngine()
        val outputFile = tempFolder.newFile("test_deck.pptx")

        val model = PresentationModel(
            title = "Executive Strategy",
            subtitle = "AI Office Suite",
            slides = listOf(
                SlideModel(
                    title = "Title Slide",
                    subtitle = "Opening Deck",
                    bulletPoints = emptyList()
                ),
                SlideModel(
                    title = "Architecture Overview",
                    bulletPoints = listOf("Central Orchestrator", "Document Engine"),
                    calloutMetric = "99.9%"
                )
            )
        )

        val result = engine.generatePptx(model, outputFile)
        assertTrue(result.exists())
        assertTrue(result.length() > 0)

        val entries = mutableListOf<String>()
        ZipInputStream(FileInputStream(result)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        assertTrue(entries.contains("[Content_Types].xml"))
        assertTrue(entries.contains("ppt/presentation.xml"))
        assertTrue(entries.contains("ppt/slides/slide1.xml"))
        assertTrue(entries.contains("ppt/slides/slide2.xml"))
    }

    @Test
    fun testXlsxGenerationWithFormulasAndSheets() {
        val engine = XlsxEngine()
        val outputFile = tempFolder.newFile("test_budget.xlsx")

        val model = SpreadsheetModel(
            workbookTitle = "FY2026 Financials",
            sheets = listOf(
                SheetModel(
                    name = "Operating Budget",
                    headers = listOf("Dept", "Q1", "Q2", "Q3"),
                    rows = listOf(
                        listOf("Engineering", 10000, 12000, 15000),
                        listOf("Product", 8000, 8500, 9000)
                    ),
                    summaryFormulaLabel = "Total Q3",
                    summaryFormula = "SUM(D2:D3)"
                )
            )
        )

        val result = engine.generateXlsx(model, outputFile)
        assertTrue(result.exists())
        assertTrue(result.length() > 0)

        val entries = mutableListOf<String>()
        ZipInputStream(FileInputStream(result)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        assertTrue(entries.contains("[Content_Types].xml"))
        assertTrue(entries.contains("xl/workbook.xml"))
        assertTrue(entries.contains("xl/worksheets/sheet1.xml"))
        assertTrue(entries.contains("xl/styles.xml"))
    }

    @Test
    fun testTemplateProfileSerializationRoundTrip() {
        val original = TemplateProfile(
            templateId = "test-tpl-001",
            name = "Apex Custom Brand",
            originalFileType = "DOCX",
            primaryColorHex = "#123456",
            secondaryColorHex = "#654321",
            headingFont = "Georgia",
            bodyFont = "Arial",
            marginTopPt = 72,
            headerText = "Corporate Confidential"
        )

        val jsonString = original.toJson()
        val parsed = TemplateProfile.fromJson(jsonString)

        assertEquals(original.templateId, parsed.templateId)
        assertEquals(original.name, parsed.name)
        assertEquals(original.primaryColorHex, parsed.primaryColorHex)
        assertEquals(original.secondaryColorHex, parsed.secondaryColorHex)
        assertEquals(original.headingFont, parsed.headingFont)
        assertEquals(original.marginTopPt, parsed.marginTopPt)
        assertEquals(original.headerText, parsed.headerText)
    }

    @Test
    fun testTemplateAnalysisEngineWithGeneratedDocx() {
        val docxEngine = DocxEngine()
        val docxFile = tempFolder.newFile("sample_source.docx")
        docxEngine.generateDocx(
            WordDocumentModel(title = "Template Reference"),
            docxFile,
            TemplateProfile.defaultProfile().copy(primaryColorHex = "#0055AA", headingFont = "Calibri")
        )

        val analysisEngine = TemplateAnalysisEngine()
        val extractedProfile = analysisEngine.analyzeFile(docxFile, "Extracted Profile")

        assertNotNull(extractedProfile)
        assertEquals("Extracted Profile", extractedProfile.name)
        assertEquals("DOCX", extractedProfile.originalFileType)
        assertTrue(extractedProfile.isAnalysisComplete)
    }
}
