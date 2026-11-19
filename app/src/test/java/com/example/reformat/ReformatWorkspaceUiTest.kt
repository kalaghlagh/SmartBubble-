package com.example.reformat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.agent.office.BlockType
import com.example.agent.office.DocBlock
import com.example.data.model.TemplateEntity
import com.example.template.domain.TemplateProfile
import com.example.ui.screens.ReformatWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Reformatting screen with the real string resources: Insert File and Image, the source card,
 * editing (unsaved state, uncertain readings), the Formatting dropdown (Keep Source Formatting and
 * Library templates), Create (PDF or DOC), the preview and errors; in English, French and Arabic,
 * at 320dp, in landscape and at font scale 2.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReformatWorkspaceUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val blocks = listOf(
        DocBlock(0, 1, BlockType.HEADING, "Rapport annuel", false, bold = true, paragraphIndex = 0),
        DocBlock(1, 1, BlockType.PARAGRAPH, "طلب ترخيص رقم REF-2026-015", true, paragraphIndex = 1),
        DocBlock(2, 1, BlockType.PARAGRAPH, "Date : 3O/10/2026", false, uncertain = true, paragraphIndex = 2),
        DocBlock(3, 1, BlockType.TABLE, "Région | Ventes", false, rows = listOf(listOf("Région", "Ventes"), listOf("الشمال", "120")))
    )
    private val templates = listOf(TemplateEntity("tpl-1", "Official Letter", "DOCX", "/x.docx", TemplateProfile.defaultProfile().toJson()))
    private fun loaded(kind: SourceKind = SourceKind.IMAGE) = ReformatState(
        source = WorkSource("scan.png", kind, 1, kind != SourceKind.DOCX, "sha", "/w/scan.png"),
        blocks = blocks.map { WorkBlock(it) }
    )

    private class Calls {
        var insert = 0; var image = 0; var created: ExportFormat? = null; var formatting: String? = "unset"; var saved = 0; var removed = 0; var reset = 0
    }

    private fun show(initial: ReformatState, calls: Calls = Calls()): Calls {
        compose.setContent {
            var s by androidx.compose.runtime.remember { mutableStateOf(initial) }
            ReformatWorkspace(
                state = s, templates = templates,
                onInsertFile = { calls.insert++ }, onImage = { calls.image++ },
                onEditText = { i, t -> s = s.copy(blocks = s.blocks.map { if (it.block.index == i) it.copy(text = t) else it }) },
                onEditCell = { _, _, _, _ -> },
                onReset = { calls.reset++; s = s.copy(blocks = s.blocks.map { WorkBlock(it.block) }) },
                onRemoveSource = { calls.removed++; s = ReformatState() },
                onFormatting = { calls.formatting = it; s = s.copy(templateId = it) },
                onCreate = { calls.created = it },
                onClosePreview = { s = s.copy(preview = null) },
                onSave = { calls.saved++; s = s.copy(preview = s.preview?.copy(savedDocumentId = "doc-9")) },
                onOpenSaved = {},
                onDismissError = { s = s.copy(error = null) }
            )
        }
        return calls
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("reformat_list").performScrollToNode(hasTestTag(tag))

    @Test
    @Config(qualifiers = "en-w320dp-h640dp")
    fun empty_insertFileAndImage() {
        val c = show(ReformatState())
        compose.onNodeWithTag("reformat_insert_file").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reformat_image").assertIsDisplayed().performClick()
        assertEquals(1, c.insert); assertEquals(1, c.image)
        assertTrue(compose.onAllNodes(hasTestTag("reformat_create")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "en-w320dp-h640dp")
    fun editing_marksUnsaved_uncertainShown_resetAndCreateDoc() {
        val c = show(loaded())
        compose.onNodeWithTag("reformat_uncertain").assertIsDisplayed()
        compose.onNodeWithTag("reformat_edit_state").assertTextContains(ctx.getString(R.string.reformat_no_edits))
        scrollTo("reformat_block_2")
        compose.onNodeWithTag("reformat_block_2").performTextReplacement("Date : 30/10/2026")
        compose.waitForIdle()
        compose.onNodeWithTag("reformat_edit_state").assertTextContains(ctx.getString(R.string.reformat_unsaved_edits))
        compose.onNodeWithTag("reformat_create").performClick()
        compose.onNodeWithTag("reformat_create_docx").assertIsDisplayed().performClick()
        assertEquals(ExportFormat.DOCX, c.created)
        scrollTo("reformat_reset")
        compose.onNodeWithTag("reformat_reset").performClick()
        assertEquals(1, c.reset)
    }

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp")
    fun formatting_keepSourceByDefault_thenALibraryTemplate_french() {
        val c = show(loaded(SourceKind.DOCX))
        scrollTo("reformat_formatting_trigger")
        compose.onNodeWithTag("reformat_formatting_trigger").assertTextContains(ctx.getString(R.string.reformat_keep_source))
        compose.onNodeWithTag("reformat_formatting_trigger").performClick()
        scrollTo("reformat_format_tpl-1")
        compose.onNodeWithTag("reformat_format_tpl-1").performClick()
        compose.waitForIdle()
        assertEquals("tpl-1", c.formatting)
        scrollTo("reformat_formatting_trigger")
        compose.onNodeWithTag("reformat_formatting_trigger").assertTextContains("Official Letter")
        compose.onNodeWithTag("reformat_create").performClick()
        compose.onNodeWithTag("reformat_create_pdf").performClick()
        assertEquals(ExportFormat.PDF, c.created)
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h640dp")
    fun arabic_rtl_previewSaveOnce_andErrorText() {
        val preview = ExportPreview("/w/out.docx", ExportFormat.DOCX, null, "طلب ترخيص رقم REF-2026-015", emptyList(), listOf("reconstructed"))
        var direction: LayoutDirection? = null
        val c = Calls()
        compose.setContent {
            direction = LocalLayoutDirection.current
            var s by androidx.compose.runtime.remember { mutableStateOf(loaded().copy(preview = preview)) }
            ReformatWorkspace(
                state = s, templates = templates, onInsertFile = {}, onImage = {}, onEditText = { _, _ -> }, onEditCell = { _, _, _, _ -> },
                onReset = {}, onRemoveSource = {}, onFormatting = {}, onCreate = {}, onClosePreview = { s = s.copy(preview = null) },
                onSave = { c.saved++; s = s.copy(preview = s.preview?.copy(savedDocumentId = "doc-9")) }, onOpenSaved = {},
                onDismissError = { s = s.copy(error = null) }
            )
        }
        assertEquals(LayoutDirection.Rtl, direction)
        compose.onNodeWithTag("reformat_preview").assertIsDisplayed()
        compose.onNodeWithTag("reformat_note_reconstructed", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("reformat_save").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("reformat_saved", useUnmergedTree = true).assertExists()
        assertTrue(compose.onAllNodes(hasTestTag("reformat_save")).fetchSemanticsNodes().isEmpty())
        assertEquals(1, c.saved)
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h640dp")
    fun error_isLocalized_andDismissed() {
        show(loaded().copy(error = "template_missing"))
        compose.onNodeWithTag("reformat_error").assertIsDisplayed()
        compose.onNode(hasText(ctx.getString(R.string.reformat_error_template_missing))).assertIsDisplayed()
        compose.onNode(hasText(ctx.getString(R.string.action_ok))).performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasTestTag("reformat_error")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    @Config(qualifiers = "en-w640dp-h320dp-land")
    fun landscape_createReachable() {
        show(loaded())
        compose.onNodeWithTag("reformat_create").assertIsDisplayed()
        compose.onNodeWithTag("reformat_insert_file").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp", fontScale = 2.0f)
    fun fontScale2_french_allControlsReachable() {
        show(loaded())
        compose.onNodeWithTag("reformat_create").assertIsDisplayed()
        for (tag in listOf("reformat_insert_file", "reformat_image", "reformat_remove_source", "reformat_formatting_trigger", "reformat_block_1", "reformat_table_3")) {
            scrollTo(tag)
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
    }
}
