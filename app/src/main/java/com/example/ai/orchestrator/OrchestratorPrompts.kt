package com.example.ai.orchestrator

import com.example.ai.providers.AiMessage
import com.example.ai.providers.AiRole

/** Content operations the orchestrator performs on supplied text. */
enum class ContentOperation {
    GENERATE, REWRITE, SUMMARIZE, TRANSLATE, CORRECT, IMPROVE_FORMAT, EXPAND, SHORTEN, ANSWER_QUESTION;

    companion object {
        /** Maps the chat follow-up action keys used by the UI. */
        fun fromActionKey(key: String): ContentOperation? = when (key.uppercase()) {
            "SPELLING", "CORRECT", "GRAMMAR" -> CORRECT
            "FORMAT", "IMPROVE_FORMAT" -> IMPROVE_FORMAT
            "TRANSLATE" -> TRANSLATE
            "SUMMARIZE" -> SUMMARIZE
            "REWRITE" -> REWRITE
            "EXPAND" -> EXPAND
            "SHORTEN" -> SHORTEN
            "GENERATE" -> GENERATE
            "ANSWER", "ANSWER_QUESTION", "QUESTION" -> ANSWER_QUESTION
            else -> null
        }
    }
}

/** A stored chat message plus the text of the document attached to it (if any). */
data class ContextMessage(val isUser: Boolean, val text: String, val attachedDocumentText: String? = null, val attachedDocumentTitle: String? = null)

/** Builds bounded conversation context for providers. Pure Kotlin. */
object ConversationContext {
    const val MAX_MESSAGES = 40
    const val MAX_TOTAL_CHARS = 60_000
    const val MAX_DOCUMENT_CHARS = 12_000

    fun build(history: List<ContextMessage>, newUserMessage: String): List<AiMessage> {
        val recent = history.takeLast(MAX_MESSAGES)
        val out = ArrayList<AiMessage>()
        var budget = MAX_TOTAL_CHARS - newUserMessage.length
        // Walk from newest to oldest so the most recent context is kept when trimming.
        for (m in recent.asReversed()) {
            val text = buildString {
                append(m.text)
                if (!m.attachedDocumentText.isNullOrBlank()) {
                    append("\n\n[Attached document")
                    m.attachedDocumentTitle?.let { append(": ").append(it) }
                    append("]\n")
                    append(m.attachedDocumentText.take(MAX_DOCUMENT_CHARS))
                }
            }
            if (text.isBlank()) continue
            if (text.length > budget) break
            budget -= text.length
            out += AiMessage(if (m.isUser) AiRole.USER else AiRole.ASSISTANT, text)
        }
        out.reverse()
        out += AiMessage(AiRole.USER, newUserMessage)
        return out
    }
}

/** System prompts used by the Central AI Orchestrator. */
object OrchestratorPrompts {

    fun languageName(code: String): String = when (code.lowercase()) {
        "ar" -> "Arabic"
        "fr" -> "French"
        else -> "English"
    }

    fun planner(uiLanguage: String, templateNote: String?): String = """
You are the central orchestrator of SmartBubble, an assistant that answers questions and creates editable documents (Word DOCX, PowerPoint PPTX, Excel XLSX and PDF).

Decide whether the user's latest message asks you to produce a document file, then respond with ONLY one JSON object, no other text:
- To answer in the chat: {"intent":"chat","reply":"<your complete answer, Markdown allowed>"}
- To produce a file: {"intent":"document","format":"DOCX|PPTX|XLSX|PDF","title":"<document title>","brief":"<a precise, detailed brief of everything the document must contain, based on the user's request and the conversation>","reply":"<one short sentence telling the user what you are preparing>"}

Rules:
- Choose "document" only when the user asks for a file, document, report, letter, presentation, slides, spreadsheet or table file to be produced. Questions about content, rewriting text in the chat, translations of short text, and general conversation are "chat".
- Format: PPTX for presentations or slides; XLSX for spreadsheets, budgets and data tables; PDF only when PDF is explicitly requested; otherwise DOCX.
- Write "reply" (and the document title and brief) in the language the user writes in. If unclear, use ${languageName(uiLanguage)}. Keep Arabic text exactly as Arabic (right-to-left); never transliterate it.
- Use the conversation and any attached document text as context. Do not claim that a file already exists or was created.
- If you need missing information to answer, ask for it in a chat reply.
${templateNote?.let { "- $it" } ?: ""}
""".trim()

