package com.example.ui.adaptive

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.example.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The adaptive content + actions layout (the PDF extraction review's) at real window sizes, in
 * English, French and Arabic, at default and large font scales, with long mixed Arabic/Latin
 * content: every one of the seven actions is laid out inside the window, not overlapped by
 * another action or by the content, reachable (directly, or by scrolling the action area when
 * the text is very large) and actually clickable; the content keeps its share of the height.
 * Native graphics: text is really measured. Rendered bounds are checked, not tree presence.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val tags = listOf("dx_insert_cursor", "dx_insert_append", "dx_insert_paragraphs", "dx_insert_heading", "dx_insert_tables", "dx_copy", "dx_cancel_review")
    private val clicks = mutableListOf<String>()

    private fun show(fontScale: Float = 1f) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MaterialTheme {
                    val labels = listOf(
                        stringResource(R.string.dx_insert_cursor), stringResource(R.string.dx_insert_append),
                        stringResource(R.string.dx_insert_paragraphs), stringResource(R.string.dx_insert_heading),
                        stringResource(R.string.dx_insert_tables, 2), stringResource(R.string.scan_copy_text), stringResource(R.string.action_cancel)
                    )
                    ContentWithActions(Modifier.testTag("screen"), actionsTag = "actions", actions = {
                        AdaptiveActionGrid(tags.mapIndexed { i, t -> AdaptiveAction(t, labels[i], primary = i == 0) { clicks += t } })
                    }) {
                        LazyColumn(Modifier.fillMaxSize().testTag("content")) {
                            items(400) { i ->
                                Text(if (i % 2 == 0) "تعد المياه الجوفية مصدرا أساسيا لمياه الشرب، الفقرة $i" else "Paragraph $i: pH 7.2 في البئر W$i, nitrates 35 mg/L.")
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun overlaps(a: Rect, b: Rect) = a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f

    /**
     * Every action inside the window, beside or under the content (never over it), not over
     * another action; [withoutScrolling]: all on screen at once; otherwise each is reached by
     * scrolling the action area. Then each is clicked.
     */
    private fun assertActionsUsable(withoutScrolling: Boolean, rtl: Boolean = false) {
        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val content = bounds("content")
        val area = bounds("actions")
        assertTrue("action area $area outside the window $window", area.left >= window.left - 0.5f && area.right <= window.right + 0.5f && area.bottom <= window.bottom + 0.5f)
        assertTrue("content $content under the actions $area", !overlaps(content, area))
        // The content keeps its share of the height (or the width when the actions are beside it).
        if (area.top >= content.bottom - 0.5f) assertTrue("content ${content.height} too small in ${window.height}", content.height >= AdaptiveRules.minContentHeightDp(window.height / density()) * density() - 2f)
        val seen = mutableMapOf<String, Rect>()
        for (t in tags) {
            if (!withoutScrolling) compose.onNodeWithTag(t).performScrollTo()
            compose.onNodeWithTag(t).assertIsDisplayed()
            val b = bounds(t)
            val visibleArea = bounds("actions")
            assertTrue("$t $b not inside the window $window", b.left >= window.left - 0.5f && b.right <= window.right + 0.5f && b.top >= window.top - 0.5f && b.bottom <= window.bottom + 0.5f)
            assertTrue("$t $b outside the visible action area $visibleArea", b.top >= visibleArea.top - 0.5f && b.bottom <= visibleArea.bottom + 0.5f)
            assertTrue("$t $b over the content $content", !overlaps(b, content))
            assertTrue("$t too small to touch: $b", b.height >= 48f * density() - 1f)
            if (withoutScrolling) seen.values.forEach { o -> assertTrue("$t $b over another action $o", !overlaps(b, o)) }
            seen[t] = b
            compose.onNodeWithTag(t).performClick()
        }
        assertEquals("every action clicked once", tags, clicks.toList())
        // Reading order follows the language: first action at the start side of its row.
        val first = seen.getValue(tags[0]); val second = seen.getValue(tags[1])
        if (withoutScrolling && first.top == second.top) assertTrue("reading order rtl=$rtl: $first / $second", if (rtl) first.left > second.left else first.left < second.left)
    }

    private fun density() = compose.density.density

    @Test @Config(qualifiers = "en-w320dp-h470dp") fun small_320x470_english() { show(); assertActionsUsable(withoutScrolling = true) }

    @Test @Config(qualifiers = "fr-w360dp-h640dp") fun compact_360x640_french() { show(); assertActionsUsable(withoutScrolling = true) }

    @Test @Config(qualifiers = "ar-w393dp-h873dp") fun phone_393x873_arabic() { show(); assertActionsUsable(withoutScrolling = true, rtl = true) }

    @Test @Config(qualifiers = "en-w412dp-h915dp") fun phone_412x915_english() { show(); assertActionsUsable(withoutScrolling = true) }

    @Test @Config(qualifiers = "fr-w600dp-h960dp") fun medium_600x960_french() { show(); assertActionsUsable(withoutScrolling = true) }

    @Test @Config(qualifiers = "ar-w840dp-h1200dp") fun expanded_840x1200_arabic() {
        show(); assertActionsUsable(withoutScrolling = true, rtl = true)
        // Wide: all seven on one row.
        val tops = tags.map { bounds(it).top }.toSet()
        assertEquals(1, tops.size)
    }

    @Test @Config(qualifiers = "fr-w640dp-h360dp-land") fun landscape_640x360_french_actionsBeside() {
        show(); assertActionsUsable(withoutScrolling = false)
        assertTrue("actions beside the content", bounds("actions").left >= bounds("content").right - 0.5f)
    }

    @Test @Config(qualifiers = "ar-w915dp-h412dp-land") fun landscape_915x412_arabic_actionsBeside() {
        show(); assertActionsUsable(withoutScrolling = false, rtl = true)
        assertTrue("actions beside the content (start side in RTL)", bounds("actions").right <= bounds("content").left + 0.5f)
    }

    @Test @Config(qualifiers = "fr-w412dp-h915dp") fun largeFont_1_3_french() { show(1.3f); assertActionsUsable(withoutScrolling = true) }

    @Test @Config(qualifiers = "en-w320dp-h470dp") fun largeFont_1_5_small_english_reachableByScrolling() { show(1.5f); assertActionsUsable(withoutScrolling = false) }

    @Test @Config(qualifiers = "ar-w360dp-h640dp") fun largeFont_2_0_arabic_reachableByScrolling() { show(2f); assertActionsUsable(withoutScrolling = false, rtl = true) }
}
