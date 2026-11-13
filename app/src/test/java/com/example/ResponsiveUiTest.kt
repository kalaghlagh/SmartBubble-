package com.example

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import com.example.ai.orchestrator.AiProgressStage
import com.example.ui.AppNavTab
import com.example.ui.components.SmartBubbleBottomNavBar
import com.example.ui.screens.LibraryCategoryRow
import com.example.ui.screens.LibraryFilter
import com.example.ui.screens.SettingsNavRow
import com.example.ui.screens.progressStageText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
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
 * Layout checks with the real string resources in English, French (long labels) and Arabic
 * (RTL), on a narrow 320dp screen. Robolectric applies the locale and layout direction from
 * the qualifiers. Native graphics mode is required so text is really measured (in the legacy
 * mode text has no width and widths cannot be checked).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResponsiveUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    // ------------------------------------------------------------------ Library categories

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp")
    fun libraryCategories_frenchNarrow_stayOnOneLineAndScroll() {
        compose.setContent { LibraryCategoryRow(selectedKey = "All", onSelect = {}) }
        val allHeight = bounds("library_filter_All").height
        // "Feuilles de calcul" does not fit on screen next to the others: reach it by swiping.
        compose.onNodeWithTag("library_filter_row").performScrollToNode(hasTestTag("library_filter_Spreadsheets"))
        compose.onNodeWithTag("library_filter_Spreadsheets").assertIsDisplayed()
        val spreadsheets = bounds("library_filter_Spreadsheets")
        // Same chip height as a short label (one line), and wider than tall: never stacked letters.
        assertEquals(allHeight, spreadsheets.height, 1f)
        assertTrue("chip is not horizontal: $spreadsheets", spreadsheets.width > spreadsheets.height * 3)
    }

    @Test
    @Config(qualifiers = "en-w320dp-h640dp")
    fun libraryCategories_selectionFilters() {
        compose.setContent {
            var selected by remember { mutableStateOf("All") }
            LibraryCategoryRow(selectedKey = selected, onSelect = { selected = it })
        }
        compose.onNodeWithTag("library_filter_row").performScrollToNode(hasTestTag("library_filter_Presentations"))
        compose.onNodeWithTag("library_filter_Presentations").performClick()
        compose.onNodeWithTag("library_filter_Presentations").assertIsSelected()

        assertTrue(LibraryFilter.PRESENTATIONS.matches("PPTX"))
        assertTrue(!LibraryFilter.PRESENTATIONS.matches("DOCX"))
        assertTrue(LibraryFilter.SPREADSHEETS.matches("xlsx"))
        assertTrue(LibraryFilter.ALL.matches("PDF"))
        assertEquals(LibraryFilter.ALL, LibraryFilter.fromKey("unknown"))
    }

    @Test
    @Config(qualifiers = "ar-w320dp-h640dp")
    fun libraryCategories_arabicStartsOnTheRight() {
        compose.setContent {
            Box(Modifier.width(320.dp).testTag("root")) { LibraryCategoryRow(selectedKey = "All", onSelect = {}) }
        }
        val root = bounds("root")
        val all = bounds("library_filter_All")
        // RTL: the first category sits at the right edge (16dp content padding).
        assertTrue("first chip not on the right: $all in $root", all.left > root.width / 2)
    }

    // ------------------------------------------------------------------ Settings row

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp")
    fun voiceLanguageRow_frenchValueUsesTheFullWidth() {
        compose.setContent {
            Box(Modifier.width(320.dp).testTag("root")) {
                SettingsNavRow(
                    icon = Icons.Outlined.Mic,
                    title = stringResource(R.string.settings_voice_language),
                    value = stringResource(R.string.voice_language_auto),
                    onClick = {},
                    testTag = "voice_row"
                )
            }
        }
        val value = bounds("voice_row_value")
        val root = bounds("root")
        // The value is laid out below the title across the row, not in a narrow column.
        assertTrue("value squeezed: $value", value.width > root.width * 0.5f)
        assertEquals("Même langue que l’application", context.getString(R.string.voice_language_auto))
    }

    @Test
    @Config(qualifiers = "ar")
    fun voiceLanguageAuto_arabicText() {
        assertEquals("نفس لغة التطبيق", context.getString(R.string.voice_language_auto))
    }

    @Test
    @Config(qualifiers = "en")
    fun voiceLanguageAuto_englishText() {
        assertEquals("Same as app language", context.getString(R.string.voice_language_auto))
    }

    // ------------------------------------------------------------------ Bottom navigation

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp")
    fun bottomNavigation_equalColumnsInFrench() {
        compose.setContent { SmartBubbleBottomNavBar(currentTab = AppNavTab.SETTINGS, onTabSelected = {}) }
        // Five destinations since Thesis Builder became its own one.
        val tags = listOf("nav_chat", "nav_thesis", "nav_templates", "nav_library", "nav_settings")
        val items = tags.map { bounds(it) }
        items.forEach { assertEquals(items[0].width, it.width, 1f) }
        items.forEach { assertEquals(items[0].height, it.height, 1f) }
        // Side by side, no overlap.
        for (i in 1 until items.size) assertTrue(items[i].left >= items[i - 1].right - 0.5f)
        compose.onNodeWithTag("nav_settings").assertIsSelected()
    }

    // ------------------------------------------------------------------ Progress and cancellation strings

    @Test
    fun progressStages_mapToTheirOwnStrings() {
        val ids = AiProgressStage.values().map { progressStageText(it) }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(R.string.progress_connecting, progressStageText(null))
    }

    @Test
    @Config(qualifiers = "fr")
    fun cancellationAndProgress_french() {
        assertEquals("Génération arrêtée.", context.getString(R.string.chat_generation_stopped))
        assertEquals("Connexion au fournisseur IA", context.getString(R.string.progress_connecting))
        assertEquals("Création du document", context.getString(R.string.progress_creating_document))
    }

    @Test
    @Config(qualifiers = "ar")
    fun cancellationAndProgress_arabic() {
        assertEquals("تم إيقاف التوليد.", context.getString(R.string.chat_generation_stopped))
        assertEquals("التحقق من النتيجة", context.getString(R.string.progress_validating))
    }

    @Test
    @Config(qualifiers = "en")
    fun cancellationAndProgress_english() {
        assertEquals("Generation stopped.", context.getString(R.string.chat_generation_stopped))
        assertEquals("Generating content", context.getString(R.string.progress_generating))
    }

    // ------------------------------------------------------------------ Launcher icon

    @Test
    @Config(sdk = [34])
    fun launcherIcon_isAdaptiveWithMonochrome() {
        val icon = ResourcesCompat.getDrawable(context.resources, R.mipmap.ic_launcher, null)
        assertTrue("not adaptive: $icon", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertTrue(icon.foreground != null && icon.background != null && icon.monochrome != null)
        val round = ResourcesCompat.getDrawable(context.resources, R.mipmap.ic_launcher_round, null)
        assertTrue(round is AdaptiveIconDrawable)
    }

    @Test
    @Config(sdk = [25], qualifiers = "xxhdpi")
    fun launcherIcon_legacyBitmapBeforeAndroid8() {
        val icon = ResourcesCompat.getDrawable(context.resources, R.mipmap.ic_launcher, null)!!
        assertTrue(abs(icon.intrinsicWidth - 144) <= 1)
    }
}
