package com.example.template.docx

import com.example.template.domain.TemplateProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A university thesis template written as a real DOCX package (the XML Word writes): A4 with
 * margins and gutter, a logo and the university name in the header, a different first-page
 * header, a footer "Page N / M" made of PAGE and NUMPAGES fields, a page border, roman page
 * numbers, Normal and Heading styles (theme fonts, basedOn, bidi), a shaded table, an image,
 * two sections and footnotes. Every value must be read back, and what the PDF cannot draw
 * must be reported.
 */
class DocxTemplateAnalyzerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val logoBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)

    private val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
        """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
        """xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape""""

    private fun drawing(rid: String, cx: Long, cy: Long) =
        """<w:r><w:drawing><wp:inline><wp:extent cx="$cx" cy="$cy"/><a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed="$rid"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"""

    private fun template(
        borders: String = """<w:pgBorders w:offsetFrom="page"><w:top w:val="single" w:sz="12" w:space="24" w:color="1F3A5F"/><w:left w:val="double" w:sz="4" w:space="24" w:color="auto"/><w:bottom w:val="single" w:sz="12" w:space="24" w:color="1F3A5F"/><w:right w:val="single" w:sz="12" w:space="24" w:color="1F3A5F"/></w:pgBorders>""",
        evenOdd: Boolean = true,
        footerShape: Boolean = false
    ): File {
        val parts = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document $ns><w:body>""" +
                """<w:p><w:pPr><w:bidi/></w:pPr><w:r><w:t>المياه الجوفية في الجزائر مصدر أساسي للماء</w:t></w:r></w:p>""" +
                """<w:p>${drawing("rId20", 1828800, 914400)}</w:p>""" +
                """<w:tbl><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:color="7F7F7F"/></w:tblBorders></w:tblPr><w:tr><w:tc><w:tcPr><w:shd w:val="clear" w:fill="D9E2F3"/></w:tcPr><w:p><w:r><w:t>العنوان</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""" +
                """<w:p><w:r><w:br w:type="page"/></w:r></w:p>""" +
                """<w:p><w:pPr><w:sectPr><w:pgSz w:w="11906" w:h="16838"/></w:sectPr></w:pPr></w:p>""" +
                """<w:p><w:r><w:t>نص الفصل</w:t></w:r></w:p>""" +
                """<w:sectPr><w:headerReference w:type="default" r:id="rId10"/><w:headerReference w:type="first" r:id="rId11"/><w:footerReference w:type="default" r:id="rId12"/>""" +
                """<w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1134" w:bottom="1134" w:left="1701" w:header="708" w:footer="567" w:gutter="567"/>""" +
                borders + """<w:pgNumType w:fmt="lowerRoman" w:start="1"/><w:bidi/><w:titlePg/></w:sectPr></w:body></w:document>""",
            "word/_rels/document.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId10" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>""" +
                """<Relationship Id="rId11" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header2.xml"/>""" +
                """<Relationship Id="rId12" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>""" +
                """<Relationship Id="rId20" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image2.png"/></Relationships>""",
            "word/header1.xml" to """<?xml version="1.0"?><w:hdr $ns><w:p><w:pPr><w:jc w:val="center"/></w:pPr>${drawing("rId1", 914400, 457200)}<w:r><w:t xml:space="preserve">Université d'Alger </w:t></w:r><w:r><w:t>– Faculté des Sciences</w:t></w:r></w:p></w:hdr>""",
            "word/_rels/header1.xml.rels" to """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/></Relationships>""",
            "word/header2.xml" to """<?xml version="1.0"?><w:hdr $ns><w:p><w:pPr><w:jc w:val="left"/></w:pPr><w:r><w:t>Cover header</w:t></w:r></w:p></w:hdr>""",
            "word/footer1.xml" to """<?xml version="1.0"?><w:ftr $ns><w:p><w:pPr><w:jc w:val="right"/></w:pPr><w:r><w:t xml:space="preserve">Page </w:t></w:r>""" +
                """<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>3</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>""" +
                """<w:r><w:t xml:space="preserve"> / </w:t></w:r><w:fldSimple w:instr=" NUMPAGES "><w:r><w:t>9</w:t></w:r></w:fldSimple></w:p>""" +
                (if (footerShape) """<w:p><w:r><w:pict><v:rect style="width:10pt;height:10pt"/></w:pict></w:r></w:p>""" else "") + """</w:ftr>""",
            "word/styles.xml" to """<?xml version="1.0"?><w:styles $ns><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:asciiTheme="minorHAnsi" w:hAnsiTheme="minorHAnsi"/><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
                """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:pPr><w:bidi/><w:spacing w:before="0" w:after="120" w:line="360" w:lineRule="auto"/><w:ind w:firstLine="709"/><w:jc w:val="both"/></w:pPr><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:cs="Traditional Arabic"/><w:sz w:val="28"/></w:rPr></w:style>""" +
                """<w:style w:type="paragraph" w:styleId="Titre1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/></w:pPr><w:rPr><w:rFonts w:asciiTheme="majorHAnsi"/><w:b/><w:color w:val="1F3A5F"/><w:sz w:val="32"/></w:rPr></w:style>""" +
                """<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Titre1"/><w:rPr><w:sz w:val="30"/></w:rPr></w:style>""" +
                """<w:style w:type="paragraph" w:styleId="Caption"><w:name w:val="caption"/></w:style></w:styles>""",
            "word/theme/theme1.xml" to """<?xml version="1.0"?><a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:themeElements><a:clrScheme><a:accent1><a:srgbClr val="4472C4"/></a:accent1></a:clrScheme><a:fontScheme><a:majorFont><a:latin typeface="Cambria"/><a:cs typeface="Arial"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:cs typeface="Arial"/></a:minorFont></a:fontScheme></a:themeElements></a:theme>""",
            "word/settings.xml" to """<?xml version="1.0"?><w:settings $ns>${if (evenOdd) "<w:evenAndOddHeaders/>" else ""}</w:settings>""",
            "word/footnotes.xml" to """<?xml version="1.0"?><w:footnotes $ns><w:footnote w:type="separator" w:id="-1"/><w:footnote w:type="continuationSeparator" w:id="0"/><w:footnote w:id="1"><w:p><w:r><w:t>Note</w:t></w:r></w:p></w:footnote></w:footnotes>""",
            "word/numbering.xml" to """<?xml version="1.0"?><w:numbering $ns/>"""
        )
        val f = File(tmp.newFolder(), "university_template.docx")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, xml) in parts) { z.putNextEntry(ZipEntry(name)); z.write(xml.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            z.putNextEntry(ZipEntry("word/media/image1.png")); z.write(logoBytes); z.closeEntry()
            z.putNextEntry(ZipEntry("word/media/image2.png")); z.write(byteArrayOf(9, 9)); z.closeEntry()
        }
        return f
    }

    @Test
    fun pageSetup_margins_borders_andNumbering_areRead() {
        val p = DocxTemplateAnalyzer.analyze(template(), "t1", "University", "university_template.docx")
        assertEquals("DOCX", p.layoutSource)
        assertEquals("DOCX", p.originalFileType)
        // A4 (11906 x 16838 twips) in points.
        assertEquals(595, p.pageWidthPt)
        assertEquals(842, p.pageHeightPt)
        assertEquals(72, p.marginTopPt)
        assertEquals(57, p.marginBottomPt)
        // The gutter (28 pt) is added to the left margin.
        assertEquals(28, p.gutterPt)
        assertEquals(85 + 28, p.marginLeftPt)
        assertEquals(57, p.marginRightPt)
        assertTrue(p.differentFirstPage)
        assertTrue(p.differentOddEven)
        assertEquals("lowerRoman", p.pageNumberFormat)
        assertEquals(1, p.pageNumberStart)
        assertEquals("page", p.pageBorderOffsetFrom)
        val borders = p.pageBorders.split(';').associate { it.substringBefore(':') to it.split(':') }
        assertEquals(setOf("top", "left", "bottom", "right"), borders.keys)
        assertEquals(listOf("top", "single", "1.5", "24", "#1F3A5F"), borders["top"])
        assertEquals(listOf("left", "double", "0.5", "24", "#000000"), borders["left"])
        assertTrue(p.rtl)
        assertEquals(2, p.templateSections)
    }

    @Test
    fun headersAndFooters_text_alignment_logo_andPageFields() {
        val file = template()
        val p = DocxTemplateAnalyzer.analyze(file, "t1", "University", "university_template.docx")
        assertEquals("Université d'Alger – Faculté des Sciences", p.headerText)
        assertEquals("center", p.headerAlignment)
        assertEquals("Cover header", p.firstHeaderText)
        // The PAGE and NUMPAGES fields are kept as fields, not as the numbers cached in the file.
        assertEquals("Page {PAGE} / {NUMPAGES}", p.footerText)
        assertEquals("end", p.footerAlignment)
        assertEquals("footer-end", p.pageNumberPosition)
        assertTrue(p.showPageNumbers)
        // The header logo: its part, size in points (914400 EMU = 72 pt) and alignment.
        assertEquals("word/media/image1.png|72|36|center", p.headerImage)
        assertFalse(p.firstPageHasHeaderImage)
        assertArrayEquals(logoBytes, DocxTemplateAnalyzer.partBytes(file, "word/media/image1.png"))
    }

    @Test
    fun styles_withThemeFonts_basedOn_andParagraphSettings() {
        val p = DocxTemplateAnalyzer.analyze(template(), "t1", "University", "u.docx")
        assertEquals("Times New Roman", p.bodyFont)
        assertEquals("Traditional Arabic", p.complexScriptFont)
        assertEquals(14, p.baseFontSizePt)
        assertEquals("justify", p.bodyAlignment)
        assertEquals(1.5f, p.lineSpacing, 0.001f)
        assertEquals(6, p.paragraphSpacingPt)
        assertEquals(35, p.firstLineIndentPt)
        // Heading 1 (found by its name, not its localized id): theme major font, size, bold, color, centered.
        assertEquals("Cambria", p.headingFont)
        assertEquals(16, p.heading1SizePt)
        assertEquals(15, p.heading2SizePt)
        assertTrue(p.headingBold)
        assertEquals("#1F3A5F", p.headingColorHex)
        assertEquals("center", p.headingAlignment)
        assertTrue(p.templateHasCaptionStyle)
        assertTrue(p.templateHasLists)
    }

    @Test
    fun bodyElements_areCounted_andUnsupportedFeatures_reported() {
        val p = DocxTemplateAnalyzer.analyze(template(footerShape = true), "t1", "University", "u.docx")
        assertEquals(1, p.templateTables)
        assertEquals(1, p.templateImages)
        assertEquals(1, p.templatePageBreaks)
        assertEquals(1, p.templateFootnotes)
        assertTrue(p.tableDetected)
        assertEquals("#D9E2F3", p.tableHeaderBgHex)
        assertEquals("#7F7F7F", p.tableBorderHex)
        val warnings = p.extractionWarnings.split(';').toSet()
        assertTrue(warnings.toString(), DocxTemplateAnalyzer.Warn.ODD_EVEN in warnings)
        assertTrue(warnings.toString(), DocxTemplateAnalyzer.Warn.HEADER_SHAPES in warnings)
        assertTrue(warnings.toString(), "${DocxTemplateAnalyzer.Warn.SECTIONS}:2" in warnings)
        assertTrue(warnings.toString(), DocxTemplateAnalyzer.Warn.BODY_CONTENT in warnings)
        assertTrue(warnings.toString(), DocxTemplateAnalyzer.Warn.FOOTNOTES in warnings)
        // Without odd/even headers and shapes, those are not reported.
        val plain = DocxTemplateAnalyzer.analyze(template(evenOdd = false), "t2", "U", "u.docx").extractionWarnings
        assertFalse(plain, DocxTemplateAnalyzer.Warn.ODD_EVEN in plain)
        assertFalse(plain, DocxTemplateAnalyzer.Warn.HEADER_SHAPES in plain)
    }

    @Test
    fun decorativeBorderArt_isReported_andDrawnAsALine() {
        val p = DocxTemplateAnalyzer.analyze(template(borders = """<w:pgBorders><w:top w:val="apples" w:sz="20" w:space="10" w:color="FF0000"/></w:pgBorders>"""), "t", "U", "u.docx")
        assertEquals("top:single:2.5:10:#FF0000", p.pageBorders)
        assertEquals("text", p.pageBorderOffsetFrom)
        assertTrue(DocxTemplateAnalyzer.Warn.BORDER_ART in p.extractionWarnings)
    }

    @Test
    fun profile_survivesSaveAndReload() {
        val p = DocxTemplateAnalyzer.analyze(template(), "t1", "University", "university_template.docx")
        val back = TemplateProfile.fromJson(p.toJson())
        assertEquals(p, back)
    }

    @Test
    fun aFileThatIsNotADocx_givesDefaults_withAWarning() {
        val f = tmp.newFile("broken.docx").apply { writeText("not a zip") }
        val p = DocxTemplateAnalyzer.analyze(f, "t", "Broken", "broken.docx")
        assertEquals("DOCX", p.originalFileType)
        assertEquals(DocxTemplateAnalyzer.Warn.NOT_READABLE, p.extractionWarnings)
        assertEquals("", p.headerText)
    }

    @Test
    fun aTemplateWithADoctype_isNotExpanded() {
        // External entities are never resolved (the template comes from outside the app).
        val f = tmp.newFile("xxe.docx")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write("""<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x SYSTEM "file:///etc/hostname">]><w:document $ns><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
            z.closeEntry()
        }
        val p = DocxTemplateAnalyzer.analyze(f, "t", "X", "x.docx")
        assertEquals(DocxTemplateAnalyzer.Warn.NOT_READABLE, p.extractionWarnings)
    }

    /**
     * The PDF export and the preview are drawn from one profile: a Word template gives them its
     * header text, header image (copied out of the template once), footer with page field,
     * borders and page setup.
     */
    @Test
    fun pdfAndPreviewProfile_ofAWordTemplate_carriesItsLayout() {
        val file = template()
        val p = DocxTemplateAnalyzer.analyze(file, "t1", "University", "u.docx")
        val resolved = com.example.thesis.export.ResolvedThesisTemplate(
            com.example.thesis.export.ThesisTemplate.DocxFile(file, p, "University"), null, com.example.offline.document.TemplateSource.EXPLICIT, emptyList())
        val pdf = com.example.thesis.export.ThesisTemplateResolver.pdfProfile(resolved)
        assertEquals(p.headerText, pdf.headerText)
        assertEquals("Page {PAGE} / {NUMPAGES}", pdf.footerText)
        assertEquals(p.pageBorders, pdf.pageBorders)
        assertEquals(595 to 842, pdf.pageWidthPt to pdf.pageHeightPt)
        assertEquals("footer-end", pdf.pageNumberPosition)
        val image = pdf.headerImage.split('|')
        assertEquals(listOf("72", "36", "center"), image.drop(1))
        assertArrayEquals(logoBytes, File(image[0]).readBytes())
        // A template without a footer still gets page numbers, as in the Word export.
        val noFooter = p.copy(footerText = "", pageNumberPosition = "")
        val pdf2 = com.example.thesis.export.ThesisTemplateResolver.pdfProfile(resolved.copy(template = com.example.thesis.export.ThesisTemplate.DocxFile(file, noFooter, "U")))
        assertEquals("footer-center", pdf2.pageNumberPosition)
    }
}
