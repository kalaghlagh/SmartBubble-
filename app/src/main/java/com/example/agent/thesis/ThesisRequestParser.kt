package com.example.agent.thesis

import com.example.thesis.model.SectionKind

/** What a document request asks to do with the active thesis (or only with a source document). */
enum class ThesisEditKind {
    /** Text of source page(s) into a thesis section. */
    INSERT_PAGE_TEXT,

    /** A numbered table of a source document into a thesis section. */
    INSERT_TABLE,

    /** Text given in the message, added to a thesis section. */
    APPEND_TEXT,

    /** The n-th paragraph of a thesis section replaced by text given in the message. */
    REPLACE_PARAGRAPH,

    /** Only read pages or a table of a source document (nothing is changed). */
    EXTRACT_ONLY
}

/** A section named in a request: by kind (Introduction, Remerciements...), chapter number or title. */
data class SectionRef(val kind: SectionKind? = null, val chapterNumber: Int? = null, val title: String? = null) {
    val isEmpty: Boolean get() = kind == null && chapterNumber == null && title.isNullOrBlank()
}

/**
 * A request understood locally (no AI request). Only what the user wrote is kept: nothing is
 * guessed. Missing parts are listed in [missing] (one focused question is asked for the first).
 */
data class ThesisRequest(
    val kind: ThesisEditKind,
    /** Source pages, 1-based, in the order written (empty: none given). */
    val pages: List<Int> = emptyList(),
    /** Normalized table number ("3", "2.1"), null when none. */
    val tableNumber: String? = null,
    val section: SectionRef? = null,
    /** 1-based paragraph number in the section; -1 = the last one. */
    val paragraph: Int? = null,
    /** Text given in the message (quoted, or after a colon). */
    val text: String? = null,
    /** A thesis page named as the destination: only a hint (Word pages move). */
    val pageHint: Int? = null,
    /** Insert at the start of the section instead of the end. */
    val atStart: Boolean = false,
    /** The thesis title the user named ("my thesis named X"), when not the active one. */
    val thesisTitle: String? = null,
    val missing: List<String> = emptyList()
)

/**
 * Local parser for document requests in Arabic, French and English. It recognizes a request about
 * the user's thesis or about pages and tables of a source document; any other request returns
 * null and goes through the existing Agent and chat pipelines unchanged.
 */
object ThesisRequestParser {
    private val thesisWord = Regex("""(?i)\b(thesis|dissertation|m[ée]moire|th[èe]se)\b|مذكرت|مذكرة|المذكرة|أطروحت|أطروحة|الأطروحة|رسالتي""")
    private val insertVerb = Regex("""(?i)\b(insert|add|append|copy|put|paste|place|move|ins[èe]re[rz]?|ajoute[rz]?|copie[rz]?|colle[rz]?|mets|mettre|place[rz]?)\b|أضف|اضف|أدرج|ادرج|انسخ|ضع|الصق|أدخل|ادخل""")
    private val replaceVerb = Regex("""(?i)\b(replace|substitute|remplace[rz]?)\b|استبدل|عوّض|عوض""")
    private val extractVerb = Regex("""(?i)\b(extract|read|show|get|give me|extrai[st]|extraire|lis|lire|montre[rz]?|affiche[rz]?)\b|استخرج|اقرأ|اعرض|أعطني""")
    private val atStartWord = Regex("""(?i)\b(at the (beginning|start)|au d[ée]but)\b|في بداية|في أول""")

