package com.example.thesis.ai

import com.example.ai.providers.Json
import com.example.thesis.core.EditMode
import com.example.thesis.model.ThesisSnapshot

/** Destination suggested by the default AI provider for content whose place was not named. */
data class DestinationSuggestion(val sectionId: String?, val confidence: Double, val reason: String, val question: String)

/** Edits returned by the provider, already checked against the request. */
data class EditSuggestion(val edits: Map<String, String>, val notes: String, val warnings: List<String>)

data class TableSuggestion(val header: List<String>, val rows: List<List<String>>, val warnings: List<String>)

class ThesisAiFormatException(message: String) : Exception(message)

/**
 * Prompts and strict response validation for thesis tasks run through the default provider.
 * Every prompt states the academic-integrity rules; every response is checked locally (ids must
 * be ones that were sent, numbers and citations must be kept, table cells must come from the
 * supplied data) before anything is shown to the user as a proposal.
 */
object ThesisAiProtocol {

    private fun languageName(code: String) = when (code) { "ar" -> "Arabic"; "fr" -> "French"; else -> "English" }

    private const val INTEGRITY = """Academic integrity rules (mandatory):
- Never invent research results, measurements, quotations, references, DOI identifiers or sources.
- Never claim a source was verified. Anything uncertain must be labelled "[to verify]".
- Keep every existing citation, reference, number, date, unit and name exactly as written.
- Do not translate the text. Answer with JSON only."""

    fun destinationSystem(language: String) = """You classify where new content belongs in a ${languageName(language)} academic thesis.
You receive the thesis outline (section ids, kinds, titles) and an excerpt of the new content.
Return {"sectionId": "<one id from the outline or null>", "confidence": 0.0-1.0, "reason": "<short>", "question": "<one short clarification question if unsure, else empty>"}.
Only choose sections that are not "generated". If several sections fit equally, return null with a question.
$INTEGRITY"""

    fun destinationUser(outline: String, excerpt: String, instruction: String) =
        "Outline:\n$outline\n\nUser instruction: ${instruction.ifBlank { "(none)" }}\n\nNew content (excerpt):\n$excerpt"

