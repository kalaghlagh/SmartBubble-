package com.example.smarttemplates

/**
 * What the user asked for: the kind of document, its format and language, and how sure the
 * detection is. [templateId] is filled in later by the template selection.
 */
data class DocumentIntent(
    val categoryId: String,
    val subcategoryId: String?,
    /** "DOCX", "PDF", "XLSX" or "PPTX". */
    val format: String,
    /** Language the document is written in ("ar", "fr", "en"): asked for, else that of the request. */
    val language: String,
    val confidence: Double,
    /** Facts the document usually needs that the request does not seem to give (hints for the writer and the user). */
    val missingInfo: List<String> = emptyList(),
    /** Subcategories offered to the user when the kind of document is not clear. */
    val choices: List<String> = emptyList(),
    val templateId: String? = null,
    /** Found by the local rules ("local") or by the AI planner ("ai"). */
    val source: String = "local"
) {
    val needsClarification: Boolean get() = subcategoryId == null || confidence < DocumentIntentDetector.AUTO_THRESHOLD
    val documentType: String get() = subcategoryId ?: categoryId
}

/**
 * Local, rule-based detection of document requests in English, French and Arabic (typed or
 * dictated). It runs before any AI call: a clear request ("write a recommendation letter")
 * goes straight to document generation, an unclear one is either asked about (one short
 * question with choices) or left to the AI planner, which classifies it in the same call that
 * decides between a chat answer and a document; no extra AI request is made for detection.
 */
object DocumentIntentDetector {

    const val AUTO_THRESHOLD = 0.75

    private val creationVerbs = listOf(
        "write", "create", "prepare", "make", "draft", "generate", "produce", "build", "design", "compose", "need", "want", "give me", "i'd like", "can you",
        "ecris", "ecrire", "redige", "rediger", "cree", "creer", "prepare", "preparer", "fais", "faire", "genere", "generer", "concois", "elabore", "besoin", "veux", "voudrais", "donne moi", "peux tu",
        "اكتب", "اكتبي", "انشئ", "انشي", "انشاء", "حضر", "حضري", "جهز", "جهزي", "اعد", "اعداد", "اريد", "ابغي", "اعمل", "اصنع", "صمم", "حرر", "ارجو", "احتاج", "اعطني"
    ).map(TemplateCatalog::normalize)

    private val questionStarts = listOf(
        "what", "how", "why", "when", "who", "which", "explain", "define", "is it", "are there", "difference",
        "qu'est ce", "qu est ce", "comment", "pourquoi", "quand", "quelle", "quel", "explique", "difference",
        "ما هو", "ما هي", "ماهي", "ماهو", "كيف", "لماذا", "متى", "من هو", "اشرح", "عرف", "ما الفرق", "هل"
    ).map(TemplateCatalog::normalize)

    private val politeRequests = listOf(
        "can you", "could you", "would you", "please", "peux tu", "pouvez vous", "pourrais tu", "pourriez vous", "stp", "s'il te plait", "s'il vous plait",
        "يمكنك", "ممكن", "تستطيع", "من فضلك", "لو سمحت"
    ).map(TemplateCatalog::normalize)

    private val formatWords: List<Pair<String, String>> = listOf(
        "excel" to "XLSX", "xlsx" to "XLSX", "tableur" to "XLSX", "اكسل" to "XLSX",
        "powerpoint" to "PPTX", "power point" to "PPTX", "pptx" to "PPTX", "باوربوينت" to "PPTX", "بوربوينت" to "PPTX",
        "pdf" to "PDF", "بي دي اف" to "PDF",
        "word" to "DOCX", "docx" to "DOCX", "وورد" to "DOCX", "ورد" to "DOCX"
    ).map { TemplateCatalog.normalize(it.first) to it.second }

    private val languageRequests: List<Pair<String, String>> = listOf(
        "in english" to "en", "en anglais" to "en", "بالانجليزيه" to "en", "باللغه الانجليزيه" to "en", "بالانكليزيه" to "en",
        "in french" to "fr", "en francais" to "fr", "بالفرنسيه" to "fr", "باللغه الفرنسيه" to "fr",
        "in arabic" to "ar", "en arabe" to "ar", "بالعربيه" to "ar", "باللغه العربيه" to "ar"
    ).map { TemplateCatalog.normalize(it.first) to it.second }

    private val frenchMarkers = setOf("je", "une", "un", "le", "la", "les", "des", "pour", "avec", "mon", "ma", "mes", "sur", "du", "de", "et", "est", "veux", "besoin", "lettre", "rapport", "demande")

    // Arabic clitics written together with the word: و ب ل ف ك and the article.
    private val arabicPrefixes = listOf("وال", "بال", "فال", "كال", "لل", "ال", "و", "ب", "ل", "ف", "ك")

    private fun variants(token: String): Set<String> {
        val out = linkedSetOf(token)
        if (token.any { it in '؀'..'ۿ' }) {
            for (p in arabicPrefixes) if (token.length - p.length >= 3 && token.startsWith(p)) out.add(token.removePrefix(p))
            // Accusative ending written with alef ("تقريرا ماليا").
            for (v in out.toList()) if (v.length >= 4 && v.endsWith("ا")) out.add(v.dropLast(1))
        } else {
            if (token.length > 4 && token.endsWith("s")) out.add(token.dropLast(1))
            if (token.length > 4 && token.endsWith("'s")) out.add(token.dropLast(2))
        }
        return out
    }

    /** Number of words of [phrase] when it occurs in [tokens] (each word in any of its variants), else 0. */
    private fun phraseMatch(tokens: List<Set<String>>, phrase: String): Int {
        val words = phrase.split(' ')
        if (words.isEmpty() || words.size > tokens.size) return 0
        for (start in 0..tokens.size - words.size) {
            if (words.indices.all { words[it] in tokens[start + it] }) return words.size
        }
        return 0
    }

