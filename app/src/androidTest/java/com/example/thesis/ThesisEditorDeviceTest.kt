package com.example.thesis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import com.example.diagnostics.CrashLog
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisMeta
import com.example.ui.SmartBubbleViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Section editor on a real Android runtime (emulator in CI): every formatting button and Paste
 * Text, with the selection made by a real long press (selection handles and the floating text
 * toolbar are showing, as on a phone), with a caret only, after typing with key events, with
 * Arabic text, and with every kind of clipboard content. CI keeps the logcat of this run and the
 * screenshots written to the app's external files ("thesis-device").
 */
@RunWith(AndroidJUnit4::class)
class ThesisEditorDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm: SmartBubbleViewModel get() = ViewModelProvider(compose.activity)[SmartBubbleViewModel::class.java]
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val text: String get() = vm.thesis.state.value.draft!!.text

    @Before
    fun noOldCrashDialog() = CrashLog.clear(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun diagnostics(): String = buildString {
        val st = vm.thesis.state.value
        append("screen=${st.screen} busy=${st.busy} draft=${st.draft?.text?.replace("\n", "\\n")?.take(300)}\n")
        try { append(compose.onAllNodes(isRoot()).printToString(maxDepth = 30)) } catch (e: Throwable) { append("tree unavailable: $e") }
    }

    private fun await(step: String, timeout: Long = 20_000, condition: () -> Boolean) {
        try { compose.waitUntil(timeout) { condition() } } catch (e: Throwable) { throw AssertionError("Timed out: $step\n" + diagnostics(), e) }
    }

    private fun tap(tag: String) {
        await("node $tag") { exists(tag) }
        val containers = compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasTestTag(tag)))
        repeat(containers.fetchSemanticsNodes().size) { i -> try { containers[i].performScrollToNode(hasTestTag(tag)) } catch (e: Throwable) { } }
        val node = compose.onNodeWithTag(tag)
        try { node.performScrollTo() } catch (e: AssertionError) { }
        node.assertIsDisplayed()
        node.performClick()
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val dir = File(context.getExternalFilesDir(null), "thesis-device").apply { mkdirs() }
        // Whole screen (selection handles and the text toolbar are separate windows, so not onRoot()).
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun setClipNoWait(clip: ClipData?) {
        compose.runOnUiThread {
            if (clip != null) clipboard().setPrimaryClip(clip)
            else if (Build.VERSION.SDK_INT >= 28) clipboard().clearPrimaryClip()
        }
        compose.waitForIdle()
    }

    /**
     * Copying shows Android's "copied" preview, a separate window that takes the focus for a few
     * seconds; Compose test input bypasses the window manager, so it would not give the focus
     * back as a finger does. Wait for the app window to have the focus again.
     */
    private fun awaitWindowFocus(step: String) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!compose.activity.hasWindowFocus()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("app window has no focus: $step")
            Thread.sleep(100)
        }
        compose.waitForIdle()
    }

    private fun setClip(clip: ClipData?) {
        setClipNoWait(clip)
        awaitWindowFocus("after setting the clipboard")
    }

    /** A tap through the window manager (as a finger): it also gives the focus to the app window. */
    private fun systemTap(tag: String) {
        await("node $tag") { exists(tag) }
        try { compose.onNodeWithTag(tag).performScrollTo() } catch (e: AssertionError) { }
        compose.waitForIdle()
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val loc = IntArray(2)
        compose.runOnUiThread { compose.activity.window.decorView.findViewById<android.view.View>(android.R.id.content).getLocationOnScreen(loc) }
        val x = loc[0] + b.center.x
        val y = loc[1] + b.center.y
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val t0 = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, t: Long) = android.view.MotionEvent.obtain(t0, t, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
        ui.injectInputEvent(ev(android.view.MotionEvent.ACTION_DOWN, t0), true)
        ui.injectInputEvent(ev(android.view.MotionEvent.ACTION_UP, t0 + 60), true)
        compose.waitForIdle()
    }

    /** Thesis tab, a new project, its Introduction open in the section editor. */
    private fun openEditor() {
        await("app started") { exists("nav_thesis") }
        vm.setAppLanguage("en")
        await("english") { compose.onAllNodes(hasTestTag("nav_thesis") and hasText("Thesis")).fetchSemanticsNodes().isNotEmpty() }
        tap("nav_thesis")
        vm.thesis.createProject(ThesisMeta("Device editor test", AcademicLevel.MASTER, "en", studentName = "Test Student"), aiAssist = false)
        await("project created") { vm.thesis.state.value.snapshot != null && vm.thesis.state.value.busy == null }
        val intro = vm.thesis.state.value.snapshot!!.sections.first { it.kind == SectionKind.INTRODUCTION }.id
        compose.runOnUiThread { vm.thesis.selectSection(intro) }
        await("editor") { exists("thesis_editor_text") && vm.thesis.state.value.draft != null }
    }

    private fun field() = compose.onNodeWithTag("thesis_editor_text")

    /** A real long press on the first word of the field: selection handles and text toolbar show. */
    private fun longPressFirstWord() {
        field().performScrollTo()
        field().performTouchInput { longClick(Offset(36.dp.toPx(), 30.dp.toPx())) }
        compose.waitForIdle()
    }

    /** A real tap in the text: caret (and its handle) at that place. */
    private fun tapInText() {
        field().performScrollTo()
        field().performTouchInput { click(Offset(60.dp.toPx(), 30.dp.toPx())) }
        compose.waitForIdle()
    }

    // Heading, alignment and list markers at the start of a line are structure, not words.
    private val prefix = Regex("""(?m)^(#{1,6}\s+)?([-•*]\s+|\d{1,3}[.)]\s+)?(\{(center|end|justify)\}\s?)?([-•*]\s+|\d{1,3}[.)]\s+)?""")
    private fun words(t: String) = Regex("[\\p{L}\\p{N}]+").findAll(prefix.replace(t, "")).map { it.value }.toList()

    /** Runs a toolbar button and checks that no word of the text was lost. */
    private fun press(tag: String, before: () -> Unit) {
        val wordsBefore = words(text)
        before()
        tap(tag)
        val after = text
        assertTrue("$tag lost text: before=$wordsBefore after=$after", words(after).containsAll(wordsBefore))
        // Every marker is read as formatting: no stray "*" or "_" (the sample texts have none).
        val plain = com.example.thesis.core.InlineFormat.plain(prefix.replace(after, ""))
        assertTrue("$tag left stray markers: $after", !plain.contains('*') && !plain.contains('_'))
    }

    private val formatButtons = listOf(
        "thesis_fmt_bold", "thesis_fmt_italic", "thesis_fmt_underline", "thesis_fmt_bullets", "thesis_fmt_numbers",
        "thesis_align_start", "thesis_align_center", "thesis_align_end", "thesis_align_justify"
    )

    @Test
    fun everyFormattingButton_withLongPressSelection() {
        openEditor()
        field().performTextInput("Groundwater quality matters for the region.\nSecond line of the section.")
        compose.waitForIdle()
        shot("01-typed")
        longPressFirstWord()
        shot("02-long-press-selection")
        for (tag in formatButtons) press(tag) { longPressFirstWord() }
        shot("03-after-format-buttons")
        for (level in listOf(1, 2, 3, 0)) press("thesis_heading_$level") { longPressFirstWord(); tap("thesis_fmt_heading") }
        tap("thesis_undo"); tap("thesis_redo")
        shot("04-after-headings-undo-redo")
        assertTrue("text lost: $text", text.contains("Groundwater"))
    }

    @Test
    fun everyFormattingButton_withCaretOnly_andEmptyField() {
        openEditor()
        // Empty field, nothing selected.
        for (tag in formatButtons) press(tag) { }
        field().performTextInput("Caret test paragraph.")
        compose.waitForIdle()
        for (tag in formatButtons) press(tag) { tapInText() }
        for (level in listOf(1, 2, 3, 0)) press("thesis_heading_$level") { tapInText(); tap("thesis_fmt_heading") }
        shot("05-caret-only")
    }

    @Test
    fun formatting_afterTypingWithKeyEvents() {
        openEditor()
        tapInText()
        awaitWindowFocus("before typing with key events")
        InstrumentationRegistry.getInstrumentation().sendStringSync("typed with keys")
        compose.waitForIdle()
        await("key text") { text.contains("typed with keys") }
        press("thesis_fmt_bold") { }
        press("thesis_fmt_italic") { longPressFirstWord() }
        InstrumentationRegistry.getInstrumentation().sendStringSync(" more")
        compose.waitForIdle()
        press("thesis_fmt_underline") { longPressFirstWord() }
        shot("06-key-events")
    }

    @Test
    fun arabicRightToLeft_formatAndPaste() {
        openEditor()
        field().performTextInput("المياه الجوفية مصدر أساسي للشرب.\nسطر ثانٍ.")
        compose.waitForIdle()
        for (tag in listOf("thesis_fmt_bold", "thesis_fmt_italic", "thesis_fmt_underline", "thesis_align_end", "thesis_fmt_bullets")) press(tag) { longPressFirstWord() }
        setClip(ClipData.newPlainText("ar", "نص ملصوق بالعربية\nمع سطر ثانٍ و emoji 🌍 et français é"))
        press("thesis_paste_text") { longPressFirstWord() }
        assertTrue("Arabic paste missing: $text", text.contains("نص ملصوق بالعربية\nمع سطر ثانٍ و emoji 🌍 et français é"))
        shot("07-arabic")
    }

    @Test
    fun pasteRightAfterCopying_whileAndroidShowsTheCopiedPreview() {
        // As on a phone: copy, then tap Paste Text at once. The "copied" preview window still has
        // the focus when the tap arrives, so Android refuses the clipboard read at that moment;
        // the editor waits for the focus and pastes then.
        openEditor()
        field().performTextInput("Before.")
        compose.waitForIdle()
        setClipNoWait(ClipData.newPlainText("t", "Pasted right after copying"))
        systemTap("thesis_paste_text")
        await("pasted after the focus came back", 20_000) { text.contains("Pasted right after copying") }
        assertTrue("text lost: $text", text.contains("Before."))
    }

    @Test
    fun leavingTheApp_savesUnsavedText() {
        openEditor()
        field().performTextInput("Typed before leaving the app.")
        compose.waitForIdle()
        assertTrue(vm.thesis.state.value.draft!!.dirty)
        // Another app comes to the front (as when copying text for Paste Text): onStop runs.
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        // Plain polling: the activity is stopped, so the Compose test clock is not used here.
        val thesis = vm.thesis
        val deadline = System.currentTimeMillis() + 20_000
        while (!(thesis.state.value.busy == null && thesis.state.value.draft?.dirty == false)) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("not saved in background: busy=${thesis.state.value.busy} dirty=${thesis.state.value.draft?.dirty}")
            Thread.sleep(50)
        }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        assertTrue(text.contains("Typed before leaving the app."))
    }

    @Test
    fun pasteText_everyKindOfClipboard() {
        openEditor()
        field().performTextInput("Existing paragraph.")
        compose.waitForIdle()

        // Plain, multiline, Unicode.
        setClip(ClipData.newPlainText("t", "Line one\nLine two — ünïcödé ✓"))
        press("thesis_paste_text") { }
        assertTrue("multiline paste missing: $text", text.contains("Line one\nLine two — ünïcödé ✓"))

        // With a selection made by a long press (selected text is kept).
        setClip(ClipData.newPlainText("t", "After selection"))
        press("thesis_paste_text") { longPressFirstWord() }
        assertTrue("paste after selection missing: $text", text.contains("After selection"))

        // Rich text (HTML).
        setClip(ClipData.newHtmlText("h", "Rich bold text", "<b>Rich</b> bold text"))
        press("thesis_paste_text") { tapInText() }
        assertTrue("HTML paste missing: $text", text.contains("Rich bold text"))

        // A link to content the app cannot read, and an Intent: nothing crashes, nothing is lost.
        setClip(ClipData.newRawUri("u", Uri.parse("content://com.example.nonexistent.provider/item/1")))
        press("thesis_paste_text") { }
        setClip(ClipData.newIntent("i", Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org"))))
        press("thesis_paste_text") { }

        // Blank and empty clipboard: a message, the text is unchanged.
        val before = text
        setClip(ClipData.newPlainText("blank", "   "))
        tap("thesis_paste_text")
        assertEquals(before, text)
        setClip(null)
        tap("thesis_paste_text")
        assertEquals(before, text)
        shot("08-paste")

        // Saved and still there after the activity is recreated.
        tap("thesis_save_changes")
        await("saved") { vm.thesis.state.value.busy == null && vm.thesis.state.value.draft?.dirty == false }
        val saved = text
        compose.activityRule.scenario.recreate()
        await("editor after recreate") { exists("thesis_editor_text") && vm.thesis.state.value.draft != null }
        assertEquals(saved, text)
    }
}
