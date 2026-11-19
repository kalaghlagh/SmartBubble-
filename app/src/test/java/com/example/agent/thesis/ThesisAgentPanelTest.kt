package com.example.agent.thesis

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.agent.core.AgentTask
import com.example.agent.core.FileSource
import com.example.agent.core.LibraryRef
import com.example.agent.core.StepStatus
import com.example.agent.core.TaskStatus
import com.example.ui.screens.AgentPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The approval card of a thesis change in the Agent panel: target thesis and section, position,
 * source, preview and warnings; Approve / Reject / Cancel; after completion Open and Undo. English,
 * French and Arabic (right to left), 320 dp and landscape, font scale 2: every control is reached.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThesisAgentPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val clicks = mutableListOf<String>()

    private fun task(status: TaskStatus, notActive: Boolean = false): AgentTask {
        val req = ThesisRequestParser.parse("Extract table 3 from Resources minérales.pdf into Chapter 2 of my thesis, page 14", listOf("Resources minérales.pdf"))!!
        var n = 0
        val planned = ThesisTaskPlanner.plan("t", "s", "req", req, LibraryRef("d1", "Resources minérales.pdf", "PDF", FileSource.LIBRARY), "fr", 0L) { "e${n++}" }
        val outputs = mapOf(
            "locate" to mapOf("projectId" to "p", "thesisTitle" to "Mémoire géologique", "sectionId" to "c2", "sectionTitle" to "Chapitre 2 : Résultats",
                "active" to (!notActive).toString(), "elements" to "4", "paragraphs" to "3", "revisionNumber" to "7"),
            "extract" to mapOf("title" to "Resources minérales.pdf", "pages" to "3", "content" to "{}", "preview" to "Minéral | Tonnes\nFer | 1 200\nZinc | 300",
                "uncertain" to "true", "tableUncertain" to "true", "sourceSha" to "x"),
            "prepare" to mapOf("plan" to "{}", "preview" to "Minéral | Tonnes\nFer | 1 200\nZinc | 300", "position" to "end:4", "rows" to "3", "columns" to "2"),
            "apply" to mapOf("projectId" to "p", "revisionId" to "r8", "revisionNumber" to "8", "previousRevisionId" to "r7", "previousRevisionNumber" to "7"),
            "check" to mapOf("outcome" to "COMPLETED", "issues" to "", "restored" to "")
        )
        val steps = planned.steps.map { s ->
            when {
                status == TaskStatus.WAITING_FOR_APPROVAL && s.id == "apply" -> s.copy(status = StepStatus.WAITING_FOR_APPROVAL)
                status == TaskStatus.WAITING_FOR_APPROVAL && s.id == "check" -> s
                else -> s.copy(status = StepStatus.COMPLETED, output = outputs.getValue(s.id))
            }
        }
        return planned.copy(status = status, steps = steps)
    }

    private fun show(task: AgentTask, rtl: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                MaterialTheme {
                    AgentPanel(
                        task = task, routing = null, sourceTitles = emptyMap(),
                        onPause = { clicks += "pause" }, onResume = { clicks += "resume" }, onCancel = { clicks += "cancel" },
                        onApprove = { clicks += "approve" }, onReject = { clicks += "reject" }, onDismiss = { clicks += "dismiss" },
                        onOpenThesis = { clicks += "open" }, onUndoThesis = { clicks += "undo" }
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun reachAndClick(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag outside the window: $b in $root", b.left >= root.left - 1 && b.right <= root.right + 1 && b.height >= 40f)
        compose.onNodeWithTag(tag).performClick()
    }

    private fun assertShown(tag: String) = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()

    @Test
    @Config(qualifiers = "en-w360dp-h740dp")
    fun approval_showsTargetPositionSourcePreviewAndWarnings_andEveryChoiceWorks() {
        show(task(TaskStatus.WAITING_FOR_APPROVAL, notActive = true))
        assertShown("agent_approval_prompt")
        assertShown("agent_thesis_preview")
        assertShown("agent_thesis_position")
        assertShown("agent_thesis_content")
        assertShown("agent_thesis_not_active")
        reachAndClick("agent_approve")
        reachAndClick("agent_reject")
        reachAndClick("agent_cancel")
        assertEquals(listOf("approve", "reject", "cancel"), clicks)
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h640dp")
    fun arabic_rightToLeft_narrow_font2_everyControlReached() {
        show(task(TaskStatus.WAITING_FOR_APPROVAL), rtl = true, fontScale = 2f)
        assertShown("agent_thesis_preview")
        reachAndClick("agent_approve")
        reachAndClick("agent_reject")
        assertEquals(listOf("approve", "reject"), clicks)
    }

    @Test
    @Config(qualifiers = "fr-w640dp-h360dp-land")
    fun completed_inFrench_landscape_openAndUndo() {
        show(task(TaskStatus.COMPLETED))
        reachAndClick("agent_thesis_open")
        reachAndClick("agent_thesis_undo")
        reachAndClick("agent_dismiss")
        assertEquals(listOf("open", "undo", "dismiss"), clicks)
    }

    @Test
    @Config(qualifiers = "en-w360dp-h740dp")
    fun undone_hidesUndo() {
        show(task(TaskStatus.COMPLETED).let { it.copy(warnings = it.warnings + "undone:9") })
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("agent_thesis_undo")).fetchSemanticsNodes().isEmpty())
    }
}