    private const val WORD_SCHEMA =
        """{"title":"string","subtitle":"string (optional)","sections":[{"heading":"string","paragraphs":["string"],"bullets":["string"],"table":{"headers":["string"],"rows":[["string"]]}}]}"""
    private const val PPTX_SCHEMA =
        """{"title":"string","subtitle":"string (optional)","slides":[{"title":"string","subtitle":"string (optional)","bullets":["string"],"metric":"short key figure (optional)","metric_label":"string (optional)"}]}"""
    private const val XLSX_SCHEMA =
        """{"title":"string","sheets":[{"name":"string (max 31 chars)","headers":["string"],"rows":[["string or number"]],"total_label":"string (optional)","total_formula":"SUM/AVERAGE/MIN/MAX/COUNT over a cell range, e.g. SUM(B2:B9) (optional)"}]}"""

    fun documentWriter(format: DocumentFormat, uiLanguage: String, templateDescription: String?): String {
        val (kind, schema, guidance) = when (format) {
            DocumentFormat.DOCX, DocumentFormat.PDF -> Triple(
                if (format == DocumentFormat.PDF) "a PDF document" else "a Word document",
                WORD_SCHEMA,
                "Write complete, real content (no placeholders such as [Name] unless the user asked for a fill-in template). Use sections with headings; use bullets for lists and \"table\" only for genuinely tabular data. Omit empty fields."
            )
            DocumentFormat.PPTX -> Triple(
                "a PowerPoint presentation",
                PPTX_SCHEMA,
                "Create 4-12 slides unless the user specified a number. Keep bullets concise (max ~6 per slide). Use \"metric\" only for a real key figure from the content."
            )
            DocumentFormat.XLSX -> Triple(
                "an Excel workbook",
                XLSX_SCHEMA,
                "Put numbers as JSON numbers (not strings) so they can be calculated. Every row must have the same number of cells as \"headers\". Only use total_formula when a total makes sense; rows start at row 2 under the header row."
            )
        }
        return """
You are SmartBubble's document writer. Produce the content of $kind that fulfils the brief and the user's request.

Respond with ONLY one JSON object matching this structure, no other text and no code fences:
$schema

$guidance
- Write the content in the language requested by the user; if not specified, in the language of the request (default ${languageName(uiLanguage)}). Keep Arabic text as Arabic.
- Base the content only on the user's request, the brief and the supplied conversation/document text. Do not invent facts presented as real data about the user (names, figures) unless asked to draft illustrative content.
${templateDescription?.let { "- The document will be rendered with the user's template. Fit the content to it: $it" } ?: ""}
""".trim()
    }

    fun repair(error: String): String =
        "Your previous response could not be used: $error Return ONLY the corrected JSON object with the same structure, and nothing else."

    fun contentOperation(op: ContentOperation, uiLanguage: String, targetLanguage: String?): String {
        val task = when (op) {
            ContentOperation.GENERATE -> "Write the requested content."
            ContentOperation.REWRITE -> "Rewrite the supplied content to improve clarity and style while keeping its meaning and language."
            ContentOperation.SUMMARIZE -> "Summarize the supplied content accurately and concisely, in the same language as the content."
            ContentOperation.TRANSLATE -> {
                val target = targetLanguage?.let { languageName(it) } ?: languageName(uiLanguage)
                "Translate the supplied content into $target. If it is already in $target, translate it into English instead. Preserve meaning, headings, lists, tables and formatting (use Markdown for structure)."
            }
            ContentOperation.CORRECT -> "Correct spelling, grammar, punctuation and obvious OCR errors in the supplied content. Keep its language, meaning and structure; do not add new content."
            ContentOperation.IMPROVE_FORMAT -> "Improve the structure and formatting of the supplied content (headings, paragraphs, lists, tables in Markdown) without changing its meaning or language."
            ContentOperation.EXPAND -> "Expand the supplied content with relevant detail, keeping its language, tone and structure."
            ContentOperation.SHORTEN -> "Shorten the supplied content while keeping the key information, its language and structure."
            ContentOperation.ANSWER_QUESTION -> "Answer the user's question using only the supplied content. If the content does not contain the answer, say so."
        }
        return """
You are SmartBubble's writing assistant. $task
Return only the resulting text (Markdown allowed), without any preamble or explanation. Keep Arabic text as Arabic (right-to-left).
""".trim()
    }

    const val OCR_CLEANUP = """You clean up text recognized by OCR from a scanned page. Fix recognition errors, broken words and line breaks, keep the original language(s) and wording, keep headings, lists and table rows (use " | " between table cells). Do not translate, summarize, add or remove content. Return only the cleaned text."""
}
