package com.example.agent.thesis

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.core.AgentEngine
import com.example.agent.core.AgentTask
import com.example.agent.core.InMemoryAgentTaskStore
import com.example.agent.core.StepStatus
import com.example.agent.core.TaskStatus
import com.example.agent.core.ToolPermission
import com.example.agent.core.ToolRegistry
import com.example.agent.files.AgentFileAccess
import com.example.agent.files.LibraryImporter
import com.example.data.db.SmartBubbleDatabase
import com.example.extraction.ExtractedDocument
import com.example.extraction.PdfTextExtraction
import com.example.template.pdf.PdfDocumentReader
import com.example.template.pdf.TestPdf
import com.example.thesis.core.NewElement
import com.example.thesis.core.ThesisOp
import com.example.thesis.data.ThesisRepository
import com.example.thesis.export.ThesisDocxWriter
import com.example.thesis.export.ThesisTemplate
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ChangeSource
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * The Agent's thesis workflows end to end on the app's real execution path: chat request ->
 * local router and planner -> Agent engine with the registered thesis tools -> Library file
 * (real import) read by the engine's digital-PDF extraction (PdfTextExtraction, the same code the
 * extraction engine uses for text pages) -> Thesis Builder repository (real Room database and
 * revision files) -> check -> undo. Synthetic documents only; no AI request is made.
 */
