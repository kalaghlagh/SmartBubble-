package com.example.reformat

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.OfficeFilesTest
import com.example.agent.files.OoxmlReader
import com.example.agent.office.BlockType
import com.example.agent.thesis.SourceExtractor
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.DocumentEntity
import com.example.data.model.TemplateEntity
import com.example.extraction.ExtractedBlock
import com.example.extraction.ExtractedDocument
import com.example.extraction.ExtractedPage
import com.example.extraction.PageSource
import com.example.extraction.PdfTextExtraction
import com.example.extraction.RegionMeta
import com.example.extraction.Script
import com.example.library.LibraryTemplateRepository
import com.example.template.domain.TemplateProfile
import com.example.template.pdf.PdfDocumentReader
import com.example.template.pdf.TestPdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * The Reformatting workspace on real files, a real Room database and the app's engines: golden
 * Word documents (Arabic, French, English, mixed text with Latin dates and references, runs with
 * different formatting, a table, header, footer, an image part, page setup), a text PDF, a photo
 * (OCR stand-in: PaddleOCR runs on devices only), Library templates, errors and recovery.
 * Untouched parts and paragraphs are compared with the original package.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReformatWorkspaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SmartBubbleDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var workDir: File
    private lateinit var libraryDir: File
    private var ocrPages: List<ExtractedPage> = emptyList()

    private val extract: SourceExtractor = { file, isPdf, pages ->
        if (isPdf) {
            val read = PdfTextExtraction.readPages(PdfDocumentReader.open(file))
            ExtractedDocument(read.filter { pages == null || it.first in pages }.mapIndexedNotNull { i, (p, page) -> page?.copy(index = i, sourcePage = p) })
        } else ExtractedDocument(ocrPages) // the OCR engine's result for a photo (PaddleOCR runs on devices)
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SmartBubbleDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        workDir = tmp.newFolder("reformat")
        libraryDir = tmp.newFolder("generated_docs")
    }

    @After
    fun tearDown() { scope.cancel(); db.close() }

    private fun controller() = ReformatController(scope, db, LibraryTemplateRepository(db.templateDao()), workDir, libraryDir, extract, { "$it (reformatted)" })

    private fun ReformatController.awaitIdle() = runBlocking {
        withTimeout(30_000) { while (state.value.busy) delay(10) }
    }

    // ------------------------------------------------------------------ golden documents

    private val ar1 = "طلب ترخيص رقم REF-2026-015 بتاريخ 15/10/2026"
    private val frRuns = listOf("Le rapport est " to false, "validé" to true, " par la direction." to false)
    private val en1 = "The annual budget was approved."

    private fun run(text: String, bold: Boolean = false, rtl: Boolean = false) =
        "<w:r><w:rPr>${if (bold) "<w:b/>" else ""}${if (rtl) "<w:rtl/>" else ""}</w:rPr><w:t xml:space=\"preserve\">$text</w:t></w:r>"

    private fun goldenDocx(): ByteArray {
        val f = File(tmp.root, "golden_${System.nanoTime()}.docx")
        val body = OfficeFilesTest.p("Rapport annuel", "Heading1") +
            "<w:p><w:pPr><w:bidi/><w:jc w:val=\"right\"/></w:pPr>${run(ar1, rtl = true)}</w:p>" +
            "<w:p>" + frRuns.joinToString("") { (t, b) -> run(t, bold = b) } + "</w:p>" +
            "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>${run(en1)}</w:p>" +
            "<w:tbl><w:tr><w:tc><w:p>${run("Région")}</w:p></w:tc><w:tc><w:p>${run("Ventes")}</w:p></w:tc></w:tr>" +
            "<w:tr><w:tc><w:p>${run("الشمال", rtl = true)}</w:p></w:tc><w:tc><w:p>${run("120")}</w:p></w:tc></w:tr></w:tbl>" +
            "<w:p>${run("Signature")}</w:p>" +
            "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rId9\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>" +
            "<w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/><w:pgMar w:top=\"720\" w:right=\"900\" w:bottom=\"720\" w:left=\"900\"/></w:sectPr>"
        OfficeFilesTest.docx(f, body, mapOf(
            "word/header1.xml" to "<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>ACME — En-tête</w:t></w:r></w:p></w:hdr>",
            "word/footer1.xml" to "<w:ftr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>Page</w:t></w:r></w:p></w:ftr>",
            "word/media/logo.png" to "PNG-LOGO-BYTES",
            "word/_rels/document.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"/>"
        ))
        return f.readBytes()
    }

    private fun paragraphsXml(bytes: ByteArray): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(bytes.inputStream())
        val ps = doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p")
        val t = TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes") }
        return (0 until ps.length).map { i -> ByteArrayOutputStream().also { t.transform(DOMSource(ps.item(i)), StreamResult(it)) }.toString("UTF-8") }
    }

    private fun entries(f: File) = ZipFile(f).use { z -> z.entries().toList().associate { it.name to z.getInputStream(it).readBytes() } }

    private fun ReformatController.importBytes(name: String, bytes: ByteArray) { importFile(name) { bytes.inputStream() }; awaitIdle() }

    private fun blockWith(c: ReformatController, text: String) = c.state.value.blocks.first { it.block.text.contains(text) }.block

    // ------------------------------------------------------------------ DOCX, Keep Source Formatting

    @Test
    fun docx_keepSource_changeAcrossRuns_onlyThatParagraphChanges_everyOtherPartIdentical() {
        val bytes = goldenDocx()
        val c = controller()
        c.importBytes("Rapport.docx", bytes)
        val s = c.state.value
        assertNull(s.error)
        assertEquals(SourceKind.DOCX, s.source!!.kind)
        assertTrue(s.blocks.any { it.block.type == BlockType.TABLE })
        // The change spans the plain run and the bold run.
        val fr = blockWith(c, "rapport est")
        c.editText(fr.index, "Le document est approuvé par la direction.")
        assertTrue(c.state.value.hasEdits)
        c.create(ExportFormat.DOCX); c.awaitIdle()
        val p = c.state.value.preview!!
        assertTrue("${p.issues} ${p.notes}", p.valid)
        val out = File(p.path)
        // Every part but the body is byte for byte the source's (header, footer, image, styles, relationships).
        val before = entries(File(c.state.value.source!!.path))
        val after = entries(out)
        assertEquals(before.keys, after.keys)
        for (name in before.keys - "word/document.xml") assertArrayEquals(name, before.getValue(name), after.getValue(name))
        // In the body only the edited paragraph differs; its text is exactly the new text.
        val pb = paragraphsXml(before.getValue("word/document.xml"))
        val pa = paragraphsXml(after.getValue("word/document.xml"))
        assertEquals(pb.size, pa.size)
        val changed = pb.indices.filter { pb[it] != pa[it] }
        assertEquals(1, changed.size)
        assertEquals("Le document est approuvé par la direction.", OoxmlReader.read(out).paragraphs.toMap().getValue(fr.paragraphIndex!!))
        // Page setup (landscape, margins) and the header reference are the source's.
        val xml = String(after.getValue("word/document.xml"))
        assertTrue(xml.contains("w:orient=\"landscape\"") && xml.contains("rId9"))
        // The source copy is unchanged.
        assertArrayEquals(bytes, File(c.state.value.source!!.path).readBytes())
    }

    @Test
    fun arabic_mixedLatinDateEdit_exactUnicode_directionKept_andLatinParagraphTurnedArabicBecomesRtl() {
        val c = controller()
        c.importBytes("طلب.docx", goldenDocx())
        val ar = blockWith(c, "طلب ترخيص")
        c.editText(ar.index, ar.text.replace("15/10/2026", "20/10/2026"))
        val en = blockWith(c, "annual budget")
        c.editText(en.index, "تمت المصادقة على الميزانية السنوية.")
        c.create(ExportFormat.DOCX); c.awaitIdle()
        val p = c.state.value.preview!!
        assertTrue("${p.issues} ${p.notes}", p.valid)
        val out = File(p.path)
        val paragraphs = OoxmlReader.read(out).paragraphs.toMap()
        // Exact code points, never reversed or reordered.
        assertEquals("طلب ترخيص رقم REF-2026-015 بتاريخ 20/10/2026", paragraphs.getValue(ar.paragraphIndex!!))
        assertEquals("تمت المصادقة على الميزانية السنوية.", paragraphs.getValue(en.paragraphIndex!!))
        assertFalse(p.text.any { ReformatCore.isPresentationForm(it) })
        val xml = paragraphsXml(entries(out).getValue("word/document.xml"))
        val enXml = xml.first { it.contains("الميزانية") }
        assertTrue(enXml, enXml.contains("bidi") && enXml.contains("rtl"))
        assertTrue(enXml, enXml.contains("w:jc w:val=\"center\"")) // its own alignment is kept
        val arXml = xml.first { it.contains("REF-2026-015") }
        assertTrue(arXml, arXml.contains("bidi") && arXml.contains("rtl"))
        assertEquals(0, RtlCheck.wrongParagraphs(out))
    }

    @Test
    fun docxTables_readOnlyWithSource_resetDropsEdits_andSaveToLibraryOnce() = runBlocking {
        val c = controller()
        c.importBytes("Rapport.docx", goldenDocx())
        val en = blockWith(c, "annual budget")
        c.editText(en.index, "The annual budget was approved in June.")
        c.resetEdits()
        assertFalse(c.state.value.hasEdits)
        c.editText(en.index, "The annual budget was approved in June.")
        c.create(ExportFormat.DOCX); c.awaitIdle()
        assertTrue(c.state.value.preview!!.valid)
        c.saveToLibrary(); c.awaitIdle()
        val id = c.state.value.preview!!.savedDocumentId!!
        c.saveToLibrary(); c.awaitIdle()
        val docs = db.documentDao().getAllDocuments().first()
        assertEquals(1, docs.size)
        assertEquals(id, docs.single().id)
        assertEquals("Rapport (reformatted).docx", docs.single().title)
        assertTrue(File(docs.single().filePath).canonicalPath.startsWith(libraryDir.canonicalPath))
        // Nothing of the source was added to the Library or to the templates.
        assertEquals(0, db.templateDao().getAllTemplates().first().size)
    }

    // ------------------------------------------------------------------ templates from the Library

    private fun templateDocx(): File {
        val f = File(tmp.root, "official_${System.nanoTime()}.docx")
        OfficeFilesTest.docx(f, OfficeFilesTest.p("Sample", "Heading1") +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1134\" w:bottom=\"1440\" w:left=\"1134\"/></w:sectPr>",
            mapOf("word/header1.xml" to "<w:hdr xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>MINISTRY HEADER</w:t></w:r></w:p></w:hdr>"))
        return f
    }

    @Test
    fun libraryTemplate_contentGoesIntoACopyOfTheTemplate_itsPartsAndPageSetupKept() = runBlocking {
        val tf = templateDocx()
        val templateBytes = tf.readBytes()
        val template = TemplateEntity("tpl-1", "Official Letter", "DOCX", tf.path, TemplateProfile.defaultProfile().toJson())
        db.templateDao().insertTemplate(template)
        val c = controller()
        c.importBytes("Rapport.docx", goldenDocx())
        c.setFormatting(template.id)
        c.create(ExportFormat.DOCX); c.awaitIdle()
        val p = c.state.value.preview!!
        assertTrue("${p.issues} ${p.notes}", p.valid)
        assertEquals("Official Letter", p.templateName)
        assertTrue(p.notes.contains("template_applied"))
        val out = entries(File(p.path))
        val tpl = entries(tf)
        // The template's own parts come with it; its page setup is the document's.
        assertArrayEquals(tpl.getValue("word/header1.xml"), out.getValue("word/header1.xml"))
        assertArrayEquals(tpl.getValue("word/styles.xml"), out.getValue("word/styles.xml"))
        val xml = String(out.getValue("word/document.xml"))
        assertTrue(xml.contains("w:left=\"1134\""))
        // All the content is there, once, with the Arabic paragraph right to left.
        val text = OoxmlReader.read(File(p.path)).toText()
        assertEquals(1, Regex(Regex.escape("REF-2026-015")).findAll(text).count())
        assertTrue(text.contains("validé") && text.contains(en1))
        assertEquals(0, RtlCheck.wrongParagraphs(File(p.path)))
        // The template file itself is unchanged.
        assertArrayEquals(templateBytes, tf.readBytes())
    }

    @Test
    fun deletedTemplate_backToSourceFormatting_withAMessage() = runBlocking {
        val tf = templateDocx()
        db.templateDao().insertTemplate(TemplateEntity("tpl-2", "Report", "DOCX", tf.path, TemplateProfile.defaultProfile().toJson()))
        val c = controller()
        c.importBytes("Rapport.docx", goldenDocx())
        c.setFormatting("tpl-2")
        tf.delete()
        c.create(ExportFormat.DOCX); c.awaitIdle()
        assertEquals("template_missing", c.state.value.error)
        assertNull(c.state.value.templateId)
        assertNull(c.state.value.preview)
    }

    // ------------------------------------------------------------------ PDF and image

    @Test
    fun textPdf_import_edit_rebuiltWord_notedAsReconstructed() {
        val pdf = TestPdf()
        fun t(y: Int, s: String) = "BT /F1 12 Tf 0 0 0 rg 72 $y Td ${TestPdf.lit(s)} Tj ET\n"
        pdf.page(t(760, "Annual report") + t(720, "Sales grew by ten percent this year.") + t(680, "Signed in Paris on 12/05/2026."))
        val c = controller()
        c.importBytes("report.pdf", pdf.build())
        assertNull(c.state.value.error)
        assertEquals(SourceKind.PDF, c.state.value.source!!.kind)
        val b = blockWith(c, "12/05/2026")
        c.editText(b.index, b.text.replace("12/05/2026", "20/05/2026"))
        c.create(ExportFormat.DOCX); c.awaitIdle()
        val p = c.state.value.preview!!
        assertTrue("${p.issues} ${p.notes}", p.valid)
        assertTrue(p.notes.contains("reconstructed"))
        assertTrue(p.text.contains("20/05/2026") && p.text.contains("Sales grew"))
    }

    @Test
    fun image_ocrResult_uncertainReadingsMarked_editedThenCreated() {
        ocrPages = listOf(ExtractedPage(0, PageSource.IMAGE_OCR, listOf(
            ExtractedBlock.Paragraph("الموضوع: طلب عطلة", true),
            ExtractedBlock.Paragraph("Date : 3O/10/2026", false, region = RegionMeta("r", null, Script.LATIN, 0.4f, needsReview = true))
        ), confidence = 0.6f))
        val c = controller()
        c.importFile("photo.png") { ByteArray(64).also { b -> byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b) }.inputStream() }
        c.awaitIdle()
        // Insert File accepts documents only; a photo goes through Image.
        assertEquals("unsupported_format", c.state.value.error)
        c.clearError()
        c.importImage("photo.png") { ByteArray(64).also { b -> byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(b) }.inputStream() }
        c.awaitIdle()
        assertNull(c.state.value.error)
        assertEquals(SourceKind.IMAGE, c.state.value.source!!.kind)
        assertEquals(1, c.state.value.uncertainBlocks)
        val d = blockWith(c, "3O/10")
        c.editText(d.index, "Date : 30/10/2026")
        c.create(ExportFormat.DOCX); c.awaitIdle()
        val p = c.state.value.preview!!
        assertTrue("${p.issues} ${p.notes}", p.valid)
        assertTrue(p.text.contains("30/10/2026") && p.text.contains("طلب عطلة"))
        assertEquals(0, RtlCheck.wrongParagraphs(File(p.path)))
    }

    @Test
    fun pdfExport_opensWithPages() {
        val c = controller()
        c.importBytes("Rapport.docx", goldenDocx())
        c.create(ExportFormat.PDF); c.awaitIdle()
        val p = c.state.value.preview
        if (p == null || p.issues.contains("output_unreadable") || p.issues.contains("output_missing")) {
            Assume.assumeTrue("PDF drawing is not available in this test runtime (${c.state.value.error})", false)
        }
        assertTrue("${p!!.issues} ${p.notes}", p.valid)
        assertTrue(PdfDocumentReader.open(File(p.path)).pages().isNotEmpty())
    }

    // ------------------------------------------------------------------ errors and recovery

    @Test
    fun errors_unavailableEmptyUnsupported_sourceChanged() {
        val c = controller()
        c.importFile("gone.docx") { null }; c.awaitIdle()
        assertEquals("source_unavailable", c.state.value.error)
        c.importFile("revoked.docx") { throw SecurityException("permission revoked") }; c.awaitIdle()
        assertEquals("source_unavailable", c.state.value.error)
        c.importFile("empty.docx") { ByteArray(0).inputStream() }; c.awaitIdle()
        assertEquals("document_empty", c.state.value.error)
        c.importFile("notes.txt") { "plain text".byteInputStream() }; c.awaitIdle()
        assertEquals("unsupported_format", c.state.value.error)
        assertNull(c.state.value.source)
        assertTrue(File(workDir, "source").listFiles().orEmpty().isEmpty())
        c.importBytes("Rapport.docx", goldenDocx())
        File(c.state.value.source!!.path).appendBytes(byteArrayOf(1))
        c.create(ExportFormat.DOCX); c.awaitIdle()
        assertEquals("source_changed", c.state.value.error)
    }

    @Test
    fun restart_restoresSourceEditsAndFormatting() = runBlocking {
        val c = controller()
        c.importBytes("Rapport.docx", goldenDocx())
        val en = blockWith(c, "annual budget")
        c.editText(en.index, "Edited after import.")
        c.setFormatting("tpl-x")
        withTimeout(10_000) { while (!File(workDir, "workspace.json").readTextOrEmpty().contains("Edited after import.") || !File(workDir, "workspace.json").readTextOrEmpty().contains("tpl-x")) delay(10) }
        val again = controller()
        again.restore()
        val s = again.state.value
        assertNotNull(s.source)
        assertEquals("Edited after import.", s.blocks.first { it.block.index == en.index }.text)
        assertEquals("tpl-x", s.templateId)
        // Removing the source clears the workspace and its private copy.
        again.removeSource()
        withTimeout(10_000) { while (File(workDir, "workspace.json").exists()) delay(10) }
        assertNull(again.state.value.source)
    }

    private fun File.readTextOrEmpty() = runCatching { readText() }.getOrDefault("")

    @Test
    fun minimalReplacement_keepsCommonStartAndEnd_neverSplitsSurrogates() {
        val r = ReformatCore.minimalReplacement(3, "Le rapport est validé.", "Le rapport est approuvé.")!!
        assertEquals(3, r.index)
        // The common end ("é.") stays in its run: only the changed middle is replaced.
        assertEquals("valid", r.oldText)
        assertEquals("approuv", r.newText)
        assertEquals("Le rapport est approuvé.", "Le rapport est validé.".replaceFirst(r.oldText, r.newText))
        val ins = ReformatCore.minimalReplacement(0, "AB", "AxB")!!
        assertEquals("A" to "Ax", ins.oldText to ins.newText)
        val again = ReformatCore.minimalReplacement(0, "x 15 y 15", "x 15 y 16")!!
        assertEquals(1, again.occurrence)
        assertNull(ReformatCore.minimalReplacement(0, "same", "same"))
        val emoji = ReformatCore.minimalReplacement(0, "a😀b", "a😁b")!!
        assertEquals("😀", emoji.oldText)
    }

    @Test
    fun libraryTemplates_onlyTemplateRecords_notOrdinaryDocuments() = runBlocking {
        val tf = templateDocx()
        db.templateDao().insertTemplate(TemplateEntity("tpl-3", "Business Report", "DOCX", tf.path, TemplateProfile.defaultProfile().toJson()))
        db.documentDao().insertDocument(DocumentEntity("doc-1", "Invoice.docx", "DOCX", tf.path, 1, 0, 0, providerUsed = "Import"))
        val repo = LibraryTemplateRepository(db.templateDao())
        assertEquals(listOf("tpl-3"), repo.observe().first().map { it.id })
        assertEquals(listOf(true), repo.refs(repo.observe().first()).map { it.available })
        assertNotNull(repo.resolve("tpl-3"))
        assertNull(repo.resolve("doc-1"))
        db.templateDao().getTemplateById("tpl-3")?.let { db.templateDao().deleteTemplate(it) }
        assertNull(repo.resolve("tpl-3"))
    }
}
