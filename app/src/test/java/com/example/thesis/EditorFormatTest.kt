package com.example.thesis

import com.example.thesis.core.EditorOps
import com.example.thesis.core.EditorOps.Format
import com.example.thesis.core.EditorText
import com.example.thesis.core.FormattedRun
import com.example.thesis.core.InlineFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bold / italic / underline of the section editor, as pressed on a phone: one after another on
 * the same word (run #36/#37 on the emulator gave "__***Groundwater__***"), with a caret only,
 * on an empty field, across lines, with heading/alignment/list prefixes, in Arabic. Every result
 * must read back (InlineFormat, used by the Word and PDF exports) as exactly the intended runs.
 */
class EditorFormatTest {

    private fun t(text: String, a: Int, b: Int = a) = EditorText.of(text, a, b)
    private fun sel(text: String, word: String) = text.indexOf(word).let { t(text, it, it + word.length) }
    private fun runs(text: String) = InlineFormat.parse(text)

    @Test
    fun boldItalicUnderline_onTheSameWord_combine_andReadBack() {
        var e = sel("Groundwater quality matters.", "Groundwater")
        e = EditorOps.toggleFormat(e, Format.BOLD)
        assertEquals("**Groundwater** quality matters.", e.text)
        assertEquals("Groundwater", e.selected)
        e = EditorOps.toggleFormat(e, Format.ITALIC)
        assertEquals("***Groundwater*** quality matters.", e.text)
        e = EditorOps.toggleFormat(e, Format.UNDERLINE)
        assertEquals("***__Groundwater__*** quality matters.", e.text)
        assertEquals(listOf(FormattedRun("Groundwater", true, true, true), FormattedRun(" quality matters.")), runs(e.text))
        // Off again one by one, in another order.
        e = EditorOps.toggleFormat(e, Format.BOLD)
        assertEquals(listOf(FormattedRun("Groundwater", false, true, true), FormattedRun(" quality matters.")), runs(e.text))
        e = EditorOps.toggleFormat(e, Format.UNDERLINE)
        assertEquals("*Groundwater* quality matters.", e.text)
        e = EditorOps.toggleFormat(e, Format.ITALIC)
        assertEquals("Groundwater quality matters.", e.text)
    }

    @Test
    fun selectionInsideTheMarkers_orIncludingThem_isTheSameWord() {
        val text = "A **bold** word"
        // Long press selects the letters only, or the markers too: both turn bold off.
        assertEquals("A bold word", EditorOps.toggleFormat(sel(text, "bold"), Format.BOLD).text)
        assertEquals("A bold word", EditorOps.toggleFormat(sel(text, "**bold**"), Format.BOLD).text)
        // Part of a word formats the whole word.
        assertEquals("A **bold** word", EditorOps.toggleFormat(t("A bold word", 3, 4), Format.BOLD).text)
    }

    @Test
    fun neighbouringWords_withDifferentFormats_readBack() {
        var e = sel("one two three", "two")
        e = EditorOps.toggleFormat(e, Format.ITALIC)
        e = EditorOps.toggleFormat(sel(e.text, "one"), Format.BOLD)
        e = EditorOps.toggleFormat(sel(e.text, "three"), Format.UNDERLINE)
        assertEquals("**one** *two* __three__", e.text)
        // Bold over all three: one run, the other formats kept.
        e = EditorOps.toggleFormat(t(e.text, 0, e.text.length), Format.BOLD)
        // Spaces between differently formatted words are written outside the markers.
        assertEquals("**one** ***two*** **__three__**", e.text)
        assertEquals(listOf(FormattedRun("one", true), FormattedRun(" "), FormattedRun("two", true, true), FormattedRun(" "), FormattedRun("three", true, false, true)),
            runs(e.text))
        assertEquals("one two three", InlineFormat.plain(e.text))
        assertTrue(runs(e.text).filter { it.text.isNotBlank() }.all { it.bold })
    }

    @Test
    fun caretOnly_formatsTheWordUnderIt_andNothingOutsideWords() {
        assertEquals("The **aquifer** is deep.", EditorOps.toggleFormat(t("The aquifer is deep.", 6), Format.BOLD).text)
        // Caret right after a word.
        assertEquals("The *aquifer* is deep.", EditorOps.toggleFormat(t("The aquifer is deep.", 11), Format.ITALIC).text)
        // Empty field, or a caret between spaces: unchanged (no stray markers).
        assertEquals("", EditorOps.toggleFormat(t("", 0), Format.BOLD).text)
        assertEquals("a  b", EditorOps.toggleFormat(t("a  b", 2), Format.UNDERLINE).text)
        // The empty-field sequence from the emulator test leaves no markers behind.
        var e = t("", 0)
        for (f in Format.values()) e = EditorOps.toggleFormat(e, f)
        assertEquals("", e.text)
    }

    @Test
    fun linePrefixes_andPlaceholders_areNeverFormatted() {
        val text = "# {center} Title words\n- item one\n1. item two\n[Page break]\n{end} 2. last"
        val e = EditorOps.toggleFormat(t(text, 0, text.length), Format.BOLD)
        assertEquals("# {center} **Title words**\n- **item one**\n1. **item two**\n[Page break]\n{end} 2. **last**", e.text)
        assertEquals(text, EditorOps.toggleFormat(e.copy(start = 0, end = e.text.length), Format.BOLD).text)
        // Alignment and lists still apply afterwards.
        assertEquals("{justify} **Title**", EditorOps.setAlign(EditorOps.toggleFormat(t("{center} Title", 10), Format.BOLD), "justify").text)
    }

    @Test
    fun arabicAndFrench_textIsKeptLetterForLetter() {
        val text = "المياه الجوفية مصدر أساسي. Une phrase française é."
        var e = sel(text, "الجوفية")
        e = EditorOps.toggleFormat(e, Format.BOLD)
        e = EditorOps.toggleFormat(e, Format.UNDERLINE)
        assertEquals("المياه **__الجوفية__** مصدر أساسي. Une phrase française é.", e.text)
        e = EditorOps.toggleFormat(sel(e.text, "française"), Format.ITALIC)
        assertEquals(text, InlineFormat.plain(e.text))
        assertEquals(listOf("الجوفية", "française"), runs(e.text).filter { it.bold || it.italic }.map { it.text })
    }

    @Test
    fun textTheMarkupCannotHold_isLeftUnchanged_withAnError() {
        // A literal "*" glued to the word: the result would not read back, so nothing changes.
        val text = "x*y z"
        val r = runCatching { EditorOps.toggleFormat(t(text, 0, 1), Format.ITALIC) }
        assertTrue(r.isFailure || InlineFormat.plain(r.getOrThrow().text) == text)
    }

    @Test
    fun legacyMarkup_parsesAsBefore() {
        assertEquals(listOf(FormattedRun("A "), FormattedRun("b", bold = true), FormattedRun(" "), FormattedRun("u", underline = true), FormattedRun(" "), FormattedRun("both", true, false, true)),
            runs("A **b** __u__ **__both__**"))
        assertEquals("5 * 3 = 15", InlineFormat.plain("5 * 3 = 15"))
        // One-letter bodies (the greedy pattern ran on to the next marker).
        assertEquals(listOf(FormattedRun("a", bold = true), FormattedRun(" and "), FormattedRun("b", bold = true)), runs("**a** and **b**"))
        assertEquals(listOf(FormattedRun("و", italic = true), FormattedRun(" نص")), runs("*و* نص"))
        // ***x*** was read as bold "*x" + "*"; now bold and italic.
        assertEquals(listOf(FormattedRun("x", true, true)), runs("***x***"))
    }

    @Test
    fun multiLineSelection_formatsEachLine_andKeepsLineBreaks() {
        val text = "first line\nsecond line\nthird"
        val e = EditorOps.toggleFormat(t(text, 6, 17), Format.UNDERLINE)
        // The selection ends inside "second": that word, not the rest of its line.
        assertEquals("first __line__\n__second__ line\nthird", e.text)
        assertEquals(text, InlineFormat.plain(e.text))
    }
}
