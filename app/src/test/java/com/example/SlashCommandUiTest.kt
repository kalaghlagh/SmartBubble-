package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ui.commands.SlashCommand
import com.example.ui.commands.SlashCommands
import com.example.ui.components.ClearConversationsDialog
import com.example.ui.components.SlashCommandSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Suggestion list and "/clear" dialog with the real resources in English, French and Arabic (RTL). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlashCommandUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "ar-w360dp-h640dp")
    fun suggestionsStayEnglishInArabicAndTapSelects() {
        val selected = mutableListOf<SlashCommand>()
        compose.setContent {
            SlashCommandSuggestions(commands = SlashCommands.suggestions("/p"), highlightedIndex = 0, onSelect = { selected += it })
        }
        compose.onNodeWithText("/pptx").assertIsDisplayed()
        compose.onNodeWithText("Create a PowerPoint presentation").assertIsDisplayed()
        compose.onNodeWithText("/pdf").assertIsDisplayed()
        compose.onNodeWithTag("slash_command_pdf").performClick()
        assertEquals(listOf("pdf"), selected.map { it.name })
        // Layout follows RTL: the command token starts on the right half of the row.
        val row = compose.onNodeWithTag("slash_command_pptx").fetchSemanticsNode().boundsInRoot
        val token = compose.onNodeWithText("/pptx").fetchSemanticsNode().boundsInRoot
        assertTrue("token not on the right in RTL: $token in $row", token.left > row.center.x)
    }

    @Test
    @Config(qualifiers = "en")
    fun suggestionsInEnglishLayoutStartOnTheLeft() {
        compose.setContent {
            SlashCommandSuggestions(commands = SlashCommands.suggestions("/d"), highlightedIndex = 0, onSelect = {})
        }
        val row = compose.onNodeWithTag("slash_command_docx").fetchSemanticsNode().boundsInRoot
        val token = compose.onNodeWithText("/docx").fetchSemanticsNode().boundsInRoot
        assertTrue(token.right < row.center.x)
    }

    private class Calls {
        var clearCurrent = 0
        var deleteAll = 0
        var dismiss = 0
    }

    private fun showDialog(calls: Calls) = compose.setContent {
        ClearConversationsDialog(
            onClearCurrent = { calls.clearCurrent++ },
            onDeleteAll = { calls.deleteAll++ },
            onDismiss = { calls.dismiss++ }
        )
    }

    @Test
    @Config(qualifiers = "en")
    fun cancellingTheClearDialogDeletesNothing() {
        val calls = Calls()
        showDialog(calls)
        compose.onNodeWithText("Clear Conversations?").assertIsDisplayed()
        compose.onNodeWithTag("clear_cancel_button").performClick()
        assertEquals(1, calls.dismiss)
        assertEquals(0, calls.clearCurrent)
        assertEquals(0, calls.deleteAll)
    }

    @Test
    @Config(qualifiers = "en")
    fun clearCurrentChatIsOneExplicitChoice() {
        val calls = Calls()
        showDialog(calls)
        compose.onNodeWithTag("clear_current_button").performClick()
        assertEquals(1, calls.clearCurrent)
        assertEquals(0, calls.deleteAll)
    }

    @Test
    @Config(qualifiers = "en")
    fun deleteAllNeedsASecondConfirmation() {
        val calls = Calls()
        showDialog(calls)
        compose.onNodeWithTag("clear_all_button").performClick()
        // First tap only opens the warning.
        assertEquals(0, calls.deleteAll)
        compose.onNodeWithText("Delete all conversations?").assertIsDisplayed()
        compose.onNodeWithTag("clear_all_cancel_button").performClick()
        assertEquals(0, calls.deleteAll)
        assertEquals(1, calls.dismiss)
    }

    @Test
    @Config(qualifiers = "en")
    fun deleteAllRunsAfterTheSecondConfirmation() {
        val calls = Calls()
        showDialog(calls)
        compose.onNodeWithTag("clear_all_button").performClick()
        compose.onNodeWithTag("clear_all_confirm_button").performClick()
        assertEquals(1, calls.deleteAll)
        assertEquals(0, calls.clearCurrent)
    }

    @Test
    @Config(qualifiers = "fr")
    fun dialogIsLocalizedInFrench() {
        showDialog(Calls())
        compose.onNodeWithText("Effacer les conversations ?").assertIsDisplayed()
        compose.onNodeWithText("Effacer la discussion actuelle").assertIsDisplayed()
        compose.onNodeWithText("Supprimer toutes les conversations").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ar")
    fun dialogIsLocalizedInArabic() {
        showDialog(Calls())
        compose.onNodeWithText("مسح المحادثات؟").assertIsDisplayed()
        compose.onNodeWithText("مسح المحادثة الحالية").assertIsDisplayed()
        assertEquals("تم حذف جميع المحادثات.", context.getString(R.string.clear_all_done))
    }
}
