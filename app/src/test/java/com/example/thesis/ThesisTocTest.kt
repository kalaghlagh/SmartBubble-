package com.example.thesis

import com.example.thesis.core.NewElement
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOperations
import com.example.thesis.core.ThesisStructure
import com.example.thesis.core.ThesisToc
import com.example.thesis.export.ThesisDocxWriter
import com.example.thesis.export.ThesisTemplate
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Hierarchical table of contents: chapters, sub-sections and the headings written in the text are
 * numbered (1, 1.1, 1.2.1), listed to the chosen depth with stable keys, renumbered after edits,
 * and written to DOCX as a real TOC field whose cached entries link to bookmarked headings.
 */
class ThesisTocTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun h(id: String, text: String, level: Int) = NewElement(id, ElementType.HEADING, text, ElementData(headingLevel = level))
    private fun p(id: String, text: String) = NewElement(id, ElementType.PARAGRAPH, text)

    /** Three chapters; chapter 1 has headings down to level 3, an image, a table and a page break; chapter 2 has a sub-section. */
    private fun thesis(language: String = "en", meta: (ThesisMeta) -> ThesisMeta = { it }): ThesisSnapshot {
        var s = ThesisSnapshot(
            meta(ThesisMeta("Groundwater", AcademicLevel.MASTER, language, studentName = "A")),
            ThesisStructure.defaultSections(language, AcademicLevel.MASTER), emptyList()
        )
        val chapters = s.sections.filter { it.kind == SectionKind.CHAPTER }
        assertEquals(3, chapters.size)
        val (c1, c2, c3) = chapters.map { it.id }
        s = ThesisOperations.apply(s, listOf(
            ThesisOp.UpdateSection(c1, title = "Study area"),
            ThesisOp.UpdateSection(c2, title = "Methods"),
            ThesisOp.UpdateSection(c3, title = "Results"),
            ThesisOp.AddElements(c1, null, listOf(
                h("h11", "Location", 1), p("p1", "Text."),
                h("h111", "Climate", 2), h("h1111", "Rainfall", 3),
                NewElement("img", ElementType.IMAGE, "", ElementData(caption = "Map")),
                h("h12", "Geology", 1),
                NewElement("tab", ElementType.TABLE, "", ElementData(caption = "Ions", tableHeader = listOf("Ion"), tableRows = listOf(listOf("Ca")))),
                NewElement("pb", ElementType.PARAGRAPH, "After a page break.", ElementData(pageBreakBefore = true))
            )),
            ThesisOp.AddElements(c2, null, listOf(h("h21", "Sampling", 1), h("h22", "2.2 Analysis", 1))),
            ThesisOp.AddSection(ThesisSection("sub", c2, SectionKind.SECTION, "Statistics", 0), null)
        ))
        return s
    }

    @Test
    fun headings_areNumberedHierarchically_andSubSectionsContinueTheCount() {
        val s = thesis()
        val n = ThesisStructure.numbering(s)
        assertEquals("1.1", n.headings["h11"])
        assertEquals("1.1.1", n.headings["h111"])
        assertEquals("1.1.1.1", n.headings["h1111"])
        assertEquals("1.2", n.headings["h12"])
        assertEquals("2.1", n.headings["h21"])
        // Typed with its own number: kept as written, not numbered twice.
        assertEquals(null, n.headings["h22"])
        // The sub-section of chapter 2 comes after its inline heading 2.1.
        assertEquals("2.2", n.sectionNumbers["sub"])
        val c1 = s.sections.first { it.kind == SectionKind.CHAPTER }
        assertEquals("1", n.sectionNumbers[c1.id])
        // Figures and tables keep their own numbering.
        assertEquals(1, n.figures["img"])
        assertEquals(1, n.tables["tab"])
    }

    @Test
    fun numbering_followsEdits() {
        var s = thesis()
        val c1 = s.sections.first { it.kind == SectionKind.CHAPTER }.id
        // A new level-1 heading after the first paragraph of chapter 1: the headings after it move down.
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddElements(c1, "p1", listOf(h("h10", "Overview", 1)))))
        var n = ThesisStructure.numbering(s)
        assertEquals("1.1", n.headings["h11"])
        assertEquals("1.2", n.headings["h10"])
        assertEquals("1.2.1", n.headings["h111"])
        assertEquals("1.2.1.1", n.headings["h1111"])
        assertEquals("1.3", n.headings["h12"])
        // Disabling chapter 1 renumbers chapter 2 as 1.
        s = ThesisOperations.apply(s, listOf(ThesisOp.UpdateSection(c1, enabled = false)))
        n = ThesisStructure.numbering(s)
        assertEquals("1.1", n.headings["h21"])
        assertEquals("1.2", n.sectionNumbers["sub"])
        // Deleting a heading renumbers the next one.
        s = ThesisOperations.apply(s, listOf(ThesisOp.UpdateSection(c1, enabled = true), ThesisOp.DeleteElement("h10")))
        assertEquals("1.1.1", ThesisStructure.numbering(s).headings["h111"])
        assertEquals("1.2", ThesisStructure.numbering(s).headings["h12"])
    }

    @Test
    fun entries_followReadingOrder_levelsAndDepth() {
        val s = thesis()
        val n = ThesisStructure.numbering(s)
        val all = ThesisToc.entries(s, n, 4)
        val texts = all.map { it.text }
        val i = { t: String -> texts.indexOf(t).also { assertTrue("$t missing in $texts", it >= 0) } }
        assertTrue(i("General Introduction") < i("Chapter 1: Study area"))
        assertTrue(i("Chapter 1: Study area") < i("1.1 Location"))
        assertTrue(i("1.1 Location") < i("1.1.1 Climate"))
        assertTrue(i("1.1.1 Climate") < i("1.1.1.1 Rainfall"))
        assertTrue(i("1.1.1.1 Rainfall") < i("1.2 Geology"))
        assertTrue(i("1.2 Geology") < i("Chapter 2: Methods"))
        assertTrue(i("2.1 Sampling") < i("2.2 Analysis"))
        assertTrue(i("2.2 Analysis") < i("2.2 Statistics"))
        assertTrue(i("Chapter 3: Results") < i("General Conclusion"))
        // Generated pages (cover, the TOC itself, lists) are not listed.
        assertFalse(texts.any { it == "Table of Contents" || it == "Cover Page" })
        assertEquals(1, all.first { it.text == "Chapter 1: Study area" }.level)
        assertEquals(2, all.first { it.text == "1.1 Location" }.level)
        assertEquals(3, all.first { it.text == "1.1.1 Climate" }.level)
        assertEquals(4, all.first { it.text == "1.1.1.1 Rainfall" }.level)
        assertEquals(2, all.first { it.text == "2.2 Statistics" }.level)
        // Keys are stable ids, never positions.
        assertEquals("e:h11", all.first { it.text == "1.1 Location" }.key)
        assertEquals(all.size, all.map { it.key }.toSet().size)
        // Depth chosen by the user (default 3).
        assertEquals(3, s.meta.tocDepth)
        assertFalse(ThesisToc.entries(s, n).any { it.level > 3 })
        assertTrue(ThesisToc.entries(s, n, 1).all { it.level == 1 })
        assertTrue(ThesisToc.entries(s, n, 2).any { it.text == "1.1 Location" })
        assertFalse(ThesisToc.entries(s, n, 2).any { it.text == "1.1.1 Climate" })
    }

    @Test
    fun pageLabels_romanFrontMatterAndStartNumber() {
        val plain = ThesisMeta("T")
        assertEquals("1", ThesisToc.pageLabel(1, 6, plain))
        assertEquals("7", ThesisToc.pageLabel(7, 6, plain))
        assertFalse(ThesisToc.customNumbering(plain))
        val roman = plain.copy(frontMatterRoman = true)
        assertEquals("i", ThesisToc.pageLabel(1, 6, roman))
        assertEquals("iv", ThesisToc.pageLabel(4, 6, roman))
        assertEquals("1", ThesisToc.pageLabel(6, 6, roman))
        assertEquals("3", ThesisToc.pageLabel(8, 6, roman))
        val start = plain.copy(mainPageStart = 5)
        assertEquals("5", ThesisToc.pageLabel(1, 6, start))
        assertEquals("xiv", ThesisToc.roman(14).lowercase())
    }

    private fun zip(f: java.io.File, name: String) = ZipFile(f).use { z -> z.getEntry(name)?.let { String(z.getInputStream(it).readBytes()) } }

    private fun docx(s: ThesisSnapshot): String {
        val out = tmp.newFile()
        ThesisDocxWriter().write(s, emptyList(), ThesisTemplate.Profile(ThesisDocxWriter.builtInProfile(), null), out)
        val doc = zip(out, "word/document.xml")!!
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(doc.byteInputStream())
        assertTrue(zip(out, "word/settings.xml")!!.contains("updateFields"))
        return doc
    }

    @Test
    fun docx_tocFieldListsNumberedHeadingsWithLinksToBookmarks() {
        val doc = docx(thesis(meta = { it.copy(tocDepth = 4) }))
        assertTrue(doc.contains("TOC \\o \"1-4\" \\h \\z \\u"))
        // Cached entries at their level, each a hyperlink to a bookmark on the heading.
        val entry = Regex("""<w:p><w:pPr><w:pStyle w:val="TOC(\d)"/>(?:<w:bidi/>)?</w:pPr>(?:.*?)<w:hyperlink w:anchor="([^"]+)"[^>]*>.*?<w:t xml:space="preserve">([^<]*)</w:t>""")
        val cached = entry.findAll(doc).map { Triple(it.groupValues[1].toInt(), it.groupValues[2], it.groupValues[3]) }.toList()
        val byText = cached.associateBy { it.third }
        assertEquals(1, byText.getValue("Chapter 1: Study area").first)
        assertEquals(2, byText.getValue("1.1 Location").first)
        assertEquals(3, byText.getValue("1.1.1 Climate").first)
        assertEquals(4, byText.getValue("1.1.1.1 Rainfall").first)
        for ((_, anchor, text) in cached) assertTrue("no bookmark for $text", doc.contains("w:name=\"$anchor\""))
        // The headings in the body carry the same numbers and heading styles.
        assertTrue(Regex("""<w:pStyle w:val="Heading2"/>.*?1\.1 Location""").containsMatchIn(doc))
        assertTrue(Regex("""<w:pStyle w:val="Heading4"/>.*?1\.1\.1\.1 Rainfall""").containsMatchIn(doc))
        // A heading the user numbered keeps its own number only once.
        assertTrue(doc.contains(">2.2 Analysis<"))
        assertFalse(doc.contains("2.2 2.2 Analysis"))
        // No roman front-matter section by default.
        assertFalse(doc.contains("lowerRoman"))
    }

    @Test
    fun docx_romanFrontMatter_restartsTheMainText() {
        val doc = docx(thesis(meta = { it.copy(frontMatterRoman = true, mainPageStart = 1) }))
        // One section break with roman numbering ends the preliminary pages, before the introduction.
        val front = doc.indexOf("w:fmt=\"lowerRoman\" w:start=\"1\"")
        assertTrue(front > 0)
        assertTrue(front < doc.lastIndexOf("General Introduction<"))
        assertTrue(front > doc.indexOf("General Introduction<")) // the TOC entry is in the front matter
        assertTrue(doc.indexOf("Table of Contents<") < front)
        assertTrue(doc.contains("<w:type w:val=\"nextPage\"/>"))
        // The main text restarts at 1, numbered on its first page.
        val last = doc.substring(doc.lastIndexOf("<w:sectPr>"))
        assertTrue(last.contains("w:fmt=\"decimal\" w:start=\"1\""))
        assertFalse(last.contains("<w:titlePg/>"))
        // Elements of sectPr in schema order: pgNumType after pgMar, before titlePg/bidi.
        val f = doc.substring(doc.indexOf("<w:sectPr>"), doc.indexOf("</w:sectPr>"))
        assertTrue(f.indexOf("<w:pgMar") < f.indexOf("<w:pgNumType"))
        assertTrue(f.indexOf("<w:pgNumType") < f.indexOf("<w:titlePg"))
    }

    @Test
    fun arabicThesis_tocIsRightToLeftWithArabicNumbers() {
        var s = thesis("ar")
        val c1 = s.sections.first { it.kind == SectionKind.CHAPTER }.id
        s = ThesisOperations.apply(s, listOf(ThesisOp.UpdateSection(c1, title = "الإطار العام"), ThesisOp.ReplaceText("h11", "الموقع")))
        val n = ThesisStructure.numbering(s)
        val e = ThesisToc.entries(s, n)
        assertTrue(e.any { it.text == "الفصل 1: الإطار العام" })
        assertTrue(e.any { it.text == "1.1 الموقع" })
        val doc = docx(s)
        assertTrue(doc.contains("<w:pStyle w:val=\"TOC2\"/><w:bidi/>"))
        assertTrue(doc.contains("فهرس المحتويات"))
    }
}
