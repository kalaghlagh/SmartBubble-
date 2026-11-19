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
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onAllNodesWithText
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
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
        /** Section contexts the assistant sent (what the provider is told about the section). */
        val contexts = CopyOnWriteArrayList<com.example.thesis.ai.SectionWriteContext>()
        override suspend fun write(snapshot: ThesisSnapshot, context: com.example.thesis.ai.SectionWriteContext, listener: AiProgressListener): EditSuggestion {
            contexts.add(context)
            return super.write(snapshot, context, listener)
        }
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
        /** The on-device extractor's stand-in (reading a page again from the review uses it). */
        var extract: com.example.thesis.Extractor? = null
        override suspend fun extractDocument(sources: List<com.example.extraction.ExtractionSource>, languageCode: String, progress: (Int, Int) -> Unit) =
            (extract ?: throw UnsupportedOperationException("no extractor"))(sources, languageCode, progress)
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
        // Wait for the controller's coroutines to finish before closing the database. Cancelling
        // does not interrupt a query already running on Dispatchers.Default (e.g. the projects
        // list read after a change): closed under it, it failed with "connection pool has been
        // closed" in a cancelled coroutine, reported as an uncaught exception at the start of the
        // next test (run #87, home_listsProjects_english). Same order as the logo restart test.
        runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
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
        append("projects listed: ${controller.projects.value.map { it.id to it.title }}; in the database: ${try { runBlocking { repo.projects() }.map { it.id to it.title } } catch (e: Throwable) { e.toString() }}\n")
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

    /**
     * Templates now live in the Library (the former My Templates records): Thesis Builder reads
     * them through the shared Library template repository, lists an uploaded Word template in
     * its picker, and keeps the chosen template on the new thesis.
     */
    @Test
    @Config(qualifiers = "en")
    fun newThesis_listsAndUsesALibraryTemplate_throughTheSharedRepository() {
        val file = java.io.File(context.cacheDir, "university_template.docx").apply { writeBytes(byteArrayOf(0x50, 0x4B, 3, 4)) }
        val template = TemplateEntity(id = "lib-template-1", name = "University template", fileType = "DOCX", originalFilePath = file.path,
            profileJson = com.example.template.domain.TemplateProfile.defaultProfile().toJson())
        runBlocking { db.templateDao().insertTemplate(template) }
        val repository = com.example.library.LibraryTemplateRepository(db.templateDao())
        val host = TestHost()
        val job = CoroutineScope(Dispatchers.IO).launch { repository.observe().collect { host.templateList.value = it } }
        try {
            await("library template read") { host.templateList.value.any { it.id == template.id } }
            assertEquals(listOf(true), repository.refs(host.templateList.value).map { it.available })
            controller.openWorkspace()
            showWorkspace(host)
            await("empty state") { tagExists("thesis_empty_state") }
            tap("thesis_new")
            typeInto("thesis_field_title", "Groundwater quality")
            typeInto("thesis_field_student", "Amina")
            tap("thesis_template_trigger")
            await("template menu") { tagExists("thesis_template_menu") }
            await("library template listed") { tagExists("select_option_${template.id}") }
            tap("select_option_${template.id}")
            await("template menu closed") { !tagExists("thesis_template_menu") }
            tap("thesis_save_meta")
            await("created") { controller.state.value.projectId != null && controller.state.value.busy == null }
            val s = runBlocking { repo.snapshot(controller.state.value.projectId!!) }
            assertEquals(template.id, s.meta.templateId)
            // A deleted template file is reported as unavailable (never resolved for use).
            file.delete()
            assertNull(runBlocking { repository.resolve(template.id) })
        } finally {
            job.cancel()
        }
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
        // Template: one dropdown (Stitch). Opening it selects nothing; choosing a built-in sets it.
        val academic = com.example.smarttemplates.BuiltInTemplates.forCategory("academic").first()
        tap("thesis_template_trigger")
        await("template menu") { tagExists("thesis_template_menu") }
        assertTrue(tagExists("select_option_default"))
        tap("select_option_${academic.id}")
        await("template menu closed") { !tagExists("thesis_template_menu") }
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
        assertEquals(academic.id, s.meta.templateId)
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

    /**
     * The assistant writes for the open section: the Dedication's identity reaches the AI, the
     * screen says which section is being written, and the result can go at the cursor, at the
     * end, or replace the whole section after confirmation.
     */
    @Test
    @Config(qualifiers = "en")
    fun aiAssistant_knowsTheSection_andOffersEveryInsertPlace() {
        val host = TestHost()
        val id = runBlocking { repo.createProject(meta(), aiAssist = false) }
        val dedication = runBlocking { repo.snapshot(id) }.sections.first { it.kind == SectionKind.DEDICATION }.id
        runBlocking { repo.applyDirect(id, listOf(ThesisOp.AddElements(dedication, null, listOf(
            NewElement("d1", ElementType.PARAGRAPH, "To my parents."), NewElement("d2", ElementType.PARAGRAPH, "To my friends.")))), ChangeSource.USER, "x") }
        ai.editResult = { items -> EditSuggestion(items.associate { it.first to "To my teachers." }, "done", emptyList()) }
        controller.openProject(id)
        showWorkspace(host)
        await("snapshot") { controller.state.value.snapshot?.element("d1") != null }
        controller.selectSection(dedication)
        await("editor") { tagExists("thesis_editor_text") && controller.state.value.draft != null }

        tap("thesis_open_assistant")
        await("assistant") { tagExists("thesis_ai_section_label") }
        compose.onNodeWithText("Section: Dedication").assertExists()
        tap("thesis_ai_generate")
        await("generated") { controller.state.value.assist?.result != null && controller.state.value.busy == null }
        val c = ai.contexts.last()
        assertEquals(com.example.thesis.ai.SectionType.DEDICATION, c.type)
        assertEquals("Dedication", c.sectionTitle)
        assertEquals(com.example.thesis.core.EditMode.GENERATE, c.mode)
        assertTrue(c.existingText.contains("To my parents."))
        assertTrue(c.related.isEmpty()) // no research excerpts for a dedication
        // Append: at the end of the section.
        tap("thesis_ai_append")
        await("appended") { controller.state.value.screen == ThesisScreen.SECTION }
        assertTrue(controller.state.value.draft!!.text.endsWith("To my friends.\n\nTo my teachers."))

        // At the cursor: after the line where the cursor is (here, the start of the text).
        controller.openAssistant(0, 0)
        controller.runAssistant(com.example.thesis.core.EditMode.CONTINUE, "")
        await("continued") { controller.state.value.assist?.result != null && controller.state.value.busy == null }
        assertEquals(com.example.thesis.core.EditMode.CONTINUE, ai.contexts.last().mode)
        tap("thesis_ai_insert_cursor")
        await("at cursor") { controller.state.value.screen == ThesisScreen.SECTION }
        assertTrue(controller.state.value.draft!!.text.startsWith("To my teachers.\n\nTo my parents."))

        // Replace the whole section from a selection: asks first, Cancel keeps the text.
        val before = controller.state.value.draft!!.text
        controller.openAssistant(0, 5)
        controller.runAssistant(com.example.thesis.core.EditMode.IMPROVE, "")
        await("improved") { controller.state.value.assist?.result != null && controller.state.value.busy == null }
        tap("thesis_ai_replace_section")
        await("confirm") { tagExists("thesis_ai_replace_section_confirm") }
        tap("thesis_ai_replace_section_confirm")
        await("replaced") { controller.state.value.screen == ThesisScreen.SECTION }
        assertEquals("To my teachers.", controller.state.value.draft!!.text)
        assertTrue(before != controller.state.value.draft!!.text)
        // Nothing is saved before "Save changes".
        assertEquals("To my parents.", runBlocking { repo.snapshot(id) }.element("d1")!!.text)
    }

    /** A stand-in for the on-device extractor: what a page with a heading, a paragraph and a table gives. */
    private val extracted = com.example.extraction.ExtractedDocument(listOf(
        com.example.extraction.ExtractedPage(0, com.example.extraction.PageSource.IMAGE_OCR, listOf(
            com.example.extraction.ExtractedBlock.Heading("1.2 Sampling", 2, rtl = false),
            com.example.extraction.ExtractedBlock.Paragraph("Twenty wells were sampled.", rtl = false),
            com.example.extraction.ExtractedBlock.Table(listOf(
                listOf(com.example.extraction.TableCell("Well"), com.example.extraction.TableCell("Nitrates")),
                listOf(com.example.extraction.TableCell("W1"), com.example.extraction.TableCell("35"))
            ), header = true, rtl = false)
        ), scripts = setOf(com.example.extraction.Script.LATIN), level = com.example.extraction.ReconstructionLevel.STRUCTURE, confidence = 0.91f)
    ))
    private val extractorCalls = CopyOnWriteArrayList<List<com.example.extraction.ExtractionSource>>()
    private val fakeExtractor: com.example.thesis.Extractor = { sources, _, progress -> extractorCalls.add(sources); progress(1, 1); extracted }

    /**
     * Extract from Image/PDF: the review opens for the section the extraction was started from,
     * corrections are kept, the content (with a real table) goes into that section only, and it
     * is saved through the normal draft, surviving a new repository (app restart).
     */
    @Test
    @Config(qualifiers = "en")
    fun extraction_insertsIntoTheStartingSection_withATable_andPersists() {
        val host = TestHost()
        val id = runBlocking { repo.createProject(meta(), aiAssist = false) }
        val introId = intro(runBlocking { repo.snapshot(id) })
        runBlocking { repo.applyDirect(id, listOf(ThesisOp.AddElements(introId, null, listOf(NewElement("p1", ElementType.PARAGRAPH, "Existing text.")))), ChangeSource.USER, "x") }
        controller.openProject(id)
        showWorkspace(host)
        await("snapshot") { controller.state.value.snapshot?.element("p1") != null }
        controller.selectSection(introId)
        await("editor") { tagExists("thesis_editor_text") && controller.state.value.draft != null }
        // The editor offers the action; it opens the choice of images or PDF.
        tap("thesis_extract_document")
        await("chooser") { tagExists("dx_pick_images") && tagExists("dx_pick_pdf") }
        compose.onAllNodesWithText("Cancel").onLast().performClick()
        // The pickers are system screens: the extraction is started as they would start it.
        val src = com.example.extraction.ExtractionSource(android.net.Uri.parse("file:///scan.jpg"), isPdf = false)
        compose.runOnIdle { controller.startExtraction(listOf(src), "auto", 999, fakeExtractor) }
        await("review") { tagExists("dx_review") && controller.state.value.extraction?.document != null }
        assertEquals(listOf(listOf(src)), extractorCalls.toList())
        assertEquals(introId, controller.state.value.extraction!!.sectionId)
        assertTrue(tagExists("dx_page_report"))
        // A correction in the review is kept.
        compose.runOnIdle { controller.updateExtractedBlock(0, 1, com.example.extraction.ExtractedBlock.Paragraph("Twenty wells were sampled in 2024.", rtl = false)) }
        tap("dx_insert_append")
        await("inserted") { controller.state.value.extraction == null }
        val draft = controller.state.value.draft!!
        assertEquals(introId, draft.sectionId)
        assertTrue(draft.text, draft.text.startsWith("Existing text.\n\n## 1.2 Sampling\n\nTwenty wells were sampled in 2024.\n\n[Table 1]"))
        val table = draft.media.getValue("[Table 1]")
        assertEquals(listOf("Well", "Nitrates"), table.data.tableHeader)
        assertEquals(listOf(listOf("W1", "35")), table.data.tableRows)
        // Nothing saved yet; "Save changes" writes it like typed text.
        assertEquals(1, runBlocking { repo.snapshot(id) }.elementsOf(introId).size)
        tap("thesis_save_changes")
        awaitRepo("saved") { repo.snapshot(id).elementsOf(introId).any { it.type == ElementType.TABLE } }
        // A new repository on the same database (as after a restart) reads the same content.
        val reopened = ThesisRepository(db, tmp.root.resolve("thesis"), imageInspector = { null })
        val els = runBlocking { reopened.snapshot(id) }.elementsOf(introId)
        assertEquals(listOf(ElementType.PARAGRAPH, ElementType.HEADING, ElementType.PARAGRAPH, ElementType.TABLE), els.map { it.type })
        assertEquals("1.2 Sampling", els[1].text)
        assertEquals(2, els[1].data.headingLevel)
        assertEquals(listOf("Well", "Nitrates"), els[3].data.tableHeader)
        assertEquals(listOf(listOf("W1", "35")), els[3].data.tableRows)
    }

    /** A page of [paragraphs] paragraphs (Latin, Arabic and mixed), headed by [heading]. */
    private fun longPage(heading: String, paragraphs: Int, index: Int = 0) = com.example.extraction.ExtractedPage(index, com.example.extraction.PageSource.PDF_OCR,
        listOf(com.example.extraction.ExtractedBlock.Heading(heading, 1, rtl = false)) + (1..paragraphs).map { i ->
            when (i % 3) {
                0 -> com.example.extraction.ExtractedBlock.Paragraph("تعد المياه الجوفية مصدرا أساسيا لمياه الشرب في المناطق الجافة، الفقرة $i.", rtl = true)
                1 -> com.example.extraction.ExtractedBlock.Paragraph("Paragraph $i: the wells of the basin were sampled every month for nitrates.", rtl = false)
                else -> com.example.extraction.ExtractedBlock.Paragraph("تم قياس درجة الحموضة pH والناقلية في البئر W$i سنة 2024.", rtl = true)
            }
        }, scripts = setOf(com.example.extraction.Script.ARABIC, com.example.extraction.Script.LATIN),
        level = com.example.extraction.ReconstructionLevel.STRUCTURE, confidence = 0.9f)

    private fun count(text: String, part: String) = text.windowed(part.length).count { it == part }

    /**
     * Device crash (Samsung SM-S901B, Android 16, build 5a4b978): "Can't represent a width of 984
     * and height of 362745 in Constraints" in OutlinedTextField measurement after an insertion
     * action. The section editor's text field sat in the page's vertical scroll with only a
     * minimum height, so it was measured at the full height of the inserted multi-page text,
     * beyond what Constraints can hold. Here every insertion action puts a page tall enough to
     * exceed that limit into the editor, repeatedly: the editor stays bounded and usable, each
     * action inserts the content once, and the text can be edited and scrolled.
     */
    @Test
    @Config(qualifiers = "en")
    fun extraction_longPage_everyInsertAction_noLayoutCrash_noDuplication() {
        val host = TestHost()
        val id = runBlocking { repo.createProject(meta(), aiAssist = false) }
        val introId = intro(runBlocking { repo.snapshot(id) })
        controller.openProject(id)
        showWorkspace(host)
        await("snapshot") { controller.state.value.snapshot != null }
        controller.selectSection(introId)
        await("editor") { tagExists("thesis_editor_text") && controller.state.value.draft != null }
        // ~4,000 paragraphs: tens of thousands of pixels more than a Constraints height can hold.
        val pdf = android.net.Uri.parse("file:///thesis.pdf")
        val actions = listOf("dx_insert_paragraphs", "dx_insert_heading", "dx_insert_cursor", "dx_insert_append")
        actions.forEachIndexed { k, action ->
            val marker = "Extracted page marker $k"
            val doc = com.example.extraction.ExtractedDocument(listOf(longPage(marker, if (k == 0) 4000 else 300)))
            val src = com.example.extraction.ExtractionSource(pdf, isPdf = true, pages = setOf(k))
            compose.runOnIdle { controller.startExtraction(listOf(src), "auto", controller.state.value.draft!!.text.length, { _, _, progress -> progress(1, 1); doc }) }
            await("review $action") { tagExists("dx_review") && controller.state.value.extraction?.document != null }
            tap(action)
            await("inserted $action") { controller.state.value.extraction == null && tagExists("thesis_editor_text") }
            compose.waitForIdle()
            val text = controller.state.value.draft!!.text
            assertEquals("$action inserts the page once", 1, count(text, marker))
            // The editor is measured within the screen, whatever the length of its text.
            val editor = compose.onNodeWithTag("thesis_editor_text").fetchSemanticsNode()
            val root = compose.onRoot().fetchSemanticsNode()
            assertTrue("editor height ${editor.size.height} within the window ${root.size.height}", editor.size.height <= root.size.height)
        }
        val text = controller.state.value.draft!!.text
        actions.indices.forEach { k -> assertEquals(1, count(text, "Extracted page marker $k")) }
        assertTrue("Arabic and mixed content kept", text.contains("تعد المياه الجوفية") && text.contains("درجة الحموضة pH والناقلية"))
        // The long text scrolls and can still be edited.
        compose.onNodeWithTag("thesis_editor_text").performTouchInput { swipeUp() }
        compose.onNodeWithTag("thesis_editor_text").performTouchInput { swipeDown() }
        compose.onNodeWithTag("thesis_editor_text").performTextInput("Edited.")
        await("edited") { controller.state.value.draft!!.text.contains("Edited.") }
        assertEquals(text.length + "Edited.".length, controller.state.value.draft!!.text.length)
    }

    /**
     * Thesis Builder PDF viewer: the page chosen there is the only page extracted and inserted;
     * choosing another page afterwards extracts that one.
     */
    @Test
    @Config(qualifiers = "en")
    fun extraction_chosenPdfPage_isTheOnlyPageInserted() {
        val host = TestHost()
        val id = runBlocking { repo.createProject(meta(), aiAssist = false) }
        val introId = intro(runBlocking { repo.snapshot(id) })
        controller.openProject(id)
        showWorkspace(host)
        await("snapshot") { controller.state.value.snapshot != null }
        controller.selectSection(introId)
        await("editor") { tagExists("thesis_editor_text") && controller.state.value.draft != null }
        val pdf = android.net.Uri.parse("file:///report.pdf")
        // The extractor reads only the pages asked for, as DocumentExtractionEngine does.
        val asked = CopyOnWriteArrayList<Set<Int>?>()
        val extractor: com.example.thesis.Extractor = { sources, _, progress ->
            asked.add(sources.single().pages); progress(1, 1)
            com.example.extraction.ExtractedDocument(sources.single().pages.orEmpty().map { p -> longPage("Content of page ${p + 1}", 2, index = 0).copy(sourcePage = p) })
        }
        for (page in listOf(2, 0)) {
            compose.runOnIdle { controller.startExtraction(listOf(com.example.extraction.ExtractionSource(pdf, isPdf = true, pages = setOf(page))), "auto", 0, extractor) }
            await("review $page") { tagExists("dx_review") && controller.state.value.extraction?.document != null }
            tap("dx_insert_append")
            await("inserted $page") { controller.state.value.extraction == null }
        }
        assertEquals(listOf<Set<Int>?>(setOf(2), setOf(0)), asked.toList())
        val text = controller.state.value.draft!!.text
        assertEquals(1, count(text, "Content of page 3"))
        assertEquals(1, count(text, "Content of page 1"))
        assertEquals(0, count(text, "Content of page 2"))
        assertTrue("page 3 first, then page 1", text.indexOf("Content of page 3") < text.indexOf("Content of page 1"))
    }

    /** A project whose Introduction holds "Existing text.", open in the section editor; returns its id. */
    private fun introEditor(host: TestHost): String {
        val id = runBlocking { repo.createProject(meta(), aiAssist = false) }
        val introId = intro(runBlocking { repo.snapshot(id) })
        runBlocking { repo.applyDirect(id, listOf(ThesisOp.AddElements(introId, null, listOf(NewElement("p1", ElementType.PARAGRAPH, "Existing text.")))), ChangeSource.USER, "x") }
        controller.openProject(id)
        showWorkspace(host)
        await("snapshot") { controller.state.value.snapshot?.element("p1") != null }
        controller.selectSection(introId)
        await("editor") { tagExists("thesis_editor_text") && controller.state.value.draft != null }
        return introId
    }

    /** Content of PDF page [p] (0-based) as DocumentExtractionEngine gives it: heading, Latin and Arabic text, a table on page 5. */
    private fun pdfPage(i: Int, p: Int, failed: Boolean = false, paragraphs: Int = 1): com.example.extraction.ExtractedPage =
        if (failed) com.example.extraction.ExtractedPage(i, com.example.extraction.PageSource.PDF_OCR, emptyList(), error = "page_unreadable", sourcePage = p)
        else com.example.extraction.ExtractedPage(i, if (p % 2 == 0) com.example.extraction.PageSource.PDF_TEXT else com.example.extraction.PageSource.PDF_OCR,
            listOf<com.example.extraction.ExtractedBlock>(com.example.extraction.ExtractedBlock.Heading("Title of page ${p + 1}", 1, rtl = false)) +
                (1..paragraphs).flatMap { k -> listOf(
                    com.example.extraction.ExtractedBlock.Paragraph("Body of page ${p + 1}" + (if (k > 1) " part $k" else "") + ": wells and springs were sampled.", rtl = false),
                    com.example.extraction.ExtractedBlock.Paragraph("نص الصفحة ${p + 1} عن المياه الجوفية ودرجة الحموضة pH.", rtl = true)) } +
                (if (p == 4) listOf(com.example.extraction.ExtractedBlock.Table(listOf(
                    listOf(com.example.extraction.TableCell("Well"), com.example.extraction.TableCell("Depth")),
                    listOf(com.example.extraction.TableCell("W5"), com.example.extraction.TableCell("120"))), header = true, rtl = false)) else emptyList()),
            scripts = setOf(com.example.extraction.Script.LATIN, com.example.extraction.Script.ARABIC),
            level = com.example.extraction.ReconstructionLevel.STRUCTURE, confidence = if (p % 2 == 0) null else 0.9f, sourcePage = p)

    /** Extracts the pages asked for in document order (as DocumentExtractionEngine), [failing] pages failing once. */
    private fun pagesExtractor(asked: MutableList<Set<Int>?>, failing: MutableSet<Int> = mutableSetOf(), paragraphs: Int = 1): com.example.thesis.Extractor = { sources, _, progress ->
        val pages = sources.single().pages.orEmpty().sorted()
        asked.add(sources.single().pages)
        com.example.extraction.ExtractedDocument(pages.mapIndexed { i, p ->
            progress(i + 1, pages.size)
            pdfPage(i, p, failed = failing.remove(p), paragraphs = paragraphs)
        })
    }

    /** The text [after] has that [before] had not (one insertion). */
    private fun insertedPart(before: String, after: String): String {
        var p = 0
        while (p < before.length && p < after.length && before[p] == after[p]) p++
        var q = 0
        while (q < before.length - p && q < after.length - p && before[before.length - 1 - q] == after[after.length - 1 - q]) q++
        return after.substring(p, after.length - q)
    }

    /** The seven review actions, each displayed (on screen) and enabled where content allows it. */
    private val reviewActions = listOf("dx_insert_cursor", "dx_insert_append", "dx_insert_paragraphs", "dx_insert_heading", "dx_insert_tables", "dx_copy", "dx_cancel_review")

    private fun assertReviewActionsShown() {
        compose.waitForIdle()
        val window = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().maxOf { it.boundsInRoot.height }
        for (tag in reviewActions) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag not wholly on screen: $b (window height $window)", b.width > 0f && b.height >= 40f && b.bottom <= window + 1f)
        }
    }

    private val pdfUri = android.net.Uri.parse("file:///report.pdf")
    private fun pdfSource(vararg pages: Int) = com.example.extraction.ExtractionSource(pdfUri, isPdf = true, pages = linkedSetOf(*pages.toTypedArray()))

    /**
     * Several PDF pages (selected out of order: 7, 2, 5): the review shows them in document order
     * under their original page numbers, the page headings are not inserted, and every action
     * (Insert at Cursor, Append, As Paragraphs, Heading + Content, Tables only, Copy) uses exactly
     * the content of those pages, once each, in document order.
     */
    @Test
    @Config(qualifiers = "en")
    fun extraction_multiplePdfPages_everyActionUsesTheSelectedPages_inDocumentOrder() {
        val host = TestHost()
        introEditor(host)
        val asked = CopyOnWriteArrayList<Set<Int>?>()
        val extractor = pagesExtractor(asked)
        fun extract() {
            compose.runOnIdle { controller.startExtraction(listOf(pdfSource(6, 1, 4)), "auto", 0, extractor) }
            await("review") { tagExists("dx_review") && controller.state.value.extraction?.document != null }
        }
        extract()
        // Review: the pages chosen, each under its own original page number, in document order.
        compose.onNodeWithTag("dx_review_pages").assertTextEquals("Pages: 2, 5, 7")
        assertEquals(listOf(1, 4, 6), controller.state.value.extraction!!.document!!.pages.map { it.sourcePage })
        listOf(0 to "Page 2", 1 to "Page 5", 2 to "Page 7").forEach { (i, label) ->
            compose.onNodeWithTag("dx_pages").performScrollToNode(androidx.compose.ui.test.hasTestTag("dx_page_header_$i"))
            compose.onNodeWithTag("dx_page_header_$i").assertTextEquals(label)
        }
        compose.onNodeWithTag("dx_summary").assertTextEquals("Extraction completed: 3 of 3 pages read.")
        // Every action of the review is on screen at once (not only in the tree), without scrolling.
        assertReviewActionsShown()
        // Copy: the text of the three pages.
        tap("dx_copy")
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip!!.getItemAt(0).text.toString()
        assertTrue(clip, clip.indexOf("Body of page 2") in 0 until clip.indexOf("Body of page 5") && clip.indexOf("Body of page 5") < clip.indexOf("Body of page 7"))
        fun body(n: Int) = "Body of page $n: wells and springs were sampled."
        for (action in listOf("dx_insert_cursor", "dx_insert_append", "dx_insert_paragraphs", "dx_insert_heading", "dx_insert_tables")) {
            if (controller.state.value.extraction == null) extract()
            val before = controller.state.value.draft!!.text
            tap(action)
            await("inserted $action") { controller.state.value.extraction == null }
            val text = controller.state.value.draft!!.text
            val added = text.length - before.length
            assertTrue("$action added nothing", added > 0)
            assertEquals("$action kept the section's text", count(before, "Existing text."), count(text, "Existing text."))
            // No page heading of the review is inserted.
            assertTrue("$action inserted a page separator: $text", text.lines().none { it.trim().matches(Regex("(-+ )?Page \\d+( -+)?")) })
            if (action == "dx_insert_tables") {
                listOf(2, 5, 7).forEach { n -> assertEquals("$action: only tables", count(before, body(n)), count(text, body(n))) }
                assertTrue(text, text.length > before.length && count(text, "[Table") > count(before, "[Table"))
            } else {
                listOf(2, 5, 7).forEach { n ->
                    assertEquals("$action: page $n once", count(before, body(n)) + 1, count(text, body(n)))
                    assertEquals("$action: Arabic of page $n once", count(before, "نص الصفحة $n عن") + 1, count(text, "نص الصفحة $n عن"))
                }
                // Document order in what was inserted (not the order of selection).
                val inserted = insertedPart(before, text)
                val a = inserted.indexOf(body(2)); val b = inserted.indexOf(body(5)); val c = inserted.indexOf(body(7))
                assertTrue("$action order: $a $b $c", a in 0 until b && b < c)
                assertEquals("$action: never another page", 0, count(text, "Body of page 1:") + count(text, "Body of page 3:"))
            }
        }
        // Each extraction asked for exactly the pages selected.
        assertTrue(asked.toList().toString(), asked.all { it == setOf(1, 4, 6) })
    }

    /**
     * A selected page that fails is shown as failed (not as extracted), the others are kept, and
     * Retry reads it again; Insert then has all the pages. Progress names the original page.
     */
    @Test
    @Config(qualifiers = "en")
    fun extraction_failedPage_keepsTheOthers_retryReadsItAgain() {
        val host = TestHost()
        introEditor(host)
        val asked = CopyOnWriteArrayList<Set<Int>?>()
        val failing = mutableSetOf(4)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val inner = pagesExtractor(asked, failing)
        // The first extraction waits after reporting page 2 of 3 (original page 5).
        val extractor: com.example.thesis.Extractor = { sources, lang, progress ->
            if (sources.single().pages!!.size == 3) { progress(2, 3); gate.await() }
            inner(sources, lang, progress)
        }
        host.extract = extractor
        compose.runOnIdle { controller.startExtraction(listOf(pdfSource(1, 4, 6)), "auto", 0, extractor) }
        await("progress") { tagExists("dx_progress") }
        compose.onNodeWithTag("dx_progress").assertTextEquals("Extracting page 5 (2 of 3)…")
        gate.complete(Unit)
        await("review") { controller.state.value.extraction?.document != null }
        compose.onNodeWithTag("dx_summary").assertTextEquals("Extraction completed: 2 of 3 pages read.")
        compose.onNodeWithTag("dx_pages").performScrollToNode(androidx.compose.ui.test.hasTestTag("dx_page_failed_1"))
        compose.onNodeWithTag("dx_page_failed_1").assertTextEquals("Page 5 could not be extracted.")
        val doc = controller.state.value.extraction!!.document!!
        assertEquals(listOf(null, "page_unreadable", null), doc.pages.map { it.error })
        assertTrue(doc.pages[0].blocks.isNotEmpty() && doc.pages[2].blocks.isNotEmpty())
        tap("dx_retry_failed_1")
        await("page read again") { controller.state.value.extraction?.document?.pages?.get(1)?.error == null && controller.state.value.extraction?.retryingPage == null }
        assertEquals(setOf(4), asked.last())
        assertEquals(listOf(1, 4, 6), controller.state.value.extraction!!.document!!.pages.map { it.sourcePage })
        tap("dx_insert_append")
        await("inserted") { controller.state.value.extraction == null }
        val text = controller.state.value.draft!!.text
        listOf(2, 5, 7).forEach { n -> assertEquals(1, count(text, "Body of page $n:")) }
    }

    /**
     * Next tapped again while the same pages are being extracted starts no second job; a new
     * selection replaces the earlier extraction, and only its result can be inserted.
     */
    @Test
    @Config(qualifiers = "en")
    fun extraction_repeatedRequest_oneJob_newSelectionReplacesTheOldResult() {
        val host = TestHost()
        introEditor(host)
        val asked = CopyOnWriteArrayList<Set<Int>?>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val inner = pagesExtractor(asked)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val extractor: com.example.thesis.Extractor = { sources, lang, progress -> calls.incrementAndGet(); gate.await(); inner(sources, lang, progress) }
        compose.runOnIdle {
            controller.startExtraction(listOf(pdfSource(0, 2)), "auto", 0, extractor)
            controller.startExtraction(listOf(pdfSource(0, 2)), "auto", 0, extractor)
            controller.startExtraction(listOf(pdfSource(2, 0)), "auto", 0, extractor)
        }
        compose.waitForIdle()
        assertEquals("one job for the same pages", 1, calls.get())
        // Another selection while it runs: the first job is dropped, the new one is the result.
        compose.runOnIdle { controller.startExtraction(listOf(pdfSource(5)), "auto", 0, extractor) }
        compose.waitForIdle()
        gate.complete(Unit)
        await("review") { controller.state.value.extraction?.document != null }
        assertEquals(listOf(5), controller.state.value.extraction!!.document!!.pages.map { it.sourcePage })
        tap("dx_insert_append")
        await("inserted") { controller.state.value.extraction == null }
        val text = controller.state.value.draft!!.text
        assertEquals(1, count(text, "Body of page 6:"))
        assertEquals(0, count(text, "Body of page 1:") + count(text, "Body of page 3:"))
        assertEquals(listOf<Set<Int>?>(setOf(5)), asked.toList())
    }

    /**
     * Long multi-page extraction (three pages of Arabic, Latin and mixed text, ~4,500 blocks):
     * the review list and its fields stay bounded and scroll, repeated insertions do not crash or
     * duplicate, and the section editor stays within the window and can be edited.
     */
    @Test
    @Config(qualifiers = "ar")
    fun extraction_longMultiPage_reviewAndInsert_noLayoutCrash() {
        val host = TestHost()
        introEditor(host)
        val asked = CopyOnWriteArrayList<Set<Int>?>()
        val extractor = pagesExtractor(asked, paragraphs = 350)
        for ((k, action) in listOf("dx_insert_paragraphs", "dx_insert_heading", "dx_insert_append").withIndex()) {
            compose.runOnIdle { controller.startExtraction(listOf(pdfSource(0, 3 + k, 8 + k)), "auto", controller.state.value.draft!!.text.length, extractor) }
            await("review $action") { tagExists("dx_pages") && controller.state.value.extraction?.document != null }
            // Right to left (Arabic UI): the seven actions are on screen too.
            assertReviewActionsShown()
            val root = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().maxOf { it.size.height }
            assertTrue("review list bounded", compose.onNodeWithTag("dx_pages").fetchSemanticsNode().size.height <= root)
            compose.onNodeWithTag("dx_pages").performTouchInput { swipeUp() }
            compose.onNodeWithTag("dx_pages").performScrollToNode(androidx.compose.ui.test.hasTestTag("dx_page_header_2"))
            tap(action)
            await("inserted $action") { controller.state.value.extraction == null && tagExists("thesis_editor_text") }
            compose.waitForIdle()
            val editor = compose.onNodeWithTag("thesis_editor_text").fetchSemanticsNode()
            assertTrue("editor ${editor.size.height} within ${compose.onRoot().fetchSemanticsNode().size.height}", editor.size.height <= compose.onRoot().fetchSemanticsNode().size.height)
        }
        val text = controller.state.value.draft!!.text
        // Page 1 was in every extraction: three times; the others once.
        assertEquals(3, count(text, "Body of page 1:"))
        listOf(4, 9, 5, 10, 6, 11).forEach { n -> assertEquals("page $n", 1, count(text, "Body of page $n:")) }
        compose.onNodeWithTag("thesis_editor_text").performTouchInput { swipeUp() }
        compose.onNodeWithTag("thesis_editor_text").performTextInput("تعديل.")
        await("edited") { controller.state.value.draft!!.text.contains("تعديل.") }
    }

    /** Extracted content never lands in another section than the one it was started from. */
    @Test
    @Config(qualifiers = "en")
    fun extraction_isNotInsertedIntoAnotherSection() {
        val id = runBlocking { repo.createProject(meta(), aiAssist = false) }
        val s = runBlocking { repo.snapshot(id) }
        val introId = intro(s)
        val chapterId = s.sections.first { it.kind == SectionKind.CHAPTER }.id
        controller.openProject(id)
        await("snapshot") { controller.state.value.snapshot != null }
        controller.selectSection(introId)
        await("intro draft") { controller.state.value.draft?.sectionId == introId }
        controller.startExtraction(listOf(com.example.extraction.ExtractionSource(android.net.Uri.parse("file:///a.jpg"), false)), "auto", 0, fakeExtractor)
        await("extracted") { controller.state.value.extraction?.document != null }
        controller.selectSection(chapterId)
        await("chapter draft") { controller.state.value.draft?.sectionId == chapterId }
        val before = controller.state.value.draft!!.text
        controller.insertExtraction(com.example.thesis.core.ExtractionInsertMode.AT_CURSOR)
        await("refused") { messages.any { it == ThesisMessage.ExtractionWrongSection } }
        assertEquals(before, controller.state.value.draft!!.text)
        assertEquals(chapterId, controller.state.value.draft!!.sectionId)
        // Cancel closes the review without inserting anything.
        controller.cancelExtraction()
        assertEquals(null, controller.state.value.extraction)
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
        // The state changes before the screen is recomposed (run #116): wait for the next idle frame.
        compose.waitForIdle()
        compose.onNodeWithTag("thesis_status_$introId", useUnmergedTree = true).assertTextEquals("Completed")

        // Preview screen: a document viewer only, with Return to Workspace and Full thesis; the
        // export buttons and the export result are not on it.
        tap("thesis_workspace_menu")
        tap("thesis_menu_preview")
        await("preview screen") { tagExists("thesis_preview_screen") && controller.state.value.busy == null }
        compose.onNodeWithTag("thesis_preview_back_workspace").assertExists()
        compose.onNodeWithTag("thesis_preview_whole").assertExists()
        for (tag in listOf("thesis_export_docx", "thesis_export_pdf", "thesis_export_result", "thesis_export_section")) assertFalse("$tag on the preview", tagExists(tag))
        runBlocking { idle() }
        // Export from the workspace (approved content only, saved to the Library).
        tap("thesis_preview_back_workspace")
        await("workspace") { controller.state.value.screen == ThesisScreen.PROJECT && tagExists("thesis_export_docx") }
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
        // The export result stays in the workspace: opening the preview again does not show it.
        tap("thesis_workspace_menu")
        tap("thesis_menu_preview")
        await("preview again") { tagExists("thesis_preview_screen") }
        assertFalse("export result on the preview", tagExists("thesis_export_result"))
        tap("thesis_preview_back_workspace")
        await("workspace again") { controller.state.value.screen == ThesisScreen.PROJECT }
        tap("thesis_export_dismiss")
        await("result dismissed") { controller.state.value.lastExport == null && !tagExists("thesis_export_result") }
    }

    @Test
    @Config(qualifiers = "en")
    fun home_renameAndDeleteProject() {
        val host = TestHost()
        val id = runBlocking { repo.createProject(meta()) }
        controller.openWorkspace()
        showWorkspace(host)
        await("project listed", 10_000) { tagExists("thesis_project_$id") }
        tap("thesis_project_menu_$id")
        tap("thesis_project_rename")
        compose.onNodeWithTag("thesis_text_input").performTextClearance()
        compose.onNodeWithTag("thesis_text_input").performTextInput("Nitrates in groundwater")
        tap("thesis_text_confirm")
        waitFor { repo.snapshot(id).meta.title == "Nitrates in groundwater" }
        await("new title listed", 10_000) { textExists("Nitrates in groundwater") }
        runBlocking { idle() }
        tap("thesis_project_menu_$id")
        tap("thesis_project_delete")
        tap("thesis_delete_project_confirm")
        waitFor { repo.observeProjects().first().isEmpty() }
        await("empty list", 10_000) { tagExists("thesis_empty_state") }
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

    /**
     * University logo picked on the New thesis form (before the project exists): copied into
     * app storage at once, attached to the project when it is created, still there after the
     * app is closed and reopened, in the Word file, and removable.
     */
    @Test
    fun logoPickedOnTheNewThesisForm_isKept_afterRestart_andExported(): Unit = runBlocking {
        val dbFile = File(tmp.root, "logo.db")
        val folder = tmp.newFolder("thesis-logo")
        fun open() = Room.databaseBuilder(context, SmartBubbleDatabase::class.java, dbFile.path).allowMainThreadQueries().build()
        val inspector: (File) -> Pair<Int, Int>? = { f -> if (f.length() > 8 && f.readBytes().take(4) == png.take(4)) 200 to 100 else null }
        // Each controller has its own scope, stopped (and waited for) before its database is
        // closed: a job still reading a closed database would fail on another thread and be
        // reported against a later test.
        fun controller(d: SmartBubbleDatabase, r: ThesisRepository, preview: File, sc: CoroutineScope) = ThesisController(sc, r, ai,
            ThesisExporter(r, RoomTemplateRepository(d.templateDao()), tmp.newFolder(), pdfRenderer = { p, sn, a, f -> ThesisPdfRenderer(p).render(sn, a, f) }), preview,
            onMessage = { messages.add(it) }, onChatNote = {}, onSaveToLibrary = { e, p -> saveToLibrary(e, p) })
        val previewDir = tmp.newFolder("logo-preview")
        var db1 = open()
        var r1 = ThesisRepository(db1, folder, imageInspector = inspector)
        var scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var c = controller(db1, r1, previewDir, scope1)
        // The picker's stream is read once; the copy is what the project uses.
        var reads = 0
        c.stagePendingLogo({ reads++; png.inputStream() }, "image/png")
        val staged = withTimeout(10_000) { c.state.first { it.pendingLogo != null && it.busy == null } }.pendingLogo!!
        assertEquals(1, reads)
        assertTrue(staged.readBytes().contentEquals(png))
        c.createProject(meta(), aiAssist = false)
        val created = withTimeout(10_000) { c.state.first { it.snapshot != null && it.busy == null } }
        val id = created.projectId!!
        val logoId = created.snapshot!!.meta.logoAssetId
        assertNotNull("logo not attached to the new project", logoId)
        assertNull("the staged copy is used once", c.state.value.pendingLogo)
        assertFalse(staged.exists())
        scope1.coroutineContext[Job]!!.cancelAndJoin()
        db1.close()

        // Closed and reopened: the logo reference and its file are still there.
        db1 = open()
        r1 = ThesisRepository(db1, folder, imageInspector = inspector)
        scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        c = controller(db1, r1, tmp.newFolder("logo-preview2"), scope1)
        c.openProject(id)
        val reopened = withTimeout(10_000) { c.state.first { it.snapshot != null && it.busy == null } }
        assertEquals(logoId, reopened.snapshot!!.meta.logoAssetId)
        val asset = r1.assets(id).single { it.id == logoId }
        assertTrue(File(asset.path).readBytes().contentEquals(png))

        // In the Word file: the original image bytes on the cover.
        c.export("DOCX")
        val docx = withTimeout(20_000) { c.state.first { it.lastExport != null && it.busy == null } }.lastExport!!.document
        ZipFile(File(docx.filePath)).use { z ->
            val media = z.entries().toList().filter { it.name.startsWith("word/media/") }
            assertTrue("no image in the Word file", media.any { z.getInputStream(it).readBytes().contentEquals(png) })
            assertTrue(z.getInputStream(z.getEntry("word/document.xml")).bufferedReader().readText().contains("<w:drawing>"))
        }

        // Removed: the cover has no logo any more (no empty placeholder).
        c.removeLogo()
        withTimeout(10_000) { c.state.first { it.busy == null && it.snapshot?.meta?.logoAssetId == null } }
        assertNull(r1.snapshot(id).meta.logoAssetId)
        scope1.coroutineContext[Job]!!.cancelAndJoin()
        db1.close()
    }

    /** Cancelling the form without saving it drops the picked logo. */
    @Test
    fun pendingLogo_clearedWhenRemoved(): Unit = runBlocking {
        controller.stagePendingLogo({ png.inputStream() }, "image/png")
        val staged = withTimeout(10_000) { controller.state.first { it.pendingLogo != null && it.busy == null } }.pendingLogo!!
        controller.clearPendingLogo()
        assertNull(controller.state.value.pendingLogo)
        withTimeout(5_000) { while (staged.exists()) delay(20) }
    }
}
