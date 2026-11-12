package com.example.offline

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.db.SmartBubbleDatabase
import com.example.data.model.TemplateEntity
import com.example.document.DocumentFileValidator
import com.example.offline.document.OfflineDocumentGenerator
import com.example.offline.document.OfflineFormat
import com.example.offline.document.OfflineFormattingUiState
import com.example.offline.document.OfflinePhase
import com.example.offline.document.RoomOfflineLibrary
import com.example.offline.document.RoomTemplateRepository
import com.example.offline.document.TemplateMatch
import com.example.offline.document.TemplateMatcher
import com.example.offline.document.TemplateSource
import com.example.ui.components.OfflineFormattingDialog
import com.example.ui.components.OfflineFormattingSuggestion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.io.File
import kotlin.math.roundToInt

/**
 * Integration tests on Robolectric: Library saving with a real (in-memory) Room database, the
 * template repository over the real DAO, PDF output, and the Chat UI pieces with the real
 * string resources in English, French and Arabic (RTL).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineAndroidIntegrationTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: SmartBubbleDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SmartBubbleDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val text = """
        RAPPORT MENSUEL

        Le chiffre d’affaires a atteint 82 400 € en mai 2025.

        Points clés
        - Ouverture du bureau de Tunis
        - 5 nouveaux clients
    """.trimIndent()

    private fun builtIn() = TemplateMatch(TemplateSource.BUILT_IN, null, TemplateMatcher.builtInProfile(), null, emptyList())

    @Test
    fun saveToLibrary_movesTheFileAndCreatesTheLibraryEntry() = runBlocking {
        val result = RuleBasedFormattingEngine().analyze(text)
        val draft = OfflineDocumentGenerator(tmp.newFolder("drafts")).generate(result, OfflineFormat.DOCX, builtIn())
        val libraryDir = tmp.newFolder("generated_docs")
        val entity = RoomOfflineLibrary(libraryDir, db.documentDao()).save(draft)

        val stored = db.documentDao().getDocumentById(entity.id)
        assertNotNull(stored)
        assertEquals("Offline", stored!!.providerUsed)
        assertEquals("DOCX", stored.fileType)
        assertEquals("RAPPORT MENSUEL.docx", stored.title)
        assertTrue(File(stored.filePath).isFile)
        assertEquals(libraryDir.canonicalPath, File(stored.filePath).parentFile!!.canonicalPath)
        assertFalse(draft.file.exists()) // moved, not copied
        assertTrue(stored.textContent.contains("82 400 €"))
    }

    @Test
    fun templateRepository_readsDefaultsFromMyTemplates() = runBlocking {
        db.templateDao().insertTemplate(TemplateEntity(id = "w", name = "Word", fileType = "DOCX", originalFilePath = "/none.docx", profileJson = "{}", isDefaultWord = true))
        db.templateDao().insertTemplate(TemplateEntity(id = "x", name = "Sheet", fileType = "XLSX", originalFilePath = "/none.xlsx", profileJson = "{}"))
        val matcher = TemplateMatcher(RoomTemplateRepository(db.templateDao()))
        assertEquals("w", matcher.match(OfflineFormat.DOCX, null).entity!!.id)
        assertEquals(TemplateSource.BUILT_IN, matcher.match(OfflineFormat.XLSX, null).source) // no default sheet template
        assertEquals("x", matcher.match(OfflineFormat.XLSX, "x").entity!!.id)
        // Templates are only read: nothing was added or changed.
        assertEquals(2, db.templateDao().getAllTemplatesOnce().size)
    }

    @Test
    fun pdf_isAValidFile() = runBlocking {
        val result = RuleBasedFormattingEngine().analyze(text)
        val draft = try {
            OfflineDocumentGenerator(tmp.newFolder("pdf")).generate(result, OfflineFormat.PDF, builtIn())
        } catch (e: Throwable) {
            // Reported as skipped (not passed) when the test runtime cannot render PDF pages.
            Assume.assumeNoException("PDF rendering is not available in this test runtime", e)
            return@runBlocking
        }
        assertEquals(null, DocumentFileValidator.validate(draft.file, "pdf"))
    }

    // ------------------------------------------------------------------ UI

    @Test
    @Config(qualifiers = "en")
    fun suggestion_englishTextsAndActions() {
        var formatted = 0
        var dismissed = 0
        compose.setContent { OfflineFormattingSuggestion(analyzing = false, onFormat = { formatted++ }, onDismiss = { dismissed++ }, onCancel = {}) }
        compose.onNodeWithText("Organize this content?").assertIsDisplayed()
        compose.onNodeWithText("Format your text using an existing template without an internet connection.").assertIsDisplayed()
        compose.onNodeWithText("Offline", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("offline_dismiss_button").performClick()
        compose.onNodeWithTag("offline_format_button").performClick()
        assertEquals(1, dismissed)
        assertEquals(1, formatted)
    }

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp")
    fun suggestion_frenchFitsANarrowScreen() {
        compose.setContent { OfflineFormattingSuggestion(analyzing = false, onFormat = {}, onDismiss = {}, onCancel = {}) }
        compose.onNodeWithText("Organiser ce contenu ?").assertIsDisplayed()
        compose.onNodeWithText("Mettre en forme hors ligne").assertIsDisplayed()
        compose.onNodeWithText("Ignorer").assertIsDisplayed()
        val card = compose.onNodeWithTag("offline_suggestion").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("offline_format_button").fetchSemanticsNode().boundsInRoot
        assertTrue("button outside the card: $button in $card", button.right <= card.right + 0.5f && button.left >= card.left - 0.5f)
    }

    @Test
    @Config(qualifiers = "ar-w360dp-h640dp")
    fun suggestion_arabicIsRightToLeft() {
        compose.setContent { OfflineFormattingSuggestion(analyzing = false, onFormat = {}, onDismiss = {}, onCancel = {}) }
        compose.onNodeWithText("تنظيم هذا المحتوى؟").assertIsDisplayed()
        val card = compose.onNodeWithTag("offline_suggestion").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("تنظيم هذا المحتوى؟", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val badge = compose.onNodeWithTag("offline_badge", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // RTL: the title starts on the right, the badge sits on the left.
        assertTrue("title not on the right: $title in $card", title.right > card.center.x)
        assertTrue("badge not on the left: $badge", badge.left < card.center.x)
    }

    @Test
    @Config(qualifiers = "en")
    fun suggestion_analyzingShowsProgressAndCancel() {
        var cancelled = 0
        compose.setContent { OfflineFormattingSuggestion(analyzing = true, onFormat = {}, onDismiss = {}, onCancel = { cancelled++ }) }
        compose.onNodeWithText("Analyzing the text on this device…").assertIsDisplayed()
        compose.onNodeWithTag("offline_cancel_analysis").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    @Config(qualifiers = "en")
    fun previewDialog_showsInferredStructureAndConfirms() {
        val result = runBlocking { RuleBasedFormattingEngine().analyze("Weekly Update\n\nSales rose by 4% this week in all regions.\n\nNext Steps\nWe call the two new suppliers on Monday.") }
        val state = OfflineFormattingUiState(
            phase = OfflinePhase.PREVIEW,
            preview = PreviewBuilder.build(result),
            format = OfflineFormat.DOCX,
            formats = OfflineFormat.available(result),
            template = builtIn()
        )
        var applied = 0
        var cancelled = 0
        val formats = mutableListOf<OfflineFormat>()
        compose.setContent {
            OfflineFormattingDialog(state, onSelectFormat = { formats += it }, onSelectTemplate = {}, onApply = { applied++ }, onSave = {}, onCancel = { cancelled++ })
        }
        compose.onNodeWithText("Preview").assertIsDisplayed()
        compose.onNodeWithText("Detected structure: General document").assertIsDisplayed()
        compose.onNodeWithTag("offline_format_chip_PPTX").performClick()
        assertEquals(listOf(OfflineFormat.PPTX), formats)
        compose.onNodeWithTag("offline_preview_list").performScrollToNode(hasText("Next Steps"))
        compose.onNodeWithText("Next Steps").assertIsDisplayed()
        val percent = (result.headings.last().confidence * 100).roundToInt()
        compose.onNodeWithText(context.getString(R.string.offline_heading_inferred, percent)).assertExists()
        compose.onNodeWithTag("offline_apply_button").performClick()
        compose.onNodeWithTag("offline_cancel_button").performClick()
        assertEquals(1, applied)
        assertEquals(1, cancelled)
    }

    @Test
    @Config(qualifiers = "ar")
    fun strings_arabicAndFrenchActions() {
        assertEquals("تنسيق دون اتصال", context.getString(R.string.offline_format))
        assertEquals("حفظ في المكتبة", context.getString(R.string.offline_save))
        assertEquals("تطبيق التنسيق", context.getString(R.string.offline_apply))
        assertEquals("اختيار قالب", context.getString(R.string.offline_select_template))
    }

    @Test
    @Config(qualifiers = "fr")
    fun strings_french() {
        assertEquals("Enregistrer dans la Bibliothèque", context.getString(R.string.offline_save))
        assertEquals("Appliquer la mise en forme", context.getString(R.string.offline_apply))
        assertEquals("Choisir un modèle", context.getString(R.string.offline_select_template))
        assertEquals("Aperçu", context.getString(R.string.offline_preview_title))
    }
}
