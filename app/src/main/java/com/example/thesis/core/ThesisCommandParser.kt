package com.example.thesis.core

import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import java.util.Locale

/** Kind of AI edit the user asked for. */
enum class EditMode { CORRECT, IMPROVE, SHORTEN, EXPAND, REWRITE }

/** What a Chat message asks the Thesis Builder to do. */
sealed class ThesisIntent {
    /** Add the given content (destination may be unknown). */
    data class AddContent(val target: ThesisTarget?, val content: String) : ThesisIntent()
    /** Replace a paragraph with content the user supplied (no AI). */
    data class ReplaceParagraph(val target: ThesisTarget, val content: String) : ThesisIntent()
    /** AI edit of existing text (only when explicitly requested). */
    data class Edit(val mode: EditMode, val target: ThesisTarget?) : ThesisIntent()
    data class AddChapter(val title: String) : ThesisIntent()
    data class AddSection(val target: ThesisTarget?, val title: String) : ThesisIntent()
    data class Rename(val target: ThesisTarget, val title: String) : ThesisIntent()
    data class MoveSection(val target: ThesisTarget, val destination: ThesisTarget) : ThesisIntent()
    data class AddHeading(val target: ThesisTarget?, val text: String) : ThesisIntent()
    data class MakeTable(val target: ThesisTarget?, val data: String) : ThesisIntent()
    object RestorePrevious : ThesisIntent()
    object ShowHistory : ThesisIntent()
    /** No instruction recognized and no content. */
    object Unknown : ThesisIntent()
}

/**
 * A place in the thesis named in a message: a section (resolved to its id) and optionally a
 * paragraph index (1-based) inside it, or an "after paragraph N" position.
 */
data class ThesisTarget(val sectionId: String, val paragraph: Int? = null, val afterParagraph: Int? = null)

/**
 * Local, deterministic interpretation of thesis instructions in English, French and Arabic. No
 * content is sent anywhere to understand a destination that is named explicitly; only when the
 * destination cannot be resolved does the caller ask the AI or the user.
 */
object ThesisCommandParser {