    private val tableRef = Regex("""(?i)\b(?:table|tableau)\s*(?:n[°o]\.?\s*|num[ée]ro\s*|number\s*|#\s*)?([0-9٠-٩]{1,3}(?:\.[0-9٠-٩]{1,2})?)\b|(?:الجدول|جدول)\s*(?:رقم\s*)?([0-9٠-٩]{1,3}(?:\.[0-9٠-٩]{1,2})?)""")
    private val pageRef = Regex("""(?i)\b(?:pages?|p\.)\s*([0-9٠-٩]{1,4})(?:\s*(?:-|–|to|à|a|au|through)\s*([0-9٠-٩]{1,4}))?\b|(?:الصفحات|الصفحة|صفحة|صفحات)\s*(?:رقم\s*)?([0-9٠-٩]{1,4})(?:\s*(?:-|–|إلى|الى)\s*([0-9٠-٩]{1,4}))?""")
    private val chapterRef = Regex("""(?i)\b(?:chapter|chapitre)\s+([0-9]{1,2}|[ivx]{1,5})\b|الفصل\s+(?:رقم\s*)?([0-9٠-٩]{1,2}|الأول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن)""")
    /** "the Methodology section" (name before the keyword). */
    private val titledBefore = Regex("""(?i)((?:[\p{L}][\p{L}'\-]*\s+){0,3}[\p{L}][\p{L}'\-]*)["”»]?\s+(?:section|chapter|part)\b""")
    /** "la section Méthodologie", "قسم المنهجية" (name after the keyword). */
    private val titledAfter = Regex("""(?i)\b(?:section|partie|chapitre)\s+["“«]?((?:[\p{L}][\p{L}'\-]*\s+){0,3}[\p{L}][\p{L}'\-]*)|(?<!\p{L})(?:قسم|فصل|جزء)\s+["“«]?((?:[\p{L}][\p{L}\-]*\s+){0,2}[\p{L}][\p{L}\-]*)""")
    private val stopWords = setOf(
        "the", "this", "that", "a", "an", "my", "of", "in", "into", "to", "at", "end", "on", "same", "next", "previous", "and", "with", "from",
        "la", "le", "les", "l", "de", "du", "des", "dans", "à", "a", "au", "cette", "ce", "même", "fin", "et", "avec", "ma", "mon",
        "من", "في", "إلى", "الى", "نهاية", "و"
    )
    private val quoted = Regex("""["“«]([^"“”«»]{3,})["”»]""")
    private val afterColon = Regex(""":\s*\n?([\s\S]{3,})$""")
    private val namedThesis = Regex("""(?i)\b(?:thesis|m[ée]moire|th[èe]se)\s+(?:named|called|titled|intitul[ée]e?|nomm[ée]e?|appel[ée]e?)\s+["“«]?([^"”»,.;:\n]{3,80}?)["”»]?(?=\s+(?:in|into|at|dans|au|à|en|and|et)\b|[,.;:\n]|$)|(?:مذكرتي|المذكرة|أطروحتي|الأطروحة)\s+(?:المسماة|بعنوان|التي عنوانها)\s+["“«]?([^"”»،.\n]{3,80}?)["”»]?(?=\s+(?:في|ضمن)\s|[،.\n]|$)""")

    private val kindWords: List<Pair<SectionKind, Regex>> = listOf(
        SectionKind.ACKNOWLEDGEMENTS to Regex("""(?i)\b(remerciements?|acknowledge?ments?)\b|شكر وتقدير|الشكر والتقدير|الشكر"""),
        SectionKind.DEDICATION to Regex("""(?i)\b(d[ée]dicaces?|dedications?)\b|الإهداء|إهداء"""),
        SectionKind.ABSTRACT to Regex("""(?i)\b(abstract|r[ée]sum[ée])\b|الملخص|ملخص"""),
        SectionKind.ABBREVIATIONS to Regex("""(?i)\b(abbreviations|abr[ée]viations|acronyms|sigles)\b|المختصرات|قائمة المختصرات"""),
        SectionKind.INTRODUCTION to Regex("""(?i)\b(general introduction|introduction g[ée]n[ée]rale|introduction)\b|المقدمة العامة|المقدمة|مقدمة"""),
        SectionKind.CONCLUSION to Regex("""(?i)\b(general conclusion|conclusion g[ée]n[ée]rale|conclusion)\b|الخاتمة العامة|الخاتمة|خاتمة"""),
        SectionKind.REFERENCES to Regex("""(?i)\b(references|bibliography|r[ée]f[ée]rences|bibliographie)\b|المراجع|قائمة المراجع"""),
        SectionKind.APPENDIX to Regex("""(?i)\b(appendix|appendices|annexes?)\b|الملاحق|ملحق""")
    )

