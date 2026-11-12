package com.example.offline.analysis

import com.example.offline.SourceSpan
import com.example.offline.StructureOrigin

/**
 * A line that may be a heading. The heading text is always the source line itself (minus a
 * Markdown "#" marker); nothing is reworded or added.
 */
data class HeadingCandidate(
    val text: String,
    val level: Int?,
    val confidence: Float,
    val origin: StructureOrigin,
    val numbered: Boolean,
    /** Source spans of markup that is not content ("#" characters, setext underline). */
    val markup: List<SourceSpan> = emptyList()
)

/**
 * Detects explicit headings (Markdown "#", setext underlines) and likely headings (short
 * standalone lines, numbered section titles, ALL CAPS lines, "Title:" lines, Title Case lines).
 * A candidate below [ACCEPT] stays a normal paragraph; a level is assigned only at [LEVEL_CONFIDENCE]
 * or above.
 */
object HeadingDetectionEngine {

    const val ACCEPT = 0.6f
    const val LEVEL_CONFIDENCE = 0.75f

    private const val MAX_LENGTH = 90
    private const val MAX_WORDS = 12

    private val MARKDOWN = Regex("""^[ \t]{0,3}(#{1,6})[ \t]+(.*?)(?:[ \t]+(#+))?[ \t]*$""")
    private val SETEXT = Regex("""^(={3,}|-{3,})$""")
    private val NUMBERED_SECTION = Regex("""^(\d{1,2}(?:\.\d{1,2}){0,3})[.)]?[ \t]+\S""")
    private val ROMAN_SECTION = Regex("""^[IVXLC]{1,6}[.)][ \t]+\S""")
    private val LINK_OR_EMAIL = Regex("""https?://|www\.|\S+@\S+\.\S+""")

    /** "# Title" .. "###### Title" (optional closing "#"s are markup too). */
    fun markdown(line: SourceLine): HeadingCandidate? {
        val m = MARKDOWN.find(line.text) ?: return null
        val text = m.groupValues[2].trim()
        if (text.isEmpty()) return null
        val hashes = m.groups[1]!!.range
        val markup = mutableListOf(SourceSpan(line.start + hashes.first, line.start + hashes.last + 1))
        m.groups[3]?.range?.let { markup.add(SourceSpan(line.start + it.first, line.start + it.last + 1)) }
        return HeadingCandidate(text, (hashes.last - hashes.first + 1).coerceAtMost(3), 0.98f, StructureOrigin.EXPLICIT, false, markup)
    }

    fun isSetextUnderline(line: SourceLine): Boolean = line.indent <= 3 && SETEXT.matches(line.trimmed)

    /** A short text line directly followed by "===" (level 1) or "---" (level 2). */
    fun setext(line: SourceLine, underline: SourceLine): HeadingCandidate? {
        if (!isSetextUnderline(underline) || line.isBlank || line.trimmed.length > MAX_LENGTH) return null
        if (TextTraits.words(line.trimmed).size > MAX_WORDS) return null
        val level = if (underline.trimmed.startsWith("=")) 1 else 2
        return HeadingCandidate(
            line.trimmed, level, 0.95f, StructureOrigin.EXPLICIT, false,
            listOf(SourceSpan(underline.contentStart, underline.contentEnd))
        )
    }

    /**
     * Scores [line] as a heading.
     * @param standalone the line is alone between blank lines (or before another structure)
     * @param nextLine the following line when the candidate leads a multi-line block
     * @param followedByContent more content follows (a last line is never a heading)
     * @param isFirstBlock nothing precedes the line
     */
    fun evaluate(
        line: SourceLine,
        standalone: Boolean,
        nextLine: SourceLine?,
        followedByContent: Boolean,
        isFirstBlock: Boolean
    ): HeadingCandidate? {
        val t = line.trimmed
        if (!followedByContent || t.length > MAX_LENGTH || t.length < 2) return null
        val words = TextTraits.words(t)
        if (words.size > MAX_WORDS) return null
        if (t.contains('|') || line.text.contains('\t') || LINK_OR_EMAIL.containsMatchIn(t)) return null
        val colon = t.endsWith(':') || t.endsWith('：')
        if (!colon && TextTraits.endsWithTerminal(t)) return null
        if (TextTraits.startsLowercaseLatin(t)) return null
        if (t.count { it.isLetter() } < 2) return null
        // Leading a block: the next line must not continue a wrapped sentence.
        if (!standalone) {
            val next = nextLine ?: return null
            if (TextTraits.startsLowercaseLatin(next.trimmed)) return null
            if (next.trimmed.length < t.length) return null
        }
        val arabic = TextTraits.hasArabic(t)
        val latin = t.filter { TextTraits.isLatinLetter(it) }
        val allCaps = latin.length >= 3 && latin.none { it.isLowerCase() } && !arabic
        val numbered = NUMBERED_SECTION.find(t)
        val roman = ROMAN_SECTION.containsMatchIn(t)

        var level: Int? = null
        var score = when {
            numbered != null -> {
                level = numbered.groupValues[1].count { it == '.' } + 1
                0.72f
            }
            roman -> { level = 1; 0.72f }
            allCaps -> { level = 1; 0.8f }
            colon && words.size <= 8 -> 0.68f
            arabic && words.size <= 7 -> if (isFirstBlock && standalone) 0.76f.also { level = 1 } else 0.62f
            isTitleCase(words) && words.size <= 10 -> if (isFirstBlock && standalone) 0.78f.also { level = 1 } else 0.66f
            words.size <= 6 && standalone -> 0.6f
            else -> 0.4f
        }
        if (!standalone) score -= 0.05f
        if (words.size <= 1 && !allCaps && !colon && numbered == null) score -= 0.05f
        if (score < LEVEL_CONFIDENCE) level = null
        return HeadingCandidate(t, level?.coerceIn(1, 3), score, StructureOrigin.INFERRED, numbered != null || roman)
    }

    /** Most words of four letters or more start with a capital letter (English/French titles). */
    private fun isTitleCase(words: List<String>): Boolean {
        val long = words.filter { w -> w.count { TextTraits.isLatinLetter(it) } >= 4 }
        if (long.isEmpty()) return false
        val capitalized = long.count { w -> w.first { it.isLetter() }.isUpperCase() }
        return capitalized * 10 >= long.size * 7
    }
}
