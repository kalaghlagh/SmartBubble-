package com.example.agent.thesis

import com.example.agent.core.IntentAnalyzer
import com.example.agent.core.LibraryRef

/** What to do with a thesis document request. */
sealed class ThesisDecision {
    data class Plan(val request: ThesisRequest, val source: LibraryRef?) : ThesisDecision()

    /**
     * One focused question first: [what] is "section", "text", "source", "pages", "source_format"
     * or "source_is_target"; [candidates] are files to pick from (the user chooses one).
     */
    data class Ask(val what: String, val candidates: List<LibraryRef> = emptyList()) : ThesisDecision()
}

/**
 * Decides how a chat request about documents and the thesis is handled, from the files the user
 * can use (Library and My Templates) and the files attached to the request. A source is used only
 * when the user attached it or named it exactly; otherwise the user picks it. Nothing is guessed.
 */
object ThesisRequestRouter {
    private val extractable = setOf("PDF", "PNG", "JPG", "JPEG", "WEBP")

    fun decide(text: String, files: List<LibraryRef>, selected: List<LibraryRef> = emptyList()): ThesisDecision? {
        val named = if (selected.isNotEmpty()) selected.distinctBy { it.fileId } else IntentAnalyzer.findSources(text, files)
        val spans = named.flatMap { r -> listOf(r.title, r.title.substringBeforeLast('.', r.title)) }
        val req = ThesisRequestParser.parse(text, spans) ?: return null
        // A file named like the thesis is the thesis itself (its export, for example), never the source.
        val sources = named.filter { r ->
            req.thesisTitle == null || ThesisSectionLocator.normalize(r.title.substringBeforeLast('.', r.title)) != ThesisSectionLocator.normalize(req.thesisTitle)
        }
        val needsSource = req.kind == ThesisEditKind.INSERT_PAGE_TEXT || req.kind == ThesisEditKind.INSERT_TABLE || req.kind == ThesisEditKind.EXTRACT_ONLY
        if (!needsSource) {
            // A source document named but no text given: the user means its pages; ask which.
            if (req.kind == ThesisEditKind.APPEND_TEXT && req.text == null && sources.any { it.fileType.uppercase() in extractable }) return ThesisDecision.Ask("pages")
            req.missing.firstOrNull()?.let { return ThesisDecision.Ask(it) }
            return ThesisDecision.Plan(req, null)
        }
        if (sources.isEmpty() && named.isNotEmpty()) return ThesisDecision.Ask("source_is_target")
        val usable = sources.filter { it.fileType.uppercase() in extractable }
        if (usable.size > 1) return ThesisDecision.Ask("source", usable)
        if (usable.isEmpty()) {
            if (sources.isNotEmpty()) return ThesisDecision.Ask("source_format")
            return ThesisDecision.Ask("source", IntentAnalyzer.fuzzy(text, files.filter { it.fileType.uppercase() in extractable }))
        }
        req.missing.firstOrNull()?.let { return ThesisDecision.Ask(it) }
        if (req.kind == ThesisEditKind.INSERT_PAGE_TEXT && req.pages.isEmpty()) return ThesisDecision.Ask("pages")
        return ThesisDecision.Plan(req, usable.single())
    }
}
