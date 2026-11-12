package com.example

import com.example.document.docx.DocxEngine
import com.example.document.model.PresentationModel
import com.example.document.model.SheetModel
import com.example.document.model.SlideModel
import com.example.document.model.SpreadsheetModel
import com.example.document.model.WordDocumentModel
import com.example.document.model.WordSection
import com.example.document.pptx.PptxEngine
import com.example.document.xlsx.XlsxEngine
import com.example.ocr.OcrLanguage
import com.example.ocr.OcrLineInfo
import com.example.ocr.OcrTextAssembler
import com.example.ocr.ScannedDocumentStructureReconstructor
import com.example.template.analysis.TemplateFileInspector
import com.example.template.domain.TemplateProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Pure JVM tests for OCR text structuring, OCR languages and template generation options. */
class ScanAndTemplateOptionsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun line(text: String, startsParagraph: Boolean = false) =
        OcrLineInfo(text = text, startsParagraph = startsParagraph)

    private fun zipText(file: File, entry: String): String =
        ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry(entry)).readBytes().toString(Charsets.UTF_8) }

    private fun zipNames(file: File): List<String> = ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }

    // ---------------------------------------------------------------- OCR text

    @Test
    fun wrappedLinesBecomeOneParagraphAndParagraphsStaySeparate() {
        val text = OcrTextAssembler.assemble(
            listOf(
                line("هذه فقرة عربية طويلة تمتد", startsParagraph = true),
                line("على سطرين متتاليين."),
                line("Second paragraph with a hyphen-", startsParagraph = true),
                line("ated word and more text.")
            )
        )
        assertEquals(
            "هذه فقرة عربية طويلة تمتد على سطرين متتاليين.\n\nSecond paragraph with a hyphenated word and more text.",
            text
        )
    }

    @Test
    fun columnsBecomeTableRowsOnlyWhenRepeated() {
        val text = OcrTextAssembler.assemble(
            listOf(
                line("Item      Qty      Price", startsParagraph = true),
                line("Pens      3        4.50"),
                line("A single line   with a gap", startsParagraph = true),
                line("followed by normal text.")
            )
        )
        assertTrue(text.contains("Item | Qty | Price\nPens | 3 | 4.50"))
        // One line with a wide gap is not a table.
        assertTrue(text.contains("A single line with a gap followed by normal text."))
    }

    @Test
    fun listItemsStayOnTheirOwnLines() {
        val text = OcrTextAssembler.assemble(
            listOf(line("Intro text", true), line("• First"), line("• Second"), line("1. Numbered"))
        )
        assertEquals("Intro text\n\n• First\n• Second\n1. Numbered", text)
    }

    @Test
    fun pageStructureKeepsReadingOrderHeadingsListsAndTables() {
        val page = ScannedDocumentStructureReconstructor.reconstructPage(
            pageIndex = 0,
            imageUriString = "file:///scan.jpg",
            rawText = """
                Quarterly Report
                Introduction:
                First paragraph, with commas, that is not a table.
                • Point A
                • Point B
                Closing paragraph after the list.
                Item | Qty
                Pens | 3
                RESULTS
                Final paragraph.
            """.trimIndent(),
            confidence = 0.82f
        )
        assertEquals("Quarterly Report", page.recognizedTitle)
        assertEquals(0.82f, page.confidenceScore, 0.0001f)
        assertEquals(listOf("Introduction:", "", "RESULTS"), page.sections.map { it.heading })
        assertEquals(listOf("First paragraph, with commas, that is not a table."), page.sections[0].paragraphs)
        assertEquals(listOf("Point A", "Point B"), page.sections[0].bulletPoints)
        assertEquals(listOf("Closing paragraph after the list."), page.sections[1].paragraphs)
        assertEquals(listOf("Item", "Qty"), page.sections[1].table?.headers)
        assertEquals(listOf("Final paragraph."), page.sections[2].paragraphs)
    }

    @Test
    fun laterPagesKeepTheirFirstLineAndEmptyPagesAreEmpty() {
        val page2 = ScannedDocumentStructureReconstructor.reconstructPage(1, "file:///p2.jpg", "First line of page two\nMore text")
        assertEquals(listOf("First line of page two", "More text"), page2.paragraphs)
        val empty = ScannedDocumentStructureReconstructor.reconstructPage(2, "file:///p3.jpg", "   ")
        assertEquals(0f, empty.confidenceScore, 0f)
        assertTrue(empty.sections.isEmpty())

        val model = ScannedDocumentStructureReconstructor.buildWordDocumentModel("T", listOf(page2, empty))
        assertEquals(1, model.sections.size) // the empty page adds nothing
    }

    @Test
    fun ocrLanguagesMapToBundledModels() {
        assertEquals("ara+eng", OcrLanguage.ARABIC.tesseractLanguages("en"))
        assertEquals("fra", OcrLanguage.FRENCH.tesseractLanguages("ar"))
        assertEquals("ara+eng+fra", OcrLanguage.AUTO.tesseractLanguages("ar"))
        assertEquals("fra+ara+eng", OcrLanguage.AUTO.tesseractLanguages("fr"))
        assertEquals(OcrLanguage.AUTO, OcrLanguage.fromCode("unknown"))
        OcrLanguage.values().forEach { lang ->
            assertTrue(lang.models.all { it in com.example.ocr.TessDataInstaller.MODELS })
        }
    }

    // ---------------------------------------------------------------- template options

    @Test
    fun templateOptionsRoundTripAndDefaultForOlderProfiles() {
        val saved = TemplateProfile.defaultProfile().copy(useTemplateLayout = false, keepTemplateContent = true)
        val loaded = TemplateProfile.fromJson(saved.toJson())
        assertFalse(loaded.useTemplateLayout)
        assertTrue(loaded.keepTemplateContent)
        // Profiles stored before these options existed keep the previous behaviour.
        val legacy = TemplateProfile.fromJson("""{"templateId":"x","name":"Old"}""")
        assertTrue(legacy.useTemplateLayout)
        assertFalse(legacy.keepTemplateContent)
    }

    private val word = WordDocumentModel(title = "New Title", sections = listOf(WordSection("H", listOf("New paragraph"))))

    private fun docxTemplate(): File {
        val file = tmp.newFile("tpl.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
            put("word/document.xml", """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>EXISTING LETTERHEAD TEXT</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="11906" w:h="16838"/></w:sectPr></w:body></w:document>""")
            put("word/header1.xml", "<w:hdr/>")
        }
        return file
    }

    @Test
    fun docxKeepContentAppendsAndReplaceModeReplaces() {
        val template = docxTemplate()
        val before = template.readBytes()

        val keep = DocxEngine().generateDocxDetailed(
            word, tmp.newFile("keep.docx"), TemplateProfile.defaultProfile().copy(keepTemplateContent = true), template
        )
        assertTrue(keep.templateApplied)
        val keepXml = zipText(keep.file, "word/document.xml")
        assertTrue(keepXml.indexOf("EXISTING LETTERHEAD TEXT") in 0 until keepXml.indexOf("New paragraph"))
        assertTrue(keepXml.indexOf("New paragraph") < keepXml.indexOf("<w:sectPr"))

        val replace = DocxEngine().generateDocxDetailed(word, tmp.newFile("replace.docx"), TemplateProfile.defaultProfile(), template)
        val replaceXml = zipText(replace.file, "word/document.xml")
        assertFalse(replaceXml.contains("EXISTING LETTERHEAD TEXT"))
        assertTrue(replaceXml.contains("New paragraph"))
        assertTrue(zipNames(replace.file).contains("word/header1.xml"))

        val styleOnly = DocxEngine().generateDocxDetailed(
            word, tmp.newFile("style.docx"), TemplateProfile.defaultProfile().copy(useTemplateLayout = false), template
        )
        assertFalse(styleOnly.templateApplied)
        assertFalse(zipNames(styleOnly.file).contains("word/header1.xml"))

        assertTrue("the original template is never modified", before.contentEquals(template.readBytes()))
    }

    @Test
    fun pptxKeepContentAddsSlidesAfterTheTemplateSlides() {
        val deck = PresentationModel("Deck", "Sub", slides = listOf(SlideModel("One", bulletPoints = listOf("a"))))
        val template = tmp.newFile("tpl.pptx")
        PptxEngine().generatePptx(deck, template) // title slide + 1 slide

        val added = PresentationModel("More", "", slides = listOf(SlideModel("Extra")))
        val keep = PptxEngine().generatePptxDetailed(
            added, tmp.newFile("keep.pptx"), TemplateProfile.defaultProfile().copy(keepTemplateContent = true), template
        )
        assertTrue(keep.templateApplied)
        val slides = zipNames(keep.file).filter { Regex("""ppt/slides/slide\d+\.xml""").matches(it) }
        assertEquals(4, slides.size) // 2 template slides + title + 1 new
        val presentation = zipText(keep.file, "ppt/presentation.xml")
        val ids = Regex("""<p:sldId\b[^>]*\bid="(\d+)"""").findAll(presentation).map { it.groupValues[1] }.toList()
        assertEquals(4, ids.size)
        assertEquals(ids.size, ids.toSet().size)

        val replace = PptxEngine().generatePptxDetailed(added, tmp.newFile("replace.pptx"), TemplateProfile.defaultProfile(), template)
        assertEquals(2, zipNames(replace.file).count { Regex("""ppt/slides/slide\d+\.xml""").matches(it) })
    }

    @Test
    fun xlsxKeepContentAddsSheetsWithUniqueNames() {
        val book = SpreadsheetModel("WB", listOf(SheetModel("Data", listOf("A"), listOf(listOf(1)))))
        val template = tmp.newFile("tpl.xlsx")
        XlsxEngine().generateXlsx(book, template)

        val keep = XlsxEngine().generateXlsxDetailed(
            book, tmp.newFile("keep.xlsx"), TemplateProfile.defaultProfile().copy(keepTemplateContent = true), template
        )
        assertTrue(keep.templateApplied)
        val workbook = zipText(keep.file, "xl/workbook.xml")
        val names = Regex("""<sheet\b[^>]*\bname="([^"]*)"""").findAll(workbook).map { it.groupValues[1] }.toList()
        assertEquals(listOf("Data", "Data (2)"), names)
        assertEquals(2, zipNames(keep.file).count { Regex("""xl/worksheets/sheet\d+\.xml""").matches(it) })
        assertNotNull(Regex("""sheetId="2"""").find(workbook))
    }

    // ---------------------------------------------------------------- import validation

    @Test
    fun importAcceptsOfficeFilesByContentAndRejectsEverythingElse() {
        assertEquals(TemplateFileInspector.Result.Valid(TemplateFileInspector.Type.DOCX), TemplateFileInspector.inspect(docxTemplate()))

        val notOffice = tmp.newFile("notes.docx").apply { writeText("plain text pretending to be a document") }
        assertEquals(TemplateFileInspector.Result.NotOfficeDocument, TemplateFileInspector.inspect(notOffice))

        val otherZip = tmp.newFile("archive.docx")
        ZipOutputStream(otherZip.outputStream()).use { it.putNextEntry(ZipEntry("readme.txt")); it.write(1); it.closeEntry() }
        assertEquals(TemplateFileInspector.Result.NotOfficeDocument, TemplateFileInspector.inspect(otherZip))

        // A small archive that unpacks to more than the limit is rejected (zip bomb).
        val bomb = tmp.newFile("bomb.xlsx")
        ZipOutputStream(bomb.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            val block = ByteArray(1024 * 1024)
            repeat((TemplateFileInspector.MAX_UNPACKED_BYTES / block.size).toInt() + 2) { zip.write(block) }
            zip.closeEntry()
        }
        assertTrue(bomb.length() < TemplateFileInspector.MAX_FILE_BYTES)
        assertEquals(TemplateFileInspector.Result.TooLarge, TemplateFileInspector.inspect(bomb))
    }
}