    private val arabicOrdinals = mapOf("الأول" to 1, "الثاني" to 2, "الثالث" to 3, "الرابع" to 4, "الخامس" to 5, "السادس" to 6, "السابع" to 7, "الثامن" to 8)
    private val paragraphOrdinal = Regex("""(?i)\b(first|second|third|fourth|fifth|sixth|last|[0-9]{1,2}(?:st|nd|rd|th))\s+paragraph\b|\bparagraph\s+([0-9]{1,2})\b|\b(premier|premi[èe]re|deuxi[èe]me|second|seconde|troisi[èe]me|quatri[èe]me|cinqui[èe]me|sixi[èe]me|dernier|derni[èe]re)\s+paragraphe\b|\bparagraphe\s+([0-9]{1,2})\b|الفقرة\s+(الأولى|الثانية|الثالثة|الرابعة|الخامسة|السادسة|الأخيرة|رقم\s*[0-9٠-٩]{1,2}|[0-9٠-٩]{1,2})""")
    private val ordinalWords = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "last" to -1,
        "premier" to 1, "premiere" to 1, "première" to 1, "deuxieme" to 2, "deuxième" to 2, "seconde" to 2,
        "troisieme" to 3, "troisième" to 3, "quatrieme" to 4, "quatrième" to 4, "cinquieme" to 5, "cinquième" to 5,
        "sixieme" to 6, "sixième" to 6, "dernier" to -1, "derniere" to -1, "dernière" to -1,
        "الأولى" to 1, "الثانية" to 2, "الثالثة" to 3, "الرابعة" to 4, "الخامسة" to 5, "السادسة" to 6, "الأخيرة" to -1
    )

    /** Arabic-Indic digits to ASCII. */
    fun digits(s: String): String = buildString { for (c in s) append(if (c in '٠'..'٩') '0' + (c - '٠') else c) }

    /** "03" -> "3", "2.01" -> "2.1", "٣" -> "3". */
    fun normalizeNumber(s: String): String = digits(s).split('.').joinToString(".") { it.trimStart('0').ifEmpty { "0" } }

    private fun roman(s: String): Int? {
        val v = mapOf('i' to 1, 'v' to 5, 'x' to 10)
        var total = 0; var prev = 0
        for (c in s.lowercase().reversed()) { val n = v[c] ?: return null; if (n < prev) total -= n else { total += n; prev = n } }
        return total.takeIf { it in 1..40 }
    }

    /**
     * Parses [text]. [maskedSpans] are file names already recognized (source document, thesis
     * title): words inside them are not read as section names or page numbers.
     */
    fun parse(text: String, maskedSpans: List<String> = emptyList()): ThesisRequest? {
        val thesisTitle = namedThesis.find(text)?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).trim().ifBlank { null } }
        val payload = quoted.find(text)?.groupValues?.get(1)?.trim() ?: afterColon.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 3 }
        // What is analyzed: the instruction without the payload text and without file names.
        var instruction = text
        payload?.let { instruction = instruction.replace(it, " ") }
        (maskedSpans + listOfNotNull(thesisTitle)).filter { it.length >= 3 }.sortedByDescending { it.length }.forEach { span ->
            instruction = Regex(Regex.escape(span), RegexOption.IGNORE_CASE).replace(instruction) { " ".repeat(it.value.length) }
        }
        val thesisAt = thesisWord.find(instruction)?.range?.first
        val table = tableRef.find(instruction)?.let { m -> normalizeNumber(m.groupValues[1].ifBlank { m.groupValues[2] }) }

        // Pages written before the word "thesis" are source pages; after it, a thesis page (a hint).
        val sourcePages = ArrayList<Int>()
        var pageHint: Int? = null
        for (m in pageRef.findAll(instruction)) {
            val a = digits(m.groupValues[1].ifBlank { m.groupValues[3] }).toIntOrNull() ?: continue
            val b = digits(m.groupValues[2].ifBlank { m.groupValues[4] }).toIntOrNull()
            if (thesisAt != null && m.range.first > thesisAt) { pageHint = pageHint ?: a; continue }
            if (b != null && b >= a && b - a <= 50) (a..b).forEach { sourcePages += it } else sourcePages += a
        }

        val section = sectionRef(instruction)
        val paragraph = paragraphOrdinal.find(instruction)?.let { m ->
            val g = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty().lowercase()
            ordinalWords[g] ?: digits(g.removePrefix("رقم").trim().filter { it.isDigit() || it in '٠'..'٩' }).toIntOrNull()
        }
        val atStart = atStartWord.containsMatchIn(instruction)
        val wantsInsert = insertVerb.containsMatchIn(instruction)
        val wantsReplace = replaceVerb.containsMatchIn(instruction)
        val wantsExtract = extractVerb.containsMatchIn(instruction)

        val kind = when {
            thesisAt != null && wantsReplace && paragraph != null -> ThesisEditKind.REPLACE_PARAGRAPH
            thesisAt != null && table != null && (wantsInsert || wantsExtract) -> ThesisEditKind.INSERT_TABLE
            thesisAt != null && sourcePages.isNotEmpty() && (wantsInsert || wantsExtract) -> ThesisEditKind.INSERT_PAGE_TEXT
            thesisAt != null && wantsInsert && (section != null || payload != null) && table == null && sourcePages.isEmpty() -> ThesisEditKind.APPEND_TEXT
            thesisAt == null && wantsExtract && (table != null || sourcePages.isNotEmpty()) -> ThesisEditKind.EXTRACT_ONLY
            else -> return null
        }
        val missing = buildList {
            if (kind != ThesisEditKind.EXTRACT_ONLY && section == null) add("section")
            if ((kind == ThesisEditKind.APPEND_TEXT || kind == ThesisEditKind.REPLACE_PARAGRAPH) && payload.isNullOrBlank()) add("text")
        }
        return ThesisRequest(
            kind = kind, pages = sourcePages.distinct(), tableNumber = table, section = section, paragraph = paragraph,
            text = payload?.takeIf { kind == ThesisEditKind.APPEND_TEXT || kind == ThesisEditKind.REPLACE_PARAGRAPH },
            pageHint = pageHint, atStart = atStart, thesisTitle = thesisTitle, missing = missing
        )
    }

    /** The section named last in [instruction] (the destination is normally named last). */
    fun sectionRef(instruction: String): SectionRef? {
        val found = ArrayList<Pair<Int, SectionRef>>()
        chapterRef.findAll(instruction).forEach { m ->
            val raw = m.groupValues[1].ifBlank { m.groupValues[2] }
            val n = digits(raw).toIntOrNull() ?: roman(raw) ?: arabicOrdinals[raw]
            if (n != null) found += m.range.first to SectionRef(chapterNumber = n)
        }
        kindWords.forEach { (kind, r) -> r.findAll(instruction).forEach { found += it.range.first to SectionRef(kind = kind) } }
        for ((regex, before) in listOf(titledBefore to true, titledAfter to false)) regex.findAll(instruction).forEach { m ->
            val raw = m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val tokens = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
            fun stop(w: String) = w.lowercase().trim('\'', '’') in stopWords
            // "of the Methodology section" -> "Methodology"; "la section Méthodologie de la thèse" -> "Méthodologie".
            val tail = if (before) tokens.drop(tokens.indexOfLast(::stop) + 1) else tokens.takeWhile { !stop(it) }
            val t = tail.joinToString(" ")
            val first = tail.firstOrNull()?.lowercase()
            val instructionWord = first != null && (insertVerb.matches(first) || replaceVerb.matches(first) || extractVerb.matches(first) || first == "paragraph" || first == "paragraphe")
            // "الفصل الثاني" is chapter 2, never a section titled "الثاني".
            if (t.length >= 3 && !instructionWord && t !in arabicOrdinals && kindWords.none { it.second.containsMatchIn(t) } && !chapterRef.containsMatchIn(t) && !thesisWord.containsMatchIn(t))
                found += (if (before) m.range.last - t.length else m.range.first) to SectionRef(title = t)
        }
        return found.maxByOrNull { it.first }?.second
    }
}
