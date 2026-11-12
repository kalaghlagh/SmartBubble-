package com.example.template.pdf

import com.example.data.model.TemplateEntity
import com.example.document.pdf.PdfProfileLayout
import com.example.offline.RuleBasedFormattingEngine
import com.example.offline.document.IncompatibleReason
import com.example.offline.document.OfflineDocumentGenerator
import com.example.offline.document.OfflineFormat
import com.example.offline.document.TemplateMatcher
import com.example.offline.document.TemplateNote
import com.example.offline.document.TemplateRepository
import com.example.offline.document.TemplateSource
import com.example.template.analysis.TemplateAnalysisEngine
import com.example.template.analysis.TemplateFileInspector
import com.example.template.domain.TemplateProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * PDF templates in template matching and document generation (pure JVM, real Word engine and
 * real PDF files built by [TestPdf]; the template repository is an in-memory fake).
 */
class PdfTemplateFormattingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Repo(val templates: List<TemplateEntity>) : TemplateRepository {
        override suspend fun findById(id: String) = templates.firstOrNull { it.id == id }
        override suspend fun defaultFor(templateType: String) = templates.firstOrNull {
            it.fileType == templateType && when (templateType) {
                "PPTX" -> it.isDefaultPpt
                "XLSX" -> it.isDefaultExcel
                "PDF" -> it.isDefaultPdf
                else -> it.isDefaultWord
            }
        }
        override suspend fun all() = templates
    }

    private fun pdfTemplate(id: String, bytes: ByteArray = TestPdf.corporateTemplate(), default: Boolean = false): Pair<TemplateEntity, File> {
        val file = tmp.newFile("$id.pdf").apply { writeBytes(bytes) }
        val profile = TemplateAnalysisEngine().analyzeFile(file, "PDF $id", "$id-original.pdf")
        return TemplateEntity(
            id = id, name = "PDF $id", fileType = "PDF", originalFilePath = file.path,
            profileJson = profile.toJson(), isDefaultPdf = default
        ) to file
    }

    private fun wordTemplate(id: String, default: Boolean) = TemplateEntity(
        id = id, name = "Word $id", fileType = "DOCX", originalFilePath = tmp.newFile("$id.docx").path,
        profileJson = TemplateProfile.defaultProfile().toJson(), isDefaultWord = default
    )

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun zip(file: File, entry: String) = ZipFile(file).use { z -> z.getEntry(entry)?.let { String(z.getInputStream(it).readBytes()) } }

    @Test
    fun inspectorAndAnalysisEngine_acceptPdfByContent() {
        val pdf = tmp.newFile("named-like-a.docx").apply { writeBytes(TestPdf.corporateTemplate()) }
        assertEquals(TemplateFileInspector.Result.Valid(TemplateFileInspector.Type.PDF), TemplateFileInspector.inspect(pdf))
        val renamed = File(pdf.parentFile, "t.pdf").also { pdf.renameTo(it) }
        val profile = TemplateAnalysisEngine().analyzeFile(renamed, "Corporate", "Corporate.pdf")
        assertEquals("PDF", profile.originalFileType)
        assertTrue(profile.isPdfLayout)
        assertEquals("Corporate.pdf", profile.sourceFileName)
        assertEquals(72, profile.marginLeftPt)
    }

    @Test
    fun matching_explicitPdfTemplate_perOutputFormat() = runBlocking {
        val (withTable, _) = pdfTemplate("t")
        val noTablePdf = TestPdf().apply { page("BT /F1 11 Tf 72 700 Td (Plain text template line) Tj ET\nBT /F1 11 Tf 72 680 Td (Second line of body) Tj ET") }.build()
        val (plain, _) = pdfTemplate("p", noTablePdf)
        val matcher = TemplateMatcher(Repo(listOf(withTable, plain)))

        val docx = matcher.match(OfflineFormat.DOCX, "t")
        assertEquals(TemplateSource.EXPLICIT, docx.source)
        assertNull(docx.file) // the PDF is a style reference, never a file template
        assertTrue(docx.profile.isPdfLayout)
        assertTrue(docx.notes.single() is TemplateNote.PdfProfileApplied)

        assertEquals(TemplateSource.EXPLICIT, matcher.match(OfflineFormat.PDF, "t").source)
        // Spreadsheet only when the PDF has a table.
        assertEquals(TemplateSource.EXPLICIT, matcher.match(OfflineFormat.XLSX, "t").source)
        val xlsx = matcher.match(OfflineFormat.XLSX, "p")
        assertEquals(TemplateSource.BUILT_IN, xlsx.source)
        assertEquals(IncompatibleReason.PDF_NO_TABLE_FOR_XLSX, (xlsx.notes.first() as TemplateNote.Incompatible).reason)
        // Never PowerPoint.
        val pptx = matcher.match(OfflineFormat.PPTX, "t")
        assertEquals(TemplateSource.BUILT_IN, pptx.source)
        assertEquals(IncompatibleReason.PDF_NOT_FOR_PPTX, (pptx.notes.first() as TemplateNote.Incompatible).reason)
        assertEquals(listOf("t", "p"), matcher.options(OfflineFormat.DOCX).map { it.id })
        assertEquals(listOf("t"), matcher.options(OfflineFormat.XLSX).map { it.id })
        assertTrue(matcher.options(OfflineFormat.PPTX).isEmpty())
    }

    @Test
    fun defaults_pdfDefaultTakesPartWithoutReplacingOtherDefaults() = runBlocking {
        val (pdfDefault, _) = pdfTemplate("pd", default = true)
        val wordDefault = wordTemplate("wd", default = true)

        val both = TemplateMatcher(Repo(listOf(pdfDefault, wordDefault)))
        assertEquals("wd", both.match(OfflineFormat.DOCX, null).entity!!.id) // Word default first for Word
        assertEquals("pd", both.match(OfflineFormat.PDF, null).entity!!.id) // PDF default first for PDF

        val onlyPdf = TemplateMatcher(Repo(listOf(pdfDefault)))
        assertEquals("pd", onlyPdf.match(OfflineFormat.DOCX, null).entity!!.id)
        assertEquals(TemplateSource.DEFAULT, onlyPdf.match(OfflineFormat.DOCX, null).source)

        // The default PDF template cannot format PowerPoint: reported, then the built-in profile.
        val pptx = onlyPdf.match(OfflineFormat.PPTX, null)
        assertEquals(TemplateSource.BUILT_IN, pptx.source)
        val note = pptx.notes.first() as TemplateNote.Incompatible
        assertTrue(note.isDefault)
        assertEquals("PDF pd", note.templateName)
        assertTrue(pptx.notes.last() is TemplateNote.NoTemplate)
    }

    @Test
    fun docxFromPdfProfile_reproducesLayoutAndKeepsTheOriginalPdf() = runBlocking {
        val (entity, file) = pdfTemplate("corp")
        val before = sha(file)
        val match = TemplateMatcher(Repo(listOf(entity))).match(OfflineFormat.DOCX, "corp")
        val text = "Monthly Report\n\nRevenue grew by 12% to 4.5 million in May 2025.\n\n- Point one\n- Point two\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        val result = RuleBasedFormattingEngine().analyze(text)
        val draft = OfflineDocumentGenerator(tmp.newFolder("d")).generate(result, OfflineFormat.DOCX, match)

        val doc = zip(draft.file, "word/document.xml")!!
        val styles = zip(draft.file, "word/styles.xml")!!
        assertTrue(doc.contains("""<w:pgSz w:w="11900" w:h="16840"/>"""))
        assertTrue(doc.contains("""w:left="1440""""))
        assertTrue(styles.contains("""w:ascii="Times""""))
        assertTrue(styles.contains("""w:ascii="Helvetica""""))
        assertTrue(styles.contains("""<w:sz w:val="22"/>""")) // 11 pt body
        assertTrue(styles.contains("""<w:color w:val="1F4E79"/>""")) // heading color
        assertTrue(styles.contains("""<w:jc w:val="both"/>""")) // justified body
        assertTrue(doc.contains("""w:fill="D9E2F3"""")) // table header fill
        assertEquals("ACME Consulting Group", Regex("<w:t[^>]*>([^<]*)</w:t>").find(zip(draft.file, "word/header1.xml")!!)!!.groupValues[1])
        assertTrue(zip(draft.file, "word/footer1.xml")!!.contains("PAGE"))
        assertTrue(doc.contains("Revenue grew by 12% to 4.5 million in May 2025."))
        assertFalse(doc.contains("Smart Bubble"))
        assertArrayEquals("original PDF changed", before, sha(file))
        assertTrue(draft.notes.any { it is TemplateNote.PdfProfileApplied })
    }

    @Test
    fun docxFromLandscapeAndArabicPdfProfiles() = runBlocking {
        val result = RuleBasedFormattingEngine().analyze("تقرير\n\nنص الفقرة الأولى من التقرير الشهري.")
        val (landscape, _) = pdfTemplate("l", TestPdf.corporateTemplate(landscape = true))
        val l = OfflineDocumentGenerator(tmp.newFolder("l")).generate(result, OfflineFormat.DOCX, TemplateMatcher(Repo(listOf(landscape))).match(OfflineFormat.DOCX, "l"))
        assertTrue(zip(l.file, "word/document.xml")!!.contains("""w:orient="landscape""""))

        val (arabic, _) = pdfTemplate("a", TestPdf.arabicTemplate())
        val a = OfflineDocumentGenerator(tmp.newFolder("a")).generate(result, OfflineFormat.DOCX, TemplateMatcher(Repo(listOf(arabic))).match(OfflineFormat.DOCX, "a"))
        val doc = zip(a.file, "word/document.xml")!!
        assertTrue(doc.contains("<w:bidi/></w:sectPr>"))
        assertTrue(doc.contains("نص الفقرة الأولى من التقرير الشهري."))
        assertTrue(zip(a.file, "word/styles.xml")!!.contains("Noto Naskh Arabic"))
    }

    @Test
    fun pdfOutputLayout_followsTheTemplateProfile() {
        val profile = PdfTemplateAnalyzer.analyze(tmp.newFile("x.pdf").apply { writeBytes(TestPdf.corporateTemplate()) }).toProfile("x", "X", "x.pdf")
        val layout = PdfProfileLayout.from(profile)
        assertEquals(595, layout.pageWidth)
        assertEquals(842, layout.pageHeight)
        assertEquals(72f, layout.marginLeft, 1f)
        assertEquals(11f, layout.bodySize)
        assertEquals(20f, layout.titleSize)
        assertEquals("ACME Consulting Group", layout.headerText)
        assertEquals("footer-center", layout.pageNumberPosition)
        assertEquals("justify", layout.bodyAlignment)
        assertEquals("serif", PdfProfileLayout.genericFamily(layout.bodyFont))
        assertEquals("sans-serif", PdfProfileLayout.genericFamily(layout.headingFont))
        assertTrue(layout.decorations.any { it.colorHex == "#1F4E79" && it.width > 590 })
        // Centered page number position.
        assertEquals((595 - 10f) / 2, PdfProfileLayout.xFor("center", 10f, layout), 0.01f)

        val scanned = PdfTemplateAnalyzer.analyze(tmp.newFile("s.pdf").apply { writeBytes(TestPdf.scannedPage()) }).toProfile("s", "S", "s.pdf")
        val s = PdfProfileLayout.from(scanned)
        assertEquals("", s.headerText) // nothing invented for a scan
        assertEquals("", s.pageNumberPosition)
    }

    @Test
    fun officeTemplates_areMatchedAsBefore() = runBlocking {
        val word = wordTemplate("w", default = true)
        val m = TemplateMatcher(Repo(listOf(word))).match(OfflineFormat.DOCX, null)
        assertEquals("w", m.entity!!.id)
        assertFalse(m.profile.isPdfLayout)
        assertTrue(m.notes.none { it is TemplateNote.PdfProfileApplied })
    }
}
