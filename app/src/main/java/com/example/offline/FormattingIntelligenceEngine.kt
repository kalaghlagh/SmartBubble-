package com.example.offline

import com.example.offline.analysis.DocumentTypeClassifier
import com.example.offline.analysis.TextStructureAnalyzer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The replaceable "intelligence" of the Offline Formatting Agent: turns pasted text into a
 * [FormattingResult]. Phase 1 ships only [RuleBasedFormattingEngine]. A future on-device model
 * (e.g. a LocalAiFormattingEngine) implements this same interface, so the Chat UI, template
 * matching, the result contract, the document engines and the Library integration stay as they
 * are.
 *
 * Contract for every implementation:
 *  - runs entirely on the device (no network, no AI provider, no API key);
 *  - organizes the text without rewriting it: no summarizing, translating, correcting or adding;
 *  - returns a result whose [FormattingResult.preservation] is lossless, falling back to the
 *    original paragraphs when its own structure cannot be verified;
 *  - stops promptly when the calling coroutine is cancelled.
 */
interface FormattingIntelligenceEngine {
    /** Short stable identifier stored with results (e.g. "rule-based"). */
    val id: String

    suspend fun analyze(pastedText: String): FormattingResult
}

/**
 * Phase 1 engine: deterministic rules only (see [TextStructureAnalyzer] and
 * [DocumentTypeClassifier]). No language model is loaded or downloaded.
 */
class RuleBasedFormattingEngine(
    private val analyzer: TextStructureAnalyzer = TextStructureAnalyzer()
) : FormattingIntelligenceEngine {

    override val id: String = ID

    companion object {
        const val ID = "rule-based"

        /** Larger texts are refused (the composer itself becomes slow well before this). */
        const val MAX_CHARACTERS = 200_000
    }

    override suspend fun analyze(pastedText: String): FormattingResult {
        require(pastedText.length <= MAX_CHARACTERS) { "Text too long for offline formatting" }
        val context = currentCoroutineContext()
        val source = TextNormalizationEngine.normalize(pastedText)
        context.ensureActive()

        val analysis = analyzer.analyze(source) { context.ensureActive() }
        context.ensureActive()
        val report = ContentPreservation.verify(source, analysis.removedMarkup, analysis.blocks)
        val (blocks, markup, finalReport, fallback) = if (report.isLossless) {
            Quad(analysis.blocks, analysis.removedMarkup, report, false)
        } else {
            // The inferred structure could not be verified: keep the original paragraphs.
            val original = originalParagraphs(source)
            Quad(original, emptyList(), ContentPreservation.verify(source, emptyList(), original), true)
        }
        context.ensureActive()
        return FormattingResult(
            sourceText = source,
            blocks = blocks,
            documentType = DocumentTypeClassifier.classify(source, blocks),
            removedMarkup = markup,
            preservation = finalReport,
            usedFallback = fallback,
            engineId = id
        )
    }

    private data class Quad(
        val blocks: List<StructureBlock>,
        val markup: List<SourceSpan>,
        val report: PreservationReport,
        val fallback: Boolean
    )

    /** One paragraph per blank-line-separated block, text unchanged except whitespace. */
    internal fun originalParagraphs(source: String): List<StructureBlock> {
        val out = ArrayList<StructureBlock>()
        var start = 0
        while (start < source.length) {
            var end = source.indexOf("\n\n", start)
            if (end < 0) end = source.length
            val raw = source.substring(start, end)
            if (raw.isNotBlank()) {
                val lead = raw.length - raw.trimStart().length
                out.add(
                    StructureBlock.Paragraph(
                        TextNormalizationEngine.collapseSpaces(raw.replace('\n', ' ')),
                        SourceSpan(start + lead, start + raw.trimEnd().length)
                    )
                )
            }
            start = end + 2
        }
        return out
    }
}
