package com.example.thesis

import com.example.document.docx.DocxEngine
import com.example.document.model.WordDocumentModel
import com.example.document.model.WordSection
import com.example.template.domain.TemplateProfile
import com.example.template.pdf.PdfTemplateAnalyzer
import com.example.template.pdf.TestPdf
import com.example.thesis.core.NewElement
import com.example.thesis.core.ThesisOp
import com.example.thesis.core.ThesisOperations
import com.example.thesis.core.ThesisStructure
import com.example.thesis.export.ThesisDocxWriter
import com.example.thesis.export.ThesisTemplate
import com.example.thesis.model.AcademicLevel
import com.example.thesis.model.ElementData
import com.example.thesis.model.ElementType
import com.example.thesis.model.SectionKind
import com.example.thesis.model.ThesisAsset
import com.example.thesis.model.ThesisMeta
import com.example.thesis.model.ThesisSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** Real DOCX output of a thesis (pure JVM): structure, fields, images, tables, templates, RTL, size. */
class ThesisDocxExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A valid 2x1 PNG. */
    private val png: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAEUlEQVR4nGP4z8DwHwQZGBgAJ+wE/IyKaXwAAAAASUVORK5CYII="
    )

    private fun asset(id: String): ThesisAsset {
        val f = tmp.newFile("$id.png").apply { writeBytes(png) }
        return ThesisAsset(id, "$id.png", f.path, 200, 100, "image/png")
    }

    private fun thesis(language: String = "en"): Pair<ThesisSnapshot, List<ThesisAsset>> {
        val meta = ThesisMeta(
            title = if (language == "ar") "جودة المياه الجوفية" else "Groundwater quality in the Saïss basin",
            level = AcademicLevel.MASTER, language = language, studentName = "Amina B.", university = "University of Fès",
            supervisor = "Pr. K. Idrissi", academicYear = "2025-2026", juryMembers = "Pr. A\nDr. B", logoAssetId = "logo"
        )
        var s = ThesisSnapshot(meta, ThesisStructure.defaultSections(language, AcademicLevel.MASTER), emptyList())
        val intro = s.sections.first { it.kind == SectionKind.INTRODUCTION }.id
        val ch1 = s.sections.first { it.kind == SectionKind.CHAPTER }.id
        val introText = if (language == "ar") "تعتمد المنطقة على المياه الجوفية بنسبة 70٪." else "The region relies on groundwater for 70% of its needs (Smith, 2019)."
        s = ThesisOperations.apply(s, listOf(
            ThesisOp.UpdateSection(ch1, title = if (language == "ar") "الإطار العام" else "Study area"),
            ThesisOp.AddElements(intro, null, listOf(NewElement("p1", ElementType.PARAGRAPH, introText))),
            ThesisOp.AddElements(ch1, null, listOf(
                NewElement("h1", ElementType.HEADING, "Location", ElementData(headingLevel = 1)),
                NewElement("p2", ElementType.PARAGRAPH, "See [[fig:img1]] and [[tab:tab1]]."),
                NewElement("img1", ElementType.IMAGE, "", ElementData(assetId = "a1", caption = "Location map", widthPercent = 70)),
                NewElement("l1", ElementType.LIST_ITEM, "Wells: 12"),
                NewElement("tab1", ElementType.TABLE, "", ElementData(caption = "Major ions", tableHeader = listOf("Ion", "mg/L"), tableRows = listOf(listOf("Ca", "12.5"), listOf("Mg", "4.1"))))
            )),
            ThesisOp.AddElements(s.sections.first { it.kind == SectionKind.REFERENCES }.id, null, listOf(NewElement("r1", ElementType.REFERENCE, "Smith, J. (2019). Aquifers. Springer.")))
        ))
        return s to listOf(asset("a1"), asset("logo"))
    }

    private fun zip(f: File, name: String) = ZipFile(f).use { z -> z.getEntry(name)?.let { String(z.getInputStream(it).readBytes()) } }

    private fun assertWellFormed(xml: String) {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(xml.byteInputStream())
    }

    private fun texts(xml: String) = Regex("""<w:t(?:\s[^>]*)?>([^<]*)</w:t>""").findAll(xml).joinToString("\n") { it.groupValues[1] }

    @Test
    fun builtInProfile_structureFieldsImagesTablesAndPageNumbers() {
        val (s, assets) = thesis()
        val out = tmp.newFile("t.docx")
        val report = ThesisDocxWriter().write(s, assets, ThesisTemplate.Profile(ThesisDocxWriter.builtInProfile(), null), out)
        val doc = zip(out, "word/document.xml")!!
        listOf("word/document.xml", "word/styles.xml", "word/settings.xml", "word/footer1.xml", "[Content_Types].xml", "word/_rels/document.xml.rels")
            .forEach { assertWellFormed(zip(out, it)!!) }
        val t = texts(doc)
        // Cover: only what the user entered.
        assertTrue(t.contains("University of Fès"))
        assertTrue(t.contains("Master's Thesis"))
        assertTrue(t.contains("Presented by: Amina B."))
        assertTrue(t.contains("Academic year: 2025-2026"))
        // Real index fields (updated by the word processor) and SEQ captions.
        assertTrue(doc.contains("TOC \\o &quot;1-3&quot;") || doc.contains("TOC \\o \"1-3\""))
        assertTrue(doc.contains("TOC \\h \\z \\c \"Figure\""))
        assertTrue(doc.contains("TOC \\h \\z \\c \"Table\""))
        assertTrue(doc.contains("SEQ Figure \\* ARABIC"))
        assertTrue(doc.contains("SEQ Table \\* ARABIC"))
        assertTrue(zip(out, "word/settings.xml")!!.contains("updateFields"))
        assertTrue(zip(out, "word/footer1.xml")!!.contains("PAGE"))
        // Headings with computed numbers, cross references resolved.
        assertTrue(t.contains("Chapter 1: Study area"))
        assertTrue(t.contains("See Figure 1 and Table 1."))
        assertTrue(t.contains("Location map"))
        assertTrue(t.contains("12.5"))
        assertTrue(doc.contains("<w:pStyle w:val=\"Heading1\"/>"))
        // Images: the original bytes embedded unchanged (logo + figure).
        ZipFile(out).use { z ->
            val media = z.entries().toList().filter { it.name.startsWith("word/media/") }
            assertEquals(2, media.size)
            media.forEach { assertArrayEquals(png, z.getInputStream(it).readBytes()) }
        }
        assertEquals(1, report.figureCount)
        assertEquals(1, report.tableCount)
        assertTrue(report.warnings.isEmpty())
        assertTrue(doc.contains("<w:pgSz w:w=\"11900\" w:h=\"16840\"/>"))
    }

    @Test
    fun arabicThesis_isRightToLeft() {
        val (s, assets) = thesis("ar")
        val out = tmp.newFile("ar.docx")
        ThesisDocxWriter().write(s, assets, ThesisTemplate.Profile(ThesisDocxWriter.builtInProfile(), null), out)
        val doc = zip(out, "word/document.xml")!!
        assertWellFormed(doc)
        assertTrue(doc.contains("<w:bidi/></w:pPr>"))
        assertTrue(doc.contains("<w:bidi/><w:titlePg/></w:sectPr>"))
        assertTrue(doc.contains("<w:bidiVisual/>"))
        val t = texts(doc)
        assertTrue(t.contains("مقدمة عامة"))
        assertTrue(t.contains("الفصل 1: الإطار العام"))
        assertTrue(t.contains("الشكل "))
        assertTrue(t.contains("تعتمد المنطقة على المياه الجوفية بنسبة 70٪."))
    }

    @Test
    fun wordTemplate_keepsTemplatePartsAndIsNotModified() {
        val (s, assets) = thesis("fr")
        val template = tmp.newFile("tpl.docx")
        DocxEngine().generateDocx(WordDocumentModel(title = "Template", sections = listOf(WordSection("H", listOf("p")))), template)
        val before = template.readBytes()
        val out = tmp.newFile("fr.docx")
        val report = ThesisDocxWriter().write(s, assets, ThesisTemplate.DocxFile(template, TemplateProfile.defaultProfile(), "Uni"), out)
        assertArrayEquals(before, template.readBytes())
        val doc = zip(out, "word/document.xml")!!
        assertWellFormed(doc)
        assertWellFormed(zip(out, "word/styles.xml")!!)
        assertWellFormed(zip(out, "word/_rels/document.xml.rels")!!)
        assertWellFormed(zip(out, "[Content_Types].xml")!!)
        // The template's styles are kept, missing thesis styles are added.
        val styles = zip(out, "word/styles.xml")!!
        assertTrue(styles.contains("w:styleId=\"Heading1\""))
        assertTrue(styles.contains("w:styleId=\"Caption\""))
        assertTrue(texts(doc).contains("Introduction générale"))
        assertTrue(texts(doc).contains("Tableau "))
        // The template had no footer: a numbered footer was added and reported.
        assertTrue("template_footer_added" in report.warnings)
        assertTrue(doc.contains("xmlns:wp="))
    }

    @Test
    fun pdfTemplateProfile_appliesMeasuredPageAndHeader() {
        val (s, assets) = thesis()
        val pdf = tmp.newFile("tpl.pdf").apply { writeBytes(TestPdf.corporateTemplate()) }
        val profile = PdfTemplateAnalyzer.analyze(pdf).toProfile("p", "Uni PDF", "tpl.pdf")
        val out = tmp.newFile("pdfprofile.docx")
        ThesisDocxWriter().write(s, assets, ThesisTemplate.Profile(profile, "Uni PDF"), out)
        val doc = zip(out, "word/document.xml")!!
        assertTrue(doc.contains("w:left=\"1440\""))
        assertTrue(zip(out, "word/header1.xml")!!.contains("ACME Consulting Group"))
        assertTrue(zip(out, "word/styles.xml")!!.contains("Times"))
    }

    @Test
    fun largeThesis_isWrittenQuickly() {
        var s = ThesisSnapshot(ThesisMeta("Large"), ThesisStructure.defaultSections("en", AcademicLevel.DOCTORATE), emptyList())
        val ch = s.sections.first { it.kind == SectionKind.CHAPTER }.id
        s = ThesisOperations.apply(s, listOf(ThesisOp.AddElements(ch, null, (1..3000).map {
            NewElement("p$it", ElementType.PARAGRAPH, "Paragraph $it of a long doctoral dissertation with enough words to be realistic.")
        })))
        val out = tmp.newFile("large.docx")
        val start = System.nanoTime()
        ThesisDocxWriter().write(s, emptyList(), ThesisTemplate.Profile(ThesisDocxWriter.builtInProfile(), null), out)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("too slow: $ms ms", ms < 10_000)
        assertTrue(texts(zip(out, "word/document.xml")!!).contains("Paragraph 3000 of a long"))
    }

    @Test
    fun missingImage_isReportedNotHidden() {
        val (s, _) = thesis()
        val out = tmp.newFile("missing.docx")
        val report = ThesisDocxWriter().write(s, emptyList(), ThesisTemplate.Profile(ThesisDocxWriter.builtInProfile(), null), out)
        assertTrue(report.warnings.any { it.startsWith("image_missing") })
        assertTrue("logo_missing" in report.warnings)
        assertFalse(zip(out, "word/document.xml")!!.contains("r:embed"))
    }
}
