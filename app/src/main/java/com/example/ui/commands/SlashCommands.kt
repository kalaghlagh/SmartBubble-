package com.example.ui.commands

import com.example.ai.orchestrator.ContentOperation
import com.example.ai.orchestrator.DocumentFormat

/** What a built-in command does. */
sealed class SlashAction {
    /** Produce a document of [format] (orchestrator document path, no planning step). */
    data class Document(val format: DocumentFormat) : SlashAction()

    /** Run a content operation through the orchestrator on text or a conversation document. */
    data class Content(val operation: ContentOperation) : SlashAction()

    /** Open the "Clear Conversations?" dialog. Nothing is deleted without confirmation. */
    object Clear : SlashAction()
}

/**
 * A built-in command. [name] and [description] are part of the command syntax and stay in
 * English in every app language (they are deliberately not string resources).
 */
data class SlashCommand(val name: String, val description: String, val action: SlashAction) {
    val token: String get() = "/$name"
}

/** A message that starts with a known command, split into the command and the rest. */
data class ParsedCommand(val command: SlashCommand, val argument: String)

/** Built-in slash commands: detection, filtering, insertion and parsing. Pure Kotlin. */
object SlashCommands {

    val ALL: List<SlashCommand> = listOf(
        SlashCommand("docx", "Create a Word document", SlashAction.Document(DocumentFormat.DOCX)),
        SlashCommand("pptx", "Create a PowerPoint presentation", SlashAction.Document(DocumentFormat.PPTX)),
        SlashCommand("xlsx", "Create an Excel spreadsheet", SlashAction.Document(DocumentFormat.XLSX)),
        SlashCommand("pdf", "Create a PDF document", SlashAction.Document(DocumentFormat.PDF)),
        SlashCommand("summarize", "Summarize content", SlashAction.Content(ContentOperation.SUMMARIZE)),
        SlashCommand("translate", "Translate content", SlashAction.Content(ContentOperation.TRANSLATE)),
        SlashCommand("correct", "Correct and improve text", SlashAction.Content(ContentOperation.CORRECT)),
        SlashCommand("rewrite", "Rewrite content", SlashAction.Content(ContentOperation.REWRITE)),
        SlashCommand("analyze", "Analyze text or attached files", SlashAction.Content(ContentOperation.ANALYZE)),
        SlashCommand("clear", "Clear conversation history", SlashAction.Clear)
    )

    private val BY_NAME = ALL.associateBy { it.name }

    /**
     * Commands to suggest for the composer text: only while the text is "/" followed by the
     * beginning of a command name (no space yet). Matching is a case-insensitive prefix match.
     */
    fun suggestions(input: String): List<SlashCommand> {
        if (!input.startsWith("/")) return emptyList()
        val typed = input.substring(1)
        if (typed.any { it.isWhitespace() }) return emptyList()
        return ALL.filter { it.name.startsWith(typed, ignoreCase = true) }
    }

    /** Composer text after choosing [command]: the command and a space, ready for instructions. */
    fun insert(command: SlashCommand): String = command.token + " "

    /**
     * Parses a message to send. Returns null when it does not start with a known command
     * (it is then an ordinary message, e.g. "/etc/hosts is a file").
     */
    fun parse(message: String): ParsedCommand? {
        val text = message.trim()
        if (!text.startsWith("/")) return null
        val name = text.substring(1).takeWhile { !it.isWhitespace() }.lowercase()
        val command = BY_NAME[name] ?: return null
        return ParsedCommand(command, text.substring(1 + name.length).trim())
    }
}

/** What sending a message did, so the composer knows whether to keep the text. */
enum class SendResult {
    /** Sent to the AI (or handled); the composer is cleared. */
    SENT,

    /** A command needs text or a document that is missing; the text stays for editing. */
    NEEDS_INPUT,

    /** "/clear": the confirmation dialog is shown; the composer is cleared. */
    CLEAR_DIALOG,

    /** Another AI request is running; the text stays. */
    BUSY
}
