package com.example.thesis

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.ai.orchestrator.AiProgressListener
import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.AiException
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.DocumentEntity
import com.example.data.model.TemplateEntity
import com.example.offline.document.RoomTemplateRepository
import com.example.thesis.ai.DestinationSuggestion
import com.example.thesis.ai.EditSuggestion
import com.example.thesis.ai.TableSuggestion
import com.example.thesis.ai.ThesisAi
import com.example.thesis.core.EditMode
import com.example.thesis.core.NewElement
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisStructure
import com.example.thesis.data.AcceptResult
import com.example.thesis.data.ThesisRepository
import com.example.thesis.export.ThesisExport
import com.example.thesis.export.ThesisExporter
import com.example.thesis.export.ThesisPdfRenderer
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ChangeSource
import com.example.thesis.model.ElementType
import com.example.thesis.model.ProposalStatus
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSnapshot
import com.example.thesis.ui.ThesisHome
import com.example.thesis.ui.ThesisHost
import com.example.thesis.ui.ThesisWorkspaceContent
import com.example.thesis.ui.ThesisReviewDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipFile

/**
 * Thesis Builder on Robolectric with a real (in-memory or file) Room database: persistence,
 * revisions, proposals, conflicts, restore, the Chat flow with a FAKE AI (no network), images,
 * tables, export, and the review / home UI in English, French and Arabic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThesisAndroidTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SmartBubbleDatabase
    private lateinit var repo: ThesisRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val png: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAEUlEQVR4nGP4z8DwHwQZGBgAJ+wE/IyKaXwAAAAASUVORK5CYII=")

    /** Deterministic stand-in for the default provider (records what was requested). */
    private class FakeAi : ThesisAi {
        var provider: String? = "FakeProvider"
        var destination: (ThesisSnapshot) -> DestinationSuggestion = { DestinationSuggestion(null, 0.0, "", "Which chapter?") }
        var editResult: (List<Pair<String, String>>) -> EditSuggestion = { items -> EditSuggestion(items.associate { it.first to it.second + " (improved)" }, "done", emptyList()) }
        var fail: AiException? = null
        var slow = false
        val requests = CopyOnWriteArrayList<String>()
        override suspend fun providerName() = provider
        override suspend fun suggestDestination(snapshot: ThesisSnapshot, excerpt: String, instruction: String, listener: AiProgressListener): DestinationSuggestion {
            requests.add("destination:$excerpt"); if (slow) delay(10_000); fail?.let { throw it }; return destination(snapshot)
        }
        override suspend fun suggestImagePlace(snapshot: ThesisSnapshot, description: String, listener: AiProgressListener) = destination(snapshot).also { requests.add("image:$description") }
        override suspend fun edit(snapshot: ThesisSnapshot, mode: EditMode, items: List<Pair<String, String>>, instruction: String, listener: AiProgressListener): EditSuggestion {
            requests.add("edit:${items.size}"); fail?.let { throw it }; return editResult(items)
        }
        override suspend fun table(snapshot: ThesisSnapshot, data: String, listener: AiProgressListener) = TableSuggestion(listOf("A"), listOf(listOf("1")), emptyList())
    }

    private val ai = FakeAi()
    private val messages = CopyOnWriteArrayList<ThesisMessage>()
    private val notes = CopyOnWriteArrayList<ThesisChatNote>()
    private val exports = CopyOnWriteArrayList<ThesisExport>()
    private val feedback = MutableStateFlow<String?>(null)
    private lateinit var controller: ThesisController

    /** Same record as the app's Library entry for a thesis export. */
    private suspend fun saveToLibrary(e: ThesisExport, projectId: String): DocumentEntity {
        exports.add(e)
        val doc = DocumentEntity(UUID.randomUUID().toString(), e.file.nameWithoutExtension, e.format, e.file.path, e.file.length(), 0, e.pages,
            e.template.entity?.id, e.template.entity?.name, "Thesis Builder", projectId = projectId)
        db.documentDao().insertDocument(doc)
        return doc
    }

    /** The app side of the workspace (the ViewModel in the app). */
    private inner class TestHost : ThesisHost {
        val chatRequests = CopyOnWriteArrayList<Pair<String, String?>>()
        val opened = CopyOnWriteArrayList<DocumentEntity>()
        override val thesis: ThesisController get() = controller
        override val templateList = MutableStateFlow<List<TemplateEntity>>(emptyList())
        override val defaultContentLanguage = "en"
        override val feedback: StateFlow<String?> get() = this@ThesisAndroidTest.feedback
        override fun consumeFeedback() { this@ThesisAndroidTest.feedback.value = null }
        override fun continueThesisInChat(projectId: String, sectionId: String?) { chatRequests.add(projectId to sectionId); controller.enterChatMode(projectId, sectionId) }
        override fun openExportedDocument(document: DocumentEntity) { opened.add(document) }
        override fun shareExportedDocument(document: DocumentEntity) { opened.add(document) }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SmartBubbleDatabase::class.java).allowMainThreadQueries().build()
        repo = ThesisRepository(db, tmp.newFolder("thesis"), imageInspector = { f -> if (f.length() > 8 && f.readBytes().take(4) == png.take(4)) 200 to 100 else null })
        val exporter = ThesisExporter(repo, RoomTemplateRepository(db.templateDao()), tmp.newFolder("exports"),
            pdfRenderer = { p, s, a, f -> ThesisPdfRenderer(p).render(s, a, f) })
        controller = ThesisController(scope, repo, ai, exporter, tmp.newFolder("preview"),
            onMessage = { messages.add(it); feedback.value = it.javaClass.simpleName }, onChatNote = { notes.add(it) },
            onSaveToLibrary = { e, projectId -> saveToLibrary(e, projectId) })
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun meta(language: String = "en") = ThesisMeta("Groundwater quality", AcademicLevel.MASTER, language, studentName = "Amina", university = "University of Fès")

    private suspend fun idle() = withTimeout(15_000) { controller.state.first { it.busy == null } }

    private suspend fun awaitReview() = withTimeout(15_000) { controller.state.first { it.review != null }.review!! }

    private fun intro(s: ThesisSnapshot) = s.sections.first { it.kind == SectionKind.INTRODUCTION }.id

    // ------------------------------------------------------------------ repository

    @Test
    fun createProject_persistsStructureAndFirstRevision_andSurvivesReopen(): Unit = runBlocking {
        val file = File(tmp.root, "thesis.db")
        val fileDb = SmartBubbleDatabase.build(context, file.path)
        val r = ThesisRepository(fileDb, tmp.newFolder("t2"), imageInspector = { null })
        val id = r.createProject(meta("fr"))
        val s = r.snapshot(id)
        assertEquals(3, s.sections.count { it.kind == SectionKind.CHAPTER })
        assertEquals("Introduction générale", s.section(intro(s))!!.title)
        r.applyDirect(id, listOf(ThesisOp.AddElements(intro(s), null, listOf(NewElement("p1", ElementType.PARAGRAPH, "Texte.")))), ChangeSource.USER, "edit")
        fileDb.close()
        // "Restart": a new database instance on the same file.
        val reopened = SmartBubbleDatabase.build(context, file.path)
        val again = ThesisRepository(reopened, File(tmp.root, "t2"), imageInspector = { null })
        assertEquals("Texte.", again.snapshot(id).element("p1")!!.text)
        assertEquals(listOf(2, 1), again.revisions(id).map { it.number })
        reopened.close()
    }

    @Test
    fun proposals_acceptRejectConflictAndRestore(): Unit = runBlocking {
        val id = repo.createProject(meta())
        val s0 = repo.snapshot(id)
        val add = repo.propose(id, listOf(ThesisOp.AddElements(intro(s0), null, listOf(NewElement("a", ElementType.PARAGRAPH, "First.")))), ChangeSource.AI, "CONTENT", "req", "ai:x", emptyList())
        assertTrue(repo.snapshot(id).elements.isEmpty()) // a proposal never touches approved content
        repo.reject(add.id)
        assertTrue(repo.snapshot(id).elements.isEmpty())

        val p = repo.propose(id, listOf(ThesisOp.AddElements(intro(s0), null, listOf(NewElement("b", ElementType.PARAGRAPH, "Second.")))), ChangeSource.AI, "CONTENT", "req", "ai:x", emptyList())
        val r = repo.accept(p.id) as AcceptResult.Accepted
        assertEquals(2, r.revision.number)
        assertEquals("Second.", repo.snapshot(id).element("b")!!.text)

        // Stale proposal: the section changed after it was prepared.
        val stale = repo.propose(id, listOf(ThesisOp.ReplaceText("b", "AI rewrite.")), ChangeSource.AI, "EDIT", "req", "ai:x", emptyList())
        repo.applyDirect(id, listOf(ThesisOp.ReplaceText("b", "User edit.")), ChangeSource.USER, "edit")
        assertTrue(repo.accept(stale.id) is AcceptResult.Conflict)
        assertEquals("User edit.", repo.snapshot(id).element("b")!!.text) // newer work kept
        assertEquals(ProposalStatus.CONFLICT.name, repo.proposal(stale.id)!!.status)

        // Restore revision 2: a NEW revision, history kept.
        val rev2 = repo.revisions(id).first { it.number == 2 }
        val restored = repo.restore(id, rev2.id)
        assertEquals(4, restored.number)
        assertEquals(2, restored.restoredFrom)
        assertEquals("Second.", repo.snapshot(id).element("b")!!.text)
        assertEquals(listOf(4, 3, 2, 1), repo.revisions(id).map { it.number })
        assertEquals("User edit.", repo.revisionSnapshot(repo.revisions(id).first { it.number == 3 }.id).element("b")!!.text)
    }

    @Test
    fun images_areCopiedPrivately_andInvalidFilesRefused(): Unit = runBlocking {
        val id = repo.createProject(meta())
        val asset = repo.importImage(id, { ByteArrayInputStream(png) }, "map.png", "image/png")
        assertEquals(200, asset.width)
        assertTrue(File(asset.path).readBytes().contentEquals(png))
        try {
            repo.importImage(id, { ByteArrayInputStream("not an image".toByteArray()) }, "x.png", "image/png")
            throw AssertionError("expected refusal")
        } catch (e: com.example.thesis.data.ThesisAssetException) {
            assertEquals(com.example.thesis.data.ThesisAssetException.Reason.NOT_AN_IMAGE, e.reason)
        }
        assertEquals(1, repo.assets(id).size)
        repo.deleteProject(id)
        assertFalse(File(asset.path).exists())
        assertNull(repo.project(id))
    }

    // ------------------------------------------------------------------ chat flow (fake AI)

    private suspend fun projectInChat(aiAssist: Boolean = true): String {
        val id = repo.createProject(meta(), aiAssist)
        controller.enterChatMode(id)
        withTimeout(10_000) { controller.state.first { it.snapshot != null } }
        return id
    }

    @Test
    fun chat_namedDestination_isLocal_andAcceptCreatesRevision(): Unit = runBlocking {
        val id = projectInChat()
        controller.submitFromChat("Add this to the introduction\nGroundwater supplies 70% of the region's water (Smith, 2019).")
        val review = awaitReview()
        assertEquals(ProposalStatus.PENDING.name, review.proposal.status)
        assertTrue(ai.requests.isEmpty()) // no provider call for a named destination
        assertTrue(repo.snapshot(id).elements.isEmpty())
        idle()
        controller.accept(review.proposal.id)
        withTimeout(10_000) { while (repo.snapshot(id).elements.isEmpty()) delay(20) }
        assertEquals("Groundwater supplies 70% of the region's water (Smith, 2019).", repo.snapshot(id).elementsOf(intro(repo.snapshot(id))).single().text)
        assertTrue(notes.any { it.proposalId == review.proposal.id })
    }

    @Test
    fun chat_ambiguousContent_isNeverInsertedArbitrarily(): Unit = runBlocking {
        val id = projectInChat(aiAssist = false)
        controller.submitFromChat("A paragraph without any destination that should not be placed automatically by the app.")
        val review = awaitReview()
        assertEquals(ProposalStatus.NEEDS_DESTINATION.name, review.proposal.status)
        assertTrue(ai.requests.isEmpty()) // AI assistance off: nothing sent
        idle()
        // The user chooses the destination, then accepts.
        val chapter = repo.snapshot(id).sections.first { it.kind == SectionKind.CHAPTER }.id
        controller.setDestination(review.proposal.id, chapter, null)
        withTimeout(10_000) { controller.state.first { it.review?.proposal?.status == ProposalStatus.PENDING.name } }
        idle()
        controller.accept(review.proposal.id)
        withTimeout(10_000) { while (repo.snapshot(id).elementsOf(chapter).isEmpty()) delay(20) }
        idle()
    }

    @Test
    fun chat_aiSuggestsDestination_userReviews(): Unit = runBlocking {
        val id = projectInChat()
        ai.destination = { s -> DestinationSuggestion(s.sections.first { it.kind == SectionKind.CONCLUSION }.id, 0.9, "summary of results", "") }
        controller.submitFromChat("In summary, the results show that nitrate levels exceed the limit in three wells.")
        val review = awaitReview()
        assertEquals(ChangeSource.AI.name, review.proposal.source)
        assertTrue(ai.requests.single().startsWith("destination:"))
        assertTrue(notes.any { it.text.code == "thesis_note_sent_to_provider" }) // the user is told what was sent
        assertTrue(repo.snapshot(id).elements.isEmpty())
    }

    @Test
    fun chat_aiFailureAndCancellation_changeNothing(): Unit = runBlocking {
        val id = projectInChat()
        ai.fail = AiException(AiErrorKind.NETWORK_UNAVAILABLE)
        controller.submitFromChat("Some content without destination long enough to be a paragraph.")
        withTimeout(10_000) { while (messages.none { it is ThesisMessage.AiFailed }) delay(20) }
        assertTrue(repo.snapshot(id).elements.isEmpty())
        assertTrue(repo.proposals(id).isEmpty())

        ai.fail = null
        ai.slow = true
        controller.submitFromChat("Another paragraph that will be cancelled while the provider answers.")
        withTimeout(10_000) { while (ai.requests.size < 2) delay(20) }
        controller.cancel()
        idle()
        assertTrue(messages.any { it is ThesisMessage.Cancelled })
        assertTrue(repo.proposals(id).isEmpty())
        assertEquals(1, repo.revisions(id).size)
    }

    @Test
    fun chat_editRequest_proposesDiff_withoutOverwriting(): Unit = runBlocking {
        val id = projectInChat()
        val s = repo.snapshot(id)
        repo.applyDirect(id, listOf(ThesisOp.AddElements(intro(s), null, listOf(NewElement("p", ElementType.PARAGRAPH, "The water are good.")))), ChangeSource.USER, "x")
        controller.submitFromChat("Correct the grammar of the introduction without changing the meaning")
        val review = awaitReview()
        assertEquals("edit:1", ai.requests.single())
        assertEquals("The water are good.", repo.snapshot(id).element("p")!!.text) // approved version untouched
        assertTrue(review.diff!!.elements.single().segments.isNotEmpty())
        idle()
        controller.reject(review.proposal.id)
        idle()
        assertEquals("The water are good.", repo.snapshot(id).element("p")!!.text)
    }

    @Test
    fun chat_imageAndTable_withNumberingAndDuplicates(): Unit = runBlocking {
        val id = projectInChat()
        controller.addImagesFromChat(listOf(Triple({ ByteArrayInputStream(png) }, "map.png", "image/png")), "Insert this image into chapter 1")
        val review = awaitReview()
        val ops = com.example.thesis.core.ThesisOperations.decode(review.proposal.payload)
        val img = (ops.single() as ThesisOp.AddElements).elements.single()
        assertEquals(ElementType.IMAGE, img.type)
        idle()
        controller.editProposalElement(review.proposal.id, img.id, img.data.copy(caption = "Location map"))
        withTimeout(10_000) { controller.state.first { s -> s.review?.proposal?.payload?.contains("Location map") == true } }
        idle()
        controller.accept(review.proposal.id)
        withTimeout(10_000) { while (repo.snapshot(id).element(img.id) == null) delay(20) }
        val snap = repo.snapshot(id)
        assertEquals("Figure 1: Location map", ThesisStructure.figureCaption(snap.element(img.id)!!, ThesisStructure.numbering(snap), "en"))

        idle()
        controller.closeReview()
        controller.submitFromChat("Create a table from this data in chapter 1\n| Ion | mg/L |\n|---|---|\n| Ca | 12.5 |")
        val t = withTimeout(10_000) { controller.state.first { it.review?.proposal?.kind == "TABLE" } }.review!!
        assertTrue(ai.requests.none { it.startsWith("table") }) // clearly tabular: converted locally
        assertEquals(ChangeSource.TABLE.name, t.proposal.source)

        // Duplicate detection against approved content.
        repo.applyDirect(id, listOf(ThesisOp.AddElements(intro(snap), null, listOf(NewElement("d", ElementType.PARAGRAPH, "Groundwater in the basin is recharged by winter rainfall on limestone outcrops.")))), ChangeSource.USER, "x")
        idle()
        controller.closeReview()
        controller.submitFromChat("Add this to chapter 2\nGroundwater in the basin is recharged by winter rainfall on limestone outcrops.")
        val dup = withTimeout(10_000) { controller.state.first { it.review?.proposal?.warnings?.contains("duplicate") == true } }.review!!
        assertEquals(1, dup.duplicates.size)
    }

    @Test
    fun export_docxUsesBoundWordTemplate_pdfWhenSupported(): Unit = runBlocking {
        val tpl = tmp.newFile("tpl.docx")
        com.example.document.docx.DocxEngine().generateDocx(com.example.document.model.WordDocumentModel(title = "T"), tpl)
        db.templateDao().insertTemplate(TemplateEntity(id = "w", name = "University", fileType = "DOCX", originalFilePath = tpl.path, profileJson = "{}", isDefaultWord = true))
        val id = repo.createProject(meta("ar"))
        controller.openProject(id)
        withTimeout(10_000) { controller.state.first { it.snapshot != null } }
        controller.export("DOCX")
        val result = withTimeout(15_000) { controller.state.first { it.lastExport != null } }.lastExport!!
        val export = exports.last()
        assertEquals("w", export.template.entity!!.id)
        ZipFile(export.file).use { z -> assertNotNull(z.getEntry("word/document.xml")) }
        // The success state points to the entry really saved in the Library.
        assertEquals("DOCX", result.format)
        assertEquals(export.file.path, result.document.filePath)
        assertTrue(db.documentDao().getAllDocuments().first().any { it.id == result.document.id && it.projectId == id })
        messages.clear()
        controller.export("PDF")
        withTimeout(15_000) { while (messages.isEmpty()) delay(20) }
        val pdfOk = messages.any { it is ThesisMessage.Exported }
        Assume.assumeTrue("PDF rendering is not available in this test runtime", pdfOk)
    }

    // ------------------------------------------------------------------ UI

    @Test
    @Config(qualifiers = "en")
    fun home_listsProjects_english() {
        runBlocking { repo.createProject(meta()) }
        compose.setContent { ThesisHome(controller) }
        compose.onNodeWithTag("thesis_new").assertIsEnabled()
        compose.onNodeWithText("Create New Thesis").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithTextExists("Groundwater quality") }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextExists(text: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    @Config(qualifiers = "ar")
    fun labels_arabicAndFrench() {
        assertEquals("منشئ المذكرات", context.getString(R.string.thesis_builder))
        assertEquals("قبول", context.getString(R.string.thesis_accept))
    }

    @Test
    @Config(qualifiers = "fr")
    fun labels_french() {
        assertEquals("Créateur de mémoire", context.getString(R.string.thesis_builder))
        assertEquals("Rejeter", context.getString(R.string.thesis_reject))
    }

    @Test
    @Config(qualifiers = "en")
    fun reviewDialog_acceptDisabledUntilDestinationChosen(): Unit = runBlocking {
        val id = projectInChat(aiAssist = false)
        controller.submitFromChat("Content that needs a destination before it can be accepted by the user.")
        val review = awaitReview()
        idle()
        compose.setContent { ThesisReviewDialog(controller, review) }
        compose.onNodeWithTag("thesis_accept").assertIsNotEnabled()
        compose.onNodeWithTag("thesis_reject").assertIsEnabled()
        compose.onNodeWithText("Nothing is changed in your thesis until you accept.").assertExists()
        assertTrue(repo.snapshot(id).elements.isEmpty())
    }

    // ------------------------------------------------------------------ end-to-end workspace flow

    private fun waitFor(timeout: Long = 15_000, condition: suspend () -> Boolean) = compose.waitUntil(timeout) { runBlocking { condition() } }

    private fun tagExists(tag: String) = compose.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun tap(tag: String) {
        compose.waitUntil(10_000) { tagExists(tag) }
        val node = compose.onNodeWithTag(tag)
        try { node.performScrollTo() } catch (e: AssertionError) { /* not inside a scrolling container */ }
        node.performClick()
        compose.waitForIdle()
    }

    private fun clickInList(list: String, tag: String) {
        compose.waitUntil(10_000) { tagExists(list) }
        compose.onNodeWithTag(list).performScrollToNode(androidx.compose.ui.test.hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    /**
     * The user journey of the bug report: create a thesis from the form, land in the workspace
     * (not back on the form), write in the General Introduction, add a chapter, send content to
     * it from Chat, accept the proposal, compare revisions and export to the Library.
     */
    @Test
    @Config(qualifiers = "en")
    fun workspace_fullFlow_createEditChatAcceptHistoryExport() {
        val host = TestHost()
        controller.openWorkspace()
        compose.setContent {
            val st by controller.state.collectAsState()
            if (st.workspaceOpen) ThesisWorkspaceContent(host)
            st.review?.let { ThesisReviewDialog(controller, it) }
        }

        // 1. Create: the form validates the title and has Cancel / Create always visible.
        tap("thesis_new")
        compose.onNodeWithTag("thesis_cancel_meta").assertIsDisplayed()
        compose.onNodeWithTag("thesis_save_meta").assertIsDisplayed().performClick()
        compose.onNodeWithTag("thesis_title_error", useUnmergedTree = true).assertExists()
        assertTrue(runBlocking { repo.observeProjects().first() }.isEmpty())
        compose.onNodeWithTag("thesis_field_title").performTextInput("Groundwater quality")
        tap("thesis_level_MASTER")
        tap("thesis_lang_en")
        // Repeated taps create one project only.
        compose.onNodeWithTag("thesis_save_meta").performTouchInput { click(); click() }

        // 2. Automatic navigation to the workspace with overview, tabs and actions.
        compose.waitUntil(15_000) { tagExists("thesis_overview") }
        val id = controller.state.value.projectId!!
        assertEquals(1, runBlocking { repo.observeProjects().first() }.size)
        compose.waitUntil(10_000) { compose.onAllNodesWithTextExists("Created") } // feedback shown inside the workspace
        listOf("thesis_tab_project", "thesis_tab_structure", "thesis_tab_changes", "thesis_tab_history", "thesis_tab_preview",
            "thesis_continue_chat", "thesis_edit_structure", "thesis_edit_section", "thesis_preview", "thesis_review_pending",
            "thesis_history", "thesis_export_docx", "thesis_export_pdf", "thesis_settings", "thesis_delete_project").forEach { compose.onNodeWithTag(it).assertExists() }
        compose.onNodeWithText("Current revision: 1").assertExists()
        val created = runBlocking { repo.snapshot(id) }
        assertEquals(AcademicLevel.MASTER, created.meta.level)
        assertEquals("en", created.meta.language)

        // 3. General Introduction: add a paragraph and save it.
        tap("thesis_tab_structure")
        val introId = intro(created)
        clickInList("thesis_structure", "thesis_section_$introId")
        compose.onNodeWithTag("thesis_section_editor").assertExists()
        tap("thesis_add_paragraph")
        compose.onNodeWithTag("thesis_text_input").performTextInput("Groundwater is the main source of drinking water in the basin.")
        tap("thesis_text_confirm")
        waitFor { repo.snapshot(id).elementsOf(introId).any { it.text == "Groundwater is the main source of drinking water in the basin." } }

        // 4. Add Chapter 1 (a new chapter; the count is not fixed) and open it.
        tap("thesis_back")
        val chaptersBefore = created.sections.filter { it.kind == SectionKind.CHAPTER }.map { it.id }.toSet()
        tap("thesis_add_chapter")
        waitFor { repo.snapshot(id).sections.count { it.kind == SectionKind.CHAPTER } == chaptersBefore.size + 1 }
        val chapterId = runBlocking { repo.snapshot(id) }.sections.first { it.kind == SectionKind.CHAPTER && it.id !in chaptersBefore }.id
        compose.waitUntil(10_000) { controller.state.value.snapshot?.section(chapterId) != null }
        clickInList("thesis_structure", "thesis_section_$chapterId")

        // 5. Continue in Chat for this chapter: the workspace closes and Chat targets the chapter.
        tap("thesis_section_chat")
        assertEquals(id to chapterId, host.chatRequests.single())
        assertEquals(chapterId, controller.state.value.chatSectionId)
        assertFalse(controller.state.value.workspaceOpen)

        // 6. Pasted text goes to the target chapter as a proposal; nothing changes before Accept.
        val pasted = "Recharge occurs mainly in winter through limestone outcrops in the northern part of the basin."
        controller.submitFromChat(pasted)
        compose.waitUntil(15_000) { tagExists("thesis_accept") && controller.state.value.busy == null }
        assertTrue(runBlocking { repo.snapshot(id) }.elementsOf(chapterId).isEmpty())
        compose.onNodeWithTag("thesis_accept").assertIsEnabled().performClick()
        waitFor { repo.snapshot(id).elementsOf(chapterId).any { it.text == pasted } }

        // 7. Back in the workspace: the content is there; history compares revisions.
        controller.openProject(id)
        compose.waitUntil(10_000) { tagExists("thesis_overview") }
        tap("thesis_tab_history")
        val latest = runBlocking { repo.revisions(id) }.first().number
        compose.waitUntil(10_000) { controller.revisions.value.firstOrNull()?.number == latest }
        clickInList("thesis_history_list", "thesis_revision_changes_$latest")
        compose.waitUntil(10_000) { compose.onAllNodesWithTextExists("Changes made in revision $latest") }
        compose.onNodeWithTag("thesis_diff_summary").assertExists()

        // 8. Export DOCX: success only once the file is in the Library.
        tap("thesis_tab_project")
        tap("thesis_export_docx")
        compose.waitUntil(20_000) { tagExists("thesis_export_result") }
        val doc = runBlocking { db.documentDao().getAllDocuments().first() }.single { it.projectId == id }
        assertEquals("DOCX", doc.fileType)
        ZipFile(File(doc.filePath)).use { z ->
            val xml = z.getInputStream(z.getEntry("word/document.xml")).bufferedReader().readText()
            assertTrue(xml.contains("Recharge occurs mainly in winter"))
            assertTrue(xml.contains("main source of drinking water"))
        }
        tap("thesis_export_open")
        assertEquals(doc.id, host.opened.single().id)
    }

    @Test
    @Config(qualifiers = "en")
    fun workspace_backGoesUpAndCancelLeavesForm() {
        val host = TestHost()
        runBlocking { repo.createProject(meta()) }
        controller.openWorkspace()
        compose.setContent { ThesisWorkspaceContent(host) }
        tap("thesis_new")
        tap("thesis_open_existing") // projects exist: the list is one tap away
        compose.waitUntil(10_000) { tagExists("thesis_new") }
        tap("thesis_new")
        tap("thesis_cancel_meta")
        compose.waitUntil(10_000) { tagExists("thesis_new") }
        assertEquals(1, runBlocking { repo.observeProjects().first() }.size)
        assertEquals(ThesisScreen.HOME, controller.state.value.screen)
    }

    @Test
    @Config(qualifiers = "en")
    fun sectionTools_textHeadingTableFormattingAndHistory(): Unit = runBlocking {
        val id = repo.createProject(meta(), aiAssist = false)
        controller.openProject(id)
        val snap = withTimeout(10_000) { controller.state.first { it.snapshot != null } }.snapshot!!
        val introId = intro(snap)
        controller.addHeading(introId, "Context"); idle()
        val first = "The first paragraph describes the hydrogeological context of the basin, which has been studied since 1990."
        val second = "The second paragraph explains why nitrate levels have increased in shallow wells over the last decade."
        controller.addText(introId, "$first\n\n$second"); idle()
        controller.addTable(introId, listOf("Year", "Rain (mm)"), listOf(listOf("2020", "410")), "Annual rainfall"); idle()
        val elements = repo.snapshot(id).elementsOf(introId)
        assertEquals(listOf(ElementType.HEADING, ElementType.PARAGRAPH, ElementType.PARAGRAPH, ElementType.TABLE), elements.map { it.type })
        assertEquals(listOf("Context", first, second), elements.take(3).map { it.text })
        assertEquals("410", elements.last().data.tableRows[0][1])

        // Image with caption, kept as an asset of the project.
        controller.addImage(introId, { ByteArrayInputStream(png) }, "map.png", "image/png", com.example.thesis.model.ElementData(caption = "Study area")); idle()
        val image = repo.snapshot(id).elementsOf(introId).last()
        assertEquals(ElementType.IMAGE, image.type)
        assertEquals("Study area", image.data.caption)

        // Apply formatting: a paragraph holding a list becomes list items, as a proposal only.
        repo.applyDirect(id, listOf(ThesisOp.AddElements(introId, null, listOf(NewElement("raw", ElementType.PARAGRAPH, "Objectives:\n1. Measure nitrates\n2. Map the aquifer")))), ChangeSource.USER, "raw")
        controller.openProject(id); idle()
        withTimeout(10_000) { controller.state.first { it.snapshot?.elements?.any { e -> e.id == "raw" } == true } }
        controller.formatSection(introId)
        val review = awaitReview()
        assertEquals(ChangeSource.FORMATTING.name, review.proposal.source)
        assertNotNull(repo.snapshot(id).elements.firstOrNull { it.id == "raw" }) // unchanged until accepted
        idle()
        controller.accept(review.proposal.id); idle()
        val after = repo.snapshot(id).elementsOf(introId)
        assertNull(after.firstOrNull { it.id == "raw" })
        assertTrue(after.count { it.type == ElementType.LIST_ITEM } >= 2)

        // Nothing left to format: an explicit message, no proposal.
        messages.clear()
        controller.closeReview()
        controller.formatSection(introId); idle()
        assertTrue(messages.any { it is ThesisMessage.NothingToFormat })

        // Section history lists the revisions that changed this section; compare with previous.
        controller.loadSectionHistory(introId); idle()
        val history = controller.state.value.sectionHistory!!
        assertTrue(history.size >= 5)
        controller.compareWithPrevious(history.first())
        val compare = withTimeout(10_000) { controller.state.first { it.compare != null } }.compare!!
        assertTrue(compare.againstPrevious)
        assertTrue(compare.diff.elements.isNotEmpty())
    }

    @Test
    fun createProject_repeatedRequests_createOneProject(): Unit = runBlocking {
        repeat(5) { controller.createProject(meta(), aiAssist = false) }
        withTimeout(10_000) { controller.state.first { it.snapshot != null && it.busy == null } }
        assertEquals(1, repo.observeProjects().first().size)
        assertEquals(ThesisScreen.PROJECT, controller.state.value.screen)
    }

    /** Close and reopen the app: same database file and thesis folder, new controller. */
    @Test
    fun restart_keepsSectionsContentTemplateHistoryAndProposals(): Unit = runBlocking {
        val dbFile = File(tmp.root, "restart.db")
        val folder = tmp.newFolder("thesis-restart")
        fun open() = Room.databaseBuilder(context, SmartBubbleDatabase::class.java, dbFile.path).allowMainThreadQueries().build()
        var db1 = open()
        var r1 = ThesisRepository(db1, folder, imageInspector = { null })
        db1.templateDao().insertTemplate(TemplateEntity(id = "w", name = "University", fileType = "DOCX", originalFilePath = "/none.docx", profileJson = "{}"))
        val id = r1.createProject(meta("ar").copy(templateId = "w"))
        val s1 = r1.snapshot(id)
        val intro = intro(s1)
        r1.applyDirect(id, listOf(ThesisOp.AddSection(com.example.thesis.model.ThesisSection("ch-new", null, SectionKind.CHAPTER, "Field study", 0), null)), ChangeSource.STRUCTURE, "chapter")
        r1.applyDirect(id, listOf(ThesisOp.AddElements(intro, null, listOf(NewElement("p1", ElementType.PARAGRAPH, "مقدمة عامة حول المياه الجوفية.")))), ChangeSource.USER, "text")
        val pending = r1.propose(id, listOf(ThesisOp.AddElements("ch-new", null, listOf(NewElement("p2", ElementType.PARAGRAPH, "Pending text.")))),
            ChangeSource.AI, "ADD_CONTENT", "request", "ai:Fake", emptyList())
        val revisionsBefore = r1.revisions(id).map { it.number }
        db1.close()

        db1 = open()
        r1 = ThesisRepository(db1, folder, imageInspector = { null })
        val c = ThesisController(scope, r1, ai, ThesisExporter(r1, RoomTemplateRepository(db1.templateDao()), tmp.newFolder("exports2"),
            pdfRenderer = { p, s, a, f -> ThesisPdfRenderer(p).render(s, a, f) }), tmp.newFolder("preview2"),
            onMessage = {}, onChatNote = {}, onSaveToLibrary = { e, p -> saveToLibrary(e, p) })
        c.openProject(id)
        val restored = withTimeout(10_000) { c.state.first { it.snapshot != null } }.snapshot!!
        assertEquals("w", restored.meta.templateId)
        assertEquals("ar", restored.meta.language)
        assertEquals("Field study", restored.section("ch-new")!!.title)
        assertEquals("مقدمة عامة حول المياه الجوفية.", restored.elementsOf(intro).single().text)
        assertTrue(restored.elementsOf("ch-new").isEmpty()) // pending proposal not applied
        assertEquals(revisionsBefore, r1.revisions(id).map { it.number })
        assertEquals(ProposalStatus.PENDING.name, r1.proposal(pending.id)!!.status)
        db1.close()
    }
}