@RunWith(RobolectricTestRunner::class)
class ThesisAgentFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SmartBubbleDatabase
    private lateinit var repo: ThesisRepository
    private lateinit var importer: LibraryImporter
    private lateinit var access: AgentFileAccess
    private val store = InMemoryAgentTaskStore()
    private lateinit var thesis: TestThesis
    private var extractCalls = 0

    /** The app's thesis access, over the real repository (as in the ViewModel), with test controls. */
    inner class TestThesis : ThesisAccess {
        var active: String? = null
        val drafts = mutableSetOf<Pair<String, String>>()
        val changedCalls = mutableListOf<String>()
        /** Simulates a faulty write: the change also touches another section. */
        var corruptNextApply = false
        var applies = 0

        override suspend fun activeThesisId() = active?.takeIf { repo.project(it) != null }
        override suspend fun projects() = repo.projects().map { it.id to it.title }
        override suspend fun snapshot(projectId: String) = repo.snapshot(projectId)
        override suspend fun currentRevision(projectId: String) = repo.project(projectId)!!.let { it.currentRevisionId to it.currentRevisionNumber }
        override suspend fun apply(projectId: String, ops: List<ThesisOp>, summary: String): Pair<String, Int> {
            applies++
            val extra = if (corruptNextApply) {
                corruptNextApply = false
                val intro = repo.snapshot(projectId).sections.first { it.kind == SectionKind.INTRODUCTION }.id
                listOf(ThesisOp.AddElements(intro, null, listOf(NewElement("stray", ElementType.PARAGRAPH, "stray"))))
            } else emptyList()
            return repo.applyDirect(projectId, ops + extra, ChangeSource.USER, summary).let { it.id to it.number }
        }
        override suspend fun revisionTagged(projectId: String, tag: String) = repo.revisions(projectId).firstOrNull { it.summary.startsWith(tag) }?.let { it.id to it.number }
        override suspend fun restore(projectId: String, revisionId: String) = repo.restore(projectId, revisionId).number
        override fun hasUnsavedDraft(projectId: String, sectionId: String) = (projectId to sectionId) in drafts
        override fun changed(projectId: String) { changedCalls += projectId }
    }

    /** The extraction engine's text-PDF path: pages read directly from the PDF text layer, with tables from its rulings. */
    private val extractor: SourceExtractor = { file, isPdf, pages ->
        assertTrue(isPdf)
        extractCalls++
        val read = PdfTextExtraction.readPages(PdfDocumentReader.open(file))
        ExtractedDocument(read.filter { pages == null || it.first in pages }.mapIndexedNotNull { i, (p, page) -> page?.copy(index = i, sourcePage = p) })
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SmartBubbleDatabase::class.java).allowMainThreadQueries().build()
        repo = ThesisRepository(db, tmp.newFolder("thesis"), imageInspector = { null })
        val libraryDir = tmp.newFolder("generated_docs")
        importer = LibraryImporter(libraryDir, db.documentDao())
        access = AgentFileAccess(db, libraryDir, tmp.newFolder("work"), extract = { _, _ -> error("structured extraction is used") }, store = store)
        thesis = TestThesis()
    }

    @After
    fun tearDown() = db.close()

    private fun engine(): AgentEngine {
        var n = 0
        val registry = ToolRegistry(setOf(ToolPermission.READ_LIBRARY, ToolPermission.WRITE_LIBRARY))
            .register(ThesisLocateTool(thesis))
            .register(SourceExtractTool(access, extractor))
            .register(ThesisPrepareTool(thesis) { "agent-el-${n++}" })
            .register(ThesisApplyTool(thesis))
            .register(ThesisValidateTool(thesis, access))
        return AgentEngine(registry, store)
    }

    /** A synthetic "Resources minerales" PDF: a title page, an acknowledgements page and a ruled, captioned Table 03. */
    private fun sourcePdf(): ByteArray {
        val pdf = TestPdf()
        fun text(font: String, size: Int, x: Int, y: Int, s: String) = "BT $font $size Tf 0 0 0 rg $x $y Td ${TestPdf.lit(s)} Tj ET\n"
        pdf.page(text("/F2", 20, 72, 760, "Resources minerales") + text("/F1", 11, 72, 720, "Etude des ressources du bassin, voir Tableau 03 plus loin."))
        pdf.page(
            text("/F2", 16, 72, 760, "Remerciements") +
                text("/F1", 11, 72, 720, "Nous remercions le laboratoire de geologie pour son accueil et son aide constante.") +
                text("/F1", 11, 72, 680, "Merci a nos familles pour leur patience.")
        )
        val c = StringBuilder(text("/F1", 11, 72, 760, "Tableau 03 : Production miniere"))
        val top = 730
        c.append("0.5 0.5 0.5 RG 0.75 w\n")
        for (r in 0..3) c.append("72 ${top - r * 20} m 518 ${top - r * 20} l S\n")
        for (x in listOf(72, 220, 370, 518)) c.append("$x $top m $x ${top - 60} l S\n")
        val rows = listOf(listOf("Mineral", "Tonnes", "Annee"), listOf("Fer", "1200", "2023"), listOf("Zinc", "300", "2023"))
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { k, cell -> c.append(text(if (r == 0) "/F2" else "/F1", 10, listOf(80, 228, 378)[k], top - r * 20 - 14, cell)) }
        }
        pdf.page(c.toString())
        return pdf.build()
    }

    private lateinit var sourceBytes: ByteArray

    private suspend fun importSource(): String {
        sourceBytes = sourcePdf()
        return importer.import("Resources minerales.pdf") { sourceBytes.inputStream() }.document!!.let { File(it.filePath).absolutePath }
    }

    private suspend fun newThesis(title: String) = repo.createProject(ThesisMeta(title, AcademicLevel.MASTER, "fr"))

    private suspend fun plan(text: String): AgentTask {
        val decision = ThesisRequestRouter.decide(text, access.listAccessibleFiles())
        assertTrue("$decision", decision is ThesisDecision.Plan)
        decision as ThesisDecision.Plan
        var n = 0
        return ThesisTaskPlanner.plan("task-${System.nanoTime()}", "s", text, decision.request, decision.source, "fr", 1L) { "exec-${System.nanoTime()}-${n++}" }
            .also { store.save(it) }
    }

    private fun section(s: ThesisSnapshot, kind: SectionKind) = s.sections.first { it.kind == kind }
    private fun chapter(s: ThesisSnapshot, n: Int) = s.outline().map { it.first }.filter { it.kind == SectionKind.CHAPTER }[n - 1]
    private fun hashes(s: ThesisSnapshot) = s.sections.associate { it.id to s.sectionHash(it.id) }

    private suspend fun approve(e: AgentEngine, task: AgentTask) = e.run(e.decide(task, "apply", true))

    // ------------------------------------------------------------------ Workflow A

    @Test
    fun workflowA_pageText_intoAcknowledgements_preview_approve_checkpoint_validate_undo() = runBlocking {
        val sourcePath = importSource()
        val id = newThesis("Mémoire géologique")
        val other = newThesis("Autre mémoire")
        thesis.active = id
        val before = repo.snapshot(id)
        val otherBefore = hashes(repo.snapshot(other))
        val revisionsBefore = repo.revisions(id).size
        val e = engine()

        val waiting = e.run(plan("Extract the Remerciements text from page 2 of Resources minerales.pdf and copy it into the Remerciements section of my thesis."))
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, waiting.status)
        assertEquals("apply", waiting.pendingApproval?.id)
        // Nothing is written before approval.
        assertEquals(revisionsBefore, repo.revisions(id).size)
        assertEquals("true", waiting.step("locate")!!.output["active"])
        val prepared = waiting.step("prepare")!!.output
        assertTrue(prepared["preview"], prepared.getValue("preview").contains("Nous remercions"))
        assertEquals("end:0", prepared["position"])
        assertEquals("2", waiting.step("extract")!!.output["pages"])

        val done = approve(e, waiting)
        assertEquals(done.steps.joinToString { "${it.id}=${it.status}/${it.error}" }, TaskStatus.COMPLETED, done.status)
        assertEquals("COMPLETED", done.step("check")!!.output["outcome"])
        val after = repo.snapshot(id)
        val ack = after.elementsOf(section(after, SectionKind.ACKNOWLEDGEMENTS).id)
        val text = ack.joinToString("\n") { it.text }
        assertTrue(text, text.contains("Nous remercions le laboratoire") && text.contains("Merci a nos familles"))
        assertTrue("the page heading is not repeated under the section title", ack.none { it.text.trim() == "Remerciements" })
        assertTrue(ack.all { it.type == ElementType.PARAGRAPH })
        // One revision (the checkpoint), every other section unchanged, the other thesis and the source untouched.
        assertEquals(revisionsBefore + 1, repo.revisions(id).size)
        val ackId = section(after, SectionKind.ACKNOWLEDGEMENTS).id
        assertEquals(hashes(before) - ackId, hashes(after) - ackId)
        assertEquals(otherBefore, hashes(repo.snapshot(other)))
        assertArrayEquals(sourceBytes, File(sourcePath).readBytes())
        assertEquals(listOf(id), thesis.changedCalls)

        // Undo: the revision before the change, as a new revision (history kept).
        val applied = done.step("apply")!!.output
        repo.restore(id, applied.getValue("previousRevisionId"))
        assertEquals(hashes(before), hashes(repo.snapshot(id)))
        assertEquals(revisionsBefore + 2, repo.revisions(id).size)
    }

    // ------------------------------------------------------------------ Workflow B

    @Test
    fun workflowB_numberedTable_intoChapter2_rowsColumnsCaption_andWordExport() = runBlocking {
        importSource()
        val id = newThesis("Mémoire géologique")
        thesis.active = id
        val e = engine()
        val waiting = e.run(plan("Extract table 03 from Resources minerales.pdf and add it to my thesis in Chapter 2, page 14."))
        assertEquals(waiting.steps.joinToString { "${it.id}=${it.status}/${it.error}" }, TaskStatus.WAITING_FOR_APPROVAL, waiting.status)
        val prepared = waiting.step("prepare")!!.output
        assertEquals("3", prepared["rows"])
        assertEquals("3", prepared["columns"])
        assertEquals("3", waiting.step("extract")!!.output["pages"])
        assertEquals("14", waiting.step("locate")!!.input["pageHint"])

        val done = approve(e, waiting)
        assertEquals(done.steps.joinToString { "${it.id}=${it.status}/${it.error}" }, TaskStatus.COMPLETED, done.status)
        val after = repo.snapshot(id)
        val table = after.elementsOf(chapter(after, 2).id).single { it.type == ElementType.TABLE }
        assertEquals(listOf("Mineral", "Tonnes", "Annee"), table.data.tableHeader)
        assertEquals(listOf(listOf("Fer", "1200", "2023"), listOf("Zinc", "300", "2023")), table.data.tableRows)
        assertEquals("Production miniere", table.data.caption)
        // Nothing went into the other chapters.
        assertTrue(after.elementsOf(chapter(after, 1).id).isEmpty())

        // The Word export of the thesis carries a native table and the thesis' own caption numbering.
        val out = tmp.newFile("thesis.docx")
        ThesisDocxWriter().write(after, emptyList(), ThesisTemplate.Profile(ThesisDocxWriter.builtInProfile(), null), out)
        val xml = ZipFile(out).use { z -> String(z.getInputStream(z.getEntry("word/document.xml")).readBytes()) }
        assertTrue(xml.contains("<w:tbl>"))
        assertTrue(xml.contains("Production miniere"))
        assertTrue(xml.contains("Zinc"))
        assertFalse("the source's own number is not kept in the caption", xml.contains("Tableau 03"))
    }

    // ------------------------------------------------------------------ Workflow C

    @Test
    fun workflowC_replaceSecondParagraph_ofIntroduction_only() = runBlocking {
        val id = newThesis("Mémoire géologique")
        thesis.active = id
        val intro = section(repo.snapshot(id), SectionKind.INTRODUCTION).id
        repo.applyDirect(id, listOf(ThesisOp.AddElements(intro, null, listOf(
            NewElement("p1", ElementType.PARAGRAPH, "Premier paragraphe."),
            NewElement("p2", ElementType.PARAGRAPH, "Deuxième paragraphe."),
            NewElement("p3", ElementType.PARAGRAPH, "Troisième paragraphe.")
        ))), ChangeSource.USER, "setup")
        val e = engine()
        val waiting = e.run(plan("Replace the second paragraph of the Introduction section of my thesis with \"Nouvelle formulation du deuxième paragraphe.\""))
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, waiting.status)
        assertEquals("Deuxième paragraphe.", waiting.step("prepare")!!.output["oldText"])
        assertEquals("replace:2", waiting.step("prepare")!!.output["position"])
        val done = approve(e, waiting)
        assertEquals(TaskStatus.COMPLETED, done.status)
        val after = repo.snapshot(id).elementsOf(intro)
        assertEquals(listOf("p1", "p2", "p3"), after.map { it.id })
        assertEquals(listOf("Premier paragraphe.", "Nouvelle formulation du deuxième paragraphe.", "Troisième paragraphe."), after.map { it.text })
    }

    // ------------------------------------------------------------------ Workflow E (read-only)

    @Test
    fun workflowE_extractOnly_readsTheLibraryFile_changesNothing() = runBlocking {
        val path = importSource()
        val id = newThesis("Mémoire géologique")
        thesis.active = id
        val revisions = repo.revisions(id).size
        val done = engine().run(plan("Extract page 2 of Resources minerales.pdf"))
        assertEquals(TaskStatus.COMPLETED, done.status)
        val text = paragraphsAsText(done.step("extract")!!.output.getValue("content"))!!
        assertTrue(text, text.contains("Nous remercions"))
        assertArrayEquals(sourceBytes, File(path).readBytes())
        assertEquals(revisions, repo.revisions(id).size)
        assertTrue(done.steps.none { it.toolId == ThesisTools.APPLY })
    }

    // ------------------------------------------------------------------ safety

    @Test
    fun rejection_cancellation_changeNothing() = runBlocking {
        importSource()
        val id = newThesis("Mémoire géologique")
        thesis.active = id
        val before = hashes(repo.snapshot(id))
        val e = engine()
        val request = "Extract the text of page 2 of Resources minerales.pdf and copy it into the Remerciements section of my thesis."
        val rejected = e.run(e.decide(e.run(plan(request)), "apply", false))
        // Rejected: the change and its check never run; the task ends incomplete (not failed).
        assertEquals(TaskStatus.INCOMPLETE, rejected.status)
        assertEquals(StepStatus.REJECTED, rejected.step("apply")!!.status)
        assertEquals(StepStatus.PENDING, rejected.step("check")!!.status)
        val cancelled = e.cancelled(e.run(plan(request)))
        assertEquals(TaskStatus.CANCELLED, cancelled.status)
        assertEquals(before, hashes(repo.snapshot(id)))
        assertEquals(0, thesis.applies)
    }

    @Test
    fun theThesisChangedAfterThePreview_orHasUnsavedEdits_nothingIsApplied() = runBlocking {
        importSource()
        val id = newThesis("Mémoire géologique")
        thesis.active = id
        val e = engine()
        val request = "Extract the text of page 2 of Resources minerales.pdf and copy it into the Remerciements section of my thesis."
        // Unsaved edits of that section in the editor.
        val waiting = e.run(plan(request))
        val ack = section(repo.snapshot(id), SectionKind.ACKNOWLEDGEMENTS).id
        thesis.drafts += id to ack
        val busy = approve(e, waiting)
        assertEquals(TaskStatus.FAILED, busy.status)
        assertEquals("thesis_section_being_edited", busy.error)
        thesis.drafts.clear()
        // The user edited the section after the preview.
        val waiting2 = e.run(plan(request))
        repo.applyDirect(id, listOf(ThesisOp.AddElements(ack, null, listOf(NewElement("mine", ElementType.PARAGRAPH, "Mon propre texte.")))), ChangeSource.USER, "user")
        val stale = approve(e, waiting2)
        assertEquals("thesis_changed", stale.error)
        assertEquals(listOf("Mon propre texte."), repo.snapshot(id).elementsOf(ack).map { it.text })
        // The user switched to another thesis after the preview.
        val waiting3 = e.run(plan(request))
        thesis.active = newThesis("Autre mémoire")
        assertEquals("active_thesis_changed", approve(e, waiting3).error)
        assertEquals(0, thesis.applies)
    }

    @Test
    fun targetThesis_none_named_duplicate_unknown_andMissingSectionOrTable() = runBlocking {
        importSource()
        val e = engine()
        val req = "Extract the text of page 2 of Resources minerales.pdf and copy it into the Remerciements section of my thesis."
        assertEquals("no_active_thesis", e.run(plan(req)).error)
        val a = newThesis("Mémoire géologique")
        newThesis("Autre mémoire")
        thesis.active = a
        // Another thesis named explicitly: allowed, but flagged as not the active one for the approval.
        val named = e.run(plan("Extract the text of page 2 of Resources minerales.pdf and copy it into the Remerciements section of my thesis named Autre mémoire."))
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, named.status)
        assertEquals("false", named.step("locate")!!.output["active"])
        assertEquals("thesis_not_found:Inconnu", e.run(plan("Extract the text of page 2 of Resources minerales.pdf and copy it into the Remerciements section of my thesis named Inconnu.")).error)
        newThesis("Autre mémoire")
        assertEquals("thesis_ambiguous:Autre mémoire", e.run(plan("Extract the text of page 2 of Resources minerales.pdf and copy it into the Remerciements section of my thesis named Autre mémoire.")).error)
        // A section that does not exist: the writable sections are listed for the question.
        val missing = e.run(plan("Add \"Une phrase.\" at the end of the Methodology section of my thesis")).error!!
        assertTrue(missing, missing.startsWith("section_not_found:") && missing.contains("Introduction"))
        assertEquals("table_not_found:7", e.run(plan("Extract table 7 from Resources minerales.pdf into Chapter 1 of my thesis")).error)
        assertEquals("page_not_found", e.run(plan("Extract page 9 of Resources minerales.pdf into the Introduction of my thesis")).error)
        assertEquals(0, thesis.applies)
    }

    @Test
    fun restart_afterTheChangeWasWritten_isNotAppliedTwice_unconfirmedRunNeedsApprovalAgain() = runBlocking {
        importSource()
        val id = newThesis("Mémoire géologique")
        thesis.active = id
        val e = engine()
        val request = "Extract the text of page 2 of Resources minerales.pdf and copy it into the Remerciements section of my thesis."
        val done = approve(e, e.run(plan(request)))
        assertEquals(TaskStatus.COMPLETED, done.status)
        val revisions = repo.revisions(id).size
        val elements = repo.snapshot(id).elements.size
        // The app stopped while "apply" ran (its revision was already written).
        val interrupted = done.copy(status = TaskStatus.RUNNING).withStep(done.step("apply")!!.copy(status = StepStatus.RUNNING, output = emptyMap()))
            .withStep(done.step("check")!!.copy(status = StepStatus.PENDING, output = emptyMap()))
        store.save(interrupted)
        val recovered = engine().recover(interrupted)
        assertEquals(StepStatus.COMPLETED, recovered.step("apply")!!.status)
        val resumed = engine().run(recovered)
        assertEquals(TaskStatus.COMPLETED, resumed.status)
        assertEquals(revisions, repo.revisions(id).size)
        assertEquals(elements, repo.snapshot(id).elements.size)
        assertEquals(1, thesis.applies)

        // Stopped before anything was written: the result cannot be confirmed, the user approves again.
        val waiting = e.run(plan(request))
        val approved = e.decide(waiting, "apply", true)
        val cut = approved.copy(status = TaskStatus.RUNNING).withStep(approved.step("apply")!!.copy(status = StepStatus.RUNNING))
        store.save(cut)
        val unknown = engine().recover(cut)
        assertEquals(StepStatus.UNKNOWN, unknown.step("apply")!!.status)
        assertEquals(TaskStatus.WAITING_FOR_APPROVAL, engine().run(unknown).status)
    }

    @Test
    fun aChangeThatCannotBeConfirmed_isRolledBack() = runBlocking {
        importSource()
        val id = newThesis("Mémoire géologique")
        thesis.active = id
        val before = hashes(repo.snapshot(id))
        thesis.corruptNextApply = true
        val e = engine()
        val done = approve(e, e.run(plan("Extract the text of page 2 of Resources minerales.pdf and copy it into the Remerciements section of my thesis.")))
        assertEquals(TaskStatus.FAILED, done.status)
        val check = done.step("check")!!.output
        assertEquals("FAILED", check["outcome"])
        assertTrue(check["issues"], check.getValue("issues").contains("other_sections_changed"))
        assertNotNull(check["restored"]?.takeIf { it.isNotBlank() })
        assertEquals(before, hashes(repo.snapshot(id)))
    }
}
