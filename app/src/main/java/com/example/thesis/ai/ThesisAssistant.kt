package com.example.thesis.ai

import com.example.ai.orchestrator.AiProgressListener
import com.example.ai.providers.Json
import com.example.thesis.core.EditMode
import com.example.thesis.core.ThesisContext
import com.example.thesis.model.ThesisSnapshot

/** AI tasks of the Thesis Builder (implemented through the Central AI Orchestrator). */
interface ThesisAi {
    /** Name of the default provider that will receive the request, or null when none is set. */
    suspend fun providerName(): String?
    suspend fun suggestDestination(snapshot: ThesisSnapshot, excerpt: String, instruction: String, listener: AiProgressListener): DestinationSuggestion
    suspend fun suggestImagePlace(snapshot: ThesisSnapshot, description: String, listener: AiProgressListener): DestinationSuggestion
    suspend fun edit(snapshot: ThesisSnapshot, mode: EditMode, items: List<Pair<String, String>>, instruction: String, listener: AiProgressListener): EditSuggestion
    suspend fun table(snapshot: ThesisSnapshot, data: String, listener: AiProgressListener): TableSuggestion

    /**
     * Writes or edits text in one section with that section's context and rules. The default
     * sends the target text as a plain edit (for implementations without section support).
     */
    suspend fun write(snapshot: ThesisSnapshot, context: SectionWriteContext, listener: AiProgressListener): EditSuggestion {
        val source = context.target
        val r = edit(snapshot, context.mode, listOf("text" to source), context.instruction, listener)
        return EditSuggestion(r.edits["text"]?.let { mapOf("text" to it) } ?: emptyMap(), r.notes, r.warnings)
    }
}

/**
 * [ThesisAi] over the default provider: user -> default provider (via CentralAiOrchestrator) ->
 * local validation ([ThesisAiProtocol]) -> proposal for the user's review. Only the outline and the
 * texts the request is about are sent; long sections are sent in chunks.
 */
class OrchestratorThesisAi(
    private val run: suspend (system: String, user: String, listener: AiProgressListener) -> String,
    private val defaultProviderName: suspend () -> String?
) : ThesisAi {

    override suspend fun providerName(): String? = defaultProviderName()

    override suspend fun suggestDestination(snapshot: ThesisSnapshot, excerpt: String, instruction: String, listener: AiProgressListener): DestinationSuggestion {
        val text = run(ThesisAiProtocol.destinationSystem(snapshot.meta.language), ThesisAiProtocol.destinationUser(ThesisContext.outline(snapshot), excerpt.take(1500), instruction), listener)
        return ThesisAiProtocol.parseDestination(text, snapshot)
    }

    override suspend fun suggestImagePlace(snapshot: ThesisSnapshot, description: String, listener: AiProgressListener): DestinationSuggestion {
        val text = run(ThesisAiProtocol.imageSystem(snapshot.meta.language), "Outline:\n${ThesisContext.outline(snapshot)}\n\nImage description from the user: ${description.take(800)}", listener)
        return ThesisAiProtocol.parseDestination(text, snapshot)
    }

    override suspend fun edit(snapshot: ThesisSnapshot, mode: EditMode, items: List<Pair<String, String>>, instruction: String, listener: AiProgressListener): EditSuggestion {
        val edits = LinkedHashMap<String, String>()
        val warnings = ArrayList<String>()
        val notes = ArrayList<String>()
        for (chunk in ThesisContext.chunks(items)) {
            val text = run(ThesisAiProtocol.editSystem(mode, snapshot.meta.language), ThesisAiProtocol.editUser(chunk, instruction), listener)
            val r = ThesisAiProtocol.parseEdits(text, chunk.toMap(), mode)
            edits.putAll(r.edits)
            warnings.addAll(r.warnings)
            if (r.notes.isNotBlank()) notes.add(r.notes)
        }
        return EditSuggestion(edits, notes.joinToString(" "), warnings)
    }

    /**
     * The section's identity, rules and focused context go to the provider; the answer is checked
     * locally (language, personal sections without research content or citations, no invented
     * names or figures) and asked again once with the reasons. What still fails is returned with
     * warnings for the user's review, never applied silently.
     */
    override suspend fun write(snapshot: ThesisSnapshot, context: SectionWriteContext, listener: AiProgressListener): EditSuggestion {
        val system = SectionWritingProtocol.system(context)
        var (text, notes) = SectionWritingProtocol.parse(run(system, SectionWritingProtocol.user(context), listener))
        var issues = SectionWritingProtocol.check(context, text)
        if (issues.isNotEmpty()) {
            val retry = try {
                SectionWritingProtocol.parse(run(system, SectionWritingProtocol.user(context, issues.map { it.second }), listener))
            } catch (e: ThesisAiFormatException) { null }
            if (retry != null) {
                val retryIssues = SectionWritingProtocol.check(context, retry.first)
                if (retryIssues.size <= issues.size) { text = retry.first; notes = retry.second; issues = retryIssues }
            }
        }
        if (text.isBlank()) throw ThesisAiFormatException("The provider returned no text")
        val source = context.target
        // The integrity checks of edits (citations and numbers kept) apply to edits of existing text.
        val integrity = if (context.mode == EditMode.GENERATE || context.mode == EditMode.CONTINUE || source.isBlank()) emptyList()
            else ThesisAiProtocol.parseEdits(Json.write(mapOf("edits" to listOf(mapOf("id" to "text", "text" to text)))), mapOf("text" to source), context.mode).warnings
        val unchanged = text.trim() == source.trim()
        return EditSuggestion(if (unchanged) emptyMap() else mapOf("text" to text), notes, SectionWritingProtocol.warningCodes(issues) + integrity)
    }

    override suspend fun table(snapshot: ThesisSnapshot, data: String, listener: AiProgressListener): TableSuggestion {
        val text = run(ThesisAiProtocol.tableSystem(snapshot.meta.language), "Data:\n${data.take(8000)}", listener)
        return ThesisAiProtocol.parseTable(text, data)
    }
}
