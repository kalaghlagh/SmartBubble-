package com.example.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.agent.routing.PriorityEntry
import com.example.data.model.ProviderEntity
import com.example.thesis.core.ExtractionInsertMode
import com.example.thesis.ui.ExtractionActions
import com.example.ui.components.InlineSelect
import com.example.ui.components.SelectGroup
import com.example.ui.components.SelectOption
import com.example.ui.components.SmartBubbleBottomNavBar
import com.example.ui.screens.AiProvidersRoutingSection
import com.example.ui.screens.ProviderItemRow
import com.example.ui.screens.TemplateLibraryFilter
import com.example.ui.screens.TemplateScopeMenu
import com.example.ui.theme.SmartBubbleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * The five Stitch redesigns as components, at phone widths (320–412 dp), landscape, font scale up
 * to 2.0, English and French (LTR) and Arabic (RTL): order and positions, that every control is
 * laid out inside the window and works, that menus only change state when an option is chosen.
 * Screenshots go to build/thesis-screenshots (uploaded by CI) for comparison with Stitch.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StitchScreensTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shots = File("build/thesis-screenshots").apply { mkdirs() }

    private fun shot(name: String) {
        compose.waitForIdle()
        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            val b = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(b))
            bitmap = b
        }
        FileOutputStream(File(shots, "stitch-$name.png")).use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(rtl: Boolean = false, fontScale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            ) { SmartBubbleTheme(darkTheme = false) { content() } }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun root(): Rect = compose.onRoot().fetchSemanticsNode().boundsInRoot

    private fun assertInside(tag: String) {
        val b = bounds(tag)
        val r = root()
        assertTrue("$tag outside the window: $b in $r", b.left >= r.left - 0.5f && b.right <= r.right + 0.5f && b.width > 0 && b.height > 0)
    }

    // ------------------------------------------------------------------ bottom navigation

    private val order = listOf("nav_thesis", "nav_templates", "nav_chat", "nav_library", "nav_settings")

    private fun nav(rtl: Boolean, fontScale: Float = 1f): MutableList<AppNavTab> {
        val picked = mutableListOf<AppNavTab>()
        show(rtl, fontScale) {
            var tab by androidx.compose.runtime.remember { mutableStateOf(AppNavTab.CHAT) }
            Box(Modifier.fillMaxSize()) {
                SmartBubbleBottomNavBar(tab, { tab = it; picked += it }, Modifier.align(Alignment.BottomCenter))
            }
        }
        return picked
    }

    @Test
    @Config(qualifiers = "en-w360dp-h740dp")
    fun navigation_order_agentInTheExactCentre_everyTabWorks() {
        val picked = nav(rtl = false)
        val xs = order.map { bounds(it).center.x }
        assertTrue("order $xs", xs.zipWithNext().all { (a, b) -> a < b })
        assertTrue("Agent not centred: ${xs[2]} vs ${root().center.x}", abs(xs[2] - root().center.x) < 2f)
        compose.onNodeWithTag("nav_chat").assertIsSelected()
        shot("nav-en")
        order.forEach { compose.onNodeWithTag(it).performClick() }
        assertEquals(listOf(AppNavTab.THESIS, AppNavTab.TEMPLATES, AppNavTab.CHAT, AppNavTab.LIBRARY, AppNavTab.SETTINGS), picked)
        compose.onNodeWithTag("nav_settings").assertIsSelected()
        // Every label fits on its line at the default font size.
        order.forEach { tag ->
            val layout = textLayout(tag)
            assertFalse("$tag label cut: ${layout.layoutInput.text}", layout.hasVisualOverflow)
        }
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val node = compose.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            n.children.forEach { walk(it) }
        }
        walk(node)
        return results.first()
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h640dp")
    fun navigation_arabic_rightToLeft_largeFont_stillCentredAndInside() {
        nav(rtl = true, fontScale = 2f)
        val xs = order.map { bounds(it).center.x }
        assertTrue("RTL order $xs", xs.zipWithNext().all { (a, b) -> a > b })
        assertTrue(abs(xs[2] - root().center.x) < 2f)
        order.forEach { assertInside(it); assertTrue("$it touch target", bounds(it).height >= 48f * compose.density.density - 1f) }
        shot("nav-ar-320-font2")
    }

    // ------------------------------------------------------------------ settings

    private val providers = listOf(
        ProviderEntity("p1", "SmartBubble", "Groq", "https://x", "", "openai/gpt-oss-120b", isDefault = true, status = "connected:858"),
        ProviderEntity("p2", "My Key", "OpenAI-compatible", "https://y", "", "gemini-4-argon", status = "error:INVALID_API_KEY")
    )

    private fun settings(rtl: Boolean = false, fontScale: Float = 1f): MutableList<String> {
        val calls = mutableListOf<String>()
        show(rtl, fontScale) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                item {
                    AiProvidersRoutingSection(
                        providerCount = 2, activeCount = 2, notice = null,
                        providerCards = {
                            providers.forEach { p ->
                                ProviderItemRow(p, hasKey = true, isTesting = false,
                                    onTest = { calls += "test ${p.id}" }, onSetDefault = { calls += "default ${p.id}" },
                                    onEdit = { calls += "edit ${p.id}" }, onDelete = { calls += "delete ${p.id}" })
                            }
                        },
                        onAddProvider = { calls += "add" },
                        entries = providers.map { PriorityEntry(it, enabled = true, hasKey = true) },
                        cycles = 2,
                        onMove = { f, t -> calls += "move $f>$t" },
                        onEnabled = { id, on -> calls += "enable $id $on" },
                        onCycles = { calls += "cycles $it" }
                    )
                }
            }
        }
        return calls
    }

    private fun reach(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        assertInside(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    @Test
    @Config(qualifiers = "en-w393dp-h852dp")
    fun settings_unifiedSection_everyProviderAndRoutingActionWorks() {
        val calls = settings()
        shot("settings-en")
        listOf("test_provider_p1", "edit_provider_p1", "delete_provider_p1", "set_default_provider_p2", "add_provider_button",
            "agent_down_p1", "agent_up_p2", "agent_enable_p2", "agent_cycles_3").forEach { reach(it) }
        assertEquals(listOf("test p1", "edit p1", "delete p1", "default p2", "add", "move 0>1", "move 1>0", "enable p2 false", "cycles 3"), calls)
        // The default provider has no "Set as Default"; the failing one shows its reason.
        assertFalse(compose.onAllNodes(hasTestTag("set_default_provider_p1")).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("provider_error_p2", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("agent_cycles_2").assertIsSelected()
        // Collapsing hides both parts; expanding brings them back.
        compose.onNodeWithTag("ai_routing_header").performClick()
        compose.waitForIdle()
        assertFalse(compose.onAllNodes(hasTestTag("agent_settings")).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("ai_routing_header").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("agent_settings").assertExists()
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h640dp")
    fun settings_arabic_narrow_font2_allControlsReachable() {
        settings(rtl = true, fontScale = 2f)
        listOf("test_provider_p2", "set_default_provider_p2", "delete_provider_p2", "agent_enable_p1", "agent_cycles_1").forEach { reach(it) }
        shot("settings-ar-320-font2")
    }

    // ------------------------------------------------------------------ My Templates scope menu

    @Test
    @Config(qualifiers = "fr-w360dp-h740dp")
    fun templates_scopeMenu_choosesAScope_andShowsTheActiveDot() {
        var scope by mutableStateOf(TemplateLibraryFilter.ALL)
        show { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) { TemplateScopeMenu(scope) { scope = it } } }
        assertFalse(compose.onAllNodes(hasTestTag("templates_scope_active")).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("templates_scope_button").performClick()
        compose.waitForIdle()
        TemplateLibraryFilter.values().forEach { compose.onNodeWithTag("st_filter_${it.name.lowercase()}").assertIsDisplayed() }
        compose.onNodeWithTag("st_filter_all").assertIsSelected()
        shot("templates-scope-menu-fr")
        compose.onNodeWithTag("st_filter_favorites").performClick()
        compose.waitForIdle()
        assertEquals(TemplateLibraryFilter.FAVORITES, scope)
        compose.onNodeWithTag("templates_scope_active", useUnmergedTree = true).assertExists()
        assertFalse(compose.onAllNodes(hasTestTag("templates_scope_menu")).fetchSemanticsNodes().isNotEmpty())
    }

    // ------------------------------------------------------------------ inline select (thesis template, review language)

    @Test
    @Config(qualifiers = "ar-w320dp-h640dp")
    fun inlineSelect_openingSelectsNothing_choosingSelectsOnce_rtl() {
        val chosen = mutableListOf<String>()
        show(rtl = true, fontScale = 1.5f) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                item {
                    InlineSelect(
                        triggerLabel = "اللغة: العربية", triggerIcon = Icons.Default.Language,
                        groups = listOf(SelectGroup(null, listOf(SelectOption("auto", "اكتشاف تلقائي"), SelectOption("ar", "العربية"), SelectOption("en", "English")))),
                        selectedKey = "ar", onSelect = { chosen += it }, testTag = "lang", optionTag = { "opt_$it" }
                    )
                }
            }
        }
        compose.onNodeWithTag("lang_trigger").performClick()
        compose.waitForIdle()
        assertTrue("opening must not choose anything", chosen.isEmpty())
        listOf("opt_auto", "opt_ar", "opt_en").forEach { assertInside(it) }
        compose.onNodeWithTag("opt_ar").assertIsSelected()
        shot("inline-select-ar")
        compose.onNodeWithTag("opt_en").performClick()
        compose.waitForIdle()
        assertEquals(listOf("en"), chosen)
        assertFalse(compose.onAllNodes(hasTestTag("lang_menu")).fetchSemanticsNodes().isNotEmpty())
    }

    // ------------------------------------------------------------------ review actions

    private fun actions(rtl: Boolean = false, fontScale: Float = 1f): MutableList<String> {
        val calls = mutableListOf<String>()
        show(rtl, fontScale) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                item { ExtractionActions(1, { calls += it.name }, { calls += "copy" }, { calls += "cancel" }) }
            }
        }
        return calls
    }

    @Test
    @Config(qualifiers = "en-w360dp-h740dp")
    fun reviewActions_twoColumns_cancelAloneAndCentred() {
        val calls = actions()
        val rows = listOf("dx_insert_cursor" to "dx_insert_append", "dx_insert_paragraphs" to "dx_insert_heading", "dx_insert_tables" to "dx_copy")
        rows.forEach { (a, b) ->
            assertEquals("$a and $b on one row", bounds(a).top, bounds(b).top, 1f)
            assertTrue(bounds(a).right <= bounds(b).left)
        }
        val cancel = bounds("dx_cancel_review")
        assertTrue("cancel under the grid", cancel.top >= bounds("dx_copy").bottom)
        assertTrue("cancel not centred: $cancel in ${root()}", abs(cancel.center.x - root().center.x) < 1.5f)
        shot("review-actions-en")
        (rows.flatMap { listOf(it.first, it.second) } + "dx_cancel_review").forEach { reach(it) }
        assertEquals(listOf("AT_CURSOR", "APPEND", "AS_PARAGRAPHS", "HEADING_AND_CONTENT", "TABLES_ONLY", "copy", "cancel"), calls)
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h640dp")
    fun reviewActions_arabic_font2_everyActionReachable_cancelCentred() {
        actions(rtl = true, fontScale = 2f)
        listOf("dx_insert_cursor", "dx_insert_append", "dx_insert_paragraphs", "dx_insert_heading", "dx_insert_tables", "dx_copy", "dx_cancel_review").forEach {
            compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
            assertInside(it)
        }
        val cancel = bounds("dx_cancel_review")
        assertTrue(abs(cancel.center.x - root().center.x) < 1.5f)
        shot("review-actions-ar-320-font2")
    }

    @Test
    @Config(qualifiers = "en-w640dp-h360dp-land")
    fun reviewActions_landscape_twoColumns() {
        actions()
        assertEquals(bounds("dx_insert_cursor").top, bounds("dx_insert_append").top, 1f)
        assertInside("dx_cancel_review")
    }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp")
    fun providerStatusPill_showsLatency_withoutClipping() {
        settings()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("858 ms")).fetchSemanticsNodes().isNotEmpty())
        assertInside("provider_status_p1")
    }
}
