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

    @Test
    fun testDocxTemplateFidelityPreservesOriginalFile() {
        val docxEngine = DocxEngine()
        val templateFile = tempFolder.newFile("brand_template.docx")
        val outputFile = tempFolder.newFile("generated_contract.docx")

        // 1. Create a template document with specific sectPr and text
        val templateModel = WordDocumentModel(
            title = "Company Master Template",
            sections = listOf(
                WordSection(
                    heading = "Template Guidelines",
                    paragraphs = listOf("This is the untouched master template.")
                )
            )
        )
        val profile = TemplateProfile.defaultProfile().copy(
            primaryColorHex = "#2E7D32",
            headingFont = "Georgia",
            headerText = "ACME Master Template"
        )
        docxEngine.generateDocx(templateModel, templateFile, profile)
        val originalSize = templateFile.length()
        val originalLastModified = templateFile.lastModified()
        assertTrue(originalSize > 0)

        // 2. Generate a new document based on the template
        val newModel = WordDocumentModel(
            title = "Generated Business Proposal",
            sections = listOf(
                WordSection(
                    heading = "Executive Summary",
                    paragraphs = listOf("Proposal content injected using the master template styles.")
                )
            )
        )
        val result = docxEngine.generateDocx(
            model = newModel,
            outputFile = outputFile,
            template = profile,
            originalTemplateFile = templateFile
        )

        // 3. Verify original template is preserved completely
        assertTrue("Template file must continue to exist", templateFile.exists())
        assertEquals("Template file size must remain intact", originalSize, templateFile.length())

        // 4. Verify output file is a new distinct file with valid OOXML structure
        assertTrue("Target file must exist", result.exists())
        assertTrue("Target file must be non-empty", result.length() > 0)
        assertNotEquals(templateFile.absolutePath, result.absolutePath)

        val entries = mutableListOf<String>()
        ZipInputStream(FileInputStream(result)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries.add(entry.name)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        assertTrue(entries.contains("word/document.xml"))
        assertTrue(entries.contains("word/styles.xml"))
    }

    @Test
    fun testPptxTemplateFidelityPreservesOriginalFile() {
        val pptxEngine = PptxEngine()
        val templateFile = tempFolder.newFile("corporate_deck_theme.pptx")
        val outputFile = tempFolder.newFile("quarterly_results.pptx")

        val initialModel = PresentationModel(
            title = "Corporate Template Theme",
            subtitle = "Theme Master",
            slides = listOf(
                SlideModel(title = "Master Layout Slide", bulletPoints = listOf("Master format"))
            )
        )
        pptxEngine.generatePptx(initialModel, templateFile)
        val originalSize = templateFile.length()
        assertTrue(originalSize > 0)

        val newModel = PresentationModel(
            title = "Q4 Results Presentation",
            subtitle = "Financial Performance",
            slides = listOf(
                SlideModel(
                    title = "Revenue Growth",
                    bulletPoints = listOf("Revenue up 25% YoY", "New customer acquisition at ATH"),
                    calloutMetric = "+25%"
                )
            )
        )
        val result = pptxEngine.generatePptx(
            model = newModel,
            outputFile = outputFile,
            originalTemplateFile = templateFile
        )

        assertTrue(templateFile.exists())
        assertEquals(originalSize, templateFile.length())
        assertTrue(result.exists())
        assertTrue(result.length() > 0)
    }

    @Test
    fun testXlsxTemplateFidelityPreservesOriginalFile() {
        val xlsxEngine = XlsxEngine()
        val templateFile = tempFolder.newFile("corporate_ledger_theme.xlsx")
        val outputFile = tempFolder.newFile("monthly_expenses.xlsx")

        val initialModel = SpreadsheetModel(
            workbookTitle = "Template Ledger",
            sheets = listOf(
                SheetModel(
                    name = "Template Sheet",
                    headers = listOf("Item", "Category", "Cost"),
                    rows = listOf(listOf("Sample", "Office", 100))
                )
            )
        )
        xlsxEngine.generateXlsx(initialModel, templateFile)
        val originalSize = templateFile.length()
        assertTrue(originalSize > 0)

        val newModel = SpreadsheetModel(
            workbookTitle = "November Expenses",
            sheets = listOf(
                SheetModel(
                    name = "Expenses",
                    headers = listOf("Expense", "Vendor", "Amount"),
                    rows = listOf(
                        listOf("Cloud Hosting", "GCP", 2400),
                        listOf("SaaS License", "JetBrains", 499)
                    ),
                    summaryFormulaLabel = "Total",
                    summaryFormula = "SUM(C2:C3)"
                )
            )
        )
        val result = xlsxEngine.generateXlsx(
            model = newModel,
            outputFile = outputFile,
            originalTemplateFile = templateFile
        )

        assertTrue(templateFile.exists())
        assertEquals(originalSize, templateFile.length())
        assertTrue(result.exists())
        assertTrue(result.length() > 0)
    }

    @Test
    fun testScannedDocumentReconstruction_ArabicAndMultilingual() {
        val sampleOcrText = """
            اتفاقية تقديم خدمات تقنية
            
            مقدمة الاتفاقية:
            تم الاتفاق بين الطرفين على تنفيذ الخدمات التقنية وفق الشروط المنصوص عليها.
            
            • البند الأول: الالتزام بالجودة والمعايير المهنية
            • البند الثاني: حماية البيانات وسرية المعلومات
            • البند الثالث: الدعم الفني على مدار الساعة
            
            رقم البند | الوصف | التكلفة | الحالة
            1 | تطوير واجهة المستخدم | 4500 | مكتمل
            2 | دمج الذكاء الاصطناعي | 8000 | جاري التنفيذ
        """.trimIndent()

        val reconstructed = com.example.ocr.ScannedDocumentStructureReconstructor.reconstructPage(
            pageIndex = 0,
            imageUriString = "content://media/scanned_0.jpg",
            rawText = sampleOcrText
        )

        assertEquals("اتفاقية تقديم خدمات تقنية", reconstructed.recognizedTitle)
        assertTrue(reconstructed.isArabicDetected)
        assertTrue(reconstructed.paragraphs.isNotEmpty())
        assertEquals(3, reconstructed.bulletPoints.size)
        assertNotNull(reconstructed.table)
        assertEquals(4, reconstructed.table?.headers?.size)
        assertEquals(2, reconstructed.table?.rows?.size)
    }

    @Test
    fun testDocxGenerationFromScannedDocument_RtlAndMultiPage() {
        val engine = DocxEngine()
        val outputFile = tempFolder.newFile("scanned_output.docx")

        val page1 = com.example.ocr.ScannedDocumentStructureReconstructor.reconstructPage(
            pageIndex = 0,
            imageUriString = "content://media/scanned_0.jpg",
            rawText = "Document Title Page 1\n\nExecutive Overview\nThis is paragraph one on page 1.\n• Item Alpha\n• Item Beta"
        )

        val page2 = com.example.ocr.ScannedDocumentStructureReconstructor.reconstructPage(
            pageIndex = 1,
            imageUriString = "content://media/scanned_1.jpg",
            rawText = "الصفحة الثانية: التقرير المالي\n\nالبيان | القيمة | النسبة\nالإيرادات | 50000 | 100%\nالتكاليف | 30000 | 60%"
        )

        val wordModel = com.example.ocr.ScannedDocumentStructureReconstructor.buildWordDocumentModel(
            title = "Scanned Comprehensive Dossier",
            pages = listOf(page1, page2)
        )

        val resultFile = engine.generateDocx(wordModel, outputFile)
        assertTrue(resultFile.exists())
        assertTrue(resultFile.length() > 0)

        // Read XML inside docx
        var documentXmlContent = ""
        ZipInputStream(FileInputStream(resultFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    documentXmlContent = zip.bufferedReader().readText()
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        assertTrue(documentXmlContent.isNotBlank())
        // Verify page break was inserted between page 1 and page 2
        assertTrue("Page break must be present", documentXmlContent.contains("<w:br w:type=\"page\"/>"))
        // Verify Arabic RTL formatting was inserted for Arabic content
        assertTrue("Arabic RTL tag must be present", documentXmlContent.contains("<w:bidi/>") || documentXmlContent.contains("<w:bidiVisual/>"))
    }

    @Test
    fun testTemplateEntityDefaultFlagPerFileType() {
        val tmplWord1 = com.example.data.model.TemplateEntity(
            id = "t1",
            name = "Corporate Word",
            fileType = "DOCX",
            originalFilePath = "/tmp/t1.docx",
            profileJson = "{}",
            isDefaultWord = true
        )
        val tmplPpt1 = com.example.data.model.TemplateEntity(
            id = "t2",
            name = "Keynote Presentation",
            fileType = "PPTX",
            originalFilePath = "/tmp/t2.pptx",
            profileJson = "{}",
            isDefaultPpt = true
        )
        val tmplExcel1 = com.example.data.model.TemplateEntity(
            id = "t3",
            name = "Financial Model",
            fileType = "XLSX",
            originalFilePath = "/tmp/t3.xlsx",
            profileJson = "{}",
            isDefaultExcel = true
        )

        // Verify independent defaults across file types
        assertTrue(tmplWord1.isDefaultWord)
        assertFalse(tmplWord1.isDefaultPpt)
        assertFalse(tmplWord1.isDefaultExcel)

        assertTrue(tmplPpt1.isDefaultPpt)
        assertFalse(tmplPpt1.isDefaultWord)
        assertFalse(tmplPpt1.isDefaultExcel)

        assertTrue(tmplExcel1.isDefaultExcel)
        assertFalse(tmplExcel1.isDefaultWord)
        assertFalse(tmplExcel1.isDefaultPpt)

        // Verify toggling off (Remove as Default)
        val wordRemoved = tmplWord1.copy(isDefaultWord = false)
        assertFalse(wordRemoved.isDefaultWord)

        // Verify switching default Word template
        val tmplWord2 = com.example.data.model.TemplateEntity(
            id = "t4",
            name = "Modern Word",
            fileType = "DOCX",
            originalFilePath = "/tmp/t4.docx",
            profileJson = "{}",
            isDefaultWord = true
        )
        val oldWordCleared = tmplWord1.copy(isDefaultWord = false)
        assertFalse(oldWordCleared.isDefaultWord)
        assertTrue(tmplWord2.isDefaultWord)
        // Ensure PPTX and Excel defaults are preserved when Word default changes
        assertTrue(tmplPpt1.isDefaultPpt)
        assertTrue(tmplExcel1.isDefaultExcel)
    }

    @Test
    fun testTemplatePreviewEngineDocxRealisticParsing() = kotlinx.coroutines.runBlocking {
        val engine = DocxEngine()
        val docxFile = tempFolder.newFile("sample_preview_template.docx")

        val model = WordDocumentModel(
            title = "Preview Contract Header",
            subtitle = "Legal Operations Dept",
            sections = listOf(
                WordSection(
                    heading = "1. Deliverables",
                    paragraphs = listOf("The contractor shall deliver production software."),
                    bulletPoints = listOf("Deliverable A", "Deliverable B")
                )
            ),
            table = WordTable(
                headers = listOf("Milestone", "Due Date", "Status"),
                rows = listOf(listOf("M1", "2026-12-01", "Completed"))
            )
        )
        engine.generateDocx(model, docxFile)

        val profile = TemplateProfile.defaultProfile().copy(
            templateId = "tmpl-test-docx",
            name = "Test Preview Template",
            originalFileType = "DOCX",
            primaryColorHex = "#1D52D8"
        )

        val entity = com.example.data.model.TemplateEntity(
            id = profile.templateId,
            name = profile.name,
            fileType = "DOCX",
            originalFilePath = docxFile.absolutePath,
            profileJson = profile.toJson(),
            isDefaultWord = true
        )

        val previewEngine = com.example.template.preview.TemplatePreviewEngine()
        val previewResult = previewEngine.loadPreview(entity)

        assertTrue("Expected Success or Fallback, got: $previewResult", previewResult is com.example.template.preview.TemplatePreviewResult.Success)
        val data = (previewResult as com.example.template.preview.TemplatePreviewResult.Success).data
        assertTrue(data is com.example.template.preview.TemplatePreviewData.DocxPreview)
        val docxData = data as com.example.template.preview.TemplatePreviewData.DocxPreview

        assertEquals("Preview Contract Header", docxData.title)
        assertEquals("Legal Operations Dept", docxData.subtitle)
        assertTrue(docxData.sections.isNotEmpty())
        val deliverablesSection = docxData.sections.firstOrNull { it.heading == "1. Deliverables" }
        assertNotNull("Deliverables section should be present", deliverablesSection)
        assertEquals("1. Deliverables", deliverablesSection?.heading)
        assertNotNull(docxData.table)
        assertEquals(listOf("Milestone", "Due Date", "Status"), docxData.table?.headers)
    }

    @Test
    fun testTemplatePreviewEnginePptxAndXlsxParsing() = kotlinx.coroutines.runBlocking {
        val previewEngine = com.example.template.preview.TemplatePreviewEngine()

        // PPTX Test
        val pptxFile = tempFolder.newFile("sample_preview_deck.pptx")
        val pptModel = PresentationModel(
            title = "Quarterly Business Review",
            subtitle = "Strategic Performance",
            slides = listOf(
                SlideModel(
                    title = "Revenue Growth",
                    bulletPoints = listOf("+35% YoY expansion", "Market share gained"),
                    calloutMetric = "+35%"
                )
            )
        )
        PptxEngine().generatePptx(pptModel, pptxFile)

        val pptProfile = TemplateProfile.defaultProfile().copy(
            templateId = "tmpl-test-pptx",
            name = "QBR Deck",
            originalFileType = "PPTX",
            primaryColorHex = "#E05D26"
        )
        val pptEntity = com.example.data.model.TemplateEntity(
            id = pptProfile.templateId,
            name = pptProfile.name,
            fileType = "PPTX",
            originalFilePath = pptxFile.absolutePath,
            profileJson = pptProfile.toJson(),
            isDefaultPpt = true
        )

        val pptResult = previewEngine.loadPreview(pptEntity)
        assertTrue(pptResult is com.example.template.preview.TemplatePreviewResult.Success)
        val pptData = (pptResult as com.example.template.preview.TemplatePreviewResult.Success).data as com.example.template.preview.TemplatePreviewData.PptxPreview
        // The generated deck starts with its title slide, followed by the content slides.
        assertEquals(listOf("Quarterly Business Review", "Revenue Growth"), pptData.slides.map { it.title })

        // XLSX Test
        val xlsxFile = tempFolder.newFile("sample_preview_sheet.xlsx")
        val xlsxModel = SpreadsheetModel(
            workbookTitle = "Financial Budget",
            sheets = listOf(
                SheetModel(
                    name = "Summary",
                    headers = listOf("Q1", "Q2", "Q3", "Total"),
                    rows = listOf(listOf(100, 200, 300, 600))
                )
            )
        )
        XlsxEngine().generateXlsx(xlsxModel, xlsxFile)

        val xlsxProfile = TemplateProfile.defaultProfile().copy(
            templateId = "tmpl-test-xlsx",
            name = "Budget Sheet",
            originalFileType = "XLSX",
            primaryColorHex = "#107C41"
        )
        val xlsxEntity = com.example.data.model.TemplateEntity(
            id = xlsxProfile.templateId,
            name = xlsxProfile.name,
            fileType = "XLSX",
            originalFilePath = xlsxFile.absolutePath,
            profileJson = xlsxProfile.toJson(),
            isDefaultExcel = true
        )

        val xlsxResult = previewEngine.loadPreview(xlsxEntity)
        assertTrue(xlsxResult is com.example.template.preview.TemplatePreviewResult.Success)
        val xlsxData = (xlsxResult as com.example.template.preview.TemplatePreviewResult.Success).data as com.example.template.preview.TemplatePreviewData.XlsxPreview
        assertTrue(xlsxData.sheets.isNotEmpty())
        assertEquals("Summary", xlsxData.sheets.first().name)
        assertEquals(listOf("Q1", "Q2", "Q3", "Total"), xlsxData.sheets.first().headers)
    }

    @Test
    fun testTemplatePreviewFallbackWhenFileMissing() = kotlinx.coroutines.runBlocking {
        val previewEngine = com.example.template.preview.TemplatePreviewEngine()
        val profile = TemplateProfile.defaultProfile().copy(
            templateId = "tmpl-missing",
            name = "Missing File Template",
            originalFileType = "DOCX",
            headingFont = "Calibri"
        )
        val entity = com.example.data.model.TemplateEntity(
            id = profile.templateId,
            name = profile.name,
            fileType = "DOCX",
            originalFilePath = "/path/to/nonexistent/file.docx",
            profileJson = profile.toJson()
        )

        val result = previewEngine.loadPreview(entity)
        assertTrue(result is com.example.template.preview.TemplatePreviewResult.Fallback)
        val fallback = result as com.example.template.preview.TemplatePreviewResult.Fallback
        assertTrue(fallback.data is com.example.template.preview.TemplatePreviewData.DocxPreview)
        assertEquals("Missing File Template", (fallback.data as com.example.template.preview.TemplatePreviewData.DocxPreview).title)
    }

    @Test
    fun testTemplateDuplicationIntegrity() {
        val originalFile = tempFolder.newFile("original_template.docx")
        originalFile.writeText("Original OOXML bytes simulation")

        val originalProfile = TemplateProfile.defaultProfile().copy(
            templateId = "tmpl-orig-123",
            name = "Executive Report",
            originalFileType = "DOCX"
        )

        val originalEntity = com.example.data.model.TemplateEntity(
            id = originalProfile.templateId,
            name = originalProfile.name,
            fileType = "DOCX",
            originalFilePath = originalFile.absolutePath,
            profileJson = originalProfile.toJson(),
            isDefaultWord = true
        )

        // Duplicate logic simulation
        val dupId = "tmpl-dup-456"
        val dupName = "${originalEntity.name} (Copy)"
        val dupFile = tempFolder.newFile("template_${dupId}.docx")
        originalFile.inputStream().use { input ->
            dupFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val dupProfile = TemplateProfile.fromJson(originalEntity.profileJson).copy(
            templateId = dupId,
            name = dupName
        )

        val duplicatedEntity = com.example.data.model.TemplateEntity(
            id = dupId,
            name = dupName,
            fileType = originalEntity.fileType,
            originalFilePath = dupFile.absolutePath,
            fileSize = dupFile.length(),
            profileJson = dupProfile.toJson(),
            isDefaultWord = false, // Never default by default
            isDefaultPpt = false,
            isDefaultExcel = false,
            isDefaultPdf = false
        )

        // Verifications
        assertNotEquals(originalEntity.id, duplicatedEntity.id)
        assertNotEquals(originalEntity.originalFilePath, duplicatedEntity.originalFilePath)
        assertEquals("Executive Report (Copy)", duplicatedEntity.name)
        assertTrue(originalEntity.isDefaultWord)
        assertFalse(duplicatedEntity.isDefaultWord) // Duplicates must not be default
        assertEquals(originalFile.length(), dupFile.length())
    }
}