    fun parseDestination(text: String, snapshot: ThesisSnapshot): DestinationSuggestion {
        val o = Json.extractObject(text) ?: throw ThesisAiFormatException("The provider did not return JSON")
        val id = (o["sectionId"] as? String)?.takeIf { sid -> snapshot.section(sid)?.let { it.enabled && it.kind.writable } == true }
        val confidence = (o["confidence"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.0
        return DestinationSuggestion(id, if (id == null) 0.0 else confidence, (o["reason"] as? String).orEmpty().take(300), (o["question"] as? String).orEmpty().take(300))
    }

    fun editSystem(mode: EditMode, language: String): String {
        val task = when (mode) {
            EditMode.CORRECT -> "Correct grammar, spelling and punctuation only. Do not change meaning, wording choices, terminology or structure."
            EditMode.IMPROVE -> "Improve the academic style (clarity, formal register, cohesion) while keeping the meaning and every fact."
            EditMode.SHORTEN -> "Shorten each text while keeping its key points and every citation, number and name."
            EditMode.EXPAND -> "Develop each text with explanations that follow from what is written. Do not add facts, figures, results or references; mark any statement that needs a source with [to verify]."
            EditMode.REWRITE -> "Rephrase each text in your own academic words, keeping the meaning and every fact."
        }
        return """You edit paragraphs of a ${languageName(language)} academic thesis. $task
You receive items as {"id": "...", "text": "..."}. Return {"edits": [{"id": "<same id>", "text": "<new text>"}], "notes": "<one short sentence about what changed>"}.
Return only items you changed, with exactly the ids you received. Keep the language of each text.
$INTEGRITY"""
    }

    fun editUser(items: List<Pair<String, String>>, instruction: String): String =
        "Instruction: $instruction\nItems:\n" + Json.write(items.map { mapOf("id" to it.first, "text" to it.second) })

    private val NUMBER = Regex("""\d+(?:[.,]\d+)*%?""")
    private val CITATION = Regex("""\([^()]{0,80}\b(?:1[5-9]|20)\d{2}[a-z]?\)|\[\d+(?:[,–-]\s*\d+)*]""")

    fun parseEdits(text: String, sent: Map<String, String>, mode: EditMode): EditSuggestion {
        val o = Json.extractObject(text) ?: throw ThesisAiFormatException("The provider did not return JSON")
        val list = Json.arr(o["edits"]) ?: throw ThesisAiFormatException("The response has no edits")
        val edits = LinkedHashMap<String, String>()
        val warnings = ArrayList<String>()
        for (raw in list) {
            val m = Json.obj(raw) ?: continue
            val id = m["id"] as? String ?: continue
            val newText = (m["text"] as? String)?.trim() ?: continue
            val old = sent[id]
            if (old == null) { warnings.add("ignored:$id"); continue } // never accept ids that were not sent
            if (newText.isEmpty() || newText == old.trim()) continue
            edits[id] = newText
            // Integrity checks: nothing may disappear or be invented silently.
            val oldNumbers = NUMBER.findAll(old).map { it.value }.toList()
            val newNumbers = NUMBER.findAll(newText).map { it.value }.toList()
            val oldCitations = CITATION.findAll(old).map { it.value }.toSet()
            val newCitations = CITATION.findAll(newText).map { it.value }.toSet()
            if (!oldCitations.all { it in newCitations }) warnings.add("citation_removed:$id")
            if ((newCitations - oldCitations).isNotEmpty()) warnings.add("citation_added:$id")
            if (mode != EditMode.SHORTEN && !oldNumbers.all { n -> newNumbers.contains(n) }) warnings.add("number_changed:$id")
            if ((newNumbers.toSet() - oldNumbers.toSet()).isNotEmpty()) warnings.add("number_added:$id")
            if (newText.contains("[to verify]", ignoreCase = true)) warnings.add("to_verify:$id")
        }
        return EditSuggestion(edits, (o["notes"] as? String).orEmpty().take(400), warnings)
    }

    fun tableSystem(language: String) = """You turn data supplied by the user into a table for a ${languageName(language)} academic thesis.
Return {"header": ["..."], "rows": [["...", "..."]]}. Use only values present in the data; leave a cell empty if a value is missing.
Keep every number and unit exactly as written. Do not compute new values.
$INTEGRITY"""

    /** Every non-empty cell must appear in the supplied data (no invented values). */
    fun parseTable(text: String, source: String): TableSuggestion {
        val o = Json.extractObject(text) ?: throw ThesisAiFormatException("The provider did not return JSON")
        val header = Json.arr(o["header"])?.map { it?.toString()?.trim().orEmpty() } ?: throw ThesisAiFormatException("No table header")
        val rows = Json.arr(o["rows"])?.mapNotNull { r -> Json.arr(r)?.map { it?.let { v -> if (v is Double && v % 1.0 == 0.0) v.toLong().toString() else v.toString() }?.trim().orEmpty() } }
            ?: throw ThesisAiFormatException("No table rows")
        if (header.isEmpty() || rows.isEmpty()) throw ThesisAiFormatException("Empty table")
        val normalizedSource = source.lowercase().replace(Regex("\\s+"), " ")
        val invented = (header + rows.flatten()).filter { it.isNotEmpty() && !normalizedSource.contains(it.lowercase().replace(Regex("\\s+"), " ")) }
        if (invented.isNotEmpty()) throw ThesisAiFormatException("The table contains values that are not in the data: ${invented.take(5)}")
        val width = header.size
        return TableSuggestion(header, rows.map { r -> (r + List((width - r.size).coerceAtLeast(0)) { "" }).take(width) }, emptyList())
    }

    fun imageSystem(language: String) = """You suggest where an image belongs in a ${languageName(language)} academic thesis.
You receive the outline and the user's description of the image. Return {"sectionId": "<id or null>", "afterElementId": "<element id or null>", "confidence": 0.0-1.0, "reason": "<short>", "question": "<clarification if unsure>"}.
Never describe or interpret the image content beyond the user's words.
$INTEGRITY"""
}
