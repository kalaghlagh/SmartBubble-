package com.example.thesis.ai

import com.example.ai.providers.Json
import com.example.thesis.core.EditMode

/**
 * Prompts and local checks for writing inside one thesis section. The provider is told which
 * section it writes (its semantic type, title, chapter and place in the outline), the rules of
 * that kind of text, the thesis information, focused excerpts of related sections and the
 * current text; its answer is checked locally and, if it breaks a rule, asked again once.
 */
object SectionWritingProtocol {

    private fun languageName(code: String) = when (code) { "ar" -> "Arabic"; "fr" -> "French"; else -> "English" }

    /**
     * Language the text must be written in: the thesis language, except for an abstract whose
     * title is in another language ("Résumé" in an English thesis, "الملخص").
     */
    fun expectedLanguage(ctx: SectionWriteContext): String {
        if (ctx.type == SectionType.ABSTRACT) {
            val raw = ctx.sectionTitle
            val n = SectionClassifier.normalize(raw)
            val arabic = raw.any { it in '؀'..'ۿ' }
            val latin = raw.any { it in 'A'..'Z' || it in 'a'..'z' }
            if (arabic && !latin) return "ar"
            if (!raw.contains('/')) {
                if (Regex("\\bresume\\b").containsMatchIn(n)) return "fr"
                if (Regex("\\babstract\\b").containsMatchIn(n)) return "en"
            }
        }
        return ctx.language
    }

    private fun task(mode: EditMode): String = when (mode) {
        EditMode.GENERATE -> "Write a complete draft of this section. If the section already has text, write a draft that is consistent with it."
        EditMode.CONTINUE -> "Continue the existing text of this section from where it stops. Return ONLY the new continuation (do not repeat the existing text), in the same tone and terminology."
        EditMode.IMPROVE -> "Improve the target text (clarity, register appropriate to this section, cohesion) while keeping its meaning and every fact."
        EditMode.REWRITE -> "Rephrase the target text in different words, keeping its meaning and every fact, in the register appropriate to this section."
        EditMode.EXPAND -> "Develop the target text with explanations that follow from what is written. Do not add facts, figures, results or references."
        EditMode.SHORTEN -> "Shorten the target text while keeping its key points and every citation, number and name."
        EditMode.SUMMARIZE -> "Summarize the target text in a few sentences, keeping the key points, citations, numbers and names it contains."
        EditMode.CORRECT -> "Correct grammar, spelling and punctuation of the target text only. Do not change meaning, wording, terminology or structure."
        EditMode.ORGANIZE -> "Organize the target text: coherent paragraphs, enumerations as lists (one item per line starting with \"- \"), logical order. Keep the wording and every fact."
    }

    fun system(ctx: SectionWriteContext): String {
        val rules = ctx.rules
        val lang = languageName(expectedLanguage(ctx))
        return buildString {
            append("You write inside one section of an academic thesis (").append(ctx.level).append("). ")
            append("Section being written: \"").append(ctx.sectionTitle).append("\" — section type: ").append(ctx.type.name).append(".\n")
            append("What this section is: ").append(rules.purpose).append('\n')
            append("Rules for this section:\n")
            rules.guidance.forEach { append("- ").append(it).append('\n') }
            if (ctx.mode == EditMode.GENERATE || ctx.mode == EditMode.CONTINUE) {
                append("- Length: about ").append(rules.words.first).append(" to ").append(rules.words.last).append(" words unless the user asks otherwise.\n")
            }
            append("- Write only the content of THIS section: no other section's content, no section title, no notes to the user inside the text.\n")
            append("- Write in ").append(lang).append(".\n")
            append("Task: ").append(task(ctx.mode)).append('\n')
            append("Lines that contain only a placeholder in square brackets, such as [Figure 1] or [Table 2], stand for figures and tables: keep each of them unchanged on its own line.\n")
            append("Answer with JSON only: {\"text\": \"<the text>\", \"notes\": \"<one short sentence for the user>\"}.")
        }
    }

    fun user(ctx: SectionWriteContext, retryReasons: List<String> = emptyList()): String = buildString {
        append("Thesis title: ").append(ctx.thesisTitle).append('\n')
        if (ctx.subtitle.isNotBlank()) append("Subtitle: ").append(ctx.subtitle).append('\n')
        if (ctx.specialization.isNotBlank()) append("Specialization: ").append(ctx.specialization).append('\n')
        append("Section: ").append(ctx.sectionTitle).append(" (type ").append(ctx.type.name)
        if (ctx.classification.source == "parent") append(", part of its chapter")
        append(")\n")
        ctx.parentTitle?.let { append("Inside: ").append(it).append('\n') }
        ctx.previousSection?.let { append("Previous section: ").append(it).append('\n') }
        ctx.nextSection?.let { append("Next section: ").append(it).append('\n') }
        if (ctx.type == SectionType.INTRODUCTION || ctx.type == SectionType.CONCLUSION) {
            append("Outline of the thesis: ").append(ctx.outlineTitles.joinToString(" | ")).append('\n')
        }
        if (ctx.type == SectionType.ACKNOWLEDGEMENTS) {
            append("People named in the thesis information (the only names you may use): ")
                .append(ctx.namedPeople.joinToString(", ").ifEmpty { "(none — use placeholders)" }).append('\n')
        }
        for ((title, excerpt) in ctx.related) append("\nExcerpt of \"").append(title).append("\":\n").append(excerpt).append('\n')
        append("\nCurrent text of this section:\n").append(ctx.existingText.ifBlank { "(empty)" }).append('\n')
        if (ctx.selection.isNotBlank()) append("\nTarget text (the selection the task applies to):\n").append(ctx.selection).append('\n')
        else if (ctx.mode != EditMode.GENERATE && ctx.mode != EditMode.CONTINUE) append("\nTarget text: the whole current text above.\n")
        append("\nUser instruction: ").append(ctx.instruction.ifBlank { "(none)" }).append('\n')
        if (retryReasons.isNotEmpty()) {
            append("\nYour previous answer was rejected for these reasons; write it again and fix them:\n")
            retryReasons.forEach { append("- ").append(it).append('\n') }
        }
    }