    private fun bestMatch(tokens: List<Set<String>>, phrases: List<String>): Int = phrases.maxOfOrNull { phraseMatch(tokens, it) } ?: 0

    fun detectLanguage(text: String): String {
        val n = TemplateCatalog.normalize(text)
        val tokens = n.split(' ').map(::variants)
        for ((phrase, lang) in languageRequests) if (phraseMatch(tokens, phrase) > 0) return lang
        val letters = text.count { it.isLetter() }.coerceAtLeast(1)
        if (text.count { it in '؀'..'ۿ' } * 2 >= letters) return "ar"
        val words = n.split(' ')
        val french = words.count { it in frenchMarkers } + text.count { it in "éèêàçùâôîœ" }
        return if (french >= 2) "fr" else "en"
    }

    /**
     * The document requested in [text], or null when the text does not ask for a document the
     * catalog knows (questions, chat, unknown kinds of files): the AI planner then decides.
     */
    fun detect(text: String, defaultDocumentFormat: String = "DOCX"): DocumentIntent? {
        val normalized = TemplateCatalog.normalize(text)
        if (normalized.isBlank()) return null
        val tokens = normalized.split(' ').map(::variants)
        val verb = creationVerbs.any { phraseMatch(tokens, it) > 0 }
        // "How do I write a cover letter?" asks about a document, it does not order one; "can you
        // write ..." / "هل يمكنك ..." does.
        val polite = politeRequests.any { phraseMatch(tokens, it) > 0 }
        val question = !polite && questionStarts.any { q -> normalized.startsWith(q + " ") || normalized == q }
        if (question) return null

        val format = formatWords.firstOrNull { phraseMatch(tokens, it.first) > 0 }?.second

        // Category scores: its document nouns (longer phrases weigh more) and, more weakly, its
        // subcategory keywords. A file format named explicitly decides the kind of file.
        data class Score(val category: TemplateCategory, val noun: Int, val sub: TemplateSubcategory?, val subWeight: Int)
        val scores = TemplateCatalog.categories.map { c ->
            val noun = bestMatch(tokens, c.nouns)
            var best: TemplateSubcategory? = null
            var bestWeight = 0
            for (s in c.subcategories) {
                val w = bestMatch(tokens, s.keywords)
                if (w > bestWeight) { best = s; bestWeight = w }
            }
            Score(c, noun, best, bestWeight)
        }
        val byFormat = when (format) {
            "XLSX" -> "spreadsheets"
            "PPTX" -> "presentations"
            else -> null
        }
        val candidates = scores.filter { it.noun > 0 || (byFormat != null && it.category.id == byFormat) }
        if (candidates.isEmpty()) return null
        // A request needs a creation verb, an explicit file format, or to start with the document noun.
        val startsWithNoun = candidates.any { s -> s.category.nouns.any { normalized.startsWith(it) } }
        if (!verb && format == null && !startsWithNoun) return null

        val ranked = candidates.sortedWith(compareByDescending<Score> { if (byFormat == it.category.id) 100 else 0 }
            .thenByDescending { it.noun * 10 + it.subWeight })
        val top = ranked.first()
        val language = detectLanguage(text)
        val outFormat = when (top.category.kind) {
            OutputKind.SPREADSHEET -> "XLSX"
            OutputKind.PRESENTATION -> "PPTX"
            OutputKind.DOCUMENT -> if (format == "PDF") "PDF" else if (format == "DOCX") "DOCX" else defaultDocumentFormat
        }
        // Two kinds of document named with the same weight and no format to decide: ask.
        val tie = ranked.getOrNull(1)?.takeIf { second ->
            byFormat == null && second.noun == top.noun && second.noun * 10 + second.subWeight == top.noun * 10 + top.subWeight
        }
        if (tie != null) {
            val choices = listOfNotNull(top.sub?.id ?: top.category.generalSubcategory?.id, tie.sub?.id ?: tie.category.generalSubcategory?.id)
            return DocumentIntent(top.category.id, null, outFormat, language, 0.4, choices = choices)
        }
        val sub = top.sub ?: top.category.generalSubcategory
        if (sub == null) {
            // A thesis without its level (or another category without a catch-all): ask which one.
            return DocumentIntent(top.category.id, null, outFormat, language, 0.5, choices = top.category.subcategories.map { it.id })
        }
        val confidence = when {
            top.sub != null && (verb || format != null) -> 0.95
            top.sub != null -> 0.85
            verb || format != null -> 0.8 // the category's general subcategory
            else -> 0.7
        }
        val words = normalized.split(' ').size
        val missing = if (words < 14) sub.fields else emptyList()
        return DocumentIntent(top.category.id, sub.id, outFormat, language, confidence, missing,
            choices = if (confidence < AUTO_THRESHOLD) top.category.subcategories.map { it.id } else emptyList())
    }

    /**
     * The subcategory picked in an answer to a clarification question: its number ("2"), or a
     * keyword of one of the [choices].
     */
    fun resolveChoice(answer: String, choices: List<String>): String? {
        val n = TemplateCatalog.normalize(answer)
        n.toIntOrNull()?.let { return choices.getOrNull(it - 1) }
        val tokens = n.split(' ').map(::variants)
        return choices.maxByOrNull { id -> TemplateCatalog.subcategory(id)?.let { bestMatch(tokens, it.keywords) } ?: 0 }
            ?.takeIf { id -> (TemplateCatalog.subcategory(id)?.let { bestMatch(tokens, it.keywords) } ?: 0) > 0 }
    }
}
