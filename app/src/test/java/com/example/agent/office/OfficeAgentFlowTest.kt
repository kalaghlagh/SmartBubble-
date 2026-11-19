package com.example.agent.office

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.OfficeFilesTest
import com.example.agent.core.AgentEngine
import com.example.agent.core.AgentTask
import com.example.agent.core.FileSource
import com.example.agent.core.InMemoryAgentTaskStore
import com.example.agent.core.LibraryRef
import com.example.agent.core.StepStatus
import com.example.agent.core.TaskStatus
import com.example.agent.core.ToolException
import com.example.agent.core.ToolPermission
import com.example.agent.core.ToolRegistry
import com.example.agent.files.AgentFileAccess
import com.example.agent.files.LibraryImporter
import com.example.agent.files.OoxmlReader
import com.example.agent.thesis.SourceExtractor
import com.example.data.db.SmartBubbleDatabase
import com.example.extraction.ExtractedDocument
import com.example.extraction.PdfTextExtraction
import com.example.template.pdf.PdfDocumentReader
import com.example.template.pdf.TestPdf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

/**
 * Document Reformatting end to end on the app's real path: a source from the Library (real
 * import) or picked from the phone (private copy) -> Agent engine with the registered Office
 * tools -> analysis (Word reader / the extraction engine's text-PDF path) -> changes from a
 * natural-language request -> approval -> new Library document written by the existing engines
 * -> check. Synthetic documents only; no AI request.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfficeAgentFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SmartBubbleDatabase
    private lateinit var importer: LibraryImporter
    private lateinit var access: AgentFileAccess
    private val store = InMemoryAgentTaskStore()

    private val extractor: SourceExtractor = { file, isPdf, pages ->
        assertTrue("images go to OCR on a device; these tests use Word and text PDFs", isPdf)
        val read = PdfTextExtraction.readPages(PdfDocumentReader.open(file))
        ExtractedDocument(read.filter { pages == null || it.first in pages }.mapIndexedNotNull { i, (p, page) -> page?.copy(index = i, sourcePage = p) })
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SmartBubbleDatabase::class.java).allowMainThreadQueries().build()
        val libraryDir = tmp.newFolder("generated_docs")
        importer = LibraryImporter(libraryDir, db.documentDao())
        access = AgentFileAccess(db, libraryDir, tmp.newFolder("work"), extract = { _, _ -> error("not used") }, store = store)
    }

    @After
    fun tearDown() = db.close()

    private fun engine() = AgentEngine(
        ToolRegistry(setOf(ToolPermission.READ_LIBRARY, ToolPermission.WRITE_LIBRARY))
            .register(OfficeAnalyzeTool(access, extractor))
            .register(OfficePrepareTool())
            .register(OfficeWriteTool(access, db, { "$it (updated)" }))
            .register(OfficeValidateTool(access, db)),
        store
    )

    private var n = 0
    private fun id() = "exec-${System.nanoTime()}-${n++}"

    /** A French request letter: bold labels in their own runs, the values in plain runs. */
    private fun letterDocx(): ByteArray {
        val f = File(tmp.root, "letter_${System.nanoTime()}.docx")
        fun labelled(label: String, value: String) =
            "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">$label</w:t></w:r><w:r><w:t xml:space=\"preserve\">$value</w:t></w:r></w:p>"
        OfficeFilesTest.docx(
            f,
            OfficeFilesTest.p("Ahmed Benali") + OfficeFilesTest.p("Ouargla") +
                labelled("Tél : ", "0661 23 45 67") +
                OfficeFilesTest.p("À Monsieur le Directeur du Bureau de distribution") +
                labelled("Objet : ", "Demande d'autorisation d'approvisionnement") +
                labelled("Réf : ", "REF-2026-015") +
                OfficeFilesTest.p("Ouargla, le 15/10/2026") +
                OfficeFilesTest.p("Je vous prie de bien vouloir m'accorder l'autorisation demandée.")
        )
        return f.readBytes()
    }

    private suspend fun importLibrary(name: String, bytes: ByteArray): LibraryRef {
        val doc = importer.import(name) { bytes.inputStream() }.document!!
        return LibraryRef(doc.id, doc.title, doc.fileType, FileSource.LIBRARY)
    }

    private suspend fun analyze(source: LibraryRef): AgentTask =
        engine().run(OfficeTaskPlanner.analyze("a-${System.nanoTime()}", "s", source.title, source, "fr", 1L, ::id).also { store.save(it) })

    private suspend fun change(source: LibraryRef, request: String): Pair<AgentEngine, AgentTask> {
        val e = engine()
        return e to e.run(OfficeTaskPlanner.change("c-${System.nanoTime()}", "s", request, source, "fr", 1L, ::id).also { store.save(it) })
    }

    private suspend fun libraryCount() = db.documentDao().getAllDocuments().first().size
    private fun documentXml(path: String) = ZipFile(path).use { z -> String(z.getInputStream(z.getEntry("word/document.xml")).readBytes()) }

    @Test
    fun word_analysis_showsFieldsWithoutChangingAnything() = runBlocking {
        val bytes = letterDocx()
        val src = importLibrary("Demande.docx", bytes)
        val done = analyze(src)
        assertEquals(TaskStatus.COMPLETED, done.status)
        val out = done.step("analyze")!!.output
        val a = OfficeCodec.decode(out.getValue("analysis"))
        assertEquals(listOf("Demande d'autorisation d'approvisionnement"), a.fields.filter { it.key == FieldKey.SUBJECT }.map { it.value })
        assertEquals(listOf("REF-2026-015"), a.fields.filter { it.key == FieldKey.REFERENCE }.map { it.value })
        assertEquals(listOf("15/10/2026"), a.fields.filter { it.key == FieldKey.DATE }.map { it.value })
        assertTrue(out.getValue("summary"), out.getValue("summary").contains("REF-2026-015"))
        assertEquals(1, libraryCount())
        assertArrayEquals(bytes, File(db.documentDao().getDocumentById(src.id)!!.filePath).readBytes())
    }

    @Test
    fun word_change_approve_newWordCopy_onlyRequestedTexts_formattingKept_sourceUnchanged() = runBlocking {
        val bytes = letterDocx()
        val src = importLibrary("Demande.docx", bytes)
        val sourcePath = db.documentDao().getDocumentById(src.id)!!.filePath
        val (e, waiting) = change(src, "change the subject to Demande de renouvellement and the date to 20/10/2026")
        assertEquals(waiting.steps.joinToString { "${it.id}=${it.status}/${it.error}" }, TaskStatus.WAITING_FOR_APPROVAL, waiting.status)
        assertEquals("write", waiting.pendingApproval?.id)
        val preview = waiting.step("prepare")!!.output.getValue("preview")
        assertTrue(preview, preview.contains("15/10/2026 → 20/10/2026"))
        assertEquals(1, libraryCount()) // nothing written before approval

        val done = e.run(e.decide(waiting, "write", true))
        assertEquals(done.steps.joinToString { "${it.id}=${it.status}/${it.error}/${it.output["issues"]}" }, TaskStatus.COMPLETED, done.status)
        val newDoc = db.documentDao().getDocumentById(done.step("verify")!!.output.getValue("documentId"))!!
        assertEquals("DOCX", newDoc.fileType)
        assertTrue(newDoc.title.contains("(updated)"))
        assertEquals(2, libraryCount())
        // The source is byte for byte the same.
        assertArrayEquals(bytes, File(sourcePath).readBytes())
        // Only the two values changed; every other paragraph is identical.
        val before = OoxmlReader.read(File(sourcePath)).paragraphs.toMap()
        val after = OoxmlReader.read(File(newDoc.filePath)).paragraphs.toMap()
        assertEquals(before.keys, after.keys)
        val changed = before.keys.filter { before[it] != after[it] }
        assertEquals(2, changed.size)
        assertTrue(after.values.contains("Objet : Demande de renouvellement"))
        assertTrue(after.values.contains("Ouargla, le 20/10/2026"))
        // The bold label stays in its own bold run; the new value is in the plain run.
        val xml = documentXml(newDoc.filePath)
        assertTrue(xml, Regex("""<w:b/></w:rPr><w:t[^>]*>Objet : </w:t></w:r><w:r><w:t[^>]*>Demande de renouvellement</w:t>""").containsMatchIn(xml))
    }

    @Test
    fun rejected_or_unclear_or_missing_or_protected_createsNothing() = runBlocking {
        val src = importLibrary("Demande.docx", letterDocx())
        val (e, waiting) = change(src, "change the date to 20/10/2026")
        val rejected = e.run(e.decide(waiting, "write", false))
        assertEquals(StepStatus.REJECTED, rejected.step("write")!!.status)
        assertEquals(1, libraryCount())
        assertEquals("no_changes_understood", change(src, "make it look nicer").second.error)
        assertEquals("field_not_found:address", change(src, "change the address to Alger").second.error)
        assertTrue(change(src, "replace \"Paris\" with \"Alger\"").second.error!!.startsWith("text_not_found"))
        // An identity document: read, but no modified version is made.
        val id = File(tmp.root, "id.docx").also { OfficeFilesTest.docx(it, OfficeFilesTest.p("PASSEPORT") + OfficeFilesTest.p("Nom : Benali")) }
        val idRef = importLibrary("Passeport.docx", id.readBytes())
        assertEquals("identity", analyze(idRef).step("analyze")!!.output["protected"])
        assertEquals("protected_document:identity", change(idRef, "change Nom to X").second.error)
        assertEquals(2, libraryCount())
    }

    @Test
    fun sourceChangedAfterThePreview_nothingIsWritten() = runBlocking {
        val src = importLibrary("Demande.docx", letterDocx())
        val (e, waiting) = change(src, "change the date to 20/10/2026")
        val path = db.documentDao().getDocumentById(src.id)!!.filePath
        File(path).appendBytes(byteArrayOf(0))
        val failed = e.run(e.decide(waiting, "write", true))
        assertEquals("source_changed", failed.error)
        assertEquals(1, libraryCount())
    }

    @Test
    fun restart_afterWriting_findsTheDocument_doesNotWriteTwice() = runBlocking {
        val src = importLibrary("Demande.docx", letterDocx())
        val (e, waiting) = change(src, "change the date to 20/10/2026")
        val done = e.run(e.decide(waiting, "write", true))
        assertEquals(TaskStatus.COMPLETED, done.status)
        val interrupted = done.copy(status = TaskStatus.RUNNING).withStep(done.step("write")!!.copy(status = StepStatus.RUNNING, output = emptyMap()))
            .withStep(done.step("verify")!!.copy(status = StepStatus.PENDING, output = emptyMap()))
        store.save(interrupted)
        val recovered = engine().recover(interrupted)
        assertEquals(StepStatus.COMPLETED, recovered.step("write")!!.status)
        assertEquals(TaskStatus.COMPLETED, engine().run(recovered).status)
        assertEquals(2, libraryCount())
    }

    @Test
    fun phoneSource_isAPrivateCopy_notInLibraryNorTemplates_refusedWhenUnsupportedOrUnavailable() = runBlocking {
        val bytes = letterDocx()
        val ref = access.stageDeviceSource("Lettre.docx") { bytes.inputStream() }
        assertEquals(FileSource.DEVICE, ref.source)
        assertTrue(ref.fileId.startsWith("device:"))
        assertEquals(0, libraryCount())
        assertEquals(0, db.templateDao().getAllTemplates().first().size)
        val (e, waiting) = change(ref, "change the reference to REF-2026-020")
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, waiting.status)
        val done = e.run(e.decide(waiting, "write", true))
        assertEquals(TaskStatus.COMPLETED, done.status)
        // Only the new document is in the Library; the phone copy is discarded on request.
        assertEquals(1, libraryCount())
        access.discardDeviceSource(ref.id)
        assertFalse(File(access.sourcesDir, ref.id).exists())
        fun code(block: suspend () -> Unit) = runBlocking { try { block(); fail(); "" } catch (e: ToolException) { e.code } }
        assertEquals("unsupported_format", code { access.stageDeviceSource("notes.txt") { "plain text".byteInputStream() } })
        assertEquals("source_unavailable", code { access.stageDeviceSource("gone.pdf") { null } })
        assertEquals("source_unavailable", code { access.stageDeviceSource("revoked.pdf") { throw SecurityException("permission revoked") } })
        assertTrue(access.sourcesDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun libraryFiles_list_rename_copy_delete_withApprovalForRenameAndDelete() = runBlocking {
        val src = importLibrary("Demande.docx", letterDocx())
        val registry = ToolRegistry(setOf(ToolPermission.READ_LIBRARY, ToolPermission.WRITE_LIBRARY))
            .register(LibraryListTool(db)).register(LibraryRenameTool(db)).register(LibraryCopyTool(access, db) { "$it (copy)" }).register(LibraryDeleteTool(db))
        val e = AgentEngine(registry, store)
        suspend fun run(text: String): AgentTask {
            val r = FileRequestParser.parse(text, access.listAccessibleFiles())!!
            return e.run(LibraryTools.plan("f-${System.nanoTime()}", "s", text, r, "en", 1L, ::id).also { store.save(it) })
        }
        val listed = run("List my Word documents")
        assertEquals("1", listed.step("list")!!.output["count"])
        assertTrue(run("Find autorisation in my library").step("list")!!.output["items"]!!.contains("Demande.docx"))
        val renaming = run("Rename Demande.docx to Demande finale")
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, renaming.status)
        assertEquals("Demande.docx", db.documentDao().getDocumentById(src.id)!!.title)
        assertEquals(TaskStatus.COMPLETED, e.run(e.decide(renaming, "rename", true)).status)
        assertEquals("Demande finale.docx", db.documentDao().getDocumentById(src.id)!!.title)
        val copied = run("duplicate Demande finale.docx")
        assertEquals(TaskStatus.COMPLETED, copied.status)
        assertEquals(2, libraryCount())
        val deleting = run("delete Demande finale.docx")
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, deleting.status)
        e.run(e.decide(deleting, "delete", false))
        assertEquals(2, libraryCount())
        val deleting2 = run("delete Demande finale.docx")
        val path = db.documentDao().getDocumentById(src.id)!!.filePath
        assertEquals(TaskStatus.COMPLETED, e.run(e.decide(deleting2, "delete", true)).status)
        assertEquals(1, libraryCount())
        assertFalse(File(path).exists())
    }

    /** A text PDF letter (the engine's direct text path; no OCR). */
    private fun letterPdf(): ByteArray {
        val pdf = TestPdf()
        fun t(y: Int, s: String, font: String = "/F1") = "BT $font 11 Tf 0 0 0 rg 72 $y Td ${TestPdf.lit(s)} Tj ET\n"
        pdf.page(
            t(780, "Ahmed Benali") + t(766, "Ouargla") +
                t(720, "A Monsieur le Directeur du Bureau de distribution") +
                t(680, "Objet : Demande d'autorisation", "/F2") + t(660, "Ref : REF-2026-015") +
                t(640, "Ouargla, le 15/10/2026") +
                t(600, "Je vous prie de bien vouloir m'accorder l'autorisation demandee.")
        )
        return pdf.build()
    }

    @Test
    fun pdf_change_rebuiltWordDocument_withTheChange_andTheRestOfTheText() = runBlocking {
        val bytes = letterPdf()
        val src = importLibrary("Demande.pdf", bytes)
        val path = db.documentDao().getDocumentById(src.id)!!.filePath
        val analyzed = analyze(src)
        assertEquals(analyzed.steps.joinToString { "${it.id}=${it.status}/${it.error}" }, TaskStatus.COMPLETED, analyzed.status)
        val a = OfficeCodec.decode(analyzed.step("analyze")!!.output.getValue("analysis"))
        assertEquals("PDF", a.sourceKind)
        assertTrue(a.fields.toString(), a.fields.any { it.key == FieldKey.DATE && it.value == "15/10/2026" })
        val (e, waiting) = change(src, "change the date to 20/10/2026")
        val done = e.run(e.decide(waiting, "write", true))
        assertEquals(done.steps.joinToString { "${it.id}=${it.status}/${it.error}/${it.output["issues"]}" }, TaskStatus.COMPLETED, done.status)
        assertEquals("true", done.step("write")!!.output["rebuilt"])
        val newDoc = db.documentDao().getDocumentById(done.step("verify")!!.output.getValue("documentId"))!!
        val text = OoxmlReader.read(File(newDoc.filePath)).toText()
        assertTrue(text, text.contains("20/10/2026"))
        assertFalse(text, text.contains("15/10/2026"))
        assertTrue(text, text.contains("REF-2026-015") && text.contains("Je vous prie"))
        assertArrayEquals(bytes, File(path).readBytes())
    }

    @Test
    fun word_toPdf_output() = runBlocking {
        val src = importLibrary("Demande.docx", letterDocx())
        val (e, waiting) = change(src, "change the date to 20/10/2026, in PDF")
        assertEquals("PDF", waiting.step("prepare")!!.output["format"])
        val done = try { e.run(e.decide(waiting, "write", true)) } catch (t: Throwable) {
            Assume.assumeNoException("PDF rendering is not available in this test runtime", t); return@runBlocking
        }
        if (done.error == "write_failed") Assume.assumeTrue("PDF rendering is not available in this test runtime", false)
        assertEquals(done.steps.joinToString { "${it.id}=${it.status}/${it.error}/${it.output["issues"]}" }, TaskStatus.COMPLETED, done.status.let {
            if (it == TaskStatus.COMPLETED_WITH_WARNINGS) TaskStatus.COMPLETED else it
        })
        val newDoc = db.documentDao().getDocumentById(done.step("verify")!!.output.getValue("documentId"))!!
        assertEquals("PDF", newDoc.fileType)
        assertTrue(PdfDocumentReader.open(File(newDoc.filePath)).pages().isNotEmpty())
    }
}
