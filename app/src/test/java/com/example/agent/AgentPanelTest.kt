package com.example.agent

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
import com.example.agent.core.AgentStep
import com.example.agent.core.AgentTask
import com.example.agent.core.IntentAnalyzer
import com.example.agent.core.LibraryRef
import com.example.agent.core.StepStatus
import com.example.agent.core.TaskPlanner
import com.example.agent.core.TaskStatus
import com.example.agent.routing.FailoverDecision
import com.example.agent.routing.PriorityEntry
import com.example.agent.routing.ProviderCandidate
import com.example.agent.routing.RoutingEvent
import com.example.ai.providers.AiErrorKind
import com.example.data.model.ProviderEntity
import com.example.ui.screens.AgentPanel
import com.example.ui.screens.AgentPriorityCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Agent panel and the Settings priority card at phone sizes, in English, French and Arabic
 * (right to left), with large fonts: plan, progress, routing status and every control are laid out
 * on screen (reached by scrolling the bounded panel when needed) and call their actions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentPanelTest {

    @get:Rule
    val compose = createComposeRule()

    private val clicks = mutableListOf<String>()

    private fun task(status: TaskStatus, share: Boolean = true): AgentTask {
        val lib = listOf(LibraryRef("d1", "Ventes 2025.xlsx", "XLSX"), LibraryRef("d2", "تقرير المبيعات.docx", "DOCX"))
        val intent = IntentAnalyzer.analyze("Compare Ventes 2025 et تقرير المبيعات, crée un PDF" + if (share) " et partage-le" else "", lib)
        var n = 0
        val planned = TaskPlanner.plan("t", "s", "req", intent, "fr", 0L) { "e${n++}" }
        val steps = planned.steps.map { s: AgentStep ->
            when {
                status == TaskStatus.WAITING_FOR_APPROVAL && s.id == "share" -> s.copy(status = StepStatus.WAITING_FOR_APPROVAL)
                status == TaskStatus.WAITING_FOR_APPROVAL || s.id.startsWith("read") -> s.copy(status = StepStatus.COMPLETED)
                s.id == "generate" && status == TaskStatus.RUNNING -> s.copy(status = StepStatus.RUNNING)
                else -> s
            }
        }
        return planned.copy(status = status, steps = steps)
    }

    private val routing = RoutingStatus(
        providerName = "Anthropic Claude (travail)", model = "claude-model",
        events = listOf(
            RoutingEvent.Failed(ProviderCandidate("p1", "OpenAI GPT", "gpt"), 1, AiErrorKind.TIMEOUT, FailoverDecision.NEXT),
            RoutingEvent.Waiting(2, 2000)
        )
    )

    private fun show(task: AgentTask?, rtl: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) {
                MaterialTheme {
                    AgentPanel(
                        task = task, routing = routing, sourceTitles = mapOf("d1" to "Ventes 2025.xlsx"),
                        onPause = { clicks += "pause" }, onResume = { clicks += "resume" }, onCancel = { clicks += "cancel" },
                        onApprove = { clicks += "approve" }, onReject = { clicks += "reject" }, onDismiss = { clicks += "dismiss" }
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** [tag] is inside the window (after scrolling the panel to it) and clickable. */
    private fun reachAndClick(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag outside the window: $b in $root", b.left >= root.left - 0.5f && b.right <= root.right + 0.5f && b.width > 0f && b.height > 0f)
    }

    @Test
    @Config(qualifiers = "w320dp-h470dp")
    fun running_showsPlanProgressRouting_andPauseCancel_onASmallPhoneWithLargeFont() {
        show(task(TaskStatus.RUNNING), fontScale = 2f)
        compose.onNodeWithTag("agent_current_model").assertIsDisplayed()
        compose.onNodeWithTag("agent_step_generate").performScrollTo().assertIsDisplayed()
        reachAndClick("agent_pause")
        reachAndClick("agent_cancel")
        assertEquals(listOf("pause", "cancel"), clicks)
    }

    @Test
    @Config(qualifiers = "ar-w360dp-h640dp")
    fun approval_inArabic_rightToLeft_approveAndRejectReachable() {
        show(task(TaskStatus.WAITING_FOR_APPROVAL), rtl = true, fontScale = 1.5f)
        compose.onNodeWithTag("agent_approval_prompt").performScrollTo().assertIsDisplayed()
        reachAndClick("agent_approve")
        reachAndClick("agent_reject")
        reachAndClick("agent_cancel")
        assertEquals(listOf("approve", "reject", "cancel"), clicks)
        // Right to left: the first button starts at the right.
        val approve = compose.onNodeWithTag("agent_approve").fetchSemanticsNode().boundsInRoot
        val reject = compose.onNodeWithTag("agent_reject").fetchSemanticsNode().boundsInRoot
        assertTrue("approve $approve should be right of reject $reject", approve.left > reject.left || approve.top < reject.top)
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h891dp")
    fun paused_inFrench_resume_andFinished_dismiss() {
        show(task(TaskStatus.PAUSED, share = false))
        reachAndClick("agent_resume")
        show(task(TaskStatus.COMPLETED, share = false))
        reachAndClick("agent_dismiss")
        assertEquals(listOf("resume", "dismiss"), clicks)
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun landscape_panelIsBounded_soTheConversationKeepsMostOfTheHeight() {
        show(task(TaskStatus.RUNNING), fontScale = 1.3f)
        val panel = compose.onNodeWithTag("agent_panel").fetchSemanticsNode().boundsInRoot
        assertTrue("panel height ${panel.height} exceeds 40% of 360dp", panel.height <= 0.4f * 360f * compose.density.density + 1f)
        reachAndClick("agent_pause")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun quickModeWithoutSwitches_showsNothing() {
        compose.setContent {
            MaterialTheme {
                AgentPanel(null, RoutingStatus("OpenAI", "gpt"), emptyMap(), {}, {}, {}, {}, {}, {})
            }
        }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTagCount("agent_panel") == 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test
    @Config(qualifiers = "ar-w320dp-h640dp")
    fun settingsCard_reordersEnablesAndSetsPasses_inArabicWithLargeFont() {
        val providers = listOf(
            PriorityEntry(ProviderEntity("a", "OpenAI GPT للعمل", "OpenAI", "https://x", "", "gpt-model"), enabled = true, hasKey = true),
            PriorityEntry(ProviderEntity("b", "Google Gemini", "Google Gemini", "https://y", "", "gemini-model"), enabled = true, hasKey = false)
        )
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    androidx.compose.foundation.lazy.LazyColumn {
                        item {
                            AgentPriorityCard(
                                entries = providers, cycles = 2,
                                onMove = { f, t -> clicks += "move $f>$t" },
                                onEnabled = { id, on -> clicks += "enable $id $on" },
                                onCycles = { clicks += "cycles $it" }
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        reachAndClick("agent_down_a")
        reachAndClick("agent_up_b")
        reachAndClick("agent_enable_a")
        reachAndClick("agent_cycles_3")
        assertEquals(listOf("move 0>1", "move 1>0", "enable a false", "cycles 3"), clicks)
    }
}