    /** The text of the provider's JSON answer (or the whole answer when it is plain text). */
    fun parse(answer: String): Pair<String, String> {
        val o = Json.extractObject(answer)
        if (o != null) {
            val text = (o["text"] as? String)
                ?: Json.arr(o["edits"])?.firstNotNullOfOrNull { Json.obj(it)?.get("text") as? String }
                ?: throw ThesisAiFormatException("The response has no text")
            return text.trim() to (o["notes"] as? String).orEmpty().take(400)
        }
        val plain = answer.trim()
        if (plain.isEmpty() || plain.startsWith("{")) throw ThesisAiFormatException("The provider did not return JSON")
        return plain to ""
    }

    // ------------------------------------------------------------------ local checks

    private val CITATION = Regex("""\([^()]{0,80}\b(?:1[5-9]|20)\d{2}[a-z]?\)|\[\d+(?:[,–-]\s*\d+)*]|\bet al\.""")
    private val NUMBER = Regex("""\d+(?:[.,]\d+)+%?|\d{2,}%?|\d+%""")
    private val ACADEMIC = Regex(
        "methodolog|hypoth|research question|this study|cette etude|problematique|results show|les resultats|statistic|المنهجيه|الفرضيه|الاشكاليه|هذه الدراسه|النتائج"
    )
    private val DISCLAIMER = Regex("as an ai|as a language model|en tant qu.?ia|en tant qu.?intelligence|en tant que modele|بصفتي نموذج|كنموذج ذكاء|i cannot|je ne peux pas")
    private val TITLE_NAME = Regex("""(?:\bDr\.?|\bPr\.?|\bProf\.?|\bProfessor|\bProfesseur|\bDocteur|الدكتور|الدكتوره|الاستاذ|الاستاذه)\s+([\p{L}][\p{L}'-]+)""")

    /** Share of letters that are Arabic. */
    fun arabicShare(text: String): Double {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return 0.0
        return letters.count { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }.toDouble() / letters.length
    }

    /**
     * Problems of [output] for this section, as codes with a reason for the retry prompt. An
     * empty list means the text can be shown for review.
     */
    fun check(ctx: SectionWriteContext, output: String): List<Pair<String, String>> {
        val issues = ArrayList<Pair<String, String>>()
        val text = output.trim()
        if (text.isEmpty()) return listOf("empty" to "The answer was empty.")
        val n = SectionClassifier.normalize(text)
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        when (expectedLanguage(ctx)) {
            "ar" -> if (arabicShare(text) < 0.5) issues += "wrong_language" to "The text must be written in Arabic."
            else -> if (arabicShare(text) > 0.3) issues += "wrong_language" to "The text must be written in ${languageName(expectedLanguage(ctx))}, not Arabic."
        }
        if (DISCLAIMER.containsMatchIn(n)) issues += "ai_disclaimer" to "Do not talk about yourself or your limits; write only the section text."
        val personal = ctx.type == SectionType.DEDICATION || ctx.type == SectionType.ACKNOWLEDGEMENTS
        if (personal) {
            if (ACADEMIC.containsMatchIn(n)) issues += "not_personal" to "This is a ${ctx.type.name.lowercase()}: no research content (methodology, hypotheses, results)."
            if (CITATION.containsMatchIn(text)) issues += "citation" to "No citations or references in a ${ctx.type.name.lowercase()}."
            if (words > ctx.rules.words.last * 2) issues += "too_long" to "Too long for a ${ctx.type.name.lowercase()}: keep it under ${ctx.rules.words.last} words."
        }
        // Titles followed by a name nobody gave (Dr. Martin...): invented people.
        val known = SectionClassifier.normalize((ctx.namedPeople + ctx.instruction + ctx.existingText + ctx.selection).joinToString(" "))
        for (m in TITLE_NAME.findAll(text)) {
            val name = SectionClassifier.normalize(m.groupValues[1])
            if (name.isNotEmpty() && !known.contains(name)) { issues += "invented_name" to "Do not invent names of people (\"${m.value}\"); use [name]."; break }
        }
        // New figures that appear nowhere in what was supplied: invented data.
        if (ctx.mode != EditMode.SHORTEN && ctx.mode != EditMode.SUMMARIZE) {
            val supplied = (listOf(ctx.existingText, ctx.selection, ctx.instruction, ctx.thesisTitle, ctx.subtitle) + ctx.outlineTitles + ctx.related.map { it.second })
                .joinToString(" ")
            val allowed = NUMBER.findAll(supplied).map { it.value }.toSet()
            val invented = NUMBER.findAll(text).map { it.value }.filter { it !in allowed }.toList()
            if (invented.isNotEmpty()) issues += "invented_numbers" to "Do not add numbers or results that were not supplied (${invented.take(3).joinToString(", ")}); use [to complete]."
        }
        if (ctx.mode == EditMode.CONTINUE && ctx.existingText.length > 80 && text.startsWith(ctx.existingText.take(60))) {
            issues += "repeated" to "Return only the continuation, not the existing text."
        }
        return issues
    }

    /** Warning codes shown to the user for checks still failing after the retry. */
    fun warningCodes(issues: List<Pair<String, String>>): List<String> = issues.map { "check_${it.first}" }.distinct()
}
