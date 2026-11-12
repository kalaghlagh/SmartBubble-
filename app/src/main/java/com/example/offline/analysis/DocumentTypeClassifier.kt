package com.example.offline.analysis

import com.example.offline.DocumentType
import com.example.offline.DocumentTypeGuess
import com.example.offline.StructureBlock
import java.util.Locale

/**
 * Local, deterministic guess of what kind of text was pasted, from keywords (English, French,
 * Arabic) and layout. This is pattern matching, not understanding: when no type scores high
 * enough the result is [DocumentType.GENERAL]. The guess is shown in the preview and never
 * changes the content.
 */
object DocumentTypeClassifier {

    const val ACCEPT = 0.5f

    /** Only the beginning of very long texts is scanned. */
    private const val SCAN_LIMIT = 20_000

    private val LETTER_OPENINGS = listOf(
        "dear ", "to whom it may concern", "cher ", "chère ", "madame,", "monsieur,", "madame, monsieur",
        "السيد", "السيدة", "تحية طيبة", "المحترم"
    )
    private val LETTER_CLOSINGS = listOf(
        "sincerely", "best regards", "kind regards", "yours faithfully", "yours truly", "cordialement",
        "veuillez agréer", "salutations distinguées", "bien à vous", "وتفضلوا", "مع خالص", "تقبلوا فائق", "وشكرا"
    )
    private val KEYWORDS: Map<DocumentType, List<String>> = mapOf(
        DocumentType.MEETING_NOTES to listOf(
            "meeting", "minutes", "attendees", "agenda", "action items", "next steps", "participants",
            "réunion", "compte rendu", "compte-rendu", "ordre du jour", "présents", "décisions",
            "اجتماع", "محضر", "الحضور", "جدول الأعمال", "القرارات"
        ),
        DocumentType.REPORT to listOf(
            "report", "executive summary", "findings", "conclusion", "introduction", "results", "analysis",
            "rapport", "résumé", "résultats", "analyse", "recommandations", "recommendations",
            "تقرير", "ملخص", "الخلاصة", "المقدمة", "النتائج", "التحليل", "التوصيات"
        ),
        DocumentType.PROPOSAL to listOf(
            "proposal", "we propose", "proposed", "budget", "timeline", "objectives", "deliverables",
            "proposition", "nous proposons", "objectifs", "calendrier", "livrables",
            "مقترح", "نقترح", "الميزانية", "الأهداف", "الجدول الزمني"
        )
    )

    fun classify(text: String, blocks: List<StructureBlock>): DocumentTypeGuess {
        val sample = text.take(SCAN_LIMIT)
        val lower = sample.lowercase(Locale.ROOT)
        val scores = HashMap<DocumentType, Float>()
        val signals = HashMap<DocumentType, MutableList<String>>()

        fun hit(type: DocumentType, signal: String, weight: Float) {
            scores[type] = (scores[type] ?: 0f) + weight
            signals.getOrPut(type) { mutableListOf() }.add(signal)
        }

        // Letters: greeting near the start, closing near the end.
        val head = lower.take(300)
        val tail = lower.takeLast(400)
        LETTER_OPENINGS.firstOrNull { head.contains(it) }?.let { hit(DocumentType.LETTER, it.trim(), 0.4f) }
        LETTER_CLOSINGS.firstOrNull { tail.contains(it) }?.let { hit(DocumentType.LETTER, it, 0.4f) }

        for ((type, words) in KEYWORDS) {
            for (keyword in words) {
                if (containsWord(lower, keyword)) hit(type, keyword, 0.2f)
            }
        }

        val headings = blocks.count { it is StructureBlock.Heading }
        if (headings >= 3) hit(DocumentType.REPORT, "$headings headings", 0.15f)

        // Structured data: tables hold most of the text.
        val tableChars = blocks.filterIsInstance<StructureBlock.Table>().sumOf { it.span.end - it.span.start }
        if (tableChars > 0 && tableChars * 10 >= text.length * 6) hit(DocumentType.STRUCTURED_DATA, "tables", 0.9f)

        val best = scores.maxByOrNull { it.value }
        return if (best == null || best.value < ACCEPT) {
            DocumentTypeGuess(DocumentType.GENERAL, 1f - (best?.value ?: 0f), emptyList())
        } else {
            DocumentTypeGuess(best.key, best.value.coerceAtMost(1f), signals[best.key].orEmpty().distinct().take(6))
        }
    }

    /** Whole-word match for Latin keywords; Arabic keywords may carry attached prefixes (ال، و). */
    private fun containsWord(text: String, keyword: String): Boolean {
        if (TextTraits.hasArabic(keyword)) return text.contains(keyword)
        var from = 0
        while (true) {
            val i = text.indexOf(keyword, from)
            if (i < 0) return false
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + keyword.length)
            if ((before == null || !before.isLetter()) && (after == null || !after.isLetter())) return true
            from = i + 1
        }
    }
}
