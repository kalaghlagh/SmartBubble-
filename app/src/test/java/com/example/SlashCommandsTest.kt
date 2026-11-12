package com.example

import com.example.ai.orchestrator.ContentOperation
import com.example.ai.orchestrator.DocumentFormat
import com.example.ui.commands.SlashAction
import com.example.ui.commands.SlashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Built-in slash commands: detection, filtering, insertion, parsing and routing. Pure JVM. */
class SlashCommandsTest {

    @Test
    fun theTenBuiltInCommandsHaveEnglishNamesAndDescriptions() {
        assertEquals(
            listOf("docx", "pptx", "xlsx", "pdf", "summarize", "translate", "correct", "rewrite", "analyze", "clear"),
            SlashCommands.ALL.map { it.name }
        )
        assertEquals("Create a Word document", SlashCommands.ALL.first().description)
        assertEquals("Clear conversation history", SlashCommands.ALL.last().description)
        // Plain ASCII: the same text in every app language.
        assertTrue(SlashCommands.ALL.all { c -> (c.name + c.description).all { it.code < 128 } })
    }

    @Test
    fun suggestionsAppearOnlyWhileTypingACommandName() {
        assertEquals(10, SlashCommands.suggestions("/").size)
        assertEquals(listOf("docx"), SlashCommands.suggestions("/d").map { it.name })
        assertEquals(listOf("pptx", "pdf"), SlashCommands.suggestions("/p").map { it.name })
        assertEquals(listOf("correct", "clear"), SlashCommands.suggestions("/C").map { it.name })
        assertTrue(SlashCommands.suggestions("/zz").isEmpty())
        assertTrue(SlashCommands.suggestions("hello /d").isEmpty()) // only at the start
        assertTrue(SlashCommands.suggestions("/docx report").isEmpty()) // closed once arguments start
        assertTrue(SlashCommands.suggestions("").isEmpty())
    }

    @Test
    fun selectingInsertsTheCommandReadyForInstructions() {
        val docx = SlashCommands.suggestions("/do").single()
        assertEquals("/docx ", SlashCommands.insert(docx))
        // The inserted text no longer shows suggestions and parses with an empty argument.
        assertTrue(SlashCommands.suggestions(SlashCommands.insert(docx)).isEmpty())
        assertEquals("", SlashCommands.parse(SlashCommands.insert(docx))!!.argument)
    }

    @Test
    fun parsingRoutesEachCommandToItsOrchestratorAction() {
        val docx = SlashCommands.parse("/docx  Quarterly report on sales\nwith a table")!!
        assertEquals(SlashAction.Document(DocumentFormat.DOCX), docx.command.action)
        assertEquals("Quarterly report on sales\nwith a table", docx.argument)
        assertEquals(SlashAction.Document(DocumentFormat.PPTX), SlashCommands.parse("/pptx x")!!.command.action)
        assertEquals(SlashAction.Document(DocumentFormat.XLSX), SlashCommands.parse("/XLSX x")!!.command.action)
        assertEquals(SlashAction.Document(DocumentFormat.PDF), SlashCommands.parse("/pdf x")!!.command.action)
        assertEquals(SlashAction.Content(ContentOperation.SUMMARIZE), SlashCommands.parse("/summarize t")!!.command.action)
        assertEquals(SlashAction.Content(ContentOperation.TRANSLATE), SlashCommands.parse("/translate t")!!.command.action)
        assertEquals(SlashAction.Content(ContentOperation.CORRECT), SlashCommands.parse("/correct t")!!.command.action)
        assertEquals(SlashAction.Content(ContentOperation.REWRITE), SlashCommands.parse("/rewrite t")!!.command.action)
        assertEquals(SlashAction.Content(ContentOperation.ANALYZE), SlashCommands.parse("/analyze t")!!.command.action)
        assertEquals(SlashAction.Clear, SlashCommands.parse("/clear")!!.command.action)
        // Arabic arguments are kept as typed.
        assertEquals("لخّص هذا النص", SlashCommands.parse("/summarize لخّص هذا النص")!!.argument)
    }

    @Test
    fun missingArgumentsAndOrdinaryTextAreRecognised() {
        // The ViewModel asks for content when the argument is empty.
        assertEquals("", SlashCommands.parse("/docx")!!.argument)
        assertEquals("", SlashCommands.parse("  /summarize   ")!!.argument)
        // Not commands: sent as normal messages.
        assertNull(SlashCommands.parse("/etc/hosts is a file"))
        assertNull(SlashCommands.parse("/docxs something"))
        assertNull(SlashCommands.parse("please /docx this"))
    }

    @Test
    fun analyzeIsAContentOperation() {
        assertEquals(ContentOperation.ANALYZE, ContentOperation.fromActionKey("analyze"))
    }
}