    private val ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "last" to -1,
        "premier" to 1, "première" to 1, "premiere" to 1, "deuxième" to 2, "deuxieme" to 2, "second" to 2, "seconde" to 2,
        "troisième" to 3, "troisieme" to 3, "quatrième" to 4, "quatrieme" to 4, "cinquième" to 5, "cinquieme" to 5, "dernier" to -1, "dernière" to -1,
        "الأول" to 1, "الاول" to 1, "الأولى" to 1, "الاولى" to 1, "الثاني" to 2, "الثانية" to 2, "الثالث" to 3, "الثالثة" to 3,
        "الرابع" to 4, "الرابعة" to 4, "الخامس" to 5, "الخامسة" to 5, "الأخير" to -1, "الأخيرة" to -1
    )
    private const val NUM = """(\d+(?:\.\d+)*|[\p{L}]+)"""
    private val CHAPTER = Regex("""(?:chapter|chapitre|chap\.?|الفصل)\s*$NUM""", RegexOption.IGNORE_CASE)
    private val SECTION = Regex("""(?:section|sous-section|subsection|المبحث|القسم|المطلب)\s*$NUM""", RegexOption.IGNORE_CASE)
    private val PARAGRAPH = Regex("""(?:paragraph|paragraphe|para\.?|الفقرة)\s*$NUM""", RegexOption.IGNORE_CASE)
    private val ORDINAL_PARAGRAPH = Regex("""(?:the\s+|le\s+|la\s+)?([\p{L}]+)\s+(?:paragraph|paragraphe)""", RegexOption.IGNORE_CASE)
    private val AFTER_PARAGRAPH = Regex("""(?:after|après|apres|بعد)\s+(?:the\s+|le\s+|la\s+)?(?:paragraph|paragraphe|الفقرة)\s*$NUM""", RegexOption.IGNORE_CASE)

    private val KIND_WORDS = mapOf(
        SectionKind.INTRODUCTION to listOf("introduction", "مقدمة", "المقدمة"),
        SectionKind.CONCLUSION to listOf("conclusion", "خاتمة", "الخاتمة"),
        SectionKind.REFERENCES to listOf("references", "référence", "reference", "bibliograph", "المراجع", "مراجع"),
        SectionKind.APPENDIX to listOf("appendix", "appendices", "annexe", "الملاحق", "ملحق"),
        SectionKind.DEDICATION to listOf("dedication", "dédicace", "dedicace", "إهداء", "الإهداء"),
        SectionKind.ACKNOWLEDGEMENTS to listOf("acknowledg", "remerciement", "شكر"),
        SectionKind.ABSTRACT to listOf("abstract", "résumé", "resume", "ملخص", "الملخص"),
        SectionKind.ABBREVIATIONS to listOf("abbreviation", "abréviation", "المختصرات")
    )

    private val EDIT_WORDS = listOf(
        EditMode.CORRECT to listOf("correct", "corrige", "fix the grammar", "grammar", "grammaire", "orthograph", "spelling", "صحح", "تصحيح", "دقق"),
        EditMode.SHORTEN to listOf("shorten", "shorter", "raccourci", "condense", "اختصر", "اختصار"),
        EditMode.EXPAND to listOf("expand", "develop", "développe", "developpe", "elaborate", "étoffe", "وسع", "توسيع", "طور"),
        EditMode.IMPROVE to listOf("improve", "academic style", "améliore", "ameliore", "style académique", "حسن", "تحسين", "أسلوب"),
        EditMode.REWRITE to listOf("rewrite", "rephrase", "reformule", "réécri", "reecri", "أعد صياغة", "إعادة صياغة")
    )

    private fun lower(s: String) = s.lowercase(Locale.ROOT)

    private fun number(token: String): Int? = token.substringBefore('.').toIntOrNull() ?: ORDINALS[lower(token)]

    /** Splits "instruction:\ncontent" (first line short and imperative) from the content. */
    fun splitInstruction(message: String): Pair<String, String> {
        val text = message.trim()
        val firstBreak = text.indexOf('\n')
        if (firstBreak in 1..300) {
            val head = text.substring(0, firstBreak).trim()
            if (looksLikeInstruction(head)) return head.trimEnd(':', '：', ' ') to text.substring(firstBreak + 1).trim()
        }
        if (text.length <= 300 && looksLikeInstruction(text)) {
            // "Add this to chapter 1: <content>" on one line.
            val colon = text.indexOf(':')
            if (colon in 1 until text.length - 1 && text.length - colon > 40) return text.substring(0, colon).trim() to text.substring(colon + 1).trim()
            return text to ""
        }
        return "" to text
    }

    private val COMMAND_WORDS = listOf(
        "add", "insert", "put", "place", "append", "replace", "move", "rename", "create", "restore", "undo", "compare", "show", "correct", "improve",
        "shorten", "expand", "rewrite", "rephrase", "fix", "ajoute", "insère", "insere", "place", "remplace", "déplace", "deplace", "renomme",
        "crée", "cree", "restaure", "annule", "compare", "affiche", "montre", "corrige", "améliore", "ameliore", "raccourcis", "développe",
        "reformule", "أضف", "اضف", "أدرج", "ضع", "استبدل", "انقل", "غير اسم", "سم", "أنشئ", "استرجع", "تراجع", "قارن", "اعرض", "صحح",
        "حسن", "اختصر", "وسع", "أعد"
    )

    /**
     * True when the text starts like a command ("Add this...", "I want to add...", "Please
     * correct..."). A command word in the middle of a sentence ("the results show that...") is
     * ordinary prose.
     */
    fun looksLikeInstruction(text: String): Boolean {
        val l = lower(text)
        return COMMAND_WORDS.any { w ->
            val i = l.indexOf(w)
            i in 0..14 && (i == 0 || l[i - 1] == ' ') && (i + w.length >= l.length || !l[i + w.length].isLetter() || TextIsArabic.isArabic(w))
        }
    }

    /** Resolves "chapter 2", "section 1.2", "the introduction" or a section title to a section. */
    fun resolveSection(instruction: String, snapshot: ThesisSnapshot, numbering: ThesisNumbering = ThesisStructure.numbering(snapshot)): ThesisSection? {
        val l = lower(instruction)
        val visible = snapshot.outline(includeDisabled = false).map { it.first }
        val chapterNo = CHAPTER.find(l)?.let { number(it.groupValues[1]) }
        val sectionNo = SECTION.find(l)?.groupValues?.get(1)
        val chapters = visible.filter { it.kind == SectionKind.CHAPTER }
        val chapter = chapterNo?.let { n -> if (n == -1) chapters.lastOrNull() else chapters.firstOrNull { numbering.sectionNumbers[it.id] == n.toString() } }
        if (sectionNo != null) {
            val full = if (sectionNo.contains('.')) sectionNo else if (chapter != null) "${numbering.sectionNumbers[chapter.id]}.$sectionNo" else null
            val bySection = full?.let { f -> visible.firstOrNull { numbering.sectionNumbers[it.id] == f && it.kind != SectionKind.CHAPTER } }
            if (bySection != null) return bySection
            number(sectionNo)?.let { n ->
                val inChapter = chapter?.let { c -> snapshot.children(c.id).filter { it.enabled } }
                inChapter?.getOrNull(if (n == -1) inChapter.lastIndex else n - 1)?.let { return it }
            }
        }
        if (chapter != null) return chapter
        for ((kind, words) in KIND_WORDS) {
            if (words.any { l.contains(it) }) visible.firstOrNull { it.kind == kind }?.let { return it }
        }
        // A section title quoted or named in the instruction.
        val quoted = Regex("""["«“]([^"»”]{2,80})["»”]""").find(instruction)?.groupValues?.get(1)?.let(::lower)
        return visible.filter { it.title.length >= 3 }.firstOrNull { s ->
            val t = lower(s.title)
            (quoted != null && quoted == t) || l.contains(t)
        }
    }

    private fun paragraphIndex(l: String): Int? =
        PARAGRAPH.find(l)?.let { number(it.groupValues[1]) } ?: ORDINAL_PARAGRAPH.find(l)?.let { ORDINALS[lower(it.groupValues[1])] }

    fun resolveTarget(instruction: String, snapshot: ThesisSnapshot): ThesisTarget? {
        val section = resolveSection(instruction, snapshot) ?: return null
        val l = lower(instruction)
        val after = AFTER_PARAGRAPH.find(l)?.let { number(it.groupValues[1]) }
        val paragraph = if (after == null) paragraphIndex(l) else null
        return ThesisTarget(section.id, paragraph, after)
    }

    private fun titleAfter(instruction: String): String {
        val quoted = Regex("""["«“]([^"»”]{1,120})["»”]""").find(instruction)?.groupValues?.get(1)
        if (quoted != null) return quoted.trim()
        val m = Regex("""(?:named|called|titled|intitulé|intitulée|nommé|nommée|بعنوان|باسم)\s+(.+)$""", RegexOption.IGNORE_CASE).find(instruction)
        return m?.groupValues?.get(1)?.trim()?.trimEnd('.') ?: ""
    }

    fun parse(message: String, snapshot: ThesisSnapshot): ThesisIntent {
        val (instruction, content) = splitInstruction(message)
        val l = lower(instruction)
        if (instruction.isEmpty()) return if (content.isBlank()) ThesisIntent.Unknown else ThesisIntent.AddContent(null, content)

        fun has(vararg words: String) = words.any { l.contains(it) }
        val target = resolveTarget(instruction, snapshot)

        if (has("restore", "restaure", "undo", "annule", "استرجع", "تراجع") && has("version", "previous", "précédente", "precedente", "السابقة", "undo", "annule", "تراجع")) {
            return ThesisIntent.RestorePrevious
        }
        if (has("history", "historique", "سجل", "compare", "قارن", "changes made", "modifications", "التغييرات")) return ThesisIntent.ShowHistory
        if (has("table", "tableau", "جدول") && has("create", "make", "crée", "cree", "fais", "أنشئ", "اصنع", "from this data", "à partir")) {
            return ThesisIntent.MakeTable(target, content)
        }
        if (has("rename", "renomme", "غير اسم", "أعد تسمية")) {
            val title = titleAfter(instruction).ifBlank { Regex("""\s(?:to|en|إلى|الى)\s+(.+)$""").find(instruction)?.groupValues?.get(1)?.trim() ?: "" }
            if (target != null && title.isNotBlank()) return ThesisIntent.Rename(target, title)
        }
        if (has("move", "déplace", "deplace", "انقل") && content.isBlank()) {
            val parts = instruction.split(Regex("""\s(?:to|into|vers|dans|إلى|الى)\s""", RegexOption.IGNORE_CASE), limit = 2)
            if (parts.size == 2) {
                val from = resolveTarget(parts[0], snapshot)
                val to = resolveTarget(parts[1], snapshot)
                if (from != null && to != null) return ThesisIntent.MoveSection(from, to)
            }
        }
        if (has("chapter", "chapitre", "فصل") && has("add", "create", "new", "ajoute", "crée", "cree", "nouveau", "أضف", "اضف", "أنشئ", "جديد") && content.isBlank() &&
            CHAPTER.find(l)?.let { number(it.groupValues[1]) } == null
        ) {
            return ThesisIntent.AddChapter(titleAfter(instruction))
        }
        if (has("section", "مبحث", "قسم") && has("add", "create", "new", "ajoute", "crée", "nouvelle", "أضف", "اضف", "أنشئ") && content.isBlank()) {
            return ThesisIntent.AddSection(target, titleAfter(instruction))
        }
        if (has("heading", "titre", "عنوان") && has("add", "ajoute", "أضف", "اضف")) {
            return ThesisIntent.AddHeading(target, titleAfter(instruction).ifBlank { content })
        }
        if (has("replace", "remplace", "استبدل") && content.isNotBlank() && target?.paragraph != null) {
            return ThesisIntent.ReplaceParagraph(target, content)
        }
        for ((mode, words) in EDIT_WORDS) {
            if (words.any { l.contains(it) } && content.isBlank()) return ThesisIntent.Edit(mode, target)
        }
        if (content.isNotBlank()) return ThesisIntent.AddContent(target, content)
        // A long text that matched no command is content, not an instruction.
        if (message.trim().length > 100) return ThesisIntent.AddContent(null, message.trim())
        return ThesisIntent.Unknown
    }
}

private object TextIsArabic {
    fun isArabic(text: String) = text.any { it in '\u0600'..'\u06FF' }
}
