package com.example.agent

import com.example.agent.files.DocxCopyEditor
import com.example.agent.files.FileKind
import com.example.agent.files.FileSniffer
import com.example.agent.files.ImportStatus
import com.example.agent.files.LibraryImporter
import com.example.agent.files.OfficeBlock
import com.example.agent.files.OfficeReadException
import com.example.agent.files.OoxmlReader
import com.example.agent.files.ParagraphEdit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Office files as the Agent reads and edits them: real OOXML packages built in the test. */
class OfficeFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    companion object {
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

        fun zip(file: File, parts: Map<String, String>): File {
            ZipOutputStream(file.outputStream()).use { out ->
                parts.forEach { (name, body) ->
                    out.putNextEntry(ZipEntry(name)); out.write(body.toByteArray(Charsets.UTF_8)); out.closeEntry()
                }
            }
            return file
        }

        fun p(text: String, style: String? = null, bold: Boolean = false) =
            "<w:p>${style?.let { "<w:pPr><w:pStyle w:val=\"$it\"/></w:pPr>" } ?: ""}<w:r>${if (bold) "<w:rPr><w:b/></w:rPr>" else ""}<w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"

        fun docx(file: File, body: String, extra: Map<String, String> = emptyMap()) = zip(file, mapOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "word/styles.xml" to "<w:styles xmlns:w=\"$W\"><w:style w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/></w:style></w:styles>",
            "word/document.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"$W\"><w:body>$body</w:body></w:document>"
        ) + extra)

        val REPORT_BODY = p("Annual report", "Heading1") + p("Teh results were good.") +
            "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Region</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Sales</w:t></w:r></w:p></w:tc></w:tr>" +
            "<w:tr><w:tc><w:p><w:r><w:t>North</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>120</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
            "<w:p><w:r><w:t xml:space=\"preserve\">Mixed </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r></w:p>" +
            p("النتائج جيدة")
    }

    @Test
    fun word_headingsParagraphsAndTablesInReadingOrder() {
        val c = OoxmlReader.read(docx(tmp.newFile("r.docx"), REPORT_BODY, mapOf("word/media/image1.png" to "x")))
        assertEquals("DOCX", c.kind)
        assertEquals(OfficeBlock.Heading(1, "Annual report", 0), c.blocks[0])
        assertEquals("Teh results were good.", (c.blocks[1] as OfficeBlock.Paragraph).text)
        assertEquals(listOf(listOf("Region", "Sales"), listOf("North", "120")), (c.blocks[2] as OfficeBlock.Table).rows)
        assertEquals("Mixed bold", (c.blocks[3] as OfficeBlock.Paragraph).text)
        assertEquals("النتائج جيدة", (c.blocks[4] as OfficeBlock.Paragraph).text)
        // Images are reported, never silently dropped.
        assertTrue("images_not_read" in c.warnings)
        assertTrue(c.toText().contains("Region\tSales"))
    }

    @Test
    fun excel_sheetsCellsAndFormulas_storedValuesOnly_neverComputed() {
        val wb = zip(tmp.newFile("b.xlsx"), mapOf(
            "xl/workbook.xml" to "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Ventes\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Evil\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Target=\"../../../etc/passwd\"/></Relationships>",
            "xl/sharedStrings.xml" to "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><si><t>Region</t></si><si><t>Total</t></si></sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>North</t></is></c><c r=\"B2\"><v>120.5</v></c></row>" +
                "<row r=\"3\"><c r=\"B3\"><f>SUM(B2:B2)</f><v>120.5</v></c><c r=\"C3\"><f>B3*2</f></c></row>" +
                "</sheetData></worksheet>"
        ))
        val c = OoxmlReader.read(wb)
        // The sheet pointing outside the package is not read.
        assertEquals(listOf("Ventes"), c.blocks.map { (it as OfficeBlock.Sheet).name })
        val cells = (c.blocks[0] as OfficeBlock.Sheet).cells.associateBy { it.ref }
        assertEquals("Region", cells["A1"]!!.value)
        assertEquals("North", cells["A2"]!!.value)
        assertEquals("SUM(B2:B2)", cells["B3"]!!.formula)
        assertEquals("120.5", cells["B3"]!!.value)
        assertNull(cells["C3"]!!.value) // never evaluated by the app
        val text = c.toText()
        assertTrue(text, text.contains("C3 = B3*2 → (not calculated in the file)"))
        assertTrue(text.contains("North\t120.5"))
    }

    @Test
    fun powerPoint_slidesInPresentationOrder_titlesTextsTables() {
        val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
        fun slide(title: String, body: String) = "<p:sld xmlns:p=\"$P\" xmlns:a=\"$A\"><p:cSld><p:spTree>" +
            "<p:sp><p:nvSpPr><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>$title</a:t></a:r></a:p></p:txBody></p:sp>" +
            "<p:sp><p:nvSpPr><p:nvPr/></p:nvSpPr><p:txBody><a:p><a:r><a:t>$body</a:t></a:r></a:p></p:txBody></p:sp>" +
            "</p:spTree></p:cSld></p:sld>"
        val deck = zip(tmp.newFile("d.pptx"), mapOf(
            "ppt/presentation.xml" to "<p:presentation xmlns:p=\"$P\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/><p:sldId id=\"257\" r:id=\"rId1\"/></p:sldIdLst></p:presentation>",
            "ppt/_rels/presentation.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Target=\"slides/slide1.xml\"/><Relationship Id=\"rId2\" Target=\"slides/slide2.xml\"/></Relationships>",
            "ppt/slides/slide1.xml" to slide("Second", "b"),
            "ppt/slides/slide2.xml" to slide("First", "a")
        ))
        val slides = OoxmlReader.read(deck).blocks.map { it as OfficeBlock.Slide }
        assertEquals(listOf("First", "Second"), slides.map { it.title })
        assertEquals(listOf(listOf("a"), listOf("b")), slides.map { it.texts })
    }

    @Test
    fun hostileXmlAndPaths_areRefused() {
        val bad = docx(tmp.newFile("x.docx"), "")
        // DOCTYPE (entity expansion) is refused before parsing.
        val evil = zip(tmp.newFile("e.docx"), mapOf("word/document.xml" to "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x \"y\">]><w:document xmlns:w=\"$W\"><w:body/></w:document>"))
        assertTrue(runCatching { OoxmlReader.read(evil) }.exceptionOrNull() is OfficeReadException)
        assertEquals(0, OoxmlReader.read(bad).blocks.size)
        assertNull(OoxmlReader.resolve("xl", "../../etc/passwd"))
        assertEquals("xl/worksheets/s.xml", OoxmlReader.resolve("xl", "worksheets/s.xml"))
        assertEquals("xl/media/x.png", OoxmlReader.resolve("xl/worksheets", "../media/x.png"))
        assertFalse(DocxCopyEditor.safeEntryName("../evil.xml"))
        assertFalse(DocxCopyEditor.safeEntryName("/abs.xml"))
        assertFalse(DocxCopyEditor.safeEntryName("a\\b.xml"))
        assertTrue(DocxCopyEditor.safeEntryName("word/document.xml"))
    }

    @Test
    fun editedCopy_changesOnlyTheChosenParagraphs_originalByteForByteUnchanged() {
        val source = docx(tmp.newFile("report.docx"), REPORT_BODY, mapOf("word/media/logo.png" to "PNGDATA"))
        val before = source.readBytes()
        val copy = File(tmp.root, "copy.docx")
        val result = DocxCopyEditor.writeCopy(source, copy, listOf(ParagraphEdit(1, "The results were good."), ParagraphEdit(6, "Mixed plain"), ParagraphEdit(99, "nowhere")))
        assertArrayEquals(before, source.readBytes())
        assertEquals(2, result.changed)
        assertEquals(listOf(99), result.skipped)
        assertEquals(listOf(6), result.mixedFormatting)
        val c = OoxmlReader.read(copy)
        assertEquals(listOf("Annual report", "The results were good.", "Mixed plain", "النتائج جيدة"), c.paragraphs.map { it.second }.filter { it.isNotBlank() && it != "Region" && it != "Sales" && it != "North" && it != "120" })
        // Heading style and other parts are kept.
        assertEquals(OfficeBlock.Heading(1, "Annual report", 0), c.blocks[0])
        ZipFile(copy).use { z -> assertEquals("PNGDATA", z.getInputStream(z.getEntry("word/media/logo.png")).readBytes().toString(Charsets.UTF_8)) }
        assertFalse(File(tmp.root, "copy.docx.part").exists())
    }

    @Test
    fun editedCopy_refusesTraversalEntries_andLeavesNoPartialFile() {
        val source = docx(tmp.newFile("t.docx"), p("x"), mapOf("../outside.txt" to "boom"))
        val copy = File(tmp.root, "t-copy.docx")
        assertTrue(runCatching { DocxCopyEditor.writeCopy(source, copy, listOf(ParagraphEdit(0, "y"))) }.isFailure)
        assertFalse(copy.exists())
        assertFalse(File(tmp.root, "t-copy.docx.part").exists())
        assertFalse(File(tmp.root.parentFile, "outside.txt").exists())
        assertTrue(runCatching { DocxCopyEditor.writeCopy(source, source, emptyList()) }.isFailure) // never onto the original
    }

    @Test
    fun sniffer_decidesFromBytes_notNames() {
        assertEquals(FileSniffer.Result.Supported(FileKind.DOCX), FileSniffer.sniff(docx(tmp.newFile("looks.pdf"), p("x"))))
        val png = tmp.newFile("a.txt").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) }
        assertEquals(FileSniffer.Result.Supported(FileKind.PNG), FileSniffer.sniff(png))
        val jpeg = tmp.newFile("b.bin").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())) }
        assertEquals(FileSniffer.Result.Supported(FileKind.JPEG), FileSniffer.sniff(jpeg))
        val ole = tmp.newFile("old.docx").apply { writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte())) }
        assertEquals(FileSniffer.Result.Refused(ImportStatus.LEGACY_OR_ENCRYPTED_OFFICE), FileSniffer.sniff(ole))
        val text = tmp.newFile("notes.docx").apply { writeText("plain text pretending to be Word") }
        assertEquals(FileSniffer.Result.Refused(ImportStatus.UNSUPPORTED), FileSniffer.sniff(text))
        assertEquals(FileSniffer.Result.Refused(ImportStatus.UNREADABLE), FileSniffer.sniff(tmp.newFile("empty.pdf")))
        assertEquals(64, LibraryImporter.sha256(text).length)
    }
}
