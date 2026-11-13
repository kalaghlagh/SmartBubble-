package com.example.template.pdf

import com.example.template.domain.TemplateProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Local PDF template analysis on real PDF files built by [TestPdf] (pure JVM, no mocks). */
class PdfTemplateAnalyzerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun analyze(bytes: ByteArray): PdfTemplateAnalysis {
        val file = tmp.newFile("t${System.nanoTime()}.pdf").apply { writeBytes(bytes) }
        return PdfTemplateAnalyzer.analyze(file)
    }

    @Test
    fun textTemplate_pageMarginsFontsColorsHeaderFooterTable() {
        val a = analyze(TestPdf.corporateTemplate())
        assertEquals(PdfTextStatus.TEXT, a.status)
        assertEquals(595.0, a.pageWidthPt, 0.01)
        assertEquals(842.0, a.pageHeightPt, 0.01)
        assertFalse(a.landscape)
        assertEquals(72.0, a.marginLeft, 1.0)
        assertEquals("Times", a.bodyFont)
        assertEquals(11.0, a.bodySize!!, 0.01)
        assertEquals("#000000", a.bodyColorHex)
        assertEquals("Helvetica", a.headingFont)
        assertEquals(listOf(20.0, 14.0), a.headingSizes.take(2))
        assertEquals("#1F4E79", a.headingColorHex)
        assertTrue(a.headingBold)
        assertEquals("justify", a.bodyAlignment)
        assertEquals(14.0 / 11.0, a.lineSpacing!!, 0.02)
        assertEquals("ACME Consulting Group", a.headerText)
        assertEquals("", a.footerText)
        assertEquals("footer-center", a.pageNumberPosition)
        assertEquals("•", a.listMarker)
        assertEquals(18.0, a.listIndent!!, 1.0)
        assertTrue(a.tableDetected)
        assertEquals("#D9E2F3", a.tableHeaderFillHex)
        assertEquals("#808080", a.tableBorderHex)
        assertTrue(a.decorations.any { it.colorHex == "#1F4E79" && it.top < 0.01 && it.width > 0.99 })
        assertFalse(a.rtl)
    }

    @Test
    fun compressedStreams_objectStreams_andSeveralPages() {
        val pdf = TestPdf(compress = true).apply { useObjectStream = true }
        pdf.page("BT /F1 12 Tf 72 700 Td (Compressed page one text body) Tj ET")
        pdf.page("BT /F1 12 Tf 72 700 Td (Compressed page two text body) Tj ET")
        val reader = PdfDocumentReader.open(pdf.build())
        assertEquals(2, reader.pages().size)
        assertEquals(2, reader.pageCount())
        val a = PdfTemplateAnalyzer.analyze(reader)
        assertEquals(12.0, a.bodySize!!, 0.01)

        // Multi-page: the repeated header is recognized on every page.
        val multi = analyze(TestPdf.corporateTemplate(compress = true, pages = 3))
        assertEquals(3, multi.pageCount)
        assertEquals("ACME Consulting Group", multi.headerText)
        assertEquals("footer-center", multi.pageNumberPosition)
    }

    @Test
    fun landscapeTemplate() {
        val a = analyze(TestPdf.corporateTemplate(landscape = true))
        assertTrue(a.landscape)
        assertEquals(842.0, a.pageWidthPt, 0.01)
    }

    @Test
    fun arabicTemplate_isRightToLeftAndRightAligned() {
        val a = analyze(TestPdf.arabicTemplate())
        assertTrue(a.rtl)
        assertTrue(a.textDecoded)
        assertEquals("Noto Naskh Arabic", a.bodyFont)
        // Right-aligned Arabic lines are "start" aligned in a right-to-left document.
        assertEquals("start", a.bodyAlignment)
        assertEquals(523.0, a.pageWidthPt - a.marginRight, 1.0)
        assertEquals("#006633", a.headingColorHex)
    }

    @Test
    fun scannedPdf_isDetectedAndNotTreatedAsEmpty() {
        val a = analyze(TestPdf.scannedPage())
        assertEquals(PdfTextStatus.NO_TEXT, a.status)
        assertTrue(a.imageOnly)
        assertEquals(595.0, a.pageWidthPt, 0.01)
        val profile = a.toProfile("id", "Scan", "scan.pdf")
        assertTrue(profile.isImageOnlyPdf)
        assertEquals(595, profile.pageWidthPt)
        assertEquals("", profile.headerText)
    }

    @Test
    fun encryptedPdf_isRefused() {
        val pdf = TestPdf().apply { encrypt = true }
        pdf.page("BT /F1 12 Tf 72 700 Td (Secret) Tj ET")
        try {
            analyze(pdf.build())
            fail("expected PdfEncryptedException")
        } catch (e: PdfEncryptedException) {
        }
    }

    @Test
    fun malformedFiles_failWithAReadError() {
        for (bytes in listOf("not a pdf at all".toByteArray(), "%PDF-1.4\n1 0 obj << /Type /Catalog".toByteArray(), ByteArray(0))) {
            try {
                analyze(bytes)
                fail("expected a read error")
            } catch (e: PdfReadException) {
            }
        }
        // Truncated real file: whatever remains readable is used, or a clear error is raised.
        val full = TestPdf.corporateTemplate()
        try {
            analyze(full.copyOf(full.size / 3))
        } catch (e: PdfReadException) {
        }
    }

    @Test
    fun largePdf_onlyFirstPagesAreAnalyzedAndLimitsHold() {
        val pdf = TestPdf(compress = true)
        repeat(200) { pdf.page("BT /F1 10 Tf 72 700 Td (Page $it of a long document with text) Tj ET") }
        val bytes = pdf.build()
        val start = System.nanoTime()
        val a = analyze(bytes)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(200, a.pageCount)
        assertTrue("analysis too slow: $ms ms", ms < 5_000)

        // A stream that decompresses beyond the limit is refused instead of exhausting memory.
        val bomb = TestPdf()
        val huge = ByteArray(PdfLimits.MAX_STREAM_BYTES + 1024) { ' '.code.toByte() }
        val num = bomb.stream("", huge, compressIt = true)
        bomb.page("BT /F1 10 Tf 72 700 Td (ok) Tj ET")
        val reader = PdfDocumentReader.open(bomb.build())
        try {
            reader.decode(reader.obj(num) as PdfValue.Stream)
            fail("expected the size limit")
        } catch (e: PdfReadException) {
        }
    }

    @Test
    fun profile_roundTripsThroughJson() {
        val a = analyze(TestPdf.corporateTemplate())
        val p = a.toProfile("pdf-1", "Corporate", "corporate.pdf")
        val back = TemplateProfile.fromJson(p.toJson())
        assertEquals(p.copy(supportedOutputTypes = back.supportedOutputTypes), back)
        assertTrue(back.isPdfLayout)
        assertEquals("corporate.pdf", back.sourceFileName)
        assertEquals("ACME Consulting Group", back.headerText)
        assertEquals(listOf("DOCX", "PDF", "XLSX"), p.supportedOutputTypes)
    }

    @Test
    fun existingProfilesKeepTheirJson() {
        // Office templates have no PDF fields: their stored JSON is unchanged by this feature.
        val docx = TemplateProfile.defaultProfile()
        assertFalse(docx.toJson().contains("layoutSource"))
        assertFalse(TemplateProfile.fromJson(docx.toJson()).isPdfLayout)
    }
}
