package com.example.agent.office

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.agent.AgentWorkflow
import com.example.agent.core.FileSource
import com.example.agent.core.LibraryRef
import com.example.ui.screens.AgentWelcome
import com.example.ui.screens.AgentWorkflowBar
import com.example.ui.screens.SourceDocumentPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The Agent Chat's three shortcuts, the shortcut bar and the source-document picker in English,
 * French and Arabic (right to left), at 320 dp with font scale 2 and in landscape: exactly three
 * cards, every control inside the window and large enough, each one calling its action.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentShortcutsTest {

    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun host(rtl: Boolean = false, fontScale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) { MaterialTheme { content() } }
        }
        compose.waitForIdle()
    }

    private fun shortcutTags() = compose.onAllNodes(SemanticsMatcher("shortcut") { n ->
        n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("agent_shortcut_") == true
    }).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }

    private fun inside(tag: String) {
        // Inside a scrolling column, scroll to it first (the bar has no scroll container).
        try { compose.onNodeWithTag(tag).performScrollTo() } catch (e: AssertionError) { }
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag outside the window: $b in $root", b.left >= root.left - 1 && b.right <= root.right + 1)
        assertTrue("$tag too small: $b", b.height >= 48f)
    }

    private fun welcome(rtl: Boolean = false, fontScale: Float = 1f) = host(rtl, fontScale) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            AgentWelcome(onShortcut = { calls += it.name })
        }
    }

    @Test
    @Config(qualifiers = "en-w360dp-h740dp")
    fun exactlyThreeShortcuts_eachStartsItsWorkflow() {
        welcome()
        assertEquals(listOf("agent_shortcut_reformat", "agent_shortcut_thesis", "agent_shortcut_template"), shortcutTags())
        listOf("agent_shortcut_reformat", "agent_shortcut_thesis", "agent_shortcut_template").forEach { inside(it); compose.onNodeWithTag(it).performClick() }
        assertEquals(listOf("REFORMAT", "THESIS", "TEMPLATE"), calls)
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h640dp")
    fun arabic_narrow_font2_cardsStacked_insideTheWindow() {
        welcome(rtl = true, fontScale = 2f)
        assertEquals(3, shortcutTags().size)
        listOf("agent_shortcut_reformat", "agent_shortcut_thesis", "agent_shortcut_template").forEach { inside(it) }
        val a = compose.onNodeWithTag("agent_shortcut_reformat").fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag("agent_shortcut_thesis").fetchSemanticsNode().boundsInRoot
        assertTrue("stacked on a narrow screen", b.top >= a.bottom)
    }

    @Test
    @Config(qualifiers = "fr-w740dp-h360dp-land")
    fun french_landscape_cardsSideBySide_equalHeights() {
        welcome()
        val tags = listOf("agent_shortcut_reformat", "agent_shortcut_thesis", "agent_shortcut_template")
        val boxes = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(boxes.toString(), boxes.zipWithNext().all { (x, y) -> abs(x.top - y.top) < 1f && abs(x.height - y.height) < 1f && y.left >= x.right })
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h640dp")
    fun workflowBar_rtl_font2_chooseSourceAndClose() {
        host(rtl = true, fontScale = 2f) {
            AgentWorkflowBar(AgentWorkflow.REFORMAT, LibraryRef("d", "طلب ترخيص.pdf", "PDF"), onChooseSource = { calls += "source" }, onChooseTemplate = { calls += "template" }, onClose = { calls += "close" })
        }
        inside("agent_workflow_choose_source")
        compose.onNodeWithTag("agent_workflow_source").assertIsDisplayed()
        compose.onNodeWithTag("agent_workflow_choose_source").performClick()
        inside("agent_workflow_close")
        compose.onNodeWithTag("agent_workflow_close").performClick()
        assertEquals(listOf("source", "close"), calls)
    }

    @Test
    @Config(qualifiers = "en-w360dp-h740dp")
    fun sourcePicker_phoneOrLibrary_neverTemplates() {
        val lib = listOf(LibraryRef("1", "Demande.pdf", "PDF", FileSource.LIBRARY), LibraryRef("2", "Lettre.docx", "DOCX", FileSource.LIBRARY))
        host { SourceDocumentPicker(lib, onFromDevice = { calls += "device" }, onPick = { calls += "pick:${it.id}" }, onDismiss = { calls += "dismiss" }) }
        compose.onNodeWithTag("source_from_device").assertIsDisplayed().performClick()
        compose.onNodeWithTag("source_library_2").assertIsDisplayed().performClick()
        compose.onNodeWithTag("source_picker_cancel").performClick()
        assertEquals(listOf("device", "pick:2", "dismiss"), calls)
    }
}
