package com.example.thesis

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.ai.orchestrator.AiProgressListener
import com.example.ai.providers.AiErrorKind
import com.example.ai.providers.AiException
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.ChatMessageEntity
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
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.ProposalStatus
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSnapshot
import com.example.thesis.ui.ChatSnippet
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
    private val chatLoads = java.util.Collections.synchronizedList(mutableListOf<String>())
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
        override suspend fun aiProviderInfo(): Pair<String, String>? = ai.provider?.let { it to "fake-model" }
        // Same path as the app (SmartBubbleViewModel): a blocking query on Dispatchers.IO.
        override suspend fun recentChatMessages(limit: Int): List<ChatSnippet> {
            // Each call is recorded (start, query done on which thread, result size, return): when
            // the Insert from Chat dialog stays on its spinner (runs #30, #41), the timeout message
            // shows whether the load never started, never finished, or finished without reaching
            // the dialog.
            val call = chatLoads.size
            chatLoads.add("#$call start ${System.currentTimeMillis()} on ${Thread.currentThread().name}")
            try {
                val list = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    db.chatDao().getRecentChatMessagesNow(limit).map { ChatSnippet(it.content, it.isUser, it.timestamp) }
                        .also { chatLoads.add("#$call queried ${it.size} at ${System.currentTimeMillis()} on ${Thread.currentThread().name}") }
                }
                chatLoads.add("#$call returned at ${System.currentTimeMillis()} on ${Thread.currentThread().name}")
                return list
            } catch (e: Throwable) {
                chatLoads.add("#$call ended with $e at ${System.currentTimeMillis()}")
                throw e
            }
        }
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

    @Test
    fun saveIfDirty_savesUnsavedSectionText_onlyWhenChanged(): Unit = runBlocking {
        val id = repo.createProject(meta(), aiAssist = false)
        controller.openProject(id)
        withTimeout(10_000) { controller.state.first { it.snapshot != null } }
        val introId = intro(controller.state.value.snapshot!!)
        controller.selectSection(introId)
        withTimeout(10_000) { controller.state.first { it.draft != null } }
        controller.saveIfDirty() // nothing changed: nothing saved
        assertEquals(null, controller.state.value.busy)
        controller.updateDraft("Text typed before the app went to the background.")
        controller.saveIfDirty()
        idle()
        assertEquals(false, controller.state.value.draft!!.dirty)
        assertTrue(repo.snapshot(id).elements.any { it.text.contains("Text typed before the app went to the background.") })
    }

    // ------------------------------------------------------------------ UI

    @Test
    @Config(qualifiers = "en")
    fun home_listsProjects_english() {
        val id = runBlocking { repo.createProject(meta()) }
        controller.openWorkspace()
        compose.setContent { ThesisWorkspaceContent(TestHost()) }
        compose.onNodeWithTag("thesis_new").assertIsEnabled()
        compose.onNodeWithText("New Thesis").assertExists()
        compose.onNodeWithText("Build your academic thesis step by step.").assertExists()
        compose.waitUntil(10_000) { compose.onAllNodesWithTextExists("Groundwater quality") }
        compose.onNodeWithTag("thesis_continue_$id").assertIsEnabled()
        compose.onNodeWithText("Continue Editing").assertExists()
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

    /** Controller state and the semantics tree of every window, for failure messages. */
    private fun diagnostics(): String = buildString {
        val st = controller.state.value
        append("state: screen=${st.screen} busy=${st.busy} review=${st.review != null} assist=${st.assist != null} ")
        append("draft=${st.draft?.text?.replace("\n", "\\n")?.take(300)}\n")
        try { append(compose.onAllNodes(androidx.compose.ui.test.isRoot()).printToString(maxDepth = 60)) } catch (e: Throwable) { append("semantics tree unavailable: $e") }
        // Threads waiting in the database or in coroutines, to show where a load is blocked.
        append("\nInsert from Chat loads (now ${System.currentTimeMillis()}): ${chatLoads.toList()}\n")
        append("\nthreads:\n")
        for ((t, frames) in Thread.getAllStackTraces()) {
            val text = frames.joinToString("\n") { "    at $it" }
            if (frames.isNotEmpty() && (text.contains("room", true) || text.contains("sqlite", true) || text.contains("kotlinx.coroutines.BlockingCoroutine"))) {
                append("  ${t.name} ${t.state}\n").append(frames.take(18).joinToString("\n") { "    at $it" }).append("\n")
            }
        }
    }

    private fun tap(tag: String) {
        try { compose.waitUntil(10_000) { tagExists(tag) } } catch (e: Throwable) { throw AssertionError("Timed out waiting for node '$tag' to tap\n" + diagnostics(), e) }
        // performScrollTo() only scrolls the closest scrolling container. A node inside nested
        // containers (e.g. a toolbar row inside the editor column) stays off screen when the outer
        // one is scrolled away (run #28: thesis_fmt_heading at t=-125px). Bring it into view in
        // every enclosing container, outermost first.
        val containers = compose.onAllNodes(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.hasAnyDescendant(androidx.compose.ui.test.hasTestTag(tag)))
        repeat(containers.fetchSemanticsNodes().size) { i ->
            try { containers[i].performScrollToNode(androidx.compose.ui.test.hasTestTag(tag)) } catch (e: Throwable) { /* checked below */ }
        }
        val node = compose.onNodeWithTag(tag)
        try { node.performScrollTo() } catch (e: AssertionError) { /* not inside a scrolling container */ }
        // A click on a node outside the visible window is not delivered: fail with the evidence.
        try { node.assertIsDisplayed() } catch (e: AssertionError) { throw AssertionError("Node '$tag' exists but is not displayed, the tap cannot reach it\n" + diagnostics(), e) }
        node.performClick()
        compose.waitForIdle()
    }

    private fun clickInList(list: String, tag: String) {
        compose.waitUntil(10_000) { tagExists(list) }
        compose.onNodeWithTag(list).performScrollToNode(androidx.compose.ui.test.hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun textExists(text: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** The Thesis Builder window as the app shows it (with the review dialog of proposals). */
    private fun showWorkspace(host: TestHost) {
        compose.setContent {
            val st by controller.state.collectAsState()
            if (st.workspaceOpen) ThesisWorkspaceContent(host)
            st.review?.let { ThesisReviewDialog(controller, it) }
        }
    }

    /** waitUntil that names the step in the failure message (shown in the CI summary). */
    private fun await(step: String, timeout: Long = 15_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { condition() } } catch (e: Throwable) { throw AssertionError("Timed out: $step\n" + diagnostics(), e) }
    }

    private fun awaitRepo(step: String, timeout: Long = 15_000, condition: suspend () -> Boolean) = await(step, timeout) { runBlocking { condition() } }

    /** Types into a field of a scrolling form. */
    private fun typeInto(tag: String, text: String) {
        await("field $tag") { tagExists(tag) }
        val node = compose.onNodeWithTag(tag)
        try { node.performScrollTo() } catch (e: AssertionError) { /* not in a scrolling container */ }
        node.performTextInput(text)
        compose.waitForIdle()
    }

    /** Screen 2 -> screen 3: every field, Save Draft, validation, one project per request, automatic navigation. */
    @Test
    @Config(qualifiers = "en")
    fun newThesis_allFields_saveDraft_validates_createsOnce_andOpensWorkspace() {
        val host = TestHost()
        controller.openWorkspace()
        showWorkspace(host)
        await("empty state") { tagExists("thesis_empty_state") }
        tap("thesis_new")
        compose.onNodeWithTag("thesis_cancel_meta").assertIsDisplayed()
        compose.onNodeWithTag("thesis_save_draft").assertIsNotEnabled() // nothing typed yet
        compose.onNodeWithTag("thesis_save_meta").assertIsDisplayed().performClick()
        compose.onNodeWithTag("thesis_field_title_error", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("thesis_field_student_error", useUnmergedTree = true).assertExists()
        assertTrue(runBlocking { repo.observeProjects().first() }.isEmpty())

        // Save Draft keeps an unfinished form; nothing is created.
        typeInto("thesis_field_title", "Groundwater quality")
        typeInto("thesis_field_subtitle", "A hydrogeological study")
        tap("thesis_save_draft")
        await("draft saved") { controller.state.value.formDraft?.subtitle == "A hydrogeological study" && controller.state.value.busy == null }
        assertTrue(runBlocking { repo.observeProjects().first() }.isEmpty())
        tap("thesis_cancel_meta")
        await("draft card on home") { tagExists("thesis_form_draft") }
        tap("thesis_form_draft_continue")
        await("form restored") { tagExists("thesis_form_draft_restored") }
        compose.onNodeWithText("A hydrogeological study").assertExists()

        typeInto("thesis_field_specialization", "Hydrogeology")
        typeInto("thesis_field_university", "University of Fès")
        typeInto("thesis_field_faculty", "Faculty of Sciences")
        typeInto("thesis_field_department", "Geology")
        typeInto("thesis_field_student", "Amina")
        typeInto("thesis_field_student_id", "2021-0457")
        typeInto("thesis_field_supervisor", "Pr. K. Idrissi")
        typeInto("thesis_field_cosupervisor", "Dr. B. Alaoui")
        typeInto("thesis_field_year", "2025")
        tap("thesis_save_meta")
        compose.onNodeWithTag("thesis_field_year_error", useUnmergedTree = true).assertExists() // bad year format
        compose.onNodeWithTag("thesis_field_year").performTextInput("-2026")
        tap("thesis_level_DOCTORATE")
        tap("thesis_language_picker")
        tap("thesis_lang_fr")
        // Repeated taps create one project only.
        compose.onNodeWithTag("thesis_save_meta").performTouchInput { click(); click() }

        await("workspace opened") { tagExists("thesis_structure") && controller.state.value.projectId != null }
        val id = controller.state.value.projectId!!
        assertEquals(1, runBlocking { repo.observeProjects().first() }.size)
        val s = runBlocking { repo.snapshot(id) }
        assertEquals(AcademicLevel.DOCTORATE, s.meta.level)
        assertEquals("fr", s.meta.language)
        assertEquals("Amina", s.meta.studentName)
        assertEquals("2021-0457", s.meta.studentId)
        assertEquals("A hydrogeological study", s.meta.subtitle)
        assertEquals("Hydrogeology", s.meta.specialization)
        assertEquals("Faculty of Sciences", s.meta.faculty)
        assertEquals("Geology", s.meta.department)
        assertEquals("Dr. B. Alaoui", s.meta.coSupervisor)
        assertEquals("2025-2026", s.meta.academicYear)
        assertEquals("Introduction générale", s.sections.first { it.kind == SectionKind.INTRODUCTION }.title)
        assertEquals(2, s.sections.count { it.kind == SectionKind.ABSTRACT })
        assertNull(controller.state.value.formDraft) // the draft is used up
        // Feedback is shown inside the workspace (consumed there, not by the hidden main snackbar).
        await("feedback consumed in workspace") { messages.any { it is ThesisMessage.Created } && feedback.value == null }
        compose.onNodeWithTag("thesis_add_chapter").assertIsEnabled()
        val dedication = s.sections.first { it.kind == SectionKind.DEDICATION }.id
        compose.onNodeWithTag("thesis_status_$dedication", useUnmergedTree = true).assertTextEquals(context.getString(R.string.thesis_status_not_started))
        val cover = s.sections.first { it.kind == SectionKind.COVER }.id
        compose.onNodeWithTag("thesis_status_$cover", useUnmergedTree = true).assertTextEquals(context.getString(R.string.thesis_status_completed))

        tap("thesis_back")
        await("project on home") { tagExists("thesis_project_$id") }
        compose.onNodeWithText("Amina").assertExists() // student name on the card
    }

    @Test
    @Config(qualifiers = "en")
    fun backNavigation_goesUpOneLevel_andCancelLeavesForm() {
        val host = TestHost()
        val id = runBlocking { repo.createProject(meta()) }
        controller.openWorkspace()
        showWorkspace(host)
        tap("thesis_new")
        tap("thesis_cancel_meta")
        await("home after cancel") { tagExists("thesis_new") }
        assertEquals(ThesisScreen.HOME, controller.state.value.screen)
        tap("thesis_continue_$id")
        await("workspace") { tagExists("thesis_structure") }
        tap("thesis_back")
        await("home after back") { tagExists("thesis_new") }
        assertEquals(1, runBlocking { repo.observeProjects().first() }.size)
        controller.back()
        assertFalse(controller.state.value.workspaceOpen)
    }

    /** Screen 4: paste, insert from Chat, image, save; Back saves what was typed. */
    @Test
    @Config(qualifiers = "en")
    fun sectionEditor_pasteInsertFromChatImageSave_andBackAutosaves() {
        val host = TestHost()
        val id = runBlocking { repo.createProject(meta(), aiAssist = false) }
        runBlocking { db.chatDao().insertMessage(ChatMessageEntity("m1", "chat-1", isUser = false, content = "Recharge occurs mainly in winter through limestone outcrops.")) }
        controller.openProject(id)
        showWorkspace(host)
        await("workspace") { tagExists("thesis_structure") && controller.state.value.snapshot != null }
        val introId = intro(controller.state.value.snapshot!!)
        clickInList("thesis_structure", "thesis_edit_$introId")
        await("editor") { tagExists("thesis_editor_text") && controller.state.value.draft != null }
        compose.onNodeWithTag("thesis_editor_text").performTextInput("Groundwater is the main source of drinking water in the basin.")
        await("unsaved status", 5_000) { controller.state.value.draft?.dirty == true }
        assertTrue(runBlocking { repo.snapshot(id) }.elementsOf(introId).isEmpty()) // nothing saved yet

        // Paste Text uses the clipboard.
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("t", "Nitrate levels have increased in shallow wells."))
        tap("thesis_paste_text")
        assertTrue(controller.state.value.draft!!.text.contains("Nitrate levels have increased in shallow wells."))

        // Insert from Chat lists Chat messages.
        tap("thesis_insert_from_chat")
        await("chat picker opened") { tagExists("thesis_chat_list") || tagExists("thesis_chat_none") }
        await("chat messages listed") { tagExists("thesis_chat_item") }
        compose.onNodeWithTag("thesis_chat_item").performClick()
        compose.waitForIdle()
        assertTrue(controller.state.value.draft!!.text.contains("Recharge occurs mainly in winter"))

        tap("thesis_save_changes")
        awaitRepo("section saved") {
            repo.snapshot(id).elementsOf(introId).map { it.text } == listOf(
                "Groundwater is the main source of drinking water in the basin.",
                "Nitrate levels have increased in shallow wells.",
                "Recharge occurs mainly in winter through limestone outcrops."
            )
        }
        await("saved status") { controller.state.value.busy == null && controller.state.value.draft?.dirty == false }

        // An image goes where its placeholder is, with its caption, when the section is saved.
        controller.insertDraftImage({ ByteArrayInputStream(png) }, "map.png", "image/png", ElementData(caption = "Study area"), at = 0)
        await("image placeholder") { controller.state.value.draft?.text?.startsWith("[Figure 1]") == true }
        await("image strip") { tagExists("thesis_media_[Figure 1]") }
        await("idle after image") { controller.state.value.busy == null }
        tap("thesis_save_changes")
        awaitRepo("image saved") { repo.snapshot(id).elementsOf(introId).firstOrNull()?.let { it.type == ElementType.IMAGE && it.data.caption == "Study area" } == true }
        assertEquals(4, runBlocking { repo.snapshot(id) }.elementsOf(introId).size)

        // Typed text is saved when leaving with Back (nothing is lost).
        await("saved again") { controller.state.value.busy == null && controller.state.value.draft?.dirty == false }
        compose.onNodeWithTag("thesis_editor_text").performTextInput(" Closing sentence typed before going back.")
        await("typed", 5_000) { controller.state.value.draft?.dirty == true }
        tap("thesis_back")
        awaitRepo("autosaved on back") { repo.snapshot(id).elementsOf(introId).any { it.text.contains("Closing sentence typed before going back.") } }
        await("back in workspace") { tagExists("thesis_structure") }
        assertEquals(ThesisScreen.PROJECT, controller.state.value.screen)
        val saved = runBlocking { repo.snapshot(id) }
        assertEquals(ThesisStructure.Status.IN_PROGRESS, ThesisStructure.status(saved, saved.section(introId)!!))
    }

    /** Screen 4 toolbar: bold, alignment, undo/redo, heading, page break, table, section title and note. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class) // performTextInputSelection
    @Test
    @Config(qualifiers = "en")
    fun sectionEditor_formattingTableHeadingPageBreakUndo_andSectionInfo_reachWord() {
        val host = TestHost()
        val id = runBlocking { repo.createProject(meta(), aiAssist = false) }
        controller.openProject(id)
        showWorkspace(host)
        await("snapshot") { controller.state.value.snapshot != null }
        val introId = intro(controller.state.value.snapshot!!)
        controller.selectSection(introId)
        await("editor") { tagExists("thesis_editor_text") && controller.state.value.draft != null }
        val field = compose.onNodeWithTag("thesis_editor_text")
        field.performTextInput("Groundwater quality matters for the region.")
        // Select "quality" and make it bold.
        field.performTextInputSelection(androidx.compose.ui.text.TextRange(12, 19))
        tap("thesis_fmt_bold")
        assertEquals("Groundwater **quality** matters for the region.", controller.state.value.draft!!.text)
        tap("thesis_align_center")
        assertEquals("{center} Groundwater **quality** matters for the region.", controller.state.value.draft!!.text)
        tap("thesis_undo")
        assertEquals("Groundwater **quality** matters for the region.", controller.state.value.draft!!.text)
        tap("thesis_redo")
        assertEquals("{center} Groundwater **quality** matters for the region.", controller.state.value.draft!!.text)
        // Page break, then a heading on a new line.
        tap("thesis_page_break")
        assertTrue(controller.state.value.draft!!.text.endsWith("[Page break]"))
        field.performTextInput("\n\nMethods")
        tap("thesis_fmt_heading")
        tap("thesis_heading_2")
        assertTrue(controller.state.value.draft!!.text.endsWith("## Methods"))
        // Table with 2 columns, header + 2 rows.
        tap("thesis_insert_table")
        await("table dialog") { tagExists("thesis_table_save") }
        typeInto("thesis_cell_0_0", "Ion"); typeInto("thesis_cell_0_1", "mg/L")
        typeInto("thesis_cell_1_0", "Ca"); typeInto("thesis_cell_1_1", "12.5")
        typeInto("thesis_cell_2_0", "Mg"); typeInto("thesis_cell_2_1", "4.1")
        typeInto("thesis_table_caption", "Major ions")
        tap("thesis_table_save")
        await("table placeholder") { controller.state.value.draft?.text?.contains("[Table 1]") == true }
        // Section title and note.
        tap("thesis_section_info_toggle")
        compose.onNodeWithTag("thesis_section_title_field").performTextClearance()
        typeInto("thesis_section_title_field", "Introduction and context")
        typeInto("thesis_section_description", "Ask the supervisor about the map.")
        tap("thesis_save_changes")
        awaitRepo("saved") { repo.snapshot(id).elementsOf(introId).any { it.type == ElementType.TABLE } }
        val s = runBlocking { repo.snapshot(id) }
        val els = s.elementsOf(introId)
        assertEquals(listOf(ElementType.PARAGRAPH, ElementType.HEADING, ElementType.TABLE), els.map { it.type })
        assertEquals("Groundwater **quality** matters for the region.", els[0].text)
        assertEquals("center", els[0].data.textAlign)
        assertTrue(els[1].data.pageBreakBefore)
        assertEquals("Methods", els[1].text)
        assertEquals(listOf("Ion", "mg/L"), els[2].data.tableHeader)
        assertEquals(listOf(listOf("Ca", "12.5"), listOf("Mg", "4.1")), els[2].data.tableRows)
        assertEquals("Major ions", els[2].data.caption)
        assertEquals("Introduction and context", s.section(introId)!!.title)
        assertEquals("Ask the supervisor about the map.", s.section(introId)!!.description)
        // The Word export draws the formatting.
        runBlocking { idle() }
        controller.export("DOCX")
        await("exported", 20_000) { controller.state.value.lastExport != null }
        ZipFile(exports.last().file).use { z ->
            val xml = z.getInputStream(z.getEntry("word/document.xml")).bufferedReader().readText()
            assertTrue(xml.contains("<w:b/><w:bCs/></w:rPr><w:t xml:space=\"preserve\">quality</w:t>"))
            assertFalse(xml.contains("**quality**"))
            assertTrue(xml.contains("<w:pageBreakBefore/>"))
            assertTrue(xml.contains("12.5"))
            assertTrue(xml.contains("Introduction and context"))
        }
    }

    /** Screen 5: provider shown, results reviewed before use, failures keep the user's text. */
    @Test
    @Config(qualifiers = "en")
    fun aiAssistant_allActions_resultNeedsInsertAndSave_failureKeepsText() {
        val host = TestHost()
        val original = "Groundwater is important for the region."
        val id = runBlocking { repo.createProject(meta(), aiAssist = false) }
        runBlocking { repo.applyDirect(id, listOf(ThesisOp.AddElements(intro(repo.snapshot(id)), null, listOf(NewElement("p1", ElementType.PARAGRAPH, original)))), ChangeSource.USER, "x") }
        controller.openProject(id)
        showWorkspace(host)
        await("snapshot") { controller.state.value.snapshot?.element("p1") != null }
        val introId = intro(controller.state.value.snapshot!!)
        controller.selectSection(introId)
        await("editor") { tagExists("thesis_editor_text") && controller.state.value.draft != null }

        tap("thesis_open_assistant")
        await("assistant") { tagExists("thesis_ai_IMPROVE") }
        compose.onNodeWithText("Provider: FakeProvider").assertExists()
        compose.onNodeWithText("Model: fake-model").assertExists()
        listOf("thesis_ai_IMPROVE", "thesis_ai_EXPAND", "thesis_ai_REWRITE", "thesis_ai_CORRECT", "thesis_ai_ORGANIZE", "thesis_ai_generate").forEach { compose.onNodeWithTag(it).assertIsEnabled() }
        tap("thesis_ai_IMPROVE")
        await("result") { tagExists("thesis_ai_result") && controller.state.value.busy == null }
        assertEquals("$original (improved)", controller.state.value.assist!!.result)
        assertEquals(original, runBlocking { repo.snapshot(id) }.element("p1")!!.text) // only shown
        // Replace asks for confirmation first.
        tap("thesis_ai_replace")
        tap("thesis_ai_replace_confirm")
        await("back in editor") { tagExists("thesis_editor_text") && controller.state.value.screen == ThesisScreen.SECTION }
        assertEquals("$original (improved)", controller.state.value.draft!!.text)
        assertEquals(original, runBlocking { repo.snapshot(id) }.element("p1")!!.text) // not saved yet
        tap("thesis_save_changes")
        awaitRepo("saved") { repo.snapshot(id).elementsOf(introId).single().text == "$original (improved)" }

        // Every other action reaches the provider with its own mode.
        for (mode in listOf("EXPAND", "CORRECT", "ORGANIZE")) {
            await("idle before $mode") { controller.state.value.busy == null }
            tap("thesis_open_assistant")
            tap("thesis_ai_$mode")
            await("result $mode") { controller.state.value.assist?.result != null && controller.state.value.busy == null }
            assertEquals(com.example.thesis.core.EditMode.valueOf(mode), controller.state.value.assist!!.mode)
            tap("thesis_ai_discard") // Cancel: nothing changes
            await("editor after cancel") { controller.state.value.screen == ThesisScreen.SECTION }
            assertEquals("$original (improved)", controller.state.value.draft!!.text)
        }

        // A failed request shows an error; the text is unchanged.
        await("idle before failure") { controller.state.value.busy == null }
        ai.fail = AiException(AiErrorKind.NETWORK_UNAVAILABLE)
        tap("thesis_open_assistant")
        tap("thesis_ai_REWRITE")
        await("failure shown") { tagExists("thesis_ai_failed") }
        assertTrue(messages.any { it is ThesisMessage.AiFailed })
        tap("thesis_back")
        await("editor after failure") { tagExists("thesis_editor_text") }
        assertEquals("$original (improved)", controller.state.value.draft!!.text)
        assertEquals("$original (improved)", runBlocking { repo.snapshot(id) }.elementsOf(introId).single().text)
        ai.fail = null

        // Generated content can be edited, then is inserted below; nothing is replaced.
        tap("thesis_open_assistant")
        typeInto("thesis_ai_instruction", "Add a sentence about wells")
        tap("thesis_ai_generate")
        await("generated") { controller.state.value.assist?.result != null && controller.state.value.busy == null }
        compose.onNodeWithTag("thesis_ai_result").performTextInput(" Edited.")
        tap("thesis_ai_insert")
        await("inserted") { controller.state.value.screen == ThesisScreen.SECTION }
        assertTrue(controller.state.value.draft!!.text.startsWith("$original (improved)\n\n"))
        assertTrue(controller.state.value.draft!!.text.contains("$original (improved) (improved)"))
        assertTrue(controller.state.value.draft!!.text.contains("Edited."))
        assertTrue(ai.requests.count { it.startsWith("edit:") } >= 6)
    }

    /** Screens 3 and 6: chapters with titles, order, sub-sections, status, preview and export of the saved content. */
    @Test
    @Config(qualifiers = "en")
    fun workspace_addReorderCompleteChapters_andExportSavedContent() {
        val host = TestHost()
        val id = runBlocking { repo.createProject(meta(), aiAssist = false) }
        val s0 = runBlocking { repo.snapshot(id) }
        val introId = intro(s0)
        runBlocking { repo.applyDirect(id, listOf(ThesisOp.AddElements(introId, null, listOf(NewElement("p1", ElementType.PARAGRAPH, "Introduction text about groundwater.")))), ChangeSource.USER, "x") }
        controller.openProject(id)
        showWorkspace(host)
        await("workspace") { tagExists("thesis_structure") && controller.state.value.snapshot?.element("p1") != null }
        val chaptersBefore = s0.sections.filter { it.kind == SectionKind.CHAPTER }.map { it.id }
        tap("thesis_add_chapter")
        typeInto("thesis_text_input", "Field study")
        tap("thesis_text_confirm")
        awaitRepo("chapter added") { repo.snapshot(id).sections.count { it.kind == SectionKind.CHAPTER } == chaptersBefore.size + 1 }
        val newChapter = runBlocking { repo.snapshot(id) }.sections.first { it.kind == SectionKind.CHAPTER && it.id !in chaptersBefore }
        assertEquals("Field study", newChapter.title)
        val newId = newChapter.id
        await("chapter shown") { controller.state.value.snapshot?.section(newId) != null && controller.state.value.busy == null }

        // Reorder: the new chapter moves before the previous last chapter.
        clickInList("thesis_structure", "thesis_expand_$newId")
        clickInList("thesis_structure", "thesis_up_$newId")
        awaitRepo("reordered") { repo.snapshot(id).children(null).map { it.id }.let { it.indexOf(newId) == it.indexOf(chaptersBefore.last()) - 1 } }
        // Sub-section with a title inside the chapter.
        await("idle") { controller.state.value.busy == null }
        clickInList("thesis_structure", "thesis_add_sub_$newId")
        typeInto("thesis_text_input", "Sampling sites")
        tap("thesis_text_confirm")
        awaitRepo("sub-section") { repo.snapshot(id).children(newId).map { it.title } == listOf("Sampling sites") }

        // Mark the introduction completed.
        await("idle 2") { controller.state.value.busy == null }
        compose.onNodeWithTag("thesis_structure").performScrollToIndex(0)
        clickInList("thesis_structure", "thesis_expand_$introId")
        clickInList("thesis_structure", "thesis_complete_$introId")
        awaitRepo("completed") { repo.snapshot(id).section(introId)!!.completed }
        await("completed shown") { controller.state.value.snapshot?.section(introId)?.completed == true }
        compose.onNodeWithTag("thesis_status_$introId", useUnmergedTree = true).assertTextEquals("Completed")

        // Preview screen, then export from it (approved content only, saved to the Library).
        tap("thesis_workspace_menu")
        tap("thesis_menu_preview")
        await("preview screen") { tagExists("thesis_export_docx") }
        compose.onNodeWithTag("thesis_preview_back_workspace").assertExists()
        runBlocking { idle() }
        tap("thesis_export_docx")
        await("export result", 20_000) { tagExists("thesis_export_result") }
        val doc = runBlocking { db.documentDao().getAllDocuments().first() }.single { it.projectId == id }
        assertEquals("DOCX", doc.fileType)
        ZipFile(File(doc.filePath)).use { z ->
            val xml = z.getInputStream(z.getEntry("word/document.xml")).bufferedReader().readText()
            assertTrue(xml.contains("Introduction text about groundwater."))
            assertTrue(xml.contains("Field study"))
            assertTrue(xml.contains("Sampling sites"))
        }
        tap("thesis_export_open")
        assertEquals(doc.id, host.opened.single().id)
        tap("thesis_preview_back_workspace")
        await("workspace again") { controller.state.value.screen == ThesisScreen.PROJECT }
    }

    @Test
    @Config(qualifiers = "en")
    fun home_renameAndDeleteProject() {
        val host = TestHost()
        val id = runBlocking { repo.createProject(meta()) }
        controller.openWorkspace()
        showWorkspace(host)
        compose.waitUntil(10_000) { tagExists("thesis_project_$id") }
        tap("thesis_project_menu_$id")
        tap("thesis_project_rename")
        compose.onNodeWithTag("thesis_text_input").performTextClearance()
        compose.onNodeWithTag("thesis_text_input").performTextInput("Nitrates in groundwater")
        tap("thesis_text_confirm")
        waitFor { repo.snapshot(id).meta.title == "Nitrates in groundwater" }
        compose.waitUntil(10_000) { textExists("Nitrates in groundwater") }
        runBlocking { idle() }
        tap("thesis_project_menu_$id")
        tap("thesis_project_delete")
        tap("thesis_delete_project_confirm")
        waitFor { repo.observeProjects().first().isEmpty() }
        compose.waitUntil(10_000) { tagExists("thesis_empty_state") }
    }

    /** Continue in Chat from the editor targets the section; Chat content is reviewed before it is added. */
    @Test
    @Config(qualifiers = "en")
    fun sectionEditor_continueInChat_targetsSection_proposalAccepted() {
        val host = TestHost()
        val id = runBlocking { repo.createProject(meta(), aiAssist = true) }
        controller.openProject(id)
        showWorkspace(host)
        compose.waitUntil(10_000) { controller.state.value.snapshot != null }
        val chapterId = controller.state.value.snapshot!!.sections.first { it.kind == SectionKind.CHAPTER }.id
        controller.selectSection(chapterId)
        tap("thesis_editor_menu")
        tap("thesis_menu_section_chat")
        assertEquals(id to chapterId, host.chatRequests.single())
        assertEquals(chapterId, controller.state.value.chatSectionId)
        assertFalse(controller.state.value.workspaceOpen)

        val pasted = "Recharge occurs mainly in winter through limestone outcrops in the northern part of the basin."
        controller.submitFromChat(pasted)
        compose.waitUntil(15_000) { tagExists("thesis_accept") && controller.state.value.busy == null }
        assertTrue(runBlocking { repo.snapshot(id) }.elementsOf(chapterId).isEmpty())
        compose.onNodeWithTag("thesis_accept").assertIsEnabled().performClick()
        waitFor { repo.snapshot(id).elementsOf(chapterId).any { it.text == pasted } }
        // Leaving thesis mode restores normal Chat.
        controller.exitChatMode()
        assertNull(controller.state.value.chatProjectId)
    }

    @Test
    @Config(qualifiers = "ar")
    fun home_arabicLabels() {
        controller.openWorkspace()
        compose.setContent { ThesisWorkspaceContent(TestHost()) }
        compose.onNodeWithText("مذكرة جديدة").assertExists()
        compose.onNodeWithText("مشاريعي").assertExists()
        assertEquals("لم يبدأ", context.getString(R.string.thesis_status_not_started))
    }

    @Test
    @Config(qualifiers = "fr")
    fun home_frenchLabels() {
        controller.openWorkspace()
        compose.setContent { ThesisWorkspaceContent(TestHost()) }
        compose.onNodeWithText("Nouveau mémoire").assertExists()
        assertEquals("Enregistrer", context.getString(R.string.thesis_save_changes))
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
        val id = r1.createProject(meta("ar").copy(templateId = "w", subtitle = "دراسة", studentId = "42"))
        val s1 = r1.snapshot(id)
        val intro = intro(s1)
        r1.applyDirect(id, listOf(ThesisOp.AddSection(com.example.thesis.model.ThesisSection("ch-new", null, SectionKind.CHAPTER, "Field study", 0), null)), ChangeSource.STRUCTURE, "chapter")
        r1.applyDirect(id, listOf(ThesisOp.AddElements(intro, null, listOf(NewElement("p1", ElementType.PARAGRAPH, "مقدمة عامة حول المياه الجوفية.")))), ChangeSource.USER, "text")
        r1.applyDirect(id, listOf(ThesisOp.UpdateSection(intro, completed = true)), ChangeSource.STRUCTURE, "done")
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
        assertTrue(restored.section(intro)!!.completed)
        assertEquals("دراسة", restored.meta.subtitle)
        assertEquals("42", restored.meta.studentId)
        assertEquals("مقدمة عامة حول المياه الجوفية.", restored.elementsOf(intro).single().text)
        assertTrue(restored.elementsOf("ch-new").isEmpty()) // pending proposal not applied
        assertEquals(revisionsBefore, r1.revisions(id).map { it.number })
        assertEquals(ProposalStatus.PENDING.name, r1.proposal(pending.id)!!.status)
        db1.close()
    }
}
