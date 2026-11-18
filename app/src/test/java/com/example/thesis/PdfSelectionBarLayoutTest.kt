package com.example.thesis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.thesis.ui.PdfSelectionBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The PDF page viewer's floating selection bar at real window sizes (compact to expanded,
 * portrait and landscape), font scales 1.0 / 1.3 / 1.5 / 2.0, in English, French and Arabic:
 * hidden without a selection; with one, Cancel and Next are both displayed inside the window,
 * at least 48 dp high, not over each other, their labels and the count not cut (text layout
 * overflow), in the language's reading order, and each click reaches its handler once.
 * Rendered bounds (native text measurement), not tree presence.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfSelectionBarLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private var count by mutableIntStateOf(0)
    private val clicks = mutableListOf<String>()

    private fun show(fontScale: Float = 1f, selected: Int = 3) {
        count = 0
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize().testTag("pages")) { items(40) { Text("Page ${it + 1}") } }
                        PdfSelectionBar(count, onCancel = { clicks += "cancel" }, onNext = { clicks += "next" }, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("bar shown without a selection", compose.onAllNodes(hasTestTag("pdf_action_bar")).fetchSemanticsNodes().isEmpty())
        count = selected
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun overlaps(a: Rect, b: Rect) = a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** The text inside [tag] is laid out whole: no line cut by the width or the height available. */
    private fun assertNotCut(tag: String, text: String) {
        val node = compose.onNode(hasText(text) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
        val l = layout(node)
        assertTrue("$tag text '$text' cut: overflowWidth=${l.didOverflowWidth} overflowHeight=${l.didOverflowHeight}", !l.didOverflowWidth && !l.didOverflowHeight)
    }

    private fun density() = compose.density.density

    private fun assertBarUsable(rtl: Boolean) {
        val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("pdf_action_bar").assertIsDisplayed()
        val bar = bounds("pdf_action_bar")
        assertTrue("bar $bar wider than the window $window", bar.left >= window.left - 0.5f && bar.right <= window.right + 0.5f && bar.bottom <= window.bottom + 0.5f)
        val next = bounds("pdf_next"); val cancel = bounds("pdf_cancel")
        for ((t, b) in listOf("pdf_next" to next, "pdf_cancel" to cancel)) {
            compose.onNodeWithTag(t).assertIsDisplayed()
            assertTrue("$t $b outside the bar $bar", b.left >= bar.left - 0.5f && b.right <= bar.right + 0.5f && b.top >= bar.top - 0.5f && b.bottom <= bar.bottom + 0.5f)
            assertTrue("$t too small to touch: $b", b.height >= 48f * density() - 1f && b.width >= 48f * density() - 1f)
        }
        assertTrue("Next $next over Cancel $cancel", !overlaps(next, cancel))
        assertNotCut("pdf_next", res.getString(R.string.dx_pdf_next))
        assertNotCut("pdf_cancel", res.getString(R.string.action_cancel))
        assertNotCut("pdf_selected_label", res.getString(R.string.dx_pdf_selected_count, 3))
        // Reading order: Cancel then Next, from the start side of the language (or Cancel above
        // Next when each has its own line).
        if (kotlin.math.abs(next.top - cancel.top) < 1f) assertTrue("order rtl=$rtl: next $next cancel $cancel", if (rtl) next.left < cancel.left else next.left > cancel.left)
        else assertTrue("Cancel $cancel above Next $next", cancel.bottom <= next.top + 0.5f)
        compose.onNodeWithTag("pdf_cancel").performClick()
        compose.onNodeWithTag("pdf_next").performClick()
        assertEquals(listOf("cancel", "next"), clicks.toList())
    }

    @Test @Config(qualifiers = "en-w320dp-h470dp") fun compact_320x470_english() { show(); assertBarUsable(rtl = false) }

    @Test @Config(qualifiers = "fr-w360dp-h640dp") fun compact_360x640_french() { show(); assertBarUsable(rtl = false) }

    @Test @Config(qualifiers = "ar-w393dp-h873dp") fun phone_393x873_arabic() { show(); assertBarUsable(rtl = true) }

    @Test @Config(qualifiers = "en-w412dp-h915dp") fun phone_412x915_english() { show(); assertBarUsable(rtl = false) }

    @Test @Config(qualifiers = "fr-w600dp-h960dp") fun medium_600x960_french() { show(); assertBarUsable(rtl = false) }

    @Test @Config(qualifiers = "ar-w840dp-h1200dp") fun expanded_840x1200_arabic() { show(); assertBarUsable(rtl = true) }

    @Test @Config(qualifiers = "fr-w640dp-h360dp-land") fun landscape_640x360_french() { show(); assertBarUsable(rtl = false) }

    @Test @Config(qualifiers = "ar-w915dp-h412dp-land") fun landscape_915x412_arabic() { show(); assertBarUsable(rtl = true) }

    @Test @Config(qualifiers = "fr-w412dp-h915dp") fun fontScale_1_3_french() { show(1.3f); assertBarUsable(rtl = false) }

    @Test @Config(qualifiers = "en-w360dp-h640dp") fun fontScale_1_5_english() { show(1.5f); assertBarUsable(rtl = false) }

    @Test @Config(qualifiers = "ar-w320dp-h470dp") fun fontScale_2_0_arabic_compact() { show(2f); assertBarUsable(rtl = true) }

    @Test @Config(qualifiers = "fr-w320dp-h470dp") fun fontScale_2_0_french_compact() { show(2f); assertBarUsable(rtl = false) }

    @Test @Config(qualifiers = "en-w412dp-h915dp") fun deselectingAll_hidesTheBar() {
        show()
        compose.onNodeWithTag("pdf_action_bar").assertIsDisplayed()
        count = 0
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("pdf_action_bar")).fetchSemanticsNodes().isEmpty() }
        assertTrue(clicks.isEmpty())
    }
}
