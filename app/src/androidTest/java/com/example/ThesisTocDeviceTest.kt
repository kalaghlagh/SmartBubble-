package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.thesis.core.NewElement
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOperations
import com.example.thesis.core.ThesisStructure
import com.example.thesis.core.ThesisToc
import com.example.thesis.export.ThesisDocxWriter
import com.example.thesis.export.ThesisPdfRenderer
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSection
import com.example.thesis.model.ThesisSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The PDF table of contents on the device (real fonts and line breaking): every entry's page is
 * the page where its heading is actually drawn, also after an edit moves the headings, with roman
 * front matter, and in an Arabic (right-to-left) thesis. The TOC pages are saved as evidence.
 */
@RunWith(AndroidJUnit4::class)
class ThesisTocDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File by lazy { File(context.getExternalFilesDir(null), "toc-device").apply { mkdirs() } }

    private fun h(id: String, text: String, level: Int) = NewElement(id, ElementType.HEADING, text, ElementData(headingLevel = level))
    private fun p(id: String, text: String) = NewElement(id, ElementType.PARAGRAPH, text)
    private val long = "Groundwater samples were collected from wells across the basin and analysed for major ions and nitrates. ".repeat(6)

    private fun thesis(language: String = "en", paragraphs: Int = 3, meta: (ThesisMeta) -> ThesisMeta = { it }): ThesisSnapshot {
        var s = ThesisSnapshot(
            meta(ThesisMeta(if (language == "ar") "جودة المياه الجوفية" else "Groundwater quality", AcademicLevel.MASTER, language, studentName = "A. B.")),
            ThesisStructure.defaultSections(language, AcademicLevel.MASTER), emptyList()
        )
        val (c1, c2, c3) = s.sections.filter { it.kind == SectionKind.CHAPTER }.map { it.id }
        val ar = language == "ar"
        s = ThesisOperations.apply(s, listOf(
            ThesisOp.UpdateSection(c1, title = if (ar) "الإطار العام" else "Study area"),
            ThesisOp.UpdateSection(c2, title = if (ar) "المنهجية" else "Methods"),
            ThesisOp.UpdateSection(c3, title = if (ar) "النتائج" else "Results"),
            ThesisOp.AddElements(c1, null, listOf(h("h11", if (ar) "الموقع" else "Location", 1)) +
                (1..paragraphs).map { p("p1$it", long) } +
                listOf(h("h111", if (ar) "المناخ" else "Climate", 2), h("h1111", if (ar) "التساقطات" else "Rainfall", 3),
                    NewElement("img", ElementType.IMAGE, "", ElementData(caption = "Map")),
                    h("h12", if (ar) "الجيولوجيا" else "Geology", 1),
                    NewElement("tab", ElementType.TABLE, "", ElementData(caption = "Ions", tableHeader = listOf("Ion", "mg/L"), tableRows = (1..12).map { listOf("Ion $it", "$it.5") })),
                    NewElement("pb", ElementType.PARAGRAPH, long, ElementData(pageBreakBefore = true)))),
            ThesisOp.AddElements(c2, null, listOf(h("h21", if (ar) "أخذ العينات" else "Sampling", 1), p("p21", long))),
            ThesisOp.AddSection(ThesisSection("sub", c2, SectionKind.SECTION, if (ar) "التحليل الإحصائي" else "Statistics", 0), null),
            ThesisOp.AddElements(c3, null, listOf(h("h31", if (ar) "التركيز" else "Concentrations", 1), p("p31", long)))
        ))
        return s
    }

    private fun render(name: String, s: ThesisSnapshot): ThesisPdfRenderer {
        val r = ThesisPdfRenderer(ThesisDocxWriter.builtInProfile())
        val out = File(dir, "$name.pdf")
        val pages = r.render(s, emptyList(), out)
        assertTrue(pages > 5)
        // Evidence: the table of contents page as an image.
        ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                val tocPage = (0 until pdf.pageCount).firstOrNull { it == tocPhysicalPage(s, r) - 1 } ?: return@use
                pdf.openPage(tocPage).use { page ->
                    val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    FileOutputStream(File(dir, "$name-toc.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
        return r
    }

    private fun tocPhysicalPage(s: ThesisSnapshot, r: ThesisPdfRenderer): Int {
        // The TOC follows the abstracts; its page is the one before the first heading after it.
        val firstAfter = ThesisToc.entries(s, ThesisStructure.numbering(s)).first { s.section(it.sectionId)?.kind?.preliminary == false }
        return (r.headingPages[firstAfter.key] ?: 2) - 1
    }

    /** Every TOC entry shows the page its heading is drawn on, and pages follow the reading order. */
    private fun assertTocMatches(s: ThesisSnapshot, r: ThesisPdfRenderer) {
        val entries = ThesisToc.entries(s, ThesisStructure.numbering(s))
        assertTrue(entries.size >= 12)
        var last = 0
        for (e in entries) {
            val drawn = r.headingPages[e.key]
            assertTrue("${e.text} not drawn", drawn != null)
            assertEquals("${e.text}: TOC page", drawn, r.tocPages[e.key])
            assertTrue("${e.text} on page $drawn before $last", drawn!! >= last)
            last = drawn
        }
        File(dir, "results.txt").appendText(entries.joinToString("\n", postfix = "\n\n") { "${it.level} ${it.text} -> ${r.headingPages[it.key]}" })
    }

    @Test
    fun englishThesis_tocPagesMatchTheHeadings_andFollowEdits() {
        val s = thesis()
        val r = render("toc-en", s)
        assertTocMatches(s, r)
        val before = r.headingPages.getValue("s:" + s.sections.filter { it.kind == SectionKind.CHAPTER }[1].id)
        // An edit adds pages to chapter 1: chapter 2 and its headings move, the TOC follows.
        val longer = thesis(paragraphs = 30)
        val r2 = render("toc-en-longer", longer)
        assertTocMatches(longer, r2)
        val after = r2.headingPages.getValue("s:" + longer.sections.filter { it.kind == SectionKind.CHAPTER }[1].id)
        assertTrue("chapter 2 did not move: $before -> $after", after > before)
    }

    @Test
    fun romanFrontMatter_mainTextStartsAfterThePreliminaryPages() {
        val s = thesis { it.copy(frontMatterRoman = true, tocDepth = 4) }
        val r = render("toc-roman", s)
        assertTocMatches(s, r)
        val intro = r.headingPages.getValue("s:" + s.sections.first { it.kind == SectionKind.INTRODUCTION }.id)
        assertEquals(intro, r.mainStartPage)
        assertTrue(intro > 5)
        assertEquals("1", ThesisToc.pageLabel(intro, r.mainStartPage, s.meta))
        assertEquals("ii", ThesisToc.pageLabel(2, r.mainStartPage, s.meta))
        // Level-4 heading listed with depth 4.
        assertTrue(r.tocPages.containsKey("e:h1111"))
    }

    @Test
    fun arabicThesis_rightToLeftTocMatches() {
        val s = thesis("ar")
        val r = render("toc-ar", s)
        assertTocMatches(s, r)
    }
}
