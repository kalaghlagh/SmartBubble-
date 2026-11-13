package com.example.thesis

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import com.example.MainActivity
import com.example.thesis.model.SectionKind
import com.example.ui.AppNavTab
import com.example.ui.SmartBubbleViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * The whole app (MainActivity with its real ViewModel, database and files) on Robolectric:
 * Thesis Builder as its own main destination, the complete user flow, and the other
 * destinations still working. Screenshots go to app/build/thesis-screenshots (uploaded by CI).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
class ThesisAppFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val shots = File("build/thesis-screenshots").apply { mkdirs() }

    private val vm: SmartBubbleViewModel get() = ViewModelProvider(compose.activity)[SmartBubbleViewModel::class.java]

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun textExists(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun diagnostics(): String = buildString {
        val st = vm.thesis.state.value
        append("tab=${vm.currentTab.value} screen=${st.screen} busy=${st.busy} project=${st.projectId}\n")
        try { append(compose.onAllNodes(isRoot()).printToString(maxDepth = 40)) } catch (e: Throwable) { append("tree unavailable: $e") }
    }

    private fun await(step: String, timeout: Long = 15_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { condition() } } catch (e: Throwable) { throw AssertionError("Timed out: $step\n" + diagnostics(), e) }
    }

    private fun tap(tag: String) {
        await("node $tag") { exists(tag) }
        val containers = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasTestTag(tag)))
        repeat(containers.fetchSemanticsNodes().size) { i -> try { containers[i].performScrollToNode(hasTestTag(tag)) } catch (e: Throwable) { } }
        val node = compose.onNodeWithTag(tag)
        try { node.performScrollTo() } catch (e: AssertionError) { }
        try { node.assertIsDisplayed() } catch (e: AssertionError) { throw AssertionError("Node '$tag' is not displayed\n" + diagnostics(), e) }
        node.performClick()
        compose.waitForIdle()
    }

    private fun type(tag: String, text: String) {
        await("field $tag") { exists(tag) }
        val node = compose.onNodeWithTag(tag)
        try { node.performScrollTo() } catch (e: AssertionError) { }
        node.performTextInput(text)
        compose.waitForIdle()
    }

    /**
     * PNG of the app window. The activity's view is drawn into a bitmap (software canvas):
     * captureToImage() waits for a frame that Robolectric's paused main looper never draws
     * (run #30: forceRedraw timed out after 2000 ms).
     */
    private fun shot(name: String) {
        compose.waitForIdle()
        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            val b = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(b))
            bitmap = b
        }
        FileOutputStream(File(shots, "$name.png")).use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun back() {
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun thesisBuilder_isItsOwnDestination_completeFlow_andOtherDestinationsStillWork() {
        await("app started") { exists("nav_chat") }
        // Chat no longer hosts Thesis Builder; it is a main destination.
        assertTrue(compose.onAllNodes(hasTestTag("chat_thesis_button")).fetchSemanticsNodes().isEmpty())
        assertTrue(exists("chat_input_field"))
        tap("nav_thesis")
        assertEquals(AppNavTab.THESIS, vm.currentTab.value)
        await("thesis home") { exists("thesis_new") && exists("thesis_empty_state") }
        await("thesis home title") { textExists("Thesis Builder") }
        shot("1-thesis-home")

        // New Thesis form: Create, Save Draft and Cancel are visible.
        tap("thesis_new")
        await("form") { exists("thesis_field_title") }
        compose.onNodeWithTag("thesis_save_meta").assertIsDisplayed()
        compose.onNodeWithTag("thesis_save_draft").assertIsDisplayed()
        compose.onNodeWithTag("thesis_cancel_meta").assertIsDisplayed()
        type("thesis_field_title", "Geological Study of Mineral Deposits")
        type("thesis_field_student", "Ahmed Benali")
        type("thesis_field_university", "University of Algiers")
        type("thesis_field_faculty", "Faculty of Earth Sciences")
        type("thesis_field_department", "Geology")
        type("thesis_field_supervisor", "Dr. Mohamed Salah")
        type("thesis_field_year", "2025-2026")
        compose.onNodeWithTag("thesis_field_title").performScrollTo()
        shot("2-new-thesis-form")
        tap("thesis_save_draft")
        await("draft saved") { vm.thesis.state.value.formDraft?.title == "Geological Study of Mineral Deposits" && vm.thesis.state.value.busy == null }
        // Cancel keeps the draft and creates nothing; it is resumed from home.
        tap("thesis_cancel_meta")
        await("draft card") { exists("thesis_form_draft") }
        assertTrue(vm.thesis.projects.value.isEmpty())
        tap("thesis_form_draft_continue")
        await("form restored") { exists("thesis_form_draft_restored") }
        tap("thesis_save_meta")
        await("workspace") { exists("thesis_structure") && vm.thesis.state.value.snapshot != null }
        val projectId = vm.thesis.state.value.projectId!!
        assertEquals(1, vm.thesis.projects.value.size)
        shot("3-workspace")

        // Section editor: write, format, save.
        val snapshot = vm.thesis.state.value.snapshot!!
        val intro = snapshot.sections.first { it.kind == SectionKind.INTRODUCTION }.id
        compose.onNodeWithTag("thesis_structure").performScrollToNode(hasTestTag("thesis_edit_$intro"))
        tap("thesis_edit_$intro")
        await("editor") { exists("thesis_editor_text") && vm.thesis.state.value.draft != null }
        type("thesis_editor_text", "Mineral deposits of the Hoggar are studied in this thesis.")
        tap("thesis_page_break")
        type("thesis_editor_text", "\n\nThe study area covers three sites.")
        shot("4-section-editor")
        tap("thesis_save_changes")
        await("saved") { vm.thesis.state.value.busy == null && vm.thesis.state.value.draft?.dirty == false }
        val saved = vm.thesis.state.value.snapshot!!.elementsOf(intro)
        assertEquals("Mineral deposits of the Hoggar are studied in this thesis.", saved.first().text)
        assertTrue(saved.last().data.pageBreakBefore)

        // Back goes up inside Thesis Builder: editor -> workspace -> home -> leaves to Chat.
        back(); await("back to workspace") { vm.thesis.state.value.screen == ThesisScreen.PROJECT }
        back(); await("back to home") { vm.thesis.state.value.screen == ThesisScreen.HOME }
        back(); await("left Thesis Builder") { vm.currentTab.value == AppNavTab.CHAT }

        // Close and reopen the activity: the project and its content are still there.
        compose.activityRule.scenario.recreate()
        await("app restarted") { exists("nav_thesis") }
        tap("nav_thesis")
        await("project listed") { exists("thesis_continue_$projectId") }
        tap("thesis_continue_$projectId")
        await("workspace reopened") { exists("thesis_structure") && vm.thesis.state.value.snapshot?.elementsOf(intro)?.size == 2 }

        // Export DOCX from the preview screen: a real file in the Library with the content.
        tap("thesis_workspace_menu")
        tap("thesis_menu_preview")
        await("preview screen") { exists("thesis_export_docx") && vm.thesis.state.value.busy == null }
        tap("thesis_export_docx")
        await("exported", 30_000) { vm.thesis.state.value.lastExport != null }
        val doc = vm.thesis.state.value.lastExport!!.document
        assertTrue(File(doc.filePath).isFile)
        ZipFile(File(doc.filePath)).use { z ->
            val xml = z.getInputStream(z.getEntry("word/document.xml")).bufferedReader().readText()
            assertTrue(xml.contains("Mineral deposits of the Hoggar are studied in this thesis."))
            assertTrue(xml.contains("<w:pageBreakBefore/>"))
            assertTrue(xml.contains("Geological Study of Mineral Deposits"))
        }
        await("save to device offered") { exists("thesis_export_save_device") }

        // The other destinations still work.
        tap("nav_templates"); await("templates") { exists("upload_template_button") }
        tap("nav_library"); compose.waitForIdle()
        tap("nav_settings"); compose.waitForIdle()
        tap("nav_chat"); await("chat") { exists("chat_input_field") }
    }

    @Test
    fun thesisBuilder_inArabicAndFrench_isTranslated_andRightToLeftInArabic() {
        await("app started") { exists("nav_chat") }
        vm.setAppLanguage("ar")
        await("arabic") { textExists("المذكرة") }
        tap("nav_thesis")
        await("arabic thesis home") { textExists("مذكرة جديدة") }
        // Right to left: the first navigation item (Chat) is laid out to the right of the last (Settings).
        val chat = compose.onNodeWithTag("nav_chat").fetchSemanticsNode().boundsInRoot
        val settings = compose.onNodeWithTag("nav_settings").fetchSemanticsNode().boundsInRoot
        assertTrue("RTL expected: chat=$chat settings=$settings", chat.left > settings.left)
        shot("5-thesis-home-arabic")
        tap("thesis_new")
        await("arabic form") { textExists("إنشاء المذكرة") }
        shot("6-new-thesis-form-arabic")
        vm.setAppLanguage("fr")
        await("french") { textExists("Mémoire") && textExists("Créer le mémoire") }
        shot("7-new-thesis-form-french")
    }
}
