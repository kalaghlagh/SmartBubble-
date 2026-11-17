package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.diagnostics.CrashLog
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisMeta
import com.example.ui.AppNavTab
import com.example.ui.SmartBubbleViewModel
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The real soft keyboard on the emulator: the Chat composer and the Thesis editor stay above it
 * while typing several lines, the composer grows, and nothing is left behind when it closes.
 * Positions are compared with the keyboard (IME) inset the system reports for the window.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm: SmartBubbleViewModel get() = ViewModelProvider(compose.activity)[SmartBubbleViewModel::class.java]

    @Before
    fun noOldCrashDialog() = CrashLog.clear(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun await(step: String, timeout: Long = 20_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { condition() } } catch (e: Throwable) { throw AssertionError("Timed out: $step", e) }
    }

    /** Height of the window covered by the keyboard, in pixels (0 when it is closed). */
    private fun imeHeight(): Int {
        var h = 0
        compose.runOnUiThread {
            val insets = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            h = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        return h
    }

    private fun windowHeight(): Int {
        var h = 0
        compose.runOnUiThread { h = compose.activity.window.decorView.height }
        return h
    }

    private fun bottomOf(tag: String): Float = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.bottom
    private fun heightOf(tag: String): Float = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow.height

    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File(compose.activity.getExternalFilesDir(null), "app-device").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
        }
        await("keyboard closed") { imeHeight() == 0 }
        compose.waitForIdle()
    }

    /** The node's bottom edge is above the keyboard's top edge (with a 1 px tolerance). */
    private fun assertAboveKeyboard(tag: String) {
        val keyboardTop = windowHeight() - imeHeight()
        val bottom = bottomOf(tag)
        assertTrue("$tag bottom $bottom is under the keyboard (top $keyboardTop)", bottom <= keyboardTop + 1)
    }

    @Test
    fun chatComposer_staysAboveTheKeyboard_growsWithText_andNoGapAfterClosing() {
        await("app started") { exists("nav_chat") }
        vm.setAppLanguage("en")
        await("english") { compose.onAllNodes(hasTestTag("nav_chat") and hasText("Chat")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_chat").performClick()
        await("chat") { vm.currentTab.value == AppNavTab.CHAT && exists("chat_input_field") }
        val closedBottom = bottomOf("chat_input_field")
        compose.onNodeWithTag("chat_input_field").performClick()
        await("keyboard open") { imeHeight() > 0 }
        await("composer moved") { bottomOf("chat_input_field") <= windowHeight() - imeHeight() + 1 }
        assertAboveKeyboard("chat_input_field")
        val oneLine = heightOf("chat_input_field")
        compose.onNodeWithTag("chat_input_field").performTextInput("First line of a longer message\nsecond line\nthird line\nfourth line")
        compose.waitForIdle()
        assertAboveKeyboard("chat_input_field")
        assertTrue("the composer did not grow: $oneLine -> ${heightOf("chat_input_field")}", heightOf("chat_input_field") > oneLine + 20)
        // The send button stays reachable above the keyboard.
        assertAboveKeyboard("chat_send_button")
        shot("keyboard-chat-open")
        // Many more lines: the field stops growing and scrolls inside, still above the keyboard.
        compose.onNodeWithTag("chat_input_field").performTextInput((5..20).joinToString("") { "\nline $it" })
        compose.waitForIdle()
        assertAboveKeyboard("chat_input_field")
        hideKeyboard()
        // No blank area left: the composer is back where it was (it is taller now, so its bottom matches).
        val after = bottomOf("chat_input_field")
        assertTrue("composer bottom $after, before the keyboard $closedBottom", kotlin.math.abs(after - closedBottom) < 4f)
        // The text typed is still there.
        assertTrue(compose.onAllNodes(hasText("line 20", substring = true)).fetchSemanticsNodes().isNotEmpty())
        shot("keyboard-chat-closed")
    }

    @Test
    fun thesisEditor_staysAboveTheKeyboard_withALongText() {
        await("app started") { exists("nav_thesis") }
        vm.setAppLanguage("en")
        await("english") { compose.onAllNodes(hasTestTag("nav_thesis") and hasText("Thesis")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("nav_thesis").performClick()
        vm.thesis.createProject(ThesisMeta("Keyboard test", AcademicLevel.MASTER, "en", studentName = "Test Student"), aiAssist = false)
        await("project created") { vm.thesis.state.value.snapshot != null && vm.thesis.state.value.busy == null }
        val intro = vm.thesis.state.value.snapshot!!.sections.first { it.kind == SectionKind.INTRODUCTION }.id
        compose.runOnUiThread { vm.thesis.selectSection(intro) }
        await("editor") { exists("thesis_editor_text") && vm.thesis.state.value.draft != null }
        compose.onNodeWithTag("thesis_editor_text").performClick()
        await("keyboard open") { imeHeight() > 0 }
        await("editor above keyboard") { bottomOf("thesis_editor_text") <= windowHeight() - imeHeight() + 1 }
        // A long text (60 lines): the field keeps its place above the keyboard and scrolls inside.
        compose.onNodeWithTag("thesis_editor_text").performTextInput((1..60).joinToString("\n") { "Paragraph $it of a long introduction about groundwater quality." })
        compose.waitForIdle()
        await("still above keyboard") { bottomOf("thesis_editor_text") <= windowHeight() - imeHeight() + 1 }
        assertAboveKeyboard("thesis_editor_text")
        assertTrue("the editor is too small to write in: ${heightOf("thesis_editor_text")}", heightOf("thesis_editor_text") > 200f)
        // The formatting toolbar stays usable above the keyboard.
        assertAboveKeyboard("thesis_fmt_bold")
        shot("keyboard-thesis-open")
        hideKeyboard()
        // The text survives the keyboard closing (unsaved draft kept).
        assertTrue(vm.thesis.state.value.draft!!.text.contains("Paragraph 60 of a long introduction"))
        shot("keyboard-thesis-closed")
    }
}
